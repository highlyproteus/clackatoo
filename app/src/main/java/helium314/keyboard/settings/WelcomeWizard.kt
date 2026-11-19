// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.clackatoo.ui.AppIcon
import helium314.keyboard.clackatoo.ui.SettingsPanel
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 0
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val scope = rememberCoroutineScope { Dispatchers.IO }
    LaunchedEffect(step) {
        if (step == 2)
            scope.launch {
                while (step == 2 && !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(50)
                }
                step = 3
            }
    }
    val useWideLayout = isWideScreen()
    val appName = stringResource(ctx.applicationInfo.labelRes)
    @Composable fun header() {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(72.dp, Modifier.clip(MaterialTheme.shapes.medium))
            val resource = if (step == 0) R.string.setup_welcome_title else R.string.setup_steps_title
            Text(
                stringResource(resource, appName),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    @Composable fun StepPanel(number: Int, title: String, instruction: String) {
        Text(
            stringResource(R.string.setup_step_count, number, 3),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SettingsPanel {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    instruction,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    @Composable fun PrimaryAction(text: String, icon: Painter, onClick: () -> Unit) {
        Button(onClick, Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text)
        }
    }
    @Composable fun SecondaryAction(text: String, onClick: () -> Unit) {
        TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
    }
    @Composable fun steps() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            step = determineStep()
        }
        when (step) {
            0 -> {
                PrimaryAction(stringResource(R.string.setup_start_action), painterResource(R.drawable.ic_setup_key)) { step = 1 }
                SecondaryAction(stringResource(R.string.setup_not_now), close)
            }
            1 -> {
                StepPanel(1, stringResource(R.string.setup_step1_title, appName), stringResource(R.string.setup_step1_instruction, appName))
                PrimaryAction(stringResource(R.string.setup_step1_action), painterResource(R.drawable.ic_setup_key)) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
                    intent.addCategory(Intent.CATEGORY_DEFAULT)
                    launcher.launch(intent)
                }
                SecondaryAction(stringResource(R.string.setup_not_now), close)
            }
            2 -> {
                StepPanel(2, stringResource(R.string.setup_step2_title, appName), stringResource(R.string.setup_step2_instruction, appName))
                PrimaryAction(stringResource(R.string.setup_step2_action), painterResource(R.drawable.ic_setup_select), imm::showInputMethodPicker)
                SecondaryAction(stringResource(R.string.setup_not_now), close)
            }
            else -> { // step 3
                StepPanel(3, stringResource(R.string.setup_step3_title), stringResource(R.string.setup_step3_instruction, appName))
                PrimaryAction(stringResource(R.string.setup_step3_action), painterResource(R.drawable.sym_keyboard_language_switch), close)
                SecondaryAction(stringResource(R.string.setup_finish_action), finish)
            }
        }
    }
    // the wizard covers the screen, so it is opaque even with glass
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (useWideLayout)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(0.4f)) {
                        header()
                    }
                    Column(Modifier.weight(0.6f)) {
                        steps()
                    }
                }
            else
                Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    header()
                    steps()
                }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(
    // content cut off on real device, but not here... great?
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}
