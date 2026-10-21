package io.github.ashleythew.stitch.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GLSL as tokens, and the few things weaving needs to find in it: top-level declarations, {@code main},
 * input declarations, and calls of a function on a sampler. Not a compiler: it knows GLSL's lexical
 * structure and braces, which is what weaving needs and stays the same across Minecraft's shaders and
 * shader packs.
 */
public final class Glsl {

    public enum Kind { IDENT, NUMBER, PUNCT, SPACE, COMMENT, DIRECTIVE }

    /** A token: its kind and text; a DIRECTIVE is a whole preprocessor line, its newline excluded. */
    public record Token(Kind kind, String text) {
        boolean is(String s) {
            return (kind == Kind.PUNCT || kind == Kind.IDENT) && text.equals(s);
        }

        boolean blank() {
            return kind == Kind.SPACE || kind == Kind.COMMENT;
        }
    }

    private Glsl() {
    }

    /** The text as tokens; joining their texts gives the text back exactly. */
    public static List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        boolean lineStart = true;
        while (i < n) {
            char c = text.charAt(i);
            int start = i;
            if (c == '#' && lineStart) {
                // a preprocessor line, with its backslash continuations
                while (i < n && text.charAt(i) != '\n') {
                    if (text.charAt(i) == '\\' && i + 1 < n && text.charAt(i + 1) == '\n') {
                        i += 2;
                    } else if (text.charAt(i) == '\\' && i + 2 < n && text.charAt(i + 1) == '\r' && text.charAt(i + 2) == '\n') {
                        i += 3;
                    } else {
                        i++;
                    }
                }
                out.add(new Token(Kind.DIRECTIVE, text.substring(start, i)));
                continue;
            }
            if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(text.charAt(i))) {
                    if (text.charAt(i) == '\n') {
                        lineStart = true;
                    }
                    i++;
                }
                out.add(new Token(Kind.SPACE, text.substring(start, i)));
                continue;
            }
            lineStart = false;
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
                out.add(new Token(Kind.COMMENT, text.substring(start, i)));
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.add(new Token(Kind.COMMENT, text.substring(start, i)));
            } else if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, text.substring(start, i)));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.'
                    || ((text.charAt(i) == '+' || text.charAt(i) == '-') && (text.charAt(i - 1) == 'e' || text.charAt(i - 1) == 'E')
                    && !text.substring(start, i).startsWith("0x")))) {
                    i++;
                }
                out.add(new Token(Kind.NUMBER, text.substring(start, i)));
            } else {
                i++;
                out.add(new Token(Kind.PUNCT, text.substring(start, i)));
            }
        }
        return out;
    }

    public static String join(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token t : tokens) {
            out.append(t.text());
        }
        return out.toString();
    }

    /** The next token from i that isn't space or a comment, or -1. */
    static int next(List<Token> tokens, int i) {
        for (int j = i; j < tokens.size(); j++) {
            if (!tokens.get(j).blank()) {
                return j;
            }
        }
        return -1;
    }

    /** The previous token before i that isn't space or a comment, or -1. */
    static int previous(List<Token> tokens, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (!tokens.get(j).blank()) {
                return j;
            }
        }
        return -1;
    }

    /** The index of the bracket closing the one at open ( ( [ { ), or -1. */
    static int closing(List<Token> tokens, int open) {
        String o = tokens.get(open).text();
        String c = o.equals("(") ? ")" : o.equals("[") ? "]" : "}";
        int depth = 0;
        for (int j = open; j < tokens.size(); j++) {
            Token t = tokens.get(j);
            if (t.is(o)) {
                depth++;
            } else if (t.is(c) && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    /**
     * A top-level statement: a declaration ending in ';', or a function definition or struct whose body
     * is [bodyOpen, bodyClose]. Indexes into the token list.
     */
    record Statement(int start, int end, int bodyOpen, int bodyClose) {
    }

    /** The top-level statements, preprocessor lines skipped. */
    static List<Statement> statements(List<Token> tokens) {
        List<Statement> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            int start = next(tokens, i);
            if (start < 0) {
                break;
            }
            if (tokens.get(start).kind() == Kind.DIRECTIVE) {
                i = start + 1;
                continue;
            }
            int j = start;
            int parens = 0;
            int end = -1;
            int bodyOpen = -1;
            int bodyClose = -1;
            boolean parameters = false;
            while (j < tokens.size()) {
                Token t = tokens.get(j);
                if (t.kind() == Kind.DIRECTIVE) {
                    j++;
                    continue;
                }
                if (t.is("(")) {
                    parens++;
                    parameters = true;
                } else if (t.is(")")) {
                    parens--;
                } else if (t.is("{") && parens == 0) {
                    bodyOpen = j;
                    bodyClose = closing(tokens, j);
                    if (bodyClose < 0) {
                        throw new IllegalArgumentException("a '{' isn't closed");
                    }
                    int after = next(tokens, bodyClose + 1);
                    // a function ends with its '}'; a struct or interface block with ';', after any instance name
                    if (parameters) {
                        end = bodyClose;
                    } else if (after >= 0 && tokens.get(after).is(";")) {
                        end = after;
                    } else if (after >= 0 && tokens.get(after).kind() == Kind.IDENT) {
                        int semi = after;
                        while (semi < tokens.size() && !tokens.get(semi).is(";")) {
                            semi++;
                        }
                        end = semi;
                    } else {
                        end = bodyClose;
                    }
                    break;
                } else if (t.is(";") && parens == 0) {
                    end = j;
                    break;
                }
                j++;
            }
            if (end < 0) {
                break;
            }
            out.add(new Statement(start, end, bodyOpen, bodyClose));
            i = end + 1;
        }
        return out;
    }

    private static final Set<String> QUALIFIERS = Set.of("const", "uniform", "in", "out", "inout", "attribute", "varying",
        "flat", "smooth", "noperspective", "centroid", "highp", "mediump", "lowp", "precision", "layout", "invariant", "struct");

    /**
     * The names a source declares at its top level: functions, variables, constants, structs and macros
     * (#define). These are the names a stitch's prefix renames.
     */
    public static Set<String> declared(List<Token> tokens) {
        Set<String> names = new LinkedHashSet<>();
        for (Token t : tokens) {
            if (t.kind() == Kind.DIRECTIVE) {
                String[] words = t.text().substring(1).trim().split("[\\s(]+");
                if (words.length > 1 && words[0].equals("define")) {
                    names.add(words[1]);
                }
            }
        }
        for (Statement s : statements(tokens)) {
            int first = next(tokens, s.start());
            if (tokens.get(first).is("struct")) {
                int name = next(tokens, first + 1);
                if (name >= 0 && tokens.get(name).kind() == Kind.IDENT) {
                    names.add(tokens.get(name).text());
                }
                continue;
            }
            int limit = s.bodyOpen() >= 0 ? s.bodyOpen() : s.end();
            // a function: the name before its parameter list
            for (int j = s.start(); j < limit; j++) {
                if (tokens.get(j).is("(")) {
                    int name = previous(tokens, j);
                    if (s.bodyOpen() >= 0 || isFunctionPrototype(tokens, s.start(), j)) {
                        names.add(tokens.get(name).text());
                        limit = -1;
                    }
                    break;
                }
                if (tokens.get(j).is("=") || tokens.get(j).is("[")) {
                    break;
                }
            }
            if (limit < 0 || s.bodyOpen() >= 0) {
                continue;
            }
            // variables: each declarator's name, at bracket depth 0
            int depth = 0;
            int lastIdent = -1;
            boolean named = false;
            for (int j = s.start(); j <= s.end(); j++) {
                Token t = tokens.get(j);
                if (t.is("(") || t.is("[")) {
                    if (depth == 0 && !named && lastIdent >= 0) {
                        names.add(tokens.get(lastIdent).text());
                        named = true;
                    }
                    depth++;
                } else if (t.is(")") || t.is("]")) {
                    depth--;
                } else if (depth == 0 && (t.is("=") || t.is(",") || t.is(";"))) {
                    if (!named && lastIdent >= 0 && !QUALIFIERS.contains(tokens.get(lastIdent).text())) {
                        names.add(tokens.get(lastIdent).text());
                    }
                    named = t.is("=");
                    if (t.is(",")) {
                        named = false;
                    }
                } else if (depth == 0 && t.kind() == Kind.IDENT) {
                    lastIdent = j;
                }
            }
        }
        return names;
    }

    private static boolean isFunctionPrototype(List<Token> tokens, int start, int paren) {
        // "type name(" with nothing but a type and qualifiers before the name
        int count = 0;
        for (int j = start; j < paren; j++) {
            if (tokens.get(j).kind() == Kind.IDENT) {
                count++;
            } else if (!tokens.get(j).blank()) {
                return false;
            }
        }
        return count >= 2;
    }

    /** Every identifier token in the map renamed; directives are renamed word by word too. */
    public static void rename(List<Token> tokens, Map<String, String> names) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() == Kind.IDENT && names.containsKey(t.text())) {
                tokens.set(i, new Token(Kind.IDENT, names.get(t.text())));
            } else if (t.kind() == Kind.DIRECTIVE) {
                tokens.set(i, new Token(Kind.DIRECTIVE, renameWords(t.text(), names)));
            }
        }
    }

    private static String renameWords(String line, Map<String, String> names) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                    i++;
                }
                String word = line.substring(s, i);
                out.append(names.getOrDefault(word, word));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** main's body: the indexes of its '{' and '}', or null if the source has no main. */
    public static int[] main(List<Token> tokens) {
        for (Statement s : statements(tokens)) {
            if (s.bodyOpen() < 0) {
                continue;
            }
            for (int j = s.start(); j < s.bodyOpen(); j++) {
                if (tokens.get(j).is("main")) {
                    int open = next(tokens, j + 1);
                    if (open >= 0 && tokens.get(open).is("(")) {
                        return new int[] {s.bodyOpen(), s.bodyClose()};
                    }
                }
            }
        }
        return null;
    }

    /** Whether the statement declares a shader input ("in" or "attribute" at its top level). */
    static boolean isInput(List<Token> tokens, Statement s) {
        if (s.bodyOpen() >= 0) {
            return false;
        }
        int depth = 0;
        for (int j = s.start(); j <= s.end(); j++) {
            Token t = tokens.get(j);
            if (t.is("(")) {
                depth++;
            } else if (t.is(")")) {
                depth--;
            } else if (depth == 0 && (t.is("in") || t.is("attribute"))) {
                return true;
            }
        }
        return false;
    }

    /** The token ranges of the program's input declarations, to leave their names alone. */
    static List<int[]> inputs(List<Token> tokens) {
        List<int[]> out = new ArrayList<>();
        for (Statement s : statements(tokens)) {
            if (isInput(tokens, s)) {
                out.add(new int[] {s.start(), s.end()});
            }
        }
        return out;
    }

    /** A call of a function on a sampler: the function name's index, its '(' and ')', and the sampler's comma. */
    public record Call(int name, int open, int comma, int close) {
    }

    /** Every call of one of the functions whose first argument is exactly the sampler. */
    public static List<Call> calls(List<Token> tokens, Set<String> functions, String sampler) {
        List<Call> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() != Kind.IDENT || !functions.contains(t.text())) {
                continue;
            }
            int open = next(tokens, i + 1);
            if (open < 0 || !tokens.get(open).is("(")) {
                continue;
            }
            int close = closing(tokens, open);
            if (close < 0) {
                continue;
            }
            int arg = next(tokens, open + 1);
            if (arg < 0 || !tokens.get(arg).is(sampler)) {
                continue;
            }
            int comma = next(tokens, arg + 1);
            if (comma < 0 || !tokens.get(comma).is(",")) {
                continue;
            }
            out.add(new Call(i, open, comma, close));
        }
        return out;
    }

    /** Whether the source declares a uniform of this name. */
    public static boolean declaresUniform(List<Token> tokens, String name) {
        for (Statement s : statements(tokens)) {
            boolean uniform = false;
            for (int j = s.start(); j <= s.end(); j++) {
                Token t = tokens.get(j);
                if (t.is("uniform")) {
                    uniform = true;
                } else if (uniform && t.is(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The highest layout location a source's in/out variables use, or -1 if none has one. */
    public static int highestLocation(List<Token> tokens) {
        int highest = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).is("location")) {
                continue;
            }
            int eq = next(tokens, i + 1);
            int value = eq < 0 ? -1 : next(tokens, eq + 1);
            if (value < 0 || !tokens.get(eq).is("=") || tokens.get(value).kind() != Kind.NUMBER) {
                continue;
            }
            // only varyings count (not uniforms or samplers): look for in/out before the ';'
            int j = value;
            boolean varying = false;
            while (j < tokens.size() && !tokens.get(j).is(";")) {
                if (tokens.get(j).is("in") || tokens.get(j).is("out")) {
                    varying = true;
                }
                j++;
            }
            if (varying) {
                highest = Math.max(highest, Integer.parseInt(tokens.get(value).text()));
            }
        }
        return highest;
    }
}
