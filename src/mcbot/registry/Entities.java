package mcbot.registry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Entity type names and their network ids.
 *
 * The id that matters is the one the server sends in {@code add_entity}, which
 * is an index into the synced entity type registry - the same table
 * {@code entity_types.tsv} was generated from. It is only used to answer one
 * question ("is this tracked entity an item on the ground?"), but that question
 * is what lets the bot walk to a drop instead of guessing which cells it might
 * be near.
 */
public final class Entities {
    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static final Map<Integer, String> BY_ID = new HashMap<>();

    static {
        load();
    }

    private Entities() {}

    private static void load() {
        try (InputStream in = Entities.class.getResourceAsStream("/mcbot/data/entity_types.tsv")) {
            if (in == null) {
                System.out.println("[entity] entity_types.tsv missing");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 2) continue;
                int id = Integer.parseInt(f[0]);
                BY_NAME.put(f[1], id);
                BY_ID.put(id, f[1]);
            }
        } catch (Exception e) {
            System.out.println("[entity] entity_types.tsv load failed: " + e);
        }
    }

    /** Network id of an entity type, or -1 when the name is unknown. */
    public static int id(String name) {
        Integer id = BY_NAME.get(name);
        return id == null ? -1 : id;
    }

    public static String name(int id) {
        return BY_ID.getOrDefault(id, "unknown");
    }

    /** True when a tracked entity of this type is a dropped item. */
    public static boolean isDroppedItem(int typeId) {
        int item = id("item");
        return item >= 0 && typeId == item;
    }
}
