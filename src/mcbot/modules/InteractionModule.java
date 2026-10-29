package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.net.PacketIds;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;
import mcbot.registry.Blocks;
import mcbot.registry.Items;
import mcbot.util.Pos;

import java.io.ByteArrayOutputStream;

/**
 * Player actions and container plumbing: attack, swing, use item, place block,
 * open a window, move items.
 *
 * Every id in here comes from the server's packet report
 * ({@link PacketIds}), which replaced a set of guesses that were wrong in ways
 * that produced no error at all:
 *
 * <pre>
 *   open_screen    was 0x33 (low_disk_space_warning) -> 0x3C
 *   use_item_on    was 0x3A (set_game_rule)           -> 0x42
 *   container_click was 0x0F (command_suggestion)     -> 0x12
 * </pre>
 *
 * The container state id is tracked and echoed on every click. Sending 0 (what
 * this used to do) makes the server treat the click as stale and resync the
 * window, so only the first click of any sequence ever applied.
 */
public class InteractionModule implements BotModule {
    private static final int HAND_MAIN = 0;

    /** Vanilla reach: 4.5 blocks for blocks, 3.0 for entities. */
    public static final double BLOCK_REACH = 4.5;
    public static final double ENTITY_REACH = 3.0;

    private volatile MinecraftBot bot;
    private volatile int sequence = 0;
    public final Inventory inventory = new Inventory();

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
        inventory.open(Inventory.PLAYER_WINDOW, -1);
    }

    public int heldSlot() {
        return inventory.heldSlot();
    }

    public String status() {
        return inventory.describe();
    }

    // ---------------------------------------------------------------- packets --

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) {
        try {
            if (packetId == PacketIds.OPEN_SCREEN) {
                PacketReader pr = new PacketReader(data);
                int windowId = pr.readVarInt();
                int menuType = pr.readVarInt();
                inventory.open(windowId, menuType);
            } else if (packetId == PacketIds.CONTAINER_SET_CONTENT) {
                PacketReader pr = new PacketReader(data);
                int windowId = pr.readVarInt();
                int stateId = pr.readVarInt();
                inventory.setContent(pr, windowId, stateId);
                if (pr.remaining() > 0) {
                    // Older layouts append the carried stack here; newer ones
                    // send it separately as set_cursor_item. Read it only when
                    // the bytes are actually there.
                    inventory.setCarried(ItemStack.readOptional(pr));
                }
            } else if (packetId == PacketIds.CONTAINER_SET_SLOT) {
                PacketReader pr = new PacketReader(data);
                int windowId = pr.readVarInt();
                int stateId = pr.readVarInt();
                int slot = pr.readUnsignedShort();
                inventory.setSlot(pr, windowId, stateId, slot);
            } else if (packetId == PacketIds.SET_CURSOR_ITEM) {
                inventory.setCarried(ItemStack.readSingle(new PacketReader(data)));
            } else if (packetId == PacketIds.SET_HELD_SLOT) {
                inventory.setHeldSlot(new PacketReader(data).readUnsignedByte());
            } else if (packetId == PacketIds.CONTAINER_CLOSE) {
                inventory.close(new PacketReader(data).readVarInt());
            } else if (packetId == PacketIds.SET_PLAYER_INVENTORY) {
                PacketReader pr = new PacketReader(data);
                int slot = pr.readVarInt();
                inventory.setPlayerSlot(slot, ItemStack.readSingle(pr));
            }
        } catch (RuntimeException e) {
            System.out.println("[use] parse error id=0x" + Integer.toHexString(packetId)
                    + " len=" + data.length + ": " + e);
            if (mcbot.bot.MinecraftBot.DEBUG) {
                System.out.println("[use] hex=" + java.util.HexFormat.of().formatHex(data));
            }
        }
    }

    // --------------------------------------------------------------- actions --

    /** Right-clicks a block: opens tables and chests, flips levers, uses doors. */
    public String useBlock(int x, int y, int z) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "Not in the world yet.";
        try {
            int face = faceFrom(b, x, y, z);
            faceBlock(b, x, y, z);
            b.sendPlay(PacketIds.SB_USE_ITEM_ON, useItemOnBody(x, y, z, face));
            swing();
            return "Used the block at " + x + " " + y + " " + z + ".";
        } catch (Exception e) {
            return "Couldn't use that block: " + e.getMessage();
        }
    }

    /**
     * The body of {@code use_item_on}, read off the server's own codec.
     *
     * <pre>
     *   ServerboundUseItemOnPacket = composite(hand, hitResult, sequence)
     *   BlockHitResult             = BlockPos, Direction, cursorX, cursorY,
     *                                cursorZ, inside, worldBorderHit
     * </pre>
     *
     * The two booleans at the end are easy to miss: the packet has *both*
     * {@code inside} and {@code isWorldBorderHit}, and omitting the second one
     * leaves the frame one byte short, which the server reports as
     * "Failed to decode packet 'serverbound/minecraft:use_item_on'" and answers
     * by closing the connection.
     *
     * The cursor floats are offsets from the block corner, not world
     * coordinates: {@code (float)(location.x - pos.getX())}.
     */
    private byte[] useItemOnBody(int x, int y, int z, int face) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(32);
        PacketWriter.varInt(body, HAND_MAIN);
        PacketWriter.blockPos(body, x, y, z);
        PacketWriter.varInt(body, face);
        PacketWriter.floatVal(body, cursorX(face));
        PacketWriter.floatVal(body, cursorY(face));
        PacketWriter.floatVal(body, cursorZ(face));
        body.write(0);                          // inside block
        body.write(0);                          // isWorldBorderHit
        PacketWriter.varInt(body, nextSequence());
        return body.toByteArray();
    }

    /**
     * Places the held block against a face.
     *
     * @param face 0=down 1=up 2=north 3=south 4=west 5=east
     */
    public String placeBlock(int x, int y, int z, int face) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "Not in the world yet.";
        try {
            faceBlock(b, x, y, z);
            b.sendPlay(PacketIds.SB_USE_ITEM_ON, useItemOnBody(x, y, z, face));
            swing();
            return "Placed against " + x + " " + y + " " + z + " face " + face + ".";
        } catch (Exception e) {
            return "Place failed: " + e.getMessage();
        }
    }

    /** Left-clicks an entity. This is the packet that actually deals damage. */
    public String attack(int entityId) {
        MinecraftBot b = bot;
        if (b == null || entityId < 0) return "No target.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, entityId);
            b.sendPlay(PacketIds.SB_ATTACK, body.toByteArray());
            swing();
            return "Attacked entity " + entityId + ".";
        } catch (Exception e) {
            return "Attack failed: " + e.getMessage();
        }
    }

    /**
     * Interacts with an entity without attacking (right click: mount, trade,
     * shear). Layout from the server's codec: entityId, hand, location
     * (LpVec3), secondary-action flag. There is no "action type" field - that
     * was a guess from an older protocol.
     */
    public String interactEntity(int entityId, boolean atLocation) {
        MinecraftBot b = bot;
        if (b == null || entityId < 0) return "No target.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, entityId);
            PacketWriter.varInt(body, HAND_MAIN);
            if (atLocation) {
                // LpVec3 of a non-zero vector: flag byte, low byte, uint, extra.
                body.write(0x01);
                body.write(0);
                PacketWriter.intVal(body, 0);
            } else {
                body.write(0);          // LpVec3.ZERO is a single zero byte
            }
            body.write(0);              // not the secondary action
            b.sendPlay(PacketIds.SB_INTERACT, body.toByteArray());
            return "Interacted with entity " + entityId + ".";
        } catch (Exception e) {
            return "Interact failed: " + e.getMessage();
        }
    }

    /** The arm swing. Cheap, and the server uses it for several checks. */
    public void swing() {
        MinecraftBot b = bot;
        if (b == null) return;
        try {
            b.sendPlay(PacketIds.SB_PUNCH, new byte[0]);
        } catch (Exception ignored) {}
    }

    /** Uses the held item: eating, drinking, shielding, casting. */
    public String useItem() {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "Not in the world yet.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, HAND_MAIN);
            PacketWriter.varInt(body, nextSequence());
            float yaw;
            float pitch;
            synchronized (b.positionLock) {
                yaw = b.playerYaw;
                pitch = b.playerPitch;
            }
            PacketWriter.floatVal(body, yaw);
            PacketWriter.floatVal(body, pitch);
            b.sendPlay(PacketIds.SB_USE_ITEM, body.toByteArray());
            return "Used the held item.";
        } catch (Exception e) {
            return "Use item failed: " + e.getMessage();
        }
    }

    /**
     * Releases a use (stops eating / lowers the shield).
     *
     * <p>{@code ServerboundPlayerActionPacket} is
     * {@code composite(action, BlockPos, Direction, sequence)} - four fields,
     * not one. Sending only the action byte (what this used to do) makes the
     * server fail to decode the packet and disconnect the client with
     * "Failed to decode packet 'serverbound/minecraft:player_action'", which is
     * exactly what happened on the first meal of every session.
     *
     * <p>Action ordinals in 26.3: START_DESTROY_BLOCK 0,
     * CHANGE_DESTROY_DIRECTION 1, ABORT 2, STOP 3, DROP_ALL_ITEMS 4,
     * DROP_ITEM 5, RELEASE_USE_ITEM 6, SWAP_ITEM_WITH_OFFHAND 7, STAB 8.
     */
    public void releaseUseItem() {
        MinecraftBot b = bot;
        if (b == null) return;
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(16);
            PacketWriter.varInt(body, 6);               // RELEASE_USE_ITEM
            PacketWriter.blockPos(body, 0, 0, 0);       // no block is involved
            PacketWriter.varInt(body, 1);               // Direction.UP
            PacketWriter.varInt(body, nextSequence());
            b.sendPlay(PacketIds.SB_PLAYER_ACTION, body.toByteArray());
        } catch (Exception e) {
            System.out.println("[use] release failed: " + e);
        }
    }

    public String selectSlot(int slot) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected.";
        if (slot < 0 || slot > 8) return "Hotbar slot must be 0..8.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.shortVal(body, slot);
            b.sendPlay(PacketIds.SB_SET_CARRIED_ITEM, body.toByteArray());
            inventory.setHeldSlot(slot);
            return "Selected hotbar slot " + slot + ".";
        } catch (Exception e) {
            return "Slot select failed: " + e.getMessage();
        }
    }

    /**
     * Puts the fastest tool for a block in the main hand.
     *
     * The whole inventory is considered, not just the hotbar: a diamond
     * pickaxe one row up used to be invisible, so the bot mined with whatever
     * happened to be in hand. When the best tool is in the main inventory it is
     * swapped into the held hotbar slot, which is what a player does with the
     * number keys.
     */
    public boolean equipBestTool(int blockState) {
        int playerSlot = inventory.bestToolPlayerSlot(blockState);
        if (playerSlot < 0) return false;
        if (playerSlot >= 36) {
            int hotbar = playerSlot - 36;
            if (hotbar == inventory.heldSlot()) return false;
            selectSlot(hotbar);
            return true;
        }
        int held = inventory.heldSlot();
        int menuSlot = inventory.menuSlotOf(playerSlot);
        if (!clickVerified(menuSlot, held, Inventory.CLICK_SWAP, 900L)) {
            // Fall back to the plain click: a swap that reports no slot update
            // still usually happened, and the next swing re-checks the hand.
            swapWithHotbar(menuSlot, held);
        }
        return true;
    }

    // -------------------------------------------------------------- clicking --

    public String clickSlot(int slot, int button, int clickType) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, inventory.windowId());
            PacketWriter.varInt(body, inventory.stateId());
            PacketWriter.shortVal(body, slot);
            body.write(button & 0xFF);
            PacketWriter.varInt(body, clickType);
            PacketWriter.varInt(body, 0);  // changed slots: none predicted
            ItemStack.writeEmpty(body);    // carried item: empty
            b.sendPlay(PacketIds.SB_CONTAINER_CLICK, body.toByteArray());
            if (MinecraftBot.DEBUG) {
                System.out.println("[click] win=" + inventory.windowId()
                        + " state=" + inventory.stateId() + " slot=" + slot
                        + " button=" + button + " type=" + clickType
                        + " slotHas=" + inventory.get(slot)
                        + " cursor=" + inventory.carried());
            }
            return "Clicked slot " + slot + " (button " + button + ", type " + clickType + ").";
        } catch (Exception e) {
            return "Click failed: " + e.getMessage();
        }
    }

    /** Left-click: pick the stack up, or drop what is carried onto the slot. */
    public String pickup(int slot) { return clickSlot(slot, 0, Inventory.CLICK_PICKUP); }

    /** Shift-click: move the stack between container and inventory. */
    public String quickMove(int slot) { return clickSlot(slot, 0, Inventory.CLICK_QUICK_MOVE); }

    /**
     * Sends a click and reports whether the server actually applied it.
     *
     * This exists because the player menu's state id is not knowable after a
     * container window closes: {@code AbstractContainerMenu.transferState}
     * copies the closed menu's {@code remoteSlots} into the player menu but
     * leaves its id alone, so the server believes the client is already in
     * sync and never resends window 0. The client is left holding a guess.
     *
     * The guess is easy to correct, though, because the two outcomes look
     * different on the wire: an applied click answers with
     * {@code container_set_slot} for the slots it changed, while a rejected one
     * answers with a full {@code container_set_content} carrying the real state
     * id. So: send, watch which arrives, and resend once with the corrected id.
     *
     * @return true when a click was accepted
     */
    public boolean clickVerified(int slot, int button, int clickType, long timeoutMs) {
        MinecraftBot b = bot;
        if (b == null) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            int updates = inventory.slotUpdates();
            int syncs = inventory.fullSyncs();
            clickSlot(slot, button, clickType);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (inventory.slotUpdates() != updates) return true;
                if (inventory.fullSyncs() != syncs) {
                    // Rejected, and the resync just handed us the real state
                    // id. Sending the same click again will be accepted.
                    System.out.println("[click] rejected slot=" + slot
                            + ", resynced to state " + inventory.stateId()
                            + (attempt == 0 ? ", retrying" : ""));
                    break;
                }
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** Pickup with the stale-id recovery of {@link #clickVerified}. */
    public boolean pickupVerified(int slot, long timeoutMs) {
        return clickVerified(slot, 0, Inventory.CLICK_PICKUP, timeoutMs);
    }

    /** Quick-move with the stale-id recovery of {@link #clickVerified}. */
    public boolean quickMoveVerified(int slot, long timeoutMs) {
        return clickVerified(slot, 0, Inventory.CLICK_QUICK_MOVE, timeoutMs);
    }

    /** Q: drop one item from the slot. */
    public String dropOne(int slot) { return clickSlot(slot, 0, Inventory.CLICK_THROW); }

    /** Ctrl-Q: drop the whole stack. */
    public String dropStack(int slot) { return clickSlot(slot, 1, Inventory.CLICK_THROW); }

    /** Moves a stack from one slot to another: pick up, then place. */
    public String moveStack(int from, int to) {
        String a = pickup(from);
        String b = pickup(to);
        return a + " " + b;
    }

    /** Swaps a slot with a hotbar slot (number keys while hovering). */
    public String swapWithHotbar(int slot, int hotbarSlot) {
        return clickSlot(slot, hotbarSlot, Inventory.CLICK_SWAP);
    }

    public String closeWindow() {
        MinecraftBot b = bot;
        if (b == null) return "Not connected.";
        if (!inventory.isOpen()) return "No container is open.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, inventory.windowId());
            b.sendPlay(PacketIds.SB_CONTAINER_CLOSE, body.toByteArray());
            int id = inventory.windowId();
            inventory.close(id);
            return "Closed window " + id + ".";
        } catch (Exception e) {
            return "Close failed: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- helpers --

    private int nextSequence() {
        int s = sequence + 1;
        if (s <= 0 || s > 1_000_000) s = 1;
        sequence = s;
        return s;
    }

    private static void faceBlock(MinecraftBot b, int x, int y, int z) {
        synchronized (b.positionLock) {
            double ex = b.playerX;
            double ey = b.playerY + 1.62;
            double ez = b.playerZ;
            double dx = (x + 0.5) - ex;
            double dy = (y + 0.5) - ey;
            double dz = (z + 0.5) - ez;
            b.playerYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            b.playerPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        }
    }

    /** Which face of the block the bot is looking at: 0 down .. 5 east. */
    private static int faceFrom(MinecraftBot b, int x, int y, int z) {
        double ex, ey, ez;
        synchronized (b.positionLock) {
            ex = b.playerX;
            ey = b.playerY + 1.62;
            ez = b.playerZ;
        }
        double dx = ex - (x + 0.5);
        double dy = ey - (y + 0.5);
        double dz = ez - (z + 0.5);
        double adx = Math.abs(dx);
        double ady = Math.abs(dy);
        double adz = Math.abs(dz);
        if (ady >= adx && ady >= adz) return dy > 0 ? 1 : 0;
        if (adx >= adz) return dx > 0 ? 5 : 4;
        return dz > 0 ? 3 : 2;
    }

    /** Cursor position on the clicked face, in block-local 0..1 coordinates. */
    private static float cursorX(int face) {
        return face == 4 ? 1f : face == 5 ? 0f : 0.5f;
    }

    private static float cursorY(int face) {
        return face == 1 ? 1f : face == 0 ? 0f : 0.5f;
    }

    private static float cursorZ(int face) {
        return face == 2 ? 0f : face == 3 ? 1f : 0.5f;
    }

    /**
     * True when the block is close enough that the server will accept an
     * interaction.
     *
     * Vanilla's limit is 4.5 blocks from the eye to the block *centre*, and the
     * server adds a 1-block buffer before rejecting. Using the full 4.5 as a
     * "good enough" distance means standing 5+ blocks from the block centre,
     * which some servers (and most anti-cheat plugins) reject outright - the
     * dig silently never starts. 3.5 keeps the bot inside the range every
     * server agrees on, and the mining job walks closer if it is not.
     */
    public static final double MINE_REACH = 3.5;

    public boolean inReach(int x, int y, int z) {
        MinecraftBot b = bot;
        if (b == null) return false;
        double ex, ey, ez;
        synchronized (b.positionLock) {
            ex = b.playerX;
            ey = b.playerY + 1.62;
            ez = b.playerZ;
        }
        double dx = (x + 0.5) - ex;
        double dy = (y + 0.5) - ey;
        double dz = (z + 0.5) - ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= MINE_REACH;
    }

    /** Convenience: block name at a position, for logs and chat. */
    public static String blockNameAt(MinecraftBot b, int x, int y, int z) {
        return Blocks.shortName(b.world.getBlock(x, y, z));
    }

    /** Convenience: is the held item a block we can place? */
    public boolean holdingPlaceable() {
        return inventory.held().item().isBlockItem();
    }

    public String describeHeld() {
        return Items.describe(inventory.held().itemId);
    }
}
