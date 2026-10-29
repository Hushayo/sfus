package mcbot.jobs;

import mcbot.bot.MinecraftBot;
import mcbot.crafting.Recipe;
import mcbot.crafting.Recipes;
import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Craft me a pickaxe" - resolve a recipe tree, gather what is missing, then
 * place the ingredients and take the result.
 *
 * The job runs the plan {@link Recipes#plan} produces, step by step, and
 * verifies the inventory at the end. It never claims success it did not
 * observe: if the grid does not produce a result slot after placing, it says
 * so instead of pretending to have crafted something.
 *
 * Grid placement is the classic click dance, because the recipe book packets
 * depend on the server's display ids and are not always enabled:
 *
 * <pre>
 *   click inventory slot   -> stack on cursor
 *   right click grid cell  -> place exactly one
 *   click inventory slot   -> put the remainder back
 * </pre>
 */
public final class CraftJob implements Job {
    private static final long CRAFT_TIMEOUT_MS = 8000L;

    private final MinecraftBot bot;
    private final String item;
    private final int count;

    private volatile boolean cancelled;
    private volatile String result;
    private int made;

    public CraftJob(MinecraftBot bot, String item, int count) {
        this.bot = bot;
        this.item = item;
        this.count = Math.max(1, count);
    }

    @Override public void cancel() { cancelled = true; }
    @Override public boolean cancelled() { return cancelled; }
    @Override public String result() { return result; }

    @Override
    public String describe() {
        return "crafting " + made + "/" + count + " " + ItemNames.pretty(item);
    }

    @Override
    public void run() {
        // "Craft" is a verb: holding some already is not an answer to it. plan()
        // subtracts what the inventory holds, so the held amount is added back to
        // make exactly `count` new ones instead of reporting "you already have".
        Recipes.Plan plan = Recipes.plan(item, count + bot.interaction.inventory.count(item),
                bot.interaction.inventory);
        if (!plan.ok()) {
            result = "I can't make " + ItemNames.pretty(item) + ": " + plan.error + ".";
            System.out.println("[craft] " + result);
            return;
        }
        System.out.println("[craft] plan for " + count + "x " + item + ": " + Recipes.describe(plan));

        if (!runPlan(plan)) {
            if (bot.interaction.inventory.isOpen()) bot.interaction.closeWindow();
            return;
        }
        if (bot.interaction.inventory.isOpen()) bot.interaction.closeWindow();

        int have = bot.interaction.inventory.count(item);
        String name = plural(ItemNames.pretty(item), count);
        if (made >= count) {
            result = "Crafted " + count + " " + name + ".";
        } else if (made > 0) {
            result = "Crafted " + made + " of " + count + " " + name + ".";
        } else if (have >= count) {
            // Nothing was made because nothing needed to be: the inventory
            // already held what was asked for. Saying "crafted" here would be
            // a lie, and the difference matters to whoever asked.
            result = "You already have " + have + " " + name + ".";
        } else {
            result = "I didn't manage to craft " + name + ".";
        }
        System.out.println("[craft] " + result);
    }

    /** "1 wooden pickaxe" / "3 wooden pickaxes". */
    private static String plural(String name, int n) {
        if (n == 1 || name.isEmpty()) return name;
        if (name.endsWith("s") || name.endsWith("x")) return name;
        return name + "s";
    }

    /** Executes a plan's steps in order. Returns false on the first failure. */
    private boolean runPlan(Recipes.Plan plan) {
        for (Recipes.Step step : plan.steps) {
            if (cancelled) {
                result = "Stopped while crafting " + ItemNames.pretty(item) + ".";
                return false;
            }
            switch (step.op) {
                case MINE: {
                    MineJob mine = new MineJob(bot, step.item, step.count, 32);
                    mine.run();
                    if (cancelled) {
                        result = "Stopped while gathering " + ItemNames.pretty(step.item) + ".";
                        return false;
                    }
                    if (mine.result() != null) System.out.println("[craft] gather: " + mine.result());
                    // A plan that silently continues after a shortfall ends in
                    // "no recipe for X" or a half-built item. Stop at the step
                    // that actually failed and say so.
                    if (mine.mined() < step.count) {
                        result = "I couldn't gather " + step.count + "x "
                                + ItemNames.pretty(step.item)
                                + (mine.result() == null ? "." : ": " + mine.result());
                        System.out.println("[craft] " + result);
                        return false;
                    }
                    break;
                }
                case SMELT: {
                    SmeltJob smelt = new SmeltJob(bot, step.item, step.count);
                    smelt.run();
                    if (cancelled) {
                        result = "Stopped while smelting " + ItemNames.pretty(step.item) + ".";
                        return false;
                    }
                    if (smelt.result() != null) System.out.println("[craft] smelt: " + smelt.result());
                    if (smelt.smelted() < step.count) {
                        result = "I couldn't smelt " + step.count + "x " + ItemNames.pretty(step.item)
                                + (smelt.result() == null ? "." : ": " + smelt.result());
                        System.out.println("[craft] " + result);
                        return false;
                    }
                    break;
                }
                case CRAFT: {
                    if (!craft(step.item, step.count)) {
                        result = "I couldn't craft " + ItemNames.pretty(step.item) + ".";
                        System.out.println("[craft] " + result);
                        return false;
                    }
                    break;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------- crafting --

    private boolean craft(String what, int amount) {
        Inventory inv = bot.interaction.inventory;
        // Intermediate wood types are interchangeable: if the plan says oak
        // planks but the bot chopped jungle, make jungle planks instead. The
        // final item the player asked for is never substituted.
        boolean allowEquivalent = !what.equals(item);
        Recipe recipe = Recipes.craftingFor(what, inv, allowEquivalent);
        if (recipe == null) {
            System.out.println("[craft] no recipe for " + what);
            return false;
        }
        String actual = recipe.output;
        boolean needsTable = recipe.kind == Recipe.Kind.SHAPED
                && (recipe.pattern.length > 2 || recipe.pattern[0].length > 2);
        boolean tableOpen = inv.isOpen();
        if (needsTable && !tableOpen) {
            // Prefer a table that already exists: one we placed earlier in this
            // session, then anything the scanner can see. Only when neither
            // answers do we build one from scratch, which may itself need
            // planks and logs - recursively running that plan is what stops
            // "have 2 planks" from turning into a failure when four more are
            // one log away.
            if (bot.crafting.openTable()) {
                tableOpen = true;
            } else {
                System.out.println("[craft] no crafting table nearby, making one");
                Recipes.Plan tablePlan = Recipes.plan("crafting_table", 1, inv);
                if (!tablePlan.ok() || !runPlan(tablePlan)) {
                    System.out.println("[craft] could not make a crafting table");
                    return false;
                }
                if (!bot.crafting.placeNearby("crafting_table")) {
                    System.out.println("[craft] made a crafting table but could not place it");
                    return false;
                }
                if (!bot.crafting.openTable()) {
                    System.out.println("[craft] placed a table but could not open it");
                    return false;
                }
                tableOpen = true;
            }
        }
        // Keep an already-open table for 2x2 recipes too. Closing and
        // reopening between plan steps changes the window's state id, and the
        // first click after a close is the one the server rejects as stale.
        if (!needsTable && !tableOpen) bot.crafting.openInventory();

        if (!bot.crafting.waitForWindow(4000L)) {
            System.out.println("[craft] window did not open");
            return false;
        }
        boolean threeByThree = inv.isOpen();
        int perCraft = recipe.count;
        int crafts = (amount + perCraft - 1) / perCraft;

        for (int i = 0; i < crafts; i++) {
            if (cancelled) return false;
            if (!placeIngredients(recipe, threeByThree)) {
                System.out.println("[craft] could not place ingredients for " + actual);
                return false;
            }
            int took = takeResult(recipe, threeByThree);
            if (took <= 0) {
                System.out.println("[craft] no " + actual + " appeared in the result slot");
                return false;
            }
            if (what.equals(item)) made += took;
        }
        return true;
    }

    /**
     * Places one of each ingredient into the grid cells the pattern names.
     * Returns false as soon as an ingredient is missing or a click does not
     * actually change the grid, so the job never reports success it did not
     * observe.
     */
    private boolean placeIngredients(Recipe recipe, boolean threeByThree) {
        Inventory inv = bot.interaction.inventory;
        Map<String, String> resolved = new LinkedHashMap<>();
        List<int[]> placements = new ArrayList<>(); // {slot, row, col}
        Map<String, Integer> required = new LinkedHashMap<>();

        if (recipe.kind == Recipe.Kind.SHAPED) {
            for (int r = 0; r < recipe.pattern.length; r++) {
                for (int c = 0; c < recipe.pattern[r].length; c++) {
                    String token = recipe.pattern[r][c];
                    if (token == null || token.isEmpty()) continue;
                    String concrete = resolved.get(token);
                    if (concrete == null) {
                        concrete = Recipes.resolveToken(token, inv);
                        if (concrete == null) return false;
                        resolved.put(token, concrete);
                    }
                    placements.add(new int[]{gridSlot(r, c, threeByThree), r, c});
                    required.merge(concrete, 1, Integer::sum);
                }
            }
        } else {
            for (int idx = 0; idx < recipe.ingredients.size(); idx++) {
                String token = recipe.ingredients.get(idx);
                String concrete = resolved.get(token);
                if (concrete == null) {
                    concrete = Recipes.resolveToken(token, inv);
                    if (concrete == null) return false;
                    resolved.put(token, concrete);
                }
                placements.add(new int[]{gridSlotForIndex(idx, threeByThree), 0, 0});
                required.merge(concrete, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Integer> e : required.entrySet()) {
            if (inv.count(e.getKey()) < e.getValue()) {
                System.out.println("[craft] missing " + e.getValue() + "x " + e.getKey()
                        + " (have " + inv.count(e.getKey()) + ")");
                return false;
            }
        }

        // Place cell by cell, in pattern order, exactly one item per cell.
        int cell = 0;
        for (int[] placement : placements) {
            if (cancelled) return false;
            int target = placement[0];
            int row = placement[1];
            int col = placement[2];
            String token = recipe.kind == Recipe.Kind.SHAPED
                    ? recipe.pattern[row][col] : recipe.ingredients.get(cell);
            String concrete = resolved.get(token);
            int source = sourceSlot(inv, concrete);
            if (source < 0) {
                System.out.println("[craft] lost track of " + concrete + " while filling the grid");
                return false;
            }
            int before = inv.get(target).count;
            ItemStack stack = inv.get(source);
            if (!bot.interaction.pickupVerified(source, 600L)) {
                System.out.println("[craft] could not pick up " + concrete + " from slot " + source);
                return false;
            }
            sleep(50);
            // Right-click places exactly one and keeps the remainder on the
            // cursor. Left-clicking would merge the whole stack into the cell
            // whenever the source stack has more than one item.
            if (!bot.interaction.clickVerified(target, 1, Inventory.CLICK_PICKUP, 600L)) {
                System.out.println("[craft] could not place " + concrete + " in slot " + target);
                return false;
            }
            sleep(50);
            if (stack.count > 1 && !bot.interaction.pickupVerified(source, 600L)) {
                System.out.println("[craft] could not return the rest of " + concrete
                        + " to slot " + source);
                return false;
            }
            if (!waitForSlot(target, concrete, before + 1, 800L)) {
                System.out.println("[craft] click did not put " + concrete + " in slot " + target
                        + " (slot has " + inv.get(target) + ")");
                return false;
            }
            cell++;
        }
        sleep(150);
        return cell > 0;
    }

    /**
     * Takes the crafted item out of the result slot, verifying that the
     * inventory actually gained it. A result slot that looks full but does not
     * move is the difference between "crafted" and "the click was rejected".
     *
     * @return how many items were moved, or -1 when nothing was crafted
     */
    private int takeResult(Recipe recipe, boolean threeByThree) {
        Inventory inv = bot.interaction.inventory;
        int resultSlot = 0;
        int outputId = Items.id(recipe.output);
        long deadline = System.currentTimeMillis() + CRAFT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ItemStack result = inv.get(resultSlot);
            if (!result.isEmpty()) {
                if (result.itemId != outputId) {
                    System.out.println("[craft] result slot holds " + result.name()
                            + ", expected " + recipe.output);
                    return -1;
                }
                int before = inv.count(outputId);
                int amount = result.count;
                if (!bot.interaction.quickMoveVerified(resultSlot, 900L)) {
                    System.out.println("[craft] the server rejected taking the result");
                    return -1;
                }
                long moved = System.currentTimeMillis() + 1500L;
                while (System.currentTimeMillis() < moved) {
                    if (inv.get(resultSlot).isEmpty() && inv.count(outputId) >= before + amount) {
                        return amount;
                    }
                    if (inv.count(outputId) > before) {
                        // Partial move is still progress; the next craft will
                        // top it up if there is room.
                        return inv.count(outputId) - before;
                    }
                    sleep(50);
                }
                System.out.println("[craft] result did not move into the inventory ("
                        + inv.get(resultSlot) + ")");
                return -1;
            }
            sleep(100);
        }
        return -1;
    }

    /** Waits until a grid slot contains at least {@code count} of {@code item}. */
    private boolean waitForSlot(int slot, String item, int count, long timeoutMs) {
        Inventory inv = bot.interaction.inventory;
        int id = Items.id(item);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ItemStack s = inv.get(slot);
            if (!s.isEmpty() && s.itemId == id && s.count >= count) return true;
            sleep(30);
        }
        return false;
    }

    private static int gridSlot(int row, int col, boolean threeByThree) {
        return threeByThree ? 1 + row * 3 + col : 1 + row * 2 + col;
    }

    /** Shapeless ingredients fill the grid in reading order, not always 3 wide. */
    private static int gridSlotForIndex(int idx, boolean threeByThree) {
        return threeByThree ? 1 + idx : 1 + (idx / 2) * 2 + (idx % 2);
    }

    /** Menu slot of a stack of the given item, preferring the hotbar. */
    private static int sourceSlot(Inventory inv, String itemName) {
        int id = Items.id(itemName);
        for (int slot = 36; slot <= 44; slot++) {
            ItemStack s = inv.get(inv.menuSlotOf(slot));
            if (!s.isEmpty() && s.itemId == id) return inv.menuSlotOf(slot);
        }
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack s = inv.get(inv.menuSlotOf(slot));
            if (!s.isEmpty() && s.itemId == id) return inv.menuSlotOf(slot);
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
