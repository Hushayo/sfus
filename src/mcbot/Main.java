package mcbot;

import mcbot.bot.MinecraftBot;

public class Main {
    public static void main(String[] args) throws Exception {
        String user = (args.length > 0) ? args[0] : "CustomBot";
        String host = (args.length > 1) ? args[1] : "51.195.191.92";
        int port = (args.length > 2) ? Integer.parseInt(args[2]) : 10679;
        String pw = (args.length > 3) ? args[3] : System.getenv("BOT_PASSWORD");
        if (pw == null || pw.isEmpty()) pw = "ChangeMe123";
        String apiKey = (args.length > 4) ? args[4] : System.getenv("OLLAMA_API_KEY");

        MinecraftBot bot = new MinecraftBot(host, port, user, pw, apiKey);
        bot.installDefaults();
        bot.run();
    }
}