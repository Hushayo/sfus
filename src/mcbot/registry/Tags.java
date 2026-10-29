package mcbot.registry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block and item tags, as the server defines them.
 *
 * Tags are how vanilla expresses "any plank", "any log", "blocks a pickaxe is
 * fast on", "blocks that need iron or better". Recipes are written in terms of
 * them ("2x #minecraft:planks -> 4 sticks"), so a bot without tags cannot
 * resolve the majority of real recipes, and a bot without
 * {@code mineable/pickaxe} cannot pick a tool.
 *
 * Tags nest: a tag's values may reference other tags with a leading {@code #}.
 * Nested references are expanded once at load time so callers always see plain
 * member names.
 */
public final class Tags {
    private static final Map<String, Set<String>> BLOCK = new HashMap<>();
    private static final Map<String, Set<String>> ITEM = new HashMap<>();

    static {
        load("/mcbot/data/tags_block.tsv", BLOCK);
        load("/mcbot/data/tags_item.tsv", ITEM);
    }

    private Tags() {}

    private static void load(String resource, Map<String, Set<String>> into) {
        Map<String, List<String>> raw = new HashMap<>();
        try (InputStream in = Tags.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.out.println("[tags] missing resource " + resource);
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String tag = line.substring(0, tab).trim();
                String rest = line.substring(tab + 1).trim();
                List<String> members = new ArrayList<>();
                for (String m : rest.split(",")) {
                    if (!m.isEmpty()) members.add(m);
                }
                raw.put(tag, members);
            }
        } catch (Exception e) {
            System.out.println("[tags] " + resource + " load failed: " + e);
            return;
        }
        for (String tag : raw.keySet()) {
            into.put(tag, expand(tag, raw, new HashSet<>()));
        }
    }

    private static Set<String> expand(String tag, Map<String, List<String>> raw, Set<String> seen) {
        Set<String> out = new HashSet<>();
        if (!seen.add(tag)) return out;
        for (String m : raw.getOrDefault(tag, Collections.emptyList())) {
            if (m.startsWith("#")) {
                String nested = m.substring(1);
                out.addAll(expand(nested, raw, seen));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /** Members of a block tag. Accepts "logs" or "minecraft:logs". */
    public static Set<String> blocks(String tag) {
        return BLOCK.getOrDefault(normalize(tag), Collections.emptySet());
    }

    /** Members of an item tag. */
    public static Set<String> items(String tag) {
        return ITEM.getOrDefault(normalize(tag), Collections.emptySet());
    }

    public static boolean blockIn(String blockName, String tag) {
        return blocks(tag).contains(strip(blockName));
    }

    public static boolean itemIn(String itemName, String tag) {
        return items(tag).contains(strip(itemName));
    }

    private static String normalize(String tag) {
        String t = tag.startsWith("#") ? tag.substring(1) : tag;
        return strip(t);
    }

    private static String strip(String s) {
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    public static int blockTagCount() { return BLOCK.size(); }
    public static int itemTagCount() { return ITEM.size(); }
}
