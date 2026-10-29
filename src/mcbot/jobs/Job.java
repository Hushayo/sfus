package mcbot.jobs;

/**
 * A unit of work the bot can be asked to do, run on its own thread.
 *
 * Jobs exist because "mine 10 stone" is not one action: it is find, walk,
 * equip, break, verify, repeat. Anything that takes longer than a tick has to
 * be a job, or the 20 Hz ticker stalls and the connection times out.
 */
public interface Job {
    /** Runs to completion. Must return promptly once {@link #cancel()} is called. */
    void run();

    /** Asks the job to stop. Jobs poll this between actions, never inside one. */
    void cancel();

    boolean cancelled();

    /** One line describing what the job is doing, for chat and status. */
    String describe();

    /** Final one-line outcome, or null while still running. */
    String result();
}
