package com.openmason.engine.ui.rendering;

import java.util.Arrays;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Everything Skia may change, captured before a paint and put back after it
 * ({@link GlStatePolicy#RESTORE}). Covers what ImGui's GL3 backend, NanoVG and the world
 * passes depend on: program, VAO and buffers (including the VAO's element binding), draw/read
 * framebuffers, viewport and scissor, capabilities (incl. dither and logic op), blend equation,
 * function and colour, depth and front/back stencil configuration, polygon mode and offset, the
 * clear colour, write masks, every pack/unpack pixel-store parameter, and the 2D texture and
 * sampler binding of every unit Skia may use.
 *
 * <p>Must be captured and restored on the thread that owns the GL context.
 */
public final class GlStateSnapshot {

    private static final int UNITS = GlBaseline.RESET_TEXTURE_UNITS;
    private static final int[] CAPS = {GL_SCISSOR_TEST, GL_BLEND, GL_DEPTH_TEST, GL_STENCIL_TEST, GL_CULL_FACE,
            GL_FRAMEBUFFER_SRGB, GL_MULTISAMPLE, GL_PRIMITIVE_RESTART, GL_POLYGON_OFFSET_FILL,
            GL_POLYGON_OFFSET_LINE, GL_DITHER, GL_COLOR_LOGIC_OP, GL_SAMPLE_ALPHA_TO_COVERAGE};
    /** Pixel-store parameters, restored in this order (pack and unpack). */
    private static final int[] PIXEL_STORE = {GL_UNPACK_ALIGNMENT, GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_ROWS,
            GL_UNPACK_SKIP_PIXELS, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_IMAGES, GL_PACK_ALIGNMENT,
            GL_PACK_ROW_LENGTH, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS, GL_PACK_IMAGE_HEIGHT, GL_PACK_SKIP_IMAGES};

    private final int program;
    private final int vao;
    private final int arrayBuffer;
    private final int elementBuffer;
    private final int drawFramebuffer;
    private final int readFramebuffer;
    private final int[] viewport = new int[4];
    private final int[] scissor = new int[4];
    private final boolean[] caps = new boolean[CAPS.length];
    private final int blendSrcRgb;
    private final int blendDstRgb;
    private final int blendSrcAlpha;
    private final int blendDstAlpha;
    private final int blendEqRgb;
    private final int blendEqAlpha;
    private final boolean[] colorMask = new boolean[4];
    private final boolean depthMask;
    private final int depthFunc;
    private final int stencilFunc;
    private final int stencilRef;
    private final int stencilValueMask;
    private final int stencilWriteMask;
    private final int stencilFail;
    private final int stencilPassDepthFail;
    private final int stencilPassDepthPass;
    private final int backStencilFunc;
    private final int backStencilRef;
    private final int backStencilValueMask;
    private final int backStencilWriteMask;
    private final int backStencilFail;
    private final int backStencilPassDepthFail;
    private final int backStencilPassDepthPass;
    private final float[] blendColor = new float[4];
    private final float[] clearColor = new float[4];
    private final int[] polygonMode = new int[2];
    private final float polygonOffsetFactor;
    private final float polygonOffsetUnits;
    private final int frontFace;
    private final int activeTexture;
    private final int[] textures = new int[UNITS];
    private final int[] samplers = new int[UNITS];
    private final int[] pixelStore = new int[PIXEL_STORE.length];
    private final int unpackBuffer;
    private final int packBuffer;

    private GlStateSnapshot() {
        program = glGetInteger(GL_CURRENT_PROGRAM);
        vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        elementBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
        drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_SCISSOR_BOX, scissor);
        for (int i = 0; i < CAPS.length; i++) {
            caps[i] = glIsEnabled(CAPS[i]);
        }
        blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        blendEqRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
        blendEqAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        int[] mask = new int[4];
        glGetIntegerv(GL_COLOR_WRITEMASK, mask);
        for (int i = 0; i < 4; i++) {
            colorMask[i] = mask[i] != 0;
        }
        depthMask = glGetInteger(GL_DEPTH_WRITEMASK) != 0;
        depthFunc = glGetInteger(GL_DEPTH_FUNC);
        stencilFunc = glGetInteger(GL_STENCIL_FUNC);
        stencilRef = glGetInteger(GL_STENCIL_REF);
        stencilValueMask = glGetInteger(GL_STENCIL_VALUE_MASK);
        stencilWriteMask = glGetInteger(GL_STENCIL_WRITEMASK);
        stencilFail = glGetInteger(GL_STENCIL_FAIL);
        stencilPassDepthFail = glGetInteger(GL_STENCIL_PASS_DEPTH_FAIL);
        stencilPassDepthPass = glGetInteger(GL_STENCIL_PASS_DEPTH_PASS);
        backStencilFunc = glGetInteger(GL_STENCIL_BACK_FUNC);
        backStencilRef = glGetInteger(GL_STENCIL_BACK_REF);
        backStencilValueMask = glGetInteger(GL_STENCIL_BACK_VALUE_MASK);
        backStencilWriteMask = glGetInteger(GL_STENCIL_BACK_WRITEMASK);
        backStencilFail = glGetInteger(GL_STENCIL_BACK_FAIL);
        backStencilPassDepthFail = glGetInteger(GL_STENCIL_BACK_PASS_DEPTH_FAIL);
        backStencilPassDepthPass = glGetInteger(GL_STENCIL_BACK_PASS_DEPTH_PASS);
        glGetFloatv(GL_BLEND_COLOR, blendColor);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
        glGetIntegerv(GL_POLYGON_MODE, polygonMode); // [front, back] (compatibility); core reports both alike
        polygonOffsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR);
        polygonOffsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS);
        frontFace = glGetInteger(GL_FRONT_FACE);
        activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int unit = 0; unit < UNITS; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            textures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D);
            samplers[unit] = glGetInteger(GL_SAMPLER_BINDING);
        }
        glActiveTexture(activeTexture);
        for (int i = 0; i < PIXEL_STORE.length; i++) {
            pixelStore[i] = glGetInteger(PIXEL_STORE[i]);
        }
        unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        packBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
    }

    public static GlStateSnapshot capture() {
        return new GlStateSnapshot();
    }

    public void restore() {
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        // The element binding is VAO state: captured with the caller's VAO bound, it is put back
        // into that same VAO. Skia may have bound an element buffer while the caller's VAO was
        // still current (before switching to its own), which would otherwise survive (#296 review).
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        for (int i = 0; i < CAPS.length; i++) {
            if (caps[i]) {
                glEnable(CAPS[i]);
            } else {
                glDisable(CAPS[i]);
            }
        }
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glBlendEquationSeparate(blendEqRgb, blendEqAlpha);
        glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        glDepthMask(depthMask);
        glDepthFunc(depthFunc);
        glStencilFuncSeparate(GL_FRONT, stencilFunc, stencilRef, stencilValueMask);
        glStencilMaskSeparate(GL_FRONT, stencilWriteMask);
        glStencilOpSeparate(GL_FRONT, stencilFail, stencilPassDepthFail, stencilPassDepthPass);
        glStencilFuncSeparate(GL_BACK, backStencilFunc, backStencilRef, backStencilValueMask);
        glStencilMaskSeparate(GL_BACK, backStencilWriteMask);
        glStencilOpSeparate(GL_BACK, backStencilFail, backStencilPassDepthFail, backStencilPassDepthPass);
        glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3]);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        restorePolygonMode();
        glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits);
        glFrontFace(frontFace);
        for (int unit = UNITS - 1; unit >= 0; unit--) {
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, textures[unit]);
            glBindSampler(unit, samplers[unit]);
        }
        glActiveTexture(activeTexture);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
        for (int i = 0; i < PIXEL_STORE.length; i++) {
            glPixelStorei(PIXEL_STORE[i], pixelStore[i]);
        }
    }

    private void restorePolygonMode() {
        if (polygonMode[0] == 0) {
            return; // the context did not report it
        }
        if (polygonMode[0] == polygonMode[1] || polygonMode[1] == 0) {
            glPolygonMode(GL_FRONT_AND_BACK, polygonMode[0]);
            return;
        }
        // Different front/back modes only exist in a compatibility context, where GL_FRONT and
        // GL_BACK are valid faces.
        org.lwjgl.opengl.GL11.glPolygonMode(GL_FRONT, polygonMode[0]);
        org.lwjgl.opengl.GL11.glPolygonMode(GL_BACK, polygonMode[1]);
    }

    // ── accessors for GL integration tests ──

    public int program() {
        return program;
    }

    public int drawFramebuffer() {
        return drawFramebuffer;
    }

    public int[] viewport() {
        return viewport.clone();
    }

    /** Same captured values (bindings, capabilities, configuration) as {@code other}. */
    public boolean sameAs(GlStateSnapshot o) {
        return program == o.program && vao == o.vao && arrayBuffer == o.arrayBuffer
                && elementBuffer == o.elementBuffer && drawFramebuffer == o.drawFramebuffer
                && readFramebuffer == o.readFramebuffer && Arrays.equals(viewport, o.viewport)
                && Arrays.equals(scissor, o.scissor) && Arrays.equals(caps, o.caps)
                && blendSrcRgb == o.blendSrcRgb && blendDstRgb == o.blendDstRgb && blendSrcAlpha == o.blendSrcAlpha
                && blendDstAlpha == o.blendDstAlpha && blendEqRgb == o.blendEqRgb && blendEqAlpha == o.blendEqAlpha
                && Arrays.equals(colorMask, o.colorMask) && depthMask == o.depthMask
                && depthFunc == o.depthFunc && stencilFunc == o.stencilFunc && stencilRef == o.stencilRef
                && stencilValueMask == o.stencilValueMask && stencilWriteMask == o.stencilWriteMask
                && stencilFail == o.stencilFail && stencilPassDepthFail == o.stencilPassDepthFail
                && stencilPassDepthPass == o.stencilPassDepthPass && frontFace == o.frontFace
                && backStencilFunc == o.backStencilFunc && backStencilRef == o.backStencilRef
                && backStencilValueMask == o.backStencilValueMask && backStencilWriteMask == o.backStencilWriteMask
                && backStencilFail == o.backStencilFail && backStencilPassDepthFail == o.backStencilPassDepthFail
                && backStencilPassDepthPass == o.backStencilPassDepthPass
                && Arrays.equals(blendColor, o.blendColor) && Arrays.equals(clearColor, o.clearColor)
                && Arrays.equals(polygonMode, o.polygonMode) && polygonOffsetFactor == o.polygonOffsetFactor
                && polygonOffsetUnits == o.polygonOffsetUnits
                && activeTexture == o.activeTexture && Arrays.equals(textures, o.textures)
                && Arrays.equals(samplers, o.samplers) && Arrays.equals(pixelStore, o.pixelStore)
                && unpackBuffer == o.unpackBuffer && packBuffer == o.packBuffer;
    }

    /** First differing field, for test messages; {@code null} when {@link #sameAs}. */
    public String firstDifference(GlStateSnapshot o) {
        if (program != o.program) return "program " + program + " vs " + o.program;
        if (vao != o.vao) return "vao";
        if (arrayBuffer != o.arrayBuffer) return "arrayBuffer";
        if (elementBuffer != o.elementBuffer) return "elementBuffer " + elementBuffer + " vs " + o.elementBuffer;
        if (drawFramebuffer != o.drawFramebuffer) return "drawFramebuffer " + drawFramebuffer + " vs " + o.drawFramebuffer;
        if (readFramebuffer != o.readFramebuffer) return "readFramebuffer";
        if (!Arrays.equals(viewport, o.viewport)) return "viewport";
        if (!Arrays.equals(scissor, o.scissor)) return "scissor";
        for (int i = 0; i < CAPS.length; i++) {
            if (caps[i] != o.caps[i]) return "capability 0x" + Integer.toHexString(CAPS[i]) + " " + caps[i] + " vs " + o.caps[i];
        }
        if (!Arrays.equals(textures, o.textures)) return "texture bindings";
        if (!Arrays.equals(samplers, o.samplers)) return "sampler bindings";
        if (activeTexture != o.activeTexture) return "active texture";
        if (!Arrays.equals(pixelStore, o.pixelStore)) return "pixel-store parameters";
        if (!Arrays.equals(polygonMode, o.polygonMode)) return "polygon mode";
        if (!Arrays.equals(blendColor, o.blendColor)) return "blend colour";
        if (!Arrays.equals(clearColor, o.clearColor)) return "clear colour";
        return sameAs(o) ? null : "blend/depth/stencil/mask/pixel-store configuration";
    }
}
