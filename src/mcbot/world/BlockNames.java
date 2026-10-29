package mcbot.world;

import mcbot.registry.Blocks;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Block-state id to name, backed by the generated registry.
 *
 * Historically this class loaded an optional {@code blocks.txt} dump next to
 * the jar, because the bot had no idea which state id was which block. The
 * registry is now generated from the server itself
 * ({@code tools/gen_data.py}), so names are always available and an external
 * dump is only useful to override them.
 *
 * Load order: {@code -Dmcbot.blocks=/path/to/blocks.txt}, then
 * {@code ./blocks.txt}, then {@code ./data/blocks.txt}, then the built-in
 * registry.
 */
public final class BlockNames {
    private static final java.util.Map<Integer, String> OVERRIDES = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> OVERRIDE_IDS = new java.util.HashMap<>();

    static {
        String explicit = System.getProperty("mcbot.blocks");
        if (explicit != null) loadFile(new File(explicit));
        if (OVERRIDES.isEmpty()) loadFile(new File("blocks.txt"));
        if (OVERRIDES.isEmpty()) loadFile(new File("data/blocks.txt"));
        if (!OVERRIDES.isEmpty()) {
            System.out.println("[blocks] " + OVERRIDES.size() + " name overrides loaded from file");
        }
    }

    private BlockNames() {}

    /** Always true now: the registry ships with the bot. */
    public static boolean loaded() {
        return Blocks.stateCount() > 0;
    }

    /** Name of a state, without the namespace ("oak_log"), or null. */
    public static String name(int state) {
        String override = OVERRIDES.get(state);
        if (override != null) return override;
        return Blocks.shortName(state);
    }

    /** Full name with namespace ("minecraft:oak_log"), or null. */
    public static String fullName(int state) {
        String n = name(state);
        return n == null ? null : "minecraft:" + n;
    }

    /** First state id of a block by name, or null when unknown. */
    public static Integer id(String name) {
        Integer override = OVERRIDE_IDS.get(name);
        if (override != null) return override;
        int state = Blocks.defaultState(name);
        return state < 0 ? null : state;
    }

    /** All state ids whose block name contains the fragment. */
    public static List<Integer> matching(String fragment) {
        List<Integer> out = new ArrayList<>();
        String f = fragment.toLowerCase(java.util.Locale.ROOT);
        if (f.startsWith("minecraft:")) f = f.substring(10);
        for (String block : Blocks.blockNames()) {
            if (block.contains(f)) {
                out.add(Blocks.defaultState(block));
            }
        }
        return out;
    }

    /** Every state of every block whose name contains the fragment. */
    public static List<Integer> matchingStates(String fragment) {
        List<Integer> out = new ArrayList<>();
        String f = fragment.toLowerCase(java.util.Locale.ROOT);
        if (f.startsWith("minecraft:")) f = f.substring(10);
        for (String block : Blocks.blockNames()) {
            if (!block.contains(f)) continue;
            for (int state : Blocks.statesOf(block)) out.add(state);
        }
        return out;
    }

    private static void loadFile(File file) {
        if (file == null || !file.isFile()) return;
        try (java.io.BufferedReader r = java.nio.file.Files.newBufferedReader(
                file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
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
                    if (token.indexOf(':') > 0) name = token;
                }
                if (id >= 0 && name != null) {
                    String shortName = name.startsWith("minecraft:") ? name.substring(10) : name;
                    OVERRIDES.put(id, shortName);
                    OVERRIDE_IDS.put(shortName, id);
                }
            }
        } catch (Exception e) {
            System.out.println("[blocks] " + file + " load failed: " + e);
        }
    }
}
