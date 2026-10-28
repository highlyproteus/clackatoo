// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.utils.SearchIcon
import helium314.keyboard.settings.IconOrImage

/*
 * The building blocks of the home screen that are not rows: the header with the app icon, the "try it" field and its quick
 * buttons, the setup checklist rows, the state tiles and the theme swatches. Like the rest of the settings UI they sit in
 * panels (frosted with glass) or on a frosted backing; text never sits on the clear window.
 */

/** The app's launcher icon as the system draws it (the adaptive icon with the launcher's mask). */
@Composable
internal fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(px) { ctx.packageManager.getApplicationIcon(ctx.applicationInfo).toBitmap(px, px).asImageBitmap() }
    Image(bitmap, contentDescription = null, modifier = modifier.size(size))
}

/**
 * The top of the home screen: the app icon, the name and what the keyboard stands for, and the search button (it opens the
 * settings search). The words sit on a frosted backing with glass, the button on the toolbar's one.
 */
@Composable
fun HomeHeader(title: @Composable () -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(56.dp, Modifier.clip(MaterialTheme.shapes.medium))
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.frostedBacking(MaterialTheme.shapes.medium)
                    .padding(horizontal = if (glass) 12.dp else 0.dp, vertical = if (glass) 6.dp else 0.dp),
            ) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium)) { title() }
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FrostedIcons { IconButton(onClick = onSearch) { SearchIcon() } }
    }
}

/**
 * A text field to try the keyboard in: what is typed is not kept or logged anywhere. It is a platform [EditText] because
 * Compose cannot ask the keyboard not to learn from it (`IME_FLAG_NO_PERSONALIZED_LEARNING`: Clackatoo then treats it like
 * incognito typing, so nothing goes into the user's dictionary). It is emptied when the screen is left or the app goes to
 * the background; the view does not save its text either.
 */
@Composable
internal fun TryField(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val hint = stringResource(R.string.home_try_hint)
    val holder = remember { arrayOfNulls<EditText>(1) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) holder[0]?.let { it.setText(""); it.clearFocus() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            holder[0]?.setText("")
            holder[0] = null
        }
    }
    AndroidView(
        factory = { ctx ->
            EditText(ctx).apply {
                background = null
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_DONE or NO_PERSONALIZED_LEARNING
                textSize = 16f
                val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                isSaveEnabled = false
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                holder[0] = this
            }
        },
        update = {
            it.hint = hint
            it.setTextColor(scheme.onSurface.toArgb())
            it.setHintTextColor(scheme.onSurfaceVariant.toArgb())
            it.highlightColor = scheme.primary.copy(alpha = 0.3f).toArgb()
        },
        modifier = modifier.fillMaxWidth()
            .border(androidx.compose.foundation.BorderStroke(1.dp, scheme.outline), MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium),
    )
}

/** EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING (API 26), the "private" / incognito flag. */
private const val NO_PERSONALIZED_LEARNING = 0x1000000

/** One of the quick buttons under the try field: a tinted icon and a short label. */
@Composable
internal fun QuickAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconTile(icon)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The icon in a small rounded square tinted with the accent, as in the model cards. */
@Composable
private fun IconTile(@DrawableRes icon: Int) {
    Box(
        Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { IconOrImage(icon, null, 24) }
}

/** A ring with a check (done) or an empty ring (still to do). */
@Composable
private fun CheckMark(done: Boolean) {
    if (done)
        Box(Modifier.size(28.dp).background(READY_GREEN, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_setup_check), null, Modifier.size(18.dp), tint = Color.White)
        }
    else
        Box(Modifier.size(28.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
}

/**
 * One line of the setup checklist: the check or ring, the [title] and [subtitle], [below] content (a progress bar) and
 * [trailing] (the Download button).
 */
@Composable
internal fun ChecklistRow(
    done: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleIsError: Boolean = false,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(done)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            below?.invoke(this)
        }
        trailing?.invoke()
    }
}

/** A tile of the 2 x 2 grid: a panel with an icon, the feature's name and its state; tapping it opens the feature's screen. */
@Composable
internal fun StateTile(title: String, state: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsPanel(modifier) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconTile(icon)
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
            Text(
                state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A theme as a live miniature of the keyboard with its name, the selected one framed in the accent. [colors] are the very
 * colours the keyboard will use (null when the theme cannot be built: the miniature is then left out).
 */
@Composable
internal fun ThemeSwatch(name: String, colors: Colors?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val selectedLabel = stringResource(R.string.theme_picker_selected)
    Column(
        modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (selected) "$name, $selectedLabel" else name; this.selected = selected }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val frame = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else Dp.Hairline, if (selected) scheme.primary else scheme.outline)
        Box(Modifier.fillMaxWidth().aspectRatio(1.7f).border(frame, MaterialTheme.shapes.small).padding(2.dp)) {
            if (colors != null) MiniKeyboard(colors, Modifier.fillMaxSize())
        }
        Text(
            // "Glass (follows the phone)" is too long for a swatch; the full name is what a screen reader says
            name.substringBefore(" ("), style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center,
            color = if (selected) scheme.primary else scheme.onSurface,
        )
    }
}
