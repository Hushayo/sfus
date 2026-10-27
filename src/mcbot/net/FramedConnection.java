package mcbot.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class FramedConnection {
    private static final boolean DEBUG = System.getenv("BOT_DEBUG") != null;
    public DataInputStream in;
    public OutputStream out;
    public boolean compressionEnabled = false;
    public int compressionThreshold = 256;

    private Socket socket;

    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(60000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new BufferedOutputStream(socket.getOutputStream());
    }

    public byte[] readPacket() throws Exception {
        int packetLength = readVarInt(in);
        byte[] frame = new byte[packetLength];
        in.readFully(frame);
        if (!compressionEnabled) return frame;
        PacketReader fr = new PacketReader(frame);
        int dataLength = fr.readVarInt();
        byte[] rest = fr.readBytes(frame.length - fr.pos());
        if (dataLength == 0) return rest;
        Inflater inflater = new Inflater();
        inflater.setInput(rest);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(dataLength);
        byte[] tmp = new byte[8192];
        while (!inflater.finished()) {
            int n = inflater.inflate(tmp);
            if (n <= 0 && !inflater.finished()) {
                if (inflater.needsInput()) break;
            }
            baos.write(tmp, 0, n);
        }
        inflater.end();
        return baos.toByteArray();
    }

    public void sendPayload(byte[] payload) throws IOException {
        if (DEBUG) {
            PacketReader pr = new PacketReader(payload);
            System.out.println("[pkt] out raw id=0x" + String.format("%02X", pr.readVarInt())
                    + " len=" + payload.length + " compressed=" + compressionEnabled);
        }
        if (compressionEnabled) {
            byte[] frameData;
            if (payload.length >= compressionThreshold) {
                byte[] c = deflate(payload);
                java.io.ByteArrayOutputStream fd = new java.io.ByteArrayOutputStream();
                PacketWriter.varInt(fd, payload.length);
                fd.write(c);
                frameData = fd.toByteArray();
            } else {
                java.io.ByteArrayOutputStream fd = new java.io.ByteArrayOutputStream();
                PacketWriter.varInt(fd, 0);
                fd.write(payload);
                frameData = fd.toByteArray();
            }
            synchronized (out) {
                PacketWriter.varInt(out, frameData.length);
                out.write(frameData);
                out.flush();
            }
        } else {
            synchronized (out) {
                PacketWriter.varInt(out, payload.length);
                out.write(payload);
                out.flush();
            }
        }
    }

    public static byte[] packet(int packetId, byte[] body) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            PacketWriter.varInt(baos, packetId);
            baos.write(body);
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] deflate(byte[] input) {
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
        def.setInput(input);
        def.finish();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(tmp);
            baos.write(tmp, 0, n);
        }
        def.end();
        return baos.toByteArray();
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int result = 0, shift = 0;
        while (true) {
            byte b = in.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }
}