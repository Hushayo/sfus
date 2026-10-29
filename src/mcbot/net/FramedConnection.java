package mcbot.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minecraft packet framing: length prefix, optional zlib compression, and the
 * VarInt primitives that everything else is built on.
 *
 * Two invariants matter here and both used to be missing:
 *
 *  - A frame length is attacker-controlled input. Without a ceiling, a single
 *    corrupt VarInt prefix asks the JVM for a multi-gigabyte array. The vanilla
 *    server refuses anything over 2 MiB (chunk packets are the largest legal
 *    ones); 8 MiB is generous and still bounded.
 *  - A compressed frame declares its uncompressed size. If the inflater
 *    produces less than declared, the frame is truncated and must fail loudly
 *    rather than hand a short buffer to a parser.
 */
public class FramedConnection {
    private static final boolean DEBUG = System.getenv("BOT_DEBUG") != null;

    /** Vanilla's protocol limit is 2 MiB; chunk packets are the big ones. */
    public static final int MAX_FRAME = 8 * 1024 * 1024;
    /** Hard ceiling on the declared uncompressed size of one frame. */
    public static final int MAX_INFLATED = 16 * 1024 * 1024;

    public DataInputStream in;
    public OutputStream out;
    public boolean compressionEnabled = false;
    public int compressionThreshold = 256;

    private Socket socket;
    private final Inflater inflater = new Inflater();
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);

    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(60000);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** Reads one logical packet body (packet id included), decompressed. */
    public byte[] readPacket() throws IOException {
        int packetLength = readVarInt(in);
        if (packetLength <= 0) {
            throw new IOException("illegal frame length " + packetLength);
        }
        if (packetLength > MAX_FRAME) {
            throw new IOException("frame too large: " + packetLength + " bytes");
        }
        byte[] frame = new byte[packetLength];
        in.readFully(frame);
        if (!compressionEnabled) return frame;

        PacketReader fr = new PacketReader(frame);
        int dataLength = fr.readVarInt();
        if (dataLength == 0) {
            return fr.readBytes(frame.length - fr.pos());
        }
        if (dataLength < 0 || dataLength > MAX_INFLATED) {
            throw new IOException("illegal uncompressed length " + dataLength);
        }
        inflater.reset();
        inflater.setInput(frame, fr.pos(), frame.length - fr.pos());
        byte[] outBuf = new byte[dataLength];
        int written = 0;
        try {
            while (written < dataLength) {
                int n = inflater.inflate(outBuf, written, dataLength - written);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break;
                }
                written += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("bad zlib frame: " + e.getMessage(), e);
        }
        if (written != dataLength) {
            throw new IOException("truncated frame: declared " + dataLength
                    + " bytes, inflated " + written);
        }
        return outBuf;
    }

    public void sendPayload(byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        if (DEBUG) {
            PacketReader pr = new PacketReader(payload);
            System.out.println("[pkt] out raw id=0x" + String.format("%02X", pr.readVarInt())
                    + " len=" + payload.length + " compressed=" + compressionEnabled);
        }
        synchronized (out) {
            if (compressionEnabled) {
                ByteArrayOutputStream fd = new ByteArrayOutputStream(payload.length + 8);
                if (payload.length >= compressionThreshold) {
                    byte[] c = deflate(payload);
                    PacketWriter.varInt(fd, payload.length);
                    fd.write(c, 0, c.length);
                } else {
                    PacketWriter.varInt(fd, 0);
                    fd.write(payload, 0, payload.length);
                }
                byte[] frameData = fd.toByteArray();
                PacketWriter.varInt(out, frameData.length);
                out.write(frameData);
            } else {
                PacketWriter.varInt(out, payload.length);
                out.write(payload);
            }
            out.flush();
        }
    }

    public static byte[] packet(int packetId, byte[] body) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream((body == null ? 0 : body.length) + 5);
        try {
            PacketWriter.varInt(baos, packetId);
        } catch (IOException e) {
            throw new IllegalStateException(e); // cannot happen on a byte array
        }
        if (body != null) baos.write(body, 0, body.length);
        return baos.toByteArray();
    }

    private byte[] deflate(byte[] input) {
        deflater.reset();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, input.length / 3));
        byte[] tmp = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(tmp);
            if (n == 0 && deflater.needsInput()) break;
            baos.write(tmp, 0, n);
        }
        return baos.toByteArray();
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    /**
     * VarInt read straight off the socket. Five bytes maximum: the sixth byte of
     * a VarInt is always illegal, and without the check a stream of 0x80 bytes
     * loops until the shift overflows into nonsense.
     */
    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("VarInt longer than 5 bytes");
    }
}
