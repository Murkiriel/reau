package com.reau.app.ui.components

import androidx.annotation.DrawableRes

/** One side of the bridge: its label (ANDROID AUTO, IMAGEM, PAINEL), what it says now, its state and the test's tag. */
data class BridgeSide(val label: String, val value: String, val kind: StatusKind, val tag: String)

/** A button of a card or a row: what it says, its test tag and what it does. */
data class CardAction(val label: String, val tag: String, val onClick: () -> Unit)

/**
 * The explanation behind a row's (i): the sheet's title and its text. The text gives the general idea in at most 3 sentences and 100 words,
 * in each language (InfoTextsTest).
 */
data class Info(val title: String, val text: String)

/** A row's button (Compartilhar, Apagar...): [danger] ones are outlined in red and always ask first. */
data class RowAction(val label: String, val tag: String, @param:DrawableRes val icon: Int? = null, val danger: Boolean = false, val onClick: () -> Unit)
