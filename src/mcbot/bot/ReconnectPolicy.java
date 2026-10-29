package mcbot.bot;

/**
 * How long to wait before trying the server again.
 *
 * <p>Exponential with a cap: 2 s, 4 s, 8 s, 16 s, then every 30 s. The first
 * retry is quick because most disconnects are a server restart or a brief
 * timeout; the cap matters because a bot that retries every two seconds against
 * a server that is down looks like a flood, and many hosts ban for that.
 *
 * <p>A session that lasted a while resets the backoff, so an all-day bot that
 * gets kicked once at midnight reconnects immediately instead of waiting 30 s
 * because of six failures at lunchtime.
 */
public final class ReconnectPolicy {
    private final long baseMs;
    private final long maxMs;
    private int attempt;

    public ReconnectPolicy(long baseMs, long maxMs) {
        this.baseMs = Math.max(100, baseMs);
        this.maxMs = Math.max(this.baseMs, maxMs);
    }

    /** Delay before the next attempt, and count it. */
    public long nextDelayMs() {
        int shift = Math.min(attempt, 16);
        long delay = baseMs << shift;
        if (delay <= 0 || delay > maxMs) delay = maxMs;
        attempt++;
        return Math.min(delay, maxMs);
    }

    public void reset() {
        attempt = 0;
    }

    public int attempts() {
        return attempt;
    }

    /** Reconnecting is the default; {@code BOT_RECONNECT=0} makes one attempt. */
    public static boolean enabled() {
        String v = System.getenv("BOT_RECONNECT");
        return v == null || v.isEmpty() || (!v.equals("0") && !v.equalsIgnoreCase("false"));
    }

    /** A session this long means the connection was healthy; forget the backoff. */
    public static final long HEALTHY_SESSION_MS = 60_000L;
}
