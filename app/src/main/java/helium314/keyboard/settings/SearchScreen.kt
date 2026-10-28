// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.latin.utils.SearchIcon
import helium314.keyboard.clackatoo.ui.SettingsGroup
import helium314.keyboard.clackatoo.ui.settingsPanelColor

@Composable
fun SearchSettingsScreen(
    onClickBack: () -> Unit,
    title: String,
    settings: List<Any?>,
    homeStyle: Boolean = false, // large title, no back arrow, search field always shown
    content: @Composable (ColumnScope.() -> Unit)? = null // overrides settings if not null
) {
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(title) },
        homeStyle = homeStyle,
        content = {
            if (content != null) content()
            else {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                ) { innerPadding ->
                    // Int entries of [settings] start a new group (with that title), the rest are keys of settings or null for "not shown now"
                    val sections = remember(settings) { splitIntoSections(settings) }
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        sections.forEachIndexed { index, (titleId, items) ->
                            // stable keys: groups and rows appearing or disappearing must not shift the state of the others
                            key(index) {
                                if (items.any { it != null })
                                    SettingsGroup(title = titleId?.let { stringResource(it) }) {
                                        items.forEachIndexed { i, item ->
                                            key(i) {
                                                // this only animates appearing prefs
                                                AnimatedVisibility(visible = item != null) {
                                                    if (item != null)
                                                        SettingsActivity.settingsContainer[item]?.Preference()
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                    // lazyColumn has janky scroll for a while (not sure why compose gets smoother after a while)
                    // maybe related to unnecessary recompositions? but even for just displaying text it's there
                    // didn't manage to improve things with @Immutable list wrapper and other lazy list hints
                    // so for now: just use "normal" Column
                    //  even though it takes up to ~50% longer to load it's much better UX
                    //  and the missing appear animations could be added
                }
            }
        },
        filteredItems = { SettingsActivity.settingsContainer.filter(it) },
        itemContent = { it.Preference() }
    )
}

private fun splitIntoSections(settings: List<Any?>): List<Pair<Int?, List<Any?>>> {
    val sections = mutableListOf<Pair<Int?, List<Any?>>>()
    var title: Int? = null
    var current = mutableListOf<Any?>()
    fun flush() {
        if (title != null || current.isNotEmpty()) sections.add(title to current)
    }
    settings.forEach {
        if (it is Int) {
            flush()
            title = it
            current = mutableListOf()
        } else current.add(it)
    }
    flush()
    return sections
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T: Any?> SearchScreen(
    onClickBack: () -> Unit,
    title: @Composable () -> Unit,
    filteredItems: (String) -> List<T>,
    itemContent: @Composable (T) -> Unit,
    icon: @Composable (() -> Unit)? = null,
    menu: List<Pair<String, () -> Unit>>? = null,
    homeStyle: Boolean = false, // large title and an always visible search field instead of the app bar (home screen)
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    // searchText and showSearch should have the same remember or rememberSaveable
    // saveable survives orientation changes and switching between screens, but shows the
    // keyboard in unexpected situations such as going back from another screen, which is rather annoying
    var searchText by remember { mutableStateOf(TextFieldValue()) }
    var showSearch by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

            fun setShowSearch(value: Boolean) {
                showSearch = value
                if (!value) searchText = TextFieldValue()
            }
            BackHandler {
                if (showSearch || searchText.text.isNotEmpty()) setShowSearch(false)
                else onClickBack()
            }
            if (homeStyle) {
                Box(Modifier.padding(start = 28.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)) {
                    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Medium)) {
                        title()
                    }
                }
            } else
                TopAppBar(
                    title = title,
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    navigationIcon = {
                        BackButton {
                            if (showSearch) setShowSearch(false)
                            else onClickBack()
                        }
                    },
                    actions = {
                        if (icon == null)
                            IconButton(onClick = { setShowSearch(!showSearch) }) { SearchIcon() }
                        else
                            icon()
                        if (menu != null)
                            Box {
                                var showMenu by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showMenu = true }
                                ) { Icon(painterResource(R.drawable.ic_arrow_left), "menu", Modifier.rotate(-90f)) }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    menu.forEach {
                                        DropdownMenuItem(
                                            text = { Text(it.first) },
                                            onClick = { showMenu = false; it.second() }
                                        )
                                    }
                                }
                            }
                    },
                )
            ExpandableSearchField(
                expanded = showSearch || homeStyle,
                onDismiss = { if (!homeStyle) setShowSearch(false) },
                search = searchText,
                onSearchChange = { searchText = it },
                requestFocus = !homeStyle,
                placeholder = stringResource(if (homeStyle) R.string.settings_search_hint else R.string.label_search_key),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                if (searchText.text.isBlank() && content != null) {
                    Column {
                        content()
                    }
                } else {
                    val items = filteredItems(searchText.text)
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    ) { innerPadding ->
                        LazyColumn(contentPadding = innerPadding) {
                            items(items) {
                                itemContent(it)
                            }
                        }
                    }
                }
            }
        }
    }
}

// from StreetComplete
/** Expandable text field that can be dismissed and requests focus when it is expanded (if [requestFocus]) */
@Composable
fun ExpandableSearchField(
    expanded: Boolean,
    onDismiss: () -> Unit,
    search: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
    placeholder: String? = null,
    colors: TextFieldColors = settingsSearchFieldColors(),
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded && requestFocus) focusRequester.requestFocus()
    }
    AnimatedVisibility(visible = expanded, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = modifier.focusRequester(focusRequester),
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = { SearchIcon() },
            trailingIcon = if (requestFocus || search.text.isNotEmpty()) {
                {
                    IconButton(onClick = {
                        if (search.text.isBlank()) onDismiss()
                        else onSearchChange(TextFieldValue())
                    }) { CloseIcon(android.R.string.cancel) }
                }
            } else null,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            textStyle = contentTextDirectionStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}

/** A search field that is a panel like the rest: no underline, the panel colour as container. */
@Composable
fun settingsSearchFieldColors(): TextFieldColors {
    val panel = settingsPanelColor()
    val scheme = MaterialTheme.colorScheme
    return TextFieldDefaults.colors(
        focusedContainerColor = panel,
        unfocusedContainerColor = panel,
        disabledContainerColor = panel,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        focusedPlaceholderColor = scheme.onSurfaceVariant,
        unfocusedPlaceholderColor = scheme.onSurfaceVariant,
        focusedLeadingIconColor = scheme.onSurfaceVariant,
        unfocusedLeadingIconColor = scheme.onSurfaceVariant,
        focusedTrailingIconColor = scheme.onSurfaceVariant,
        unfocusedTrailingIconColor = scheme.onSurfaceVariant,
    )
}
