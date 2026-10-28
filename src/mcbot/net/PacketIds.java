package mcbot.net;

/**
 * Packet ids for protocol 777 (Minecraft 26.3) in one place.
 *
 * The ids the original bot needs to stay alive (login, keepalive, chunk,
 * movement, block break) are hard-coded where they are used because they have
 * been verified against a live server. The ids used by the optional feature
 * modules (health, damage, combat, containers) are collected here and can be
 * overridden at runtime without recompiling:
 *
 *   java -Dmcbot.packet.entity_status=0x1D -Dmcbot.packet.interact=0x36 ...
 *
 * Values accept decimal or 0x-prefixed hex (Integer.getInteger decodes both).
 * Run with BOT_DEBUG=1 to log every inbound packet id and length, which is how
 * you find the right number for a given server build.
 *
 * Evidence for each default is recorded in docs/PROTOCOL.md, which was derived
 * from a captured session. Two ids that used to be here are known wrong and are
 * no longer used:
 *   0x5B set_health   - never sent by the server (0 of 516,866 packets)
 *   0x18 damage_event - that id carries entity metadata, not a damage event
 * Health is read from 0x6A instead (one 9-byte packet per session, exactly the
 * float + VarInt + float shape of set_health).
 */
public final class PacketIds {
    private PacketIds() {}

    private static int id(String name, int fallback) {
        Integer v = Integer.getInteger("mcbot.packet." + name);
        if (v == null) return fallback;
        System.out.println("[ids] override mcbot.packet." + name
                + " = 0x" + Integer.toHexString(v) + " (default 0x"
                + Integer.toHexString(fallback) + ")");
        return v;
    }

    // ---- clientbound (play) -------------------------------------------------

    /** entity_event / entity_status: VarInt entity id, byte status (2 = hurt). */
    public static final int ENTITY_STATUS = id("entity_status", 0x1D);
    /**
     * damage_event: VarInt entityId, VarInt sourceType, VarInt causeId, VarInt
     * directId. DISABLED by default: 0x18 was captured and turned out to be
     * entity metadata (33/94 byte bodies, not 4 VarInts), so parsing it as a
     * damage event would read garbage. Set -Dmcbot.packet.damage_event=0x.. once
     * a capture actually exercises combat. Until then damage is detected from
     * the health packet and the hurt animation.
     */
    public static final int DAMAGE_EVENT = id("damage_event", -1);
    /** set_health: float health, VarInt food, float saturation (9 bytes). */
    public static final int SET_HEALTH = id("set_health", 0x6A);
    /** open_screen: VarInt windowId, VarInt menuType, text component. */
    public static final int OPEN_SCREEN = id("open_screen", 0x33);
    /** container_set_content: windowId, stateId, slots. */
    public static final int CONTAINER_SET_CONTENT = id("container_set_content", 0x12);
    /** container_close: windowId. */
    public static final int CONTAINER_CLOSE = id("container_close", 0x13);
    /** set_held_item: byte slot. */
    public static final int SET_HELD_ITEM = id("set_held_item", 0x4A);

    // ---- serverbound (play) -------------------------------------------------

    /**
     * swing: VarInt hand (0 = main hand). DISABLED by default - unverified, and
     * a wrong serverbound id disconnects the bot (see INTERACT below).
     */
    public static final int SWING = id("swing", -1);
    /**
     * interact: VarInt entityId, VarInt action (1 = attack), boolean sneaking.
     *
     * DISABLED by default, and this one cost us a real disconnect. The guess was
     * 0x36; sending a 4-byte interact body there made the server answer:
     *
     *   Packet play/serverbound/minecraft:set_carried_item was larger than I
     *   expected, found 2 bytes extra
     *
     * so serverbound 0x36 is set_carried_item, not interact. The real interact
     * id is still unknown; enable it with -Dmcbot.packet.interact=0x.. once you
     * have evidence, and survival will fight back again.
     */
    public static final int INTERACT = id("interact", -1);
    /** use_item: VarInt hand, VarInt sequence, float yaw, float pitch. */
    public static final int USE_ITEM = id("use_item", 0x35);
    /** use_item_on: hand, BlockPos, face, cursor xyz, inside, sequence. */
    public static final int USE_ITEM_ON = id("use_item_on", 0x3A);
    /**
     * set_held_slot: the hotbar slot as a **short** (2 bytes, not a VarInt).
     *
     * This id is the one piece of good news from the disconnect above: the
     * server named 0x36 as set_carried_item, so we no longer have to guess it.
     * The short encoding is why a 4-byte body produced "2 bytes extra".
     */
    public static final int SET_HELD_SLOT = id("set_held_slot", 0x36);
    /** container_click: windowId, stateId, slot, button, clickType, changed, carried. */
    public static final int CONTAINER_CLICK = id("container_click", 0x0F);
    /** container_close (serverbound): windowId. */
    public static final int CONTAINER_CLOSE_SB = id("container_close_sb", 0x0E);
}
