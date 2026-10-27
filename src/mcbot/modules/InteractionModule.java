package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketIds;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;

import java.io.ByteArrayOutputStream;

/**
 * Right-click / attack / hotbar / container plumbing.
 *
 * This is the low-level layer the crafting and furnace work sits on. Nothing
 * here guesses at game logic: it sends exactly the packets a vanilla client
 * sends and records what the server tells us back (open window id, held slot).
 *
 * Verified against the live server: block breaking (MiningModule) uses the same
 * BlockPos encoding and the same sequence counter.
 *
 * Best-effort (ids can be overridden, see {@link PacketIds}): interact/attack,
 * use_item, use_item_on and the container click packet. They are sent in the
 * same shape vanilla uses, but the exact id numbers for a given server build
 * may differ - run with BOT_DEBUG=1 and check the log if a call does nothing.
 */
public class InteractionModule implements BotModule {
    private static final int HAND_MAIN = 0;

    private volatile MinecraftBot bot;
    private volatile int sequence = 0;
    private volatile int heldSlot = 0;
    private volatile int windowId = 0;
    private volatile int windowType = -1;
    private volatile int lastSlotCount = 0;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    public int windowId() {
        return windowId;
    }

    public int heldSlot() {
        return heldSlot;
    }

    public String status() {
        if (windowId == 0) return "No container open (hotbar slot " + heldSlot + ").";
        return "Container open: window " + windowId + ", type " + windowType
                + ", slots " + lastSlotCount + ".";
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) {
        try {
            if (packetId == PacketIds.OPEN_SCREEN) {
                PacketReader pr = new PacketReader(data);
                windowId = pr.readVarInt();
                windowType = pr.readVarInt();
                System.out.println("[use] screen opened window=" + windowId + " type=" + windowType);
            } else if (packetId == PacketIds.CONTAINER_CLOSE) {
                PacketReader pr = new PacketReader(data);
                int id = pr.readVarInt();
                if (id == windowId) {
                    System.out.println("[use] screen closed window=" + id);
                    windowId = 0;
                    windowType = -1;
                    lastSlotCount = 0;
                }
            } else if (packetId == PacketIds.SET_HELD_ITEM) {
                PacketReader pr = new PacketReader(data);
                heldSlot = pr.readUnsignedByte();
            } else if (packetId == PacketIds.CONTAINER_SET_CONTENT) {
                PacketReader pr = new PacketReader(data);
                int id = pr.readVarInt();
                pr.readVarInt(); // state id
                int count = pr.readVarInt();
                if (id == windowId) lastSlotCount = count;
                if (MinecraftBot.DEBUG) {
                    System.out.println("[use] container content window=" + id
                            + " slots=" + count + " bytes=" + data.length);
                }
            }
        } catch (RuntimeException e) {
            System.out.println("[use] parse error id=0x" + Integer.toHexString(packetId) + ": " + e);
        }
    }

    /** Right-clicks a block: opens crafting tables, furnaces, chests, doors. */
    public String useBlock(int x, int y, int z) {
        MinecraftBot b = bot;
        if (b == null || !b.hasPlayerPosition) return "Not in the world yet.";
        try {
            faceBlock(b, x, y, z);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, HAND_MAIN);
            PacketWriter.blockPos(body, x, y, z);
            PacketWriter.varInt(body, faceFrom(b, x, y, z));
            PacketWriter.floatVal(body, 0.5f);
            PacketWriter.floatVal(body, 0.5f);
            PacketWriter.floatVal(body, 0.5f);
            body.write(0); // inside block
            PacketWriter.varInt(body, nextSequence());
            b.sendPlay(PacketIds.USE_ITEM_ON, body.toByteArray());
            System.out.println("[use] use_item_on " + x + " " + y + " " + z);
            return "Used the block at " + x + " " + y + " " + z + ".";
        } catch (Exception e) {
            return "Couldn't use that block: " + e.getMessage();
        }
    }

    /** Swings at an entity (the vanilla attack packet). */
    public String attack(int entityId) {
        MinecraftBot b = bot;
        if (b == null || entityId < 0) return "No target.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, entityId);
            PacketWriter.varInt(body, 1); // 1 = ATTACK
            body.write(0);                // not sneaking
            b.sendPlay(PacketIds.INTERACT, body.toByteArray());
            swing();
            return "Attacked entity " + entityId + ".";
        } catch (Exception e) {
            return "Attack failed: " + e.getMessage();
        }
    }

    /** Arm swing animation. */
    public void swing() {
        MinecraftBot b = bot;
        if (b == null) return;
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, HAND_MAIN);
            b.sendPlay(PacketIds.SWING, body.toByteArray());
        } catch (Exception ignored) {}
    }

    /** Uses the held item: eating, blocking with a shield, placing nothing. */
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
            b.sendPlay(PacketIds.USE_ITEM, body.toByteArray());
            return "Used the held item.";
        } catch (Exception e) {
            return "Use item failed: " + e.getMessage();
        }
    }

    /** Selects hotbar slot 0..8. */
    public String selectSlot(int slot) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected.";
        if (slot < 0 || slot > 8) return "Hotbar slot must be 0..8.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, slot);
            b.sendPlay(PacketIds.SET_HELD_SLOT, body.toByteArray());
            heldSlot = slot;
            return "Selected hotbar slot " + slot + ".";
        } catch (Exception e) {
            return "Slot select failed: " + e.getMessage();
        }
    }

    /**
     * Clicks a slot in the open container.
     *
     * clickType: 0 = pickup/place (left), 1 = pickup/place (right),
     *            2 = swap with hotbar (button = slot), 3 = clone (creative),
     *            4 = throw, 5 = quick move (shift click), 6 = pick all.
     *
     * The changed-slot list is sent empty and the carried stack as empty, which
     * is what most server implementations accept for a simple pickup. A real
     * client predicts the resulting slots; we do not, so a desync shows up as
     * the server sending container_set_content back.
     */
    public String clickSlot(int slot, int button, int clickType) {
        MinecraftBot b = bot;
        if (b == null) return "Not connected.";
        if (windowId == 0) return "No container is open.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, windowId);
            PacketWriter.varInt(body, 0); // state id (0 = accept anything)
            PacketWriter.shortVal(body, slot);
            body.write(button & 0xFF);
            PacketWriter.varInt(body, clickType);
            PacketWriter.varInt(body, 0); // changed slots
            PacketWriter.varInt(body, 0); // carried item: empty stack
            b.sendPlay(PacketIds.CONTAINER_CLICK, body.toByteArray());
            System.out.println("[use] click window=" + windowId + " slot=" + slot
                    + " button=" + button + " type=" + clickType);
            return "Clicked slot " + slot + ".";
        } catch (Exception e) {
            return "Click failed: " + e.getMessage();
        }
    }

    /** Closes the open container. */
    public String closeWindow() {
        MinecraftBot b = bot;
        if (b == null) return "Not connected.";
        if (windowId == 0) return "No container is open.";
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.varInt(body, windowId);
            b.sendPlay(PacketIds.CONTAINER_CLOSE_SB, body.toByteArray());
            int id = windowId;
            windowId = 0;
            return "Closed window " + id + ".";
        } catch (Exception e) {
            return "Close failed: " + e.getMessage();
        }
    }

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

    /** Which face of the block we are looking at (0 down .. 5 east). */
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
}
