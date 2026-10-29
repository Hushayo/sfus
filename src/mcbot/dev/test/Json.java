package mcbot.dev.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON parser, used to validate the strings the bot sends.
 *
 * <p>The tool schema is a 5 KB hand-concatenated Java string. One missing brace
 * and every chat request fails at the server with a 400 the bot never sees -
 * which is exactly the kind of bug that is invisible until someone talks to it.
 * Parsing the string in a test turns that into a red line on the build.
 */
final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.ws();
        if (json.pos != text.length()) {
            throw new IllegalArgumentException("trailing data at " + json.pos);
        }
        return value;
    }

    private Object value() {
        ws();
        if (pos >= text.length()) throw new IllegalArgumentException("unexpected end");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            if (peek() != ':') throw new IllegalArgumentException("expected ':' at " + pos);
            pos++;
            out.put(key, value());
            ws();
            char c = peek();
            pos++;
            if (c == '}') return out;
            if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at " + pos);
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        ws();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(value());
            ws();
            char c = peek();
            pos++;
            if (c == ']') return out;
            if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at " + pos);
        }
    }

    private String string() {
        if (peek() != '"') throw new IllegalArgumentException("expected string at " + pos);
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw new IllegalArgumentException("unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = text.charAt(pos++);
            switch (esc) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw new IllegalArgumentException("bad escape \\" + esc);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        if (start == pos) throw new IllegalArgumentException("expected value at " + start);
        return Double.parseDouble(text.substring(start, pos));
    }

    private void expect(String word) {
        if (!text.startsWith(word, pos)) throw new IllegalArgumentException("expected " + word);
        pos += word.length();
    }

    private char peek() {
        if (pos >= text.length()) throw new IllegalArgumentException("unexpected end");
        return text.charAt(pos);
    }

    private void ws() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }
}
