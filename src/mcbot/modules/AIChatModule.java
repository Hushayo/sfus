package mcbot.modules;

import mcbot.ai.OllamaClient;
import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketIds;
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
    /** Player the bot is trailing, or null when it is not following anyone. */
    private volatile UUID followTarget;
    /** True while the follow worker lives, so only one is ever started. */
    private volatile boolean followBusy;
    /** When this module last answered a greeting or farewell from each sender. */
    private final java.util.Map<String, Long> socialReplyAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SOCIAL_COOLDOWN_MS = 30_000L;
    /**
     * Words that ask for nothing. A message made only of these is an
     * acknowledgement, and answering acknowledgements is what feeds a loop.
     */
    private static final java.util.Set<String> SOCIAL_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "hi", "hey", "hello", "yo", "bye", "goodbye", "cya", "see", "ya", "you", "later", "night",
            "thanks", "thank", "thx", "ty", "ok", "okay", "sure", "cool", "nice", "great", "too", "again",
            "all", "good", "morning", "evening", "take", "care", "have", "fun", "talk", "soon", "np",
            "welcome", "and", "the", "so", "much", "a", "it", "i", "im"));

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
        /** Name to /msg the answer back to, for a whispered request. */
        final String replyTo;
        Job(String text, List<String[]> history, UUID sender, String replyTo) {
            this.text = text;
            this.history = history;
            this.sender = sender;
            this.replyTo = replyTo;
        }
    }

    private static void log(String msg) {
        System.out.println("[ai " + LocalTime.now().format(TIME) + "] " + msg);
    }

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
        // Say where the key came from (argument, environment, key file, registry)
        // as soon as the module loads. The alternative is finding out one chat
        // message at a time, and a silence that looks like the bot ignoring you.
        log(OllamaClient.keyStatus(apiKey));
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
        if (packetId == PacketIds.DISGUISED_CHAT) {
            // /msg is delivered as disguised_chat: sender name, no UUID.
            String[] whisper = mcbot.net.ChatText.whisper(data);
            if (whisper == null) return;
            if (whisper[0].equalsIgnoreCase(bot.username)) return;
            accept(bot, whisper[1], whisper[0], null);
            return;
        }
        if (packetId != PacketIds.PLAYER_CHAT) return;
        PacketReader pr = new PacketReader(data);
        pr.readVarInt(); // global index
        UUID sender = pr.readUUID();
        pr.readVarInt(); // index
        boolean hasSigBytes = pr.readByte() != 0;
        if (hasSigBytes) pr.readBytes(256);
        String message = pr.readString();

        if (sender.equals(bot.uuid)) return;
        accept(bot, message, null, sender);
    }

    /**
     * The single entry point for anything a human said, public or whispered.
     *
     * @param whisperFrom sender name of a private message, or null for chat
     * @param sender      UUID of the speaker, or null for a whisper (the
     *                    packet carries a name, not an identity)
     */
    private void accept(MinecraftBot bot, String message, String whisperFrom, UUID sender) {
        if (message == null || message.trim().isEmpty()) return;
        String text = message.trim();
        // One check instead of one per module: everything that is a command has
        // already been answered by ChatCommands, so the model only sees talk.
        if (ChatCommands.isCommand(text)) return;
        // Two chat bots in one server greet and say goodbye to each other
        // forever: every farewell gets an answer, which is itself a farewell.
        // One acknowledgement per sender per half minute ends that.
        if (isSocial(text)) {
            String who = whisperFrom != null ? whisperFrom : String.valueOf(sender);
            long now = System.currentTimeMillis();
            Long last = socialReplyAt.get(who);
            if (last != null && now - last < SOCIAL_COOLDOWN_MS) {
                log("ignoring social echo from the same sender: " + text);
                return;
            }
            socialReplyAt.put(who, now);
        }
        // Someone spoke to the bot: turn their way while thinking of an answer.
        if (sender != null) bot.look.glanceAt(sender, 6000L);
        remember("user", text);
        log(whisperFrom != null ? "whisper from " + whisperFrom + ": " + text
                : "received chat: " + text);

        if (!pending.offer(new Job(text, snapshot(), sender, whisperFrom))) {
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
                if (from == null) return "I can't see where you are - tell me your coordinates.";
                return b.movement.comeTo(from);
            }
            @Override
            public String walkTo(double x, double y, double z) {
                return b.movement.goTo(x, y, z);
            }
            @Override
            public String stop() {
                followTarget = null;
                return b.movement.stopMoving();
            }
            @Override
            public String mineBlock(double x, double y, double z) {
                return b.mining.mine((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            }
            @Override
            public String mineHere() {
                if (from == null) return "I need to see the block you mean - give me x y z.";
                return b.mining.mineNear(from);
            }
            @Override
            public String stopMining() {
                b.mining.stop();
                return "Stopped mining.";
            }
            @Override
            public String lookAtPlayer() {
                if (from == null) return "I can't see you from here.";
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
            // The item intents reuse ChatCommands so there is exactly one
            // implementation of "craft me a pickaxe": the one players already
            // use by typing it. run() collects the answer instead of sending it,
            // because the model phrases the reply.
            @Override
            public String craftItem(String item, int count) {
                return intent(b, from, "craft " + count + " " + item);
            }
            @Override
            public String smeltItem(String item, int count) {
                return intent(b, from, "smelt " + count + " " + item);
            }
            @Override
            public String gatherItem(String item, int count) {
                return intent(b, from, "get " + count + " " + item);
            }
            @Override
            public String listInventory() {
                return ChatCommands.describeInventory(b);
            }
            @Override
            public String followPlayer() {
                if (from == null) return "I can't see you to follow you.";
                startFollow(from);
                return "Following you.";
            }
            @Override
            public String attackPlayer(String name) {
                return b.actions.attackPlayer(name).message;
            }
            @Override
            public String eat() {
                return b.survival.eatNow();
            }
            @Override
            public String equipArmour() {
                b.jobs.start(new mcbot.jobs.ActionJob(b, "equip armour",
                        () -> b.actions.equipBestArmor()));
                return "Gearing up.";
            }
            @Override
            public String defend() {
                b.jobs.start(new mcbot.jobs.ActionJob(b, "defend", () -> b.actions.defend()));
                return "Defending myself.";
            }
            @Override
            public String build(String what) {
                String kind = what == null || what.isBlank() ? "wall" : what.trim().toLowerCase();
                return intent(b, from, "build a " + kind);
            }
            @Override
            public String selfStatus() {
                return b.actions.selfReport();
            }
            @Override
            public String knowledge(String question) {
                String answer = b.reasoner.answer(question);
                return answer != null ? answer : b.reasoner.whatDoYouKnow();
            }
        };

        log("generating reply...");
        String state = b.describeState();
        String reply = callWithDeadline("completion", REPLY_DEADLINE_MS,
                () -> OllamaClient.reply(apiKey, b.username, history, mover, state, AIChatModule::log));

        long ms = System.currentTimeMillis() - t0;
        if (reply != null && !reply.isEmpty()) {
            log("reply (" + ms + " ms): " + reply);
            remember("assistant", reply);
            try {
                if (job.replyTo != null) {
                    // Whisper back: the sender asked privately.
                    b.sendCommand("/msg " + job.replyTo + " " + reply);
                } else {
                    b.sendChat(reply);
                }
            } catch (Exception e) {
                log("send failed: " + e);
            }
        } else {
            log("no reply generated (" + ms + " ms)");
        }
    }

    /**
     * True when the message is only greetings, thanks or farewells.
     * Elongated spellings ("byeeee") collapse so they still count.
     */
    private static boolean isSocial(String text) {
        String[] words = text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z\\s]", " ").trim().split("\\s+");
        if (words.length == 0 || words.length > 6) return false;
        for (String w : words) {
            if (!SOCIAL_WORDS.contains(w.replaceAll("(.)\\1{2,}", "$1"))) return false;
        }
        return true;
    }

    /**
     * Runs a chat intent for the model and returns what it would have said.
     * The command's own chat line is suppressed: the model is about to speak.
     */
    private static String intent(MinecraftBot b, UUID from, String text) {
        String said = b.chat.run(b, from, text);
        return (said == null || said.isEmpty()) ? "I can't do that with what I have." : said;
    }

    /** Starts (or retargets) the follow worker. */
    private void startFollow(UUID target) {
        followTarget = target;
        if (followBusy) return;
        followBusy = true;
        Thread t = new Thread(() -> {
            try {
                followLoop();
            } finally {
                followBusy = false;
            }
        }, "ai-follow");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Trails the player without restarting their walk every second.
     *
     * A new goto while a path is already being followed would reset the
     * pathfinder from wherever the bot happens to stand, so the target is only
     * refreshed when the previous walk has finished. That lags a few blocks
     * behind a sprinting player, which is the honest behaviour of a bot that
     * walks at walking speed.
     */
    private void followLoop() {
        while (true) {
            UUID target = followTarget;
            MinecraftBot b = bot;
            if (target == null || b == null || !b.playerLoaded) {
                followTarget = null;
                return;
            }
            double[] p = b.entityTracker.positionOf(target);
            if (p != null) {
                double bx, bz;
                synchronized (b.positionLock) {
                    bx = b.playerX;
                    bz = b.playerZ;
                }
                if (!b.movement.isMoving() && Math.hypot(p[0] - bx, p[2] - bz) > 4.0) {
                    b.movement.goTo(p[0], p[1], p[2]);
                }
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
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
