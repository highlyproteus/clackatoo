// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.clackatoo.ui.HomeActions
import helium314.keyboard.clackatoo.ui.HomeContent
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.latin.utils.previewDark

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickTheme: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickVoiceTyping: () -> Unit,
    onClickGrammarCheck: () -> Unit,
    onClickSetUp: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(LocalContext.current.applicationInfo.labelRes),
        settings = emptyList(),
        homeStyle = true,
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(Modifier.padding(innerPadding)) {
                HomeContent(
                    HomeActions(
                        onSetUp = onClickSetUp,
                        onLanguages = onClickLanguage,
                        onPreferences = onClickPreferences,
                        onGestureTyping = onClickGestureTyping,
                        onDataGathering = onClickDataGathering,
                        onTextCorrection = onClickTextCorrection,
                        onLayouts = onClickLayouts,
                        onDictionaries = onClickDictionaries,
                        onVoiceTyping = onClickVoiceTyping,
                        onGrammarCheck = onClickGrammarCheck,
                        onTheme = onClickTheme,
                        onAppearance = onClickAppearance,
                        onToolbar = onClickToolbar,
                        onAdvanced = onClickAdvanced,
                        onAbout = onClickAbout,
                    )
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
