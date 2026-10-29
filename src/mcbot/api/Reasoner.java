package mcbot.api;

import mcbot.bot.MinecraftBot;
import mcbot.crafting.Recipe;
import mcbot.crafting.Recipes;
import mcbot.inventory.Inventory;
import mcbot.knowledge.Armor;
import mcbot.knowledge.Combat;
import mcbot.knowledge.Food;
import mcbot.knowledge.Loot;
import mcbot.knowledge.Mechanics;
import mcbot.knowledge.Mobs;
import mcbot.registry.Blocks;
import mcbot.registry.ItemNames;
import mcbot.registry.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic reasoning about the game state.
 *
 * <p>This is the part that answers "why can't you mine that?", "what can you
 * make right now?", "where do I get iron?" and "what should you do next?"
 * without calling a language model. Every answer is computed from the knowledge
 * tables and the live inventory, so it is reproducible, testable, and cannot
 * hallucinate a recipe that does not exist.
 *
 * <p>The chat AI uses this as a tool; a player talking to the bot hits it
 * directly for the questions it can answer exactly.
 */
public final class Reasoner {
    private final MinecraftBot bot;
    private final Actions actions;

    public Reasoner(MinecraftBot bot) {
        this.bot = bot;
        this.actions = new Actions(bot);
    }

    /**
     * True when the text is one of the questions this class answers exactly.
     *
     * <p>Exposed statically so the chat AI can stand down: two answers to the
     * same question, one deterministic and one generated, is worse than either
     * alone.
     */
    public static boolean isKnowledgeQuestion(String question) {
        if (question == null) return false;
        String q = question.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return false;
        return containsAny(q, "what can you make", "what can you craft", "what can i make",
                "what can we make", "craftable", "what are you able to make",
                "where do i get", "where can i get", "where do you get",
                "how do i get", "how do you get", "where to find", "where do you find",
                "what should you do", "what should i do", "what now", "advice",
                "what do you need", "what next",
                "what do you know", "how much do you know", "knowledge", "what can you do",
                "are you okay", "how are you", "status", "how's it going", "hows it going",
                "danger", "threat", "safe here", "is it safe", "what's nearby", "whats nearby",
                "what is ", "what's ", "whats ", "tell me about",
                "explain ", "what does ", "how much damage",
                "what do you have", "inventory", "what are you carrying",
                "can you make", "can you craft", "can you build");
    }

    /**
     * Answers a question, or returns null when it is not a question this class
     * can answer exactly. Null means "send it to the model", not "I don't
     * know" - the distinction keeps the cheap path cheap.
     */
    public String answer(String question) {
        if (!isKnowledgeQuestion(question)) return null;
        String q = question.toLowerCase(Locale.ROOT).trim();

        if (containsAny(q, "what can you make", "what can you craft", "what can i make",
                "what can we make", "craftable", "what are you able to make")) {
            return whatCanIMake();
        }
        if (containsAny(q, "where do i get", "where can i get", "where do you get",
                "how do i get", "how do you get", "where to find", "where do you find")) {
            String item = afterAny(q, "where do i get", "where can i get", "where do you get",
                    "how do i get", "how do you get", "where to find", "where do you find");
            return whereDoIGet(item);
        }
        if (containsAny(q, "what should you do", "what should i do", "what now", "advice",
                "what do you need", "what next")) {
            return advice();
        }
        if (containsAny(q, "what do you know", "how much do you know", "knowledge", "what can you do")) {
            return whatDoYouKnow();
        }
        if (containsAny(q, "are you okay", "how are you", "status", "how's it going", "hows it going")) {
            return new SelfModel(bot).toText();
        }
        if (containsAny(q, "danger", "threat", "safe here", "is it safe", "what's nearby", "whats nearby")) {
            return threats();
        }
        if (containsAny(q, "what is ", "what's ", "whats ", "tell me about",
                "explain ", "what does ", "how much damage")) {
            // Longest prefix first: "what is a zombie" must not leave "a zombie".
            String what = afterAny(q, "what is a ", "what is an ", "what is ", "what's a ",
                    "what's an ", "what's ", "whats a ", "whats an ", "whats ",
                    "tell me about", "explain ", "what does ", "how much damage");
            String explained = explain(what);
            if (explained != null) return explained;
        }
        if (containsAny(q, "what do you have", "inventory", "what are you carrying")) {
            return new SelfModel(bot).toText();
        }
        if (containsAny(q, "can you make", "can you craft", "can you build")) {
            String item = afterAny(q, "can you make", "can you craft", "can you build");
            return canYouMake(item);
        }
        return null;
    }

    // --------------------------------------------------------------- status --

    public String status() {
        return new SelfModel(bot).toText();
    }

    /** The single most useful next action, plus the threat picture. */
    public String advice() {
        Inventory inv = bot.interaction.inventory;
        StringBuilder sb = new StringBuilder();
        sb.append(Mechanics.survivalAdvice(inv, bot.health, bot.food));
        List<String> threats = new SelfModel(bot).threats();
        if (!threats.isEmpty()) {
            sb.append(" Threats: ").append(String.join("; ", threats)).append('.');
        }
        Combat.Weapon weapon = Combat.bestWeapon(inv);
        if (weapon.name.equals("fist")) {
            sb.append(" I have no weapon.");
        }
        if (Armor.wornDefense(inv) == 0 && !Armor.upgrades(inv).isEmpty()) {
            sb.append(" I own armour but am not wearing it.");
        }
        return sb.toString();
    }

    public String threats() {
        List<String> threats = new SelfModel(bot).threats();
        if (threats.isEmpty()) return "Nothing hostile nearby.";
        return "Hostile: " + String.join("; ", threats) + ".";
    }

    // -------------------------------------------------------------- crafting --

    /**
     * Recipes that can be made from the current inventory, deduplicated by
     * output and capped at 12 so the answer stays a sentence rather than a
     * dump.
     */
    public String whatCanIMake() {
        Inventory inv = bot.interaction.inventory;
        Set<String> outputs = new LinkedHashSet<>();
        for (Recipe r : Recipes.all()) {
            if (!r.isCrafting()) continue;
            if (outputs.contains(r.output)) continue;
            if (Recipes.canCraftNow(r, inv)) outputs.add(r.output);
            if (outputs.size() >= 12) break;
        }
        if (outputs.isEmpty()) return "Nothing, with what I'm carrying.";
        List<String> pretty = new ArrayList<>();
        for (String o : outputs) pretty.add(ItemNames.pretty(o));
        return "I can make: " + String.join(", ", pretty) + ".";
    }

    /** "Can you make a diamond pickaxe?" with the plan or the missing step. */
    public String canYouMake(String itemText) {
        String item = ItemNames.resolveItem(itemText);
        if (item == null) return "I don't know what " + itemText + " is.";
        Recipes.Plan plan = Recipes.plan(item, 1, bot.interaction.inventory);
        if (!plan.ok()) return "No: " + plan.error + ".";
        if (plan.steps.isEmpty()) return "I already have one.";
        return "Yes: " + Recipes.describe(plan) + ".";
    }

    // ------------------------------------------------------------- knowledge --

    /** Where an item comes from: blocks, mobs, recipes, smelting. */
    public String whereDoIGet(String itemText) {
        String item = ItemNames.resolveItem(itemText);
        if (item == null) return "I don't know what " + itemText + " is.";
        List<String> sources = new ArrayList<>();

        for (String block : Loot.sourcesOf(item)) {
            sources.add("mine " + ItemNames.pretty(block));
            if (sources.size() >= 3) break;
        }
        if (sources.size() < 3) {
            for (String mob : Loot.mobSourcesOf(item)) {
                sources.add("kill " + ItemNames.pretty(mob));
                if (sources.size() >= 3) break;
            }
        }
        if (sources.size() < 3) {
            for (Recipe r : Recipes.forOutput(item)) {
                if (!r.isCrafting()) continue;
                // "9 ingots from an iron block" is true but useless: it plans
                // to already own nine of the thing being asked for.
                List<String> tokens = r.allTokens();
                if (tokens.size() == 1 && r.count >= 4 && tokens.get(0).endsWith("_block")) continue;
                sources.add("craft from " + ItemNames.pretty(tokens.get(0)));
                break;
            }
        }
        if (sources.size() < 3 && Recipes.cookingFor(item) != null) {
            Recipe cook = Recipes.cookingFor(item);
            if (cook != null && !cook.ingredients.isEmpty()) {
                sources.add("smelt " + ItemNames.pretty(cook.ingredients.get(0)));
            }
        }
        if (sources.isEmpty()) return "I don't know a way to get " + ItemNames.pretty(item) + ".";
        return "You get " + ItemNames.pretty(item) + " by: " + String.join(", ", sources) + ".";
    }

    /** Explain a block, item, mob or damage type. Null when none matches. */
    public String explain(String what) {
        String text = what == null ? "" : what.trim();
        if (text.isEmpty()) return null;
        // "a zombie", "an iron pickaxe", "the nether" all mean the noun.
        text = text.replaceFirst("(?i)^(?:a|an|the)\s+", "");
        String norm = ItemNames.normalize(text);

        // Exact names first, and mobs before blocks: "zombie" fuzzily resolves
        // to "zombie_head" as a block, which is a true statement about the
        // wrong thing.
        Mobs.Mob mob = Mobs.get(norm);
        if (mob.health > 0) return Mobs.describe(norm);
        int exactBlock = Blocks.defaultState(norm);
        if (exactBlock >= 0) return Mechanics.describeBlock(exactBlock);
        if (Items.get(norm).id != 0) return Mechanics.describeItem(norm);

        // Then a fuzzy match, which is what "what is a pick" needs.
        String block = ItemNames.resolveBlock(text);
        if (block != null) {
            int state = Blocks.defaultState(block);
            if (state >= 0) return Mechanics.describeBlock(state);
        }
        String item = ItemNames.resolveItem(text);
        if (item != null && Items.get(item).id != 0) return Mechanics.describeItem(item);
        return null;
    }

    /** What this build knows, in numbers - the honest coverage report. */
    public String whatDoYouKnow() {
        return "I know " + Mechanics.summary() + ".";
    }

    // -------------------------------------------------------------- planning --

    /** Plan for an item, for chat and tests. */
    public String plan(String itemText, int count) {
        String item = ItemNames.resolveItem(itemText);
        if (item == null) return "I don't know what " + itemText + " is.";
        Recipes.Plan plan = Recipes.plan(item, count, bot.interaction.inventory);
        return plan.ok() ? Recipes.describe(plan) : "I can't: " + plan.error;
    }

    /** Food decision, with the reasoning spelled out. */
    public String foodAdvice() {
        Inventory inv = bot.interaction.inventory;
        Food.FoodItem best = Food.best(inv);
        if (best == null) return "I have no food.";
        if (!Food.canEat(bot.food, best)) return "I'm full; " + ItemNames.pretty(best.name) + " would be wasted.";
        return "I should eat " + ItemNames.pretty(best.name) + ": " + best.nutrition
                + " food and " + String.format(Locale.ROOT, "%.1f", best.saturation)
                + " saturation, and I'm at " + bot.food + ".";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String afterAny(String haystack, String... prefixes) {
        for (String p : prefixes) {
            int at = haystack.indexOf(p);
            if (at >= 0) {
                String rest = haystack.substring(at + p.length()).trim();
                return rest.replaceAll("[?.!]+$", "").trim();
            }
        }
        return "";
    }
}
