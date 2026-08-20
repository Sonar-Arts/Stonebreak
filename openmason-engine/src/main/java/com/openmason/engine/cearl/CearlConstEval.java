package com.openmason.engine.cearl;

import com.openmason.engine.cearl.CearlAst.Bin;
import com.openmason.engine.cearl.CearlAst.BoolLit;
import com.openmason.engine.cearl.CearlAst.Expr;
import com.openmason.engine.cearl.CearlAst.FloatLit;
import com.openmason.engine.cearl.CearlAst.Ident;
import com.openmason.engine.cearl.CearlAst.IntLit;
import com.openmason.engine.cearl.CearlAst.SizeLit;
import com.openmason.engine.cearl.CearlAst.Un;

import java.util.Map;

/**
 * Compile-time constant evaluation — used for {@code const} initializers and
 * plan {@code when} guards. Values are {@link Long} (integers and sizes,
 * i64 semantics), {@link Double}, or {@link Boolean}.
 *
 * <p>Identifiers resolve first against previously declared consts, then the
 * host environment the compiler was invoked with (e.g. {@code vram} — the
 * machine's detected VRAM in bytes, 0 when unknown). Unknown names fail with
 * the available names listed.
 */
final class CearlConstEval {

    private final String sourceName;
    private final Map<String, Object> consts;
    private final Map<String, Long> env;

    CearlConstEval(String sourceName, Map<String, Object> consts, Map<String, Long> env) {
        this.sourceName = sourceName;
        this.consts = consts;
        this.env = env;
    }

    Object eval(Expr expr) {
        return switch (expr) {
            case IntLit i -> i.value();
            case FloatLit f -> f.value();
            case BoolLit b -> b.value();
            case SizeLit s -> s.bytes();
            case Ident id -> {
                Object c = consts.get(id.name());
                if (c != null) {
                    yield c;
                }
                Long e = env.get(id.name());
                if (e != null) {
                    yield e;
                }
                throw err(id.line(), "unknown name '" + id.name() + "' in a constant expression"
                    + " — available: consts " + consts.keySet() + ", environment " + env.keySet());
            }
            case Un u -> {
                Object v = eval(u.operand());
                if (u.op().equals("-")) {
                    if (v instanceof Long l) {
                        yield -l;
                    }
                    if (v instanceof Double d) {
                        yield -d;
                    }
                    throw err(u.line(), "unary '-' needs a number");
                }
                if (v instanceof Boolean b) {
                    yield !b;
                }
                throw err(u.line(), "'!' needs a boolean");
            }
            case Bin b -> evalBin(b);
            default -> throw err(expr.line(),
                "only literals, consts, environment names, and arithmetic/comparison"
                    + " operators are allowed in constant expressions");
        };
    }

    boolean evalBool(Expr expr, String what) {
        Object v = eval(expr);
        if (v instanceof Boolean b) {
            return b;
        }
        throw err(expr.line(), "the " + what + " must be a boolean condition"
            + " (got " + describe(v) + ")");
    }

    private Object evalBin(Bin b) {
        // 'a otherwise b': a when it is a non-zero integer, else b. The
        // hardware-fallback operator — '(vram otherwise FLOOR) * 3 / 4'.
        if (b.op().equals("otherwise")) {
            Object l = eval(b.left());
            if (!(l instanceof Long ll)) {
                throw err(b.line(), "'otherwise' picks between integers"
                    + " (the left value wins when non-zero); got " + describe(l));
            }
            if (ll != 0) {
                return ll;
            }
            Object r = eval(b.right());
            if (!(r instanceof Long rl)) {
                throw err(b.line(), "the 'otherwise' fallback must be an integer"
                    + " (got " + describe(r) + ")");
            }
            return rl;
        }
        // Short-circuit logic first.
        if (b.op().equals("&&") || b.op().equals("||")) {
            Object l = eval(b.left());
            if (!(l instanceof Boolean lb)) {
                throw err(b.line(), "'" + b.op() + "' needs boolean operands");
            }
            if (b.op().equals("&&") && !lb) {
                return false;
            }
            if (b.op().equals("||") && lb) {
                return true;
            }
            Object r = eval(b.right());
            if (!(r instanceof Boolean rb)) {
                throw err(b.line(), "'" + b.op() + "' needs boolean operands");
            }
            return rb;
        }

        Object l = eval(b.left());
        Object r = eval(b.right());

        if (b.op().equals("==") || b.op().equals("!=")) {
            boolean eq;
            if (l instanceof Boolean && r instanceof Boolean) {
                eq = l.equals(r);
            } else if (l instanceof Long && r instanceof Long) {
                eq = l.equals(r);
            } else if (isNumber(l) && isNumber(r)) {
                eq = asDouble(l) == asDouble(r);
            } else {
                throw err(b.line(), "cannot compare " + describe(l) + " with " + describe(r));
            }
            return b.op().equals("==") ? eq : !eq;
        }

        if (!isNumber(l) || !isNumber(r)) {
            throw err(b.line(), "'" + b.op() + "' needs numeric operands"
                + " (got " + describe(l) + " and " + describe(r) + ")");
        }

        boolean bothInt = l instanceof Long && r instanceof Long;
        switch (b.op()) {
            case "<" -> {
                return asDouble(l) < asDouble(r);
            }
            case "<=" -> {
                return asDouble(l) <= asDouble(r);
            }
            case ">" -> {
                return asDouble(l) > asDouble(r);
            }
            case ">=" -> {
                return asDouble(l) >= asDouble(r);
            }
            default -> { }
        }
        if (bothInt) {
            long a = (Long) l;
            long c = (Long) r;
            return switch (b.op()) {
                case "+" -> a + c;
                case "-" -> a - c;
                case "*" -> a * c;
                case "/" -> a / nonZero(c, b.line());
                case "%" -> a % nonZero(c, b.line());
                default -> throw err(b.line(), "unsupported operator '" + b.op() + "'");
            };
        }
        double a = asDouble(l);
        double c = asDouble(r);
        return switch (b.op()) {
            case "+" -> a + c;
            case "-" -> a - c;
            case "*" -> a * c;
            case "/" -> a / c;
            case "%" -> a % c;
            default -> throw err(b.line(), "unsupported operator '" + b.op() + "'");
        };
    }

    private long nonZero(long c, int line) {
        if (c == 0) {
            throw err(line, "division by zero in a constant expression");
        }
        return c;
    }

    private static boolean isNumber(Object v) {
        return v instanceof Long || v instanceof Double;
    }

    private static double asDouble(Object v) {
        return v instanceof Long l ? (double) l : (Double) v;
    }

    private static String describe(Object v) {
        if (v instanceof Long) {
            return "an integer";
        }
        if (v instanceof Double) {
            return "a float";
        }
        return "a boolean";
    }

    private CearlException err(int line, String message) {
        return new CearlException(sourceName, line, 0, message);
    }
}
