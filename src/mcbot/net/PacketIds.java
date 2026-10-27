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
 *   java -Dmcbot.packet.set_health=0x5B -Dmcbot.packet.interact=0x36 ...
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

    /** swing: VarInt hand (0 = main hand). */
    public static final int SWING = id("swing", 0x33);
    /** interact: VarInt entityId, VarInt action (1 = attack), boolean sneaking. */
    public static final int INTERACT = id("interact", 0x36);
    /** use_item: VarInt hand, VarInt sequence, float yaw, float pitch. */
    public static final int USE_ITEM = id("use_item", 0x35);
    /** use_item_on: hand, BlockPos, face, cursor xyz, inside, sequence. */
    public static final int USE_ITEM_ON = id("use_item_on", 0x3A);
    /** set_held_slot: VarInt slot (0..8). */
    public static final int SET_HELD_SLOT = id("set_held_slot", 0x34);
    /** container_click: windowId, stateId, slot, button, clickType, changed, carried. */
    public static final int CONTAINER_CLICK = id("container_click", 0x0F);
    /** container_close (serverbound): windowId. */
    public static final int CONTAINER_CLOSE_SB = id("container_close_sb", 0x0E);
}
