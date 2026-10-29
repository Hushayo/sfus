package mcbot.dev.test;

import mcbot.ai.OllamaClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chat tool schema is a hand-built JSON string; these tests keep it valid.
 *
 * <p>Nothing else in the project can check it: a malformed schema is only
 * discovered when the model endpoint rejects the request, and the bot then
 * answers nothing at all. Parsing it here makes that a build failure instead of
 * a silent one.
 */
public final class AiTests {
    private AiTests() {}

    @SuppressWarnings("unchecked")
    public static void run() {
        TestRunner.suite("ai tools");

        TestRunner.test("the tool schema is valid JSON", () -> {
            Object parsed = Json.parse(OllamaClient.TOOLS);
            TestRunner.check("schema is an array", parsed instanceof List);
        });

        TestRunner.test("every tool is a well-formed function", () -> {
            List<Object> tools = (List<Object>) Json.parse(OllamaClient.TOOLS);
            TestRunner.atLeast("tools", 15, tools.size());
            Set<String> names = new HashSet<>();
            for (Object entry : tools) {
                Map<String, Object> tool = (Map<String, Object>) entry;
                TestRunner.eq("tool type", "function", tool.get("type"));
                Map<String, Object> fn = (Map<String, Object>) tool.get("function");
                TestRunner.notNull("function", fn);
                String name = (String) fn.get("name");
                TestRunner.notNull("name", name);
                TestRunner.check("unique name " + name, names.add(name));
                String description = (String) fn.get("description");
                TestRunner.check("description for " + name,
                        description != null && description.length() > 20);
                Map<String, Object> params = (Map<String, Object>) fn.get("parameters");
                TestRunner.notNull("parameters for " + name, params);
                TestRunner.eq("parameters type for " + name, "object", params.get("type"));
            }
            System.out.println("        " + names.size() + " tools: " + String.join(", ",
                    names.stream().sorted().toList()));
        });

        TestRunner.test("the mechanics tools are present", () -> {
            List<Object> tools = (List<Object>) Json.parse(OllamaClient.TOOLS);
            Set<String> names = new HashSet<>();
            for (Object entry : tools) {
                Map<String, Object> tool = (Map<String, Object>) entry;
                names.add((String) ((Map<String, Object>) tool.get("function")).get("name"));
            }
            for (String required : new String[]{"eat_food", "equip_best_armour", "defend",
                    "build", "self_status", "game_knowledge", "gather_item", "craft_item",
                    "smelt_item", "attack_nearest", "list_inventory"}) {
                TestRunner.check("missing tool " + required, names.contains(required));
            }
        });

        TestRunner.test("required parameters point at declared properties", () -> {
            List<Object> tools = (List<Object>) Json.parse(OllamaClient.TOOLS);
            for (Object entry : tools) {
                Map<String, Object> tool = (Map<String, Object>) entry;
                Map<String, Object> fn = (Map<String, Object>) tool.get("function");
                String name = (String) fn.get("name");
                Map<String, Object> params = (Map<String, Object>) fn.get("parameters");
                Map<String, Object> props = (Map<String, Object>) params.get("properties");
                Object required = params.get("required");
                if (!(required instanceof List)) continue;
                for (Object field : (List<Object>) required) {
                    TestRunner.check("required field " + field + " of " + name
                                    + " is not declared",
                            props != null && props.containsKey(field));
                }
            }
        });

        TestRunner.test("the parser itself rejects broken JSON", () -> {
            try {
                Json.parse("{\"a\": 1,}");
                TestRunner.fail("trailing comma should fail");
            } catch (RuntimeException expected) {
                // correct
            }
            try {
                Json.parse("{\"a\": }");
                TestRunner.fail("missing value should fail");
            } catch (RuntimeException expected) {
                // correct
            }
            Json.parse("{\"a\": [1, 2, {\"b\": \"c\\n\"}]}");
        });
    }
}
