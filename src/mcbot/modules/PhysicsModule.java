package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;

import java.util.concurrent.atomic.AtomicBoolean;

public class PhysicsModule implements BotModule {
    private double x, y, z;
    private float yaw, pitch;
    private volatile boolean onGround = true;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean hasPosition = false;

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId != 0x49) return; // Synchronize Player Position
        PacketReader pr = new PacketReader(data);
        pr.readVarInt(); // teleport id, consumed by TeleportModule separately
        x = pr.readDouble();
        y = pr.readDouble();
        z = pr.readDouble();
        pr.readDouble(); pr.readDouble(); pr.readDouble(); // velocities
        yaw = pr.readFloat();
        pitch = pr.readFloat();
        pr.readInt(); // flags
        hasPosition = true;

        // after confirmation vanilla client sends one initial movement
        sendMovement(bot, true);
    }

    private void sendMovement(MinecraftBot bot, boolean immediate) throws Exception {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        PacketWriter.doubleVal(body, x);
        PacketWriter.doubleVal(body, y);
        PacketWriter.doubleVal(body, z);
        PacketWriter.floatVal(body, yaw);
        PacketWriter.floatVal(body, pitch);
        body.write(onGround ? 1 : 0);
        bot.sendPlay(0x1F, body.toByteArray());
    }
}