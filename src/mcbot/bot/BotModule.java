package mcbot.bot;

import mcbot.core.BotState;

public interface BotModule {
    default void install(mcbot.bot.MinecraftBot bot) {}
    default void onStateChange(BotState state) {}
    default void onLoginPacket(int packetId, byte[] data, mcbot.bot.MinecraftBot bot) throws Exception {}
    default void onConfigPacket(int packetId, byte[] data, mcbot.bot.MinecraftBot bot) throws Exception {}
    default void onPlayPacket(int packetId, byte[] data, mcbot.bot.MinecraftBot bot) throws Exception {}

    /**
     * Called once per client tick (20/s) from the bot ticker thread, but only
     * while the bot is in PLAY. Implementations must return quickly: anything
     * that blocks (pathfinding, HTTP) belongs on its own worker thread.
     */
    default void onTick(mcbot.bot.MinecraftBot bot) {}
}
