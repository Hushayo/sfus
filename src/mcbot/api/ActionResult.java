package mcbot.api;

/**
 * The result of one high-level action: did it work, and what should be said
 * about it.
 *
 * <p>Actions are the layer above modules. A module answers "did the packet go
 * out"; an action answers "did the thing happen". Keeping the two separate is
 * what stops the chat layer from reporting success for a click the server
 * silently rejected.
 */
public final class ActionResult {
    public final boolean ok;
    public final String message;

    private ActionResult(boolean ok, String message) {
        this.ok = ok;
        this.message = message;
    }

    public static ActionResult ok(String message) {
        return new ActionResult(true, message);
    }

    public static ActionResult fail(String message) {
        return new ActionResult(false, message);
    }

    /** A result that carries a pre-formatted sentence from a module. */
    public static ActionResult of(boolean ok, String message) {
        return new ActionResult(ok, message);
    }

    @Override
    public String toString() {
        return (ok ? "ok: " : "failed: ") + message;
    }
}
