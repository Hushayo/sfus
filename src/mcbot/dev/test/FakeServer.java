package mcbot.dev.test;

import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A protocol-777 server that speaks just enough to drive the bot end to end.
 *
 * <p>This is the only test that exercises the real state machine: handshake,
 * login, compression negotiation, configuration, play login, health, keepalive
 * echo and a deliberately malformed packet. Everything else is tested in
 * isolation; this checks that the pieces are wired together.
 *
 * <p>It is not a Minecraft server and does not try to be. It sends no chunks,
 * which is fine: the bot's world stays empty and the test asserts on the
 * connection, not on terrain.
 */
final class FakeServer implements AutoCloseable {
    private static final int THRESHOLD = 256;

    private final ServerSocket socket;
    private final Thread thread;
    private final CountDownLatch loginSent = new CountDownLatch(1);
    private final CountDownLatch keepAliveEchoed = new CountDownLatch(1);
    private final CountDownLatch playerLoaded = new CountDownLatch(1);
    private final CountDownLatch playerActionDecoded = new CountDownLatch(1);
    private final AtomicInteger keepAliveValue = new AtomicInteger();
    private final AtomicInteger malformedSent = new AtomicInteger();
    private volatile Throwable failure;

    FakeServer() throws IOException {
        socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::serve, "fake-server");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    boolean awaitLogin(long ms) throws InterruptedException {
        return loginSent.await(ms, TimeUnit.MILLISECONDS);
    }

    boolean awaitKeepAlive(long ms) throws InterruptedException {
        return keepAliveEchoed.await(ms, TimeUnit.MILLISECONDS);
    }

    boolean awaitPlayerLoaded(long ms) throws InterruptedException {
        return playerLoaded.await(ms, TimeUnit.MILLISECONDS);
    }

    boolean awaitPlayerAction(long ms) throws InterruptedException {
        return playerActionDecoded.await(ms, TimeUnit.MILLISECONDS);
    }

    int keepAliveValue() { return keepAliveValue.get(); }
    int malformedSent() { return malformedSent.get(); }
    Throwable failure() { return failure; }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {}
        thread.interrupt();
    }

    // ------------------------------------------------------------------ serve --

    private void serve() {
        try (Socket client = socket.accept()) {
            client.setTcpNoDelay(true);
            Conn conn = new Conn(client);
            conn.readPacket();                       // handshake
            conn.readPacket();                       // login start

            // Compression is negotiated before login success, exactly as a real
            // server does it.
            conn.send(0x03, varIntBytes(THRESHOLD));
            conn.compressed = true;

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.uuid(body, UUID.nameUUIDFromBytes("FakeServer".getBytes(StandardCharsets.UTF_8)));
            PacketWriter.string(body, "TestBot");
            PacketWriter.varInt(body, 0);            // no properties
            PacketWriter.uuid(body, UUID.randomUUID());
            conn.send(0x02, body.toByteArray());

            conn.readPacket();                       // login acknowledged
            conn.readPacket();                       // client information

            conn.send(0x03, new byte[0]);            // finish_configuration
            conn.readPacket();                       // configuration acknowledged

            // Play login: entity id, hardcore, worlds, max players, view/sim
            // distance, three flags, then the spawn info the bot parses.
            ByteArrayOutputStream play = new ByteArrayOutputStream();
            PacketWriter.intVal(play, 42);
            play.write(0);
            PacketWriter.varInt(play, 1);
            PacketWriter.string(play, "minecraft:overworld");
            PacketWriter.varInt(play, 20);
            PacketWriter.varInt(play, 10);
            PacketWriter.varInt(play, 10);
            play.write(0);                           // reduced debug
            play.write(1);                           // respawn screen
            play.write(0);                           // limited crafting
            PacketWriter.varInt(play, 0);            // dimension type
            PacketWriter.string(play, "minecraft:overworld");
            PacketWriter.longVal(play, 1234L);       // hashed seed
            play.write(0);                           // survival
            conn.send(0x32, play.toByteArray());
            loginSent.countDown();

            // An empty chunk batch. The bot answers chunk_batch_finished with
            // chunk_batch_received and then player_loaded, which is the
            // handshake that tells the server it may move us.
            ByteArrayOutputStream batchStart = new ByteArrayOutputStream();
            PacketWriter.varInt(batchStart, 0);
            conn.send(0x0C, batchStart.toByteArray());
            ByteArrayOutputStream batchEnd = new ByteArrayOutputStream();
            PacketWriter.varInt(batchEnd, 0);
            conn.send(0x0B, batchEnd.toByteArray());

            ByteArrayOutputStream health = new ByteArrayOutputStream();
            PacketWriter.floatVal(health, 20.0f);
            PacketWriter.varInt(health, 20);
            PacketWriter.floatVal(health, 5.0f);
            conn.send(0x6A, health.toByteArray());

            // A keepalive the bot must echo with the same id.
            ByteArrayOutputStream ka = new ByteArrayOutputStream();
            PacketWriter.longVal(ka, 0x5EEDL);
            conn.send(0x2D, ka.toByteArray());

            // A packet with an id no module knows, and a truncated chunk
            // packet. Neither may take the connection down.
            conn.send(0x99, new byte[]{1, 2, 3, 4});
            conn.send(0x2E, new byte[]{0, 0, 0, 1, 2, 3});
            malformedSent.incrementAndGet();

            // Drain until the test closes us.
            while (true) {
                byte[] packet = conn.readPacket();
                PacketReader pr = new PacketReader(packet);
                int id = pr.readVarInt();
                if (id == 0x1C && pr.remaining() >= 8) {
                    long value = pr.readLong();
                    if (value == 0x5EEDL) {
                        keepAliveValue.set((int) value);
                        keepAliveEchoed.countDown();
                    }
                } else if (id == 0x2C) {
                    playerLoaded.countDown();
                } else if (id == 0x29) {
                    // ServerboundPlayerActionPacket: action, BlockPos,
                    // Direction, sequence. A short body is exactly what the
                    // real server rejects, so a trailing-bytes or truncation
                    // check here is the point of the test.
                    int action = pr.readVarInt();
                    pr.readLong();          // BlockPos
                    pr.readVarInt();        // Direction
                    pr.readVarInt();        // sequence
                    if (pr.remaining() != 0) {
                        throw new IOException("player_action has " + pr.remaining()
                                + " trailing bytes");
                    }
                    if (action == 6) playerActionDecoded.countDown();
                }
            }
        } catch (EOFException | java.net.SocketException e) {
            // normal shutdown
        } catch (Throwable t) {
            failure = t;
        }
    }

    // ------------------------------------------------------------------- conn --

    private static final class Conn {
        final Socket socket;
        final DataInputStream in;
        final OutputStream out;
        boolean compressed = false;

        Conn(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void send(int id, byte[] body) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            PacketWriter.varInt(payload, id);
            payload.write(body, 0, body.length);
            byte[] data = payload.toByteArray();

            byte[] frame;
            if (compressed) {
                if (data.length >= THRESHOLD) {
                    ByteArrayOutputStream f = new ByteArrayOutputStream();
                    PacketWriter.varInt(f, data.length);
                    f.write(deflate(data));
                    frame = f.toByteArray();
                } else {
                    ByteArrayOutputStream f = new ByteArrayOutputStream();
                    PacketWriter.varInt(f, 0);
                    f.write(data);
                    frame = f.toByteArray();
                }
            } else {
                frame = data;
            }
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            PacketWriter.varInt(framed, frame.length);
            framed.write(frame);
            out.write(framed.toByteArray());
            out.flush();
        }

        byte[] readPacket() throws IOException {
            int length = readVarInt(in);
            if (length <= 0 || length > 8 * 1024 * 1024) {
                throw new IOException("bad frame length " + length);
            }
            byte[] frame = new byte[length];
            in.readFully(frame);
            if (!compressed) return frame;
            PacketReader pr = new PacketReader(frame);
            int dataLength = pr.readVarInt();
            byte[] rest = pr.readRemaining();
            return dataLength == 0 ? rest : inflate(rest);
        }

        private static int readVarInt(InputStream in) throws IOException {
            int value = 0;
            int shift = 0;
            while (true) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
                if (shift > 35) throw new IOException("varint too long");
            }
        }

        private static byte[] deflate(byte[] data) {
            Deflater deflater = new Deflater();
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            deflater.end();
            return out.toByteArray();
        }

        private static byte[] inflate(byte[] data) throws IOException {
            Inflater inflater = new Inflater();
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try {
                while (!inflater.finished()) {
                    int n = inflater.inflate(buffer);
                    if (n == 0 && inflater.needsInput()) break;
                    out.write(buffer, 0, n);
                }
            } catch (java.util.zip.DataFormatException e) {
                throw new IOException(e);
            } finally {
                inflater.end();
            }
            return out.toByteArray();
        }
    }

    private static byte[] varIntBytes(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PacketWriter.varInt(out, value);
        } catch (IOException ignored) {}
        return out.toByteArray();
    }
}
