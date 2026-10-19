package com.reau.app.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.reau.app.R
import com.reau.app.aa.ServerMenuRun
import com.reau.app.ui.components.ComponentLogic
import com.reau.app.ui.theme.Grafite
import com.reau.app.ui.theme.Marfim
import com.reau.app.ui.theme.Ouro
import com.reau.app.ui.theme.OuroSuave
import com.reau.app.ui.theme.StatusSucesso

/**
 * The opaque cover over the walk through Android Auto's menu, as the redesign draws it: Grafite,
 * a ring turning once every 1.1 s (still with Android's "remove animations") around REau's icon, what is going on
 * ([setText]), the walk's three steps with where it is ([setStep], [ServerMenuRun.coverStep]), a line saying nothing
 * needs a touch, and Cancelar ([onCancel]). Plain Views: it lives in an accessibility window, never focusable.
 */
@SuppressLint("ViewConstructor") // built in code only, by the accessibility service: never inflated from a layout
class ServerCoverView(context: Context, mode: ServerMenuRun.Mode, onCancel: () -> Unit) : FrameLayout(context) {
    private val label: TextView
    private val steps: List<Pair<ImageView, TextView>>
    private val ring = Ring(context)

    init {
        setBackgroundColor(Grafite.toArgb())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val middle = FrameLayout(context).apply {
            addView(ring, LayoutParams(dp(RING_DP), dp(RING_DP)))
            addView(ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }, LayoutParams(dp(ICON_DP), dp(ICON_DP), Gravity.CENTER))
        }
        column.addView(middle, LinearLayout.LayoutParams(dp(RING_DP), dp(RING_DP)))
        label = TextView(context).apply {
            setTextColor(Marfim.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            typeface = ResourcesCompat.getFont(context, R.font.hanken_grotesk_500)
            gravity = Gravity.CENTER
            tag = "cover_text"
        }
        column.addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), withAlpha(OuroSuave.toArgb(), BOX_BORDER_ALPHA))
            }
        }
        val third = if (mode == ServerMenuRun.Mode.STOP) R.string.cover_step_stop else R.string.cover_step_start
        steps = listOf(R.string.cover_step_open, R.string.cover_step_menu, third).mapIndexed { i, text -> stepRow(box, text, i) }
        column.addView(box, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        column.addView(TextView(context).apply {
            setText(R.string.cover_hint)
            setTextColor(OuroSuave.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_SP)
            typeface = ResourcesCompat.getFont(context, R.font.hanken_grotesk_400)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(cancelButton(onCancel), LayoutParams(LayoutParams.MATCH_PARENT, dp(CANCEL_DP), Gravity.BOTTOM).apply {
            setMargins(dp(24), 0, dp(24), dp(32))
        })
        setStep(0)
    }

    fun setText(text: Int) = label.setText(text)

    /** How much of the ring is lit (degrees), and whether it is green: for the tests. */
    internal val ringSweep: Float get() = ring.sweep
    internal val ringDone: Boolean get() = ring.done

    /**
     * [step] of 0..3: the steps before it done (green tick), it the current one (Ouro), the rest to come. The ring fills
     * with them (with Android's animations off, a ring that only turned stood still at a quarter, whatever the steps
     * said).
     */
    fun setStep(step: Int) {
        ring.show(step)
        steps.forEachIndexed { i, (icon, _) ->
            val (res, color, said) = when {
                i < step -> Triple(R.drawable.ic_ms_check_circle, StatusSucesso.toArgb(), DONE)
                i == step -> Triple(R.drawable.ic_ms_radio_button_checked, Ouro.toArgb(), NOW)
                else -> Triple(R.drawable.ic_ms_radio_button_unchecked, OuroSuave.toArgb(), LATER)
            }
            icon.setImageResource(res)
            icon.imageTintList = ColorStateList.valueOf(color)
            icon.tag = said
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (ComponentLogic.animationsOn(scale)) ring.spin()
    }

    override fun onDetachedFromWindow() {
        ring.stop()
        super.onDetachedFromWindow()
    }

    private fun stepRow(box: LinearLayout, text: Int, index: Int): Pair<ImageView, TextView> {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(context)
        val words = TextView(context).apply {
            setText(text)
            setTextColor(Marfim.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, STEP_SP)
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_400)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(STEP_ICON_DP), dp(STEP_ICON_DP)))
        row.addView(words, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        row.tag = "cover_step_$index"
        box.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (index > 0) topMargin = dp(10) })
        return icon to words
    }

    private fun cancelButton(onCancel: () -> Unit) = Button(context).apply {
        setText(R.string.aa_server_cover_cancel)
        isAllCaps = false
        setTextColor(Ouro.toArgb())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CANCEL_SP)
        typeface = ResourcesCompat.getFont(context, R.font.hanken_grotesk_600)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Ouro.toArgb())
        }
        stateListAnimator = null
        tag = "cover_cancel"
        setOnClickListener { onCancel() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * The progress ring: a faint Ouro suave circle, lit in Ouro a third more at each step, all green once every step is
     * done; turning, unless Android's animations are off.
     */
    private class Ring(context: Context) : View(context) {
        private val stroke = RING_STROKE_DP * context.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = withAlpha(OuroSuave.toArgb(), TRACK_ALPHA)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Ouro.toArgb()
        }
        private val box = RectF()
        private var animator: ValueAnimator? = null
        var sweep = STEP_SWEEP
            private set
        var done = false
            private set

        fun show(step: Int) {
            sweep = STEP_SWEEP * (step + 1).coerceAtMost(STEPS)
            done = step >= STEPS
            arc.color = if (done) StatusSucesso.toArgb() else Ouro.toArgb()
            invalidate()
        }

        fun spin() {
            animator = ValueAnimator.ofFloat(0f, FULL_TURN).apply {
                duration = SPIN_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { rotation = it.animatedValue as Float }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            val half = stroke / 2
            box.set(half, half, width - half, height - half)
            canvas.drawOval(box, track)
            canvas.drawArc(box, START_ANGLE, sweep, false, arc)
        }
    }

    companion object {
        const val DONE = "feito"
        const val NOW = "agora"
        const val LATER = "falta"

        private const val RING_DP = 120
        private const val ICON_DP = 76
        private const val RING_STROKE_DP = 3
        private const val CANCEL_DP = 56
        private const val STEP_ICON_DP = 18
        private const val TEXT_SP = 20f
        private const val HINT_SP = 13f
        private const val STEP_SP = 12.5f
        private const val CANCEL_SP = 16f
        private const val SPIN_MS = 1100L
        private const val FULL_TURN = 360f
        private const val START_ANGLE = -90f
        private const val STEPS = 3
        private const val STEP_SWEEP = 120f
        private const val TRACK_ALPHA = 0.18f
        private const val BOX_BORDER_ALPHA = 0.22f

        private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
    }
}
