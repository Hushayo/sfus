package mcbot.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class OllamaClient {
    /**
     * No key is compiled in. This repository is public, and a committed key is a
     * leaked key. Provide one of:
     *   5th command line argument, OLLAMA_API_KEY, -Dmcbot.ollama.key=...,
     *   an ollama.key file, or (Windows) the User environment in the registry.
     *
     * The last two exist because {@code OLLAMA_API_KEY} is read from the
     * *process* environment, not from wherever it is configured. A bot launched
     * from a terminal that was already open when the variable was set (setx, the
     * Environment Variables dialog, System Properties) inherits the old
     * environment block, sees nothing, and answers "chat is disabled" while the
     * key is plainly set. Reading the registry and a key file makes the process
     * find it anyway. Chat and web search stay disabled only when all of the
     * above miss.
     */
    public static final String DEFAULT_KEY = "";
    /** Process environment variable, then Windows user environment, both checked. */
    public static final String KEY_ENV = "OLLAMA_API_KEY";
    /** Optional override: -Dmcbot.ollama.keyfile=C:\path\to\key. */
    public static final String KEY_FILE_PROP = "mcbot.ollama.keyfile";
    /** Files checked when nothing else has the key, in this order. */
    private static final String[] KEY_FILES = {"ollama.key", "data/ollama.key"};
    public static final String MODEL = "beam:cloud";
    public static final long MEMORY_WINDOW_MS = 24L * 60 * 60 * 1000;
    public static final int WEB_TIMEOUT_SEC = 8;
    public static final int CHAT_TIMEOUT_SEC = 25;

    // Shared client: one connection pool, DNS/connect capped, so a dead proxy or
    // DNS lookup cannot block a caller forever before the request timeout starts.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private OllamaClient() {}

    /** Cached once found: the registry lookup shells out, so it is done at most once. */
    private static volatile String cachedKey = null;

    /**
     * First non-empty key from argument, process environment, system property,
     * key file, then the Windows user environment in the registry.
     *
     * A miss is not cached, so dropping an {@code ollama.key} next to the jar
     * starts answering chat without a restart.
     */
    public static String resolveKey(String apiKey) {
        if (cachedKey != null) return cachedKey;
        String[] found = findKey(apiKey);
        if (found == null) return null;
        cachedKey = found[0];
        return cachedKey;
    }

    /** One line for the startup log: where the key came from, or what is missing. */
    public static String keyStatus(String apiKey) {
        String[] found = findKey(apiKey);
        if (found == null) {
            return "no Ollama key: set " + KEY_ENV + ", pass it as the 5th argument, "
                    + "put it in ollama.key, or -Dmcbot.ollama.key=... - chat is disabled";
        }
        return "Ollama key found via " + found[1];
    }

    /** {key, human-readable source}, or null when nothing has it. */
    private static String[] findKey(String apiKey) {
        String v = clean(apiKey);
        if (v != null) return new String[]{v, "the 5th command line argument"};
        v = clean(System.getenv(KEY_ENV));
        if (v != null) return new String[]{v, KEY_ENV + " (process environment)"};
        v = clean(System.getProperty("mcbot.ollama.key"));
        if (v != null) return new String[]{v, "-Dmcbot.ollama.key"};
        String[] file = keyFromFile();
        if (file != null) return file;
        v = clean(windowsUserEnv(KEY_ENV));
        if (v != null) return new String[]{v, KEY_ENV + " (Windows user environment)"};
        return null;
    }

    /**
     * Normalises a candidate key: drops a UTF-8 BOM, surrounding whitespace and
     * one layer of wrapping quotes.
     *
     * Every one of these is something a real editor or shell adds. PowerShell
     * 5.1's {@code Set-Content -Encoding utf8} prepends a BOM and Java's UTF-8
     * decoder does not strip it, so the raw bytes become part of the bearer
     * token and the request fails as "invalid header value"; {@code setx} with
     * quoted text stores the quotes; a file written by hand ends in a newline.
     */
    private static String clean(String raw) {
        if (raw == null) return null;
        String s = raw;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        s = s.trim();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * A key file is the escape hatch for a launcher that cannot pass the value:
     * no shell quoting, no environment inheritance, no restart of the terminal.
     */
    private static String[] keyFromFile() {
        String prop = System.getProperty(KEY_FILE_PROP);
        java.util.List<String> paths = new ArrayList<>();
        if (prop != null && !prop.isEmpty()) paths.add(prop);
        for (String p : KEY_FILES) paths.add(p);
        for (String path : paths) {
            java.io.File f = new java.io.File(path);
            try {
                if (!f.isFile()) continue;
                String v = clean(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                if (v != null) return new String[]{v, "the file " + f.getPath()};
            } catch (Exception ignored) {
                // unreadable file: keep looking, a later source may still work
            }
        }
        return null;
    }

    /**
     * The User-scope environment straight from HKCU\Environment.
     *
     * This is the one source that is current even when the process inherited an
     * environment block from before the variable was set, which is the exact
     * case that makes a correctly configured key report "chat is disabled".
     * {@code reg.exe} reads the hive itself, so it sees the new value. Windows
     * only, and any failure is a miss rather than an error: on Linux the value
     * arrives through the process environment (systemd EnvironmentFile).
     */
    private static String windowsUserEnv(String name) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win")) {
            return null;
        }
        Process p = null;
        try {
            p = new ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", name)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) return null;
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (!t.startsWith(name)) continue;
                // "OLLAMA_API_KEY    REG_SZ    <value>"
                String rest = t.substring(name.length()).trim();
                if (!rest.regionMatches(true, 0, "REG_", 0, 4)) continue;
                int sp = rest.indexOf(' ');
                String v = sp < 0 ? "" : rest.substring(sp + 1).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {
            // no reg.exe, blocked exec, odd locale: not fatal, just no key here
        } finally {
            if (p != null && p.isAlive()) p.destroy();
        }
        return null;
    }

    public static final String TOOLS = "["
            + "{\"type\":\"function\",\"function\":{\"name\":\"web_search\","
            + "\"description\":\"Search the web for current or factual information you are unsure about.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query\"}},\"required\":[\"query\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"come_to_player\","
            + "\"description\":\"Walk to the player who sent the current chat message. Use this when they ask you to come to them, follow them, or meet them where they are.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"walk_to_coordinates\","
            + "\"description\":\"Walk to absolute world coordinates. Use this when asked to go to a place given as x y z numbers.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"stop_walking\","
            + "\"description\":\"Stop the current walk. Use this when asked to stop, stay, or cancel movement.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"mine_block\","
            + "\"description\":\"Mine (break) the block at absolute world coordinates x y z. Use this when asked to mine, dig, or break a block at a given position.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"stop_mining\","
            + "\"description\":\"Stop the current mining job. Use this when asked to stop mining or digging.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"mine_here\","
            + "\"description\":\"Break the single block the player is pointing at or standing on, whatever it happens to be. Use this ONLY when they point at a block without naming a material or a count (\\\"this one\\\", \\\"that block\\\"). If they name a material or give a number (\\\"two andesite\\\", \\\"10 stone\\\", \\\"some wood\\\"), call gather_item instead.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"look_at_player\","
            + "\"description\":\"Turn your head and look straight at the player who sent the message. Use this when they ask you to look at them, wave at you, or call your name.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"scan_blocks\","
            + "\"description\":\"Scan the blocks in the loaded world and report what is around, e.g. ores, wood, stone, water. Use this when asked what resources or blocks are nearby.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"radius\":{\"type\":\"number\",\"description\":\"Optional radius in blocks. Omit or 0 for the whole loaded area.\"}},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"attack_nearest\","
            + "\"description\":\"Attack the nearest hostile mob. Use this when a player asks you to fight, kill, defend, or protect them.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"use_block\","
            + "\"description\":\"Right-click the block at absolute coordinates: open a crafting table, furnace or chest, or press a button.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"craft_item\","
            + "\"description\":\"Craft an item by name, gathering and mining the ingredients first if needed. Use this whenever a player asks you to craft, make or build something.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"description\":\"Item name, e.g. wooden_pickaxe, crafting_table, stick\"},\"count\":{\"type\":\"number\",\"description\":\"How many. Defaults to 1.\"}},\"required\":[\"item\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"smelt_item\","
            + "\"description\":\"Smelt or cook something in a furnace, e.g. raw iron into an iron ingot. Use this when a player asks you to smelt, cook or bake.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"description\":\"What to smelt, e.g. raw_iron, raw_copper, porkchop\"},\"count\":{\"type\":\"number\",\"description\":\"How many. Defaults to 1.\"}},\"required\":[\"item\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"gather_item\","
            + "\"description\":\"Obtain a named item by whatever means it takes: mine the right block, craft it, smelt what is missing. Use this for ANY request that names a material or an item, even when the player says mine, dig or break - for example \\\"mine 10 stone\\\", \\\"get two andesite\\\", \\\"grab some wood\\\".\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"item\":{\"type\":\"string\",\"description\":\"Item name, e.g. oak_log, cobblestone, iron_ingot\"},\"count\":{\"type\":\"number\",\"description\":\"How many. Defaults to 1.\"}},\"required\":[\"item\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"list_inventory\","
            + "\"description\":\"List what is in your inventory, including the item you are holding. Use this when asked what you have, what you are carrying, or whether you have a certain item.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"follow_player\","
            + "\"description\":\"Keep following the player who sent the message as they move, instead of just walking to where they were. Use this when they ask you to follow them around.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"eat_food\","
            + "\"description\":\"Eat the best food you are carrying. Use this when asked to eat, when you are hungry, or before a fight.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"equip_best_armour\","
            + "\"description\":\"Put on the best armour you own. Use this when asked to gear up, put on armour, or get ready to fight.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"defend\","
            + "\"description\":\"Equip your best weapon and armour, then attack the nearest threat - a hostile mob first, then a player. Use this when asked to defend, fight, kill something, or protect someone.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"attack_player\","
            + "\"description\":\"Attack the nearest player. Use this when a player is hitting you, or when asked to fight a person nearby.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"build\","
            + "\"description\":\"Build a simple structure out of the most plentiful block you carry. Use this when asked to build a wall, a shelter, a house or a floor.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"what\":{\"type\":\"string\",\"description\":\"wall, shelter or platform\"}},\"required\":[\"what\"]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"self_status\","
            + "\"description\":\"Report your health, hunger, position, gear, inventory and what you are doing. Use this for questions about yourself rather than guessing.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"game_knowledge\","
            + "\"description\":\"Answer a Minecraft rules question from the game's own data: what a block or mob is, what it drops, how to get an item, what you can craft right now. Use this instead of relying on memory for recipes, drops and stats.\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"The question, e.g. 'where do I get iron' or 'what is obsidian'\"}},\"required\":[\"question\"]}}}"
            + "]";

    /** What the model is allowed to do to move/mine. Implemented by the chat pipeline. */
    public interface Mover {
        String comeHere();
        String walkTo(double x, double y, double z);
        String stop();
        String mineBlock(double x, double y, double z);
        String mineHere();
        String stopMining();
        String lookAtPlayer();
        String scanBlocks(double radius);
        String attackNearest();
        String useBlock(double x, double y, double z);
        String craftItem(String item, int count);
        String smeltItem(String item, int count);
        String gatherItem(String item, int count);
        String listInventory();
        String followPlayer();
        String attackPlayer(String name);
        String eat();
        String equipArmour();
        String defend();
        String build(String what);
        String selfStatus();
        String knowledge(String question);
    }

    private static final long REPLY_BUDGET_MS = 45_000;
    private static final int MAX_TOOL_ROUNDS = 2;
    private static final int CHAT_ATTEMPTS = 3;

    /**
     * One chat turn. The model itself decides whether to call web_search; when it
     * does, the search runs here and the results are fed back so it can answer.
     */
    public static String reply(String apiKey, String botName, List<String[]> history,
                               Mover mover, String state, java.util.function.Consumer<String> log) {
        String key = resolveKey(apiKey);
        if (key == null) {
            log.accept("no Ollama key - " + keyStatus(null));
            return null;
        }
        String system = "You are " + botName + ", a friendly, knowledgeable Minecraft bot who can chat about anything. "
                + "You have a working pathfinder and mining, and you control them with tools: call come_to_player to walk to the player who "
                + "just messaged you, walk_to_coordinates to walk to x y z, stop_walking to stop, mine_here to break the block the player "
                + "is looking at or standing on near them (this is the normal way to mine, no coordinates needed), mine_block to break the "
                + "block at x y z, and stop_mining to stop mining. "
                + "You can also look_at_player to turn your head towards whoever is talking to you, scan_blocks to see what resources are "
                + "around, attack_nearest to fight a hostile mob, and use_block to open a crafting table, furnace or chest at x y z. "
                + "For items: craft_item makes a named item and gathers or mines the ingredients automatically, smelt_item cooks ore or food, "
                + "gather_item obtains a named item however it takes, and list_inventory says what you are carrying. follow_player keeps you "
                + "walking after the player as they move, while come_to_player walks to where they are now. "
                + "When someone asks you to make, smelt, get or check something, call the matching tool instead of telling them to type a command. "
                + "For anything about the game itself - what a block is, what a mob drops, where to find an item, what you can craft - call game_knowledge "
                + "instead of answering from memory; it reads the server's own data and cannot invent a recipe. "
                + "Use self_status for questions about your own health, hunger, gear or inventory, eat_food when you are hungry or told to eat, "
                + "equip_best_armour to gear up, defend to fight a mob, and build to put up a wall or a shelter. "
                + "Call a movement tool whenever a player asks you to come, follow, meet them, go somewhere, or stop - call it "
                + "right away, do not ask for permission or wait for confirmation. Whenever they ask you to mine, dig, or break something "
                + "and do not give coordinates, call mine_here right away. Only ask for coordinates if they clearly want a specific faraway block. "
                + "Do not call a tool for ordinary "
                + "conversation, questions, or remarks. Never say you cannot pathfind, move, or mine. "
                + "Call the web_search tool only when you need current or factual information you are not sure about; otherwise just answer. "
                + "Right now: " + state + ". "
                + "If someone asks what just happened, whether you felt that hit, or how you are doing, answer from that state. "
                + "If your health has not dropped, say honestly that you did not feel anything instead of pretending you did. "
                + "Keep replies concise but natural (max 150 chars).";

        StringBuilder msgs = new StringBuilder();
        msgs.append("{\"role\":\"system\",\"content\":\"").append(escape(system)).append("\"}");
        if (history != null) {
            for (String[] m : history) {
                String role = (m != null && m.length > 0 && m[0] != null) ? m[0] : "user";
                String content = (m != null && m.length > 1 && m[1] != null) ? m[1] : "";
                msgs.append(",{\"role\":\"").append(role).append("\",\"content\":\"").append(escape(content)).append("\"}");
            }
        }

        long deadline = System.currentTimeMillis() + REPLY_BUDGET_MS;
        boolean nudged = false;
        for (int toolRound = 0; toolRound <= MAX_TOOL_ROUNDS; toolRound++) {
            if (System.currentTimeMillis() > deadline) {
                log.accept("reply budget exhausted");
                return null;
            }
            String body = "{\"model\":\"" + MODEL + "\",\"messages\":[" + msgs + "],\"tools\":" + TOOLS + ",\"stream\":false}";
            String resBody = postChat(key, body, deadline, log);
            if (resBody == null) return null;

            String message = extractObject(resBody, "message");
            String calls = message == null ? null : extractArray(message, "tool_calls");
            if (calls != null && !calls.equals("[]")) {
                msgs.append(',').append(message);
                for (String fn : functionObjects(calls)) {
                    String name = stringField(fn, "name");
                    String args = extractObject(fn, "arguments");
                    String result;
                    if ("web_search".equals(name)) {
                        String query = args == null ? null : stringField(args, "query");
                        if (query == null || query.trim().isEmpty()) {
                            result = "No results found.";
                        } else {
                            log.accept("model asked to web search: " + query);
                            String found = webSearch(query);
                            result = (found == null || found.isEmpty()) ? "No results found." : found;
                            log.accept("web results: " + abbreviate(result));
                        }
                    } else if ("come_to_player".equals(name) && mover != null) {
                        log.accept("model asked to come to the sender");
                        result = mover.comeHere();
                    } else if ("walk_to_coordinates".equals(name) && mover != null) {
                        Double x = args == null ? null : numberField(args, "x");
                        Double y = args == null ? null : numberField(args, "y");
                        Double z = args == null ? null : numberField(args, "z");
                        if (x == null || y == null || z == null) {
                            result = "I need numeric x y z coordinates.";
                        } else {
                            log.accept("model asked to walk to " + x + " " + y + " " + z);
                            result = mover.walkTo(x, y, z);
                        }
                    } else if ("stop_walking".equals(name) && mover != null) {
                        log.accept("model asked to stop walking");
                        result = mover.stop();
                    } else if ("mine_block".equals(name) && mover != null) {
                        Double x = args == null ? null : numberField(args, "x");
                        Double y = args == null ? null : numberField(args, "y");
                        Double z = args == null ? null : numberField(args, "z");
                        if (x == null || y == null || z == null) {
                            result = "I need numeric x y z coordinates.";
                        } else {
                            log.accept("model asked to mine " + x + " " + y + " " + z);
                            result = mover.mineBlock(x, y, z);
                        }
                    } else if ("mine_here".equals(name) && mover != null) {
                        log.accept("model asked to mine here");
                        result = mover.mineHere();
                    } else if ("stop_mining".equals(name) && mover != null) {
                        log.accept("model asked to stop mining");
                        result = mover.stopMining();
                    } else if ("look_at_player".equals(name) && mover != null) {
                        log.accept("model asked to look at the sender");
                        result = mover.lookAtPlayer();
                    } else if ("scan_blocks".equals(name) && mover != null) {
                        Double radius = args == null ? null : numberField(args, "radius");
                        log.accept("model asked to scan blocks r=" + radius);
                        result = mover.scanBlocks(radius == null ? 0 : radius);
                    } else if ("attack_nearest".equals(name) && mover != null) {
                        log.accept("model asked to attack the nearest mob");
                        result = mover.attackNearest();
                    } else if ("use_block".equals(name) && mover != null) {
                        Double x = args == null ? null : numberField(args, "x");
                        Double y = args == null ? null : numberField(args, "y");
                        Double z = args == null ? null : numberField(args, "z");
                        if (x == null || y == null || z == null) {
                            result = "I need numeric x y z coordinates.";
                        } else {
                            log.accept("model asked to use block " + x + " " + y + " " + z);
                            result = mover.useBlock(x, y, z);
                        }
                    } else if ("craft_item".equals(name) && mover != null) {
                        String item = args == null ? null : stringField(args, "item");
                        if (item == null || item.trim().isEmpty()) {
                            result = "I need the name of the item to craft.";
                        } else {
                            int n = countField(args);
                            log.accept("model asked to craft " + n + "x " + item);
                            result = mover.craftItem(item.trim(), n);
                        }
                    } else if ("smelt_item".equals(name) && mover != null) {
                        String item = args == null ? null : stringField(args, "item");
                        if (item == null || item.trim().isEmpty()) {
                            result = "I need the name of the item to smelt.";
                        } else {
                            int n = countField(args);
                            log.accept("model asked to smelt " + n + "x " + item);
                            result = mover.smeltItem(item.trim(), n);
                        }
                    } else if ("gather_item".equals(name) && mover != null) {
                        String item = args == null ? null : stringField(args, "item");
                        if (item == null || item.trim().isEmpty()) {
                            result = "I need the name of the item to gather.";
                        } else {
                            int n = countField(args);
                            log.accept("model asked to gather " + n + "x " + item);
                            result = mover.gatherItem(item.trim(), n);
                        }
                    } else if ("list_inventory".equals(name) && mover != null) {
                        log.accept("model asked for the inventory");
                        result = mover.listInventory();
                    } else if ("follow_player".equals(name) && mover != null) {
                        log.accept("model asked to follow the sender");
                        result = mover.followPlayer();
                    } else if ("attack_player".equals(name) && mover != null) {
                        String who = args == null ? null : stringField(args, "name");
                        log.accept("model asked to attack player: " + who);
                        result = mover.attackPlayer(who);
                    } else if ("eat_food".equals(name) && mover != null) {
                        log.accept("model asked to eat");
                        result = mover.eat();
                    } else if ("equip_best_armour".equals(name) && mover != null) {
                        log.accept("model asked to gear up");
                        result = mover.equipArmour();
                    } else if ("defend".equals(name) && mover != null) {
                        log.accept("model asked to defend");
                        result = mover.defend();
                    } else if ("build".equals(name) && mover != null) {
                        String what = args == null ? null : stringField(args, "what");
                        log.accept("model asked to build " + what);
                        result = mover.build(what == null ? "wall" : what.trim());
                    } else if ("self_status".equals(name) && mover != null) {
                        log.accept("model asked for its own status");
                        result = mover.selfStatus();
                    } else if ("game_knowledge".equals(name) && mover != null) {
                        String question = args == null ? null : stringField(args, "question");
                        log.accept("model asked a knowledge question: " + question);
                        result = question == null || question.isBlank()
                                ? "Ask me a specific question about a block, item or mob."
                                : mover.knowledge(question);
                    } else {
                        log.accept("model called unavailable tool: " + name);
                        result = "That tool is not available. Answer in plain chat text.";
                    }
                    msgs.append(",{\"role\":\"tool\",\"tool_name\":\"")
                            .append(name == null ? "web_search" : name)
                            .append("\",\"content\":\"").append(escape(result)).append("\"}");
                }
                continue;
            }

            String content = extractContent(resBody);
            if (content != null && !content.trim().isEmpty()) {
                content = content.trim();
                return content.length() > 150 ? content.substring(0, 150) : content;
            }

            if (!nudged) {
                nudged = true;
                msgs.append(",{\"role\":\"user\",\"content\":\"Answer that in plain text now.\"}");
                continue;
            }
            log.accept("empty response from ollama: " + abbreviate(resBody));
            return null;
        }
        return null;
    }

    /**
     * POST to ollama.com/api/chat, retrying transient failures. Traffic here goes
     * through a local proxy interceptor, so a connect can be refused for a second
     * and then work fine; one lost packet must not cost the player a reply.
     */
    private static String postChat(String key, String body, long deadline, java.util.function.Consumer<String> log) {
        for (int attempt = 1; attempt <= CHAT_ATTEMPTS; attempt++) {
            long t0 = System.currentTimeMillis();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://ollama.com/api/chat"))
                        .timeout(Duration.ofSeconds(CHAT_TIMEOUT_SEC))
                        .header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    log.accept("ollama http " + res.statusCode() + ": " + abbreviate(res.body()));
                    return null;
                }
                return res.body();
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - t0;
                log.accept("ollama " + rootCause(e) + " after " + ms + " ms (attempt " + attempt + "/" + CHAT_ATTEMPTS + ")");
                if (attempt == CHAT_ATTEMPTS || System.currentTimeMillis() + 2_000L >= deadline) return null;
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
    }

    /** Search entry point: DuckDuckGo HTML (no key, ~3 s), then Wikipedia. */
    private static volatile long ddgBlockedUntil = 0L;

    /**
     * Free sources only.
     *
     * The previous chain ended in ollama.com/api/web_search, which bills per
     * call. That fallback is gone: if DuckDuckGo is rate limited and Wikipedia
     * has no article, the tool honestly reports no results instead of spending
     * money. Neither remaining source takes an API key or has a quota.
     */
    public static String webSearch(String query) {
        if (System.currentTimeMillis() >= ddgBlockedUntil) {
            String ddg = duckDuckGoSearch(query);
            if (ddg != null && !ddg.isEmpty()) return ddg;
        }
        String wiki = wikipediaSearch(query);
        if (wiki != null && !wiki.isEmpty()) return wiki;
        return null;
    }

    private static String duckDuckGoSearch(String query) {
        try {
            String url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(WEB_TIMEOUT_SEC))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                System.out.println("[ai] ddg http " + res.statusCode() + " - backing off 10 min");
                ddgBlockedUntil = System.currentTimeMillis() + 10 * 60 * 1000L;
                return null;
            }
            String html = res.body();
            List<String> titles = htmlBlocks(html, "class=\"result__a\"");
            List<String> snippets = htmlBlocks(html, "class=\"result__snippet\"");
            int n = Math.min(titles.size(), snippets.size());
            if (n == 0) return null;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(n, 4); i++) {
                String c = snippets.get(i);
                if (c.length() > 220) c = c.substring(0, 220);
                out.append("- ").append(titles.get(i)).append(": ").append(c).append("\n");
            }
            return out.toString().trim();
        } catch (Exception e) {
            System.out.println("[ai] ddg error: " + e);
            return null;
        }
    }

    private static String wikipediaSearch(String query) {
        try {
            String url = "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch="
                    + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&gsrlimit=3&prop=extracts&exintro=1&explaintext=1&exlimit=3&format=json";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(WEB_TIMEOUT_SEC))
                    .header("User-Agent", "mcbot/1.0 (Minecraft chat bot)")
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                System.out.println("[ai] wikipedia http " + res.statusCode());
                return null;
            }
            List<String> titles = new ArrayList<>();
            List<String> extracts = new ArrayList<>();
            for (String[] kv : stringFields(res.body(), "title", "extract")) {
                if (kv[0].equals("title")) titles.add(kv[1]);
                else extracts.add(kv[1]);
            }
            int n = Math.min(titles.size(), extracts.size());
            if (n == 0) return null;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(n, 3); i++) {
                String c = extracts.get(i);
                if (c.length() > 300) c = c.substring(0, 300);
                out.append("- ").append(titles.get(i)).append(": ").append(c).append("\n");
            }
            return out.toString().trim();
        } catch (Exception e) {
            System.out.println("[ai] wikipedia error: " + e);
            return null;
        }
    }

    /** Text of every element whose attribute marker matches, tags stripped, entities decoded. */
    private static List<String> htmlBlocks(String html, String marker) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = html.indexOf(marker, i)) >= 0) {
            int gt = html.indexOf('>', i);
            if (gt < 0) break;
            int endA = html.indexOf("</a>", gt);
            int endD = html.indexOf("</div>", gt);
            int end = (endA < 0) ? endD : (endD < 0 ? endA : Math.min(endA, endD));
            if (end < 0) break;
            String text = stripHtml(html.substring(gt + 1, end));
            if (!text.isEmpty()) out.add(text);
            i = end;
        }
        return out;
    }

    private static String stripHtml(String html) {
        StringBuilder sb = new StringBuilder();
        boolean inTag = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') inTag = true;
            else if (c == '>') inTag = false;
            else if (!inTag) sb.append(c);
        }
        return decodeEntities(sb.toString()).replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntities(String s) {
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 8 && s.charAt(i + 1) == '#') {
                    try {
                        int cp = s.charAt(i + 2) == 'x' || s.charAt(i + 2) == 'X'
                                ? Integer.parseInt(s.substring(i + 3, semi), 16)
                                : Integer.parseInt(s.substring(i + 2, semi));
                        sb.append((char) cp);
                        i = semi;
                        continue;
                    } catch (Exception ignored) {}
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** All string key/value pairs for the wanted keys, in document order. */
    private static List<String[]> stringFields(String body, String... wanted) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> want = new java.util.HashSet<>(java.util.Arrays.asList(wanted));
        int i = 0;
        while ((i = body.indexOf('"', i)) >= 0) {
            StringBuilder keyBuf = new StringBuilder();
            int afterKey = readJsonString(body, i, keyBuf);
            if (afterKey < 0) break;
            String k = keyBuf.toString();
            int colon = skipWhitespace(body, afterKey);
            int valueStart = (colon < body.length() && body.charAt(colon) == ':')
                    ? skipWhitespace(body, colon + 1) : -1;
            if (want.contains(k) && valueStart >= 0 && valueStart < body.length() && body.charAt(valueStart) == '"') {
                StringBuilder val = new StringBuilder();
                int afterValue = readJsonString(body, valueStart, val);
                if (afterValue < 0) break;
                out.add(new String[]{k, val.toString()});
                i = afterValue;
            } else {
                i = afterKey;
            }
        }
        return out;
    }

    private static String abbreviate(String s) {
        if (s == null) return "<null>";
        s = s.replace('\n', ' ');
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Raw JSON object that follows "key": (string-aware brace matching). */
    private static String extractObject(String s, String key) {
        int i = s.indexOf("\"" + key + "\":");
        if (i < 0) return null;
        int start = s.indexOf('{', i);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int j = start; j < s.length(); j++) {
            char c = s.charAt(j);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, j + 1);
            }
        }
        return null;
    }

    /** Raw JSON array that follows "key": . */
    private static String extractArray(String s, String key) {
        int i = s.indexOf("\"" + key + "\":");
        if (i < 0) return null;
        int start = s.indexOf('[', i);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int j = start; j < s.length(); j++) {
            char c = s.charAt(j);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return s.substring(start, j + 1);
            }
        }
        return null;
    }

    /** The function object of every tool call in a tool_calls array. */
    private static List<String> functionObjects(String calls) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int f = calls.indexOf("\"function\":", from);
            if (f < 0) break;
            String fn = extractObject(calls.substring(f), "function");
            if (fn == null) break;
            out.add(fn);
            from = calls.indexOf('{', f) + fn.length();
        }
        return out;
    }

    private static String stringField(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int q = skipWhitespace(json, colon + 1);
        if (q >= json.length() || json.charAt(q) != '"') return null;
        StringBuilder sb = new StringBuilder();
        return readJsonString(json, q, sb) < 0 ? null : sb.toString();
    }

    /** Tool "count" argument, clamped to something a player could actually hold. */
    private static int countField(String args) {
        Double d = args == null ? null : numberField(args, "count");
        if (d == null || !Double.isFinite(d)) return 1;
        return (int) Math.max(1, Math.min(64, Math.round(d)));
    }

    /** Numeric (or numeric-string) field value, or null when absent/not a number. */
    private static Double numberField(String json, String key) {
        if (json == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int p = skipWhitespace(json, colon + 1);
        if (p < json.length() && json.charAt(p) == '"') p++;
        int start = p;
        while (p < json.length()) {
            char c = json.charAt(p);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                    || (c >= '0' && c <= '9')) {
                p++;
            } else {
                break;
            }
        }
        if (p == start) return null;
        try {
            return Double.valueOf(json.substring(start, p));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Iterative JSON string scanner. Regex parsing of a ~65 KB web-search body
     * recurses once per character and blows the thread stack (StackOverflowError),
     * which is what silently killed the AI thread. This walks the string instead.
     */
    private static int readJsonString(String s, int quoteIdx, StringBuilder out) {
        if (quoteIdx < 0 || quoteIdx >= s.length() || s.charAt(quoteIdx) != '"') return -1;
        for (int i = quoteIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') return i + 1;
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= s.length()) return -1;
            char e = s.charAt(i);
            switch (e) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {}
                    }
                    break;
                default: out.append(e);
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String extractContent(String body) {
        int i = 0;
        while ((i = body.indexOf('"', i)) >= 0) {
            StringBuilder key = new StringBuilder();
            int afterKey = readJsonString(body, i, key);
            if (afterKey < 0) return null;
            int colon = skipWhitespace(body, afterKey);
            if (key.toString().equals("content") && colon < body.length() && body.charAt(colon) == ':') {
                int valueStart = skipWhitespace(body, colon + 1);
                if (valueStart < body.length() && body.charAt(valueStart) == '"') {
                    StringBuilder val = new StringBuilder();
                    return readJsonString(body, valueStart, val) < 0 ? null : val.toString();
                }
            }
            i = afterKey;
        }
        return null;
    }
}