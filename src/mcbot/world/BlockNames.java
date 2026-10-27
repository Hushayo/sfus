package mcbot.world;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional block-state id -> name table.
 *
 * The protocol sends block *state ids*, not names, so without a registry dump
 * the bot can only talk about "state 4123". Drop a dump next to the jar (or on
 * the classpath) and everything switches to real names:
 *
 *   blocks.txt
 *   ----------
 *   1            minecraft:stone
 *   14           minecraft:granite
 *   4123         minecraft:oak_log
 *
 * Any of these line shapes work, so a dump can be copied in nearly as-is:
 *
 *   4123 minecraft:oak_log
 *   minecraft:oak_log 4123
 *   4123,minecraft:oak_log
 *   "minecraft:oak_log": 4123,
 *
 * Search order: classpath /blocks.txt, ./blocks.txt, ./data/blocks.txt,
 * then -Dmcbot.blocks=/path/to/blocks.txt.
 */
public final class BlockNames {
    private static final Map<Integer, String> BY_ID = new HashMap<>();
    private static final Map<String, Integer> BY_NAME = new HashMap<>();

    static {
        String explicit = System.getProperty("mcbot.blocks");
        if (explicit != null) loadFile(new File(explicit));
        if (BY_ID.isEmpty()) loadResource("/blocks.txt");
        if (BY_ID.isEmpty()) loadFile(new File("blocks.txt"));
        if (BY_ID.isEmpty()) loadFile(new File("data/blocks.txt"));
        if (!BY_ID.isEmpty()) {
            System.out.println("[blocks] loaded " + BY_ID.size() + " block names");
        }
    }

    private BlockNames() {}

    public static boolean loaded() {
        return !BY_ID.isEmpty();
    }

    public static String name(int state) {
        return BY_ID.get(state);
    }

    public static Integer id(String name) {
        Integer exact = BY_NAME.get(name);
        if (exact != null) return exact;
        String prefixed = name.startsWith("minecraft:") ? name : "minecraft:" + name;
        return BY_NAME.get(prefixed);
    }

    /** All state ids whose name contains the given fragment. */
    public static List<Integer> matching(String fragment) {
        List<Integer> out = new ArrayList<>();
        String f = fragment.toLowerCase(java.util.Locale.ROOT);
        if (!f.contains(":")) f = ":" + f;
        for (Map.Entry<String, Integer> e : BY_NAME.entrySet()) {
            if (e.getKey().contains(f)) out.add(e.getValue());
        }
        return out;
    }

    private static void loadResource(String path) {
        try (InputStream in = BlockNames.class.getResourceAsStream(path)) {
            if (in == null) return;
            parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            System.out.println("[blocks] resource load failed: " + e);
        }
    }

    private static void loadFile(File file) {
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = java.nio.file.Files.newBufferedReader(
                file.toPath(), StandardCharsets.UTF_8)) {
            parse(r);
        } catch (Exception e) {
            System.out.println("[blocks] " + file + " load failed: " + e);
        }
    }

    private static void parse(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            int id = -1;
            String name = null;
            for (String token : line.split("[\\s,;=\"]+")) {
                if (token.isEmpty()) continue;
                if (id < 0) {
                    try {
                        id = Integer.parseInt(token);
                        continue;
                    } catch (NumberFormatException ignored) {}
                }
                if (token.startsWith("minecraft:") || token.indexOf(':') > 0) {
                    name = token;
                }
            }
            if (id >= 0 && name != null) {
                BY_ID.put(id, name);
                BY_NAME.put(name, id);
            }
        }
    }
}
