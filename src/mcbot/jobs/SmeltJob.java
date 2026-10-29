package mcbot.jobs;

import mcbot.bot.MinecraftBot;
import mcbot.crafting.Recipes;
import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;

/**
 * "Smelt 10 iron ore" - load a furnace and wait for the output.
 *
 * A furnace is three slots: input, fuel, output. The job puts the raw material
 * in, works out how much fuel that needs (coal burns for eight items), then
 * polls the output slot. Polling the slot contents is what the server actually
 * reports; the burn and cook progress arrive in container_set_data, which is
 * not parsed yet, so time is measured rather than read.
 */
public final class SmeltJob implements Job {
    private static final int INPUT_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_SLOT = 2;
    /** Vanilla smelt time is 10 s per item. */
    private static final long PER_ITEM_MS = 10_000L;

    private final MinecraftBot bot;
    private final String item;
    private final int count;
    /** Where the requesting player stood, so their own furnace wins over a far one. */
    private final double[] near;

    private volatile boolean cancelled;
    private volatile String result;
    private int smelted;
    /** Why the last furnace-crafting attempt failed, for the result line. */
    private volatile String lastFurnaceError;

    public SmeltJob(MinecraftBot bot, String item, int count) {
        this(bot, item, count, null);
    }

    public SmeltJob(MinecraftBot bot, String item, int count, double[] near) {
        this.bot = bot;
        this.item = item;
        this.count = Math.max(1, count);
        this.near = near;
    }

    @Override public void cancel() { cancelled = true; }
    @Override public boolean cancelled() { return cancelled; }
    @Override public String result() { return result; }

    /** How many items the furnace actually produced, for plan verification. */
    public int smelted() { return smelted; }

    @Override
    public String describe() {
        return "smelting " + smelted + "/" + count + " " + ItemNames.pretty(item);
    }

    @Override
    public void run() {
        Inventory inv = bot.interaction.inventory;

        // What goes in the top slot. The caller normally resolves this to the
        // exact item in the inventory, but fall back to the obvious synonyms so
        // a direct SmeltJob call ("smelt iron ore" with only raw_iron carried)
        // still finds something to cook instead of reporting an empty furnace.
        String input = item;
        if (inv.count(input) <= 0) {
            String base = item.replaceFirst("^raw_", "")
                    .replaceFirst("_ore$", "")
                    .replaceFirst("_ingot$", "");
            for (String candidate : new String[]{"raw_" + base, base + "_ore"}) {
                if (inv.count(candidate) > 0) {
                    input = candidate;
                    break;
                }
            }
        }
        if (inv.count(input) <= 0) {
            result = "I have no " + ItemNames.pretty(item) + " to smelt.";
            System.out.println("[smelt] " + result);
            return;
        }
        String fuel = Recipes.bestFuel(inv);
        if (fuel == null) {
            result = "I have no fuel to smelt with.";
            System.out.println("[smelt] " + result);
            return;
        }

        if (!bot.crafting.openFurnaceNear(near)) {
            if (!makeFurnace()) {
                result = "I can't find a furnace and couldn't make one"
                        + (lastFurnaceError == null ? "." : ": " + lastFurnaceError + ".");
                System.out.println("[smelt] " + result);
                return;
            }
        }
        if (!bot.crafting.waitForWindow(4000L)) {
            result = "The furnace didn't open.";
            return;
        }

        int batch = Math.min(count, inv.count(input));
        System.out.println("[smelt] " + batch + "x " + input + " with " + fuel);

        // Input stack.
        moveInto(sourceSlot(inv, input), INPUT_SLOT, Math.min(batch, 64));
        // Fuel: one coal per eight items, plus one for the initial burn.
        int fuelTicks = Recipes.fuelTicks(fuel);
        int perFuel = Math.max(1, fuelTicks / 200);
        int fuelNeeded = (batch + perFuel - 1) / perFuel;
        moveInto(sourceSlot(inv, fuel), FUEL_SLOT, fuelNeeded);

        // The click is answered by the server, not by the client: at 200 ms RTT
        // the slot still looks empty on the very next line, which is what made a
        // correctly loaded furnace report "nothing landed" and give up instantly.
        boolean loaded = waitForInput(1500L);
        if (loaded && inv.get(FUEL_SLOT).isEmpty()) {
            moveInto(sourceSlot(inv, fuel), FUEL_SLOT, fuelNeeded);
            sleep(300);
        }

        System.out.println("[smelt] furnace window " + inv.windowId() + " ("
                + inv.layout().name + "): input=" + inv.get(INPUT_SLOT)
                + " fuel=" + inv.get(FUEL_SLOT) + " output=" + inv.get(OUTPUT_SLOT));
        if (!loaded) {
            System.out.println("[smelt] nothing landed in the input slot, giving up");
            bot.interaction.closeWindow();
            result = "I couldn't load the furnace.";
            System.out.println("[smelt] " + result);
            return;
        }

        long deadline = System.currentTimeMillis() + batch * PER_ITEM_MS + 15_000L;
        long nextReport = 0;
        while (!cancelled && smelted < batch && System.currentTimeMillis() < deadline) {
            ItemStack out = inv.get(OUTPUT_SLOT);
            if (!out.isEmpty()) {
                int n = out.count;
                bot.interaction.quickMove(OUTPUT_SLOT);
                smelted += n;
                System.out.println("[smelt] took " + n + " " + Items.name(out.itemId)
                        + " (" + smelted + "/" + batch + ")");
                // Top the furnace up if there is more to do.
                if (smelted < batch && inv.count(input) > 0 && inv.get(INPUT_SLOT).isEmpty()) {
                    moveInto(sourceSlot(inv, input), INPUT_SLOT, batch - smelted);
                }
                continue;
            }
            if (System.currentTimeMillis() > nextReport) {
                nextReport = System.currentTimeMillis() + 5000L;
                System.out.println("[smelt] waiting: input=" + inv.get(INPUT_SLOT)
                        + " fuel=" + inv.get(FUEL_SLOT) + " output=" + inv.get(OUTPUT_SLOT));
            }
            sleep(250);
        }

        bot.interaction.closeWindow();
        if (smelted >= batch) {
            result = "Smelted " + smelted + " " + ItemNames.pretty(item) + ".";
        } else if (smelted > 0) {
            result = "Smelted " + smelted + " of " + batch + " " + ItemNames.pretty(item) + ".";
        } else {
            result = "The furnace didn't produce anything.";
        }
        System.out.println("[smelt] " + result);
    }

    /**
     * Crafts and places a furnace when none is nearby.
     *
     * Smelting is not just "use a furnace": a bot in a fresh world has no
     * furnace, and the plan that got it here ("craft an iron pickaxe") has
     * already mined the cobblestone. Making the workstation is part of the
     * intent, not a separate command the player has to think of.
     */
    private boolean makeFurnace() {
        Inventory inv = bot.interaction.inventory;
        if (inv.count("furnace") <= 0) {
            Recipes.Plan plan = Recipes.plan("furnace", 1, inv);
            if (!plan.ok()) {
                lastFurnaceError = plan.error;
                return false;
            }
            System.out.println("[smelt] no furnace nearby, making one: " + Recipes.describe(plan));
            CraftJob make = new CraftJob(bot, "furnace", 1);
            make.run();
            if (cancelled) return false;
            if (inv.count("furnace") <= 0) {
                lastFurnaceError = make.result() == null ? "crafting failed" : make.result();
                return false;
            }
        }
        if (!bot.crafting.placeNearby("furnace")) {
            lastFurnaceError = "no free spot to place it";
            return false;
        }
        return bot.crafting.openFurnaceNear(near);
    }

    /**
     * Moves up to `amount` of the item in `from` into `to`.
     *
     * Every click is verified: on a server 200 ms away the answer to one click
     * can still be in flight when the next is sent, and an unverified sequence
     * ends up placing items from an empty cursor.
     */
    private void moveInto(int from, int to, int amount) {
        if (from < 0) return;
        Inventory inv = bot.interaction.inventory;
        ItemStack stack = inv.get(from);
        if (stack.isEmpty()) return;
        if (!bot.interaction.pickupVerified(from, 900L)) return;
        sleep(60);
        if (stack.count <= amount) {
            bot.interaction.clickVerified(to, 0, Inventory.CLICK_PICKUP, 900L);
        } else {
            // Split: right-click places one at a time, which is slow but exact.
            for (int i = 0; i < amount; i++) {
                if (!bot.interaction.clickVerified(to, 1, Inventory.CLICK_PICKUP, 900L)) break;
                sleep(40);
            }
            bot.interaction.pickupVerified(from, 900L);
        }
        sleep(120);
    }

    private boolean waitForInput(long timeoutMs) {
        Inventory inv = bot.interaction.inventory;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!inv.get(INPUT_SLOT).isEmpty()) return true;
            sleep(50);
        }
        return !inv.get(INPUT_SLOT).isEmpty();
    }

    private static int sourceSlot(Inventory inv, String itemName) {
        int id = Items.id(itemName);
        for (int slot = 36; slot <= 44; slot++) {
            int menu = inv.menuSlotOf(slot);
            ItemStack s = inv.get(menu);
            if (!s.isEmpty() && s.itemId == id) return menu;
        }
        for (int slot = 9; slot <= 35; slot++) {
            int menu = inv.menuSlotOf(slot);
            ItemStack s = inv.get(menu);
            if (!s.isEmpty() && s.itemId == id) return menu;
        }
        return -1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
