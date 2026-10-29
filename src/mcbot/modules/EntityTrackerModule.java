package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the server-side position of entities that are currently in range.
 *
 * The "come here" command needs the chat sender's entity position, but the
 * player chat packet only carries the sender UUID. add_entity is the only
 * packet that links a UUID to an entity id, so that populates the map and the
 * position is then maintained from entity_position_sync / teleport_entity /
 * move_entity_* packets.
 *
 * Positions are decoded with the same VecDelta base logic the vanilla client
 * uses (1/4096 block units relative to the last sent position), so the tracker
 * stays in sync with the server even when only small movement deltas arrive.
 */
public class EntityTrackerModule implements BotModule {
    private static final int ADD_ENTITY = 0x01;
    private static final int ENTITY_POSITION_SYNC = 0x23;
    private static final int MOVE_ENTITY_POS = 0x36;
    private static final int MOVE_ENTITY_POS_ROT = 0x37;
    private static final int MOVE_ENTITY_ROT = 0x39;
    private static final int REMOVE_ENTITIES = 0x4E;
    private static final int LOGIN = 0x32;
    private static final int RESPAWN = 0x54;
    private static final int TELEPORT_ENTITY = 0x80;

    private static final double DELTA_SCALE = 4096.0;
    private static final int MAX_STEPS = 1024;

    private final Map<Integer, TrackedEntity> byId = new HashMap<>();
    private final Map<UUID, TrackedEntity> byUuid = new HashMap<>();

    public static final class TrackedEntity {
        public final int id;
        public final UUID uuid;
        /** Entity type registry id from add_entity. -1 when unknown. */
        public int type = -1;
        public double x;
        public double y;
        public double z;
        public double baseX;
        public double baseY;
        public double baseZ;
        public float yaw;
        public float pitch;
        public long lastUpdateMs;

        TrackedEntity(int id, UUID uuid) {
            this.id = id;
            this.uuid = uuid;
        }

        void setPosition(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastUpdateMs = System.currentTimeMillis();
        }

        void setRotation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.lastUpdateMs = System.currentTimeMillis();
        }

        void setBase(double x, double y, double z) {
            this.baseX = x;
            this.baseY = y;
            this.baseZ = z;
        }
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) {
        try {
            switch (packetId) {
                case ADD_ENTITY:
                    handleAddEntity(data);
                    break;
                case ENTITY_POSITION_SYNC:
                    handlePositionSync(data);
                    break;
                case MOVE_ENTITY_POS:
                    handleMoveEntity(data, false);
                    break;
                case MOVE_ENTITY_POS_ROT:
                    handleMoveEntity(data, true);
                    break;
                case MOVE_ENTITY_ROT:
                    handleMoveEntityRot(data);
                    break;
                case REMOVE_ENTITIES:
                    handleRemoveEntities(data);
                    break;
                case TELEPORT_ENTITY:
                    handleTeleportEntity(data);
                    break;
                case LOGIN:
                case RESPAWN:
                    clear();
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            System.out.println("[entity] parse error id=0x"
                    + Integer.toHexString(packetId) + ": " + e);
        }
    }

    /**
     * Immutable copy of one tracked entity. Everything that reads the tracker
     * from another thread (gaze, combat) uses this instead of the live object,
     * so a position update on the network thread cannot tear a read.
     */
    public static final class Pose {
        public final int id;
        public final UUID uuid;
        public final int type;
        public final double x, y, z;
        public final float yaw, pitch;
        public final long lastUpdateMs;

        Pose(TrackedEntity e) {
            this.id = e.id;
            this.uuid = e.uuid;
            this.type = e.type;
            this.x = e.x;
            this.y = e.y;
            this.z = e.z;
            this.yaw = e.yaw;
            this.pitch = e.pitch;
            this.lastUpdateMs = e.lastUpdateMs;
        }

        public double distanceTo(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Copy of every entity currently in range. */
    public synchronized java.util.List<Pose> poses() {
        java.util.List<Pose> out = new java.util.ArrayList<>(byId.size());
        for (TrackedEntity e : byId.values()) out.add(new Pose(e));
        return out;
    }

    /** Copy of one entity by UUID, or null when it is not in range. */
    public synchronized Pose poseByUuid(UUID uuid) {
        TrackedEntity e = byUuid.get(uuid);
        return e == null ? null : new Pose(e);
    }

    /** Copy of one entity by id, or null when it is not in range. */
    public synchronized Pose pose(int id) {
        TrackedEntity e = byId.get(id);
        return e == null ? null : new Pose(e);
    }

    /** Returns x,y,z for the tracked entity, or null when it is not in range. */
    public synchronized double[] positionOf(UUID uuid) {
        TrackedEntity e = byUuid.get(uuid);
        if (e == null) return null;
        return new double[]{e.x, e.y, e.z};
    }

    /**
     * Returns x,y,z,yaw,pitch for the tracked entity, or null when it is not in
     * range. The rotation is the server-reported body rotation, in degrees.
     */
    public synchronized double[] poseOf(UUID uuid) {
        TrackedEntity e = byUuid.get(uuid);
        if (e == null) return null;
        return new double[]{e.x, e.y, e.z, e.yaw, e.pitch};
    }

    public synchronized long lastUpdateMs(UUID uuid) {
        TrackedEntity e = byUuid.get(uuid);
        return e == null ? 0L : e.lastUpdateMs;
    }

    public synchronized int trackedCount() {
        return byId.size();
    }

    /**
     * Nearest dropped item to a point, or null when none is in range.
     *
     * Drops are real entities with real positions, so this is ground truth:
     * the alternative is to walk to where the block used to be and hope the
     * item did not bounce off a slope on the way down.
     */
    public synchronized Pose nearestItem(double x, double y, double z, double radius) {
        Pose best = null;
        double bestDist = radius * radius;
        for (TrackedEntity e : byId.values()) {
            if (!mcbot.registry.Entities.isDroppedItem(e.type)) continue;
            double dx = e.x - x;
            double dy = e.y - y;
            double dz = e.z - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestDist) {
                bestDist = d;
                best = new Pose(e);
            }
        }
        return best;
    }

    private void clear() {
        synchronized (this) {
            byId.clear();
            byUuid.clear();
        }
    }

    private void handleAddEntity(byte[] data) {
        PacketReader pr = new PacketReader(data);
        int id = pr.readVarInt();
        UUID uuid = pr.readUUID();
        int type = pr.readVarInt(); // entity type (registry id)
        double x = pr.readDouble();
        double y = pr.readDouble();
        double z = pr.readDouble();
        skipLpVec3(pr);            // movement
        float pitch = unpackDegrees(pr.readByte()); // xRot
        float yaw = unpackDegrees(pr.readByte());   // yRot
        pr.readByte();             // yHeadRot
        // data (VarInt) follows; it is not needed.
        synchronized (this) {
            TrackedEntity e = new TrackedEntity(id, uuid);
            e.type = type;
            e.setPosition(x, y, z);
            e.setBase(x, y, z);
            e.setRotation(yaw, pitch);
            TrackedEntity old = byUuid.put(uuid, e);
            if (old != null && old.id != id) byId.remove(old.id);
            byId.put(id, e);
        }
    }

    /**
     * LpVec3: a zero byte means Vec3.ZERO, otherwise a packed 48-bit vector.
     * The decoder lives in {@link mcbot.net.LpVec3Codec} so this module and
     * the motion handler cannot drift apart.
     */
    private static void skipLpVec3(PacketReader pr) {
        mcbot.net.LpVec3Codec.skip(pr);
    }

    private void handlePositionSync(byte[] data) {
        PacketReader pr = new PacketReader(data);
        int id = pr.readVarInt();
        int type = pr.readVarInt();
        double x;
        double y;
        double z;
        if (type == 0) {
            x = pr.readDouble();
            y = pr.readDouble();
            z = pr.readDouble();
        } else if (type == 1) {
            int count = pr.readVarInt();
            if (count <= 0) return;
            x = 0;
            y = 0;
            z = 0;
            for (int i = 0; i < count; i++) {
                x = pr.readDouble();
                y = pr.readDouble();
                z = pr.readDouble();
                pr.readVarInt(); // tick offset
            }
        } else {
            return;
        }
        synchronized (this) {
            TrackedEntity e = byId.get(id);
            if (e == null) return;
            e.setPosition(x, y, z);
            e.setBase(x, y, z);
        }
    }

    private void handleMoveEntity(byte[] data, boolean hasRotation) {
        PacketReader pr = new PacketReader(data);
        int id = pr.readVarInt();
        int packed = pr.readVarInt();
        int steps = packed >>> 1;
        TrackedEntity e;
        synchronized (this) {
            e = byId.get(id);
        }
        if (e == null) return;
        double[] pos = readDelta(pr, steps, e.baseX, e.baseY, e.baseZ);
        float yaw = 0f;
        float pitch = 0f;
        if (hasRotation) {
            yaw = unpackDegrees(pr.readByte());   // yRot
            pitch = unpackDegrees(pr.readByte()); // xRot
        }
        synchronized (this) {
            TrackedEntity cur = byId.get(id);
            if (cur == null) return;
            cur.setPosition(pos[0], pos[1], pos[2]);
            cur.setBase(pos[0], pos[1], pos[2]);
            if (hasRotation) cur.setRotation(yaw, pitch);
        }
    }

    /** move_entity_rot: VarInt id, boolean onGround, byte yRot, byte xRot. */
    private void handleMoveEntityRot(byte[] data) {
        PacketReader pr = new PacketReader(data);
        int id = pr.readVarInt();
        pr.readBoolean(); // onGround
        float yaw = unpackDegrees(pr.readByte());
        float pitch = unpackDegrees(pr.readByte());
        synchronized (this) {
            TrackedEntity e = byId.get(id);
            if (e == null) return;
            e.setRotation(yaw, pitch);
        }
    }

    /** Packet rotation bytes are 1/256 of a turn. */
    private static float unpackDegrees(byte b) {
        return b * 360.0F / 256.0F;
    }

    private void handleRemoveEntities(byte[] data) {
        PacketReader pr = new PacketReader(data);
        int count = pr.readVarInt();
        if (count < 0) return;
        synchronized (this) {
            for (int i = 0; i < count; i++) {
                TrackedEntity e = byId.remove(pr.readVarInt());
                if (e != null) byUuid.remove(e.uuid, e);
            }
        }
    }

    private void handleTeleportEntity(byte[] data) {
        PacketReader pr = new PacketReader(data);
        int id = pr.readVarInt();
        double x = pr.readDouble();
        double y = pr.readDouble();
        double z = pr.readDouble();
        pr.readDouble(); // delta x
        pr.readDouble(); // delta y
        pr.readDouble(); // delta z
        float yaw = pr.readFloat();
        float pitch = pr.readFloat();
        int relatives = pr.readInt(); // Relative.SET_STREAM_CODEC == int bitmask
        synchronized (this) {
            TrackedEntity e = byId.get(id);
            if (e == null) return;
            if ((relatives & 1) != 0) x = e.x + x;
            if ((relatives & 2) != 0) y = e.y + y;
            if ((relatives & 4) != 0) z = e.z + z;
            e.setPosition(x, y, z);
            e.setBase(x, y, z);
            e.setRotation(yaw, pitch);
        }
    }

    /**
     * Mirrors VecDelta.read + VecDeltaCodec.decode. A component whose delta is
     * zero keeps the previous value exactly; otherwise the new value is
     * decode(encode(base) + delta) with the 1/4096 fixed point scale.
     */
    private static double[] readDelta(PacketReader pr, int steps,
                                      double baseX, double baseY, double baseZ) {
        if (steps > 0) {
            if (steps > MAX_STEPS) throw new IllegalArgumentException("too many steps " + steps);
            double x = baseX;
            double y = baseY;
            double z = baseZ;
            for (int i = 0; i < steps; i++) {
                pr.readVarInt(); // ticks
                short xa = pr.readShort();
                short ya = pr.readShort();
                short za = pr.readShort();
                x = decodeComponent(x, xa);
                y = decodeComponent(y, ya);
                z = decodeComponent(z, za);
            }
            return new double[]{x, y, z};
        }
        short xa = pr.readShort();
        short ya = pr.readShort();
        short za = pr.readShort();
        return new double[]{
                decodeComponent(baseX, xa),
                decodeComponent(baseY, ya),
                decodeComponent(baseZ, za)
        };
    }

    private static double decodeComponent(double base, short delta) {
        if (delta == 0) return base;
        return (Math.round(base * DELTA_SCALE) + delta) / DELTA_SCALE;
    }
}
