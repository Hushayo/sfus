package mcbot;

import mcbot.bot.MinecraftBot;
import mcbot.bot.ReconnectPolicy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

public class Main {
    /** The one and only log file, unless BOT_LOG overrides it. */
    private static final String DEFAULT_LOG = "logs" + File.separator + "bot.log";

    /**
     * Held for the life of the process. A scheduled task that fires every few
     * minutes must not stack up a second bot on the same account: the server
     * kicks the first one as "already online", and the two then fight.
     */
    private static RandomAccessFile lockFile;
    private static FileLock instanceLock;

    public static void main(String[] args) throws Exception {
        setupLogging();

        if (anotherInstanceIsRunning()) {
            System.out.println("[bot] another instance already holds sfus.lock; exiting");
            return;
        }

        String user = (args.length > 0) ? args[0] : "CustomBot";
        String host = (args.length > 1) ? args[1] : "51.195.191.92";
        int port = (args.length > 2) ? Integer.parseInt(args[2]) : 10679;
        String pw = (args.length > 3) ? args[3] : System.getenv("BOT_PASSWORD");
        if (pw == null || pw.isEmpty()) pw = "ChangeMe123";
        String apiKey = (args.length > 4) ? args[4] : System.getenv("OLLAMA_API_KEY");

        // One process, many sessions. Every attempt gets a fresh MinecraftBot:
        // the world cache, inventory, job runner and module state all belong to
        // a connection, and reusing them after a kick is how a bot ends up
        // walking with a stale position and an inventory from last night.
        ReconnectPolicy policy = new ReconnectPolicy(2_000L, 30_000L);
        while (true) {
            long sessionStart = System.currentTimeMillis();
            MinecraftBot bot = new MinecraftBot(host, port, user, pw, apiKey);
            bot.installDefaults();
            try {
                bot.run();
            } catch (Throwable t) {
                System.out.println("[bot] connection failed: " + t);
            } finally {
                bot.shutdown();
            }

            long lived = System.currentTimeMillis() - sessionStart;
            if (lived >= ReconnectPolicy.HEALTHY_SESSION_MS) policy.reset();

            if (!ReconnectPolicy.enabled()) {
                System.out.println("[bot] BOT_RECONNECT=0, not reconnecting");
                return;
            }
            long delay = policy.nextDelayMs();
            String why = bot.disconnectReason == null ? "connection closed" : bot.disconnectReason;
            System.out.println("[bot] " + why + " - reconnecting in "
                    + (delay / 1000) + "s (attempt " + policy.attempts() + ")");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * True when another process holds the instance lock.
     *
     * <p>Uses a file lock rather than a PID file because Windows releases it
     * when the process dies - including a crash or a kill - so a stale lock
     * cannot keep the bot down. If the lock file cannot even be opened (a
     * read-only working directory, for instance), the bot runs anyway: refusing
     * to start is worse than the rare duplicate.
     */
    private static boolean anotherInstanceIsRunning() {
        try {
            lockFile = new RandomAccessFile("sfus.lock", "rw");
            instanceLock = lockFile.getChannel().tryLock();
            if (instanceLock == null) {
                lockFile.close();
                lockFile = null;
                return true;
            }
            return false;
        } catch (Exception e) {
            System.out.println("[bot] could not create sfus.lock (" + e
                    + "); continuing without a single-instance guard");
            return false;
        }
    }

    /**
     * Mirrors stdout and stderr into a single log file.
     *
     * Every launcher used to build its own filename - run.ps1 one, VPS shell
     * scripts another, systemd a third - and each ad-hoc run left a fresh
     * bot-a.log/aiout2.txt behind. Owning the file here means the same one path
     * is written no matter how the process was started, and stderr cannot
     * become a second empty .err file.
     *
     * The stream is still a tee: the console and systemd journal keep working.
     * The file is truncated on each start so a restarted bot never greps a
     * previous session's "already online"; set BOT_LOG_APPEND=1 to keep history.
     */
    private static void setupLogging() {
        PrintStream console = System.out;
        String path = System.getenv("BOT_LOG");
        if (path == null || path.isEmpty()) path = DEFAULT_LOG;

        try {
            File file = new File(path).getAbsoluteFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            boolean append = System.getenv("BOT_LOG_APPEND") != null;
            // Explicit charset: a jar started on a Linux VPS has no console
            // encoding to inherit, and packet dumps contain non-ASCII chat.
            PrintStream tee = new PrintStream(
                    new Tee(console, new FileOutputStream(file, append)),
                    true, "UTF-8");
            System.setOut(tee);
            System.setErr(tee);
            // Through System.out, so the path lands in the file as well.
            System.out.println("[log] writing to " + file);
        } catch (Exception e) {
            // Logging is a convenience; never stop the bot over it.
            console.println("[log] cannot open " + path + ": " + e);
        }
    }

    /** Writes every byte to two streams so stdout is not sacrificed to the file. */
    private static final class Tee extends OutputStream {
        private final OutputStream console;
        private final OutputStream file;

        Tee(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            console.write(b);
            file.write(b);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            console.write(buf, off, len);
            file.write(buf, off, len);
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            file.flush();
        }

        @Override
        public void close() throws IOException {
            // Flush only: closing stdout would break the console for callers.
            flush();
        }
    }
}
