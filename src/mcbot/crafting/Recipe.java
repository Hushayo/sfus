package mcbot.crafting;

import java.util.ArrayList;
import java.util.List;

/**
 * One recipe, as the server's data pack defines it.
 *
 * Loaded from {@code recipes.tsv}, which {@code tools/gen_data.py} writes from
 * the server's own recipe files - so "2 planks vertical makes 4 sticks" is the
 * server's opinion, not a table someone typed in.
 *
 * Ingredients are stored as tokens: either an item name ({@code oak_planks}) or
 * a tag ({@code #planks}). Tags are resolved to a concrete item at plan time,
 * because "any plank" is only useful once you decide which plank.
 */
public final class Recipe {
    public enum Kind { SHAPED, SHAPELESS, COOKING, STONECUTTING, SMITHING }

    public final String type;
    public final Kind kind;
    public final String output;
    public final int count;
    /** Shaped only: rows of ingredient tokens, "" for an empty cell. */
    public final String[][] pattern;
    /** Shapeless/cooking: one token per ingredient. */
    public final List<String> ingredients;

    Recipe(String type, Kind kind, String output, int count,
           String[][] pattern, List<String> ingredients) {
        this.type = type;
        this.kind = kind;
        this.output = output;
        this.count = count;
        this.pattern = pattern;
        this.ingredients = ingredients;
    }

    /** Tokens this recipe consumes, with multiplicity (shaped counts cells). */
    public List<String> allTokens() {
        List<String> out = new ArrayList<>();
        if (kind == Kind.SHAPED) {
            for (String[] row : pattern) {
                for (String cell : row) {
                    if (!cell.isEmpty()) out.add(cell);
                }
            }
        } else {
            out.addAll(ingredients);
        }
        return out;
    }

    /**
     * True when this shaped recipe fits the given 3x3 grid of item names
     * (null = empty). The grid is trimmed of empty rows and columns first, so
     * a 2x2 recipe works anywhere in a 3x3 table.
     */
    public boolean matches(String[][] grid) {
        if (kind == Kind.SHAPELESS) {
            List<String> have = new ArrayList<>();
            for (String[] row : grid) {
                for (String cell : row) {
                    if (cell != null && !cell.isEmpty()) have.add(cell);
                }
            }
            if (have.size() != ingredients.size()) return false;
            List<String> left = new ArrayList<>(have);
            for (String want : ingredients) {
                int hit = -1;
                for (int i = 0; i < left.size(); i++) {
                    if (tokenMatches(want, left.get(i))) { hit = i; break; }
                }
                if (hit < 0) return false;
                left.remove(hit);
            }
            return true;
        }
        if (kind != Kind.SHAPED) return false;

        String[][] trimmedPattern = trim(pattern);
        String[][] trimmedGrid = trim(grid);
        if (trimmedPattern.length != trimmedGrid.length) return false;
        if (trimmedPattern[0].length != trimmedGrid[0].length) return false;
        for (int r = 0; r < trimmedPattern.length; r++) {
            for (int c = 0; c < trimmedPattern[r].length; c++) {
                String want = trimmedPattern[r][c];
                String have = trimmedGrid[r][c];
                if (want.isEmpty()) {
                    if (have != null && !have.isEmpty()) return false;
                } else if (!tokenMatches(want, have)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * True when the token accepts the given item name.
     *
     * Token forms: {@code oak_planks} (one item), {@code #planks} (a tag), and
     * {@code coal|charcoal} (a list of alternatives, which is how vanilla
     * writes a recipe that accepts either).
     */
    public static boolean tokenMatches(String token, String itemName) {
        if (token == null || itemName == null) return false;
        if (token.indexOf('|') >= 0) {
            for (String part : token.split("\\|")) {
                if (tokenMatches(part, itemName)) return true;
            }
            return false;
        }
        if (token.startsWith("#")) {
            return mcbot.registry.Tags.itemIn(itemName, token.substring(1));
        }
        return token.equals(itemName);
    }

    /**
     * Trims empty rows and columns from a grid.
     *
     * Rows may be ragged: the recipe generator drops trailing empty cells, so a
     * one-wide recipe is {@code ["#", "#"]} and a three-wide one is
     * {@code ["###","# #"]}. Treating the missing cells as empty rather than
     * indexing past the end is what makes 1-wide patterns comparable to a 3x3
     * crafting grid.
     */
    private static String[][] trim(String[][] in) {
        int top = -1, bottom = -1, left = Integer.MAX_VALUE, right = -1;
        for (int r = 0; r < in.length; r++) {
            for (int c = 0; c < in[r].length; c++) {
                if (in[r][c] == null || in[r][c].isEmpty()) continue;
                if (top < 0) top = r;
                bottom = r;
                if (c < left) left = c;
                if (c > right) right = c;
            }
        }
        if (top < 0) return new String[0][0];
        String[][] out = new String[bottom - top + 1][right - left + 1];
        for (int r = top; r <= bottom; r++) {
            for (int c = left; c <= right; c++) {
                String v = c < in[r].length ? in[r][c] : null;
                out[r - top][c - left] = v == null ? "" : v;
            }
        }
        return out;
    }

    public boolean isCrafting() {
        return kind == Kind.SHAPED || kind == Kind.SHAPELESS;
    }

    @Override
    public String toString() {
        return type + ": " + String.join(" + ", allTokens()) + " -> " + count + "x " + output;
    }
}
