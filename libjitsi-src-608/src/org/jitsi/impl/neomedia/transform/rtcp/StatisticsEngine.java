/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.transform.rtcp;

import java.io.*;
import java.util.*;

import javax.media.control.*;
import javax.media.rtp.*;

import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.RTPHeader; //disambiguation
import net.sf.fmj.utility.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.control.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;

/**
 * Implements a <tt>TransformEngine</tt> monitors the incoming and outgoing RTCP
 * packets, logs and stores statistical data about an associated
 * <tt>MediaStream</tt>.
 *
 * @author Damian Minkov
 * @author Lyubomir Marinov
 * @author Boris Grozev
 */
public class StatisticsEngine
    extends SinglePacketTransformer
    implements TransformEngine
{
    /**
     * The <tt>Logger</tt> used by the <tt>StatisticsEngine</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(StatisticsEngine.class);

    /**
     * The RTP statistics prefix we use for every log.
     * Simplifies parsing and searching for statistics info in log files.
     */
    public static final String RTP_STAT_PREFIX = "rtpstat:";

    /**
     * Determines whether <tt>buf</tt> appears to contain an RTCP packet
     * starting at <tt>off</tt> and spanning at most <tt>len</tt> bytes. Returns
     * the length in bytes of the RTCP packet if it was determined that there
     * indeed appears to be such an RTCP packet; otherwise, <tt>-1</tt>.
     *
     * @param buf
     * @param off
     * @param len
     * @return the length in bytes of the RTCP packet in <tt>buf</tt> starting
     * at <tt>off</tt> and spanning at most <tt>len</tt> bytes if it was
     * determined that there indeed appears to be such an RTCP packet;
     * otherwise, <tt>-1</tt>
     */
    private static int getLengthIfRTCP(byte[] buf, int off, int len)
    {
        if ((off >= 0)
                && (len >= 4)
                && (buf != null)
                && (buf.length >= (off + len)))
        {
            int v = (buf[off] & 0xc0) >>> 6;

            if (v == RTCPHeader.VERSION)
            {
                int words = (buf[off + 2] << 8) + (buf[off + 3] << 0);
                int bytes = (words + 1) * 4;

                if (bytes <= len)
                    return bytes;
            }
        }
        return -1;
    }

    /**
     * Determines whether a specific <tt>RawPacket</tt> appears to represent an
     * RTCP packet.
     *
     * @param pkt the <tt>RawPacket</tt> to be examined
     * @return <tt>true</tt> if the specified <tt>pkt</tt> appears to represent
     * an RTCP packet
     */
    private static boolean isRTCP(RawPacket pkt)
    {
        return
            getLengthIfRTCP(pkt.getBuffer(), pkt.getOffset(), pkt.getLength())
                > 0;
    }

    /**
     * Removes any RTP Control Protocol Extended Report (RTCP XR) packets from
     * <tt>pkt</tt>.
     *
     * @param pkt the <tt>RawPacket</tt> from which any RTCP XR packets are to
     * be removed
     * @return a list of <tt>RTCPExtendedReport</tt> packets removed from
     * <tt>pkt</tt> or <tt>null</tt> or an empty list if no RTCP XR packets were
     * removed from <tt>pkt</tt>
     */
    private static List<RTCPExtendedReport> removeRTCPExtendedReports(
            RawPacket pkt)
    {
        int off = pkt.getOffset();
        List<RTCPExtendedReport> rtcpXRs = null;

        // TODO(gp) maybe move the parsing into <tt>RTCPPacketParserEx</tt>
        do
        {
            /*
             * XXX RawPacket may shrink if an RTCP XR packet is removed. Such an
             * operation may (or may not) modify either of the buffer, offset,
             * and length properties of RawPacket. Ensure that the buf and end
             * values are in accord with pkt.
             */
            int end = pkt.getOffset() + pkt.getLength();

            if (off >= end)
                break;

            byte[] buf = pkt.getBuffer();
            int rtcpPktLen = getLengthIfRTCP(buf, off, end - off);

            if (rtcpPktLen <= 0) // Not an RTCP packet.
                break;

            int pt = 0xff & buf[off + 1];

            if (pt == RTCPExtendedReport.XR) // An RTCP XR packet.
            {
                RTCPExtendedReport rtcpXR;

                try
                {
                    rtcpXR = new RTCPExtendedReport(buf, off, rtcpPktLen);
                }
                catch (IOException ioe)
                {
                    // It looked like an RTCP XR packet but didn't parse.
                    rtcpXR = null;
                }
                if (rtcpXR == null)
                {
                    // It looked like an RTCP XR packet but didn't parse.
                    off += rtcpPktLen;
                }
                else
                {
                    // Remove the RTCP XR packet.
                    int tailOff = off + rtcpPktLen;
                    int tailLen = end - tailOff;

                    if (tailLen > 0)
                        System.arraycopy(buf, tailOff, buf, off, tailLen);

                    /*
                     * XXX RawPacket may be shrunk if an RTCP XR packet is
                     * removed. Such an operation may (or may not) modify either
                     * of the buffer, offset, and length properties of
                     * RawPacket. Ensure that the off value is in accord with
                     * pkt.
                     */
                    int oldOff = pkt.getOffset();

                    pkt.shrink(rtcpPktLen);

                    int newOff = pkt.getOffset();

                    off = off - oldOff + newOff;

                    // Return the (removed) RTCP XR packet.
                    if (rtcpXRs == null)
                        rtcpXRs = new LinkedList<RTCPExtendedReport>();
                    rtcpXRs.add(rtcpXR);
                }
            }
            else
            {
                // Not an RTCP XR packet.
                off += rtcpPktLen;
            }
        }
        while (true);
        return rtcpXRs;
    }

    /**
     * Number of lost packets reported.
     */
    private long lost = 0;

    /**
     * The minimum inter arrival jitter value we have reported, in RTP timestamp
     * units.
     */
    private long maxInterArrivalJitter = 0;

    /**
     * The stream created us.
     */
    private final MediaStreamImpl mediaStream;

    /**
     * The <tt>MediaType</tt> of {@link #mediaStream}. Cached for the purposes
     * of performance.
     */
    private final MediaType mediaType;

    /**
     * The minimum inter arrival jitter value we have reported, in RTP timestamp
     * units.
     */
    private long minInterArrivalJitter = -1;

    /**
     * The number of RTCP sender reports (SR) and/or receiver reports (RR) sent.
     */
    private long numberOfRTCPReports = 0;

    /**
     * The sum of the jitter values we have reported in RTCP reports, in RTP
     * timestamp units.
     */
    private long jitterSum = 0;

    /**
     * The number of RTP packets sent though this instance.
     */
    private long rtpPacketsSent = 0;

    /**
     * The number of RTP packets sent though this instance.
     */
    private long rtpPacketsReceived = 0;

    /**
     * The <tt>PacketTransformer</tt> instance to use for RTP. It only counts
     * packets.
     */
    private final PacketTransformer rtpTransformer
            = new SinglePacketTransformer()
    {
        @Override
        public RawPacket transform(RawPacket pkt)
        {
            if (pkt != null && pkt.getVersion() == RTPHeader.VERSION)
                StatisticsEngine.this.rtpPacketsSent++;
            return pkt;
        }

        @Override
        public RawPacket reverseTransform(RawPacket pkt)
        {
            if (pkt != null && pkt.getVersion() == RTPHeader.VERSION)
                StatisticsEngine.this.rtpPacketsReceived++;
            return pkt;
        }
    };

    /**
     * Creates Statistic engine.
     * @param stream the stream creating us.
     */
    public StatisticsEngine(MediaStreamImpl stream)
    {
        this.mediaStream = stream;

        mediaType = this.mediaStream.getMediaType();
    }

    /**
     * Adds a specific RTCP XR packet into <tt>pkt</tt>.
     *
     * @param pkt the <tt>RawPacket</tt> into which <tt>extendedReport</tt> is
     * to be added
     * @param extendedReport the RTCP XR packet to add into <tt>pkt</tt>
     * @return <tt>true</tt> if <tt>extendedReport</tt> was added into
     * <tt>pkt</tt>; otherwise, <tt>false</tt>
     */
    private boolean addRTCPExtendedReport(
            RawPacket pkt,
            RTCPExtendedReport extendedReport)
    {
        /*
         * Find an offset within pkt at which the specified RTCP XR packet is to
         * be added. According to RFC 3550, it should not follow an RTCP BYE
         * packet with matching SSRC.
         */
        byte[] buf = pkt.getBuffer();
        int off = pkt.getOffset();
        int end = off + pkt.getLength();

        while (off < end)
        {
            int rtcpPktLen = getLengthIfRTCP(buf, off, end - off);

            if (rtcpPktLen <= 0) // Not an RTCP packet.
                break;

            int pt = 0xff & buf[off + 1]; // payload type (PT)
            boolean before = false;

            if (pt == RTCPPacket.BYE)
            {
                int sc = 0x1f & buf[off]; // source count

                if ((sc < 0) || (rtcpPktLen < ((1 + sc) * 4)))
                {
                    /*
                     * If the packet is not really an RTCP BYE, then we should
                     * better add the RTCP XR before a chunk of bytes that we do
                     * not fully understand.
                     */
                    before = true;
                }
                else
                {
                    for (int i = 0, ssrcOff = off + 4;
                            i < sc;
                            ++i, ssrcOff += 4)
                    {
                        if (RTPTranslatorImpl.readInt(buf, ssrcOff)
                                == extendedReport.getSSRC())
                        {
                            before = true;
                            break;
                        }
                    }
                }
            }

            if (before)
                break;
            else
                off += rtcpPktLen;
        }

        boolean added = false;

        if (off <= end)
        {
            // Make room within pkt for extendedReport.
            int extendedReportLen = extendedReport.calcLength();
            int oldOff = pkt.getOffset();

            pkt.grow(extendedReportLen);

            int newOff = pkt.getOffset();

            buf = pkt.getBuffer();
            off = off - oldOff + newOff;
            end = newOff + pkt.getLength();

            if (off < end)
            {
                System.arraycopy(
                        buf, off,
                        buf, off + extendedReportLen,
                        end - off);
            }

            // Write extendedReport into pkt.
            DataOutputStream dataoutputstream
                = new DataOutputStream(
                        new ByteBufferOutputStream(
                                buf,
                                off,
                                extendedReportLen));

            try
            {
                extendedReport.assemble(dataoutputstream);
                added = (dataoutputstream.size() == extendedReportLen);
            }
            catch (IOException e)
            {
            }
            if (added)
            {
                pkt.setLength(pkt.getLength() + extendedReportLen);
            }
            else if (off < end)
            {
                // Reclaim the room within pkt for extendedReport.
                System.arraycopy(
                        buf, off + extendedReportLen,
                        buf, off,
                        end - off);
            }
        }

        return added;
    }

    /**
     * Adds RTP Control Protocol Extended Report (RTCP XR) packets to
     * <tt>pkt</tt> if <tt>pkt</tt> contains RTCP SR or RR packets.
     *
     * @param pkt the <tt>RawPacket</tt> to which RTCP XR packets are to be
     * added
     * @param sdpParams
     * @return a list of <tt>RTCPExtendedReport</tt> packets added to
     * <tt>pkt</tt> or <tt>null</tt> or an empty list if no RTCP XR packets were
     * added to <tt>pkt</tt>
     */
    private List<RTCPExtendedReport> addRTCPExtendedReports(
            RawPacket pkt,
            String sdpParams)
    {
        /*
         * Create an RTCP XR packet for each RTCP SR or RR packet. Afterwards,
         * add the newly created RTCP XR packets into pkt.
         */
        byte[] buf = pkt.getBuffer();
        int off = pkt.getOffset();
        List<RTCPExtendedReport> rtcpXRs = null;

        for (int end = off + pkt.getLength(); off < end;)
        {
            int rtcpPktLen = getLengthIfRTCP(buf, off, end - off);

            if (rtcpPktLen <= 0) // Not an RTCP packet.
                break;

            int pt = 0xff & buf[off + 1]; // payload type (PT)

            if ((pt == RTCPPacket.RR) || (pt == RTCPPacket.SR))
            {
                int rc = 0x1f & buf[off]; // reception report count

                if (rc >= 0)
                {
                    /*
                     * Does the packet still look like an RTCP packet of the
                     * advertised packet type (PT)? 
                     */
                    int minRTCPPktLen = (2 + rc * 6) * 4;
                    int receptionReportBlockOff = off + 2 * 4;

                    if (pt == RTCPPacket.SR)
                    {
                        minRTCPPktLen += 5 * 4;
                        receptionReportBlockOff += 5 * 4;
                    }
                    if (rtcpPktLen < minRTCPPktLen)
                    {
                        rtcpXRs = null; // Abort, not an RTCP RR or SR packet.
                        break;
                    }

                    int senderSSRC = RTPTranslatorImpl.readInt(buf, off + 4);
                    /*
                     * Collect the SSRCs of the RTP data packet sources being
                     * reported upon by the RTCP RR/SR packet because they may
                     * be of concern to the RTCP XR packet (e.g. VoIP Metrics
                     * Report Block).
                     */
                    int[] sourceSSRCs = new int[rc];

                    for (int i = 0; i < rc; i++)
                    {
                        sourceSSRCs[i]
                            = RTPTranslatorImpl.readInt(
                                    buf,
                                    receptionReportBlockOff);
                        receptionReportBlockOff += 6 * 4;
                    }

                    // Initialize an RTCP XR packet.
                    RTCPExtendedReport rtcpXR
                        = createRTCPExtendedReport(
                                senderSSRC,
                                sourceSSRCs,
                                sdpParams);

                    if (rtcpXR != null)
                    {
                        if (rtcpXRs == null)
                            rtcpXRs = new LinkedList<RTCPExtendedReport>();
                        rtcpXRs.add(rtcpXR);
                    }
                }
                else
                {
                    rtcpXRs = null; // Abort, not an RTCP RR or SR packet.
                    break;
                }
            }

            off += rtcpPktLen;
        }

        // Add the newly created RTCP XR packets into pkt.
        if ((rtcpXRs != null) && !rtcpXRs.isEmpty())
        {
            for (RTCPExtendedReport rtcpXR : rtcpXRs)
                addRTCPExtendedReport(pkt, rtcpXR);
        }

        return rtcpXRs;
    }

    /**
     * Close the transformer and underlying transform engine.
     *
     * Nothing to do here.
     */
    @Override
    public void close()
    {
    }

    /**
     * Initializes a new RTP Control Protocol Extended Report (RTCP XR) packet.
     *
     * @param senderSSRC the synchronization source identifier (SSRC) of the
     * originator of the new RTCP XR packet
     * @param sourceSSRCs the SSRCs of the RTP data packet sources to be
     * reported upon by the new RTCP XR packet
     * @param sdpParams
     * @return a new RTCP XR packet with originator <tt>senderSSRC</tt> and
     * reporting upon <tt>sourceSSRCs</tt>
     */
    private RTCPExtendedReport createRTCPExtendedReport(
            int senderSSRC,
            int[] sourceSSRCs,
            String sdpParams)
    {
        RTCPExtendedReport xr = null;

        if ((sourceSSRCs != null)
                && (sourceSSRCs.length != 0)
                && (sdpParams != null)
                && sdpParams.contains(
                        RTCPExtendedReport.VoIPMetricsReportBlock
                            .SDP_PARAMETER))
        {
            xr = new RTCPExtendedReport();
            for (int sourceSSRC : sourceSSRCs)
            {
                RTCPExtendedReport.VoIPMetricsReportBlock reportBlock
                    = createVoIPMetricsReportBlock(senderSSRC, sourceSSRC);

                if (reportBlock != null)
                    xr.addReportBlock(reportBlock);
            }
            if (xr.getReportBlockCount() > 0)
            {
                xr.setSSRC(senderSSRC);
            }
            else
            {
                /*
                 * An RTCP XR packet with zero report blocks is fine, generally,
                 * but we see no reason to send such a packet.
                 */
                xr = null;
            }
        }
        return xr;
    }

    /**
     * Initializes a new RTCP XR &quot;VoIP Metrics Report Block&quot; as
     * defined by RFC 3611.
     *
     * @param senderSSRC
     * @param sourceSSRC the synchronization source identifier (SSRC) of the RTP
     * data packet source to be reported upon by the new instance
     * @return a new <tt>RTCPExtendedReport.VoIPMetricsReportBlock</tt> instance
     * reporting upon <tt>sourceSSRC</tt>
     */
    private RTCPExtendedReport.VoIPMetricsReportBlock
        createVoIPMetricsReportBlock(int senderSSRC, int sourceSSRC)
    {
        RTCPExtendedReport.VoIPMetricsReportBlock voipMetrics = null;

        if (MediaType.AUDIO.equals(mediaType))
        {
            ReceiveStream receiveStream
                = mediaStream.getReceiveStream(sourceSSRC);

            if (receiveStream != null)
            {
                voipMetrics
                    = createVoIPMetricsReportBlock(senderSSRC, receiveStream);
            }
        }
        return voipMetrics;
    }

    /**
     * Initializes a new RTCP XR &quot;VoIP Metrics Report Block&quot; as
     * defined by RFC 3611.
     *
     * @param senderSSRC
     * @param receiveStream the <tt>ReceiveStream</tt> to be reported upon by
     * the new instance
     * @return a new <tt>RTCPExtendedReport.VoIPMetricsReportBlock</tt> instance
     * reporting upon <tt>receiveStream</tt>
     */
    private RTCPExtendedReport.VoIPMetricsReportBlock
        createVoIPMetricsReportBlock(
                int senderSSRC,
                ReceiveStream receiveStream)
    {
        RTCPExtendedReport.VoIPMetricsReportBlock voipMetrics
            = new RTCPExtendedReport.VoIPMetricsReportBlock();

        voipMetrics.setSourceSSRC((int) receiveStream.getSSRC());

        // loss rate
        long expectedPacketCount = 0;
        ReceptionStats receptionStats = receiveStream.getSourceReceptionStats();
        double lossRate;

        if (receiveStream instanceof SSRCInfo)
        {
            SSRCInfo ssrcInfo = (SSRCInfo) receiveStream;

            expectedPacketCount = ssrcInfo.getExpectedPacketCount();
            if (expectedPacketCount > 0)
            {
                long lostPacketCount = receptionStats.getPDUlost();

                if ((lostPacketCount > 0)
                        && (lostPacketCount <= expectedPacketCount))
                {
                    /*
                     * RFC 3611 mentions that the total number of packets lost
                     * takes into account "the effects of applying any error
                     * protection such as FEC".
                     */
                    long fecDecodedPacketCount
                        = getFECDecodedPacketCount(receiveStream);

                    if ((fecDecodedPacketCount > 0)
                            && (fecDecodedPacketCount <= lostPacketCount))
                    {
                        lostPacketCount -= fecDecodedPacketCount;
                    }

                    lossRate
                        = (lostPacketCount / (double) expectedPacketCount)
                            * 256;
                    if (lossRate > 255)
                        lossRate = 255;
                    voipMetrics.setLossRate((short) lossRate);
                }
            }
        }

        // round trip delay
        if (receiveStream instanceof RecvSSRCInfo)
        {
            voipMetrics.setRoundTripDelay(
                    ((RecvSSRCInfo) receiveStream).getRoundTripDelay(
                            senderSSRC));
        }
        // end system delay
        /*
         * Defined as the sum of the total sample accumulation and encoding
         * delay associated with the sending direction and the jitter buffer,
         * decoding, and playout buffer delay associated with the receiving
         * direction. Collectively, these cover the whole path from the network
         * to the very playback of the audio. We cannot guarantee latency pretty
         * much anywhere along the path and, consequently, the metric will be
         * "extremely variable".
         */

        // signal level
        // noise level
        /*
         * The computation of noise level requires the notion of silent period
         * which we do not have (because, for example, we do not voice activity
         * detection).
         */
        
        // residual echo return loss (RERL)
        /*
         * WebRTC, which is available and default on OS X, appears to be able to
         * provide the residual echo return loss. Speex, which is available and
         * not default on the supported operating systems, and WASAPI, which is
         * available and default on Windows, do not seem to be able to provide
         * the metric. Taking into account the availability of the metric and
         * the distribution of the users according to operating system, the
         * support for the metric is estimated to be insufficiently useful.
         * Moreover, RFC 3611 states that RERL for "PC softphone or
         * speakerphone" is "extremely variable, consider reporting "undefined"
         * (127)".
         */

        // R factor
        /*
         * TODO Requires notions such as noise and noise sources, simultaneous
         * impairments, and others that we do not know how to define at the time
         * of this writing. 
         */
        // ext. R factor
        /*
         * The external R factor is a voice quality metric describing the
         * segment of the call that is carried over a network segment external
         * to the RTP segment, for example a cellular network. We implement the
         * RTP segment only and we do not have a notion of a network segment
         * external to the RTP segment.
         */
        // MOS-LQ
        /*
         * TODO It is unclear at the time of this writing from RFC 3611 how
         * MOS-LQ is to be calculated.
         */
        // MOS-CQ
        /*
         * The metric may be calculated by converting an R factor determined
         * according to ITU-T G.107 or ETSI TS 101 329-5 into an estimated MOS
         * using the equation specified in G.107. However, we do not have R
         * factor.
         */

        // receiver configuration byte (RX config)
        // packet loss concealment (PLC)
        /*
         * We insert silence in place of lost packets by default and we have FEC
         * and/or PLC for OPUS and SILK.
         */
        byte packetLossConcealment
            = RTCPExtendedReport.VoIPMetricsReportBlock
                .DISABLED_PACKET_LOSS_CONCEALMENT;
        MediaFormat mediaFormat = mediaStream.getFormat();

        if (mediaFormat != null)
        {
            String encoding = mediaFormat.getEncoding();

            if (encoding != null)
            {
                encoding = encoding.toLowerCase();
                if (Constants.OPUS_RTP.toLowerCase().contains(encoding)
                        || Constants.SILK_RTP.toLowerCase().contains(encoding))
                {
                    packetLossConcealment
                        = RTCPExtendedReport.VoIPMetricsReportBlock
                            .STANDARD_PACKET_LOSS_CONCEALMENT;
                }
            }
        }
        voipMetrics.setPacketLossConcealment(packetLossConcealment);

        // jitter buffer adaptive (JBA)
        JitterBufferControl jbc
            = MediaStreamStatsImpl.getJitterBufferControl(receiveStream);
        double discardRate;

        if (jbc == null)
        {
            voipMetrics.setJitterBufferAdaptive(
                    RTCPExtendedReport.VoIPMetricsReportBlock
                        .UNKNOWN_JITTER_BUFFER_ADAPTIVE);
        }
        else
        {
            // discard rate
            if (expectedPacketCount > 0)
            {
                int discardedPacketCount = jbc.getDiscarded();

                if ((discardedPacketCount > 0)
                        && (discardedPacketCount <= expectedPacketCount))
                {
                    discardRate
                        = (discardedPacketCount / (double) expectedPacketCount)
                            * 256;
                    if (discardRate > 255)
                        discardRate = 255;
                    voipMetrics.setDiscardRate((short) discardRate);
                }
            }

            // jitter buffer nominal delay (JB nominal)
            // jitter buffer maximum delay (JB maximum)
            // jitter buffer absolute maximum delay (JB abs max)
            int maximumDelay = jbc.getMaximumDelay();

            voipMetrics.setJitterBufferMaximumDelay(maximumDelay);
            voipMetrics.setJitterBufferNominalDelay(jbc.getNominalDelay());
            if (jbc.isAdaptiveBufferEnabled())
            {
                voipMetrics.setJitterBufferAdaptive(
                        RTCPExtendedReport.VoIPMetricsReportBlock
                            .ADAPTIVE_JITTER_BUFFER_ADAPTIVE);
                voipMetrics.setJitterBufferAbsoluteMaximumDelay(
                        jbc.getAbsoluteMaximumDelay());
            }
            else
            {
                voipMetrics.setJitterBufferAdaptive(
                        RTCPExtendedReport.VoIPMetricsReportBlock
                            .NON_ADAPTIVE_JITTER_BUFFER_ADAPTIVE);
                /*
                 * Jitter buffer absolute maximum delay (JB abs max) MUST be set
                 * to jitter buffer maximum delay (JB maximum) for fixed jitter
                 * buffer implementations.
                 */
                voipMetrics.setJitterBufferAbsoluteMaximumDelay(maximumDelay);
            }

            // jitter buffer rate (JB rate)
            /*
             * TODO We do not know how to calculate it at the time of this
             * writing. It very likely is to be calculated in
             * JitterBufferBehaviour because JitterBufferBehaviour implements
             * the adaptiveness of a jitter buffer implementation.
             */
        }

        if (receptionStats instanceof RTPStats)
        {
            // burst density
            // gap density
            // burst duration
            // gap duration
            RTPStats rtpStats = (RTPStats) receptionStats;
            BurstMetrics burstMetrics = rtpStats.getBurstMetrics();
            long l = burstMetrics.getBurstMetrics();
            int gapDuration, burstDuration;
            short gapDensity, burstDensity;

            gapDuration = (int) (l & 0xFFFFL);
            l >>= 16;
            burstDuration = (int) (l & 0xFFFFL);
            l >>= 16;
            gapDensity = (short) (l & 0xFFL);
            l >>= 8;
            burstDensity = (short) (l & 0xFFL);
            l >>= 8;
            discardRate = l & 0xFFL;
            l >>= 8;
            lossRate = l & 0xFFL;

            voipMetrics.setBurstDensity(burstDensity);
            voipMetrics.setGapDensity(gapDensity);
            voipMetrics.setBurstDuration(burstDuration);
            voipMetrics.setGapDuration(gapDuration);

            // Gmin
            voipMetrics.setGMin(burstMetrics.getGMin());
        }

        return voipMetrics;
    }

    /**
     * Gets the number of packets in a <tt>ReceiveStream</tt> which have been
     * decoded by means of FEC.
     *
     * @param receiveStream the <tt>ReceiveStream</tt> of which the number of
     * packets decoded by means of FEC is to be returned
     * @return the number of packets in <tt>receiveStream</tt> which have been
     * decoded by means of FEC
     */
    private long getFECDecodedPacketCount(ReceiveStream receiveStream)
    {
        MediaDeviceSession devSession = mediaStream.getDeviceSession();
        long fecDecodedPacketCount = 0;

        if (devSession != null)
        {
            Iterable<FECDecoderControl> decoderControls
                = devSession.getDecoderControls(
                        receiveStream,
                        FECDecoderControl.class);

            for (FECDecoderControl decoderControl : decoderControls)
                fecDecodedPacketCount += decoderControl.fecPacketsDecoded();
        }
        return fecDecodedPacketCount;
    }

    /**
     * Number of lost packets reported.
     * @return number of lost packets reported.
     */
    public long getLost()
    {
        return lost;
    }

    /**
     * The minimum inter arrival jitter value we have reported.
     * @return minimum inter arrival jitter value we have reported.
     */
    public long getMaxInterArrivalJitter()
    {
        return maxInterArrivalJitter;
    }

    /**
     * Gets the average value of the jitter reported in RTCP packets, in RTP
     * timestamp units.
     */
    public double getAvgInterArrivalJitter()
    {
        return numberOfRTCPReports == 0
                ? 0
                : ((double) jitterSum) / numberOfRTCPReports;
    }

    /**
     * The maximum inter arrival jitter value we have reported.
     * @return maximum inter arrival jitter value we have reported.
     */
    public long getMinInterArrivalJitter()
    {
        return minInterArrivalJitter;
    }

    /**
     * Returns a reference to this class since it is performing RTP
     * transformations in here.
     *
     * @return a reference to <tt>this</tt> instance of the
     * <tt>StatisticsEngine</tt>.
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return this;
    }

    /**
     * Always returns <tt>null</tt> since this engine does not require any
     * RTP transformations.
     *
     * @return <tt>null</tt> since this engine does not require any
     * RTP transformations.
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }

    /**
     * Initializes a new SR or RR <tt>RTCPReport</tt> instance from a specific
     * <tt>RawPacket</tt>.
     *
     * @param pkt the <tt>RawPacket</tt> to parse into a new SR or RR
     * <tt>RTCPReport</tt> instance
     * @return a new SR or RR <tt>RTCPReport</tt> instance initialized from the
     * specified <tt>pkt</tt>
     * @throws IOException if an I/O error occurs while parsing the specified
     * <tt>pkt</tt> into a new SR or RR <tt>RTCPReport</tt> instance
     */
    private RTCPReport parseRTCPReport(RawPacket pkt)
        throws IOException
    {
        switch (pkt.getRTCPPacketType())
        {
        case RTCPPacket.RR:
            return
                new RTCPReceiverReport(
                        pkt.getBuffer(),
                        pkt.getOffset(),
                        pkt.getLength());
        case RTCPPacket.SR:
            return
                new RTCPSenderReport(
                        pkt.getBuffer(),
                        pkt.getOffset(),
                        pkt.getLength());
        default:
            return null;
        }
    }

    /**
     * Transfers RTCP sender report feedback as new information about the upload
     * stream for the <tt>MediaStreamStats</tt>. Returns the packet as we are
     * listening just for sending packages.
     *
     * @param pkt the packet to reverse-transform
     * @return the packet which is the result of the reverse-transform
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        // SRTP may send non-RTCP packets.
        if (isRTCP(pkt))
        {
            /*
             * Remove any RTP Control Protocol Extended Report (RTCP XR) packets
             * because neither FMJ, nor RTCPSenderReport/RTCPReceiverReport
             * understands them.
             */
            List<RTCPExtendedReport> xrs = removeRTCPExtendedReports(pkt);

            // The pkt may have contained RTCP XR packets only.
            if (isRTCP(pkt))
            {
                try
                {
                    updateReceivedMediaStreamStats(pkt);
                }
                catch(Throwable t)
                {
                    if (t instanceof InterruptedException)
                    {
                        Thread.currentThread().interrupt();
                    }
                    else if (t instanceof ThreadDeath)
                    {
                        throw (ThreadDeath) t;
                    }
                    else
                    {
                        logger.error(
                                "Failed to analyze an incoming RTCP packet for"
                                    + " the purposes of statistics.",
                                t);
                    }
                }
            }
            else
            {
                // The pkt contained RTCP XR packets only.
                pkt = null;
            }

            // RTCP XR
            if (xrs != null)
            {
                RTCPReports rtcpReports
                    = mediaStream.getMediaStreamStats().getRTCPReports();

                for (RTCPExtendedReport xr : xrs)
                    rtcpReports.rtcpExtendedReportReceived(xr);
            }
        }
        return pkt;
    }

    /**
     * Transfers RTCP sender report feedback as new information about the
     * download stream for the MediaStreamStats. Finds the info needed for
     * statistics in the packet and stores it, then returns the same packet as
     * <tt>StatisticsEngine</tt> is not modifying it.
     *
     * @param pkt the packet to transform
     * @return the packet which is the result of the transform
     */
    @Override
    public RawPacket transform(RawPacket pkt)
    {
        // SRTP may send non-RTCP packets.
        if (isRTCP(pkt))
        {
            try
            {
                updateSentMediaStreamStats(pkt);
            }
            catch(Throwable t)
            {
                if (t instanceof InterruptedException)
                {
                    Thread.currentThread().interrupt();
                }
                else if (t instanceof ThreadDeath)
                {
                    throw (ThreadDeath) t;
                }
                else
                {
                    logger.error(
                            "Failed to analyze an outgoing RTCP packet for the"
                                + " purposes of statistics.",
                            t);
                }
            }

            // RTCP XR
            /*
             * We support RTCP XR VoIP Metrics Report Block only at the time of
             * this writing. While the methods addRTCPExtendedReports(RawPacket)
             * and createVoIPMetricsReportBlock(int) are aware of the fact, it
             * does not make sense to even go there for the sake of performance.
             */
            if (MediaType.AUDIO.equals(mediaType))
            {
                /*
                 * We will send RTCP XR only if the SDP attribute rtcp-xr is
                 * present and we will send only XR blocks indicated by SDP
                 * parameters.
                 */
                Object o
                    = mediaStream.getProperty(RTCPExtendedReport.SDP_ATTRIBUTE);

                if (o != null)
                {
                    String sdpParams = o.toString();

                    if ((sdpParams != null) && (sdpParams.length() != 0))
                    {
                        List<RTCPExtendedReport> xrs
                            = addRTCPExtendedReports(pkt, sdpParams);

                        if (xrs != null)
                        {
                            RTCPReports rtcpReports
                                = mediaStream
                                    .getMediaStreamStats()
                                        .getRTCPReports();

                            for (RTCPExtendedReport xr : xrs)
                                rtcpReports.rtcpExtendedReportSent(xr);
                        }
                    }
                }
            }
        }
        return pkt;
    }

    /**
     * Transfers RTCP sender/receiver report feedback as new information about
     * the upload stream for the <tt>MediaStreamStats</tt>.
     *
     * @param pkt the received RTCP packet
     */
    private void updateReceivedMediaStreamStats(RawPacket pkt)
        throws Exception
    {
        RTCPReport r = parseRTCPReport(pkt);

        if (r != null)
        {
            mediaStream.getMediaStreamStats().getRTCPReports()
                .rtcpReportReceived(r);
        }
    }

    /**
     * Transfers RTCP sender/receiver report feedback as new information about
     * the download stream for the <tt>MediaStreamStats</tt>.
     *
     * @param pkt the sent RTCP packet
     */
    private void updateSentMediaStreamStats(RawPacket pkt)
        throws Exception
    {
        RTCPReport r = parseRTCPReport(pkt);

        if (r != null)
        {
            mediaStream.getMediaStreamStats().getRTCPReports().rtcpReportSent(
                    r);

            List<?> feedbackReports = r.getFeedbackReports();

            if(!feedbackReports.isEmpty())
            {
                RTCPFeedback feedback
                        = (RTCPFeedback) feedbackReports.get(0);
                long jitter = feedback.getJitter();

                numberOfRTCPReports++;

                if ((jitter < getMinInterArrivalJitter())
                        || (getMinInterArrivalJitter() == -1))
                    minInterArrivalJitter = jitter;
                if (getMaxInterArrivalJitter() < jitter)
                    maxInterArrivalJitter = jitter;

                jitterSum += jitter;

                lost = feedback.getNumLost();

                if(logger.isTraceEnabled())
                {
                    // As sender reports are sent on every 5 seconds, print
                    // every 4th packet, on every 20 seconds.
                    if(numberOfRTCPReports % 4 == 1)
                    {
                        StringBuilder buff = new StringBuilder(RTP_STAT_PREFIX);
                        String mediaTypeStr
                            = (mediaType == null) ? "" : mediaType.toString();

                        buff.append("Sending a report for ")
                                .append(mediaTypeStr)
                            .append(" stream SSRC:").append(feedback.getSSRC())
                            .append(" [");
                        // SR includes sender info, RR does not.
                        if (r instanceof RTCPSenderReport)
                        {
                            RTCPSenderReport sr = (RTCPSenderReport) r;

                            buff.append("packet count:")
                                    .append(sr.getSenderPacketCount())
                                .append(", bytes:")
                                    .append(sr.getSenderByteCount())
                                .append(", ");
                        }
                        buff.append("interarrival jitter:").append(jitter)
                            .append(", lost packets:")
                                .append(feedback.getNumLost())
                            .append(", time since previous report:")
                                .append((int) (feedback.getDLSR() / 65.536))
                            .append("ms]");
                        logger.trace(buff);
                    }
                }
            }
        }
    }

    /**
     * Gets the number of RTP packets sent though this instance.
     * @return the number of RTP packets sent though this instance.
     */
    public long getRtpPacketsSent()
    {
        return rtpPacketsSent;
    }

    /**
     * Gets the number of RTP packets received though this instance.
     * @return the number of RTP packets received though this instance.
     */
    public long getRtpPacketsReceived()
    {
        return rtpPacketsReceived;
    }
}
