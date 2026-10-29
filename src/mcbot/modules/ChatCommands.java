package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.jobs.CraftJob;
import mcbot.jobs.JobRunner;
import mcbot.jobs.MineJob;
import mcbot.jobs.SmeltJob;
import mcbot.net.PacketIds;
import mcbot.net.PacketReader;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place chat is turned into an intent.
 *
 * Players ask for outcomes, not for coordinates:
 *
 * <pre>
 *   mine 10 stone here          craft me a pickaxe
 *   get 32 oak logs             smelt 10 iron ore
 *   come here / stop            where are you
 * </pre>
 *
 * There are deliberately no commands for scanning, opening containers or
 * clicking slots. Those are things the bot does *for* an intent, not things a
 * player should have to spell out - and a chat command that clicks a raw slot
 * number is a way to make the bot act without knowing what it is doing.
 *
 * Parsing happens once, here, and then dispatches. Previously five modules each
 * parsed the same chat packet independently and matched overlapping patterns,
 * so a message could be answered twice.
 */
public class ChatCommands implements BotModule {
    /** "mine 10 stone here", "dig oak logs", "chop 3 trees" */
    private static final Pattern MINE = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:mine|dig|chop|break)\\s+(?:(\\d+)\\s+)?(.+?)\\s*$");
    /** "get 32 oak logs", "gather 10 wood" */
    private static final Pattern GET = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:get|gather|collect|fetch)\\s+(?:(\\d+)\\s+)?(.+?)\\s*$");
    /** "craft me a pickaxe", "make 4 sticks" */
    private static final Pattern CRAFT = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:craft|make|build)\\s+(?:me\\s+)?(?:(\\d+)\\s+)?(?:an?\\s+|some\\s+)?(.+?)\\s*$");
    /** "smelt 10 iron ore", "cook 5 porkchops" */
    private static final Pattern SMELT = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:smelt|cook|bake)\\s+(?:(\\d+)\\s+)?(.+?)\\s*$");
    /** "goto 100 64 -20" - kept for testing, not advertised */
    private static final Pattern GOTO = Pattern.compile(
            "(?i)^\\s*(?:goto|go\\s+to|walk\\s+to)\\s+(-?\\d+(?:\\.\\d+)?)\\s+" +
                    "(-?\\d+(?:\\.\\d+)?)(?:\\s+(-?\\d+(?:\\.\\d+)?))?\\s*$");

    private static final Pattern STOP = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:stop|cancel|halt|nevermind|never mind)(?:\\s+.*)?$");
    private static final Pattern COME = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:come|come here|come to me|follow me)\\s*$");
    private static final Pattern STATUS = Pattern.compile(
            "(?i)^(?:where are you|what are you doing|status|what'?s up)\\s*\\??$");
    private static final Pattern INVENTORY = Pattern.compile(
            "(?i)^(?:inventory|inv|what do you have|items)\\s*\\??$");
    private static final Pattern HELP = Pattern.compile(
            "(?i)^(?:help|commands)\\s*\\??$");

    // ------------------------------------------------------- new mechanics --

    /** "eat", "eat something", "have a snack" */
    private static final Pattern EAT = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:eat|eat something|have something to eat|have a snack|"
                    + "eat some food|feed yourself)\\s*$");
    /** "equip an iron pickaxe", "wear the helmet", "put on boots" */
    private static final Pattern EQUIP = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:equip|wear|put on|hold)\\s+(?:the\\s+|a\\s+|an\\s+|my\\s+)?(.+?)\\s*$");
    /** "gear up", "armour on", "equip your armour" */
    private static final Pattern GEAR_UP = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:gear up|armour on|armor on|equip (?:your )?(?:armour|armor)|"
                    + "put on (?:your )?(?:armour|armor))\\s*$");
    /** "defend", "fight", "attack", "kill it" */
    private static final Pattern DEFEND = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:defend|fight|attack|kill|kill it|defend yourself|"
                    + "fight back|protect yourself)\\s*$");
    /** "attack the player", "kill ManalaHush", "hit that guy" */
    private static final Pattern ATTACK_TARGET = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:attack|kill|hit|fight)\\s+(?:the\\s+|that\\s+|this\\s+)?(.+?)\\s*$");
    /** "flee", "run away", "retreat" */
    private static final Pattern FLEE = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:flee|run away|retreat|get away|escape)\\s*$");
    /** "drop 10 dirt", "throw away the cobblestone" */
    private static final Pattern DROP = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:drop|throw away|toss|discard)\\s+(?:(\\d+)\\s+)?(.+?)\\s*$");
    /** "build a wall", "build a shelter", "build a platform" */
    private static final Pattern BUILD = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:build|make)\\s+(?:me\\s+)?(?:a\\s+|an\\s+)?"
                    + "(wall|shelter|house|platform|floor)\\s*$");
    /** "sleep", "go to bed" */
    private static final Pattern SLEEP = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:sleep|go to bed|go to sleep)\\s*$");

    /** Words that mean "right here", stripped before resolving the target. */
    private static final Pattern LOCATION_SUFFIX = Pattern.compile(
            "(?i)\\s+(?:here|around|nearby|near\\s+me|right\\s+here|in\\s+front\\s+of\\s+me|please)\\s*$");

    private volatile MinecraftBot bot;
    private volatile boolean startupRan = false;
    private int tickCounter = 0;

    /** File polled for commands, so a headless bot can be driven without chat. */
    private static final java.io.File COMMAND_FILE =
            new java.io.File(System.getenv().getOrDefault("BOT_CMD_FILE", "bot.cmd"));

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    /**
     * Two out-of-band command sources, both for running the bot without a
     * player to type at it:
     *
     *  - {@code BOT_JOB="mine 10 stone"} runs once when the world is ready
     *  - {@code bot.cmd} is polled once a second; its contents are handled as
     *    if a player had typed them, then the file is deleted
     *
     * The file channel is what makes a 24/7 bot drivable from a shell, a cron
     * job or another process.
     */
    @Override
    public void onTick(MinecraftBot bot) {
        if (!startupRan) {
            startupOnce(bot);
            return;
        }
        if (!bot.playerLoaded || !bot.hasPlayerPosition) return;
        if (++tickCounter % 20 != 0) return;
        pollCommandFile(bot);
    }

    private void pollCommandFile(MinecraftBot bot) {
        if (!COMMAND_FILE.isFile()) return;
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(COMMAND_FILE.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            java.nio.file.Files.deleteIfExists(COMMAND_FILE.toPath());
        } catch (Exception e) {
            return;
        }
        if (text.isEmpty()) return;
        System.out.println("[cmd] file command: " + text);
        if (!handle(bot, bot.uuid, text)) {
            System.out.println("[cmd] not understood: " + text);
        }
    }

    private void startupOnce(MinecraftBot bot) {
        String command = System.getenv("BOT_JOB");
        String say = System.getenv("BOT_SAY");
        if ((command == null || command.isBlank()) && (say == null || say.isBlank())) {
            startupRan = true;
            return;
        }
        if (!bot.playerLoaded || !bot.hasPlayerPosition) return;
        startupRan = true;
        new Thread(() -> {
            try {
                Thread.sleep(3000L); // let the spawn area settle
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (say != null && !say.isBlank()) {
                // Headless way to exercise the inbound chat path: a second bot
                // says something and the first one has to parse it.
                System.out.println("[cmd] startup say: " + say);
                try {
                    // A leading slash is a server command (/msg, /login), which
                    // cannot travel in a chat packet.
                    if (say.startsWith("/")) bot.sendCommand(say);
                    else bot.sendChat(say);
                } catch (Exception e) {
                    System.out.println("[cmd] say failed: " + e);
                }
                return;
            }
            System.out.println("[cmd] startup job: " + command);
            if (!handle(bot, bot.uuid, command)) {
                System.out.println("[cmd] startup job not understood: " + command);
            }
        }, "startup-job").start();
    }

    // ------------------------------------------------------------- dispatch --

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        // Whispers arrive as disguised_chat with a target name; the sender is a
        // display name, not a UUID, so commands that need a position fall back
        // to saying they need coordinates.
        if (packetId == PacketIds.DISGUISED_CHAT) {
            String[] whisper = mcbot.net.ChatText.whisper(data);
            if (whisper == null) return;
            if (whisper[0].equalsIgnoreCase(bot.username)) return;
            WHISPER_TO.set(whisper[0]);
            try {
                handle(bot, null, whisper[1]);
            } finally {
                WHISPER_TO.remove();
            }
            return;
        }
        if (packetId != PacketIds.PLAYER_CHAT) return;

        PacketReader pr = new PacketReader(data);
        pr.readVarInt();                       // global index
        UUID sender = pr.readUUID();
        pr.readVarInt();                       // index
        if (pr.readByte() != 0) pr.readBytes(256);
        String message = pr.readString();
        if (message == null || message.isBlank()) return;
        if (sender.equals(bot.uuid)) return;
        bot.knownPlayers.add(sender);

        handle(bot, sender, message.trim());
    }

    /** True when the text is something this module answers (so the AI stays quiet). */
    public static boolean isCommand(String text) {
        if (text == null) return false;
        String t = text.trim();
        return MINE.matcher(t).matches() || GET.matcher(t).matches()
                || CRAFT.matcher(t).matches() || SMELT.matcher(t).matches()
                || GOTO.matcher(t).matches() || STOP.matcher(t).matches()
                || COME.matcher(t).matches() || STATUS.matcher(t).matches()
                || INVENTORY.matcher(t).matches() || HELP.matcher(t).matches()
                || EAT.matcher(t).matches() || EQUIP.matcher(t).matches()
                || GEAR_UP.matcher(t).matches() || DEFEND.matcher(t).matches()
                || ATTACK_TARGET.matcher(t).matches()
                || FLEE.matcher(t).matches() || DROP.matcher(t).matches()
                || BUILD.matcher(t).matches() || SLEEP.matcher(t).matches()
                || mcbot.api.Reasoner.isKnowledgeQuestion(t);
    }

    /** Parses and runs one message. Public so the AI tool layer can reuse it. */
    public boolean handle(MinecraftBot bot, UUID sender, String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        if (STOP.matcher(text).matches()) {
            boolean had = bot.jobs.cancel();
            bot.movement.stop();
            bot.mining.stop();
            say(bot, had ? "Stopped." : "I wasn't doing anything.");
            return true;
        }
        if (COME.matcher(text).matches()) {
            say(bot, bot.movement.comeTo(sender));
            return true;
        }
        if (STATUS.matcher(text).matches()) {
            say(bot, new mcbot.api.SelfModel(bot).toText());
            return true;
        }
        if (INVENTORY.matcher(text).matches()) {
            say(bot, describeInventory(bot));
            return true;
        }
        if (HELP.matcher(text).matches()) {
            say(bot, "Ask me to mine, craft, smelt, eat, equip, build, defend or come to you. "
                    + "For example: \"mine 10 stone\", \"craft me a pickaxe\", \"eat\", "
                    + "\"build a wall\", \"where do I get iron\", \"what can you make\".");
            return true;
        }

        // ---- mechanics the bot does for itself -------------------------------
        if (EAT.matcher(text).matches()) {
            say(bot, bot.survival.eatNow());
            return true;
        }
        if (GEAR_UP.matcher(text).matches()) {
            say(bot, "Gearing up.");
            bot.jobs.start(new mcbot.jobs.ActionJob(bot, "gear up",
                    () -> bot.actions.equipBestArmor()));
            return true;
        }
        if (DEFEND.matcher(text).matches()) {
            say(bot, "Defending myself.");
            bot.jobs.start(new mcbot.jobs.ActionJob(bot, "defend", () -> bot.actions.defend()));
            return true;
        }
        Matcher am = ATTACK_TARGET.matcher(text);
        if (am.matches()) {
            String raw = am.group(1).trim();
            String name = raw.replaceAll("(?i)^(the|that|this)\\s+", "").trim();
            // "attack ManalaHush" resolves through the tab list; "attack the
            // player" means the nearest one; anything else ("kill the zombie")
            // is the normal mob-first defend.
            if (bot.playerList != null && bot.playerList.known(name)) {
                say(bot, "Attacking " + name + ".");
                bot.jobs.start(new mcbot.jobs.ActionJob(bot, "attack " + name,
                        () -> bot.actions.attackPlayer(name)));
            } else if (name.toLowerCase(Locale.ROOT).contains("player")) {
                say(bot, "Attacking the nearest player.");
                bot.jobs.start(new mcbot.jobs.ActionJob(bot, "attack player",
                        () -> bot.actions.attackPlayer(null)));
            } else {
                say(bot, "Attacking " + raw + ".");
                bot.jobs.start(new mcbot.jobs.ActionJob(bot, "attack " + raw,
                        () -> bot.actions.defend()));
            }
            return true;
        }
        if (FLEE.matcher(text).matches()) {
            say(bot, bot.actions.flee().message);
            return true;
        }
        if (SLEEP.matcher(text).matches()) {
            say(bot, "Looking for a bed.");
            bot.jobs.start(new mcbot.jobs.ActionJob(bot, "sleep", () -> bot.actions.sleep()));
            return true;
        }

        // BUILD has to be tested before CRAFT: "build a wall" is also "build X".
        Matcher bm = BUILD.matcher(text);
        if (bm.matches()) {
            return build(bot, bm.group(1).toLowerCase(Locale.ROOT));
        }

        Matcher em = EQUIP.matcher(text);
        if (em.matches()) {
            String target = em.group(1).trim();
            say(bot, "Equipping " + target + ".");
            bot.jobs.start(new mcbot.jobs.ActionJob(bot, "equip " + target,
                    () -> bot.actions.equip(target)));
            return true;
        }

        Matcher dm = DROP.matcher(text);
        if (dm.matches()) {
            int count = dm.group(1) == null ? Integer.MAX_VALUE : Integer.parseInt(dm.group(1));
            String item = dm.group(2);
            say(bot, "Dropping " + item + ".");
            bot.jobs.start(new mcbot.jobs.ActionJob(bot, "drop " + item,
                    () -> bot.actions.drop(item, count)));
            return true;
        }

        Matcher m = GOTO.matcher(text);
        if (m.matches()) {
            double x = Double.parseDouble(m.group(1));
            double y = m.group(3) != null ? Double.parseDouble(m.group(2)) : currentY(bot);
            double z = m.group(3) != null ? Double.parseDouble(m.group(3))
                    : Double.parseDouble(m.group(2));
            say(bot, bot.movement.goTo(x, y, z));
            return true;
        }

        m = MINE.matcher(text);
        if (m.matches()) {
            return mine(bot, sender, m.group(1), m.group(2));
        }
        m = GET.matcher(text);
        if (m.matches()) {
            return get(bot, m.group(1), m.group(2));
        }
        m = CRAFT.matcher(text);
        if (m.matches()) {
            return craft(bot, m.group(1), m.group(2));
        }
        m = SMELT.matcher(text);
        if (m.matches()) {
            return smelt(bot, sender, m.group(1), m.group(2));
        }

        // Deterministic answers about the game, the world and itself. Anything
        // this cannot answer exactly is left for the language model.
        String reasoned = bot.reasoner.answer(text);
        if (reasoned != null) {
            say(bot, reasoned);
            return true;
        }
        return false;
    }

    /**
     * "build a wall" - picks the best building block the bot owns.
     *
     * <p>Preference is deliberate: cobblestone and dirt are what a bot has in
     * bulk, and using planks on a wall wastes the material it needs for tools.
     */
    private boolean build(MinecraftBot bot, String what) {
        String block = bestBuildingBlock(bot);
        if (block == null) {
            say(bot, "I have nothing to build with.");
            return true;
        }
        say(bot, "Building a " + what + " out of " + mcbot.registry.ItemNames.pretty(block) + ".");
        bot.jobs.start(new mcbot.jobs.ActionJob(bot, "build " + what, () -> {
            switch (what) {
                case "wall": return bot.actions.wall(block, 4, 2);
                case "platform":
                case "floor": return bot.actions.platform(block, 2);
                default: return bot.actions.shelter(block);
            }
        }));
        return true;
    }

    /** The most plentiful block item that is worth building with. */
    private static String bestBuildingBlock(MinecraftBot bot) {
        String[] order = {"cobblestone", "cobbled_deepslate", "dirt", "oak_planks", "stone",
                "deepslate", "andesite", "diorite", "granite", "netherrack", "sand", "oak_log"};
        for (String name : order) {
            if (bot.interaction.inventory.count(name) >= 4) return name;
        }
        return null;
    }

    /**
     * Runs one message and returns the answer instead of saying it in chat.
     *
     * The AI tool layer uses this: the model asked for the outcome and will
     * phrase the reply itself, so the command's own line would be a second,
     * redundant message. It is still printed to the log.
     *
     * @return what the command would have said, or null when nothing matched
     */
    public String run(MinecraftBot bot, UUID sender, String text) {
        StringBuilder said = new StringBuilder();
        CAPTURE.set(said);
        try {
            if (!handle(bot, sender, text)) return null;
            return said.toString();
        } finally {
            CAPTURE.remove();
        }
    }

    /** Non-null while a whisper is being answered: the name to /msg back. */
    private static final ThreadLocal<String> WHISPER_TO = new ThreadLocal<>();

    /** Non-null while {@link #run} is collecting a command's answer. */
    private static final ThreadLocal<StringBuilder> CAPTURE = new ThreadLocal<>();

    /** Words that never name an item, skipped when picking a target out of a sentence. */
    private static final java.util.Set<String> FILLER = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "some", "any", "i", "im", "you", "oyu", "u", "me", "my", "we", "us",
            "need", "want", "to", "of", "for", "and", "then", "please", "can", "could", "would", "should",
            "now", "more", "all", "them", "it", "that", "this", "these", "those", "here", "there",
            "ok", "okay", "just", "stuff", "thing", "things", "block", "blocks", "item", "items",
            "up", "pick", "grab", "get", "go", "have", "has", "with", "from", "at", "on", "in"));

    /** First number in a phrase, so "mine the coals I need 4 of" still counts. */
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3})");

    // -------------------------------------------------------------- intents --

    private boolean mine(MinecraftBot bot, UUID sender, String countText, String phrase) {
        String target = stripLocation(phrase);
        if (target.isEmpty()) return false;
        int count = countText == null ? countIn(target) : Integer.parseInt(countText);

        String block = blockInPhrase(target);
        if (block == null) {
            // Not a block: maybe they named an item ("mine 10 diamonds").
            String item = itemInPhrase(target);
            String source = item == null ? null : mcbot.crafting.Recipes.mineSource(item);
            if (source != null) {
                block = source;
            } else if (item != null && ItemNames.isBlock(item)) {
                block = item;
            }
        }
        if (block == null) {
            say(bot, "I don't know what " + ItemNames.pretty(target) + " is.");
            return true;
        }

        int already = bot.interaction.inventory.count(dropOf(block));
        if (already >= count) {
            say(bot, "I already have " + already + " " + ItemNames.pretty(dropOf(block)) + ".");
            return true;
        }

        // Wrong-tool mining is worse than no mining, but the old answer here was
        // "I need a stone pickaxe" and nothing else: the player then had to ask
        // for logs, planks, sticks, a wooden pickaxe and stone by hand. The job
        // can plan and build the tool (logs -> planks -> sticks -> pickaxe), so
        // only refuse when even that plan is impossible.
        int blockState = mcbot.registry.Blocks.defaultState(block);
        if (blockState >= 0 && !bot.interaction.inventory.hasToolFor(blockState)) {
            String tool = mcbot.crafting.Recipes.requiredToolFor(block);
            if (tool == null) {
                say(bot, "I don't have the " + bot.interaction.inventory.missingToolFor(blockState)
                        + " for " + ItemNames.pretty(block) + ".");
                return true;
            }
            var toolPlan = mcbot.crafting.Recipes.plan(tool, 1, bot.interaction.inventory);
            if (!toolPlan.ok()) {
                say(bot, "I need a " + ItemNames.pretty(tool) + " to mine "
                        + ItemNames.pretty(block) + " and can't make one: " + toolPlan.error + ".");
                return true;
            }
            say(bot, "I need a " + ItemNames.pretty(tool) + " first: "
                    + mcbot.crafting.Recipes.describe(toolPlan) + ".");
        }

        int remaining = count - already;
        // Search around the player who asked, when they are close enough that
        // "here" plausibly means "where I am standing". This is what makes
        // "mine 4 oak logs" work on a row of trees someone placed for testing
        // instead of walking to the nearest forest.
        double[] center = null;
        if (sender != null && !sender.equals(bot.uuid)) {
            double[] pose = bot.entityTracker.poseOf(sender);
            if (pose != null) {
                double bx, bz;
                synchronized (bot.positionLock) {
                    bx = bot.playerX;
                    bz = bot.playerZ;
                }
                if (Math.hypot(pose[0] - bx, pose[2] - bz) <= 48.0) {
                    center = pose;
                }
            }
        }
        say(bot, "Mining " + remaining + " " + ItemNames.pretty(block) + ".");
        bot.jobs.start(new MineJob(bot, block, remaining, 32, center));
        return true;
    }

    /** "get 32 oak logs" - plan the item, gather or craft whatever is missing. */
    private boolean get(MinecraftBot bot, String countText, String phrase) {
        String target = stripLocation(phrase);
        int count = countText == null ? countIn(target) : Integer.parseInt(countText);
        String item = itemInPhrase(target);
        if (item == null) {
            say(bot, "I don't know what " + ItemNames.pretty(target) + " is.");
            return true;
        }
        int have = bot.interaction.inventory.count(item);
        if (have >= count) {
            say(bot, "I already have " + have + " " + plural(ItemNames.pretty(item), have) + ".");
            return true;
        }
        var plan = mcbot.crafting.Recipes.plan(item, count, bot.interaction.inventory);
        if (!plan.ok()) {
            say(bot, "I can't get " + ItemNames.pretty(item) + ": " + plan.error + ".");
            return true;
        }
        say(bot, "Getting " + count + " " + plural(ItemNames.pretty(item), count) + ": "
                + mcbot.crafting.Recipes.describe(plan) + ".");
        // The job makes exactly what it is asked for, so it gets the deficit:
        // asking for the full count would add `have` on top of the target.
        bot.jobs.start(new CraftJob(bot, item, count - have));
        return true;
    }

    private boolean craft(MinecraftBot bot, String countText, String phrase) {
        String target = stripLocation(phrase);
        int count = countText == null ? countIn(target) : Integer.parseInt(countText);
        String item = itemInPhrase(target);
        if (item == null) {
            say(bot, "I don't know how to make " + ItemNames.pretty(target) + ".");
            return true;
        }
        var plan = mcbot.crafting.Recipes.plan(item, count, bot.interaction.inventory);
        if (!plan.ok()) {
            say(bot, "I can't make " + ItemNames.pretty(item) + ": " + plan.error + ".");
            return true;
        }
        if (plan.steps.isEmpty()) {
            // "craft a stick" when four are already in the bag used to answer
            // "Crafting 1 stick (I already have it.)" and start a job that did
            // nothing.
            int have = bot.interaction.inventory.count(item);
            say(bot, "I already have " + have + " " + plural(ItemNames.pretty(item), have) + ".");
            return true;
        }
        say(bot, "Crafting " + count + " " + plural(ItemNames.pretty(item), count) + " ("
                + mcbot.crafting.Recipes.describe(plan) + ").");
        bot.jobs.start(new CraftJob(bot, item, count));
        return true;
    }

    private boolean smelt(MinecraftBot bot, UUID sender, String countText, String phrase) {
        String target = stripLocation(phrase);
        int count = countText == null ? countIn(target) : Integer.parseInt(countText);
        String item = itemInPhrase(target);
        if (item == null) {
            say(bot, "I don't know what " + ItemNames.pretty(target) + " is.");
            return true;
        }
        // "iron ore", "iron" and "raw iron" all name the same thing on the
        // input side, and which one the bot is carrying depends on how it was
        // gathered. Resolve to what is actually in the inventory instead of
        // insisting on the exact name the player happened to say.
        List<String> candidates = smeltInputs(item);
        String input = null;
        for (String c : candidates) {
            if (bot.interaction.inventory.count(c) > 0) {
                input = c;
                break;
            }
        }
        if (input == null) {
            say(bot, "I have no " + ItemNames.pretty(candidates.get(0)) + " to smelt.");
            return true;
        }
        say(bot, "Smelting " + count + " " + ItemNames.pretty(input) + ".");
        // Prefer the furnace the requester is standing next to: a 32-block scan
        // otherwise picks one in a cave and the bot walks off while the player
        // watches it leave the furnace right beside them.
        double[] center = null;
        if (sender != null && !sender.equals(bot.uuid)) {
            double[] pose = bot.entityTracker.poseOf(sender);
            if (pose != null) {
                double bx, bz;
                synchronized (bot.positionLock) {
                    bx = bot.playerX;
                    bz = bot.playerZ;
                }
                if (Math.hypot(pose[0] - bx, pose[2] - bz) <= 48.0) {
                    center = pose;
                }
            }
        }
        bot.jobs.start(new SmeltJob(bot, input, count, center));
        return true;
    }

    /**
     * The items that smelt into the same thing, most literal first.
     *
     * Iron is the motivating case: a player says "smelt iron ore" because that
     * is what the block is called, but what drops from it is {@code raw_iron},
     * so looking only for {@code iron_ore} reports "I have no iron ore" while
     * the bot is holding five of the things it was asked to smelt.
     */
    private static List<String> smeltInputs(String item) {
        List<String> out = new ArrayList<>();
        out.add(item);
        String base = item.replaceFirst("^raw_", "")
                .replaceFirst("_ore$", "")
                .replaceFirst("_ingot$", "");
        if (!base.equals(item)) {
            out.add("raw_" + base);
            out.add(base + "_ore");
        }
        // Smelts where the input is not named after the output at all.
        if (item.equals("glass") || item.equals("stone") || item.equals("smooth_stone")) {
            out.add(item.equals("stone") ? "cobblestone" : "sand");
        }
        List<String> valid = new ArrayList<>(out.size());
        for (String c : out) {
            if (Items.get(c).id != 0 && !valid.contains(c)) valid.add(c);
        }
        return valid.isEmpty() ? out : valid;
    }

    // -------------------------------------------------------------- helpers --

    /** The item a block drops, for "do I already have enough" checks. */
    private static String dropOf(String block) {
        String drop = mcbot.crafting.Recipes.dropOf(block);
        return drop == null ? block : drop;
    }

    /**
     * The block a phrase is asking for, even when the phrase is a sentence.
     *
     * Whole-phrase matching runs first, so "oak logs" still wins over "oak".
     * When that fails, every word is tried and the first one that names a block
     * wins: "mine the coals I need oyu to mine 4" has to mean coal, not a block
     * literally called that.
     */
    private static String blockInPhrase(String phrase) {
        String direct = ItemNames.resolveBlock(phrase);
        if (direct != null) return direct;
        for (String word : phrase.split("\\s+")) {
            String w = cleanWord(word);
            if (w.isEmpty() || FILLER.contains(w)) continue;
            String block = ItemNames.resolveBlock(w);
            if (block != null) return block;
        }
        return null;
    }

    /** Same as {@link #blockInPhrase}, for items rather than blocks. */
    private static String itemInPhrase(String phrase) {
        String direct = ItemNames.resolveItem(phrase);
        if (direct != null) return direct;
        for (String word : phrase.split("\\s+")) {
            String w = cleanWord(word);
            if (w.isEmpty() || FILLER.contains(w)) continue;
            String item = ItemNames.resolveItem(w);
            if (item != null) return item;
        }
        return null;
    }

    private static String cleanWord(String word) {
        return word.replaceAll("[^A-Za-z0-9_']", "").toLowerCase(Locale.ROOT);
    }

    /** "4 sticks", not "4 stick". */
    private static String plural(String name, int count) {
        if (count == 1 || name.endsWith("s")) return name;
        return name + "s";
    }

    /** First number in the phrase, or 1. */
    private static int countIn(String phrase) {
        Matcher m = NUMBER.matcher(phrase);
        if (!m.find()) return 1;
        try {
            return Math.max(1, Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String stripLocation(String phrase) {
        if (phrase == null) return "";
        String s = phrase.trim();
        String previous;
        do {
            previous = s;
            s = LOCATION_SUFFIX.matcher(s).replaceAll("").trim();
        } while (!s.equals(previous));
        return s;
    }

    private static double currentY(MinecraftBot bot) {
        synchronized (bot.positionLock) {
            return bot.playerY;
        }
    }

    public static String describeInventory(MinecraftBot bot) {
        var inv = bot.interaction.inventory;
        var contents = inv.contents();
        StringBuilder sb = new StringBuilder("I have ");
        int shown = 0;
        for (var entry : contents) {
            if (shown >= 8) {
                sb.append(", and more");
                break;
            }
            if (shown++ > 0) sb.append(", ");
            sb.append(entry.getValue()).append(' ').append(ItemNames.pretty(entry.getKey()));
        }
        if (shown == 0) sb.append("almost nothing");
        sb.append(". Holding ").append(inv.held().isEmpty() ? "nothing"
                : ItemNames.pretty(inv.held().name()));
        return sb.toString();
    }

    private void say(MinecraftBot bot, String message) {
        System.out.println("[cmd] " + message);
        StringBuilder capture = CAPTURE.get();
        if (capture != null) {
            capture.append(message);
            return;
        }
        String whisperTo = WHISPER_TO.get();
        try {
            if (whisperTo != null) {
                // Answer a whisper in private: the sender asked quietly.
                bot.sendCommand("/msg " + whisperTo + " " + message);
            } else {
                bot.sendChat(message);
            }
        } catch (Exception e) {
            System.out.println("[cmd] send failed: " + e);
        }
    }
}
