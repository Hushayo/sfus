package mcbot.dev.test;

import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;
import mcbot.net.FramedConnection;
import mcbot.util.Pos;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * Wire format tests.
 *
 * <p>Every one of these is a bug that already happened at least once in this
 * project: a wrong VarInt cap, a BlockPos packed the old way, a UUID written as
 * two longs in the wrong order. They are cheap to run and they fail loudly,
 * which is exactly what a hand-written protocol needs.
 */
public final class CodecTests {
    private CodecTests() {}

    public static void run() {
        TestRunner.suite("codec");

        TestRunner.test("varint round-trips small values", () -> {
            for (int v : new int[]{0, 1, 2, 127, 128, 255, 300, 16383, 16384, 1_000_000}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                PacketWriter.varInt(out, v);
                TestRunner.eq("varint " + v, v, new PacketReader(out.toByteArray()).readVarInt());
            }
        });

        TestRunner.test("varint round-trips negative values", () -> {
            for (int v : new int[]{-1, -2, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                PacketWriter.varInt(out, v);
                TestRunner.eq("varint " + v, v, new PacketReader(out.toByteArray()).readVarInt());
            }
        });

        TestRunner.test("varint is at most five bytes", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PacketWriter.varInt(out, Integer.MIN_VALUE);
            TestRunner.eq("byte count", 5, out.size());
        });

        TestRunner.test("string round-trips UTF-8", () -> {
            String text = "minecraft:oak_planks \u00e9\u4e2d\u6587";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PacketWriter.string(out, text);
            TestRunner.eq("string", text, new PacketReader(out.toByteArray()).readString());
        });

        TestRunner.test("block position packs x/y/z in the 26/12/26 layout", () -> {
            int[][] points = {{0, 0, 0}, {1, 64, -1}, {-1234, 64, 5678}, {33_000_000, 255, -33_000_000}};
            for (int[] p : points) {
                long packed = Pos.blockLong(p[0], p[1], p[2]);
                TestRunner.eq("x of " + p[0] + "," + p[1] + "," + p[2], p[0], Pos.blockX(packed));
                TestRunner.eq("y of " + p[0] + "," + p[1] + "," + p[2], p[1], Pos.blockY(packed));
                TestRunner.eq("z of " + p[0] + "," + p[1] + "," + p[2], p[2], Pos.blockZ(packed));
            }
        });

        TestRunner.test("block position y occupies the low 12 bits", () -> {
            long packed = Pos.blockLong(0, 255, 0);
            TestRunner.eq("low bits", 255L, packed & 0xFFFL);
        });

        TestRunner.test("uuid round-trips", () -> {
            UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PacketWriter.uuid(out, uuid);
            TestRunner.eq("uuid", uuid, new PacketReader(out.toByteArray()).readUUID());
        });

        TestRunner.test("a packet body is id followed by the payload", () -> {
            byte[] body = {1, 2, 3, 4, 5};
            byte[] packet = FramedConnection.packet(0x2A, body);
            PacketReader pr = new PacketReader(packet);
            TestRunner.eq("packet id", 0x2A, pr.readVarInt());
            TestRunner.eq("remaining", body.length, pr.remaining());
            TestRunner.eq("first payload byte", 1, pr.readUnsignedByte());
        });

        TestRunner.test("an empty packet is just its id", () -> {
            byte[] packet = FramedConnection.packet(0x0D, new byte[0]);
            TestRunner.eq("length", 1, packet.length);
            TestRunner.eq("id", 0x0D, new PacketReader(packet).readVarInt());
        });

        TestRunner.test("reader refuses to read past the end", () -> {
            PacketReader pr = new PacketReader(new byte[]{1});
            pr.readByte();
            try {
                pr.readByte();
                TestRunner.fail("reading past the end should throw");
            } catch (RuntimeException expected) {
                // correct
            }
        });

        TestRunner.test("varint refuses an over-long encoding", () -> {
            byte[] bad = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01};
            try {
                new PacketReader(bad).readVarInt();
                TestRunner.fail("six-byte varint should throw");
            } catch (RuntimeException expected) {
                // correct
            }
        });

        TestRunner.test("float and double keep their exact bits", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PacketWriter.floatVal(out, 0.1f);
            PacketWriter.doubleVal(out, Math.PI);
            PacketReader pr = new PacketReader(out.toByteArray());
            TestRunner.eq("float", 0.1f, pr.readFloat());
            TestRunner.eq("double", Math.PI, pr.readDouble());
        });
    }
}
