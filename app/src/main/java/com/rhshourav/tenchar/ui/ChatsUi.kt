package com.rhshourav.tenchar.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhshourav.tenchar.MessageLimits
import com.rhshourav.tenchar.R
import com.rhshourav.tenchar.store.KeysLostReason
import com.rhshourav.tenchar.store.Profile

/** The note shown above the chat list and the pairing screen after something happened (a forgotten chat, a failed pairing). */
@Composable
internal fun NoticeCard(notice: Notice) {
    val warning = notice != Notice.Forgotten
    GlassCard(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        tint = tintOf(if (warning) Tc.Peach else Tc.Lavender, if (warning) Palette.TINT_CAUTION else Palette.TINT_INFO),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(Modifier.padding(18.dp)) {
            GlowDot(if (warning) Tc.Peach else Tc.Lavender, 8.dp, Modifier.padding(top = 9.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(
                    when (notice) {
                        Notice.Mismatch -> R.string.notice_mismatch
                        Notice.Forgotten -> R.string.notice_forgotten
                        Notice.Timeout -> R.string.notice_timeout
                        Notice.Unreachable -> R.string.notice_unreachable
                        Notice.Tampered -> R.string.notice_tampered
                        Notice.Full -> R.string.notice_full
                        Notice.Storage -> R.string.notice_storage
                        Notice.TooManyChats -> R.string.notice_too_many
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A chat's name, or "Chat 3" if neither person has given it one. */
@Composable
internal fun chatName(chat: ChatEntry, index: Int): String =
    chat.name.ifEmpty { stringResource(R.string.chat_unnamed, index + 1) }

// ---- the list of chats ----------------------------------------------------------------------------------

@Composable
internal fun ChatsScreen(vm: AppViewModel) {
    TenCharScreen(
        title = stringResource(R.string.chats_title),
        actions = { GlassChip(stringResource(R.string.chat_settings), { vm.open(Screen.Settings) }) },
        bottomBar = { TcButton(stringResource(R.string.chats_new), vm::startNewChat) },
    ) {
        val notice = vm.notice
        if (notice != null) {
            Spacer(Modifier.height(8.dp))
            NoticeCard(notice)
        }
        Spacer(Modifier.height(12.dp))
        vm.chats.forEachIndexed { index, chat ->
            ChatRow(chat, chatName(chat, index), onClick = { vm.openChat(chat.id) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ChatRow(chat: ChatEntry, name: String, onClick: () -> Unit) {
    val tick = rememberTick()
    GlassCard(shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button) {
                    tick()
                    onClick()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(chat.avatar, name, chat.color, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (chat.verified) chat.endpoints.firstOrNull().orEmpty().removePrefix("https://")
                    else stringResource(R.string.chat_unverified),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chat.verified) Tc.InkMuted else Tc.PeachText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chat.unread > 0) {
                Spacer(Modifier.width(10.dp))
                UnreadBadge(chat.unread)
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
            .clip(CircleShape)
            .background(Tc.Mint)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelMedium.copy(color = Tc.Space),
        )
    }
}

// ---- one chat's settings ---------------------------------------------------------------------------------

@Composable
internal fun ChatSettingsScreen(vm: AppViewModel) {
    val chat = vm.current
    var editMe by remember { mutableStateOf(false) }
    var editThem by remember { mutableStateOf(false) }
    var showNumber by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }

    TenCharScreen(title = stringResource(R.string.chat_settings_title), onBack = vm::back) {
        if (chat == null) return@TenCharScreen
        Spacer(Modifier.height(8.dp))
        GlassCard {
            ProfileRow(stringResource(R.string.profile_you), chat.meName, chat.meAvatar, chat.meColor) { editMe = true }
            RowDivider()
            ProfileRow(stringResource(R.string.profile_them), chat.name, chat.avatar, chat.color) { editThem = true }
        }
        Text(
            text = stringResource(R.string.profile_share_note),
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkFaint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(12.dp))
        GlassCard {
            GlassSwitchRow(
                text = stringResource(R.string.notifications_label),
                checked = chat.notifications,
                onCheckedChange = { vm.setNotifications(chat.id, it) },
                detail = stringResource(R.string.notifications_detail),
            )
            RowDivider()
            GlassRow(stringResource(R.string.settings_show_number), { showNumber = true })
            RowDivider()
            GlassInfoRow(
                text = stringResource(R.string.chat_relays),
                detail = chat.endpoints.joinToString("\n") { it.removePrefix("https://") },
            )
            RowDivider()
            GlassRow(stringResource(R.string.settings_forget), { showForget = true }, caution = true)
        }
        Spacer(Modifier.height(16.dp))

        if (editMe) {
            ProfileDialog(
                title = stringResource(R.string.profile_title_you),
                name = chat.meName, avatar = chat.meAvatar, color = chat.meColor,
                onSave = { p -> vm.setMyProfile(chat.id, p) },
                onReset = null,
                onDismiss = { editMe = false },
            )
        }
        if (editThem) {
            ProfileDialog(
                title = stringResource(R.string.profile_title_them),
                name = chat.name, avatar = chat.avatar, color = chat.color,
                onSave = { p -> vm.setThemProfile(chat.id, p) },
                onReset = if (chat.theirLabelIsMine) ({ vm.setThemProfile(chat.id, null) }) else null,
                onDismiss = { editThem = false },
            )
        }
        if (showNumber) {
            AlertDialog(
                onDismissRequest = { showNumber = false },
                properties = SecureDialog,
                containerColor = Tc.Plum,
                titleContentColor = Tc.Ink,
                shape = RoundedCornerShape(28.dp),
                title = { Text(stringResource(R.string.settings_show_number)) },
                text = { Text(vm.verification.orEmpty(), style = CodeStyle.copy(brush = Tc.Brand)) },
                confirmButton = {
                    TextButton(
                        onClick = { showNumber = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = Tc.Mint),
                    ) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
        if (showForget) {
            AlertDialog(
                onDismissRequest = { showForget = false },
                properties = SecureDialog,
                containerColor = Tc.Plum,
                titleContentColor = Tc.Ink,
                textContentColor = Tc.InkMuted,
                shape = RoundedCornerShape(28.dp),
                title = { Text(stringResource(R.string.forget_confirm_title)) },
                text = { Text(stringResource(R.string.forget_confirm_body)) },
                confirmButton = {
                    TextButton(
                        onClick = { showForget = false; vm.forgetChat(chat.id) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Tc.PeachText),
                    ) { Text(stringResource(R.string.forget_confirm_yes)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showForget = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = Tc.Ink),
                    ) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
    PushDialogs(vm)
}

@Composable
private fun ProfileRow(label: String, name: String, avatar: String, color: Int, onClick: () -> Unit) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button) {
                tick()
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatar, name, color, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Tc.InkMuted)
            Text(
                text = name.ifEmpty { stringResource(R.string.profile_none) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TcIconView(TcIcon.ChevronRight, Tc.InkFaint, Modifier.size(20.dp))
    }
}

private val EMOJI = listOf(
    "😀", "😎", "🥳", "🤓", "😇", "🦊",
    "🐱", "🐶", "🐼", "🦁", "🐸", "🦄",
    "🌸", "🌿", "🔥", "⭐", "🌙", "🍀",
    "🎧", "🎨", "⚡", "🚀", "💎", "🎈",
)

/** Name, avatar and colour for one person in one chat. */
@Composable
internal fun ProfileDialog(
    title: String,
    name: String,
    avatar: String,
    color: Int,
    onSave: (Profile) -> Unit,
    onReset: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var n by remember { mutableStateOf(name) }
    var a by remember { mutableStateOf(avatar) }
    var c by remember { mutableStateOf(color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        containerColor = Tc.Plum,
        titleContentColor = Tc.Ink,
        textContentColor = Tc.InkMuted,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(a, n, c, 64.dp)
                    Spacer(Modifier.width(16.dp))
                    GlassCard(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        TextField(
                            value = n,
                            onValueChange = { n = MessageLimits.clampTo(it, Profile.MAX_NAME_CHARS, Profile.MAX_NAME_BYTES) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            placeholder = { Text(stringResource(R.string.profile_name_hint)) },
                            colors = tcFieldColors(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                    }
                }
                Text(stringResource(R.string.profile_avatar), style = MaterialTheme.typography.labelMedium)
                for (row in EMOJI.chunked(6)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (e in row) EmojiButton(e, selected = a == e) { a = e }
                    }
                }
                GlassCard(shape = RoundedCornerShape(16.dp)) {
                    TextField(
                        value = a,
                        onValueChange = { a = Profile.cleanAvatar(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = { Text(stringResource(R.string.profile_avatar_hint)) },
                        colors = tcFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }
                TextButton(
                    onClick = { a = "" },
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.Lavender),
                ) { Text(stringResource(R.string.profile_use_initial)) }
                Text(stringResource(R.string.profile_colour), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (i in 0 until Palette.AVATARS.size) ColourDot(i, selected = c == i) { c = i }
                }
                if (onReset != null) {
                    TextButton(
                        onClick = {
                            onReset()
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Tc.Lavender),
                    ) { Text(stringResource(R.string.profile_reset_them)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(Profile.of(n, a, c))
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Tc.Mint),
            ) { Text(stringResource(R.string.relay_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Tc.Ink),
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun EmojiButton(emoji: String, selected: Boolean, onSelect: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f))
            .then(if (selected) Modifier.border(2.dp, Tc.Mint, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = emoji },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ColourDot(index: Int, selected: Boolean, onSelect: () -> Unit) {
    val (from, to) = Palette.AVATARS[index]
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(from), Color(to))))
            .then(if (selected) Modifier.border(3.dp, Tc.Ink, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = "${index + 1}" },
    )
}

// ---- notifications ----------------------------------------------------------------------------------------

@Composable
private fun PushDialogs(vm: AppViewModel) {
    val address = vm.pushHelp
    val problem = vm.pushProblem
    val context = LocalContext.current
    if (address != null) {
        AlertDialog(
            onDismissRequest = vm::dismissPushHelp,
            properties = SecureDialog,
            containerColor = Tc.Plum,
            titleContentColor = Tc.Ink,
            textContentColor = Tc.InkMuted,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.notifications_help_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notifications_help_body), style = MaterialTheme.typography.bodyMedium)
                    GlassCard(shape = RoundedCornerShape(16.dp)) {
                        Text(
                            text = address,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = Tc.Ink,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    TextButton(
                        onClick = { copySensitive(context, address) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Tc.Lavender),
                    ) { Text(stringResource(R.string.notifications_copy)) }
                    Text(stringResource(R.string.notifications_privacy), style = MaterialTheme.typography.labelMedium, color = Tc.InkMuted)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { openInNtfy(context, address) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.Mint),
                ) { Text(stringResource(R.string.notifications_open)) }
            },
            dismissButton = {
                TextButton(
                    onClick = vm::dismissPushHelp,
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.Ink),
                ) { Text(stringResource(R.string.notifications_done)) }
            },
        )
    }
    if (problem != null) {
        AlertDialog(
            onDismissRequest = vm::dismissPushProblem,
            properties = SecureDialog,
            containerColor = Tc.Plum,
            titleContentColor = Tc.Ink,
            textContentColor = Tc.InkMuted,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.notifications_label)) },
            text = {
                Text(
                    stringResource(
                        when (problem) {
                            PushProblem.Unsupported -> R.string.notifications_unsupported
                            PushProblem.Rejected -> R.string.notifications_rejected
                            PushProblem.Unreachable -> R.string.notice_unreachable
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = vm::dismissPushProblem,
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.Mint),
                ) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }
}

/** ntfy registers itself for ntfy:// links. If it is not installed, the address is copied so it can be pasted into any push app. */
private fun openInNtfy(context: Context, address: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address.replaceFirst("https://", "ntfy://"))))
    } catch (e: ActivityNotFoundException) {
        copySensitive(context, address)
    }
}

// ---- settings for the whole app --------------------------------------------------------------------------

@Composable
internal fun SettingsScreen(vm: AppViewModel) {
    var showRelay by remember { mutableStateOf(false) }
    var showFallback by remember { mutableStateOf(false) }
    var showPush by remember { mutableStateOf(false) }
    var showDefault by remember { mutableStateOf(false) }
    val me = vm.defaultProfile

    TenCharScreen(title = stringResource(R.string.settings_title), onBack = vm::back) {
        SectionTitle(stringResource(R.string.settings_autolock))
        AutoLockCard(vm)
        Spacer(Modifier.height(24.dp))
        GlassCard {
            ProfileRow(stringResource(R.string.settings_default_profile), me.name, me.avatar, me.color) { showDefault = true }
            RowDivider()
            GlassRow(
                text = stringResource(R.string.settings_relay),
                onClick = { showRelay = true },
                detail = vm.relayUrl.removePrefix("https://"),
            )
            RowDivider()
            GlassSwitchRow(
                text = stringResource(R.string.fallback_label),
                checked = vm.fallbackEnabled,
                onCheckedChange = vm::enableFallback,
                detail = stringResource(R.string.fallback_detail),
            )
            if (vm.fallbackEnabled) {
                RowDivider()
                GlassRow(
                    text = stringResource(R.string.fallback_address),
                    onClick = { showFallback = true },
                    detail = vm.fallbackUrl.removePrefix("https://"),
                )
            }
            RowDivider()
            GlassRow(
                text = stringResource(R.string.settings_push_server),
                onClick = { showPush = true },
                detail = vm.pushServer.removePrefix("https://"),
            )
            RowDivider()
            GlassRow(stringResource(R.string.settings_about), { vm.open(Screen.About) })
        }
        Text(
            text = stringResource(R.string.relay_change_note),
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkFaint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
    }

    if (showDefault) {
        ProfileDialog(
            title = stringResource(R.string.settings_default_profile),
            name = me.name, avatar = me.avatar, color = me.color,
            onSave = vm::updateDefaultProfile,
            onReset = null,
            onDismiss = { showDefault = false },
        )
    }
    if (showRelay) {
        RelayDialog(
            title = stringResource(R.string.relay_title),
            current = vm.relayUrl,
            onSave = vm::saveRelay,
            onReset = vm::resetRelay,
            onDismiss = { showRelay = false },
        )
    }
    if (showFallback) {
        RelayDialog(
            title = stringResource(R.string.fallback_address),
            current = vm.fallbackUrl,
            onSave = vm::saveFallback,
            onReset = vm::resetFallback,
            onDismiss = { showFallback = false },
        )
    }
    if (showPush) {
        RelayDialog(
            title = stringResource(R.string.push_server_title),
            current = vm.pushServer,
            onSave = vm::savePushServer,
            onReset = vm::resetPushServer,
            onDismiss = { showPush = false },
            body = stringResource(R.string.push_server_body),
        )
    }
}

// ---- when the saved chats cannot be opened -----------------------------------------------------------------

/**
 * Shown when the key that protects the chats is gone. **Nothing has been deleted.** The person can try again (a
 * mistaken or temporary cause then costs nothing) or choose to erase everything; only that choice removes data.
 */
@Composable
internal fun KeysResetScreen(vm: AppViewModel, onUnlock: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val reason = vm.lostReason
    TenCharScreen(
        contentArrangement = Arrangement.Center,
        contentAlignment = Alignment.CenterHorizontally,
        bottomBar = {
            TcButton(stringResource(R.string.keys_reset_retry), {
                vm.retryUnlock()
                onUnlock()
            })
            TcButton(stringResource(R.string.keys_reset_erase), { confirm = true }, kind = ButtonKind.Caution)
        },
    ) {
        HeroBlock(stringResource(R.string.keys_reset_title), stringResource(R.string.keys_reset_body), halo = Tc.Peach)
        if (reason != null) {
            Spacer(Modifier.height(16.dp))
            BodyText(
                stringResource(
                    when (reason) {
                        KeysLostReason.KeyInvalidated -> R.string.lost_key_invalidated
                        KeysLostReason.KeyMissing -> R.string.lost_key_missing
                        KeysLostReason.VaultFileDamaged -> R.string.lost_vault_damaged
                        KeysLostReason.StateUnreadable -> R.string.lost_state_unreadable
                    },
                ),
                center = true,
            )
        }
        Spacer(Modifier.height(12.dp))
        BodyText(stringResource(R.string.keys_reset_kept), center = true)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            properties = SecureDialog,
            containerColor = Tc.Plum,
            titleContentColor = Tc.Ink,
            textContentColor = Tc.InkMuted,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.erase_confirm_title)) },
            text = { Text(stringResource(R.string.erase_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        vm.eraseAndStartOver()
                        onUnlock()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.PeachText),
                ) { Text(stringResource(R.string.erase_confirm_yes)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.Ink),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}