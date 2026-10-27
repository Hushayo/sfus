package mcbot.modules;

import mcbot.ai.OllamaClient;
import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Chat AI pipeline.
 *
 * One worker thread answers messages in order. Every network call runs behind a
 * hard deadline, so a stalled HTTP request can never wedge the bot again: the
 * message that caused it is abandoned, the failure is logged, and the next
 * message is still answered.
 */
public class AIChatModule implements BotModule {
    private static final long MAX_AGE_MS = OllamaClient.MEMORY_WINDOW_MS;
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_PENDING = 8;
    private static final long REPLY_DEADLINE_MS = 55_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String apiKey;
    private final List<Entry> memory = new ArrayList<>();
    private final BlockingQueue<Job> pending = new LinkedBlockingQueue<>(MAX_PENDING);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-chat-thread");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService net = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-net");
        t.setDaemon(true);
        return t;
    });
    private volatile MinecraftBot bot;

    public AIChatModule(String apiKey) {
        // Resolved lazily from argument / OLLAMA_API_KEY / -Dmcbot.ollama.key.
        this.apiKey = apiKey;
    }

    private static final class Entry {
        final long time;
        final String role;
        final String content;
        Entry(long time, String role, String content) {
            this.time = time;
            this.role = role;
            this.content = content;
        }
    }

    /** One queued message plus the conversation as it looked when it arrived. */
    private static final class Job {
        final String text;
        final List<String[]> history;
        final UUID sender;
        Job(String text, List<String[]> history, UUID sender) {
            this.text = text;
            this.history = history;
            this.sender = sender;
        }
    }

    private static void log(String msg) {
        System.out.println("[ai " + LocalTime.now().format(TIME) + "] " + msg);
    }

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
        worker.submit(this::loop);
    }

    private synchronized void remember(String role, String content) {
        long now = System.currentTimeMillis();
        memory.add(new Entry(now, role, content));
        prune(now);
    }

    private synchronized List<String[]> snapshot() {
        long now = System.currentTimeMillis();
        prune(now);
        List<String[]> out = new ArrayList<>(memory.size());
        for (Entry e : memory) out.add(new String[]{e.role, e.content});
        return out;
    }

    private synchronized void prune(long now) {
        memory.removeIf(e -> now - e.time > MAX_AGE_MS);
        while (memory.size() > MAX_ENTRIES) memory.remove(0);
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId != 0x42) return;
        PacketReader pr = new PacketReader(data);
        pr.readVarInt(); // global index
        UUID sender = pr.readUUID();
        pr.readVarInt(); // index
        boolean hasSigBytes = pr.readByte() != 0;
        if (hasSigBytes) pr.readBytes(256);
        String message = pr.readString();

        if (sender.equals(bot.uuid)) return;
        if (message == null || message.trim().isEmpty()) return;

        String text = message.trim();
        if (MovementModule.isMovementCommand(text)) return;
        if (MiningModule.isMiningCommand(text)) return;
        if (ScanModule.isScanCommand(text)) return;
        if (CraftingModule.isCraftCommand(text)) return;
        // Someone spoke to the bot: turn their way while thinking of an answer.
        bot.look.glanceAt(sender, 6000L);
        remember("user", text);
        log("received chat: " + text);

        if (!pending.offer(new Job(text, snapshot(), sender))) {
            log("queue full (" + MAX_PENDING + "), dropped: " + text);
        }
    }

    private void loop() {
        while (true) {
            Job job;
            try {
                job = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                answer(job);
            } catch (Throwable t) {
                // Never let one bad reply (including Errors such as StackOverflowError)
                // kill the only chat worker.
                log("error: " + t);
            }
        }
    }

    private void answer(Job job) {
        long t0 = System.currentTimeMillis();
        MinecraftBot b = bot;
        if (b == null) return;

        List<String[]> history = job.history;

        // Movement tools act on the server on behalf of the sender. The actual
        // path walk runs on its own thread inside MovementModule, so this stays
        // a quick "queued" answer for the model.
        final UUID from = job.sender;
        OllamaClient.Mover mover = new OllamaClient.Mover() {
            @Override
            public String comeHere() {
                return b.movement.comeTo(from);
            }
            @Override
            public String walkTo(double x, double y, double z) {
                return b.movement.goTo(x, y, z);
            }
            @Override
            public String stop() {
                return b.movement.stopMoving();
            }
            @Override
            public String mineBlock(double x, double y, double z) {
                return b.mining.mine((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            }
            @Override
            public String mineHere() {
                return b.mining.mineNear(from);
            }
            @Override
            public String stopMining() {
                b.mining.stop();
                return "Stopped mining.";
            }
            @Override
            public String lookAtPlayer() {
                b.look.glanceAt(from, 8000L);
                return "Looking at the player.";
            }
            @Override
            public String scanBlocks(double radius) {
                return b.scanner.runScan((int) Math.max(0, Math.min(128, radius)));
            }
            @Override
            public String attackNearest() {
                return b.survival.engageNearest();
            }
            @Override
            public String useBlock(double x, double y, double z) {
                return b.interaction.useBlock((int) Math.floor(x),
                        (int) Math.floor(y), (int) Math.floor(z));
            }
        };

        log("generating reply...");
        String reply = callWithDeadline("completion", REPLY_DEADLINE_MS,
                () -> OllamaClient.reply(apiKey, b.username, history, mover, AIChatModule::log));

        long ms = System.currentTimeMillis() - t0;
        if (reply != null && !reply.isEmpty()) {
            log("reply (" + ms + " ms): " + reply);
            remember("assistant", reply);
            try {
                b.sendChat(reply);
            } catch (Exception e) {
                log("send failed: " + e);
            }
        } else {
            log("no reply generated (" + ms + " ms)");
        }
    }

    private String callWithDeadline(String label, long deadlineMs, Callable<String> task) {
        Future<String> f = net.submit(task);
        try {
            return f.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            log(label + " timed out after " + deadlineMs + " ms - abandoned");
            return null;
        } catch (Exception e) {
            f.cancel(true);
            log(label + " failed: " + e);
            return null;
        }
    }
}
