package mcbot.dev;

import mcbot.api.Reasoner;
import mcbot.bot.MinecraftBot;
import mcbot.knowledge.Armor;
import mcbot.knowledge.Combat;
import mcbot.knowledge.Food;
import mcbot.knowledge.Loot;
import mcbot.knowledge.Mechanics;
import mcbot.knowledge.Mobs;
import mcbot.registry.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Prints what the bot knows, and a few answers it gives from that knowledge.
 *
 * <pre>
 *   java -cp sfus.jar mcbot.dev.KnowledgeDump
 * </pre>
 *
 * <p>Useful as a smoke test after regenerating the data tables: if the counts
 * are wrong or a sample answer is empty, the tables did not load.
 */
public final class KnowledgeDump {
    private KnowledgeDump() {}

    public static void main(String[] args) {
        System.out.println("== coverage ==");
        System.out.println(Mechanics.summary());
        System.out.println();

        System.out.println("== drops ==");
        for (String block : new String[]{"stone", "grass_block", "diamond_ore", "iron_ore",
                "oak_leaves", "short_grass", "gravel", "oak_log"}) {
            System.out.printf("  %-14s -> %s%n", block, Loot.describeBlock(block));
        }
        System.out.println();

        System.out.println("== food, best first ==");
        List<Food.FoodItem> foods = new ArrayList<>();
        for (String name : Food.all()) foods.add(Food.get(name));
        foods.sort(Comparator.comparingDouble((Food.FoodItem f) -> -f.value()));
        for (int i = 0; i < Math.min(5, foods.size()); i++) {
            System.out.println("  " + foods.get(i));
        }
        System.out.println();

        System.out.println("== armour ==");
        for (String name : new String[]{"leather_chestplate", "iron_chestplate",
                "diamond_chestplate", "netherite_chestplate"}) {
            Armor.Piece piece = Armor.get(name);
            if (piece != null) System.out.println("  " + piece);
        }
        System.out.println();

        System.out.println("== weapons ==");
        for (String name : new String[]{"wooden_sword", "iron_sword", "diamond_sword",
                "netherite_axe"}) {
            System.out.println("  " + Combat.describe(name));
        }
        System.out.println();

        System.out.println("== hostile mobs, hardest hitters ==");
        List<Mobs.Mob> hostiles = Mobs.hostiles();
        for (int i = 0; i < Math.min(6, hostiles.size()); i++) {
            System.out.println("  " + hostiles.get(i));
        }
        System.out.println();

        System.out.println("== block facts ==");
        for (String block : new String[]{"obsidian", "stone", "oak_stairs", "lava"}) {
            int state = Blocks.defaultState(block);
            if (state >= 0) System.out.println("  " + Mechanics.describeBlock(state));
        }
        System.out.println();

        System.out.println("== reasoning (offline bot, empty inventory) ==");
        MinecraftBot bot = new MinecraftBot("127.0.0.1", 25565, "KnowledgeDump", null);
        Reasoner reasoner = new Reasoner(bot);
        for (String question : new String[]{
                "where do I get iron",
                "where do I get rotten flesh",
                "what is obsidian",
                "what is a zombie",
                "what can you make",
                "what do you know",
                "what should you do"}) {
            System.out.println("  Q: " + question);
            System.out.println("  A: " + reasoner.answer(question));
        }
    }
}
