package mcbot.net;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PacketWriter {
    private PacketWriter() {}
    public static void varInt(OutputStream out, int v) throws java.io.IOException {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
    }
    public static void shortVal(OutputStream out, int v) throws java.io.IOException {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
    public static void intVal(OutputStream out, int v) throws java.io.IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
    public static void longVal(OutputStream out, long v) throws java.io.IOException {
        for (int i = 7; i >= 0; i--) out.write((int)((v >>> (i*8)) & 0xFF));
    }
    public static void doubleVal(OutputStream out, double v) throws java.io.IOException { longVal(out, Double.doubleToRawLongBits(v)); }
    public static void floatVal(OutputStream out, float v) throws java.io.IOException { intVal(out, Float.floatToRawIntBits(v)); }
    public static void string(OutputStream out, String s) throws java.io.IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varInt(out, b.length);
        out.write(b);
    }
    public static void uuid(OutputStream out, UUID u) throws java.io.IOException {
        longVal(out, u.getMostSignificantBits());
        longVal(out, u.getLeastSignificantBits());
    }

    /**
     * BlockPos.asLong() as of this protocol version: 26-bit x at bit 38, 12-bit
     * y at bit 0, 26-bit z at bit 12. Not the older x<<38|y<<26|z layout.
     */
    public static void blockPos(OutputStream out, int x, int y, int z) throws java.io.IOException {
        long v = (((long) (x & 0x3FFFFFF)) << 38)
                | ((long) (y & 0xFFF))
                | (((long) (z & 0x3FFFFFF)) << 12);
        longVal(out, v);
    }
}