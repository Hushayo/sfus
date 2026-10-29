package mcbot.knowledge;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared loader for the generated knowledge tables.
 *
 * Every table under {@code /mcbot/data} is tab-separated with a {@code #}
 * header line. They are classpath resources, not files on disk: the build
 * copies them into the jar, which is why running {@code javac} alone produces a
 * bot that knows nothing.
 */
final class Tables {
    private Tables() {}

    static List<String[]> read(String resource) {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = Tables.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.out.println("[knowledge] missing resource " + resource);
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line.split("\t", -1));
            }
        } catch (Exception e) {
            System.out.println("[knowledge] " + resource + " load failed: " + e);
        }
        return out;
    }

    static String strip(String s) {
        if (s == null) return "";
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    static String col(String[] row, int i, String fallback) {
        return i < row.length && !row[i].isEmpty() ? row[i] : fallback;
    }

    static int intCol(String[] row, int i, int fallback) {
        try {
            return i < row.length ? Integer.parseInt(row[i].trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static float floatCol(String[] row, int i, float fallback) {
        try {
            return i < row.length ? Float.parseFloat(row[i].trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
