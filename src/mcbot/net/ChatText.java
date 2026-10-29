package mcbot.net;

/**
 * Flattens a Minecraft text component, the way it arrives on the wire.
 *
 * Chat messages are not strings: every clientbound chat packet carries a tree
 * of "text" / "translate" / "extra" nodes encoded as network NBT. Reading the
 * strings out of it is what makes whispers visible at all - {@code /msg} is
 * delivered as {@code disguised_chat}, and its payload is three components
 * (message, sender name, optional target name).
 */
public final class ChatText {
    private static final int MAX_DEPTH = 32;

    private ChatText() {}

    /** Reads one component from the reader and returns its plain text. */
    public static String read(PacketReader pr) {
        return payload(pr, pr.readUnsignedByte(), 0);
    }

    /**
     * {sender, message} when this {@code disguised_chat} packet is a whisper to
     * us, otherwise null.
     *
     * Vanilla marks a private message with a target name in the chat type;
     * ordinary disguised chat (some servers/proxies) has none, and answering
     * those would mean replying to server noise.
     */
    public static String[] whisper(byte[] data) {
        try {
            PacketReader pr = new PacketReader(data);
            String message = read(pr);
            pr.readVarInt();                     // chat type registry id
            String name = read(pr);              // who it is from
            if (!pr.readBoolean()) return null;  // no target: not a whisper to us
            read(pr);                            // target name (us)
            if (name == null || message == null) return null;
            String from = name.trim();
            String text = message.trim();
            if (from.isEmpty() || text.isEmpty()) return null;
            return new String[]{from, text};
        } catch (Exception e) {
            return null;
        }
    }

    private static String payload(PacketReader pr, int type, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalStateException("component too deep");
        switch (type) {
            case 0: return "";
            case 1: pr.readByte(); return "";
            case 2: pr.readShort(); return "";
            case 3: pr.readInt(); return "";
            case 4: pr.readLong(); return "";
            case 5: pr.readFloat(); return "";
            case 6: pr.readDouble(); return "";
            case 7: { int n = pr.readInt(); pr.readBytes(Math.max(0, n)); return ""; }
            case 8: return string(pr);
            case 9: {
                int element = pr.readUnsignedByte();
                int n = pr.readInt();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) append(sb, payload(pr, element, depth + 1));
                return sb.toString();
            }
            case 10: return compound(pr, depth + 1);
            case 11: { int n = pr.readInt(); pr.readBytes(Math.max(0, n) * 4); return ""; }
            case 12: { int n = pr.readInt(); pr.readBytes(Math.max(0, n) * 8); return ""; }
            default: throw new IllegalStateException("unknown nbt tag " + type);
        }
    }

    private static String compound(PacketReader pr, int depth) {
        StringBuilder extra = new StringBuilder();
        String text = null;
        while (true) {
            int type = pr.readUnsignedByte();
            if (type == 0) break;
            String key = string(pr);
            String value = payload(pr, type, depth + 1);
            if ("text".equals(key) || "translate".equals(key)) {
                if (text == null || text.isEmpty()) text = value;
            } else if ("with".equals(key) || "extra".equals(key)) {
                append(extra, value);
            }
        }
        if (text != null && !text.isEmpty()) {
            return extra.length() == 0 ? text : text + " " + extra;
        }
        return extra.toString().trim();
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null) return;
        String p = part.trim();
        if (p.isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(p);
    }

    /** NBT strings are a two-byte length, not the VarInt used elsewhere. */
    private static String string(PacketReader pr) {
        int len = pr.readUnsignedShort();
        return new String(pr.readBytes(len), java.nio.charset.StandardCharsets.UTF_8);
    }
}
