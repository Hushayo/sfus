package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketIds;
import mcbot.net.PacketReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tab list: which UUID is called what.
 *
 * <p>Without this, "attack ManalaHush" is not expressible - the bot tracks
 * entities by id and UUID, and chat by UUID, but never learns that a particular
 * UUID belongs to a particular name. {@code player_info_update} is the packet
 * that carries it.
 *
 * <p>The entry payload is a per-action union: every set bit in the mask adds
 * fields to every entry. Two of those actions carry data this parser cannot
 * skip reliably (the chat session key and the display-name component, which is
 * NBT), so an update containing either is ignored wholesale rather than
 * half-parsed. Losing one name update is harmless; reading a component as a
 * VarInt desynchronises the rest of the packet and invents players.
 */
public class PlayerListModule implements BotModule {
    /** Action bits in PlayerInfoUpdatePacket. */
    private static final int ADD_PLAYER = 1;
    private static final int INITIALIZE_CHAT = 1 << 1;
    private static final int UPDATE_GAME_MODE = 1 << 2;
    private static final int UPDATE_LISTED = 1 << 3;
    private static final int UPDATE_LATENCY = 1 << 4;
    private static final int UPDATE_DISPLAY_NAME = 1 << 5;
    private static final int UPDATE_LIST_ORDER = 1 << 6;
    private static final int UPDATE_HAT = 1 << 7;

    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile boolean warned = false;
    private volatile boolean loggedMask = false;

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) {
        if (packetId == PacketIds.PLAYER_INFO_UPDATE) {
            parseUpdate(data);
        } else if (packetId == PacketIds.PLAYER_INFO_REMOVE) {
            parseRemove(data);
        } else if (packetId == PacketIds.LOGIN || packetId == PacketIds.RESPAWN) {
            names.clear();
        }
    }

    private void parseUpdate(byte[] data) {
        PacketReader pr = new PacketReader(data);
        int mask = pr.readUnsignedByte();
        int count = pr.readVarInt();
        if (count < 0 || count > 1024) return;
        if (!loggedMask) {
            loggedMask = true;
            System.out.println("[players] player_info_update mask=0x"
                    + Integer.toHexString(mask) + " entries=" + count);
        }
        try {
            for (int i = 0; i < count; i++) {
                UUID uuid = pr.readUUID();
                String name = null;
                if ((mask & ADD_PLAYER) != 0) {
                    name = pr.readString();
                    int properties = pr.readVarInt();
                    for (int p = 0; p < properties; p++) {
                        pr.readString();                      // property name
                        pr.readString();                      // value
                        if (pr.readBoolean()) pr.readString(); // signature
                    }
                }
                if ((mask & INITIALIZE_CHAT) != 0) {
                    // Optional<RemoteChatSession.Data>: UUID, expiry, key, sig.
                    if (pr.readBoolean()) {
                        pr.readUUID();
                        pr.readLong();
                        skipByteArray(pr);
                        skipByteArray(pr);
                    }
                }
                if ((mask & UPDATE_GAME_MODE) != 0) pr.readVarInt();
                if ((mask & UPDATE_LISTED) != 0) pr.readBoolean();
                if ((mask & UPDATE_LATENCY) != 0) pr.readVarInt();
                if ((mask & UPDATE_DISPLAY_NAME) != 0) {
                    // Optional<Component>, serialised as NBT.
                    if (pr.readBoolean()) mcbot.registry.Nbt.skip(pr);
                }
                if ((mask & UPDATE_LIST_ORDER) != 0) pr.readVarInt();
                if ((mask & UPDATE_HAT) != 0) pr.readBoolean();
                if (name != null && !name.isBlank()) names.put(uuid, name);
            }
        } catch (RuntimeException e) {
            // A mask this parser does not fully understand must not invent
            // names: stop at the last entry that decoded cleanly.
            if (!warned) {
                warned = true;
                System.out.println("[players] stopped reading a player list update: " + e);
            }
        }
    }

    private static void skipByteArray(PacketReader pr) {
        int n = pr.readVarInt();
        if (n < 0 || n > pr.remaining()) throw new IllegalArgumentException("bad byte array " + n);
        pr.skip(n);
    }

    private void parseRemove(byte[] data) {
        PacketReader pr = new PacketReader(data);
        int count = pr.readVarInt();
        for (int i = 0; i < count && i < 1024; i++) {
            names.remove(pr.readUUID());
        }
    }

    public String nameOf(UUID uuid) {
        return uuid == null ? null : names.get(uuid);
    }

    /** UUID for a name, case-insensitively, or null. */
    public UUID uuidOf(String name) {
        if (name == null) return null;
        String want = name.trim();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(want)) return e.getKey();
        }
        return null;
    }

    /** True when the name is a known player. */
    public boolean known(String name) {
        return uuidOf(name) != null;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>(names.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public int size() {
        return names.size();
    }

    /** One line for status. */
    public String describe() {
        if (names.isEmpty()) return "no players listed";
        return names.size() + " listed: " + String.join(", ", names());
    }
}
