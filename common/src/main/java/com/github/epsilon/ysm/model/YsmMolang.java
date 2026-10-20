package com.github.epsilon.ysm.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 精简 Molang 求值器（为 YSM 关键帧表达式而写）。
 *
 * <p>支持：数字/字符串/布尔、四则与取余、比较、逻辑、三元 {@code ?:}、括号、
 * 函数调用、变量与赋值（{@code variable.x = ...}）、以及 {@code math.*} 常用函数。
 * 未知变量/函数返回 0，解析失败返回 {@code null}（调用方回退）。
 *
 * <p>它不追求完整 Molang 语义，只覆盖 YSM 模型里常见的关键帧/骨骼表达式
 * （眨眼 {@code query.is_close_eyes}、头发物理 {@code ysm.first_order/second_order} 等）。
 */
public final class YsmMolang {

    /** 求值上下文：提供变量读取、变量写入、以及非 math 函数的分发（如 ysm 物理）。 */
    public interface Context {
        /** 读取变量（如 {@code query.anim_time}、{@code variable.x}、{@code query.is_close_eyes}）。未知返回 0。 */
        float variable(String name);

        /** 写变量（{@code variable.x = ...}）；默认可忽略。 */
        default void setVariable(String name, Object value) {
        }

        /**
         * 处理函数调用。返回 {@code true} 表示已处理（结果写入 {@code out[0]}）。
         * {@code args} 为已求值的参数（数字或字符串）。
         */
        default boolean function(String name, List<Object> args, Object[] out) {
            return false;
        }
    }

    private final Node root;

    private YsmMolang(Node root) {
        this.root = root;
    }

    /** 编译表达式；失败返回 {@code null}。 */
    public static YsmMolang compile(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            List<Token> tokens = tokenize(expression);
            Parser parser = new Parser(tokens);
            Node node = parser.parseExpression();
            if (!parser.atEnd()) {
                return null;
            }
            return new YsmMolang(node);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 求值；出错抛出会被调用方捕获。返回 {@code Float} 或 {@code String}。 */
    public Object eval(Context ctx) {
        return this.root.eval(ctx);
    }

    /** 便捷：求值为 float（字符串按 0 处理，除非是数字串）。 */
    public static float asFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1.0f : 0.0f;
        }
        if (value instanceof String string) {
            try {
                return Float.parseFloat(string.trim());
            } catch (NumberFormatException ignored) {
                return 0.0f;
            }
        }
        return 0.0f;
    }

    // ================= 词法 =================

    private enum Kind { NUM, STR, IDENT, PUNCT, EOF }

    private record Token(Kind kind, String text, double num) {
    }

    private static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                    i++;
                }
                // 科学计数
                if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                    int save = i;
                    i++;
                    if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
                        i++;
                    }
                    if (i < n && Character.isDigit(src.charAt(i))) {
                        while (i < n && Character.isDigit(src.charAt(i))) {
                            i++;
                        }
                    } else {
                        i = save;
                    }
                }
                String text = src.substring(start, i);
                out.add(new Token(Kind.NUM, text, Double.parseDouble(text)));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i))
                        || src.charAt(i) == '_' || src.charAt(i) == '.' || src.charAt(i) == '$')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, src.substring(start, i), 0));
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n && src.charAt(i) != quote) {
                    sb.append(src.charAt(i));
                    i++;
                }
                if (i < n) {
                    i++;
                }
                out.add(new Token(Kind.STR, sb.toString(), 0));
                continue;
            }
            // 运算符（尽量匹配双字符）
            String two = i + 1 < n ? src.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||") || two.equals("??")) {
                out.add(new Token(Kind.PUNCT, two, 0));
                i += 2;
                continue;
            }
            out.add(new Token(Kind.PUNCT, String.valueOf(c), 0));
            i++;
        }
        out.add(new Token(Kind.EOF, "", 0));
        return out;
    }

    // ================= 语法/求值（递归下降 + 运行时节点）=================

    private interface Node {
        Object eval(Context ctx);
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private boolean atEnd() {
            return peek().kind() == Kind.EOF;
        }

        private Token peek() {
            return this.tokens.get(this.pos);
        }

        private Token next() {
            return this.tokens.get(this.pos++);
        }

        private boolean match(String text) {
            if (peek().kind() == Kind.PUNCT && peek().text().equals(text)) {
                this.pos++;
                return true;
            }
            return false;
        }

        private Node parseExpression() {
            Node left = parseTernary();
            // 赋值：variable.x = expr（以及 *= /= += -=）
            if (peek().kind() == Kind.PUNCT && peek().text().endsWith("=")
                    && !peek().text().equals("==") && !peek().text().equals("!=")
                    && !peek().text().equals("<=") && !peek().text().equals(">=")) {
                String op = next().text();
                Node right = parseExpression();
                return ctx -> {
                    Object value = right.eval(ctx);
                    if (left instanceof VarNode var) {
                        Object target = value;
                        if (op.length() == 2) { // += -= *= /=
                            float base = asFloat(var.eval(ctx));
                            float rhs = asFloat(value);
                            char c = op.charAt(0);
                            target = switch (c) {
                                case '+' -> base + rhs;
                                case '-' -> base - rhs;
                                case '*' -> base * rhs;
                                case '/' -> rhs == 0 ? 0f : base / rhs;
                                default -> rhs;
                            };
                        }
                        ctx.setVariable(var.name, target);
                    }
                    return value;
                };
            }
            return left;
        }

        private Node parseTernary() {
            Node condition = parseOr();
            if (match("?")) {
                Node whenTrue = parseTernary();
                if (!match(":")) {
                    throw new IllegalStateException("expected :");
                }
                Node whenFalse = parseTernary();
                return ctx -> asFloat(condition.eval(ctx)) != 0f ? whenTrue.eval(ctx) : whenFalse.eval(ctx);
            }
            return condition;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (match("||")) {
                Node right = parseAnd();
                Node l = left;
                left = ctx -> (asFloat(l.eval(ctx)) != 0f || asFloat(right.eval(ctx)) != 0f) ? 1f : 0f;
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseEquality();
            while (match("&&")) {
                Node right = parseEquality();
                Node l = left;
                left = ctx -> (asFloat(l.eval(ctx)) != 0f && asFloat(right.eval(ctx)) != 0f) ? 1f : 0f;
            }
            return left;
        }

        private Node parseEquality() {
            Node left = parseRelational();
            while (peek().kind() == Kind.PUNCT
                    && (peek().text().equals("==") || peek().text().equals("!="))) {
                String op = next().text();
                Node right = parseRelational();
                Node l = left;
                left = ctx -> compare(l, right, ctx, op);
            }
            return left;
        }

        private Node parseRelational() {
            Node left = parseAdditive();
            while (peek().kind() == Kind.PUNCT
                    && (peek().text().equals("<") || peek().text().equals(">")
                    || peek().text().equals("<=") || peek().text().equals(">="))) {
                String op = next().text();
                Node right = parseAdditive();
                Node l = left;
                left = ctx -> compare(l, right, ctx, op);
            }
            return left;
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (peek().kind() == Kind.PUNCT
                    && (peek().text().equals("+") || peek().text().equals("-"))) {
                String op = next().text();
                Node right = parseMultiplicative();
                Node l = left;
                left = ctx -> op.equals("+")
                        ? asFloat(l.eval(ctx)) + asFloat(right.eval(ctx))
                        : asFloat(l.eval(ctx)) - asFloat(right.eval(ctx));
            }
            return left;
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (peek().kind() == Kind.PUNCT
                    && (peek().text().equals("*") || peek().text().equals("/") || peek().text().equals("%"))) {
                String op = next().text();
                Node right = parseUnary();
                Node l = left;
                left = ctx -> {
                    float a = asFloat(l.eval(ctx));
                    float b = asFloat(right.eval(ctx));
                    return switch (op) {
                        case "*" -> a * b;
                        case "/" -> b == 0f ? 0f : a / b;
                        default -> b == 0f ? 0f : a % b;
                    };
                };
            }
            return left;
        }

        private Node parseUnary() {
            if (match("!")) {
                Node operand = parseUnary();
                return ctx -> asFloat(operand.eval(ctx)) == 0f ? 1f : 0f;
            }
            if (match("-")) {
                Node operand = parseUnary();
                return ctx -> -asFloat(operand.eval(ctx));
            }
            if (match("+")) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            Token token = peek();
            if (token.kind() == Kind.NUM) {
                next();
                double value = token.num();
                return ctx -> (float) value;
            }
            if (token.kind() == Kind.STR) {
                next();
                String value = token.text();
                return ctx -> value;
            }
            if (match("(")) {
                Node inner = parseExpression();
                if (!match(")")) {
                    throw new IllegalStateException("expected )");
                }
                return inner;
            }
            if (token.kind() == Kind.IDENT) {
                next();
                String name = token.text();
                if (match("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!match(")")) {
                        do {
                            args.add(parseExpression());
                        } while (match(","));
                        if (!match(")")) {
                            throw new IllegalStateException("expected )");
                        }
                    }
                    return new CallNode(name, args);
                }
                return new VarNode(name);
            }
            throw new IllegalStateException("unexpected token " + token.text());
        }

        private static Object compare(Node left, Node right, Context ctx, String op) {
            Object a = left.eval(ctx);
            Object b = right.eval(ctx);
            boolean result;
            if (a instanceof String || b instanceof String) {
                String sa = String.valueOf(a);
                String sb = String.valueOf(b);
                result = switch (op) {
                    case "==" -> sa.equals(sb);
                    case "!=" -> !sa.equals(sb);
                    default -> {
                        float fa = asFloat(a);
                        float fb = asFloat(b);
                        yield switch (op) {
                            case "<" -> fa < fb;
                            case ">" -> fa > fb;
                            case "<=" -> fa <= fb;
                            default -> fa >= fb;
                        };
                    }
                };
            } else {
                float fa = asFloat(a);
                float fb = asFloat(b);
                result = switch (op) {
                    case "==" -> fa == fb;
                    case "!=" -> fa != fb;
                    case "<" -> fa < fb;
                    case ">" -> fa > fb;
                    case "<=" -> fa <= fb;
                    default -> fa >= fb;
                };
            }
            return result ? 1f : 0f;
        }
    }

    private static final class VarNode implements Node {
        private final String name;

        private VarNode(String name) {
            this.name = name;
        }

        @Override
        public Object eval(Context ctx) {
            return ctx.variable(this.name);
        }
    }

    private static final class CallNode implements Node {
        private final String name;
        private final List<Node> args;

        private CallNode(String name, List<Node> args) {
            this.name = name;
            this.args = args;
        }

        @Override
        public Object eval(Context ctx) {
            List<Object> values = new ArrayList<>(this.args.size());
            for (Node arg : this.args) {
                values.add(arg.eval(ctx));
            }
            String fn = this.name.toLowerCase(Locale.ROOT);
            // math.* 内部处理
            if (fn.startsWith("math.")) {
                return math(fn.substring(5), values);
            }
            // 交给上下文（ysm 物理函数等）
            Object[] out = new Object[1];
            if (ctx.function(this.name, values, out)) {
                return out[0] == null ? 0f : out[0];
            }
            if (ctx.function(fn, values, out)) {
                return out[0] == null ? 0f : out[0];
            }
            return 0f;
        }

        private static Object math(String fn, List<Object> values) {
            float a = values.isEmpty() ? 0f : asFloat(values.get(0));
            float b = values.size() > 1 ? asFloat(values.get(1)) : 0f;
            float c = values.size() > 2 ? asFloat(values.get(2)) : 0f;
            return switch (fn) {
                case "sin" -> (float) Math.sin(a);
                case "cos" -> (float) Math.cos(a);
                case "tan" -> (float) Math.tan(a);
                case "asin" -> (float) Math.asin(a);
                case "acos" -> (float) Math.acos(a);
                case "atan" -> (float) Math.atan(a);
                case "atan2" -> (float) Math.atan2(a, b);
                case "abs" -> Math.abs(a);
                case "floor" -> (float) Math.floor(a);
                case "ceil" -> (float) Math.ceil(a);
                case "round" -> (float) Math.round(a);
                case "trunc" -> (float) (a < 0 ? Math.ceil(a) : Math.floor(a));
                case "sqrt" -> (float) Math.sqrt(Math.max(0f, a));
                case "pow" -> (float) Math.pow(a, b);
                case "exp" -> (float) Math.exp(a);
                case "ln" -> (float) Math.log(Math.max(1e-9f, a));
                case "log" -> (float) (Math.log(Math.max(1e-9f, a)) / Math.log(10));
                case "mod" -> b == 0f ? 0f : a % b;
                case "min" -> Math.min(a, b);
                case "max" -> Math.max(a, b);
                case "clamp" -> Math.max(b, Math.min(c, a));
                case "lerp" -> a + (b - a) * c;
                case "sign" -> (float) Math.signum(a);
                case "degrees" -> (float) Math.toDegrees(a);
                case "radians" -> (float) Math.toRadians(a);
                case "random" -> (float) Math.random();
                case "pi" -> (float) Math.PI;
                case "hermite_blend", "ease_in_out_sine" -> a; // 近似
                default -> 0f;
            };
        }
    }
}
