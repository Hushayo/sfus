package mcbot.modules;

import mcbot.api.Actions;
import mcbot.bot.BotModule;
import mcbot.bot.MinecraftBot;
import mcbot.inventory.Inventory;
import mcbot.jobs.ActionJob;
import mcbot.knowledge.Armor;
import mcbot.knowledge.Combat;
import mcbot.knowledge.Food;

/**
 * Keeps the bot dressed and armed without being asked.
 *
 * <p>A player equips armour once and forgets about it; a bot that picks up a
 * diamond chestplate and leaves it in slot 23 is strictly worse in every fight
 * than one that puts it on. This module closes that gap, and it also keeps food
 * and a weapon in the hotbar so eating and fighting never need an inventory
 * click in the middle of combat.
 *
 * <p>It only acts when the bot is genuinely idle - no job, no path, no mining,
 * no open window. Equipping during a mining job would swap the pickaxe out of
 * the hand mid-block.
 */
public class LoadoutModule implements BotModule {
    private static final long CHECK_INTERVAL_MS = 3000L;

    private volatile MinecraftBot bot;
    private volatile long nextCheckMs = 0L;
    private volatile String lastAction = "none";

    @Override
    public void install(MinecraftBot bot) {
        this.bot = bot;
    }

    @Override
    public void onTick(MinecraftBot bot) {
        if (bot == null || bot.dead || !bot.playerLoaded || !bot.hasPlayerPosition) return;
        long now = System.currentTimeMillis();
        if (now < nextCheckMs) return;
        nextCheckMs = now + CHECK_INTERVAL_MS;

        Inventory inv = bot.interaction.inventory;
        if (inv.isOpen()) return;
        if (bot.jobs.busy() || bot.mining.isMining() || bot.movement.isMoving()) return;
        if (bot.survival.inCombat() || bot.eating) return;

        // 1. Armour first: it is the difference between surviving a fight and
        //    not, and it costs one shift-click per piece.
        var upgrades = Armor.upgrades(inv);
        if (!upgrades.isEmpty()) {
            lastAction = "equipping " + upgrades.get(0).name;
            bot.jobs.start(new ActionJob(bot, "equip armour", () -> new Actions(bot).equipBestArmor()));
            return;
        }

        // 2. Food in the hotbar, so eating is one slot select instead of a
        //    click sequence with a container state id in the middle of it.
        if (Food.bestHotbarSlot(inv) < 0) {
            Food.FoodItem food = Food.best(inv);
            if (food != null) {
                lastAction = "moving " + food.name + " to the hotbar";
                bot.jobs.start(new ActionJob(bot, "ready food", () -> new Actions(bot).equip(food.name)));
                return;
            }
        }

        // 3. A weapon in the hotbar. The pickaxe is usually already there, and
        //    swapping it out when nothing is hostile would break mining, so
        //    this only runs when a weapon exists but is not reachable.
        Combat.Weapon weapon = Combat.bestWeapon(inv);
        if (!weapon.name.equals("fist") && hotbarSlotOf(inv, weapon.name) < 0) {
            lastAction = "moving " + weapon.name + " to the hotbar";
            bot.jobs.start(new ActionJob(bot, "ready weapon", () -> new Actions(bot).equip(weapon.name)));
        }
    }

    private static int hotbarSlotOf(Inventory inv, String item) {
        for (int i = 0; i < 9; i++) {
            var stack = inv.get(36 + i);
            if (!stack.isEmpty() && stack.name().equals(item)) return i;
        }
        return -1;
    }

    /** What the module last did, for status. */
    public String lastAction() {
        return lastAction;
    }
}
