package com.openmason.engine.cearl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code half of CEARL's native syntax: parsing (newline statements,
 * end-blocks, worded logic), type checking, mutability and buffer discipline,
 * implicit fields in methods, recursion rejection, and the teaching quality
 * of errors — including the ones that catch C-family habits.
 */
class CearlLanguageTest {

    private static final Map<String, Long> ENV = Map.of("vram", 0L);

    private static CearlProgram compile(String source) {
        return CearlCompiler.compile(source, "test.CEARL", ENV);
    }

    private static CearlException fails(String source) {
        return assertThrows(CearlException.class, () -> compile(source));
    }

    // ─── A full program compiles ──────────────────────────────────────────

    private static final String DEMO = """
        pin HALF: f32 = 0.5

        form Box
            lo: vec3
            pad0: f32
            hi: vec3
            pad1: f32

            craft mid() -> vec3
                give (lo + hi) * HALF
            end

            craft expand(by: f32)
                hi = hi + vec3(by)
            end
        end

        craft squared(x: f32) -> f32
            give x * x
        end

        kernel demo(128)
            take boxes: Box[] in
            take counts: u32[] inout
            take scale: f32

            fix i = gid.x
            if i >= count(boxes)
                give
            end
            flux b = boxes[i]
            b.expand(squared(scale))
            fix c = b.mid()
            if c.y > 0.0
                atomic_add(counts[0], 1)
            end
        end
        """;

    @Test
    void fullProgramCompiles() {
        CearlProgram program = compile(DEMO);
        CearlKernel kernel = program.kernel("demo");
        assertNotNull(kernel);
        assertEquals(128, kernel.localSize());
        assertEquals(2, kernel.buffers().size());
        assertEquals(1, kernel.uniforms().size());
        assertEquals("scale", kernel.uniforms().getFirst().name());
        assertEquals(0, kernel.buffers().getFirst().binding());
        assertEquals(1, kernel.buffers().get(1).binding());
    }

    @Test
    void formsGetMethodsWithStaticDispatch() {
        String glsl = compile(DEMO).kernel("demo").glsl();
        // Read-only method takes self by value; mutating method takes inout.
        assertTrue(glsl.contains("vec3 Box_mid(in Box self)"), glsl);
        assertTrue(glsl.contains("void Box_expand(inout Box self, in float by)"), glsl);
        // Method calls rewrite to the flattened functions.
        assertTrue(glsl.contains("Box_expand(b, squared(scale))"), glsl);
        assertTrue(glsl.contains("Box_mid(b)"), glsl);
    }

    @Test
    void bareFieldsInMethodsEmitAsSelf() {
        String glsl = compile(DEMO).kernel("demo").glsl();
        // `give (lo + hi) * HALF` — implicit fields lower to self.<field>.
        assertTrue(glsl.contains("((self.lo + self.hi) * HALF)"), glsl);
        assertTrue(glsl.contains("self.hi = (self.hi + vec3(by))"), glsl);
    }

    // ─── The syntax is its own — C-family habits get taught ───────────────

    @Test
    void semicolonsAreRejectedWithTheFix() {
        CearlException e = fails("""
            kernel k
                take r: u32[] out
                fix x = 1;
            end
            """);
        assertTrue(e.getMessage().contains("remove the ';'"), e.getMessage());
    }

    @Test
    void bracesAreRejectedWithTheFix() {
        CearlException e = fails("kernel k {\n");
        assertTrue(e.getMessage().contains("'header ... end'"), e.getMessage());
    }

    @Test
    void symbolLogicTeachesTheWords() {
        assertTrue(fails("kernel k\n    take r: u32[] out\n    if true && false\n        r[0] = 1\n    end\nend")
            .getMessage().contains("'and'"));
        assertTrue(fails("kernel k\n    take r: u32[] out\n    if true || false\n        r[0] = 1\n    end\nend")
            .getMessage().contains("'or'"));
        assertTrue(fails("kernel k\n    take r: u32[] out\n    if !true\n        r[0] = 1\n    end\nend")
            .getMessage().contains("'not'"));
    }

    @Test
    void legacySpellingsNameTheCearlWord() {
        assertTrue(fails("kernel k\n    take r: u32[] out\n    let x = 1\nend")
            .getMessage().contains("write 'fix'"));
        assertTrue(fails("struct P\n    x: f32\nend")
            .getMessage().contains("write 'form'"));
        assertTrue(fails("fn f(x: f32) -> f32\n    give x\nend")
            .getMessage().contains("write 'craft'"));
    }

    @Test
    void hashCommentsTeachTilde() {
        assertTrue(fails("# old comment\nkernel k\nend")
            .getMessage().contains("'~'"));
    }

    // ─── Mutability discipline ────────────────────────────────────────────

    @Test
    void fixIsImmutable() {
        CearlException e = fails("""
            kernel k
                take r: u32[] out
                fix x = 1
                x = 2
                r[0] = u32(x)
            end
            """);
        assertTrue(e.getMessage().contains("immutable"), e.getMessage());
        assertTrue(e.getMessage().contains("'flux'"), e.getMessage());
        assertEquals(4, e.line());
    }

    @Test
    void mutatingMethodNeedsMutableReceiver() {
        CearlException e = fails(DEMO.replace("flux b = boxes[i]", "fix b = boxes[i]"));
        assertTrue(e.getMessage().contains("immutable"), e.getMessage());
    }

    @Test
    void inBufferIsReadOnly() {
        CearlException e = fails("""
            kernel k
                take data: u32[] in
                data[0] = 1
            end
            """);
        assertTrue(e.getMessage().contains("read-only"), e.getMessage());
    }

    @Test
    void outBufferIsWriteOnly() {
        CearlException e = fails("""
            kernel k
                take data: u32[] out
                fix x = data[0]
                data[0] = x
            end
            """);
        assertTrue(e.getMessage().contains("write-only"), e.getMessage());
    }

    @Test
    void atomicsNeedInoutBuffers() {
        CearlException e = fails("""
            kernel k
                take data: u32[] out
                atomic_add(data[0], 1)
            end
            """);
        assertTrue(e.getMessage().contains("inout"), e.getMessage());
    }

    // ─── Type discipline ──────────────────────────────────────────────────

    @Test
    void noImplicitConversionsBetweenVariables() {
        CearlException e = fails("""
            kernel k
                take r: f32[] out
                fix a = 1.5
                fix b = 7
                r[0] = a + b
            end
            """);
        assertTrue(e.getMessage().contains("implicit"), e.getMessage());
    }

    @Test
    void intLiteralsAdaptToContext() {
        // The same literal works against f32 and u32 without casts.
        compile("""
            kernel k
                take r: f32[] out
                fix a = 1.5 * 2
                fix i = gid.x + 1
                r[i] = a
            end
            """);
    }

    @Test
    void i64IsHostOnly() {
        CearlException e = fails("""
            kernel k
                take r: u32[] out
                fix x: i64 = 1
                r[0] = 1
            end
            """);
        assertTrue(e.getMessage().contains("host-only"), e.getMessage());
    }

    @Test
    void sizeLiteralsAreHostOnly() {
        CearlException e = fails("""
            kernel k
                take r: u32[] out
                fix x = 4 MiB
                r[0] = 1
            end
            """);
        assertTrue(e.getMessage().contains("host-only"), e.getMessage());
    }

    @Test
    void uniformArraysAreReadOnlyInputs() {
        assertTrue(fails("""
            kernel k
                take planes: vec4[6]
                planes[0] = vec4(0.0)
            end
            """).getMessage().contains("read-only"));
        assertTrue(fails("""
            kernel k
                take planes: vec4[6] out
                planes[0] = vec4(0.0)
            end
            """).getMessage().contains("inputs"));
        assertTrue(fails("""
            kernel k
                take xs: u32[9999]
                fix a = xs[0]
            end
            """).getMessage().contains("register space"));
    }

    @Test
    void sharedMemoryIsValidated() {
        // Too big for the portable 32 KiB per-workgroup budget.
        assertTrue(fails("""
            kernel k
                take r: u32[] out
                shared big: vec4[4096]
                r[0] = 1
            end
            """).getMessage().contains("32 KiB"));
        // Shared arrays must be sized.
        assertTrue(fails("""
            kernel k
                take r: u32[] out
                shared s: u32[]
                r[0] = 1
            end
            """).getMessage().contains("fixed-size"));
        // Atomics on shared memory are legal (no 'inout' needed).
        compile("""
            kernel k
                take r: u32[] inout
                shared s: u32[64]
                s[lid.x] = 0
                barrier()
                atomic_add(s[0], 1)
                barrier()
                if lid.x == 0
                    atomic_add(r[0], s[0])
                end
            end
            """);
    }

    @Test
    void otherwiseIsHostOnly() {
        CearlException e = fails("""
            kernel k
                take r: u32[] out
                fix x = gid.x otherwise 1
                r[0] = x
            end
            """);
        assertTrue(e.getMessage().contains("host-only"), e.getMessage());
    }

    @Test
    void modOnFloatsTeachesMod() {
        CearlException e = fails("""
            kernel k
                take r: f32[] out
                r[0] = 1.5 % 2.0
            end
            """);
        assertTrue(e.getMessage().contains("mod("), e.getMessage());
    }

    @Test
    void shadowingIsRejected() {
        CearlException e = fails("""
            kernel k
                take r: u32[] out
                fix x = 1
                if true
                    fix x = 2
                    r[0] = u32(x)
                end
            end
            """);
        assertTrue(e.getMessage().contains("shadowing"), e.getMessage());
    }

    @Test
    void localsCannotShadowFields() {
        CearlException e = fails("""
            form P
                x: f32

                craft reset(x: f32)
                    give
                end
            end
            kernel k
                take ps: P[] out
                ps[0].x = 1.0
            end
            """);
        assertTrue(e.getMessage().contains("shadows a field"), e.getMessage());
    }

    @Test
    void unknownNamesAndTypesTeach() {
        assertTrue(fails("kernel k\n    take r: u32[] out\n    r[0] = missing\nend")
            .getMessage().contains("unknown name 'missing'"));
        assertTrue(fails("kernel k\n    take r: Wat[] out\n    r[0].x = 1.0\nend")
            .getMessage().contains("unknown type 'Wat'"));
    }

    @Test
    void formFieldErrorsListFields() {
        CearlException e = fails("""
            form P
                x: f32
                y: f32
            end
            kernel k
                take ps: P[] in
                take r: f32[] out
                r[0] = ps[0].z
            end
            """);
        assertTrue(e.getMessage().contains("no field 'z'"), e.getMessage());
        assertTrue(e.getMessage().contains("x"), e.getMessage());
    }

    // ─── GPU-target restrictions ──────────────────────────────────────────

    @Test
    void recursionIsRejected() {
        CearlException e = fails("""
            craft a(x: f32) -> f32
                give b(x)
            end
            craft b(x: f32) -> f32
                give a(x)
            end
            kernel k
                take r: f32[] out
                r[0] = a(1.0)
            end
            """);
        assertTrue(e.getMessage().contains("recursion"), e.getMessage());
    }

    @Test
    void missingGivePathIsCaught() {
        CearlException e = fails("""
            craft f(x: f32) -> f32
                if x > 0.0
                    give x
                end
            end
            kernel k
                take r: f32[] out
                r[0] = f(1.0)
            end
            """);
        assertTrue(e.getMessage().contains("give"), e.getMessage());
    }

    @Test
    void reservedGpuNamesAreRejected() {
        assertTrue(fails("kernel k\n    take r: u32[] out\n    fix float = 1\nend")
            .getMessage().contains("reserved"));
        assertTrue(fails("form main\n    x: f32\nend").getMessage().contains("reserved"));
    }

    // ─── Parser diagnostics carry locations ───────────────────────────────

    @Test
    void unclosedBlockNamesTheOpeningLine() {
        CearlException e = fails("kernel k\n    take r: u32[] out\n    r[0] = 1\n");
        assertTrue(e.getMessage().contains("never closed"), e.getMessage());
    }

    @Test
    void errorsCarryLineAndColumn() {
        CearlException e = fails("\n\nkernel k\n    take r: u32[] out\n    r[0] === 1\nend");
        assertEquals(5, e.line());
        assertTrue(e.column() > 0);
    }

    @Test
    void keywordsCannotBeNames() {
        assertTrue(fails("craft while(x: f32) -> f32\n    give x\nend")
            .getMessage().contains("keyword"));
    }
}
