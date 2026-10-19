package com.reau.app.dash.video

import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface

/**
 * Test helper: draws on an encoder's input surface with OpenGL, the way the compositor will, stamping each frame
 * with its presentation time. Everything runs on the thread that created it (an EGL context belongs to a thread).
 */
class EglPainter(surface: Surface) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val wanted = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no recordable EGL config on this device"
        }
        val config: EGLConfig = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    /** Fills the frame with [color] and posts it with presentation time [ptsMs]. */
    fun paint(color: Int, ptsMs: Long) {
        GLES20.glClearColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsMs * 1_000_000L)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
