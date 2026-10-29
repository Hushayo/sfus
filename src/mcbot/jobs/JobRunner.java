package mcbot.jobs;

import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;

/**
 * Runs at most one job at a time, on its own thread.
 *
 * Every long-running behaviour in the bot funnels through here so that "stop"
 * means one thing: cancel the current job and wait for it to notice. Without a
 * single owner, a new request could start a second miner while the first was
 * still breaking blocks, and the two would fight over the same target.
 */
public class JobRunner implements BotModule {
    private volatile MinecraftBot bot;
    private volatile Job current;
    private volatile Thread worker;
    private volatile String lastResult;

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    /** Starts a job, cancelling whatever was running. Returns the job for reporting. */
    public Job start(Job job) {
        cancel();
        current = job;
        lastResult = null;
        worker = new Thread(() -> {
            try {
                job.run();
            } catch (Throwable t) {
                System.out.println("[job] " + job.getClass().getSimpleName() + " crashed: " + t);
                t.printStackTrace();
            } finally {
                lastResult = job.result();
                if (current == job) current = null;
            }
        }, "job");
        worker.setDaemon(true);
        worker.start();
        return job;
    }

    /** Cancels the running job, if any. Returns true when something was running. */
    public boolean cancel() {
        Job job = current;
        if (job == null) return false;
        job.cancel();
        Thread t = worker;
        if (t != null) t.interrupt();
        current = null;
        System.out.println("[job] cancelled: " + job.describe());
        return true;
    }

    public boolean busy() {
        Job job = current;
        return job != null && !job.cancelled();
    }

    public Job current() {
        return current;
    }

    public String lastResult() {
        return lastResult;
    }

    /** One line for chat/status: what is happening right now. */
    public String status() {
        Job job = current;
        if (job == null) return lastResult == null ? "idle" : lastResult;
        return job.describe();
    }
}
