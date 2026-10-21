package io.github.ashleythew.stitch.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the core needs no libraries: objects become ordered maps, arrays
 * lists, numbers doubles, and the rest strings, booleans and null.
 */
public final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** The value the text holds; fails on anything that isn't one JSON value. */
    public static Object parse(String text) {
        Json json = new Json(text);
        json.space();
        Object value = json.value();
        json.space();
        if (json.at != text.length()) {
            throw json.error("text after the value");
        }
        return value;
    }

    private Object value() {
        if (at >= text.length()) {
            throw error("a value was expected");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return map;
        }
        while (true) {
            space();
            if (peek() != '"') {
                throw error("a key was expected");
            }
            String key = string();
            space();
            expect(':');
            space();
            map.put(key, value());
            space();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return list;
        }
        while (true) {
            space();
            list.add(value());
            space();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        at++;
        while (true) {
            if (at >= text.length()) {
                throw error("the string isn't closed");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error("the string isn't closed");
            }
            char e = text.charAt(at++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (at + 4 > text.length()) {
                        throw error("a \\u escape needs four digits");
                    }
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> out.append(e);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("a value was expected");
        }
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("not a number: " + text.substring(start, at));
        }
    }

    private void word(String word) {
        if (!text.startsWith(word, at)) {
            throw error("a value was expected");
        }
        at += word.length();
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : 0;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("'" + c + "' was expected");
        }
        at++;
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(at, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException("JSON line " + line + ": " + message);
    }

    /** The value as JSON, objects and arrays over several lines with this indent. */
    public static String write(Object value, String indent) {
        StringBuilder out = new StringBuilder();
        write(out, value, indent, "");
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, String indent, String at) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                out.append(at).append(indent);
                quote(out, e.getKey());
                out.append(": ");
                write(out, e.getValue(), indent, at + indent);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            out.append(at).append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                out.append(at).append(indent);
                write(out, list.get(i), indent, at + indent);
                out.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            out.append(at).append(']');
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            out.append(d.longValue());
        } else {
            out.append(value);
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
