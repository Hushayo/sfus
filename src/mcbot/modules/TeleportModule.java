package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;

import java.io.ByteArrayOutputStream;

/**
 * Handles ClientboundPlayerPositionPacket (0x49).
 *
 * The server expects the accept packet to echo the resulting position and
 * rotation exactly. It must not be followed by a movement packet in the same
 * server tick; the pathfinder sends the next move on its own schedule.
 */
public class TeleportModule implements BotModule {
    private int lastAcceptedId = Integer.MIN_VALUE;

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId != 0x49) return; // player_position

        PacketReader pr = new PacketReader(data);
        int teleportId = pr.readVarInt();
        double rx = pr.readDouble();
        double ry = pr.readDouble();
        double rz = pr.readDouble();
        pr.readDouble(); // delta x
        pr.readDouble(); // delta y
        pr.readDouble(); // delta z
        float ryaw = pr.readFloat();
        float rpitch = pr.readFloat();
        int flags = pr.readInt();

        if (teleportId == lastAcceptedId) {
            System.out.println("[teleport] duplicate id=" + teleportId + " ignored");
            return;
        }
        lastAcceptedId = teleportId;

        synchronized (bot.positionLock) {
            boolean hadPosition = bot.hasPlayerPosition;
            double baseX = hadPosition ? bot.playerX : 0.0;
            double baseY = hadPosition ? bot.playerY : 0.0;
            double baseZ = hadPosition ? bot.playerZ : 0.0;
            float baseYaw = hadPosition ? bot.playerYaw : 0.0f;
            float basePitch = hadPosition ? bot.playerPitch : 0.0f;

            double x = ((flags & 1) != 0) ? baseX + rx : rx;
            double y = ((flags & 2) != 0) ? baseY + ry : ry;
            double z = ((flags & 4) != 0) ? baseZ + rz : rz;
            float yaw = ((flags & 8) != 0) ? baseYaw + ryaw : ryaw;
            float pitch = ((flags & 16) != 0) ? basePitch + rpitch : rpitch;

            bot.playerX = x;
            bot.playerY = y;
            bot.playerZ = z;
            bot.playerYaw = yaw;
            bot.playerPitch = pitch;
            bot.hasPlayerPosition = true;
            bot.lastTeleportMs = System.currentTimeMillis();
            bot.teleportCounter++;

            System.out.println("[teleport] id=" + teleportId + " pos=" + x + "," + y + "," + z
                    + " yaw=" + yaw + " pitch=" + pitch + " flags=" + flags);

            // accept_teleportation: id + echoed position/rotation
            ByteArrayOutputStream confirm = new ByteArrayOutputStream();
            PacketWriter.varInt(confirm, teleportId);
            PacketWriter.doubleVal(confirm, x);
            PacketWriter.doubleVal(confirm, y);
            PacketWriter.doubleVal(confirm, z);
            PacketWriter.floatVal(confirm, yaw);
            PacketWriter.floatVal(confirm, pitch);
            bot.sendPlay(0x00, confirm.toByteArray());

            // Reset the server's per-tick position flag; no movement is sent here.
            bot.sendTickEnd();
        }
    }
}
