package mcbot.bot;

import mcbot.core.BotState;
import mcbot.net.FramedConnection;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;
import mcbot.world.World;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MinecraftBot {
    public static final boolean DEBUG = System.getenv("BOT_DEBUG") != null;
    public final String host;
    public final int port;
    public final String username;
    public final UUID uuid;
    public final String password;
    public final String apiKey;

    public final Object positionLock = new Object();
    /**
     * Serializes everything that writes a position packet. The path worker and
     * the 20 Hz ticker (gaze, idle keepalive) both need to send movement, and
     * two position packets racing each other is how you get rubber-banding.
     */
    public final Object moveLock = new Object();
    public volatile boolean hasPlayerPosition = false;
    public volatile boolean playerLoaded = false;
    public volatile long lastTeleportMs = 0;
    public volatile long teleportCounter = 0;
    public double playerX, playerY, playerZ;
    public float playerYaw, playerPitch;

    /** Our own entity id from the play login packet; -1 until known. */
    public volatile int entityId = -1;
    public volatile float health = 20.0f;
    public volatile int food = 20;
    public volatile float saturation = 5.0f;
    public volatile boolean dead = false;
    public volatile String lastDeathMessage = null;
    public volatile long lastDamageMs = 0L;
    /** UUIDs that have spoken in chat: a registry-free way to spot players. */
    public final java.util.Set<UUID> knownPlayers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public final FramedConnection connection = new FramedConnection();
    public final World world = new World();
    public final mcbot.modules.EntityTrackerModule entityTracker =
            new mcbot.modules.EntityTrackerModule();
    public final mcbot.modules.MovementModule movement =
            new mcbot.modules.MovementModule();
    public final mcbot.modules.MiningModule mining =
            new mcbot.modules.MiningModule();
    public final mcbot.modules.LookModule look =
            new mcbot.modules.LookModule();
    public final mcbot.modules.SurvivalModule survival =
            new mcbot.modules.SurvivalModule();
    public final mcbot.modules.InteractionModule interaction =
            new mcbot.modules.InteractionModule();
    public final mcbot.modules.ScanModule scanner =
            new mcbot.modules.ScanModule();
    public final mcbot.modules.CraftingModule crafting =
            new mcbot.modules.CraftingModule();
    /** Game mode id from the login packet: 0 survival, 1 creative, 2 adventure, 3 spectator. */
    public volatile int gameMode = 0;
    public volatile boolean creative = false;
    public volatile String autoGoto = System.getenv("BOT_GOTO");

    private BotState state = BotState.LOGIN;
    public final List<BotModule> modules = new ArrayList<>();

    public MinecraftBot(String host, int port, String username, String password) {
        this(host, port, username, password, null);
    }
    public MinecraftBot(String host, int port, String username, String password, String apiKey) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        this.password = (password == null || password.isEmpty()) ? "ChangeMe123" : password;
        this.apiKey = apiKey;
    }

    public void installDefaults() {
        modules.add(new mcbot.modules.KeepAliveModule());
        modules.add(new mcbot.modules.TeleportModule());
        modules.add(new mcbot.modules.WorldModule());
        modules.add(entityTracker);
        modules.add(new mcbot.modules.LoginSecurityModule());
        modules.add(new mcbot.modules.AutoRespawnModule());
        modules.add(look);
        modules.add(survival);
        modules.add(interaction);
        modules.add(scanner);
        modules.add(crafting);
        modules.add(new mcbot.modules.AIChatModule(apiKey));
        modules.add(movement);
        modules.add(mining);
        for (BotModule m : modules) m.install(this);
        startTicker();
    }

    private volatile boolean ticking = true;

    /**
     * 20 Hz module tick. Only runs in PLAY so nothing touches the world before
     * the handshake finishes. A module that throws is disabled for that tick,
     * never for the session.
     */
    private void startTicker() {
        Thread t = new Thread(() -> {
            long next = System.currentTimeMillis();
            while (ticking) {
                next += 50L;
                if (state == BotState.PLAY) {
                    for (BotModule m : modules) {
                        try {
                            m.onTick(this);
                        } catch (Throwable e) {
                            System.out.println("[tick] " + m.getClass().getSimpleName() + ": " + e);
                        }
                    }
                }
                long sleep = next - System.currentTimeMillis();
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    next = System.currentTimeMillis();
                }
            }
        }, "ticker");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Sends the current rotation with the current position (move_player_pos_rot).
     * Used by the gaze module; callers must hold {@link #moveLock}.
     */
    public void sendLook() throws Exception {
        synchronized (positionLock) {
            sendPositionAndTickEnd(true);
        }
    }

    public BotState state() { return state; }
    public void setState(BotState s) {
        state = s;
        for (BotModule m : modules) m.onStateChange(s);
    }

    public void run() throws Exception {
        connection.connect(host, port);
        System.out.println("[bot] tcp connected");

        // Handshake
        ByteArrayOutputStream hs = new ByteArrayOutputStream();
        PacketWriter.varInt(hs, 777);
        PacketWriter.string(hs, host);
        PacketWriter.shortVal(hs, port);
        PacketWriter.varInt(hs, 2);
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        PacketWriter.varInt(p, 0);
        p.write(hs.toByteArray());
        connection.sendPayload(p.toByteArray());

        // Login Start
        ByteArrayOutputStream ls = new ByteArrayOutputStream();
        PacketWriter.string(ls, username);
        PacketWriter.uuid(ls, uuid);
        ByteArrayOutputStream p2 = new ByteArrayOutputStream();
        PacketWriter.varInt(p2, 0);
        p2.write(ls.toByteArray());
        connection.sendPayload(p2.toByteArray());

        while (true) {
            byte[] payload = connection.readPacket();
            PacketReader pr = new PacketReader(payload);
            int packetId = pr.readVarInt();
            byte[] data = pr.readBytes(payload.length - pr.pos());
            if (DEBUG) {
                System.out.println("[pkt] in state=" + state + " id=0x"
                        + String.format("%02X", packetId) + " len=" + data.length);
            }

            switch (state) {
                case LOGIN:
                    handleLogin(packetId, data);
                    for (BotModule m : modules) m.onLoginPacket(packetId, data, this);
                    break;
                case CONFIG:
                    handleConfig(packetId, data);
                    for (BotModule m : modules) m.onConfigPacket(packetId, data, this);
                    break;
                case PLAY:
                    handlePlay(packetId, data);
                    for (BotModule m : modules) m.onPlayPacket(packetId, data, this);
                    break;
            }
        }
    }

    private void handleLogin(int packetId, byte[] data) throws Exception {
        if (packetId == 0x00) {
            System.out.println("[bot] login disconnect: " + bytesToHex(data));
            connection.close();
        } else if (packetId == 0x01) {
            System.out.println("[bot] server requires encryption/auth (online mode).");
            connection.close();
        } else if (packetId == 0x02) {
            PacketReader pr = new PacketReader(data);
            UUID profileUuid = pr.readUUID();
            String name = pr.readString();
            int propCount = pr.readVarInt();
            for (int i = 0; i < propCount; i++) {
                pr.readString(); // name
                pr.readString(); // value
                boolean hasSig = pr.readByte() != 0;
                if (hasSig) pr.readString();
            }
            UUID sessionId = pr.readUUID();
            System.out.println("[bot] login success profile=" + name + " uuid=" + profileUuid + " session=" + sessionId);

            // Login Acknowledged
            connection.sendPayload(FramedConnection.packet(0x03, new byte[0]));
            setState(BotState.CONFIG);
            System.out.println("[bot] -> config");

            // Client Information
            sendClientInformation();
        } else if (packetId == 0x03) {
            PacketReader pr = new PacketReader(data);
            int thr = pr.readVarInt();
            connection.compressionThreshold = thr;
            connection.compressionEnabled = true;
            System.out.println("[bot] compression enabled threshold=" + thr);
        }
    }

    private void sendClientInformation() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        PacketWriter.string(body, "en_US");
        body.write(8);
        PacketWriter.varInt(body, 0);
        body.write(1);
        body.write(0x7F);
        PacketWriter.varInt(body, 1);
        body.write(0);
        body.write(0);
        PacketWriter.varInt(body, 0);
        connection.sendPayload(FramedConnection.packet(0x00, body.toByteArray()));
        System.out.println("[bot] client information sent");
    }

    private void handleConfig(int packetId, byte[] data) throws Exception {
        if (packetId == 0x02) {
            System.out.println("[bot] config disconnect: " + bytesToHex(data));
            connection.close();
        } else if (packetId == 0x03) {
            System.out.println("[bot] finish configuration received");
            connection.sendPayload(FramedConnection.packet(0x03, new byte[0]));
            setState(BotState.PLAY);
            System.out.println("[bot] -> play");
        } else if (packetId == 0x05) {
            PacketReader pr = new PacketReader(data);
            int id = pr.readInt();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.intVal(body, id);
            connection.sendPayload(FramedConnection.packet(0x05, body.toByteArray()));
        } else if (packetId == 0x0F) {
            PacketReader pr = new PacketReader(data);
            int count = pr.readVarInt();
            List<String[]> packs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String ns = pr.readString();
                String id = pr.readString();
                String ver = pr.readString();
                packs.add(new String[]{ns, id, ver});
            }
            System.out.println("[bot] known packs from server: " + count);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, packs.size());
            for (String[] pck : packs) {
                PacketWriter.string(body, pck[0]);
                PacketWriter.string(body, pck[1]);
                PacketWriter.string(body, pck[2]);
            }
            connection.sendPayload(FramedConnection.packet(0x07, body.toByteArray()));
        }
    }

    private void handlePlay(int packetId, byte[] data) throws Exception {
        if (packetId == 0x20) {
            System.out.println("[bot] play disconnect: " + bytesToHex(data));
            connection.close();
        }
    }

    public void sendPlay(int packetId, byte[] body) throws Exception {
        connection.sendPayload(FramedConnection.packet(packetId, body));
    }

    public void sendConfig(int packetId, byte[] body) throws Exception {
        connection.sendPayload(FramedConnection.packet(packetId, body));
    }

    /**
     * Sends one movement packet followed by Client Tick End. The tick-end packet
     * resets the server's "position received this tick" flag so a second move
     * packet can never trigger multiplayer.disconnect.invalid_player_movement.
     * The caller must hold {@link #positionLock} (synchronized is reentrant).
     */
    public void sendPositionAndTickEnd(boolean onGround) throws Exception {
        synchronized (positionLock) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.doubleVal(body, playerX);
            PacketWriter.doubleVal(body, playerY);
            PacketWriter.doubleVal(body, playerZ);
            PacketWriter.floatVal(body, playerYaw);
            PacketWriter.floatVal(body, playerPitch);
            body.write(onGround ? 1 : 0);
            sendPlay(0x1F, body.toByteArray()); // move_player_pos_rot
            sendPlay(0x0D, new byte[0]);        // client_tick_end
        }
    }

    public void sendTickEnd() throws Exception {
        sendPlay(0x0D, new byte[0]);
    }

    /** Input flag bits for {@link #sendInput} (vanilla Input record). */
    public static final int INPUT_FORWARD = 1;
    public static final int INPUT_BACKWARD = 2;
    public static final int INPUT_LEFT = 4;
    public static final int INPUT_RIGHT = 8;
    public static final int INPUT_JUMP = 16;
    public static final int INPUT_SHIFT = 32;
    public static final int INPUT_SPRINT = 64;

    /**
     * Sends serverbound player_input (0x2B): one byte of held-input flags. The
     * server tracks this for jump/riptide/elytra bookkeeping; the position is
     * still sent by the client, so this only mirrors what a real client reports.
     */
    public void sendInput(int flags) throws Exception {
        sendPlay(0x2B, new byte[]{(byte) (flags & 0x7F)});
    }

    public void sendChat(String message) throws Exception {
        String m = message;
        if (m.length() > 256) m = m.substring(0, 256);
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        mcbot.net.PacketWriter.string(body, m);
        mcbot.net.PacketWriter.longVal(body, System.currentTimeMillis());
        long salt = new java.util.Random().nextLong();
        mcbot.net.PacketWriter.longVal(body, salt);
        body.write(0);
        mcbot.net.PacketWriter.varInt(body, 0);
        body.write(new byte[3]);
        body.write(0); // 0 = acknowledge the server's last-seen update (accepted)
        sendPlay(0x09, body.toByteArray());
        System.out.println("[bot] chat: " + m);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}