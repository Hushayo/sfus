package mcbot.net;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class PacketReader {
    public final byte[] buf;
    public int pos;
    public PacketReader(byte[] buf) { this.buf = buf; pos = 0; }
    public int pos() { return pos; }
    public int remaining() { return buf.length - pos; }
    public byte readByte() { return buf[pos++]; }
    public int readUnsignedByte() { return buf[pos++] & 0xFF; }
    public short readShort() { return (short) (((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF)); }
    public int readUnsignedShort() { return ((buf[pos++] & 0xFF) << 8) | (buf[pos++] & 0xFF); }
    public boolean readBoolean() { return readByte() != 0; }
    public long readVarLong() {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift >= 64) throw new IllegalArgumentException("VarLong too long");
        }
        return result;
    }
    public int readVarInt() {
        int result=0, shift=0;
        while (true) {
            byte b = buf[pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80)==0) break;
            shift += 7;
        }
        return result;
    }
    public String readString() {
        int len = readVarInt();
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }
    public UUID readUUID() {
        long msb = 0, lsb = 0;
        for (int i=0;i<8;i++) msb = (msb<<8) | (buf[pos++] & 0xFFL);
        for (int i=0;i<8;i++) lsb = (lsb<<8) | (buf[pos++] & 0xFFL);
        return new UUID(msb, lsb);
    }
    public int readInt() {
        int v = ((buf[pos] & 0xFF)<<24) | ((buf[pos+1] & 0xFF)<<16) | ((buf[pos+2] & 0xFF)<<8) | (buf[pos+3] & 0xFF);
        pos += 4;
        return v;
    }
    public long readLong() {
        long v = 0;
        for (int i=0;i<8;i++) v = (v<<8) | (buf[pos+i] & 0xFFL);
        pos += 8;
        return v;
    }
    public double readDouble() { return Double.longBitsToDouble(readLong()); }
    public float readFloat() { return Float.intBitsToFloat(readInt()); }
    public byte[] readBytes(int n) {
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }
}