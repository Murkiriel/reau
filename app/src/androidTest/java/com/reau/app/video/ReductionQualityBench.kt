package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * How well the compositor's reduction of Android Auto's 800×480 picture to
 * the dash's 526×300 frame matches a high-quality reference, for the filter in use and for the variants. The encoder
 * is not in the path, so the emulator's different encoder does not matter; the graphics chip's filter does, and the
 * emulator draws with software OpenGL: what comes out here says how the filters compare, not how fast they are on the
 * phone. Frames are synthetic ([SyntheticAaFrames]).
 *
 * Writes `reduction_bench/relatorio.txt` and the pictures into the app's external files folder; run it alone:
 * `adb -s emulator-5554 shell am instrument -w -e class com.reau.app.video.ReductionQualityBench …`.
 */
@RunWith(AndroidJUnit4::class)
class ReductionQualityBench {
    private class Variant(val name: String, val filter: ReductionFilter)
    private class Fit(val name: String, val mapping: FrameFit.Mapping)

    private val variants = listOf(
        Variant("atual", ReductionFilter.CURRENT),
        Variant("suave", ReductionFilter(ReductionFilter.Kernel.SOFT)),
        Variant("cubico", ReductionFilter(ReductionFilter.Kernel.CUBIC)),
        Variant("atual+realce50", ReductionFilter(ReductionFilter.Kernel.BILINEAR, 0.5f)),
        Variant("suave+realce50", ReductionFilter(ReductionFilter.Kernel.SOFT, 0.5f)),
        Variant("suave+realce100", ReductionFilter(ReductionFilter.Kernel.SOFT, 1.0f)),
        Variant("cubico+realce30", ReductionFilter(ReductionFilter.Kernel.CUBIC, 0.3f)),
    )

    private val gl = CompositorGl()
    private val thread = HandlerThread("bench-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val pictureArrived = Semaphore(0)
    private val imageArrived = Semaphore(0)
    private var reader: ImageReader? = null
    private var input: Surface? = null

    private fun <T> onGl(block: () -> T): T {
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post {
            result = runCatching(block)
            done.countDown()
        }
        assertTrue("the graphics thread did not answer", done.await(30, TimeUnit.SECONDS))
        return checkNotNull(result).getOrThrow()
    }

    @After
    fun tearDown() {
        runCatching { onGl { gl.release() } }
        reader?.close()
        thread.quitSafely()
    }

    private fun outDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(checkNotNull(ctx.getExternalFilesDir(null)), "reduction_bench").apply { deleteRecursively(); mkdirs() }
    }

    private fun save(bitmap: Bitmap, file: File) = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun bitmapOf(p: ReductionMetrics.Rgb): Bitmap {
        val px = IntArray(p.w * p.h) { android.graphics.Color.rgb(p.r[it].toInt().coerceIn(0, 255), p.g[it].toInt().coerceIn(0, 255), p.b[it].toInt().coerceIn(0, 255)) }
        return Bitmap.createBitmap(px, p.w, p.h, Bitmap.Config.ARGB_8888)
    }

    /** Draws [frame] on the input, lets the compositor latch it, draws it with [mapping] and [filter], returns the frame. */
    private fun render(frame: Bitmap, mapping: FrameFit.Mapping, filter: ReductionFilter): ReductionMetrics.Rgb {
        pictureArrived.drainPermits()
        imageArrived.drainPermits()
        val surface = checkNotNull(input)
        val canvas = surface.lockCanvas(null)
        canvas.drawBitmap(frame, 0f, 0f, null)
        surface.unlockCanvasAndPost(canvas)
        assertTrue("no picture arrived at the compositor", pictureArrived.tryAcquire(10, TimeUnit.SECONDS))
        onGl {
            check(gl.latch()) { "latch failed" }
            gl.reductionFilter = filter
            gl.draw(CompositorMath.texWindow(mapping.source, SyntheticAaFrames.W, SyntheticAaFrames.H), CompositorMath.viewport(mapping.dest))
            check(gl.post(stampMs++)) { "post failed" }
        }
        assertTrue("no frame came out", imageArrived.tryAcquire(10, TimeUnit.SECONDS))
        val image = checkNotNull(reader).acquireLatestImage() ?: error("no image")
        try {
            val plane = image.planes[0]
            val out = ReductionMetrics.Rgb(DashGlass.FRAME_W, DashGlass.FRAME_H)
            val buf = plane.buffer
            for (y in 0 until DashGlass.FRAME_H) {
                for (x in 0 until DashGlass.FRAME_W) {
                    val o = y * plane.rowStride + x * plane.pixelStride
                    out.r[y * DashGlass.FRAME_W + x] = (buf.get(o).toInt() and 0xFF).toFloat()
                    out.g[y * DashGlass.FRAME_W + x] = (buf.get(o + 1).toInt() and 0xFF).toFloat()
                    out.b[y * DashGlass.FRAME_W + x] = (buf.get(o + 2).toInt() and 0xFF).toFloat()
                }
            }
            return out
        } finally {
            image.close()
        }
    }

    private var stampMs = 1L

    @Test
    fun theReductionAgainstALanczosReference() {
        val dir = outDir()
        val report = StringBuilder()
        fun say(s: String) {
            report.appendLine(s)
            android.util.Log.i("ReductionBench", s)
        }

        val imageReader = ImageReader.newInstance(DashGlass.FRAME_W, DashGlass.FRAME_H, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ imageArrived.release() }, handler)
        reader = imageReader
        onGl {
            gl.start()
            input = gl.createInput(SyntheticAaFrames.W, SyntheticAaFrames.H, handler) { pictureArrived.release() }
            gl.attachOutput(imageReader.surface)
        }

        val content = RectPx(0, 0, SyntheticAaFrames.W, SyntheticAaFrames.H)
        val fits = listOf(
            Fit("recorte74", FrameFit.cropAt(content, 74, null, null)),
            Fit("recorte80", FrameFit.cropAt(content, 80, null, null)),
            Fit("cupula", FrameFit.fillDome(content)),
            Fit("inteira", FrameFit.wholeVisible(content)),
        )
        say("Banco de qualidade da redução (quadros sintéticos 800x480 -> 526x300; emulador, OpenGL por software)")
        say("Colunas: PSNR dB / SSIM / nitidez (1,00 = como a referência Lanczos-3)")

        val scenes = SyntheticAaFrames.all()
        val sums = HashMap<String, DoubleArray>() // "fit|variant" -> psnr, ssim, sharp, n
        for (scene in scenes) {
            save(scene.bitmap, File(dir, "${scene.name}_origem.png"))
            val src = ReductionMetrics.toRgb(scene.bitmap)
            for (fit in fits) {
                val m = fit.mapping
                val ref = ReductionMetrics.lanczosReduce(
                    src, m.source.x.toDouble(), m.source.y.toDouble(), m.source.w.toDouble(), m.source.h.toDouble(), m.dest.w, m.dest.h,
                )
                // the reference sits at the destination's place in a frame-sized picture, black elsewhere
                val refFrame = ReductionMetrics.Rgb(DashGlass.FRAME_W, DashGlass.FRAME_H)
                for (y in 0 until m.dest.h) for (x in 0 until m.dest.w) {
                    val to = (m.dest.y + y) * DashGlass.FRAME_W + m.dest.x + x
                    refFrame.r[to] = ref.r[y * m.dest.w + x]
                    refFrame.g[to] = ref.g[y * m.dest.w + x]
                    refFrame.b[to] = ref.b[y * m.dest.w + x]
                }
                val refLuma = ReductionMetrics.luma(refFrame)
                if (fit.name == "recorte74" || fit.name == "cupula") save(bitmapOf(refFrame), File(dir, "${scene.name}_${fit.name}_referencia.png"))
                say("")
                say("${scene.name} / ${fit.name} (escala %.3f)".format(java.util.Locale.ROOT, m.scale))
                for (v in variants) {
                    val got = render(scene.bitmap, m, v.filter)
                    val score = ReductionMetrics.compare(
                        ReductionMetrics.luma(got), refLuma, DashGlass.FRAME_W, m.dest.x, m.dest.y, m.dest.x + m.dest.w, m.dest.y + m.dest.h,
                    )
                    if (fit.name == "recorte74" || fit.name == "cupula") save(bitmapOf(got), File(dir, "${scene.name}_${fit.name}_${v.name}.png"))
                    say("  %-17s %5.2f dB  %.4f  %.2f".format(java.util.Locale.ROOT, v.name, score.psnr, score.ssim, score.sharpness))
                    val acc = sums.getOrPut("${fit.name}|${v.name}") { DoubleArray(4) }
                    acc[0] += score.psnr; acc[1] += score.ssim; acc[2] += score.sharpness; acc[3] += 1.0
                }
            }
        }

        say("")
        say("MÉDIA DOS ${scenes.size} QUADROS")
        for (fit in fits) {
            say("${fit.name} (escala %.3f)".format(java.util.Locale.ROOT, fit.mapping.scale))
            for (v in variants) {
                val a = checkNotNull(sums["${fit.name}|${v.name}"])
                say("  %-17s %5.2f dB  %.4f  %.2f".format(java.util.Locale.ROOT, v.name, a[0] / a[3], a[1] / a[3], a[2] / a[3]))
            }
        }

        // time per frame, for what the emulator's software OpenGL says about the cost ratio between filters
        say("")
        say("TEMPO DE DESENHO (emulador, só a razão importa; o custo no celular vem do registro: composição em ms)")
        val frame = scenes[0].bitmap
        for (v in variants) {
            val t0 = System.nanoTime()
            repeat(TIMED_FRAMES) { render(frame, fits[0].mapping, v.filter) }
            say("  %-17s %.1f ms por quadro".format(java.util.Locale.ROOT, v.name, (System.nanoTime() - t0) / 1e6 / TIMED_FRAMES))
        }

        File(dir, "relatorio.txt").writeText(report.toString())

        // The numbers mean something only if the geometry is right: the plain filter must be close to the reference.
        val current = checkNotNull(sums["recorte74|atual"])
        assertTrue("the current filter is far from the reference (geometry?): ${current[1] / current[3]}", current[1] / current[3] > 0.6)
    }

    private companion object {
        const val TIMED_FRAMES = 12
    }
}
