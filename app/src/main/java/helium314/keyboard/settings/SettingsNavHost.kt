// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.getTransitionAnimationScale
import helium314.keyboard.settings.screens.AboutScreen
import helium314.keyboard.settings.screens.AdvancedSettingsScreen
import helium314.keyboard.settings.screens.AppearanceScreen
import helium314.keyboard.settings.screens.ColorsScreen
import helium314.keyboard.settings.screens.DebugScreen
import helium314.keyboard.settings.screens.DictionaryScreen
import helium314.keyboard.settings.screens.GestureTypingScreen
import helium314.keyboard.settings.screens.GrammarCheckScreen
import helium314.keyboard.settings.screens.VoiceTypingScreen
import helium314.keyboard.settings.screens.LanguageScreen
import helium314.keyboard.settings.screens.MainSettingsScreen
import helium314.keyboard.settings.screens.PersonalDictionariesScreen
import helium314.keyboard.settings.screens.PersonalDictionaryScreen
import helium314.keyboard.settings.screens.PreferencesScreen
import helium314.keyboard.settings.screens.SecondaryLayoutScreen
import helium314.keyboard.settings.screens.SubtypeScreen
import helium314.keyboard.settings.screens.ThemePickerScreen
import helium314.keyboard.settings.screens.TextCorrectionScreen
import helium314.keyboard.settings.screens.ToolbarScreen
import helium314.keyboard.settings.screens.gesturedata.GestureDataScreen
import helium314.keyboard.settings.screens.gesturedata.ReviewScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun SettingsNavHost(
    onClickBack: () -> Unit,
    onClickSetUp: () -> Unit = { },
    startDestination: String? = null,
) {
    val navController = rememberNavController()
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val target = SettingsDestination.navTarget.collectAsState()

    // duration does not change when system setting changes, but that's rare enough to not care
    val duration = (250 * getTransitionAnimationScale(LocalContext.current)).toInt()
    // fade through, with a small slide: the screens are glass, so a full-width slide would show two screens' text over each other
    val slide = tween<IntOffset>(durationMillis = duration, easing = FastOutSlowInEasing)
    val fadeInSpec = tween<Float>(durationMillis = (duration * 0.6f).toInt(), delayMillis = (duration * 0.3f).toInt(), easing = LinearOutSlowInEasing)
    val fadeOutSpec = tween<Float>(durationMillis = (duration * 0.3f).toInt(), easing = FastOutLinearInEasing)

    fun goBack() {
        if (!navController.popBackStack()) onClickBack()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination ?: SettingsDestination.Settings,
        enterTransition = { slideInHorizontally(initialOffsetX = { +it / 10 * dir }, animationSpec = slide) + fadeIn(fadeInSpec) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 10 * dir }, animationSpec = slide) + fadeOut(fadeOutSpec) },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 10 * dir }, animationSpec = slide) + fadeIn(fadeInSpec) },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { +it / 10 * dir }, animationSpec = slide) + fadeOut(fadeOutSpec) }
    ) {
        composable(SettingsDestination.Settings) {
            MainSettingsScreen(
                onClickAbout = { navController.navigate(SettingsDestination.About) },
                onClickTextCorrection = { navController.navigate(SettingsDestination.TextCorrection) },
                onClickPreferences = { navController.navigate(SettingsDestination.Preferences) },
                onClickToolbar = { navController.navigate(SettingsDestination.Toolbar) },
                onClickGestureTyping = { navController.navigate(SettingsDestination.GestureTyping) },
                onClickDataGathering = { navController.navigate(SettingsDestination.DataGathering) },
                onClickAdvanced = { navController.navigate(SettingsDestination.Advanced) },
                onClickAppearance = { navController.navigate(SettingsDestination.Appearance) },
                onClickLanguage = { navController.navigate(SettingsDestination.Languages) },
                onClickLayouts = { navController.navigate(SettingsDestination.Layouts) },
                onClickDictionaries = { navController.navigate(SettingsDestination.Dictionaries) },
                onClickVoiceTyping = { navController.navigate(SettingsDestination.VoiceTyping) },
                onClickGrammarCheck = { navController.navigate(SettingsDestination.GrammarCheck) },
                onClickSetUp = onClickSetUp,
                onClickBack = ::goBack,
            )
        }
        composable(SettingsDestination.VoiceTyping) {
            VoiceTypingScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.GrammarCheck) {
            GrammarCheckScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.About) {
            AboutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.TextCorrection) {
            TextCorrectionScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Preferences) {
            PreferencesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Toolbar) {
            ToolbarScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.GestureTyping) {
            GestureTypingScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.DataGathering) {
            GestureDataScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.DataReview) {
            ReviewScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Advanced) {
            AdvancedSettingsScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Debug) {
            DebugScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Appearance) {
            AppearanceScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.PersonalDictionary + "{locale}") {
            val locale = it.arguments?.getString("locale")?.takeIf { loc -> loc.isNotBlank() }?.constructLocale()
            PersonalDictionaryScreen(
                onClickBack = ::goBack,
                locale = locale
            )
        }
        composable(SettingsDestination.PersonalDictionaries) {
            PersonalDictionariesScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Languages) {
            LanguageScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Dictionaries) {
            DictionaryScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Layouts) {
            SecondaryLayoutScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.ThemePicker) {
            ThemePickerScreen(onClickBack = ::goBack)
        }
        composable(SettingsDestination.Colors + "{theme}") {
            ColorsScreen(isNight = false, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.ColorsNight + "{theme}") {
            ColorsScreen(isNight = true, theme = it.arguments?.getString("theme"), onClickBack = ::goBack)
        }
        composable(SettingsDestination.Subtype + "{subtype}") {
            SubtypeScreen(initialSubtype = it.arguments?.getString("subtype")!!.toSettingsSubtype(), onClickBack = ::goBack)
        }
    }
    if (target.value != SettingsDestination.Settings/* && target.value != navController.currentBackStackEntry?.destination?.route*/)
        navController.navigate(route = target.value)
}

object SettingsDestination {
    const val Settings = "settings"
    const val About = "about"
    const val TextCorrection = "text_correction"
    const val Preferences = "preferences"
    const val Toolbar = "toolbar"
    const val GestureTyping = "gesture_typing"
    const val DataGathering = "data_gathering" // remove when data gathering phase is done (end of 2026 latest)
    const val DataReview = "data_review" // remove when data gathering phase is done (end of 2026 latest)
    const val Advanced = "advanced"
    const val Debug = "debug"
    const val Appearance = "appearance"
    const val ThemePicker = "theme_picker"
    const val Colors = "colors/"
    const val ColorsNight = "colors_night/"
    const val PersonalDictionaries = "personal_dictionaries"
    const val PersonalDictionary = "personal_dictionary/"
    const val Languages = "languages"
    const val Subtype = "subtype/"
    const val Layouts = "layouts"
    const val Dictionaries = "dictionaries"
    const val VoiceTyping = "voice_typing"
    const val GrammarCheck = "grammar_check"
    val navTarget = MutableStateFlow(Settings)

    private val navScope = CoroutineScope(Dispatchers.Default)
    fun navigateTo(target: String) {
        if (navTarget.value == target) {
            // triggers recompose twice, but that's ok as it's a rare event
            navTarget.value = Settings
            navScope.launch { delay(10); navTarget.value = target }
        } else
            navTarget.value = target
        navScope.launch { delay(50); navTarget.value = Settings }
    }
}
