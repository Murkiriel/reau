package com.reau.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.components.CardAction
import com.reau.app.ui.components.ExpandableBlock
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.RoundIcon
import com.reau.app.ui.components.SupportButton
import com.reau.app.ui.components.hairline

/**
 * "O REau e os seus dados": what REau is and does with the rider's data, the text written in answer to whether its
 * privacy claim was true, checked against the code. Three lines first, then the parts folded but the first, every
 * text whole and short.
 */
@Composable
fun AboutDataScreen(onBack: () -> Unit, onOpenCredits: () -> Unit = {}, onOpenTerms: () -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf(setOf(PARTS.first().tag)) }
    val allOpen = open.size == PARTS.size
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.about_data_title), onBack, titleTag = "about_data_title")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(Modifier.semantics(mergeDescendants = true) {}.testTag("about_summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Summary(R.drawable.ic_ms_wifi_off, R.string.about_summary_internet)
                    Summary(R.drawable.ic_ms_two_wheeler, R.string.about_summary_dash)
                    Summary(R.drawable.ic_ms_folder_managed, R.string.about_summary_log)
                }
                val shape = MaterialTheme.shapes.large
                Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.18f), shape)) {
                    PARTS.forEachIndexed { i, part ->
                        if (i > 0) HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                        ExpandableBlock(
                            part.icon, stringResource(part.title), stringResource(part.text), part.tag in open,
                            { open = if (part.tag in open) open - part.tag else open + part.tag }, "about_${part.tag}",
                        )
                    }
                }
                SupportButton(
                    CardAction(stringResource(if (allOpen) R.string.about_close_all else R.string.about_open_all), "about_toggle_all") {
                        open = if (allOpen) emptySet() else PARTS.map { it.tag }.toSet()
                    },
                    Modifier.align(Alignment.CenterHorizontally),
                )
                // What the app is, under its data: the terms of use and the credits (read only, so not in the settings).
                TermsRow(onOpenTerms)
                CreditsRow(onOpenCredits)
            }
        }
    }
}

/** One of the three lines on top: the icon in a circle and what it says. */
@Composable
private fun Summary(icon: Int, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundIcon(icon)
        Text(
            stringResource(text), color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** A part of the page: its tag (`about_<tag>`), icon, title and text. */
private class Part(val tag: String, val icon: Int, val title: Int, val text: Int)

private val PARTS = listOf(
    Part("what", R.drawable.ic_ms_info, R.string.about_data_what_title, R.string.about_data_what),
    Part("leaves", R.drawable.ic_ms_upload, R.string.about_data_leaves_title, R.string.about_data_leaves),
    Part("web", R.drawable.ic_ms_wifi, R.string.about_data_web_title, R.string.about_data_web),
    Part("stays", R.drawable.ic_ms_smartphone, R.string.about_data_stays_title, R.string.about_data_stays),
    Part("kept", R.drawable.ic_ms_lock, R.string.about_data_kept_title, R.string.about_data_kept),
    Part("control", R.drawable.ic_ms_tune, R.string.about_data_control_title, R.string.about_data_control),
    Part("reach", R.drawable.ic_ms_block, R.string.about_data_reach_title, R.string.about_data_reach),
)
