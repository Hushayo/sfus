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
     *   5th command line argument, OLLAMA_API_KEY, or -Dmcbot.ollama.key=...
     * Chat and web search simply stay disabled when none is present.
     */
    public static final String DEFAULT_KEY = "";
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

    /** First non-empty key from argument, environment, then system property. */
    public static String resolveKey(String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        String env = System.getenv("OLLAMA_API_KEY");
        if (env != null && !env.isEmpty()) return env;
        String prop = System.getProperty("mcbot.ollama.key");
        if (prop != null && !prop.isEmpty()) return prop;
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
            + "\"description\":\"Mine (break) a block right where the player who messaged you is: the block they are looking at, or the block under their feet. Use this whenever they ask you to mine, dig, or break something nearby or \\\"here\\\" without giving coordinates.\","
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
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"z\"]}}}"
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
    }

    private static final long REPLY_BUDGET_MS = 45_000;
    private static final int MAX_TOOL_ROUNDS = 2;
    private static final int CHAT_ATTEMPTS = 3;

    /**
     * One chat turn. The model itself decides whether to call web_search; when it
     * does, the search runs here and the results are fed back so it can answer.
     */
    public static String reply(String apiKey, String botName, List<String[]> history,
                               Mover mover, java.util.function.Consumer<String> log) {
        String key = resolveKey(apiKey);
        if (key == null) {
            log.accept("no OLLAMA_API_KEY set - chat is disabled");
            return null;
        }
        String system = "You are " + botName + ", a friendly, knowledgeable Minecraft bot who can chat about anything. "
                + "You have a working pathfinder and mining, and you control them with tools: call come_to_player to walk to the player who "
                + "just messaged you, walk_to_coordinates to walk to x y z, stop_walking to stop, mine_here to break the block the player "
                + "is looking at or standing on near them (this is the normal way to mine, no coordinates needed), mine_block to break the "
                + "block at x y z, and stop_mining to stop mining. "
                + "You can also look_at_player to turn your head towards whoever is talking to you, scan_blocks to see what resources are "
                + "around, attack_nearest to fight a hostile mob, and use_block to open a crafting table, furnace or chest at x y z. "
                + "Call a movement tool whenever a player asks you to come, follow, meet them, go somewhere, or stop - call it "
                + "right away, do not ask for permission or wait for confirmation. Whenever they ask you to mine, dig, or break something "
                + "and do not give coordinates, call mine_here right away. Only ask for coordinates if they clearly want a specific faraway block. "
                + "Do not call a tool for ordinary "
                + "conversation, questions, or remarks. Never say you cannot pathfind, move, or mine. "
                + "Call the web_search tool only when you need current or factual information you are not sure about; otherwise just answer. "
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
                            String found = webSearch(key, query);
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

    /** Search entry point: DuckDuckGo HTML (no key, ~3 s), ollama.com as fallback. */
    private static volatile long ddgBlockedUntil = 0L;

    /**
     * Search chain: DuckDuckGo HTML (good for current events), then Wikipedia
     * (fast, no key, no quota), then ollama.com (free tier has an hourly limit).
     */
    public static String webSearch(String apiKey, String query) {
        if (System.currentTimeMillis() >= ddgBlockedUntil) {
            String ddg = duckDuckGoSearch(query);
            if (ddg != null && !ddg.isEmpty()) return ddg;
        }
        String wiki = wikipediaSearch(query);
        if (wiki != null && !wiki.isEmpty()) return wiki;
        System.out.println("[ai] wikipedia empty, falling back to ollama web_search");
        return ollamaSearch(apiKey, query);
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

    private static String ollamaSearch(String apiKey, String query) {
        String key = resolveKey(apiKey);
        if (key == null) return null;
        try {
            String json = "{\"query\":\"" + escape(query) + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://ollama.com/api/web_search"))
                    .timeout(Duration.ofSeconds(WEB_TIMEOUT_SEC))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                System.out.println("[ai] web search http " + res.statusCode() + ": " + abbreviate(res.body()));
                return null;
            }
            List<String> titles = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            for (String[] kv : stringFields(res.body(), "title", "content")) {
                if (kv[0].equals("title")) titles.add(kv[1]);
                else contents.add(kv[1]);
            }
            int n = Math.min(titles.size(), contents.size());
            if (n == 0) return null;
            StringBuilder out = new StringBuilder();
            for (int r = 0; r < Math.min(n, 4); r++) {
                String c = contents.get(r);
                if (c.length() > 220) c = c.substring(0, 220);
                out.append("- ").append(titles.get(r)).append(": ").append(c).append("\n");
            }
            return out.toString().trim();
        } catch (Exception e) {
            System.out.println("[ai] web search error: " + e);
            return null;
        }
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