package com.aircontrol;

/** Small deterministic arithmetic evaluator exposed as an agent tool, not a phrase matcher. */
public final class NovaCalculator {
    private NovaCalculator() { }

    public static String calculate(String expression) {
        if (expression == null) return "";
        String s = expression.replace("×", "*").replace("÷", "/").trim();
        if (s.isEmpty() || !s.matches("[0-9+\\-*/(). ]+")) return "";
        try {
            double value = new Parser(s).parse();
            if (Double.isNaN(value) || Double.isInfinite(value)) return "";
            if (Math.rint(value) == value) return Long.toString((long) value);
            return String.format(java.util.Locale.US, "%.10f", value)
                    .replaceAll("0+$", "").replaceAll("\\.$", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final class Parser {
        private final String s;
        private int pos = -1;
        private int ch;
        Parser(String value) { s = value; }
        private void next() { ch = ++pos < s.length() ? s.charAt(pos) : -1; }
        private boolean eat(int expected) {
            while (ch == ' ') next();
            if (ch == expected) { next(); return true; }
            return false;
        }
        double parse() {
            next();
            double value = expression();
            while (ch == ' ') next();
            if (pos < s.length()) throw new IllegalArgumentException();
            return value;
        }
        private double expression() {
            double x = term();
            for (;;) {
                if (eat('+')) x += term();
                else if (eat('-')) x -= term();
                else return x;
            }
        }
        private double term() {
            double x = factor();
            for (;;) {
                if (eat('*')) x *= factor();
                else if (eat('/')) x /= factor();
                else return x;
            }
        }
        private double factor() {
            if (eat('+')) return factor();
            if (eat('-')) return -factor();
            int start = pos;
            if (eat('(')) {
                double x = expression();
                if (!eat(')')) throw new IllegalArgumentException();
                return x;
            }
            while ((ch >= '0' && ch <= '9') || ch == '.') next();
            if (start == pos) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
