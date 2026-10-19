package com.reau.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reau.app.ui.theme.LocalREauDark
import com.reau.app.ui.theme.LocalREauNight
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The screens dark at night and light by day, as the rider's "Tema" says; dark by default. */
@RunWith(AndroidJUnit4::class)
class AppThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theScreensFollowTheNightTheyAreHanded() {
        compose.setContent {
            CompositionLocalProvider(LocalREauNight provides false) {
                AppTheme { Text(if (LocalREauDark.current) "escuro" else "claro", Modifier.testTag("theme")) }
            }
        }
        compose.onNodeWithTag("theme").assertTextEquals("claro")
    }

    @Test
    fun withNothingHandedTheyAreDark() {
        compose.setContent { AppTheme { Text(if (LocalREauDark.current) "escuro" else "claro", Modifier.testTag("theme")) } }
        compose.onNodeWithTag("theme").assertTextEquals("escuro")
    }
}
