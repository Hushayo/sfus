package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.core.BotState;
import mcbot.net.PacketReader;
import mcbot.net.PacketWriter;

import java.io.ByteArrayOutputStream;

public class KeepAliveModule implements BotModule {
    @Override
    public void onConfigPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId == 0x04) {
            long id = new PacketReader(data).readLong();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.longVal(body, id);
            bot.sendConfig(0x04, body.toByteArray());
        }
    }

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId == 0x2D) {
            long id = new PacketReader(data).readLong();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            PacketWriter.longVal(body, id);
            bot.sendPlay(0x1C, body.toByteArray());
        }
    }
}