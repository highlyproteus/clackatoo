// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.clackatoo.ui

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R

/** What a model card shows: the states a model has on the phone, without the details of how it got there. */
sealed interface ModelCardState {
    data object NotInstalled : ModelCardState
    data class Downloading(val bytes: Long, val total: Long) : ModelCardState
    data object Installing : ModelCardState
    data object Installed : ModelCardState
    /** [message] is shown as it is. */
    data class Failed(val message: String) : ModelCardState
    /** Not installed, and the phone cannot run it (no Download button). */
    data object Unsupported : ModelCardState
}

/**
 * A model as a card: a neutral [icon] (never a maker's logo), the [title], the maker and size in [subtitle], and
 * what can be done with it in its [state]: Download (labelled [downloadLabel], as it may send the user to the
 * companion app first), progress with Cancel, Installing, or Ready with a radio button when [selectable] (the active
 * model of the role) and a menu to delete it. A [badge] goes above the title.
 */
@Composable
fun ModelCard(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    /** What a screen reader says for the title: the model's full name. */
    fullName: String,
    state: ModelCardState,
    downloadLabel: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    /** A short label above the title: "Suggested for this phone", or "Experimental · not verified" for a custom model. */
    badge: String? = null,
    /** Shown as a warning rather than a recommendation (experimental models). */
    badgeIsWarning: Boolean = false,
) {
    val installed = state is ModelCardState.Installed
    // a long label ("Get Clackatoo Models") would squeeze the name, so it goes below the text instead of beside it
    val buttonBelow = downloadLabel.length > SHORT_LABEL
    val select = if (installed && selectable) Modifier.clickable(role = Role.RadioButton, onClick = onSelect) else Modifier
    SettingsPanel(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().then(select).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                if (badge != null)
                    Text(
                        badge, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (badgeIsWarning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                Text(
                    title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = fullName },
                )
                if (state !is ModelCardState.Downloading)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                when (state) {
                    is ModelCardState.Downloading -> DownloadProgress(state)
                    is ModelCardState.Installing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                        Text(
                            stringResource(R.string.models_installing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    is ModelCardState.Failed ->
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    else -> {}
                }
                if (buttonBelow && (state is ModelCardState.NotInstalled || state is ModelCardState.Failed))
                    FilledTonalButton(onClick = onDownload, modifier = Modifier.padding(top = 10.dp)) { Text(downloadLabel) }
            }
            when (state) {
                is ModelCardState.NotInstalled, is ModelCardState.Failed ->
                    if (!buttonBelow) FilledTonalButton(onClick = onDownload) { Text(downloadLabel) }
                is ModelCardState.Downloading -> TextButton(onClick = onCancel) { Text(stringResource(R.string.models_cancel)) }
                is ModelCardState.Installed -> {
                    if (selectable) RadioButton(selected = selected, onClick = onSelect)
                    else Text(stringResource(R.string.models_ready), style = MaterialTheme.typography.labelLarge)
                    ModelMenu(onDelete)
                }
                is ModelCardState.Unsupported -> Text(
                    stringResource(R.string.models_not_on_this_phone),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {}
            }
        }
    }
}

private const val SHORT_LABEL = 12

@Composable
private fun DownloadProgress(state: ModelCardState.Downloading) {
    val ctx = LocalContext.current
    val fraction = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = 2.dp)) {
        Text(
            stringResource(
                R.string.model_card_progress,
                Formatter.formatShortFileSize(ctx, state.bytes), Formatter.formatShortFileSize(ctx, state.total)
            ) + " \u00b7 ${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun ModelMenu(onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.model_card_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.models_delete)) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}
