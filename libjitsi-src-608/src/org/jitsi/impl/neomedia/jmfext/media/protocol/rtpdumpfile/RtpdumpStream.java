/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */

package org.jitsi.impl.neomedia.jmfext.media.protocol.rtpdumpfile;

import javax.media.*;
import javax.media.control.*;
import javax.media.format.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.jmfext.media.protocol.*;
import org.jitsi.util.*;

import java.io.*;

/**
 * Implements a <tt>PullBufferStream</tt> which read an rtpdump file to generate
 * a RTP stream from the payloads recorded in a rtpdump file.
 * 
 * @author Thomas Kuntz
 */
public class RtpdumpStream
    extends AbstractVideoPullBufferStream<DataSource>
{
    /**
     * The <tt>Logger</tt> used by <tt>RtpdumpStream</tt> and its instances
     * for logging output.
     */
    private static final Logger logger
            = Logger.getLogger(RtpdumpStream.class);

    /**
     * The RTP clock rate, used to interpret the RTP timestamps read from the
     * file.
     */
    private final long CLOCK_RATE;

    /**
     * The timestamp of the last rtp packet (the timestamp change only when
     * a marked packet has been sent).
     */
    private long lastRtpTimestamp = -1;

    /**
     * Boolean indicating if the last call to <tt>doRead</tt> return a marked
     * rtp packet (to know if <tt>timestamp</tt> needs to be updated).
     */
    private boolean lastReadWasMarked = true;

    /**
     * The <tt>RtpdumpFileReader</tt> used by this stream to get the rtp payload.
     */
    private RtpdumpFileReader rtpFileReader;

    /**
     * The timestamp to use for the timestamp of the next <tt>Buffer</tt> filled
     * in {@link #doRead(javax.media.Buffer)}
     */
    private long timestamp;

    /**
     * Initializes a new <tt>RtpdumpStream</tt> instance
     *
     * @param dataSource the <tt>DataSource</tt> which is creating the new
     * instance so that it becomes one of its <tt>streams</tt>
     * @param formatControl the <tt>FormatControl</tt> of the new instance which
     * is to specify the format in which it is to provide its media data
     */
    RtpdumpStream(DataSource dataSource, FormatControl formatControl)
    {
        super(dataSource, formatControl);

        /*
         * NOTE: We use the sampleRate or frameRate field of the format to
         * piggyback the RTP clock rate. See
         * RtpdumpMediaDevice#createRtpdumpMediaDevice.
         */
        Format format = getFormat();
        if (format instanceof AudioFormat)
        {
            CLOCK_RATE = (long) ((AudioFormat) format).getSampleRate();
        }
        else if (format instanceof VideoFormat)
        {
            CLOCK_RATE = (long) ((VideoFormat) format).getFrameRate();
        }
        else
        {
            logger.warn("Unknown format. Creating RtpdumpStream with clock" +
                                "rate 1 000 000 000.");
            CLOCK_RATE = 1000 * 1000 * 1000;
        }

        String rtpdumpFilePath = dataSource.getLocator().getRemainder();
        this.rtpFileReader = new RtpdumpFileReader(rtpdumpFilePath);
    }

    /**
     * Reads available media data from this instance into a specific
     * <tt>Buffer</tt>.
     *
     * @param buffer the <tt>Buffer</tt> to write the available media data
     * into
     * @throws IOException if an I/O error has prevented the reading of
     * available media data from this instance into the specified
     * <tt>Buffer</tt>
     */
    @Override
    protected void doRead(Buffer buffer)
        throws IOException
    {
        Format format;

        format = buffer.getFormat();
        if (format == null)
        {
            format = getFormat();
            if (format != null)
                buffer.setFormat(format);
        }

        RawPacket rtpPacket = rtpFileReader.getNextPacket(true);
        byte[] data = rtpPacket.getPayload(); 

        buffer.setData(data);
        buffer.setOffset(rtpPacket.getOffset());
        buffer.setLength(rtpPacket.getPayloadLength());

        buffer.setFlags(Buffer.FLAG_SYSTEM_TIME | Buffer.FLAG_LIVE_DATA);
        if(lastReadWasMarked)
        {
            timestamp = System.nanoTime();
        }
        lastReadWasMarked = rtpPacket.isPacketMarked();
        if(lastReadWasMarked)
        {
            buffer.setFlags(buffer.getFlags() | Buffer.FLAG_RTP_MARKER);
        }
        buffer.setTimeStamp(timestamp);

        if (lastRtpTimestamp == -1)
        {
            lastRtpTimestamp = 0xffffffffL & rtpPacket.getTimestamp();
            return;
        }

        long previous= lastRtpTimestamp;
        lastRtpTimestamp = 0xffffffffL & rtpPacket.getTimestamp();

        long rtpDiff = lastRtpTimestamp - previous;

        // rtpDiff < 0 can happen when the timestamps wrap at 2^32, or when
        // the rtpdump file loops. In the latter case, we don't want to sleep.
        //if (rtpDiff < 0)
        //    rtpDiff += 1L << 32; //rtp timestamps wrap at 2^32

        long nanos = (rtpDiff * 1000 * 1000 * 1000) / CLOCK_RATE;
        if (nanos > 0)
        {
            try
            {
                Thread.sleep(
                               nanos / 1000000,
                        (int) (nanos % 1000000));
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }
    }
}