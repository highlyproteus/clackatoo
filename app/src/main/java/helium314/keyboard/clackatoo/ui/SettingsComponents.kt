// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.IconOrImage
import helium314.keyboard.settings.SettingsActivity

/*
 * The building blocks of every settings screen: grouped panels (Material 3 medium shape), rows with an icon, a title
 * and a subtitle that shows the state, section headers and hints. The settings background is glass over the
 * wallpaper (see GlassStyle and GlassWindow) unless the user turned it off.
 */

/** True while the settings draw glass panels over the wallpaper. */
val LocalGlass = staticCompositionLocalOf { false }

/** True while the phone blurs windows, so the glass window is lightly tinted; without blur the tint is heavier (see GlassStyle). */
val LocalGlassBlur = staticCompositionLocalOf { false }

/** True inside a [SettingsGroup]: its rows are part of the group's panel; elsewhere a row gets a panel of its own. */
private val LocalInGroup = compositionLocalOf { false }

/**
 * The colour scheme for glass: what is drawn on the frosted surfaces keeps 4.5:1 over any wallpaper. The accent,
 * the secondary text and the error colour move towards the main text colour just as far as that takes
 * ([GlassStyle.readable]); everything else stays.
 */
fun glassColorScheme(scheme: ColorScheme): ColorScheme {
    val container = scheme.surfaceContainerHigh.toArgb()
    val surface = scheme.surface.toArgb()
    val toward = scheme.onSurface.toArgb()
    fun readable(c: Color) = Color(GlassStyle.readable(c.toArgb(), toward, container, surface))
    return scheme.copy(
        primary = readable(scheme.primary),
        onSurfaceVariant = readable(scheme.onSurfaceVariant),
        error = readable(scheme.error),
        // the page is transparent, so Scaffolds (which default to the background colour) do not paint over the wallpaper
        background = Color.Transparent,
    )
}

/** The glass switch of the settings, read again whenever a preference changed. */
@Composable
fun rememberGlassEnabled(): Boolean {
    val ctx = LocalContext.current
    (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()?.value // recompose when the switch is flipped
    return ctx.prefs().getBoolean(GlassStyle.PREF_GLASS, GlassStyle.DEFAULT_GLASS)
}

/** Colour of a panel: frosted over the window background with glass, a solid Material 3 container without. */
@Composable
fun settingsPanelColor(): Color =
    if (LocalGlass.current) {
        val scheme = MaterialTheme.colorScheme
        Color(GlassStyle.panel(scheme.surfaceContainerHigh.toArgb(), scheme.surface.toArgb()))
    } else MaterialTheme.colorScheme.surfaceContainer

/** Colour of a dialog: frosted where the phone blurs what is behind it, solid otherwise (and without glass). */
@Composable
fun settingsDialogColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (LocalGlass.current && LocalGlassBlur.current)
        Color(GlassStyle.dialog(scheme.surfaceContainerHigh.toArgb(), scheme.surface.toArgb()))
    else scheme.surfaceContainerHigh
}

@Composable
private fun settingsPanelBorder(): BorderStroke? =
    if (LocalGlass.current) BorderStroke(Dp.Hairline, MaterialTheme.colorScheme.onSurface.copy(alpha = GlassStyle.BORDER_ALPHA)) else null

/**
 * A frosted backing for text or icons that would otherwise sit on the clear window (page title, section headers,
 * hints, toolbar icons): the container colour at [GlassStyle.BACKING_ALPHA_LIGHT]/[GlassStyle.BACKING_ALPHA_DARK] with a
 * hairline border. Without glass the window is solid and nothing is added.
 */
@Composable
fun Modifier.frostedBacking(shape: Shape = MaterialTheme.shapes.small): Modifier {
    if (!LocalGlass.current) return this
    val scheme = MaterialTheme.colorScheme
    val color = Color(GlassStyle.backing(scheme.surfaceContainerHigh.toArgb(), scheme.surface.toArgb()))
    return this
        .background(color, shape)
        .border(BorderStroke(Dp.Hairline, scheme.onSurface.copy(alpha = GlassStyle.BORDER_ALPHA)), shape)
}

/** The window's page title (or anything else that sits on the clear window): on a frosted backing with glass. */
@Composable
fun FrostedTitle(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glass = LocalGlass.current
    Box(modifier.frostedBacking().then(if (glass) Modifier.padding(horizontal = 12.dp, vertical = 4.dp) else Modifier)) { content() }
}

/** Icon buttons of the toolbar: one frosted pill behind them with glass. */
@Composable
fun FrostedIcons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.frostedBacking(CircleShape), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** A card: Material 3 medium shape, glass or solid. */
@Composable
fun SettingsPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = settingsPanelColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = settingsPanelBorder(),
        content = content,
    )
}

/** Rows (and anything else) in one panel, with the [title] in small type at the top. */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsPanel(modifier) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (title != null) SettingsGroupTitle(title)
            CompositionLocalProvider(LocalInGroup provides true) { content() }
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * A header between panels, on the window background (so in the main text colour). With glass it sits on a small frosted
 * backing; [startPadding] is where the text starts, as without glass.
 */
@Composable
fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier, startPadding: Dp = 28.dp) {
    val glass = LocalGlass.current
    val inner = if (glass) BACKING_PADDING else 0.dp
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(start = (startPadding - inner).coerceAtLeast(0.dp), end = 16.dp, top = 20.dp, bottom = 8.dp)
            .frostedBacking()
            .padding(horizontal = inner, vertical = if (glass) 4.dp else 0.dp),
    )
}

/**
 * Explanatory text between rows or panels, on the window background (so in the main text colour). With glass it sits on
 * a frosted backing of its own.
 */
@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier, startPadding: Dp = 28.dp) {
    val glass = LocalGlass.current
    val inner = if (glass) BACKING_PADDING else 0.dp
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (startPadding - inner).coerceAtLeast(0.dp), end = (28.dp - inner).coerceAtLeast(0.dp), top = 6.dp, bottom = 6.dp)
            .frostedBacking(MaterialTheme.shapes.medium)
            .padding(horizontal = inner, vertical = if (glass) 8.dp else 0.dp),
    )
}

private val BACKING_PADDING = 12.dp

/**
 * One row: optional icon, [title], optional [subtitle] (usually the state), optional [trailing] content (a switch, a
 * value, an arrow). Inside a [SettingsGroup] it is part of the group's panel, anywhere else it is a panel of its own.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) IconOrImage(icon, title, 24)
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
            }
            if (trailing != null)
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(textAlign = TextAlign.End, hyphens = Hyphens.Auto),
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        trailing()
                    }
                }
        }
    }
    if (LocalInGroup.current) row()
    else SettingsPanel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { row() }
}
