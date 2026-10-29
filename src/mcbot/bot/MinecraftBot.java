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
    /**
     * The onGround flag carried by the last movement packet we sent.
     *
     * This is not the same as {@link mcbot.physics.PlayerPhysics#onGround}: the
     * server only ever learns what a packet told it. It matters because
     * {@code Player.getDestroySpeed} divides mining speed by 5 while the server
     * believes the player is airborne, so a stale {@code false} here makes every
     * dig take five times as long as predicted.
     */
    public volatile boolean sentOnGround = false;

    /** Our own entity id from the play login packet; -1 until known. */
    public volatile int entityId = -1;
    public volatile float health = 20.0f;
    public volatile int food = 20;
    public volatile float saturation = 5.0f;
    public volatile boolean dead = false;
    /** True while an eat animation is running, so two eaters cannot overlap. */
    public volatile boolean eating = false;
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
    private final mcbot.modules.PhysicsModule physicsModule = new mcbot.modules.PhysicsModule();
    public final mcbot.modules.CraftingModule crafting =
            new mcbot.modules.CraftingModule();
    /** Classifies nearby entities into hostile / neutral / passive. */
    public final mcbot.modules.ThreatModule threats =
            new mcbot.modules.ThreatModule();
    /** UUID -> player name, from the tab list. */
    public final mcbot.modules.PlayerListModule playerList =
            new mcbot.modules.PlayerListModule();
    /** Keeps armour on the body and food in the hotbar. */
    public final mcbot.modules.LoadoutModule loadout =
            new mcbot.modules.LoadoutModule();
    /** High-level verbs: dig, place, craft, eat, equip, deposit. */
    public final mcbot.api.Actions actions = new mcbot.api.Actions(this);
    /** Deterministic reasoning over the knowledge tables. */
    public final mcbot.api.Reasoner reasoner = new mcbot.api.Reasoner(this);
    /** One long-running job at a time: mining, crafting, smelting. */
    public final mcbot.jobs.JobRunner jobs = new mcbot.jobs.JobRunner();
    /** Chat intent parser, so the AI tool layer can trigger the same jobs. */
    public final mcbot.modules.ChatCommands chat = new mcbot.modules.ChatCommands();
    /** Gravity, drag and collision, plus velocity from server knockback. */
    public final mcbot.physics.PlayerPhysics physics = new mcbot.physics.PlayerPhysics();
    /** Blocks fallen in the last landing, read by survival. */
    public volatile double lastFallBlocks = 0;
    /** Game mode id from the login packet: 0 survival, 1 creative, 2 adventure, 3 spectator. */
    public volatile int gameMode = 0;
    public volatile boolean creative = false;
    public volatile String autoGoto = System.getenv("BOT_GOTO");
    /** Last disconnect reason, as text, so the cause survives the socket close. */
    public volatile String disconnectReason = null;

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
        // Physics sits right after teleports so a knockback is applied before
        // anything else reads the position this tick.
        modules.add(physicsModule);
        modules.add(new mcbot.modules.WorldModule());
        modules.add(entityTracker);
        modules.add(playerList);
        // Threats read the tracker, so they come after it and before survival,
        // which uses the classification to pick a target.
        modules.add(threats);
        modules.add(new mcbot.modules.LoginSecurityModule());
        modules.add(new mcbot.modules.AutoRespawnModule());
        modules.add(new mcbot.modules.PacketProbeModule());
        modules.add(jobs);
        // Commands are parsed once, here, before anything else looks at chat.
        modules.add(chat);
        modules.add(look);
        modules.add(survival);
        modules.add(interaction);
        modules.add(loadout);
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

    /**
     * One line of self-knowledge, fed to the chat model so it can answer
     * questions about itself from facts instead of inventing them.
     */
    public String describeState() {
        double x, y, z;
        synchronized (positionLock) {
            x = playerX;
            y = playerY;
            z = playerZ;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("health ").append(Math.round(health)).append("/20, food ").append(food);
        sb.append(", standing at ").append(Math.round(x)).append(' ')
                .append(Math.round(y)).append(' ').append(Math.round(z));
        sb.append(" in ").append(world.dimensionKey());
        if (dead) {
            sb.append(", DEAD and waiting to respawn");
        } else if (survival.inCombat()) {
            sb.append(", ").append(survival.statusLine());
        } else if (lastDamageMs > 0) {
            long secs = (System.currentTimeMillis() - lastDamageMs) / 1000L;
            sb.append(", last took damage ").append(secs).append("s ago");
        } else {
            sb.append(", haven't been hurt since spawning");
        }
        if (movement.isMoving()) sb.append(", currently walking");
        if (jobs.busy()) sb.append(", ").append(jobs.status());
        sb.append(", ").append(entityTracker.trackedCount()).append(" entities nearby");
        String threatLine = threats.describe();
        if (!"nothing nearby".equals(threatLine)) sb.append(" (").append(threatLine).append(')');
        sb.append(". Gear: ").append(mcbot.knowledge.Mechanics.loadout(interaction.inventory));
        return sb.toString();
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
                    dispatch(BotState.LOGIN, packetId, data);
                    break;
                case CONFIG:
                    handleConfig(packetId, data);
                    dispatch(BotState.CONFIG, packetId, data);
                    break;
                case PLAY:
                    handlePlay(packetId, data);
                    dispatch(BotState.PLAY, packetId, data);
                    break;
            }
            if (!connection.isConnected()) {
                // A disconnect packet closes the socket; reading from it again
                // would throw SocketException and hide the real reason.
                System.out.println("[bot] disconnected: " + disconnectReason);
                return;
            }
        }
    }

    /**
     * Stops the ticker and closes the socket. The instance cannot be reused:
     * every reconnect builds a new bot so no state leaks between sessions.
     */
    public void shutdown() {
        ticking = false;
        try {
            jobs.cancel();
        } catch (Throwable ignored) {}
        try {
            mining.stop();
        } catch (Throwable ignored) {}
        try {
            movement.stop();
        } catch (Throwable ignored) {}
        try {
            connection.close();
        } catch (Throwable ignored) {}
    }

    /**
     * Hands one packet to every module, isolating failures.
     *
     * This is the difference between "a parser is wrong" and "the bot is
     * offline". Module code runs on the same thread that reads the socket, so
     * an exception used to unwind all the way out of the read loop and close
     * the connection - one unexpected packet and the bot vanished, with the
     * stack trace as the only clue.
     *
     * A module that throws is logged once per packet id, then suppressed: a
     * packet type that arrives 20 times a second must not flood the log while
     * the bot keeps running.
     */
    private void dispatch(BotState state, int packetId, byte[] data) {
        for (BotModule m : modules) {
            try {
                switch (state) {
                    case LOGIN: m.onLoginPacket(packetId, data, this); break;
                    case CONFIG: m.onConfigPacket(packetId, data, this); break;
                    case PLAY: m.onPlayPacket(packetId, data, this); break;
                }
            } catch (Throwable t) {
                reportModuleError(m, packetId, t);
            }
        }
    }

    private void reportModuleError(BotModule module, int packetId, Throwable t) {
        String key = module.getClass().getSimpleName() + "#" + packetId;
        Integer seen = moduleErrors.get(key);
        if (seen == null) {
            moduleErrors.put(key, 1);
            System.out.println("[pkt] " + module.getClass().getSimpleName()
                    + " failed on id=0x" + Integer.toHexString(packetId)
                    + " len=" + (t.getMessage() == null ? "?" : t.getMessage()));
            t.printStackTrace(System.out);
        } else if (seen < 5) {
            moduleErrors.put(key, seen + 1);
        } else if (seen == 5) {
            moduleErrors.put(key, 6);
            System.out.println("[pkt] " + module.getClass().getSimpleName()
                    + " still failing on id=0x" + Integer.toHexString(packetId)
                    + "; suppressing further reports");
        }
    }

    /** First error per module+packet, used to avoid log floods. */
    private final java.util.Map<String, Integer> moduleErrors =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void handleLogin(int packetId, byte[] data) throws Exception {
        if (packetId == 0x00) {
            // The reason is a JSON text component; the printable part is the
            // message, which is what a human needs to see.
            disconnectReason = printable(data);
            System.out.println("[bot] login disconnect: " + disconnectReason);
            connection.close();
        } else if (packetId == 0x01) {
            disconnectReason = "server requires encryption/auth (online mode)";
            System.out.println("[bot] " + disconnectReason);
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
            disconnectReason = printable(data);
            System.out.println("[bot] config disconnect: " + disconnectReason);
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
            disconnectReason = printable(data);
            System.out.println("[bot] play disconnect: " + disconnectReason);
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
            sentOnGround = onGround;
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
        String m = cleanForChat(message);
        if (m.isBlank()) return;
        sendPlay(0x09, chatBody(m));
        System.out.println("[bot] chat: " + m);
    }

    /**
     * Makes a message safe for the chat packet without destroying it.
     *
     * <p>The old sanitizer replaced every non-ASCII character with '?', so
     * "Sure - back later" came out as "Sure?back later" and every emoji became
     * a question mark. Minecraft chat is UTF-8 and supports Unicode; what it
     * does not support is control characters and over-long messages. So:
     * collapse whitespace, drop controls, keep real characters, and cut at a
     * word boundary instead of mid-word.
     */
    public static String cleanForChat(String message) {
        String m = message == null ? "" : message;
        StringBuilder sb = new StringBuilder(m.length());
        for (int i = 0; i < m.length(); ) {
            int cp = m.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
            } else if (!Character.isISOControl(cp)) {
                sb.appendCodePoint(cp);
            }
        }
        String s = sb.toString().replaceAll("\\s{2,}", " ").trim();
        if (s.length() > 256) {
            int cut = s.lastIndexOf(' ', 256);
            s = s.substring(0, cut > 180 ? cut : 256).trim();
        }
        return s;
    }

    /**
     * Sends a server command, used to answer whispers with {@code /msg}.
     * A leading slash in a chat packet is not treated as a command by the
     * server; and in 26.3 the command packet is just the text, no slash and no
     * signature fields.
     */
    public void sendCommand(String command) throws Exception {
        String c = command.startsWith("/") ? command.substring(1) : command;
        c = cleanForChat(c);
        if (c.isBlank()) return;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        mcbot.net.PacketWriter.string(body, c);
        sendPlay(0x07, body.toByteArray());
        System.out.println("[bot] command: /" + c);
    }

    /** Payload shared by the chat and command packets: text, timestamp, salt, ack. */
    private static byte[] chatBody(String text) throws java.io.IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        mcbot.net.PacketWriter.string(body, text);
        mcbot.net.PacketWriter.longVal(body, System.currentTimeMillis());
        long salt = new java.util.Random().nextLong();
        mcbot.net.PacketWriter.longVal(body, salt);
        body.write(0);                              // no message signatures
        mcbot.net.PacketWriter.varInt(body, 0);     // no last-seen messages
        body.write(new byte[3]);                    // empty acknowledged bitset
        body.write(0);                              // acknowledge the server's update
        return body.toByteArray();
    }

    /**
     * Pulls the human-readable part out of a text-component payload. A
     * disconnect reason is JSON, and a player who cannot join wants the message,
     * not the JSON and not a hex dump.
     */
    private static String printable(byte[] data) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127) {
                run.append((char) c);
            } else {
                if (run.length() >= 3) out.append(run).append(' ');
                run.setLength(0);
            }
        }
        if (run.length() >= 3) out.append(run);
        String s = out.toString().trim();
        return s.isEmpty() ? "unknown reason" : s;
    }

    /**
     * Pulls the human-readable part out of a text-component payload. A
     * disconnect reason is JSON, and a player who cannot join wants the message,
     * not the JSON and not a hex dump.
     */

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}