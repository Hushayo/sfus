package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;
import mcbot.world.Chunk;
import mcbot.world.ChunkParser;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps a client-side block cache in sync with the server and completes the
 * 26.3 client-load handshake (chunk batch ack + player_loaded).
 *
 * Some servers (proxies in particular) stream level_chunk_with_light packets
 * without ever sending chunk_batch_finished. player_loaded must still be sent,
 * otherwise the server ignores movement until its load timeout expires.
 */
public class WorldModule implements BotModule {
    private long lastChunkLogMs = 0;
    private final AtomicBoolean playerLoadedSent = new AtomicBoolean(false);
    private final AtomicBoolean playerLoadedScheduled = new AtomicBoolean(false);
    private final AtomicLong loadGeneration = new AtomicLong();

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        switch (packetId) {
            case 0x2E: { // level_chunk_with_light
                Chunk chunk = ChunkParser.parse(data, bot.world);
                bot.world.putChunk(chunk);
                long now = System.currentTimeMillis();
                if (now - lastChunkLogMs > 2000) {
                    lastChunkLogMs = now;
                    System.out.println("[world] chunks loaded=" + bot.world.chunkCount()
                            + " latest=" + chunk.chunkX + "," + chunk.chunkZ);
                }
                schedulePlayerLoaded(bot);
                break;
            }
            case 0x08: { // block_update
                PacketReader pr = new PacketReader(data);
                long pos = pr.readLong();
                int x = (int) (pos >> 38);
                int y = (int) (pos << 52 >> 52);
                int z = (int) (pos << 26 >> 38);
                int state = pr.readVarInt();
                bot.world.setBlock(x, y, z, state);
                if (MinecraftBot.DEBUG) {
                    System.out.println("[world] block_update " + x + " " + y + " " + z + " state=" + state);
                }
                break;
            }
            case 0x56: { // section_blocks_update
                PacketReader pr = new PacketReader(data);
                long packed = pr.readLong();
                int sectionX = (int) (packed >> 42);
                int sectionY = (int) (packed << 44 >> 44);
                int sectionZ = (int) (packed << 22 >> 42);
                int count = pr.readVarInt();
                for (int i = 0; i < count; i++) {
                    long record = pr.readVarLong();
                    int state = (int) (record >> 12);
                    int localX = (int) ((record >> 8) & 0xF);
                    int localY = (int) (record & 0xF);
                    int localZ = (int) ((record >> 4) & 0xF);
                    bot.world.setBlock((sectionX << 4) + localX,
                            (sectionY << 4) + localY,
                            (sectionZ << 4) + localZ, state);
                }
                break;
            }
            case 0x26: { // forget_level_chunk (ChunkPos packed long)
                PacketReader pr = new PacketReader(data);
                long packed = pr.readLong();
                int chunkX = (int) packed;
                int chunkZ = (int) (packed >> 32);
                bot.world.unloadChunk(chunkX, chunkZ);
                break;
            }
            case 0x32: { // login (play)
                PacketReader pr = new PacketReader(data);
                bot.entityId = pr.readInt(); // our own entity id: needed for hurt/death events
                pr.readBoolean(); // hardcore
                int worlds = pr.readVarInt();
                for (int i = 0; i < worlds; i++) pr.readString();
                pr.readVarInt(); // max players
                pr.readVarInt(); // view distance
                pr.readVarInt(); // simulation distance
                pr.readBoolean(); // reduced debug info
                pr.readBoolean(); // enable respawn screen
                pr.readBoolean(); // limited crafting
                readSpawnInfo(pr, bot);
                bot.world.clear();
                bot.playerLoaded = false;
                playerLoadedSent.set(false);
                playerLoadedScheduled.set(false);
                loadGeneration.incrementAndGet();
                System.out.println("[world] login dimension=" + bot.world.dimensionKey()
                        + " type=" + bot.world.dimensionType()
                        + " minY=" + bot.world.minY() + " height=" + bot.world.height());
                break;
            }
            case 0x54: { // respawn
                PacketReader pr = new PacketReader(data);
                readSpawnInfo(pr, bot);
                bot.world.clear();
                bot.dead = false;
                bot.health = 20.0f;
                bot.food = 20;
                bot.playerLoaded = false;
                playerLoadedSent.set(false);
                playerLoadedScheduled.set(false);
                loadGeneration.incrementAndGet();
                System.out.println("[world] respawn dimension=" + bot.world.dimensionKey()
                        + " type=" + bot.world.dimensionType()
                        + " minY=" + bot.world.minY() + " height=" + bot.world.height());
                break;
            }
            case 0x0B: { // chunk_batch_finished
                PacketReader pr = new PacketReader(data);
                int batchSize = pr.readVarInt();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                PacketWriter.floatVal(body, 10.0f);
                bot.sendPlay(0x0B, body.toByteArray()); // chunk_batch_received

                if (playerLoadedSent.compareAndSet(false, true)) {
                    bot.playerLoaded = true;
                    bot.sendPlay(0x2C, new byte[0]); // player_loaded
                    System.out.println("[world] chunk batch finished size=" + batchSize
                            + ", player_loaded sent (chunks=" + bot.world.chunkCount() + ")");
                }
                break;
            }
            case 0x27: { // game_event
                PacketReader pr = new PacketReader(data);
                int event = pr.readUnsignedByte();
                float value = pr.readFloat();
                if (event == 13) {
                    System.out.println("[world] server waiting for chunks to load");
                } else if (event == 3) {
                    int mode = (int) value;
                    bot.gameMode = mode;
                    bot.creative = mode == 1;
                    System.out.println("[world] game mode changed to " + mode);
                }
                break;
            }
            case 0x41: { // player_abilities
                PacketReader pr = new PacketReader(data);
                int flags = pr.readUnsignedByte();
                pr.readFloat(); // flying speed
                pr.readFloat(); // walking speed
                boolean instabuild = (flags & 0x08) != 0;
                bot.creative = instabuild;
                System.out.println("[world] abilities flags=" + flags
                        + " invulnerable=" + ((flags & 0x01) != 0)
                        + " flying=" + ((flags & 0x02) != 0)
                        + " canFly=" + ((flags & 0x04) != 0)
                        + " instantBuild=" + instabuild);
                break;
            }
            case 0x7C:   // system_chat
            case 0x21:   // disguised_chat (command feedback / server messages)
            case 0x59: { // set_action_bar_text
                System.out.println("[chat-in] id=0x" + Integer.toHexString(packetId)
                        + " text=" + asciiRuns(data));
                break;
            }
            default:
                break;
        }
    }

    /**
     * Fallback for servers that do not send chunk_batch_finished. The vanilla
     * client sends player_loaded as soon as the first batch is in; waiting a
     * little lets the initial chunk stream settle without stalling movement.
     */
    private void schedulePlayerLoaded(MinecraftBot bot) {
        if (playerLoadedSent.get()) return;
        if (!playerLoadedScheduled.compareAndSet(false, true)) return;
        long gen = loadGeneration.get();
        new Thread(() -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (gen != loadGeneration.get()) return; // world changed while waiting
            if (playerLoadedSent.compareAndSet(false, true)) {
                bot.playerLoaded = true;
                try {
                    bot.sendPlay(0x2C, new byte[0]); // player_loaded
                    System.out.println("[world] player_loaded sent after chunk fallback (chunks="
                            + bot.world.chunkCount() + ")");
                } catch (Exception e) {
                    System.out.println("[world] player_loaded fallback error: " + e);
                }
            }
        }, "player-loaded").start();
    }

    /** Printable runs of a packet body (NBT strings are length-prefixed UTF-8). */
    private static String asciiRuns(byte[] data) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127) {
                run.append((char) c);
            } else {
                if (run.length() >= 3) out.append('"').append(run).append("\" ");
                run.setLength(0);
            }
        }
        if (run.length() >= 3) out.append('"').append(run).append('"');
        return out.toString().trim();
    }

    private static void readSpawnInfo(PacketReader pr, MinecraftBot bot) {
        int dimensionType = pr.readVarInt();
        String dimensionKey = pr.readString();
        bot.world.setDimension(dimensionKey, dimensionType);
        long seed = pr.readLong();
        int gameMode = pr.readUnsignedByte();
        bot.gameMode = gameMode;
        bot.creative = gameMode == 1;
        System.out.println("[world] gameMode=" + gameMode
                + (gameMode == 1 ? " (creative)" : gameMode == 2 ? " (adventure)"
                : gameMode == 3 ? " (spectator)" : " (survival)")
                + " seed=" + seed);
    }
}
