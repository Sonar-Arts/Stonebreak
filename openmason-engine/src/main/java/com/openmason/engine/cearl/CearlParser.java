package com.openmason.engine.cearl;

import com.openmason.engine.cearl.CearlAst.ArenaDecl;
import com.openmason.engine.cearl.CearlAst.Assign;
import com.openmason.engine.cearl.CearlAst.Bin;
import com.openmason.engine.cearl.CearlAst.Block;
import com.openmason.engine.cearl.CearlAst.BoolLit;
import com.openmason.engine.cearl.CearlAst.Break;
import com.openmason.engine.cearl.CearlAst.Call;
import com.openmason.engine.cearl.CearlAst.ConstDecl;
import com.openmason.engine.cearl.CearlAst.Continue;
import com.openmason.engine.cearl.CearlAst.DeviceDecl;
import com.openmason.engine.cearl.CearlAst.Dir;
import com.openmason.engine.cearl.CearlAst.Expr;
import com.openmason.engine.cearl.CearlAst.ExprStmt;
import com.openmason.engine.cearl.CearlAst.Field;
import com.openmason.engine.cearl.CearlAst.FloatLit;
import com.openmason.engine.cearl.CearlAst.FnDecl;
import com.openmason.engine.cearl.CearlAst.ForRange;
import com.openmason.engine.cearl.CearlAst.Ident;
import com.openmason.engine.cearl.CearlAst.If;
import com.openmason.engine.cearl.CearlAst.Index;
import com.openmason.engine.cearl.CearlAst.IntLit;
import com.openmason.engine.cearl.CearlAst.KernelDecl;
import com.openmason.engine.cearl.CearlAst.Let;
import com.openmason.engine.cearl.CearlAst.Member;
import com.openmason.engine.cearl.CearlAst.Param;
import com.openmason.engine.cearl.CearlAst.PlanDecl;
import com.openmason.engine.cearl.CearlAst.PoolDecl;
import com.openmason.engine.cearl.CearlAst.PressureRule;
import com.openmason.engine.cearl.CearlAst.Program;
import com.openmason.engine.cearl.CearlAst.Return;
import com.openmason.engine.cearl.CearlAst.SizeLit;
import com.openmason.engine.cearl.CearlAst.Stmt;
import com.openmason.engine.cearl.CearlAst.StructDecl;
import com.openmason.engine.cearl.CearlAst.TypeRef;
import com.openmason.engine.cearl.CearlAst.Un;
import com.openmason.engine.cearl.CearlAst.WhenDecl;
import com.openmason.engine.cearl.CearlAst.While;
import com.openmason.engine.cearl.CearlLexer.Token;
import com.openmason.engine.cearl.CearlLexer.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser for CEARL's native grammar: newline-terminated
 * statements, {@code header ... end} blocks, worded logic. Summary:
 *
 * <pre>
 * program := { form | craft | kernel | pin | plan }
 * form    := "form" IDENT NL { field NL | craft } "end" NL
 * field   := IDENT ":" type
 * craft   := "craft" IDENT "(" params ")" [ "->" type ] NL body "end" NL
 * kernel  := "kernel" IDENT [ "(" INT ")" ] NL { take NL } body "end" NL
 * take    := "take" IDENT ":" type [ "in" | "out" | "inout" ]
 * pin     := "pin" IDENT [ ":" type ] "=" expr NL
 * stmt    := fix/flux | assign | call | if/elif/else | while | for..in | give | break | continue
 * plan    := "plan" IDENT NL { device | pool | when | on } "end" NL
 * pool    := "pool" IDENT [ "from" IDENT ] NL { attr NL | arena } "end" NL
 * </pre>
 *
 * <p>Methods declare no receiver — a form's fields are directly visible
 * inside its crafts. Every syntax error is a {@link CearlException} naming
 * the found token, what was expected, and where. Retired spellings
 * ({@code let}, {@code fn}, {@code struct}, {@code return}, ...) are
 * recognized and answered with their CEARL replacement.
 */
final class CearlParser {

    private static final Set<String> KEYWORDS = Set.of(
        "form", "craft", "kernel", "pin", "fix", "flux", "give", "take", "shared", "plan",
        "if", "elif", "else", "while", "for", "in", "break", "continue",
        "true", "false", "and", "or", "not", "otherwise", "end", "from", "self");

    /** Retired spellings → the CEARL word, for teaching errors. */
    private static final Map<String, String> LEGACY = Map.of(
        "let", "fix", "var", "flux", "fn", "craft", "struct", "form",
        "const", "pin", "return", "give", "func", "craft", "def", "craft");

    private static final Set<String> STORAGE_MODES = Set.of("static", "persistent");
    private static final Set<String> GROW_MODES = Set.of("copy", "sparse");

    private final List<Token> tokens;
    private final String sourceName;
    private int idx;

    private CearlParser(List<Token> tokens, String sourceName) {
        this.tokens = tokens;
        this.sourceName = sourceName;
    }

    static Program parse(String source, String sourceName) {
        return new CearlParser(CearlLexer.lex(source, sourceName), sourceName).parseProgram();
    }

    /** Returns the byte multiplier for a size unit name, or -1 if not a unit. */
    static long unitMultiplier(String unit) {
        return switch (unit) {
            case "B" -> 1L;
            case "KiB", "KB" -> 1L << 10;
            case "MiB", "MB" -> 1L << 20;
            case "GiB", "GB" -> 1L << 30;
            default -> -1L;
        };
    }

    // ─── Top level ────────────────────────────────────────────────────────

    private Program parseProgram() {
        List<StructDecl> forms = new ArrayList<>();
        List<FnDecl> crafts = new ArrayList<>();
        List<KernelDecl> kernels = new ArrayList<>();
        List<ConstDecl> pins = new ArrayList<>();
        PlanDecl plan = null;

        skipNewlines();
        while (peek().type() != Type.EOF) {
            Token t = peek();
            String kw = t.type() == Type.IDENT ? t.text() : "";
            switch (kw) {
                case "form" -> forms.add(parseForm());
                case "craft" -> crafts.add(parseCraft(null));
                case "kernel" -> kernels.add(parseKernel());
                case "pin" -> pins.add(parsePin());
                case "plan" -> {
                    if (plan != null) {
                        throw err(t, "a CEARL program may declare at most one plan"
                            + " (the first is at line " + plan.line() + ")");
                    }
                    plan = parsePlan();
                }
                default -> {
                    teachLegacy(t);
                    throw err(t, "expected a top-level declaration"
                        + " (form, craft, kernel, pin, or plan) but found " + t.describe());
                }
            }
            skipNewlines();
        }
        return new Program(forms, crafts, kernels, pins, plan);
    }

    private ConstDecl parsePin() {
        Token kw = advance();
        String name = expectFreshIdent("pin name");
        TypeRef declared = null;
        if (peek().type() == Type.COLON) {
            advance();
            declared = parseType();
        }
        expect(Type.ASSIGN, "'=' after the pin name");
        Expr value = parseExpr();
        expectNewline("the pin value");
        return new ConstDecl(name, declared, value, kw.line());
    }

    private StructDecl parseForm() {
        Token kw = advance();
        String name = expectFreshIdent("form name");
        expectNewline("the form header");
        List<Field> fields = new ArrayList<>();
        List<FnDecl> crafts = new ArrayList<>();
        skipNewlines();
        while (!atWord("end")) {
            Token t = peek();
            requireNotEof(t, kw, "form '" + name + "'");
            if (t.type() == Type.IDENT && t.text().equals("craft")) {
                crafts.add(parseCraft(name));
            } else if (t.type() == Type.IDENT) {
                teachLegacy(t);
                String fieldName = expectFreshIdent("field name");
                expect(Type.COLON, "':' between the field name and its type");
                TypeRef type = parseType();
                if (type.array()) {
                    throw err(t, "form fields cannot be arrays — runtime-sized arrays"
                        + " exist only as kernel take buffers");
                }
                expectNewline("the field");
                fields.add(new Field(fieldName, type, t.line()));
            } else {
                throw err(t, "expected a field ('name: type') or a method ('craft ...')"
                    + " inside form '" + name + "' but found " + t.describe());
            }
            skipNewlines();
        }
        advance(); // end
        expectNewline("'end'");
        return new StructDecl(name, fields, crafts, kw.line());
    }

    private FnDecl parseCraft(String owner) {
        Token kw = advance();
        String name = expectFreshIdent("craft name");
        expect(Type.LPAREN, "'(' to open the parameter list");
        List<Param> params = new ArrayList<>();
        while (peek().type() != Type.RPAREN) {
            Token nameTok = peek();
            String pname = expectFreshIdent("parameter name");
            expect(Type.COLON, "':' between the parameter name and its type");
            TypeRef type = parseType();
            params.add(new Param(pname, type, Dir.IN, nameTok.line()));
            if (peek().type() == Type.COMMA) {
                advance();
            } else {
                break;
            }
        }
        expect(Type.RPAREN, "')' to close the parameter list");
        TypeRef ret = null;
        if (peek().type() == Type.ARROW) {
            advance();
            ret = parseType();
            if (ret.array()) {
                throw err(kw, "crafts cannot return arrays");
            }
        }
        expectNewline("the craft header");
        Block body = parseBody(kw, "craft '" + name + "'", Set.of("end"));
        advance(); // end
        expectNewline("'end'");
        return new FnDecl(name, owner, params, ret, body, kw.line());
    }

    private KernelDecl parseKernel() {
        Token kw = advance();
        String name = expectFreshIdent("kernel name");
        int localSize = 64;
        if (peek().type() == Type.LPAREN) {
            advance();
            Token n = expect(Type.NUMBER, "the kernel's workgroup size, e.g. kernel cull(64)");
            localSize = (int) requireInteger(n, "workgroup size");
            if (localSize < 1 || localSize > 1024) {
                throw err(n, "workgroup size must be between 1 and 1024 (got " + n.text() + ")");
            }
            expect(Type.RPAREN, "')' after the workgroup size");
        }
        if (peek().type() == Type.ARROW) {
            throw err(peek(), "kernels cannot return values — write results into an 'out' buffer");
        }
        expectNewline("the kernel header");
        skipNewlines();

        List<Param> params = new ArrayList<>();
        List<CearlAst.SharedDecl> shareds = new ArrayList<>();
        Set<String> names = new HashSet<>();
        while (atWord("take") || atWord("shared")) {
            boolean isShared = peek().text().equals("shared");
            advance();
            Token nameTok = peek();
            String pname = expectFreshIdent(isShared ? "shared name" : "take name");
            if (!names.add(pname)) {
                throw err(nameTok, "duplicate " + (isShared ? "shared" : "take")
                    + " '" + pname + "'");
            }
            expect(Type.COLON, "':' between the name and its type");
            TypeRef type = parseType();
            if (isShared) {
                if (!type.array() || type.size() == null) {
                    throw err(nameTok, "shared memory is a fixed-size array"
                        + " — write 'shared " + pname + ": u32[256]'");
                }
                shareds.add(new CearlAst.SharedDecl(pname, type, nameTok.line()));
                expectNewline("the shared line");
                skipNewlines();
                continue;
            }
            Dir dir = Dir.IN;
            if (peek().type() == Type.IDENT) {
                switch (peek().text()) {
                    case "in" -> {
                        advance();
                        dir = Dir.IN;
                    }
                    case "out" -> {
                        advance();
                        dir = Dir.OUT;
                    }
                    case "inout" -> {
                        advance();
                        dir = Dir.INOUT;
                    }
                    default -> { }
                }
            }
            params.add(new Param(pname, type, dir, nameTok.line()));
            expectNewline("the take line");
            skipNewlines();
        }

        Block body = parseBody(kw, "kernel '" + name + "'", Set.of("end"));
        advance(); // end
        expectNewline("'end'");
        return new KernelDecl(name, localSize, params, shareds, body, kw.line());
    }

    private TypeRef parseType() {
        Token t = expect(Type.IDENT, "a type name");
        boolean array = false;
        Expr size = null;
        if (peek().type() == Type.LBRACKET) {
            advance();
            if (peek().type() != Type.RBRACKET) {
                size = parseExpr(); // Sized array: vec4[6], u32[SLOTS] — const-evaluated.
            }
            expect(Type.RBRACKET, "']' to close the array type ('T[]' runtime-sized,"
                + " 'T[n]' fixed-size)");
            array = true;
        }
        return new TypeRef(t.text(), array, size, t.line());
    }

    // ─── Statements ───────────────────────────────────────────────────────

    /** Parses statements until one of {@code stopWords} appears at line start. */
    private Block parseBody(Token opener, String what, Set<String> stopWords) {
        int startLine = peek().line();
        List<Stmt> stmts = new ArrayList<>();
        skipNewlines();
        while (true) {
            Token t = peek();
            requireNotEof(t, opener, what);
            if (t.type() == Type.IDENT && stopWords.contains(t.text())) {
                return new Block(stmts, startLine);
            }
            stmts.add(parseStmt());
            skipNewlines();
        }
    }

    private Stmt parseStmt() {
        Token t = peek();
        if (t.type() == Type.IDENT) {
            teachLegacy(t);
            switch (t.text()) {
                case "fix", "flux" -> {
                    return parseLocal();
                }
                case "if" -> {
                    return parseIf();
                }
                case "while" -> {
                    Token kw = advance();
                    Expr cond = parseExpr();
                    expectNewline("the while condition");
                    Block body = parseBody(kw, "while", Set.of("end"));
                    advance();
                    expectNewline("'end'");
                    return new While(cond, body, kw.line());
                }
                case "for" -> {
                    return parseFor();
                }
                case "give" -> {
                    advance();
                    Expr value = peek().type() == Type.NEWLINE || peek().type() == Type.EOF
                        ? null : parseExpr();
                    expectNewline("the give statement");
                    return new Return(value, t.line());
                }
                case "break" -> {
                    advance();
                    expectNewline("'break'");
                    return new Break(t.line());
                }
                case "continue" -> {
                    advance();
                    expectNewline("'continue'");
                    return new Continue(t.line());
                }
                default -> { }
            }
        }
        // Expression statement or assignment.
        Expr expr = parseExpr();
        Type nt = peek().type();
        if (nt == Type.ASSIGN || nt == Type.PLUS_ASSIGN || nt == Type.MINUS_ASSIGN
                || nt == Type.STAR_ASSIGN || nt == Type.SLASH_ASSIGN) {
            Token opTok = advance();
            if (!isLvalue(expr)) {
                throw err(opTok, "the left side of '" + opTok.text() + "' must be assignable"
                    + " — a variable, field, or indexed element");
            }
            Expr value = parseExpr();
            expectNewline("the assignment");
            return new Assign(expr, opTok.text(), value, opTok.line());
        }
        if (!(expr instanceof Call)) {
            throw err(t, "this expression has no effect — only calls can stand alone"
                + " as statements (did you mean to assign it with '='?)");
        }
        expectNewline("the call");
        return new ExprStmt(expr, t.line());
    }

    private Stmt parseLocal() {
        Token kw = advance();
        boolean mutable = kw.text().equals("flux");
        String name = expectFreshIdent("variable name");
        TypeRef declared = null;
        if (peek().type() == Type.COLON) {
            advance();
            declared = parseType();
            if (declared.array()) {
                throw err(kw, "locals cannot be arrays");
            }
        }
        expect(Type.ASSIGN, "'=' — every " + kw.text() + " must be initialized");
        Expr init = parseExpr();
        expectNewline("the initializer");
        return new Let(name, declared, init, mutable, kw.line());
    }

    private Stmt parseIf() {
        Token kw = advance();
        Expr cond = parseExpr();
        expectNewline("the if condition");
        Block then = parseBody(kw, "if", Set.of("elif", "else", "end"));
        Stmt elseBranch = parseIfTail(kw);
        return new If(cond, then, elseBranch, kw.line());
    }

    /** Handles elif chains and else; consumes the closing 'end' + newline. */
    private Stmt parseIfTail(Token opener) {
        Token t = peek();
        if (t.type() == Type.IDENT && t.text().equals("elif")) {
            advance();
            Expr cond = parseExpr();
            expectNewline("the elif condition");
            Block body = parseBody(opener, "elif", Set.of("elif", "else", "end"));
            return new If(cond, body, parseIfTail(opener), t.line());
        }
        if (t.type() == Type.IDENT && t.text().equals("else")) {
            advance();
            expectNewline("'else'");
            Block body = parseBody(opener, "else", Set.of("end"));
            advance(); // end
            expectNewline("'end'");
            return body;
        }
        advance(); // end
        expectNewline("'end'");
        return null;
    }

    private Stmt parseFor() {
        Token kw = advance();
        String var = expectFreshIdent("loop variable name");
        expectWord("in", "'in' after the loop variable");
        Expr from = parseExpr();
        expect(Type.DOTDOT, "'..' between the range bounds (for i in 0..n)");
        Expr to = parseExpr();
        expectNewline("the for header");
        Block body = parseBody(kw, "for", Set.of("end"));
        advance();
        expectNewline("'end'");
        return new ForRange(var, from, to, body, kw.line());
    }

    private static boolean isLvalue(Expr e) {
        return switch (e) {
            case Ident i -> true;
            case Member m -> isLvalue(m.target());
            case Index i -> isLvalue(i.target());
            default -> false;
        };
    }

    // ─── Expressions (precedence climbing; and/or/not are words) ──────────

    private Expr parseExpr() {
        return parseOtherwise();
    }

    /**
     * {@code a otherwise b} — the lowest-precedence operator: picks {@code a}
     * when it is a non-zero integer, else {@code b}. Host-only (plans and
     * pins); the checker rejects it in device code. Exists so a plan can say
     * {@code (vram otherwise FLOOR) * 3 / 4} — real hardware when detected,
     * the min-spec assumption when not.
     */
    private Expr parseOtherwise() {
        Expr left = parseOr();
        while (atWord("otherwise")) {
            Token op = advance();
            left = new Bin("otherwise", left, parseOr(), op.line());
        }
        return left;
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (atWord("or")) {
            Token op = advance();
            left = new Bin("||", left, parseAnd(), op.line());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseEquality();
        while (atWord("and")) {
            Token op = advance();
            left = new Bin("&&", left, parseEquality(), op.line());
        }
        return left;
    }

    private Expr parseEquality() {
        Expr left = parseRelational();
        while (peek().type() == Type.EQ || peek().type() == Type.NE) {
            Token op = advance();
            left = new Bin(op.text(), left, parseRelational(), op.line());
        }
        return left;
    }

    private Expr parseRelational() {
        Expr left = parseAdditive();
        while (peek().type() == Type.LT || peek().type() == Type.LE
                || peek().type() == Type.GT || peek().type() == Type.GE) {
            Token op = advance();
            left = new Bin(op.text(), left, parseAdditive(), op.line());
        }
        return left;
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (peek().type() == Type.PLUS || peek().type() == Type.MINUS) {
            Token op = advance();
            left = new Bin(op.text(), left, parseMultiplicative(), op.line());
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (peek().type() == Type.STAR || peek().type() == Type.SLASH
                || peek().type() == Type.PERCENT) {
            Token op = advance();
            left = new Bin(op.text(), left, parseUnary(), op.line());
        }
        return left;
    }

    private Expr parseUnary() {
        Token t = peek();
        if (t.type() == Type.MINUS) {
            advance();
            return new Un("-", parseUnary(), t.line());
        }
        if (t.type() == Type.IDENT && t.text().equals("not")) {
            advance();
            return new Un("!", parseUnary(), t.line());
        }
        return parsePostfix();
    }

    private Expr parsePostfix() {
        Expr expr = parsePrimary();
        while (true) {
            Token t = peek();
            if (t.type() == Type.DOT) {
                advance();
                Token name = expect(Type.IDENT, "a field, swizzle, or craft name after '.'");
                expr = new Member(expr, name.text(), name.line());
            } else if (t.type() == Type.LBRACKET) {
                advance();
                Expr index = parseExpr();
                expect(Type.RBRACKET, "']' to close the index");
                expr = new Index(expr, index, t.line());
            } else if (t.type() == Type.LPAREN) {
                if (!(expr instanceof Ident) && !(expr instanceof Member)) {
                    throw err(t, "only craft names and methods can be called");
                }
                advance();
                List<Expr> args = new ArrayList<>();
                while (peek().type() != Type.RPAREN) {
                    args.add(parseExpr());
                    if (peek().type() == Type.COMMA) {
                        advance();
                    } else {
                        break;
                    }
                }
                expect(Type.RPAREN, "')' to close the argument list");
                expr = new Call(expr, args, t.line());
            } else {
                return expr;
            }
        }
    }

    private Expr parsePrimary() {
        Token t = peek();
        switch (t.type()) {
            case NUMBER -> {
                advance();
                // A unit identifier straight after a number makes a size literal.
                if (peek().type() == Type.IDENT) {
                    long mult = unitMultiplier(peek().text());
                    if (mult > 0) {
                        Token unit = advance();
                        double bytes = t.number() * mult;
                        if (bytes > Long.MAX_VALUE) {
                            throw err(t, "size literal overflows");
                        }
                        return new SizeLit((long) bytes, t.text() + " " + unit.text(), t.line());
                    }
                }
                if (t.text().contains(".")) {
                    return new FloatLit(t.number(), t.text(), t.line());
                }
                return new IntLit((long) t.number(), t.text(), t.line());
            }
            case IDENT -> {
                if (t.text().equals("true") || t.text().equals("false")) {
                    advance();
                    return new BoolLit(t.text().equals("true"), t.line());
                }
                if (KEYWORDS.contains(t.text())) {
                    throw err(t, "'" + t.text() + "' is a keyword and cannot start an expression");
                }
                advance();
                return new Ident(t.text(), t.line());
            }
            case LPAREN -> {
                advance();
                Expr inner = parseExpr();
                expect(Type.RPAREN, "')' to close the parenthesized expression");
                return inner;
            }
            default -> throw err(t, "expected an expression but found " + t.describe());
        }
    }

    // ─── Plan declarations ────────────────────────────────────────────────

    private PlanDecl parsePlan() {
        Token kw = advance();
        String name = expectFreshIdent("plan name");
        expectNewline("the plan header");
        DeviceDecl device = null;
        List<PoolDecl> pools = new ArrayList<>();
        List<WhenDecl> whens = new ArrayList<>();
        List<PressureRule> rules = new ArrayList<>();
        Set<String> poolNames = new HashSet<>();

        skipNewlines();
        while (!atWord("end")) {
            Token t = peek();
            requireNotEof(t, kw, "plan '" + name + "'");
            String w = t.type() == Type.IDENT ? t.text() : "";
            switch (w) {
                case "device" -> {
                    if (device != null) {
                        throw err(t, "duplicate 'device' block — a plan has one device");
                    }
                    device = parseDevice();
                }
                case "pool" -> {
                    PoolDecl pool = parsePool();
                    if (!poolNames.add(pool.name())) {
                        throw err(t, "duplicate pool '" + pool.name() + "'");
                    }
                    pools.add(pool);
                }
                case "when" -> whens.add(parseWhen());
                case "on" -> rules.add(parseOn());
                default -> throw err(t, "expected 'device', 'pool', 'when', or 'on' inside"
                    + " the plan but found " + t.describe());
            }
            skipNewlines();
        }
        advance(); // end
        expectNewline("'end'");
        return new PlanDecl(name, device, pools, whens, rules, kw.line());
    }

    private DeviceDecl parseDevice() {
        Token kw = advance();
        expectNewline("'device'");
        Expr budget = null;
        double headroom = -1;
        skipNewlines();
        while (!atWord("end")) {
            Token t = expect(Type.IDENT, "'budget' or 'headroom'");
            requireNotEof(t, kw, "the device block");
            switch (t.text()) {
                case "budget" -> {
                    dupCheck(budget != null, t);
                    // A full const expression: '6 GiB', 'vram * 3 / 4', ...
                    budget = parseExpr();
                }
                case "headroom" -> {
                    dupCheck(headroom >= 0, t);
                    headroom = parsePercent("headroom");
                }
                default -> throw err(t, "unknown device attribute '" + t.text()
                    + "' — expected budget or headroom");
            }
            expectNewline("the attribute");
            skipNewlines();
        }
        advance(); // end
        expectNewline("'end'");
        return new DeviceDecl(budget, headroom, kw.line());
    }

    private PoolDecl parsePool() {
        Token kw = advance();
        String name = expectFreshIdent("pool name");
        String parent = null;
        if (atWord("from")) {
            advance();
            parent = expect(Type.IDENT, "the parent pool name after 'from'").text();
        }
        expectNewline("the pool header");

        String category = null;
        long budgetBytes = -1;
        double budgetShare = -1;
        int priority = PoolDecl.PRIORITY_UNSET;
        String storage = null;
        String grow = null;
        ArenaDecl arena = null;

        skipNewlines();
        while (!atWord("end")) {
            Token t = expect(Type.IDENT,
                "a pool attribute (category, budget, priority, storage, grow, or arena)");
            requireNotEof(t, kw, "pool '" + name + "'");
            switch (t.text()) {
                case "category" -> {
                    dupCheck(category != null, t);
                    category = expect(Type.IDENT, "a GpuMemoryTracker category name").text();
                    expectNewline("the attribute");
                }
                case "budget" -> {
                    dupCheck(budgetBytes >= 0 || budgetShare >= 0, t);
                    Token n = expect(Type.NUMBER, "a size (e.g. 512 MiB) or share (e.g. 45%)");
                    if (peek().type() == Type.PERCENT) {
                        advance();
                        budgetShare = n.number() / 100.0;
                    } else {
                        budgetBytes = sizeFrom(n, "pool budget");
                    }
                    expectNewline("the attribute");
                }
                case "priority" -> {
                    dupCheck(priority != PoolDecl.PRIORITY_UNSET, t);
                    Token n = expect(Type.NUMBER, "an integer priority");
                    priority = (int) requireInteger(n, "priority");
                    expectNewline("the attribute");
                }
                case "storage" -> {
                    dupCheck(storage != null, t);
                    Token m = expect(Type.IDENT, "'static' or 'persistent'");
                    if (!STORAGE_MODES.contains(m.text())) {
                        throw err(m, "unknown storage mode '" + m.text()
                            + "' — expected static or persistent");
                    }
                    storage = m.text();
                    expectNewline("the attribute");
                }
                case "grow" -> {
                    dupCheck(grow != null, t);
                    Token m = expect(Type.IDENT, "'copy' or 'sparse'");
                    if (!GROW_MODES.contains(m.text())) {
                        throw err(m, "unknown grow mode '" + m.text()
                            + "' — expected copy or sparse");
                    }
                    grow = m.text();
                    expectNewline("the attribute");
                }
                case "arena" -> {
                    dupCheck(arena != null, t);
                    arena = parseArena(t);
                }
                default -> throw err(t, "unknown pool attribute '" + t.text()
                    + "' — expected category, budget, priority, storage, grow, or arena");
            }
            skipNewlines();
        }
        advance(); // end
        expectNewline("'end'");
        return new PoolDecl(name, parent, category, budgetBytes, budgetShare, priority,
            storage, grow, arena, kw.line());
    }

    private ArenaDecl parseArena(Token kw) {
        expectNewline("'arena'");
        long vertex = -1;
        long index = -1;
        double growth = -1;
        double reserve = -1;
        int align = -1;
        double trim = -1;
        skipNewlines();
        while (!atWord("end")) {
            Token t = expect(Type.IDENT,
                "an arena attribute (vertex, index, growth, reserve, align, or trim)");
            requireNotEof(t, kw, "the arena block");
            switch (t.text()) {
                case "vertex" -> {
                    dupCheck(vertex >= 0, t);
                    vertex = parseSize("vertex arena size");
                }
                case "index" -> {
                    dupCheck(index >= 0, t);
                    index = parseSize("index arena size");
                }
                case "growth" -> {
                    dupCheck(growth >= 0, t);
                    growth = expect(Type.NUMBER, "a growth factor, e.g. 1.75").number();
                }
                case "reserve" -> {
                    dupCheck(reserve >= 0, t);
                    reserve = parsePercent("reserve");
                }
                case "align" -> {
                    dupCheck(align >= 0, t);
                    Token n = expect(Type.NUMBER, "an element alignment, e.g. 4");
                    align = (int) requireInteger(n, "align");
                }
                case "trim" -> {
                    dupCheck(trim >= 0, t);
                    trim = parsePercent("trim");
                }
                default -> throw err(t, "unknown arena attribute '" + t.text()
                    + "' — expected vertex, index, growth, reserve, align, or trim");
            }
            expectNewline("the attribute");
            skipNewlines();
        }
        advance(); // end
        expectNewline("'end'");
        return new ArenaDecl(vertex, index, growth, reserve, align, trim, kw.line());
    }

    private WhenDecl parseWhen() {
        Token kw = advance();
        Expr condition = parseExpr();
        expectNewline("the when condition");
        List<PoolDecl> pools = new ArrayList<>();
        skipNewlines();
        while (!atWord("end")) {
            Token t = peek();
            requireNotEof(t, kw, "the when block");
            if (t.type() == Type.IDENT && t.text().equals("pool")) {
                pools.add(parsePool());
            } else {
                throw err(t, "only pool overrides may appear inside 'when' but found "
                    + t.describe());
            }
            skipNewlines();
        }
        advance(); // end
        expectNewline("'end'");
        return new WhenDecl(condition, pools, kw.line());
    }

    private PressureRule parseOn() {
        Token kw = advance();
        expectWord("pressure", "'pressure' after 'on' — the only trigger in this version");
        expect(Type.GT, "'>' after 'pressure'");
        Token n = expect(Type.NUMBER, "a pressure threshold percent, e.g. 85%");
        expect(Type.PERCENT, "'%' after the threshold");
        double threshold = n.number() / 100.0;
        expectNewline("the rule header");
        skipNewlines();
        expectWord("shed", "'shed' followed by pool names");
        List<String> shed = new ArrayList<>();
        shed.add(expect(Type.IDENT, "a pool name to shed").text());
        while (peek().type() == Type.COMMA) {
            advance();
            shed.add(expect(Type.IDENT, "a pool name to shed").text());
        }
        expectNewline("the shed list");
        skipNewlines();
        expectWord("end", "'end' to close the rule");
        expectNewline("'end'");
        return new PressureRule(threshold, shed, kw.line());
    }

    private long parseSize(String what) {
        Token n = expect(Type.NUMBER, "a size for the " + what + ", e.g. 640 KiB");
        return sizeFrom(n, what);
    }

    private long sizeFrom(Token n, String what) {
        Token unit = expect(Type.IDENT, "a unit after the " + what
            + " (B, KiB, MiB, or GiB; KB/MB/GB are binary aliases)");
        long mult = unitMultiplier(unit.text());
        if (mult < 0) {
            throw err(unit, "unknown size unit '" + unit.text()
                + "' — expected B, KiB, MiB, or GiB (KB/MB/GB are binary aliases)");
        }
        double bytes = n.number() * mult;
        if (bytes < 1 || bytes > Long.MAX_VALUE) {
            throw err(n, "the " + what + " must be at least 1 byte");
        }
        return (long) bytes;
    }

    private double parsePercent(String what) {
        Token n = expect(Type.NUMBER, "a percent for the " + what + ", e.g. 25%");
        expect(Type.PERCENT, "'%' after the " + what + " value");
        return n.number() / 100.0;
    }

    private void dupCheck(boolean seen, Token t) {
        if (seen) {
            throw err(t, "duplicate '" + t.text() + "' — each attribute may appear once per block");
        }
    }

    // ─── Token plumbing ───────────────────────────────────────────────────

    private Token peek() {
        return tokens.get(idx);
    }

    private Token advance() {
        Token t = tokens.get(idx);
        if (t.type() != Type.EOF) {
            idx++;
        }
        return t;
    }

    private boolean atWord(String word) {
        Token t = peek();
        return t.type() == Type.IDENT && t.text().equals(word);
    }

    private void skipNewlines() {
        while (peek().type() == Type.NEWLINE) {
            advance();
        }
    }

    /** Consumes the statement terminator: a newline (or end of file). */
    private void expectNewline(String after) {
        Token t = peek();
        if (t.type() == Type.NEWLINE) {
            advance();
            return;
        }
        if (t.type() == Type.EOF) {
            return;
        }
        throw err(t, "expected end of line after " + after + " but found " + t.describe()
            + " — CEARL takes one statement per line");
    }

    private void requireNotEof(Token t, Token opener, String what) {
        if (t.type() == Type.EOF) {
            throw err(t, "unexpected end of file — " + what + " opened at line "
                + opener.line() + " is never closed with 'end'");
        }
    }

    private Token expect(Type type, String what) {
        Token t = peek();
        if (t.type() != type) {
            throw err(t, "expected " + what + " but found " + t.describe());
        }
        return advance();
    }

    private Token expectWord(String word, String what) {
        Token t = peek();
        if (t.type() != Type.IDENT || !t.text().equals(word)) {
            throw err(t, "expected " + what + " but found " + t.describe());
        }
        return advance();
    }

    private String expectFreshIdent(String what) {
        Token t = expect(Type.IDENT, "a " + what);
        if (KEYWORDS.contains(t.text())) {
            throw err(t, "'" + t.text() + "' is a keyword and cannot be used as a " + what);
        }
        return t.text();
    }

    /** Old-spelling tokens get an error naming the CEARL word. */
    private void teachLegacy(Token t) {
        if (t.type() == Type.IDENT) {
            String replacement = LEGACY.get(t.text());
            if (replacement != null) {
                throw err(t, "'" + t.text() + "' is not a CEARL word — write '"
                    + replacement + "'");
            }
        }
    }

    private long requireInteger(Token n, String what) {
        if (n.text().contains(".")) {
            throw err(n, "the " + what + " must be a whole number (got " + n.text() + ")");
        }
        return (long) n.number();
    }

    private CearlException err(Token t, String message) {
        return new CearlException(sourceName, t.line(), t.column(), message);
    }
}
