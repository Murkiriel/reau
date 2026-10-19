package com.reau.app.service

import com.reau.app.aa.AaLinkState

/**
 * The network server rule, as the machine applies it: with that server (the browser's, "Ver no navegador") on, REau
 * does not look for the dash, since the point is to run Android Auto on something that is not the dash. Switched on while the
 * projection runs, the video and the dash go (and what the let-go session still reports is not heard, [ProjectionMachine]'s
 * dashChanged); switched off, the dash is looked for again.
 */
internal object ProjectionNetworkRule {
    /**
     * The network server switched [on] or off. Off: the dash is looked for again if Android Auto is up, else when it comes up. The
     * card mode has no network server; while preparing, the choice is only kept.
     */
    fun switched(state: Projection, on: Boolean): Step = when {
        state.phase == Phase.STOPPED || state.card || on == state.networkServer -> Step(state, emptyList())
        state.phase == Phase.PREPARING -> Step(state.copy(networkServer = on), emptyList())
        on -> Step(
            state.copy(networkServer = true, dash = DashLink.OFF, dashRetries = 0, dashGoneAtMs = null),
            listOfNotNull(
                ProjectionEffect.StopVideo.takeIf { state.dash == DashLink.READY },
                ProjectionEffect.StopDash.takeIf { state.dash != DashLink.OFF },
            ),
        )
        state.androidAuto == AaLinkState.ACTIVE ->
            Step(state.copy(networkServer = false, dash = DashLink.CONNECTING), listOf(ProjectionEffect.StartDash))
        else -> Step(state.copy(networkServer = false), emptyList())
    }

    /** Android Auto's side changed to [aa]: its session up with the dash waiting for it starts the dash, unless the network server is on. */
    fun androidAutoChanged(state: Projection, aa: AaLinkState): Step = when {
        state.phase != Phase.RUNNING -> Step(state, emptyList())
        aa == AaLinkState.ACTIVE && state.dash == DashLink.OFF && !state.networkServer ->
            Step(state.copy(androidAuto = aa, dash = DashLink.CONNECTING), listOf(ProjectionEffect.StartDash))
        else -> Step(state.copy(androidAuto = aa), emptyList())
    }
}
