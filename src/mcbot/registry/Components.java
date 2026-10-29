package mcbot.registry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Data component type ids, and enough knowledge of their payloads to skip them.
 *
 * An item stack on the wire is {@code count, itemId, addedComponents,
 * removedComponents}, where each added component is a type id followed by that
 * component's own codec. Reading a *list* of stacks (a container's contents)
 * therefore requires knowing every component's size - one wrong guess and every
 * slot after it is garbage.
 *
 * That is what made the bot report "0 cobblestone" while holding nineteen: the
 * first slot held a damaged pickaxe, its {@code minecraft:damage} payload was
 * not consumed, and the parser lost its place for the remaining 45 slots.
 *
 * Only the simple, fixed-size payloads are handled here. Anything else makes the
 * stack "opaque", which callers treat as unknown rather than wrong - an
 * inventory that admits it does not know is far better than one that lies.
 */
public final class Components {
    /** Payload kinds this class can skip. */
    private enum Kind { VAR_INT, BOOL, UNIT, NBT, VAR_INT_NBT }

    private static final Map<Integer, String> NAME = new HashMap<>();
    private static final Map<String, Kind> KIND = new HashMap<>();

    static {
        load();
        // Fixed-size payloads. Everything else is opaque on purpose.
        kind("max_stack_size", Kind.VAR_INT);
        kind("max_damage", Kind.VAR_INT);
        kind("damage", Kind.VAR_INT);
        kind("repair_cost", Kind.VAR_INT);
        kind("custom_model_data", Kind.VAR_INT);
        kind("rarity", Kind.VAR_INT);
        kind("enchantment_glint_override", Kind.BOOL);
        kind("unbreakable", Kind.UNIT);
        kind("creative_slot_lock", Kind.UNIT);
        // Arbitrary NBT. Skipping it is what keeps a stack with custom data
        // from desynchronising every slot after it in the window.
        kind("custom_data", Kind.NBT);
        // Typed entity data: a registry id followed by a compound tag.
        kind("entity_data", Kind.VAR_INT_NBT);
        kind("bucket_entity_data", Kind.VAR_INT_NBT);
        kind("block_entity_data", Kind.VAR_INT_NBT);
    }

    private Components() {}

    private static void kind(String name, Kind k) {
        KIND.put(name, k);
    }

    private static void load() {
        try (InputStream in = Components.class.getResourceAsStream("/mcbot/data/components.tsv")) {
            if (in == null) {
                System.out.println("[components] missing resource /mcbot/data/components.tsv");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 2) continue;
                NAME.put(Integer.parseInt(f[0]), f[1]);
            }
        } catch (Exception e) {
            System.out.println("[components] load failed: " + e);
        }
    }

    public static String name(int id) {
        return NAME.get(id);
    }

    /** Number of component types in the registry. */
    public static int count() {
        return NAME.size();
    }

    /** Protocol id of a component by name, or -1. */
    public static int id(String name) {
        for (var e : NAME.entrySet()) {
            if (e.getValue().equals(name)) return e.getKey();
        }
        return -1;
    }

    public static boolean known(int id) {
        String n = NAME.get(id);
        return n != null && KIND.containsKey(n);
    }

    /**
     * Consumes one component payload from the reader.
     *
     * @return true when the payload was skipped and the reader is positioned at
     *         the next field; false when the payload size is unknown, in which
     *         case the reader is left where it was and the caller must stop.
     */
    public static boolean skip(mcbot.net.PacketReader pr, int typeId) {
        String n = NAME.get(typeId);
        if (n == null) {
            warnOnce(typeId, "unknown type id");
            return false;
        }
        Kind k = KIND.get(n);
        if (k == null) {
            warnOnce(typeId, "no skip rule for " + n);
            return false;
        }
        switch (k) {
            case VAR_INT:
                pr.readVarInt();
                return true;
            case BOOL:
                pr.readBoolean();
                return true;
            case UNIT:
                return true;   // no payload
            case NBT:
                return Nbt.skip(pr);
            case VAR_INT_NBT:
                pr.readVarInt();
                return Nbt.skip(pr);
            default:
                return false;
        }
    }

    private static final java.util.Set<Integer> WARNED = new java.util.HashSet<>();

    private static void warnOnce(int id, String why) {
        synchronized (WARNED) {
            if (!WARNED.add(id)) return;
        }
        System.out.println("[components] cannot skip id " + id + ": " + why
                + " (stacks containing it will be marked unknown)");
    }
}
