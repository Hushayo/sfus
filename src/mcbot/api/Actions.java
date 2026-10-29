package mcbot.api;

import mcbot.bot.MinecraftBot;
import mcbot.crafting.Recipes;
import mcbot.inventory.Inventory;
import mcbot.inventory.ItemStack;
import mcbot.jobs.CraftJob;
import mcbot.jobs.MineJob;
import mcbot.jobs.SmeltJob;
import mcbot.knowledge.Armor;
import mcbot.knowledge.Combat;
import mcbot.knowledge.Food;
import mcbot.knowledge.Mechanics;
import mcbot.registry.Blocks;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;
import mcbot.world.BlockScanner;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The bot's verbs.
 *
 * <p>This is the equivalent of what a script author gets from a mature bot
 * library - {@code dig}, {@code placeBlock}, {@code craft}, {@code eat},
 * {@code equip}, {@code openContainer}, {@code deposit} - implemented directly
 * on this project's own packets and modules. No third-party bot code is
 * involved; the design goal is only that the call sites read like an action,
 * not like a packet.
 *
 * <p>Two rules hold throughout:
 *
 * <ul>
 *   <li><b>Check, then act, then verify.</b> An action that cannot work returns
 *       a failure with the reason (missing tool, no food, out of reach) instead
 *       of sending a packet the server will ignore.
 *   <li><b>Blocking calls are marked.</b> Everything that waits (walking,
 *       eating, crafting) says so, and every wait has a timeout. A caller on the
 *       network thread must use the job runner instead.
 * </ul>
 */
public final class Actions {
    private final MinecraftBot bot;

    public Actions(MinecraftBot bot) {
        this.bot = bot;
    }

    public MinecraftBot bot() { return bot; }
    public Inventory inventory() { return bot.interaction.inventory; }

    // ------------------------------------------------------------- movement --

    /** Starts walking. Returns immediately; use {@link #gotoAndWait} to block. */
    public ActionResult gotoPosition(double x, double y, double z) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        return ActionResult.ok(bot.movement.goTo(x, y, z));
    }

    /** Walks and waits for arrival. Blocking. */
    public ActionResult gotoAndWait(double x, double y, double z, long timeoutMs) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        bot.movement.goTo(x, y, z);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!bot.movement.isMoving()) break;
            sleep(50);
        }
        double dist = distanceTo(x, y, z);
        if (dist <= 2.5) return ActionResult.ok("Arrived at " + fmt(x) + " " + fmt(y) + " " + fmt(z) + ".");
        return ActionResult.fail("Couldn't reach " + fmt(x) + " " + fmt(y) + " " + fmt(z)
                + " (stopped " + fmt(dist) + " blocks away).");
    }

    /** Walks to the nearest block of a type. Blocking. */
    public ActionResult gotoBlock(String blockName, int radius, long timeoutMs) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        BlockScanner.Hit hit = nearestBlock(blockName, radius);
        if (hit == null) return ActionResult.fail("I don't see any " + ItemNames.pretty(blockName) + " nearby.");
        return gotoAndWait(hit.x + 0.5, hit.y, hit.z + 0.5, timeoutMs);
    }

    public ActionResult stop() {
        bot.movement.stop();
        bot.mining.stop();
        bot.jobs.cancel();
        return ActionResult.ok("Stopped.");
    }

    /** Turns the head toward a point without moving. */
    public void lookAt(double x, double y, double z) {
        bot.movement.faceTowards(x, y, z);
    }

    // --------------------------------------------------------------- mining --

    /** Breaks one block. Starts a worker and returns immediately. */
    public ActionResult dig(int x, int y, int z) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        int state = bot.world.getBlock(x, y, z);
        if (Blocks.isAir(state)) return ActionResult.fail("There's nothing there.");
        String why = Mechanics.whyCannotMine(inventory(), state);
        if (why != null) return ActionResult.fail(why);
        return ActionResult.ok(bot.mining.mine(x, y, z));
    }

    /**
     * Mines a number of a block type, walking to each one. Runs as a job;
     * returns once the job has been started.
     */
    public ActionResult digBlock(String blockName, int count) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        String block = ItemNames.resolveBlock(blockName);
        if (block == null) return ActionResult.fail("I don't know what " + blockName + " is.");
        int state = Blocks.defaultState(block);
        String why = Mechanics.whyCannotMine(inventory(), state);
        if (why != null) return ActionResult.fail(why);
        bot.jobs.start(new MineJob(bot, block, count, 32));
        return ActionResult.ok("Mining " + count + " " + ItemNames.pretty(block) + ".");
    }

    /**
     * Gets an item however it takes: mine it, craft it, or smelt it. This is
     * the one call a script should use when it does not care how the item
     * arrives. Runs as a job.
     */
    public ActionResult collect(String itemName, int count) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        String item = ItemNames.resolveItem(itemName);
        if (item == null) return ActionResult.fail("I don't know what " + itemName + " is.");
        int have = inventory().count(item);
        if (have >= count) return ActionResult.ok("I already have " + have + " " + ItemNames.pretty(item) + ".");
        Recipes.Plan plan = Recipes.plan(item, count, inventory());
        if (!plan.ok()) return ActionResult.fail("I can't get " + ItemNames.pretty(item) + ": " + plan.error);
        bot.jobs.start(new CraftJob(bot, item, count - have));
        return ActionResult.ok("Getting " + count + " " + ItemNames.pretty(item) + ": "
                + Recipes.describe(plan) + ".");
    }

    public ActionResult stopMining() {
        bot.mining.stop();
        return ActionResult.ok("Stopped mining.");
    }

    // ------------------------------------------------------ craft and smelt --

    public ActionResult craft(String itemName, int count) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        String item = ItemNames.resolveItem(itemName);
        if (item == null) return ActionResult.fail("I don't know what " + itemName + " is.");
        Recipes.Plan plan = Recipes.plan(item, count, inventory());
        if (!plan.ok()) return ActionResult.fail("I can't make " + ItemNames.pretty(item) + ": " + plan.error);
        bot.jobs.start(new CraftJob(bot, item, count));
        return ActionResult.ok("Crafting " + count + " " + ItemNames.pretty(item) + " ("
                + Recipes.describe(plan) + ").");
    }

    public ActionResult smelt(String itemName, int count) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        String item = ItemNames.resolveItem(itemName);
        if (item == null) return ActionResult.fail("I don't know what " + itemName + " is.");
        bot.jobs.start(new SmeltJob(bot, item, count));
        return ActionResult.ok("Smelting " + count + " " + ItemNames.pretty(item) + ".");
    }

    // ------------------------------------------------------------ inventory --

    /**
     * Puts an item where it belongs: armour onto the body, everything else into
     * the main hand.
     *
     * <p>Armour uses a shift-click, which is the vanilla "move to the correct
     * slot" operation - the server does the slot choice, so this cannot put a
     * helmet on the legs.
     */
    public ActionResult equip(String itemName) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        String item = ItemNames.resolveItem(itemName);
        if (item == null) return ActionResult.fail("I don't know what " + itemName + " is.");
        Inventory inv = inventory();
        List<Integer> slots = inv.findSlots(item);
        if (slots.isEmpty()) return ActionResult.fail("I don't have a " + ItemNames.pretty(item) + ".");

        Armor.Piece armor = Armor.get(item);
        if (armor != null) {
            if (!bot.interaction.quickMoveVerified(slots.get(0), 1200L)) {
                return ActionResult.fail("Couldn't equip the " + ItemNames.pretty(item) + ".");
            }
            return ActionResult.ok("Equipped " + ItemNames.pretty(item) + ".");
        }

        int hotbar = hotbarSlotOf(item);
        if (hotbar >= 0) {
            bot.interaction.selectSlot(hotbar);
            return ActionResult.ok("Holding " + ItemNames.pretty(item) + ".");
        }
        int target = slots.get(0);
        int held = inv.heldSlot();
        if (!bot.interaction.clickVerified(target, held, Inventory.CLICK_SWAP, 1200L)) {
            bot.interaction.swapWithHotbar(target, held);
        }
        bot.interaction.selectSlot(held);
        return ActionResult.ok("Holding " + ItemNames.pretty(item) + ".");
    }

    /** Equips the best armour the bot owns for every slot it can improve. */
    public ActionResult equipBestArmor() {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        List<Armor.Piece> upgrades = Armor.upgrades(inventory());
        if (upgrades.isEmpty()) return ActionResult.ok("My armour is already the best I have.");
        StringBuilder done = new StringBuilder();
        for (Armor.Piece piece : upgrades) {
            ActionResult r = equip(piece.name);
            if (r.ok) {
                if (done.length() > 0) done.append(", ");
                done.append(ItemNames.pretty(piece.name));
            }
            sleep(120);
        }
        return done.length() == 0
                ? ActionResult.fail("Couldn't equip any armour.")
                : ActionResult.ok("Equipped " + done + ".");
    }

    /** Puts the best weapon in the main hand. */
    public ActionResult equipBestWeapon() {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        Combat.Weapon weapon = Combat.bestWeapon(inventory());
        if (weapon.name.equals("fist")) return ActionResult.fail("I have no weapon.");
        return equip(weapon.name);
    }

    /**
     * Eats the best food available. Blocking for the eating animation.
     *
     * <p>Vanilla will not start the eat animation when the hunger bar is full
     * (unless the food is marked always-edible), and the server ignores the
     * release if the item was never consumed - so both conditions are checked
     * here rather than discovered from a silent failure.
     */
    public ActionResult eat() {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        Inventory inv = inventory();
        if (bot.eating) return ActionResult.fail("I'm already eating.");
        Food.FoodItem food = Food.best(inv);
        if (food == null) {
            // Only golden apples left: worth it when the player explicitly asks
            // to eat, never for the automatic top-up path.
            food = Food.best(inv, true);
        }
        if (food == null) return ActionResult.fail("I have no food.");
        if (!Food.canEat(bot.food, food)) return ActionResult.ok("I'm not hungry.");
        if (bot.food >= 20 && !food.alwaysEat) return ActionResult.ok("I'm not hungry.");

        int slot = Food.bestHotbarSlot(inv, true);
        if (slot < 0) {
            ActionResult moved = equip(food.name);
            if (!moved.ok) return moved;
            slot = Food.bestHotbarSlot(inv, true);
            if (slot < 0) return ActionResult.fail("Couldn't get food into my hand.");
        }

        bot.eating = true;
        try {
            bot.interaction.selectSlot(slot);
            sleep(180);
            bot.interaction.useItem();
            sleep(food.eatTicks() * 50L);
            bot.interaction.releaseUseItem();
            sleep(120);
            return ActionResult.ok("Ate " + ItemNames.pretty(food.name) + ".");
        } finally {
            bot.eating = false;
        }
    }

    /** Drops items from the inventory. Blocking. */
    public ActionResult drop(String itemName, int count) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        String item = ItemNames.resolveItem(itemName);
        if (item == null) return ActionResult.fail("I don't know what " + itemName + " is.");
        Inventory inv = inventory();
        int left = count;
        for (int slot : inv.findSlots(item)) {
            if (left <= 0) break;
            ItemStack stack = inv.get(slot);
            if (stack.isEmpty()) continue;
            if (left >= stack.count) {
                bot.interaction.dropStack(slot);
                left -= stack.count;
            } else {
                for (int i = 0; i < left; i++) bot.interaction.dropOne(slot);
                left = 0;
            }
            sleep(120);
        }
        int dropped = count - Math.max(0, left);
        return dropped > 0
                ? ActionResult.ok("Dropped " + dropped + " " + ItemNames.pretty(item) + ".")
                : ActionResult.fail("I don't have any " + ItemNames.pretty(item) + ".");
    }

    // ----------------------------------------------------------- containers --

    /** Opens a block that has a window (chest, furnace, table, barrel). */
    public ActionResult openContainer(int x, int y, int z) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        if (!bot.interaction.inReach(x, y, z)) {
            ActionResult walk = gotoAndWait(x + 0.5, y, z + 0.5, 8000L);
            if (!walk.ok) return walk;
        }
        String used = bot.interaction.useBlock(x, y, z);
        if (!waitFor(() -> inventory().isOpen(), 2500L)) {
            return ActionResult.fail("Nothing opened. " + used);
        }
        return ActionResult.ok("Opened " + inventory().layout().name + ".");
    }

    /** Shift-clicks matching items from the player inventory into the open container. */
    public ActionResult deposit(String itemName, int count) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        if (!inventory().isOpen()) return ActionResult.fail("No container is open.");
        String item = ItemNames.resolveItem(itemName);
        if (item == null) return ActionResult.fail("I don't know what " + itemName + " is.");
        int before = inventory().count(item);
        int moved = 0;
        for (int slot : inventory().findSlots(item)) {
            if (moved >= count) break;
            ItemStack stack = inventory().get(slot);
            if (stack.isEmpty()) continue;
            if (bot.interaction.quickMoveVerified(slot, 900L)) moved += stack.count;
        }
        int after = inventory().count(item);
        return before - after > 0
                ? ActionResult.ok("Put away " + (before - after) + " " + ItemNames.pretty(item) + ".")
                : ActionResult.fail("Couldn't put anything away.");
    }

    /** Shift-clicks matching items out of the open container. */
    public ActionResult withdraw(String itemName, int count) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        if (!inventory().isOpen()) return ActionResult.fail("No container is open.");
        String item = ItemNames.resolveItem(itemName);
        if (item == null) return ActionResult.fail("I don't know what " + itemName + " is.");
        int moved = 0;
        int containerSlots = inventory().layout().containerSlots;
        for (int slot = 0; slot < containerSlots && moved < count; slot++) {
            ItemStack stack = inventory().get(slot);
            if (stack.isEmpty() || !stack.name().equals(item)) continue;
            if (bot.interaction.quickMoveVerified(slot, 900L)) moved += stack.count;
        }
        return moved > 0
                ? ActionResult.ok("Took " + moved + " " + ItemNames.pretty(item) + ".")
                : ActionResult.fail("There's no " + ItemNames.pretty(item) + " in there.");
    }

    public ActionResult closeContainer() {
        return ActionResult.ok(bot.interaction.closeWindow());
    }

    // -------------------------------------------------------------- combat --

    public ActionResult attack(int entityId) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        Combat.Weapon weapon = Combat.bestWeapon(inventory());
        String held = inventory().held().name();
        if (!weapon.name.equals("fist") && !weapon.name.equals(held)) equip(weapon.name);
        return ActionResult.ok(bot.interaction.attack(entityId));
    }

    public ActionResult defend() {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        equipBestWeapon();
        equipBestArmor();
        return ActionResult.ok(bot.survival.engageNearest());
    }

    /** Attacks the nearest player specifically. */
    public ActionResult attackNearestPlayer() {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        equipBestWeapon();
        return ActionResult.ok(bot.survival.engageNearestPlayer());
    }

    /** Attacks a named player, or the nearest one when the name is blank. */
    public ActionResult attackPlayer(String name) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        equipBestWeapon();
        if (name == null || name.isBlank()) {
            return ActionResult.ok(bot.survival.engageNearestPlayer());
        }
        return ActionResult.ok(bot.survival.engagePlayer(name));
    }

    public ActionResult flee() {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        double x, y, z;
        synchronized (bot.positionLock) {
            x = bot.playerX;
            y = bot.playerY;
            z = bot.playerZ;
        }
        double angle = Math.random() * Math.PI * 2;
        bot.movement.goTo(x + Math.cos(angle) * 20, y, z + Math.sin(angle) * 20);
        return ActionResult.ok("Running away.");
    }

    // ------------------------------------------------------------- building --

    /**
     * Places one block against an existing face.
     *
     * <p>The support block is chosen explicitly, because "place at x y z" is not
     * something the protocol can express: a placement is always "right-click
     * this face". Picking the first solid neighbour also means a placement into
     * mid-air fails here instead of on the server.
     */
    public ActionResult place(String blockName, int x, int y, int z) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        String block = ItemNames.resolveItem(blockName);
        if (block == null || Blocks.defaultState(block) < 0) {
            return ActionResult.fail("I can't place " + blockName + ".");
        }
        if (inventory().count(block) <= 0) return ActionResult.fail("I have no " + ItemNames.pretty(block) + ".");
        int target = bot.world.getBlock(x, y, z);
        if (!Blocks.isAir(target) && !Blocks.passable(target)) {
            return ActionResult.fail("Something is already there.");
        }
        if (!bot.world.isBlockLoaded(x, z)) return ActionResult.fail("That chunk isn't loaded.");

        int[] support = findSupport(x, y, z);
        if (support == null) return ActionResult.fail("Nothing to place against there.");

        if (!bot.interaction.inReach(support[0], support[1], support[2])) {
            ActionResult walk = gotoAndWait(x + 0.5, y, z + 0.5, 8000L);
            if (!walk.ok) return walk;
            support = findSupport(x, y, z);
            if (support == null) return ActionResult.fail("Nothing to place against there.");
        }

        ActionResult held = equip(block);
        if (!held.ok) return held;
        bot.interaction.placeBlock(support[0], support[1], support[2], support[3]);
        boolean placed = waitFor(() -> {
            int now = bot.world.getBlock(x, y, z);
            return !Blocks.isAir(now) && !Blocks.passable(now);
        }, 900L);
        return placed
                ? ActionResult.ok("Placed " + ItemNames.pretty(block) + " at " + x + " " + y + " " + z + ".")
                : ActionResult.fail("The server didn't accept the placement.");
    }

    /**
     * Builds a wall of blocks in front of the bot.
     *
     * @param length blocks along the facing direction
     * @param height blocks up
     */
    public ActionResult wall(String blockName, int length, int height) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        double x, y, z;
        float yaw;
        synchronized (bot.positionLock) {
            x = bot.playerX;
            y = bot.playerY;
            z = bot.playerZ;
            yaw = bot.playerYaw;
        }
        int dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int dz = (int) Math.round(Math.cos(Math.toRadians(yaw)));
        if (dx == 0 && dz == 0) dz = 1;
        int baseX = (int) Math.floor(x) + dx;
        int baseY = (int) Math.floor(y);
        int baseZ = (int) Math.floor(z) + dz;

        int placed = 0;
        for (int up = 0; up < height; up++) {
            for (int step = 0; step < length; step++) {
                int bx = baseX + dx * step;
                int bz = baseZ + dz * step;
                ActionResult r = place(blockName, bx, baseY + up, bz);
                if (r.ok) placed++;
                else if (placed == 0) return r;   // first block failing means the plan is wrong
            }
        }
        return placed > 0
                ? ActionResult.ok("Built " + placed + " blocks.")
                : ActionResult.fail("Couldn't place anything.");
    }

    /** Fills a square floor around the bot. */
    public ActionResult platform(String blockName, int radius) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        double x, y, z;
        synchronized (bot.positionLock) {
            x = bot.playerX;
            y = bot.playerY;
            z = bot.playerZ;
        }
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y) - 1;
        int cz = (int) Math.floor(z);
        int placed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int state = bot.world.getBlock(cx + dx, cy, cz + dz);
                if (!Blocks.isAir(state) && !Blocks.passable(state)) continue;
                if (place(blockName, cx + dx, cy, cz + dz).ok) placed++;
            }
        }
        return placed > 0 ? ActionResult.ok("Laid " + placed + " blocks.")
                : ActionResult.fail("Couldn't lay a floor here.");
    }

    /**
     * A minimal shelter: a 3x3 floor and four walls with a one-block doorway.
     * Enough to survive a night, not a build system.
     */
    public ActionResult shelter(String blockName) {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        double x, y, z;
        synchronized (bot.positionLock) {
            x = bot.playerX;
            y = bot.playerY;
            z = bot.playerZ;
        }
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        int placed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (place(blockName, cx + dx, cy - 1, cz + dz).ok) placed++;
            }
        }
        for (int up = 0; up < 2; up++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (!edge) continue;
                    if (dx == 0 && dz == 1 && up == 0) continue;   // doorway
                    if (place(blockName, cx + dx, cy + up, cz + dz).ok) placed++;
                }
            }
        }
        return placed > 0 ? ActionResult.ok("Built a shelter out of " + placed + " blocks.")
                : ActionResult.fail("Couldn't build here.");
    }

    /** Sleeps in the nearest bed. */
    public ActionResult sleep() {
        if (!ready()) return ActionResult.fail("I'm not in the world yet.");
        BlockScanner.Hit bed = nearestBlock("red_bed", 24);
        if (bed == null) bed = nearestBlock("white_bed", 24);
        if (bed == null) return ActionResult.fail("I don't see a bed nearby.");
        ActionResult walk = gotoAndWait(bed.x + 0.5, bed.y, bed.z + 0.5, 12000L);
        if (!walk.ok) return walk;
        return ActionResult.ok(bot.interaction.useBlock(bed.x, bed.y, bed.z));
    }

    // ---------------------------------------------------------- inspection --

    /** What is at a position, in knowledge terms. */
    public String inspect(int x, int y, int z) {
        if (!bot.world.isBlockLoaded(x, z)) return "That chunk isn't loaded.";
        int state = bot.world.getBlock(x, y, z);
        return Mechanics.describeBlock(state);
    }

    /** One-paragraph self report, used by chat and by the model. */
    public String selfReport() {
        return new SelfModel(bot).toText();
    }

    // --------------------------------------------------------------- helpers --

    private boolean ready() {
        return bot != null && bot.playerLoaded && bot.hasPlayerPosition;
    }

    private int hotbarSlotOf(String item) {
        Inventory inv = inventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.get(36 + i);
            if (!stack.isEmpty() && stack.name().equals(item)) return i;
        }
        return -1;
    }

    private BlockScanner.Hit nearestBlock(String blockName, int radius) {
        String block = ItemNames.resolveBlock(blockName);
        if (block == null) return null;
        int state = Blocks.defaultState(block);
        if (state < 0) return null;
        double px, py, pz;
        synchronized (bot.positionLock) {
            px = bot.playerX;
            py = bot.playerY;
            pz = bot.playerZ;
        }
        return BlockScanner.nearest(bot.world, s -> s == state, px, py, pz, radius);
    }

    /**
     * Picks a solid neighbour to place against, with the face that points from
     * that neighbour into the target cell.
     *
     * @return {x, y, z, face} or null
     */
    private int[] findSupport(int x, int y, int z) {
        int[][] dirs = {{0, -1, 0, 1}, {0, 1, 0, 0}, {0, 0, -1, 3}, {0, 0, 1, 2}, {-1, 0, 0, 5}, {1, 0, 0, 4}};
        for (int[] d : dirs) {
            int sx = x + d[0];
            int sy = y + d[1];
            int sz = z + d[2];
            if (!bot.world.isBlockLoaded(sx, sz)) continue;
            int state = bot.world.getBlock(sx, sy, sz);
            if (Blocks.isAir(state) || Blocks.passable(state) || Blocks.isLiquid(state)) continue;
            return new int[]{sx, sy, sz, d[3]};
        }
        return null;
    }

    private double distanceTo(double x, double y, double z) {
        synchronized (bot.positionLock) {
            double dx = bot.playerX - x;
            double dy = bot.playerY - y;
            double dz = bot.playerZ - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v));
    }
}
