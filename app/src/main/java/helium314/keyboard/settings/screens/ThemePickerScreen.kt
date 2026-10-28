// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.clackatoo.ThemeCatalog
import helium314.keyboard.clackatoo.ThemeGroup
import helium314.keyboard.clackatoo.ui.SettingsGroup
import helium314.keyboard.clackatoo.ui.SettingsHint
import helium314.keyboard.clackatoo.ui.SettingsRow
import helium314.keyboard.clackatoo.ui.SettingsSectionHeader
import helium314.keyboard.clackatoo.ui.settingsPanelColor
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.dialogs.ColorThemePickerDialog
import helium314.keyboard.settings.Setting

/**
 * The theme picker: every colour theme as a live miniature of the keyboard (drawn from the very colours the keyboard will use),
 * grouped as Follow phone, Glass, Omarchy, Classic and Custom. Tapping one applies it, for the day or, with the day/night switch
 * on, for the day or the night, whichever is selected at the top. Nothing is stored beyond the existing theme preferences
 * ([Settings.PREF_THEME_COLORS], [Settings.PREF_THEME_COLORS_NIGHT], [Settings.PREF_THEME_DAY_NIGHT]).
 */
@Composable
fun ThemePickerScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    val canDayNight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    var dayNight by remember { mutableStateOf(canDayNight && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)) }
    // which of the two the next tap sets: the one the phone shows now at first
    var night by rememberSaveable { mutableStateOf(ResourceUtils.isNight(ctx.resources)) }
    val editsNight = dayNight && night
    val prefKey = if (editsNight) Settings.PREF_THEME_COLORS_NIGHT else Settings.PREF_THEME_COLORS
    var day by remember { mutableStateOf(prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)!!) }
    var nightTheme by remember { mutableStateOf(prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)!!) }
    val selected = if (editsNight) nightTheme else day
    var showManageDialog by remember { mutableStateOf(false) }

    val userThemes = userThemeNames(prefs)

    fun apply(id: String) {
        prefs.edit {
            putString(prefKey, id)
            // glass and Omarchy are made for the Clackatoo style (8dp keys with a hairline); it is the one that shows them as designed
            if (ThemeCatalog.isGlass(id) || ThemeCatalog.isOmarchy(id)) putString(Settings.PREF_THEME_STYLE, KeyboardTheme.STYLE_CLACKATOO)
        }
        if (editsNight) nightTheme = id else day = id
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.theme_picker_title),
        settings = emptyList(),
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                if (canDayNight) {
                    SettingsGroup(Modifier.padding(top = 8.dp)) {
                        SettingsRow(
                            title = stringResource(R.string.theme_picker_day_night),
                            subtitle = stringResource(R.string.theme_picker_day_night_summary),
                            onClick = {
                                dayNight = !dayNight
                                prefs.edit { putBoolean(Settings.PREF_THEME_DAY_NIGHT, dayNight) }
                                KeyboardSwitcher.getInstance().setThemeNeedsReload()
                            },
                        ) { Switch(checked = dayNight, onCheckedChange = null) }
                        if (dayNight)
                            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !night, onClick = { night = false }, label = { Text(stringResource(R.string.theme_picker_day)) })
                                FilterChip(selected = night, onClick = { night = true }, label = { Text(stringResource(R.string.theme_picker_night)) })
                            }
                    }
                }

                val groups = themesByGroup(ctx, editsNight, userThemes, selected)
                for ((group, ids) in groups) {
                    SettingsSectionHeader(stringResource(groupTitle(group)), startPadding = 12.dp)
                    groupNote(group)?.let { SettingsHint(stringResource(it), startPadding = 12.dp) }
                    // two tiles to a row
                    for (row in ids.chunked(2)) {
                        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (id in row) {
                                ThemeTile(
                                    id = id, night = editsNight, selected = id == selected, isUser = id in userThemes,
                                    onClick = { apply(id) }, onEdit = { openEditor(id, editsNight) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (group == ThemeGroup.CUSTOM) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { newCustomTheme(prefs, prefKey, editsNight) }) { Text(stringResource(R.string.theme_picker_new_custom)) }
                            // adding, loading from a file or the clipboard, renaming and deleting stay where they were
                            TextButton(onClick = { showManageDialog = true }) { Text(stringResource(R.string.load)) }
                        }
                    }
                }
            }
        },
    )
    if (showManageDialog) {
        val setting = Setting(ctx, prefKey, if (editsNight) R.string.theme_colors_night else R.string.theme_colors) {}
        ColorThemePickerDialog(
            onDismissRequest = { showManageDialog = false },
            setting = setting,
            isNight = editsNight,
            default = if (editsNight) Defaults.PREF_THEME_COLORS_NIGHT else Defaults.PREF_THEME_COLORS,
        )
    }
}

/** The theme with its miniature, name and, for a custom theme, the way to its colours. */
@Composable
private fun ThemeTile(
    id: String, night: Boolean, selected: Boolean, isUser: Boolean,
    onClick: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // the colours are what the keyboard will use: the same code builds them
    val colors = remember(id, night) { runCatching { KeyboardTheme.getColorsForThemeName(ctx, id, night) }.getOrNull() }
    val name = ThemeCatalog.displayName(ctx, id)
    val selectedLabel = stringResource(R.string.theme_picker_selected)
    Card(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = if (selected) "$name, $selectedLabel" else name
            this.selected = selected
        },
        shape = MaterialTheme.shapes.medium,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(Dp.Hairline, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = settingsPanelColor()),
    ) {
        Column(Modifier.padding(8.dp)) {
            if (colors != null) MiniKeyboard(colors, Modifier.fillMaxWidth().aspectRatio(1.7f))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, style = MaterialTheme.typography.labelLarge, maxLines = 2,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (isUser) TextButton(onClick = onEdit) { Text(stringResource(R.string.theme_picker_edit_custom), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/**
 * A miniature keyboard drawn from [colors]: suggestion strip, four rows of keys with their letters, the space bar and the Enter key,
 * in the colours the keyboard really has (a translucent theme shows over a colourful stand-in for the app behind it).
 */
@Composable
private fun MiniKeyboard(colors: Colors, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds().semantics { }) {
        val corner = size.width * 0.022f // 8dp on a 360dp keyboard
        val round = CornerRadius(corner)
        val keyboardBackground = colors.get(ColorType.MAIN_BACKGROUND)
        if (keyboardBackground ushr 24 < 255) {
            // a stand-in for what is behind a translucent keyboard
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFFF7A59), Color(0xFFFFC857), Color(0xFF2BB5C8), Color(0xFF6A4FD8)), Offset.Zero, Offset(size.width, size.height)), cornerRadius = round)
            drawCircle(Color(0xFFFFFFFF), radius = size.height * 0.3f, center = Offset(size.width * 0.2f, size.height * 0.25f))
            drawCircle(Color(0xFF111111), radius = size.height * 0.22f, center = Offset(size.width * 0.8f, size.height * 0.6f))
        }
        drawRoundRect(Color(keyboardBackground), size = size, cornerRadius = round)

        val pad = size.width * 0.012f
        val gap = size.width * 0.012f
        val stripHeight = size.height * 0.15f
        val rows = 4
        val rowHeight = (size.height - stripHeight - 2 * pad - gap * rows) / rows
        val keyFill = Color(colors.get(ColorType.KEY_BACKGROUND))
        val functionFill = Color(colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
        val spaceFill = Color(colors.get(ColorType.SPACE_BAR_BACKGROUND))
        val actionFill = Color(colors.get(ColorType.ACTION_KEY_BACKGROUND))
        val border = Color(colors.keyBorder)
        val text = colors.get(ColorType.KEY_TEXT)
        val actionText = colors.get(ColorType.ACTION_KEY_ICON)
        val hairline = size.width * 0.0025f

        // suggestion strip: three words
        for (i in 0 until 3) {
            val w = size.width * 0.18f
            val x = size.width * (0.17f + i * 0.28f)
            drawRoundRect(Color(colors.get(ColorType.SUGGESTED_WORD)).copy(alpha = if (i == 1) 0.9f else 0.55f), Offset(x, stripHeight * 0.4f), Size(w, stripHeight * 0.22f), CornerRadius(stripHeight * 0.11f))
        }
        drawRoundRect(Color(colors.get(ColorType.TOOL_BAR_KEY)).copy(alpha = 0.7f), Offset(size.width * 0.04f, stripHeight * 0.32f), Size(stripHeight * 0.4f, stripHeight * 0.4f), CornerRadius(corner / 2))

        fun key(x: Float, y: Float, w: Float, fill: Color, label: String?, labelColor: Int) {
            if (colors.hasKeyBorders) {
                drawRoundRect(fill, Offset(x, y), Size(w, rowHeight), round)
                drawRoundRect(border, Offset(x + hairline / 2, y + hairline / 2), Size(w - hairline, rowHeight - hairline), round, style = Stroke(hairline))
            }
            if (label != null) drawLabel(label, x + w / 2, y + rowHeight / 2, rowHeight * 0.5f, labelColor)
        }

        val keyWidth = (size.width - 2 * pad - 9 * gap) / 10
        var y = stripHeight + pad
        val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for ((r, letters) in letterRows.withIndex()) {
            val rowWidth = letters.length * keyWidth + (letters.length - 1) * gap
            val functionWidth = if (r == 2) (size.width - 2 * pad - rowWidth - 2 * gap) / 2 else 0f
            var x = pad + (size.width - 2 * pad - rowWidth - 2 * functionWidth - (if (r == 2) 2 * gap else 0f)) / 2 + (if (r == 2) 0f else 0f)
            if (r == 2) {
                key(pad, y, functionWidth, functionFill, "⇧", text)
                x = pad + functionWidth + gap
            }
            for (letter in letters) {
                key(x, y, keyWidth, keyFill, letter.toString(), text)
                x += keyWidth + gap
            }
            if (r == 2) key(size.width - pad - functionWidth, y, functionWidth, functionFill, "⌫", text)
            y += rowHeight + gap
        }
        // bottom row: ?123, comma, space, period, Enter
        val symbolWidth = keyWidth * 1.4f
        val enterWidth = keyWidth * 1.8f
        var x = pad
        key(x, y, symbolWidth, functionFill, "?123", text); x += symbolWidth + gap
        key(x, y, keyWidth, functionFill, ",", text); x += keyWidth + gap
        val spaceWidth = size.width - 2 * pad - symbolWidth - enterWidth - 2 * keyWidth - 4 * gap
        key(x, y, spaceWidth, spaceFill, null, text); x += spaceWidth + gap
        key(x, y, keyWidth, functionFill, ".", text); x += keyWidth + gap
        key(x, y, enterWidth, actionFill, "⏎", actionText)
    }
}

private fun DrawScope.drawLabel(label: String, cx: Float, cy: Float, textSize: Float, color: Int) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSize
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.nativeCanvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
    }
}

private fun groupTitle(group: ThemeGroup) = when (group) {
    ThemeGroup.FOLLOW_PHONE -> R.string.theme_picker_group_follow
    ThemeGroup.GLASS -> R.string.theme_picker_group_glass
    ThemeGroup.OMARCHY -> R.string.theme_picker_group_omarchy
    ThemeGroup.CLASSIC -> R.string.theme_picker_group_classic
    ThemeGroup.CUSTOM -> R.string.theme_picker_group_custom
}

private fun groupNote(group: ThemeGroup) = when (group) {
    ThemeGroup.GLASS -> R.string.theme_picker_glass_note
    ThemeGroup.OMARCHY -> R.string.theme_picker_omarchy_note
    else -> null
}

/** The names of the user's own themes. */
private fun userThemeNames(prefs: android.content.SharedPreferences): List<String> = prefs.all.keys.mapNotNull {
    when {
        it.startsWith(Settings.PREF_USER_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_COLORS_PREFIX)
        it.startsWith(Settings.PREF_USER_ALL_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_ALL_COLORS_PREFIX)
        it.startsWith(Settings.PREF_USER_MORE_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_MORE_COLORS_PREFIX)
        else -> null
    }
}.distinct().sorted()

/**
 * The themes of each group that has any. [selected] is shown even if it is none of the known ones (a theme that has no settings
 * stored yet), as a custom theme.
 */
private fun themesByGroup(ctx: Context, night: Boolean, userThemes: List<String>, selected: String): List<Pair<ThemeGroup, List<String>>> {
    val prefs = ctx.prefs()
    val classic = ThemeCatalog.classic(prefs, night)
    val custom = (userThemes + selected).filter { it != "" && it !in classic && it !in KNOWN && !ThemeCatalog.isOmarchy(it) || it in userThemes }.distinct().sorted()
    val follow = listOfNotNull(KeyboardTheme.THEME_CLACKATOO, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) KeyboardTheme.THEME_DYNAMIC else null)
    return listOf(
        ThemeGroup.FOLLOW_PHONE to follow,
        ThemeGroup.GLASS to ThemeCatalog.glass,
        ThemeGroup.OMARCHY to helium314.keyboard.clackatoo.OmarchyThemes.all.map { it.id },
        ThemeGroup.CLASSIC to classic,
        ThemeGroup.CUSTOM to custom,
    ).filter { it.second.isNotEmpty() || it.first == ThemeGroup.CUSTOM }
}

private val KNOWN = setOf(ThemeCatalog.GLASS, ThemeCatalog.GLASS_DARK, ThemeCatalog.GLASS_LIGHT, KeyboardTheme.THEME_CLACKATOO, KeyboardTheme.THEME_DYNAMIC)

private fun openEditor(id: String, night: Boolean) {
    SettingsDestination.navigateTo((if (night) SettingsDestination.ColorsNight else SettingsDestination.Colors) + id)
}

/** A new custom theme, as the colour dialog makes it: a name nobody has, selected, and its colour screen open. */
private fun newCustomTheme(prefs: android.content.SharedPreferences, prefKey: String, night: Boolean) {
    val name = KeyboardTheme.getUnusedThemeName("My theme", prefs)
    prefs.edit { putString(prefKey, name) }
    KeyboardTheme.writeUserMoreColors(prefs, name, Defaults.PREF_USER_MORE_COLORS) // write sth so theme is stored
    openEditor(name, night)
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}
