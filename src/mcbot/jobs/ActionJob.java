package mcbot.jobs;

import mcbot.api.ActionResult;
import mcbot.bot.MinecraftBot;

import java.util.function.Supplier;

/**
 * Runs one blocking {@link mcbot.api.Actions} call on the job thread.
 *
 * <p>Eating, equipping and building all wait for the server: the eat animation
 * is 1.6 s, an armour swap needs a slot update, a wall is one placement per
 * block. Doing that on the packet thread would stall keepalives and get the bot
 * kicked, so anything that waits goes through here.
 *
 * <p>Deliberately not used for mining, crafting or smelting: those already have
 * their own job classes, and nesting a job inside a job would make "stop" cancel
 * the wrong one.
 */
public final class ActionJob implements Job {
    private final MinecraftBot bot;
    private final String description;
    private final Supplier<ActionResult> task;

    private volatile boolean cancelled = false;
    private volatile String result = null;

    public ActionJob(MinecraftBot bot, String description, Supplier<ActionResult> task) {
        this.bot = bot;
        this.description = description;
        this.task = task;
    }

    @Override
    public void run() {
        if (cancelled) return;
        try {
            ActionResult r = task.get();
            result = r.message;
            System.out.println("[action] " + description + " -> " + r);
        } catch (Throwable t) {
            result = description + " failed: " + t.getMessage();
            System.out.println("[action] " + description + " crashed: " + t);
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean cancelled() {
        return cancelled;
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public String result() {
        return result;
    }
}
