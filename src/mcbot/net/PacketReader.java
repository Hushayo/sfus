package mcbot.net;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bounds-checked reader over a packet body.
 *
 * Every method that runs off the end of the buffer throws
 * {@link IndexOutOfBoundsException} with the offset, instead of silently
 * returning garbage from the next field. That distinction matters: a module
 * that reads past the end of a packet it mis-identified used to corrupt state
 * quietly, and the symptom showed up minutes later somewhere unrelated.
 */
public class PacketReader {
    private static final int MAX_STRING_CHARS = 32_767;

    public final byte[] buf;
    public int pos;

    public PacketReader(byte[] buf) {
        this.buf = buf;
        this.pos = 0;
    }

    public int pos() { return pos; }
    public int remaining() { return buf.length - pos; }
    public boolean hasRemaining() { return pos < buf.length; }

    private void need(int n) {
        if (n < 0 || pos + n > buf.length) {
            throw new IndexOutOfBoundsException("need " + n + " bytes at " + pos
                    + ", packet is " + buf.length);
        }
    }

    public void skip(int n) {
        need(n);
        pos += n;
    }

    public byte readByte() {
        need(1);
        return buf[pos++];
    }

    public int readUnsignedByte() {
        need(1);
        return buf[pos++] & 0xFF;
    }

    public short readShort() {
        need(2);
        return (short) (((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF));
    }

    public int readUnsignedShort() {
        need(2);
        return ((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF);
    }

    public boolean readBoolean() { return readByte() != 0; }

    public int readVarInt() {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            need(1);
            byte b = buf[pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IllegalArgumentException("VarInt longer than 5 bytes at " + pos);
    }

    public long readVarLong() {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            need(1);
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IllegalArgumentException("VarLong longer than 10 bytes at " + pos);
    }

    public String readString() {
        int len = readVarInt();
        if (len < 0 || len > MAX_STRING_CHARS * 4) {
            throw new IllegalArgumentException("illegal string length " + len + " at " + pos);
        }
        need(len);
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    public UUID readUUID() {
        need(16);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (buf[pos++] & 0xFFL);
        for (int i = 0; i < 8; i++) lsb = (lsb << 8) | (buf[pos++] & 0xFFL);
        return new UUID(msb, lsb);
    }

    public int readInt() {
        need(4);
        int v = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long readLong() {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (buf[pos + i] & 0xFFL);
        pos += 8;
        return v;
    }

    public double readDouble() { return Double.longBitsToDouble(readLong()); }

    public float readFloat() { return Float.intBitsToFloat(readInt()); }

    public byte[] readBytes(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** Everything left, as a copy. */
    public byte[] readRemaining() {
        return readBytes(buf.length - pos);
    }

    /**
     * A FixedBitSet of {@code bits} bits, sent as ceil(bits/8) bytes,
     * little-endian within each byte.
     */
    public boolean[] readBitSet(int bits) {
        int bytes = (bits + 7) / 8;
        need(bytes);
        boolean[] out = new boolean[bits];
        for (int i = 0; i < bits; i++) {
            out[i] = (buf[pos + (i >> 3)] & (1 << (i & 7))) != 0;
        }
        pos += bytes;
        return out;
    }
}
