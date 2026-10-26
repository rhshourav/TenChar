package com.rhshourav.tenchar.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.MessageLimits
import com.rhshourav.tenchar.Pairing
import com.rhshourav.tenchar.R

/** Smaller than CodeStyle so 19 characters fit on a narrow phone. */
private val EntryStyle = CodeStyle.copy(fontSize = 20.sp, letterSpacing = 1.sp)

/** Dialogs are separate windows, so they need FLAG_SECURE of their own. */
private val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

@Composable
fun TenCharApp(
    vm: DemoViewModel,
    deviceSecure: Boolean,
    onOpenSecuritySettings: () -> Unit,
) {
    TenCharTheme {
        if (!deviceSecure) {
            LockRequiredScreen(onOpenSecuritySettings)
        } else {
            BackHandler(
                enabled = vm.screen == Screen.Settings || vm.screen == Screen.About ||
                    vm.screen == Screen.EnterCode || vm.screen == Screen.Waiting,
            ) { vm.back() }
            when (vm.screen) {
                Screen.Gate -> GateScreen(vm)
                Screen.Pair -> PairScreen(vm)
                Screen.EnterCode -> EnterCodeScreen(vm)
                Screen.Waiting -> WaitingScreen(vm)
                Screen.Verify -> VerifyScreen(vm)
                Screen.Chat -> ChatScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.About -> AboutScreen(vm)
                Screen.KeysReset -> KeysResetScreen(vm)
            }
        }
    }
}

@Composable
private fun LockRequiredScreen(onOpenSettings: () -> Unit) {
    TenCharScreen(title = stringResource(R.string.app_name)) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.lockreq_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.lockreq_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        BigButton(stringResource(R.string.lockreq_open), onOpenSettings)
    }
}

@Composable
private fun GateScreen(vm: DemoViewModel) {
    TenCharScreen(title = stringResource(R.string.app_name)) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.gate_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.gate_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        BigButton(stringResource(R.string.gate_unlock), vm::unlock)
    }
}

@Composable
private fun PairScreen(vm: DemoViewModel) {
    TenCharScreen(title = stringResource(R.string.app_name)) {
        val notice = vm.notice
        if (notice != null) {
            val isWarning = notice == Notice.Mismatch
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (isWarning) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isWarning) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = stringResource(
                        if (isWarning) R.string.notice_mismatch else R.string.notice_forgotten,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.pair_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        BigButton(stringResource(R.string.pair_make), vm::startWaiting)
        Spacer(Modifier.height(12.dp))
        BigButton(stringResource(R.string.pair_have), vm::openEnterCode, primary = false)
    }
}

@Composable
private fun EnterCodeScreen(vm: DemoViewModel) {
    val valid = Pairing.isValid(vm.entry)
    val showError = vm.entry.isNotEmpty() && !valid && vm.entry.length >= Constants.CODE_LEN
    TenCharScreen(title = stringResource(R.string.enter_title), onBack = vm::back) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = vm.entry,
            onValueChange = vm::onEntry,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = EntryStyle,
            placeholder = { Text(stringResource(R.string.enter_hint), style = EntryStyle) },
            isError = showError,
            supportingText = {
                if (showError) Text(stringResource(R.string.enter_invalid))
            },
            // Password type asks keyboards not to learn or suggest from the code.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) vm.join() }),
        )
        Spacer(Modifier.height(24.dp))
        BigButton(stringResource(R.string.enter_join), vm::join, enabled = valid)
    }
}

@Composable
private fun WaitingScreen(vm: DemoViewModel) {
    val context = LocalContext.current
    val code = vm.code
    TenCharScreen(title = stringResource(R.string.waiting_title), onBack = vm::back) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.waiting_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (code != null) {
            // XXXX-XXXX-XXXX-XXXX shown as two lines of two groups.
            val groups = code.split("-")
            CodeBlock(
                lines = groups.chunked(2).map { it.joinToString("  ") },
                spoken = code.filter { it != '-' }.toList().joinToString(" "),
            )
            Spacer(Modifier.height(16.dp))
            BigButton(
                stringResource(R.string.waiting_copy),
                onClick = { copySensitive(context, code) },
                primary = false,
            )
        }
        Spacer(Modifier.height(12.dp))
        BigButton(stringResource(R.string.cancel), vm::cancelWaiting, primary = false)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = vm::previewOtherPhoneJoined, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.waiting_simulate))
        }
    }
}

/**
 * The code is the one secret the spec allows on the clipboard. It is marked
 * sensitive so the system hides it from clipboard previews (Android 13+; older
 * versions ignore the extra).
 */
private fun copySensitive(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun VerifyScreen(vm: DemoViewModel) {
    TenCharScreen(title = stringResource(R.string.verify_title)) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.verify_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        CodeBlock(
            lines = listOf(DemoData.SAMPLE_NUMBER),
            spoken = DemoData.SAMPLE_NUMBER.filter { it != ' ' }.toList().joinToString(" "),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.verify_sample_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        BigButton(stringResource(R.string.verify_match), vm::verifyMatches)
        Spacer(Modifier.height(12.dp))
        BigButton(stringResource(R.string.verify_mismatch), vm::verifyMismatch, primary = false)
    }
}

@Composable
private fun ChatScreen(vm: DemoViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }
    val count = MessageLimits.graphemeCount(vm.draft)
    TenCharScreen(
        title = stringResource(R.string.chat_title),
        scroll = false,
        actions = {
            TextButton(onClick = { vm.open(Screen.Settings) }) {
                Text(stringResource(R.string.chat_settings))
            }
        },
    ) {
        if (vm.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(vm.messages, key = { it.id }) { MessageRow(it) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = vm.draft,
                onValueChange = vm::onDraft,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.chat_counter, count, Constants.MAX_CHARS),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = vm::send,
                enabled = vm.draft.isNotBlank(),
                modifier = Modifier.heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(R.string.chat_send)) }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    val deliveryText = when (message.delivery) {
        Delivery.Queued -> stringResource(R.string.delivery_queued)
        Delivery.Sent -> stringResource(R.string.delivery_sent)
        null -> null
    }
    val spoken = stringResource(
        if (message.mine) R.string.msg_from_you else R.string.msg_from_them,
        message.text,
    ) + (deliveryText?.let { ", $it" } ?: "")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (message.mine) colors.primaryContainer else colors.secondaryContainer,
            contentColor = if (message.mine) colors.onPrimaryContainer else colors.onSecondaryContainer,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.mine) 18.dp else 4.dp,
                bottomEnd = if (message.mine) 4.dp else 18.dp,
            ),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (deliveryText != null) {
            Text(
                text = deliveryText,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SettingsScreen(vm: DemoViewModel) {
    var showNumber by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }
    TenCharScreen(title = stringResource(R.string.settings_title), onBack = vm::back) {
        SectionTitle(stringResource(R.string.settings_autolock))
        Column(Modifier.selectableGroup()) {
            for (option in AutoLock.entries) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = vm.autoLock == option,
                            onClick = { vm.chooseAutoLock(option) },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.autoLock == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(
                            when (option) {
                                AutoLock.Immediately -> R.string.lock_immediately
                                AutoLock.Seconds30 -> R.string.lock_30s
                                AutoLock.Minutes5 -> R.string.lock_5m
                            },
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        BigButton(stringResource(R.string.settings_show_number), { showNumber = true }, primary = false)
        Spacer(Modifier.height(12.dp))
        BigButton(stringResource(R.string.settings_about), { vm.open(Screen.About) }, primary = false)
        Spacer(Modifier.height(12.dp))
        BigButton(stringResource(R.string.settings_forget), { showForget = true }, primary = false)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = vm::showKeysReset, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_preview_keys_reset))
        }
    }

    if (showNumber) {
        AlertDialog(
            onDismissRequest = { showNumber = false },
            properties = SecureDialog,
            title = { Text(stringResource(R.string.settings_show_number)) },
            text = { Text(DemoData.SAMPLE_NUMBER, style = CodeStyle) },
            confirmButton = {
                TextButton(onClick = { showNumber = false }) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }
    if (showForget) {
        AlertDialog(
            onDismissRequest = { showForget = false },
            properties = SecureDialog,
            title = { Text(stringResource(R.string.forget_confirm_title)) },
            text = { Text(stringResource(R.string.forget_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showForget = false; vm.forgetConversation() }) {
                    Text(stringResource(R.string.forget_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForget = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AboutScreen(vm: DemoViewModel) {
    TenCharScreen(title = stringResource(R.string.about_title), onBack = vm::back) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.about_intro), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about_status), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about_review), style = MaterialTheme.typography.bodyLarge)
        SectionTitle(stringResource(R.string.about_not_title))
        Bullet(stringResource(R.string.about_not_1))
        Bullet(stringResource(R.string.about_not_2))
        Bullet(stringResource(R.string.about_not_3))
        Bullet(stringResource(R.string.about_not_4))
        Bullet(stringResource(R.string.about_not_5))
        Bullet(stringResource(R.string.about_not_6))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun KeysResetScreen(vm: DemoViewModel) {
    TenCharScreen(title = stringResource(R.string.app_name)) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.keys_reset_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.keys_reset_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        BigButton(stringResource(R.string.keys_reset_pair), vm::pairAgain)
    }
}
