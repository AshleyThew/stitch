package io.github.ashleythew.stitch.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL's conditional compilation, resolved: the lines inside #if, #ifdef and #ifndef blocks that the
 * compiler would skip become blank, and the conditionals go, so a reader sees one program. Shader packs
 * under OptiFine reach Stitch unpreprocessed, often with every stage in one file behind
 * {@code #ifdef VERTEX_SHADER}, and a program can't be woven until it is one program.
 *
 * <p>Comments go too (a comment opened on a skipped line would otherwise lose its start); every other
 * line keeps its place, so the compiler's line numbers still match. #define and #undef stay where they
 * are active, since the compiler still expands them. A condition this can't decide (a function-like
 * macro, an extension it wasn't told about) is an {@link IllegalArgumentException}: better unwoven than wrong.
 */
public final class Preprocessor {

    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(\\w*)\\s*(.*)$");
    private static final Pattern VERSION = Pattern.compile("^\\s*(\\d+)\\s*(\\w*)");
    private static final Pattern DEFINE = Pattern.compile("^(\\w+)(\\()?\\s*(.*)$");

    private Preprocessor() {
    }

    /**
     * The source with its conditionals resolved. Extensions are the GL extensions the driver supports,
     * which GLSL defines as macros of their own names; null if unknown, when a condition on one can't be
     * decided.
     */
    public static String resolve(String source, Set<String> extensions) {
        String[] lines = joinContinuations(stripComments(source));
        Map<String, String> macros = new HashMap<>();
        Set<String> functionMacros = new HashSet<>();
        // each open conditional: whether its enclosing block is live, whether a branch has been taken,
        // whether the current branch is live
        List<boolean[]> stack = new ArrayList<>();
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean live = stack.isEmpty() || stack.get(stack.size() - 1)[2];
            Matcher d = DIRECTIVE.matcher(line);
            if (d.matches()) {
                String name = d.group(1);
                String rest = d.group(2).strip();
                boolean conditional = true;
                switch (name) {
                    case "if" -> push(stack, live, live && evaluate(rest, macros, functionMacros, extensions, i));
                    case "ifdef" -> push(stack, live, live && isDefined(identifier(rest, i), macros, functionMacros, extensions, i));
                    case "ifndef" -> push(stack, live, live && !isDefined(identifier(rest, i), macros, functionMacros, extensions, i));
                    case "elif" -> {
                        boolean[] top = top(stack, "#elif", i);
                        boolean take = top[0] && !top[1] && evaluate(rest, macros, functionMacros, extensions, i);
                        top[2] = take;
                        top[1] |= take;
                    }
                    case "else" -> {
                        boolean[] top = top(stack, "#else", i);
                        top[2] = top[0] && !top[1];
                        top[1] = true;
                    }
                    case "endif" -> {
                        top(stack, "#endif", i);
                        stack.remove(stack.size() - 1);
                    }
                    default -> conditional = false;
                }
                if (conditional) {
                    out.append('\n');
                    continue;
                }
                if (live) {
                    if (name.equals("define")) {
                        Matcher m = DEFINE.matcher(rest);
                        if (m.matches()) {
                            if (m.group(2) != null) {
                                functionMacros.add(m.group(1));
                                macros.remove(m.group(1));
                            } else {
                                macros.put(m.group(1), m.group(3).strip());
                                functionMacros.remove(m.group(1));
                            }
                        }
                    } else if (name.equals("undef")) {
                        macros.remove(rest);
                        functionMacros.remove(rest);
                    } else if (name.equals("version")) {
                        Matcher v = VERSION.matcher(rest);
                        if (v.find()) {
                            macros.put("__VERSION__", v.group(1));
                            String profile = v.group(2).isEmpty() ? "core" : v.group(2);
                            if (profile.equals("core") || profile.equals("compatibility")) {
                                macros.put("GL_" + profile + "_profile", "1");
                            }
                        }
                    }
                }
            }
            if (live) {
                out.append(line);
            }
            out.append('\n');
        }
        if (!stack.isEmpty()) {
            throw new IllegalArgumentException("an #if is never closed with #endif");
        }
        // the source's own last line break, or none
        return source.endsWith("\n") ? out.toString() : out.substring(0, Math.max(0, out.length() - 1));
    }

    private static void push(List<boolean[]> stack, boolean outerLive, boolean taken) {
        stack.add(new boolean[]{outerLive, taken, taken});
    }

    private static boolean[] top(List<boolean[]> stack, String directive, int line) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException(directive + " without an #if, line " + (line + 1));
        }
        return stack.get(stack.size() - 1);
    }

    private static String identifier(String rest, int line) {
        Matcher m = Pattern.compile("^(\\w+)").matcher(rest);
        if (!m.find()) {
            throw new IllegalArgumentException("#ifdef without a name, line " + (line + 1));
        }
        return m.group(1);
    }

    private static boolean isDefined(String name, Map<String, String> macros, Set<String> functionMacros, Set<String> extensions, int line) {
        if (macros.containsKey(name) || functionMacros.contains(name)) {
            return true;
        }
        if (name.startsWith("GL_") && !name.endsWith("_profile") && !name.equals("GL_ES")) {
            if (extensions == null) {
                throw new IllegalArgumentException("can't tell whether the driver has " + name + ", line " + (line + 1));
            }
            return extensions.contains(name);
        }
        return false;
    }

    /** Comments as spaces, line breaks kept, outside string-free GLSL. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    // a line comment runs on past a line break escaped with \
                    if (source.charAt(i) == '\\' && i + 1 < n && source.charAt(i + 1) == '\n') {
                        out.append('\n');
                        i += 2;
                        continue;
                    }
                    i++;
                }
                if (i < n) {
                    out.append('\n');
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                out.append(' ');
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Lines, each continued line joined to the one it ends, and blank lines kept in their place. */
    private static String[] joinContinuations(String source) {
        String[] raw = source.split("\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        StringBuilder joined = null;
        int pending = 0;
        for (String line : raw) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (l.endsWith("\\")) {
                (joined == null ? joined = new StringBuilder() : joined).append(l, 0, l.length() - 1).append(' ');
                pending++;
                continue;
            }
            if (joined != null) {
                lines.add(joined.append(l).toString());
                for (int k = 0; k < pending; k++) {
                    lines.add("");
                }
                joined = null;
                pending = 0;
            } else {
                lines.add(l);
            }
        }
        if (joined != null) {
            lines.add(joined.toString());
        }
        // the source's trailing newline made an empty last element
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() && source.endsWith("\n")) {
            lines.remove(lines.size() - 1);
        }
        return lines.toArray(new String[0]);
    }

    private static boolean evaluate(String expression, Map<String, String> macros, Set<String> functionMacros, Set<String> extensions, int line) {
        try {
            Expression e = new Expression(expand(expression, macros, functionMacros, extensions, line, 0));
            double value = e.ternary();
            if (e.at < e.tokens.size()) {
                throw new IllegalArgumentException("unexpected " + e.tokens.get(e.at));
            }
            return value != 0;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("can't decide #if " + expression + " (line " + (line + 1) + "): " + ex.getMessage());
        }
    }

    private static final Pattern TOKEN = Pattern.compile(
        "\\s*(0[xX][0-9a-fA-F]+[uU]?|\\d+\\.?\\d*(?:[eE][+-]?\\d+)?[uUfF]?|\\.\\d+(?:[eE][+-]?\\d+)?[fF]?|\\w+|&&|\\|\\||<<|>>|<=|>=|==|!=|[-+*/%<>!~&|^()?:])");

    /** The expression's tokens, macros expanded and defined(...) answered, to numbers and operators. */
    private static List<String> expand(String expression, Map<String, String> macros, Set<String> functionMacros, Set<String> extensions, int line, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("macros nest too deep");
        }
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(expression);
        int at = 0;
        List<String> raw = new ArrayList<>();
        while (at < expression.length()) {
            if (!m.find(at) || m.start() != at) {
                if (expression.substring(at).isBlank()) {
                    break;
                }
                throw new IllegalArgumentException("can't read '" + expression.substring(at).strip() + "'");
            }
            raw.add(m.group(1));
            at = m.end();
        }
        for (int i = 0; i < raw.size(); i++) {
            String t = raw.get(i);
            if (t.equals("defined")) {
                String name;
                if (i + 1 < raw.size() && raw.get(i + 1).equals("(")) {
                    if (i + 3 >= raw.size() || !raw.get(i + 3).equals(")")) {
                        throw new IllegalArgumentException("defined( without its )");
                    }
                    name = raw.get(i + 2);
                    i += 3;
                } else if (i + 1 < raw.size()) {
                    name = raw.get(++i);
                } else {
                    throw new IllegalArgumentException("defined without a name");
                }
                tokens.add(isDefined(name, macros, functionMacros, extensions, line) ? "1" : "0");
            } else if (Character.isLetter(t.charAt(0)) || t.charAt(0) == '_') {
                if (functionMacros.contains(t)) {
                    throw new IllegalArgumentException("function-like macro " + t);
                }
                String body = macros.get(t);
                if (body != null) {
                    tokens.addAll(expand(body.isEmpty() ? "" : body, without(macros, t), functionMacros, extensions, line, depth + 1));
                } else if (t.startsWith("GL_") && !t.endsWith("_profile") && !t.equals("GL_ES")) {
                    tokens.add(isDefined(t, macros, functionMacros, extensions, line) ? "1" : "0");
                } else {
                    // as compilers treat it: a name nothing defines is 0
                    tokens.add("0");
                }
            } else {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static Map<String, String> without(Map<String, String> macros, String name) {
        Map<String, String> out = new HashMap<>(macros);
        out.remove(name);
        return out;
    }

    /** A precedence-climbing evaluator over C's preprocessor operators. */
    private static final class Expression {
        final List<String> tokens;
        int at;

        Expression(List<String> tokens) {
            this.tokens = tokens;
        }

        private String peek() {
            return at < tokens.size() ? tokens.get(at) : "";
        }

        private boolean take(String t) {
            if (peek().equals(t)) {
                at++;
                return true;
            }
            return false;
        }

        double ternary() {
            double c = binary(0);
            if (take("?")) {
                double a = ternary();
                if (!take(":")) {
                    throw new IllegalArgumentException("? without :");
                }
                double b = ternary();
                return c != 0 ? a : b;
            }
            return c;
        }

        private static final List<List<String>> LEVELS = List.of(
            List.of("||"), List.of("&&"), List.of("|"), List.of("^"), List.of("&"), List.of("==", "!="),
            List.of("<", ">", "<=", ">="), List.of("<<", ">>"), List.of("+", "-"), List.of("*", "/", "%"));

        double binary(int level) {
            if (level == LEVELS.size()) {
                return unary();
            }
            double left = binary(level + 1);
            while (LEVELS.get(level).contains(peek())) {
                String op = tokens.get(at++);
                double right = binary(level + 1);
                left = apply(op, left, right);
            }
            return left;
        }

        private static double apply(String op, double a, double b) {
            return switch (op) {
                case "||" -> (a != 0 || b != 0) ? 1 : 0;
                case "&&" -> (a != 0 && b != 0) ? 1 : 0;
                case "|" -> (long) a | (long) b;
                case "^" -> (long) a ^ (long) b;
                case "&" -> (long) a & (long) b;
                case "==" -> a == b ? 1 : 0;
                case "!=" -> a != b ? 1 : 0;
                case "<" -> a < b ? 1 : 0;
                case ">" -> a > b ? 1 : 0;
                case "<=" -> a <= b ? 1 : 0;
                case ">=" -> a >= b ? 1 : 0;
                case "<<" -> (long) a << (long) b;
                case ">>" -> (long) a >> (long) b;
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> {
                    if (b == 0) {
                        throw new IllegalArgumentException("division by zero");
                    }
                    yield a == Math.rint(a) && b == Math.rint(b) ? (double) ((long) a / (long) b) : a / b;
                }
                case "%" -> {
                    if (b == 0) {
                        throw new IllegalArgumentException("division by zero");
                    }
                    yield (long) a % (long) b;
                }
                default -> throw new IllegalArgumentException("operator " + op);
            };
        }

        double unary() {
            if (take("!")) {
                return unary() == 0 ? 1 : 0;
            }
            if (take("-")) {
                return -unary();
            }
            if (take("+")) {
                return unary();
            }
            if (take("~")) {
                return ~(long) unary();
            }
            if (take("(")) {
                double v = ternary();
                if (!take(")")) {
                    throw new IllegalArgumentException("( without )");
                }
                return v;
            }
            String t = peek();
            if (t.isEmpty()) {
                throw new IllegalArgumentException("the expression ends early");
            }
            at++;
            String n = t.replaceAll("[uUfF]$", "");
            try {
                if (n.startsWith("0x") || n.startsWith("0X")) {
                    return Long.parseLong(n.substring(2), 16);
                }
                if (n.length() > 1 && n.startsWith("0") && n.chars().allMatch(Character::isDigit)) {
                    return Long.parseLong(n, 8);
                }
                return Double.parseDouble(n);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: " + t);
            }
        }
    }
}
