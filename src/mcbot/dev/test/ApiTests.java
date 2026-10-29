package mcbot.dev.test;

import mcbot.api.Actions;
import mcbot.api.Reasoner;
import mcbot.api.SelfModel;
import mcbot.bot.MinecraftBot;
import mcbot.inventory.Inventory;
import mcbot.knowledge.Mechanics;

/**
 * Tests for the action and reasoning layer, without a server.
 *
 * <p>A bot that is not connected still has to answer questions correctly and
 * refuse actions cleanly. That is what is checked here: the failure messages,
 * the knowledge answers, and the self model - all of which are used in chat and
 * as the language model's prompt.
 */
public final class ApiTests {
    private ApiTests() {}

    private static MinecraftBot offlineBot() {
        return new MinecraftBot("127.0.0.1", 25565, "Tester", null);
    }

    public static void run() {
        TestRunner.suite("api: reconnect");

        TestRunner.test("backoff doubles and caps at 30 s", () -> {
            mcbot.bot.ReconnectPolicy policy = new mcbot.bot.ReconnectPolicy(2_000L, 30_000L);
            TestRunner.eq("first", 2_000L, policy.nextDelayMs());
            TestRunner.eq("second", 4_000L, policy.nextDelayMs());
            TestRunner.eq("third", 8_000L, policy.nextDelayMs());
            TestRunner.eq("fourth", 16_000L, policy.nextDelayMs());
            TestRunner.eq("fifth", 30_000L, policy.nextDelayMs());
            TestRunner.eq("capped", 30_000L, policy.nextDelayMs());
            TestRunner.eq("attempts", 6, policy.attempts());
        });

        TestRunner.test("a healthy session resets the backoff", () -> {
            mcbot.bot.ReconnectPolicy policy = new mcbot.bot.ReconnectPolicy(2_000L, 30_000L);
            policy.nextDelayMs();
            policy.nextDelayMs();
            policy.reset();
            TestRunner.eq("starts over", 2_000L, policy.nextDelayMs());
        });

        TestRunner.test("reconnect is enabled by default", () -> {
            TestRunner.check("default on", mcbot.bot.ReconnectPolicy.enabled());
        });

        TestRunner.suite("api: chat and pvp");

        TestRunner.test("chat keeps unicode instead of turning it into '?'", () -> {
            String cleaned = mcbot.bot.MinecraftBot.cleanForChat("Sure \u2014 back later \uD83D\uDEBF");
            TestRunner.check("em dash kept: " + cleaned, cleaned.contains("\u2014"));
            TestRunner.check("emoji kept: " + cleaned, cleaned.contains("\uD83D\uDEBF"));
            TestRunner.check("no stray question marks", !cleaned.contains("?"));
        });

        TestRunner.test("chat strips control characters and collapses whitespace", () -> {
            TestRunner.eq("controls", "hello world",
                    mcbot.bot.MinecraftBot.cleanForChat("hello\u0000\n\t  world"));
        });

        TestRunner.test("long chat is cut at a word boundary", () -> {
            String longText = "word ".repeat(80);
            String cleaned = mcbot.bot.MinecraftBot.cleanForChat(longText);
            TestRunner.check("fits the packet limit", cleaned.length() <= 256);
            TestRunner.check("does not end mid-word", cleaned.endsWith("word"));
        });

        TestRunner.test("pvp is on by default", () -> {
            TestRunner.check("pvp enabled", mcbot.modules.SurvivalModule.pvpEnabled());
        });

        TestRunner.test("attack commands are recognised", () -> {
            TestRunner.check("bare defend",
                    mcbot.modules.ChatCommands.isCommand("defend"));
            TestRunner.check("attack a player",
                    mcbot.modules.ChatCommands.isCommand("attack ManalaHush"));
            TestRunner.check("kill a mob",
                    mcbot.modules.ChatCommands.isCommand("kill the zombie"));
        });

        TestRunner.suite("api: player list");

        TestRunner.test("player_info_update maps a UUID to a name", () -> {
            mcbot.modules.PlayerListModule list = new mcbot.modules.PlayerListModule();
            java.util.UUID id = java.util.UUID.randomUUID();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            body.write(1 | 8 | 16);                       // add, listed, latency
            mcbot.net.PacketWriter.varInt(body, 1);
            mcbot.net.PacketWriter.uuid(body, id);
            mcbot.net.PacketWriter.string(body, "ManalaHush");
            mcbot.net.PacketWriter.varInt(body, 0);       // no properties
            body.write(1);                                // listed
            mcbot.net.PacketWriter.varInt(body, 42);      // latency

            list.onPlayPacket(mcbot.net.PacketIds.PLAYER_INFO_UPDATE, body.toByteArray(), null);

            TestRunner.eq("name by uuid", "ManalaHush", list.nameOf(id));
            TestRunner.eq("uuid by name", id, list.uuidOf("manalahush"));
            TestRunner.check("known", list.known("MANALAHUSH"));
            TestRunner.eq("size", 1, list.size());
        });

        TestRunner.test("an unparseable mask is skipped, not half-read", () -> {
            mcbot.modules.PlayerListModule list = new mcbot.modules.PlayerListModule();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            body.write(1 | 32);                           // add + display name (NBT)
            mcbot.net.PacketWriter.varInt(body, 1);
            mcbot.net.PacketWriter.uuid(body, java.util.UUID.randomUUID());
            mcbot.net.PacketWriter.string(body, "Someone");
            mcbot.net.PacketWriter.varInt(body, 0);
            // no display-name payload on purpose: the parser must not read it
            list.onPlayPacket(mcbot.net.PacketIds.PLAYER_INFO_UPDATE, body.toByteArray(), null);
            TestRunner.eq("nothing invented", 0, list.size());
        });

        TestRunner.suite("api: actions");

        TestRunner.test("actions refuse cleanly when not in the world", () -> {
            MinecraftBot bot = offlineBot();
            Actions actions = new Actions(bot);
            TestRunner.check("goto fails", !actions.gotoPosition(0, 64, 0).ok);
            TestRunner.check("dig fails", !actions.dig(0, 64, 0).ok);
            TestRunner.check("craft fails", !actions.craft("stick", 1).ok);
            TestRunner.check("eat fails", !actions.eat().ok);
            TestRunner.check("place fails", !actions.place("stone", 0, 64, 0).ok);
            TestRunner.contains("reason", actions.gotoPosition(0, 64, 0).message, "world");
        });

        TestRunner.test("equip reports a missing item instead of clicking", () -> {
            MinecraftBot bot = offlineBot();
            Actions actions = new Actions(bot);
            TestRunner.check("not in world", !actions.equip("diamond_sword").ok);
        });

        TestRunner.suite("api: self knowledge");

        TestRunner.test("the self model names the bot and its state", () -> {
            MinecraftBot bot = offlineBot();
            String text = new SelfModel(bot).toText();
            TestRunner.contains("name", text, "Tester");
            TestRunner.contains("health", text, "health");
            TestRunner.contains("gear", text, "gear");
            TestRunner.contains("next action", text, "next");
        });

        TestRunner.test("capabilities list the mechanics honestly", () -> {
            var caps = new SelfModel(offlineBot()).capabilities();
            TestRunner.atLeast("capability lines", 8, caps.size());
            String all = String.join("\n", caps);
            TestRunner.contains("survival", all, "survival");
            TestRunner.contains("defence", all, "defence");
            TestRunner.contains("crafting", all, "crafting");
            TestRunner.contains("building", all, "building");
            TestRunner.contains("inventory", all, "inventory");
            TestRunner.contains("reasoning", all, "reasoning");
        });

        TestRunner.test("the offline inventory is empty, not broken", () -> {
            MinecraftBot bot = offlineBot();
            SelfModel model = new SelfModel(bot);
            TestRunner.eq("inventory", "empty", model.inventorySummary());
            TestRunner.eq("weapon", "fist", model.weapon());
            TestRunner.eq("defence", 0, model.defense());
            TestRunner.check("no food", model.food() == null);
        });

        TestRunner.suite("api: reasoning");

        TestRunner.test("knowledge questions are recognised", () -> {
            TestRunner.check("where", Reasoner.isKnowledgeQuestion("where do I get iron"));
            TestRunner.check("craftable", Reasoner.isKnowledgeQuestion("what can you make"));
            TestRunner.check("explain", Reasoner.isKnowledgeQuestion("tell me about obsidian"));
            TestRunner.check("status", Reasoner.isKnowledgeQuestion("how are you"));
            TestRunner.check("advice", Reasoner.isKnowledgeQuestion("what should you do"));
            TestRunner.check("plain chat is not a question",
                    !Reasoner.isKnowledgeQuestion("hello there"));
            TestRunner.check("empty", !Reasoner.isKnowledgeQuestion("   "));
        });

        TestRunner.test("where-do-I-get answers from the loot tables", () -> {
            Reasoner reasoner = new Reasoner(offlineBot());
            String diamond = reasoner.whereDoIGet("diamond");
            TestRunner.contains("diamond ore", diamond, "diamond ore");
            String iron = reasoner.whereDoIGet("raw iron");
            TestRunner.contains("iron ore", iron, "iron ore");
            String rotten = reasoner.whereDoIGet("rotten flesh");
            TestRunner.contains("zombie", rotten, "zombie");
        });

        TestRunner.test("explain answers with real mechanics", () -> {
            Reasoner reasoner = new Reasoner(offlineBot());
            TestRunner.contains("stone", reasoner.explain("stone"), "cobblestone");
            TestRunner.contains("sword", reasoner.explain("diamond_sword"), "7");
            TestRunner.contains("zombie", reasoner.explain("zombie"), "hostile");
            TestRunner.contains("obsidian", reasoner.explain("obsidian"), "pickaxe");
        });

        TestRunner.test("articles and plurals do not confuse the lookup", () -> {
            Reasoner reasoner = new Reasoner(offlineBot());
            TestRunner.contains("a zombie", reasoner.answer("what is a zombie"), "hostile");
            TestRunner.contains("an iron pickaxe", reasoner.answer("what is an iron pickaxe"), "6");
            TestRunner.contains("the obsidian", reasoner.answer("what's obsidian"), "diamond");
        });

        TestRunner.test("what-can-I-make respects the inventory", () -> {
            MinecraftBot bot = offlineBot();
            TestFixtures.fillInto(bot.interaction.inventory, "cobblestone:3", "stick:2");
            Reasoner reasoner = new Reasoner(bot);
            String canMake = reasoner.whatCanIMake();
            TestRunner.contains("stone pickaxe", canMake, "stone pickaxe");
            String plan = reasoner.canYouMake("stone_pickaxe");
            TestRunner.check("yes: " + plan, plan.startsWith("Yes"));
        });

        TestRunner.test("an impossible request is refused with a reason", () -> {
            MinecraftBot bot = offlineBot();
            Reasoner reasoner = new Reasoner(bot);
            String answer = reasoner.canYouMake("elytra");
            TestRunner.check("refused: " + answer, answer.startsWith("No"));
            TestRunner.contains("reason", answer.toLowerCase(java.util.Locale.ROOT), "elytra");
        });

        TestRunner.test("advice prioritises starving over everything else", () -> {
            Inventory inv = TestFixtures.packed("cooked_beef:3");
            String advice = Mechanics.survivalAdvice(inv, 20, 4);
            TestRunner.contains("food", advice.toLowerCase(java.util.Locale.ROOT), "eat");
        });

        TestRunner.test("advice sends a hurt bot to its food", () -> {
            Inventory inv = TestFixtures.packed("cooked_beef:3");
            String advice = Mechanics.survivalAdvice(inv, 5, 12);
            TestRunner.contains("health", advice.toLowerCase(java.util.Locale.ROOT), "health");
        });

        TestRunner.test("advice is calm when nothing is wrong", () -> {
            Inventory inv = TestFixtures.packed("cooked_beef:3");
            TestRunner.eq("nothing urgent", "Nothing urgent.",
                    Mechanics.survivalAdvice(inv, 20, 20));
        });

        TestRunner.test("the knowledge summary is complete", () -> {
            String summary = new Reasoner(offlineBot()).whatDoYouKnow();
            TestRunner.contains("states", summary, "states");
            TestRunner.contains("mobs", summary, "mobs");
            TestRunner.contains("loot", summary, "loot");
        });
    }
}
