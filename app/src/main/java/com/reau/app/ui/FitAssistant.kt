package com.reau.app.ui

import com.reau.app.aa.AaScreen
import com.reau.app.settings.Fit
import com.reau.app.settings.FitDefaults
import com.reau.app.settings.REauSettings

/** The counters of a fit, in the order the fit shows them: the crop's size and place, or the picture's move. */
internal enum class FitControl {
    CROP_SIZE,
    CROP_X,
    CROP_Y,
    MOVE_X,
    MOVE_Y,
    ;

    companion object {
        /**
         * The counters a fit of mode [fit] has: the crop's size and place, or the move. The move is not offered on the crop, which already
         * fills the dash's frame (FrameFit.moved has nowhere to take it).
         */
        fun of(fit: Fit): List<FitControl> = when (fit) {
            Fit.CROP -> listOf(CROP_SIZE, CROP_X, CROP_Y)
            Fit.WHOLE_VISIBLE -> listOf(MOVE_X, MOVE_Y)
        }
    }
}

/**
 * The first-time fit assistant of the "Tela do Android Auto" (13/11/2026): four short steps over the navigation apps' fit, for a rider whose
 * Android Auto does not draw the maneuver and the speed where the owner's did. It asks what the rider sees in the picture above (the part
 * the mask leaves clear is what reaches the dash's glass) and, on a "no", shows the counters of that step, which are the fit's own
 * ([FitControl]): it sets nothing by itself and has no fit of its own. Whether it opens by itself is the store's to say
 * ([com.reau.app.settings.SettingsStore.fitAssistantPending]); the button "Assistente de encaixe" opens it by hand.
 */
internal object FitAssistant {
    /** The steps, in order; [asks]: the step is a question with the counters behind its "no". */
    enum class Step(val asks: Boolean) {
        START(false),
        MANEUVER(true),
        SPEED(true),
        END(false),
    }

    /** Where the assistant is: its [step] and, on a step that asks, whether the rider said "no" and is [adjusting]. */
    data class State(val step: Step = Step.START, val adjusting: Boolean = false) {
        /** The next step ("Começar", "Sim", "Ficou bom"), its question first; the last step stays. */
        fun forward(): State = State(Step.entries[(step.ordinal + 1).coerceAtMost(Step.entries.lastIndex)])

        /** "Não, ajustar": the counters of this step; a step that asks nothing has none. */
        fun adjust(): State = copy(adjusting = step.asks)

        /** "Voltar": from the counters to the question of the same step, else to the step before; the first step stays. */
        fun back(): State = if (adjusting) copy(adjusting = false) else State(Step.entries[(step.ordinal - 1).coerceAtLeast(0)])
    }

    /**
     * The counters [step] offers on a fit of mode [fit]. The maneuver is a matter of place (the crop's place); the speed, of how much of
     * the picture is taken (the crop's size) and of its height, the side having been settled with the maneuver. A whole picture has only its move.
     */
    fun controls(step: Step, fit: Fit): List<FitControl> = when (step) {
        Step.START, Step.END -> emptyList()
        Step.MANEUVER -> FitControl.of(fit) - FitControl.CROP_SIZE
        Step.SPEED -> FitControl.of(fit) - FitControl.CROP_X
    }

    /** What "Começar" changes: the dash's mask on, so the picture shows what reaches the glass; nothing of the fit. */
    fun started(s: REauSettings): REauSettings = if (s.dashMask) s else s.copy(dashMask = true)

    /** Whether Android Auto shows a navigation app now, which is the screen the assistant's questions are about. */
    fun navigationShown(screen: AaScreen): Boolean = screen == AaScreen.NAV

    /** Whether the factory fit was measured at the density [s] asks of Android Auto ([FitDefaults]); with another, the rider is told. */
    fun densityMeasured(s: REauSettings): Boolean = FitDefaults.measured(s.density) != null
}

/**
 * The fit assistant as the "Tela do Android Auto" is given it: [pending], it opens by itself (a phone that never saved a fit); [seen] is
 * called when the rider finishes or skips it, so it does not open by itself again.
 */
class FitAssistantAsk(val pending: Boolean = false, val seen: () -> Unit = {})
