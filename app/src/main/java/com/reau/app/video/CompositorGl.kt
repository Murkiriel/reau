// Derived from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt, commit 0abbe2a.
// Kept from it: the EGL setup (recordable RGBA8888 config, ES2 context, a 1x1 pbuffer to stay current before there
// is an output), the external-texture SurfaceTexture input, the quad, the shader program and its build helper.
// Changes: split from the compositor's thread and clock (DashCompositor.kt); the shader samples a free window of
// the source (uWindow) instead of a centred crop (uCrop); one output only (the phone preview target is cut); the
// presentation time is given by the caller instead of System.nanoTime(); failures throw instead of being ignored;
// the phone preview came back, drawn after the encoder's frame with no vsync wait;
// eglTerminate is not called (the display is shared by the whole process).
package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The OpenGL side of [DashCompositor]: an EGL context, the texture Android Auto's decoder renders into, and one
 * output surface (the encoder's) to draw a window of that texture on. The standard SurfaceTexture→encoder pattern
 * (Grafika), by way of OpenCfMoto's AaCompositor.
 *
 * No thread of its own: every method must be called on the one thread that called [start].
 */
// One function per OpenGL step (EGL setup, program, input, the dash's output, the phone's preview, release): split
// further, they would only pass the same EGL handles around.
@Suppress("TooManyFunctions")
internal class CompositorGl {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE // keeps a current surface before output exists
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE // the phone's own screen, while it shows Android Auto
    private var webSurface: EGLSurface = EGL14.EGL_NO_SURFACE // the web viewers' encoder, while a browser watches

    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    /** How the picture is shrunk. The stream only uses the default; the others are for the bench. */
    var reductionFilter: ReductionFilter = ReductionFilter.CURRENT

    // The variants of the reduction filter: one more program, built the first time one is asked for.
    private var filterProgram = 0
    private var inputW = 1
    private var inputH = 1

    // The stats text over the dash's frame (StatsOverlay): its own small program and a plain 2-D texture.
    private var overlayProgram = 0
    private var overlayPosition = 0
    private var overlayTexCoord = 0
    private var overlayTexture = 0
    private var overlayW = 0
    private var overlayH = 0

    // The "picture stuck" badge (StaleBadgeInk): the same program, a texture of its own.
    private var badgeTexture = 0
    private var badgeW = 0
    private var badgeH = 0
    private val overlayQuad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    val hasOutput: Boolean get() = windowSurface != EGL14.EGL_NO_SURFACE

    // Full-screen quad (triangle strip): pos.xy + tex.uv interleaved.
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
            position(0)
        }

    /** Brings up EGL and the shader. Throws [IllegalStateException] with the step that failed. */
    fun start() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, numConfig, 0) && numConfig[0] > 0) {
            "no recordable EGL config"
        }
        eglConfig = configs[0]
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        // 1x1 pbuffer so the context can be current before we have an output window surface.
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) { "eglMakeCurrent failed" }
        initProgram()
    }

    private fun initProgram() {
        val vs = VERTEX_SOURCE
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec2 vPicture;
            uniform samplerExternalOES sTexture;
            void main() {
                // A window past the picture's edge shows black there, not the edge's pixels smeared
                if (vPicture.x < 0.0 || vPicture.x > 1.0 || vPicture.y < 0.0 || vPicture.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            }
        """.trimIndent()
        program = buildProgram(vs, fs)
        Matrix.setIdentityM(texMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vsSrc))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fsSrc))
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    /**
     * Creates the surface the decoder renders into, for pictures of [width]×[height]. [onPicture] is called on
     * [handler]'s thread (which must be this object's thread) for every picture that arrives.
     */
    fun createInput(width: Int, height: Int, handler: Handler, onPicture: () -> Unit): Surface {
        val texture = SurfaceTexture(textureId)
        texture.setDefaultBufferSize(width, height)
        inputW = width
        inputH = height
        texture.setOnFrameAvailableListener({ onPicture() }, handler)
        surfaceTexture = texture
        return Surface(texture).also { inputSurface = it }
    }

    /** The input's size for a producer that does not give its own (the decoder does; the card's canvas does not). */
    fun resizeInput(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
        inputW = width
        inputH = height
    }

    /**
     * The picture of the stats text ([StatsOverlayInk.render]), or null for none; copied into a texture at once, so the
     * caller may recycle it. Drawn by [drawOverlay].
     */
    fun setOverlay(bitmap: Bitmap?) {
        if (bitmap == null) {
            overlayW = 0
            overlayH = 0
            return
        }
        if (overlayProgram == 0) initOverlay()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        overlayW = bitmap.width
        overlayH = bitmap.height
    }

    /** The picture of the "picture stuck" badge ([StaleBadgeInk.render]), or null for none; copied at once, as [setOverlay] does. */
    fun setBadge(bitmap: Bitmap?) {
        if (bitmap == null) {
            badgeW = 0
            badgeH = 0
            return
        }
        if (overlayProgram == 0) initOverlay()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, badgeTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        badgeW = bitmap.width
        badgeH = bitmap.height
    }

    private fun initOverlay() {
        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()
        overlayProgram = buildProgram(vs, fs)
        overlayPosition = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        overlayTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        overlayTexture = ids[0]
        badgeTexture = ids[1]
        for (texture in ids) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    /**
     * Lays the stats text over what [draw] drew on the output, its box's top-left corner at ([x], [y]) in the dash's
     * frame (rows counted from the top, as they are seen). Nothing without a picture from [setOverlay]. The
     * bitmap is premultiplied, so it blends as (1, 1 − alpha).
     */
    fun drawOverlay(x: Int, y: Int) = drawQuad(overlayTexture, overlayW, overlayH, x, y)

    /** Lays the "picture stuck" badge at the bottom centre, over what [draw] drew (and over the stats text, if it is there). Nothing without a picture from [setBadge]. */
    fun drawBadge() {
        val (x, y) = StaleBadgeInk.corner(badgeW, badgeH)
        drawQuad(badgeTexture, badgeW, badgeH, x, y)
    }

    private fun drawQuad(texture: Int, width: Int, height: Int, x: Int, y: Int) {
        if (width == 0 || height == 0) return
        val w = DashGlass.FRAME_W.toFloat()
        val h = DashGlass.FRAME_H.toFloat()
        val left = x / w * 2f - 1f
        val right = (x + width) / w * 2f - 1f
        val top = 1f - y / h * 2f
        val bottom = 1f - (y + height) / h * 2f
        overlayQuad.clear()
        overlayQuad.put(floatArrayOf(left, bottom, 0f, 1f, right, bottom, 1f, 1f, left, top, 0f, 0f, right, top, 1f, 0f))
        GLES20.glViewport(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H)
        GLES20.glUseProgram(overlayProgram)
        overlayQuad.position(0)
        GLES20.glVertexAttribPointer(overlayPosition, 2, GLES20.GL_FLOAT, false, QUAD_STRIDE, overlayQuad)
        GLES20.glEnableVertexAttribArray(overlayPosition)
        overlayQuad.position(2)
        GLES20.glVertexAttribPointer(overlayTexCoord, 2, GLES20.GL_FLOAT, false, QUAD_STRIDE, overlayQuad)
        GLES20.glEnableVertexAttribArray(overlayTexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(overlayPosition)
        GLES20.glDisableVertexAttribArray(overlayTexCoord)
    }

    /** Takes the newest picture into the texture. False if there was none to take or the surface is gone. */
    fun latch(): Boolean = runCatching { checkNotNull(surfaceTexture).updateTexImage() }.isSuccess

    /** Makes [encoderSurface] the output, letting go of the previous one first. */
    fun attachOutput(encoderSurface: Surface) {
        detachOutput()
        windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(hasOutput) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun detachOutput() {
        if (!hasOutput) return
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
        EGL14.eglDestroySurface(eglDisplay, windowSurface)
        windowSurface = EGL14.EGL_NO_SURFACE
    }

    val hasPreview: Boolean get() = previewSurface != EGL14.EGL_NO_SURFACE

    /** Makes [surface] (the phone's preview screen) a second output, letting go of the previous one first. */
    fun attachPreview(surface: Surface) {
        detachPreview()
        previewSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(hasPreview) { "eglCreateWindowSurface (preview) failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun detachPreview() {
        if (!hasPreview) return
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
        EGL14.eglDestroySurface(eglDisplay, previewSurface)
        previewSurface = EGL14.EGL_NO_SURFACE
    }

    /**
     * Draws the whole latched picture into [viewport] of the [canvasW]×[canvasH] preview, on black, and shows it.
     * The swap does not wait for the phone's vsync (interval 0), so it never holds the dash's next frame back.
     * False if the preview surface refused it (the screen went away).
     */
    fun drawPreview(canvasW: Int, canvasH: Int, viewport: RectPx): Boolean {
        if (!EGL14.eglMakeCurrent(eglDisplay, previewSurface, previewSurface, eglContext)) return false
        EGL14.eglSwapInterval(eglDisplay, 0)
        GLES20.glViewport(0, 0, canvasW, canvasH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(WHOLE_PICTURE, viewport)
        return EGL14.eglSwapBuffers(eglDisplay, previewSurface)
    }

    val hasWeb: Boolean get() = webSurface != EGL14.EGL_NO_SURFACE

    /** Makes [surface] (the web encoder's input) one more output, letting go of the previous one first. */
    fun attachWeb(surface: Surface) {
        detachWeb()
        webSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(hasWeb) { "eglCreateWindowSurface (web) failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun detachWeb() {
        if (!hasWeb) return
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
        EGL14.eglDestroySurface(eglDisplay, webSurface)
        webSurface = EGL14.EGL_NO_SURFACE
    }

    /**
     * Draws the whole latched picture over the [canvasW]×[canvasH] web surface and posts it to its encoder with [stampNs].
     * The swap does not wait for any vsync. False if the surface refused it (the encoder is gone).
     */
    fun drawWeb(canvasW: Int, canvasH: Int, stampNs: Long): Boolean {
        if (!EGL14.eglMakeCurrent(eglDisplay, webSurface, webSurface, eglContext)) return false
        EGL14.eglSwapInterval(eglDisplay, 0)
        GLES20.glViewport(0, 0, canvasW, canvasH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(WHOLE_PICTURE, RectPx(0, 0, canvasW, canvasH))
        EGLExt.eglPresentationTimeANDROID(eglDisplay, webSurface, stampNs)
        return EGL14.eglSwapBuffers(eglDisplay, webSurface)
    }

    /**
     * Clears the output to black and, with a [window], draws that part of the latched picture into [viewport]
     * (OpenGL coordinates). Nothing is shown until [post].
     */
    fun draw(window: CompositorMath.TexWindow?, viewport: RectPx) {
        EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
        GLES20.glViewport(0, 0, DashGlass.FRAME_W, DashGlass.FRAME_H)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (window == null) return
        drawQuad(window, viewport)
    }

    private fun drawQuad(window: CompositorMath.TexWindow, viewport: RectPx) {
        surfaceTexture?.getTransformMatrix(texMatrix)
        GLES20.glViewport(viewport.x, viewport.y, viewport.w, viewport.h)
        val variant = reductionFilter.takeUnless { it.isCurrent }
        val prog = if (variant != null) filterProgram() else program
        GLES20.glUseProgram(prog)
        val position = GLES20.glGetAttribLocation(prog, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(prog, "aTexCoord")

        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, QUAD_STRIDE, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, QUAD_STRIDE, quad)
        GLES20.glEnableVertexAttribArray(texCoord)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uWindow"), window.offsetU, window.offsetV, window.scaleU, window.scaleV)
        if (variant != null) {
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uKernel"), variant.kernel.ordinal)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSharpen"), variant.sharpen)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uSrcSize"), inputW.toFloat(), inputH.toFloat())
            // One destination pixel, in the picture's own 0..1 coordinates: the footprint the filter averages over.
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(prog, "uDestStep"),
                window.scaleU / viewport.w * kotlin.math.abs(texMatrix[0]),
                window.scaleV / viewport.h * kotlin.math.abs(texMatrix[5]),
            )
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun filterProgram(): Int {
        if (filterProgram == 0) filterProgram = buildProgram(VERTEX_SOURCE, FILTER_FRAGMENT_SOURCE)
        return filterProgram
    }

    /** Hands the drawn frame to the encoder with presentation time [stampMs]. False if the surface refused it. */
    fun post(stampMs: Long): Boolean {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, stampMs * NS_PER_MS)
        return EGL14.eglSwapBuffers(eglDisplay, windowSurface)
    }

    fun release() {
        runCatching { detachWeb() }
        runCatching { detachPreview() }
        runCatching { detachOutput() }
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        pbuffer = EGL14.EGL_NO_SURFACE
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    private companion object {
        val VERTEX_SOURCE = """
            uniform mat4 uTexMatrix;
            uniform vec4 uWindow;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            varying vec2 vPicture;
            void main() {
                gl_Position = aPosition;
                // uWindow = (offsetU, offsetV, scaleU, scaleV): the source rectangle inside the picture, rows
                // counted from the bottom. SurfaceTexture's own matrix then takes care of its flip and crop.
                float u = uWindow.x + aTexCoord.x * uWindow.z;
                float v = uWindow.y + aTexCoord.y * uWindow.w;
                vTexCoord = (uTexMatrix * vec4(u, v, 0.0, 1.0)).xy;
                vPicture = vec2(u, v); // where in Android Auto's picture, 0 to 1 inside it
            }
        """.trimIndent()

        // The reduction variants (ReductionFilter). highp: with half-precision coordinates an 800-pixel texture is
        // sampled a few hundredths of a pixel off, which a sharp filter shows.
        val FILTER_FRAGMENT_SOURCE = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            varying vec2 vPicture;
            uniform samplerExternalOES sTexture;
            uniform int uKernel;       // 0 bilinear, 1 soft, 2 cubic
            uniform float uSharpen;
            uniform vec2 uSrcSize;     // the picture, in its own pixels
            uniform vec2 uDestStep;    // one destination pixel, in the picture's 0..1 coordinates

            vec4 catmullRom(float f) {
                float f2 = f * f;
                float f3 = f2 * f;
                return vec4(-0.5 * f3 + f2 - 0.5 * f, 1.5 * f3 - 2.5 * f2 + 1.0, -1.5 * f3 + 2.0 * f2 + 0.5 * f, 0.5 * f3 - 0.5 * f2);
            }

            float pick(vec4 w, int i) {
                return i == 0 ? w.x : (i == 1 ? w.y : (i == 2 ? w.z : w.w));
            }

            vec4 shrink(vec2 uv) {
                if (uKernel == 1) {
                    vec2 s = uDestStep * 0.5;
                    vec4 sum = vec4(0.0);
                    for (int j = -1; j <= 1; j++) {
                        for (int i = -1; i <= 1; i++) {
                            float w = (i == 0 ? 2.0 : 1.0) * (j == 0 ? 2.0 : 1.0);
                            sum += w * texture2D(sTexture, uv + vec2(float(i), float(j)) * s);
                        }
                    }
                    return sum / 16.0;
                }
                if (uKernel == 2) {
                    vec2 p = uv * uSrcSize - 0.5;
                    vec2 base = floor(p);
                    vec2 f = p - base;
                    vec4 wx = catmullRom(f.x);
                    vec4 wy = catmullRom(f.y);
                    vec4 sum = vec4(0.0);
                    for (int j = 0; j < 4; j++) {
                        for (int i = 0; i < 4; i++) {
                            vec2 q = (base + vec2(float(i) - 1.0, float(j) - 1.0) + 0.5) / uSrcSize;
                            sum += pick(wx, i) * pick(wy, j) * texture2D(sTexture, q);
                        }
                    }
                    return sum;
                }
                return texture2D(sTexture, uv);
            }

            void main() {
                if (vPicture.x < 0.0 || vPicture.x > 1.0 || vPicture.y < 0.0 || vPicture.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec4 c = shrink(vTexCoord);
                if (uSharpen > 0.0) {
                    // Unsharp mask over the four neighbours, one destination pixel away.
                    vec4 blur = 0.25 * (shrink(vTexCoord + vec2(uDestStep.x, 0.0)) + shrink(vTexCoord - vec2(uDestStep.x, 0.0))
                        + shrink(vTexCoord + vec2(0.0, uDestStep.y)) + shrink(vTexCoord - vec2(0.0, uDestStep.y)));
                    c = c + uSharpen * (c - blur);
                }
                gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        const val NS_PER_MS = 1_000_000L
        const val QUAD_STRIDE = 16
        val WHOLE_PICTURE = CompositorMath.TexWindow(0f, 0f, 1f, 1f)
    }
}
