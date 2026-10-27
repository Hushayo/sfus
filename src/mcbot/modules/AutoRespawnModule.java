package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.net.PacketWriter;

import java.io.ByteArrayOutputStream;

public class AutoRespawnModule implements BotModule {
    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId != 0x45) return; // Combat Death
        new Thread(() -> {
            try {
                Thread.sleep(1500);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                PacketWriter.varInt(body, 0); // 0 = Perform respawn
                bot.sendPlay(0x0C, body.toByteArray());
                System.out.println("[bot] auto-respawn sent");
            } catch (Exception e) {
                System.out.println("[bot] auto-respawn error: " + e);
            }
        }, "respawn-thread").start();
    }
}