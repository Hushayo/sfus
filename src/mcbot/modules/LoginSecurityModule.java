package mcbot.modules;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;

public class LoginSecurityModule implements BotModule {
    private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onPlayPacket(int packetId, byte[] data, MinecraftBot bot) throws Exception {
        if (packetId != 0x49 || !scheduled.compareAndSet(false, true)) return;
        String pw = bot.password;
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                System.out.println("[bot] sending register/login commands");
                sendCmd(bot, "register " + pw + " " + pw);
                Thread.sleep(700);
                sendCmd(bot, "login " + pw);
            } catch (Exception e) {
                System.out.println("[bot] auth error: " + e);
            }
        }, "auth-thread").start();
    }

    private void sendCmd(MinecraftBot bot, String command) throws Exception {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        mcbot.net.PacketWriter.string(body, command);
        bot.sendPlay(0x07, body.toByteArray());
        System.out.println("[bot] sent command: /" + command);
    }
}