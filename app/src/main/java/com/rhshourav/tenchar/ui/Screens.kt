package com.rhshourav.tenchar.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.rhshourav.tenchar.CodeFormat
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.MessageLimits
import com.rhshourav.tenchar.Pairing
import com.rhshourav.tenchar.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Dialogs are separate windows, so they need FLAG_SECURE of their own. */
internal val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

@Composable
fun TenCharApp(
    vm: AppViewModel,
    deviceSecure: Boolean,
    onOpenSecuritySettings: () -> Unit,
    onUnlock: () -> Unit,
) {
    TenCharTheme {
        val reduceMotion = LocalReduceMotion.current
        Box(Modifier.fillMaxSize()) {
            // One aurora for the whole app, so screen changes never restart it.
            AuroraBackground()
            if (!deviceSecure) {
                LockRequiredScreen(onOpenSecuritySettings)
            } else {
                BackHandler(
                    enabled = when (vm.screen) {
                        Screen.Chat, Screen.ChatSettings, Screen.Verify, Screen.Settings, Screen.About,
                        Screen.EnterCode, Screen.Waiting -> true
                        // From the pairing screen, back goes to the chat list, if there is one.
                        Screen.Pair -> vm.chats.isNotEmpty()
                        else -> false
                    },
                ) { vm.back() }
                AnimatedContent(
                    targetState = vm.screen,
                    transitionSpec = {
                        // Locking is never animated: nothing may linger on screen.
                        if (reduceMotion || targetState == Screen.Gate) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (fadeIn(tween(320, delayMillis = 80)) +
                                scaleIn(tween(320, delayMillis = 80, easing = EaseOutCubic), initialScale = 0.96f)) togetherWith
                                fadeOut(tween(120))
                        }
                    },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Setup -> SetupScreen(vm, onUnlock)
                        Screen.Gate -> GateScreen(vm, onUnlock)
                        Screen.Chats -> ChatsScreen(vm)
                        Screen.ChatSettings -> ChatSettingsScreen(vm)
                        Screen.Pair -> PairScreen(vm)
                        Screen.EnterCode -> EnterCodeScreen(vm)
                        Screen.Waiting -> WaitingScreen(vm)
                        Screen.Verify -> VerifyScreen(vm)
                        Screen.Chat -> ChatScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.About -> AboutScreen(vm)
                        Screen.KeysReset -> KeysResetScreen(vm, onUnlock)
                    }
                }
            }
        }
    }
}

@Composable
private fun LockRequiredScreen(onOpenSettings: () -> Unit) {
    TenCharScreen(
        contentArrangement = Arrangement.Center,
        contentAlignment = Alignment.CenterHorizontally,
        bottomBar = { TcButton(stringResource(R.string.lockreq_open), onOpenSettings) },
    ) {
        HeroBlock(stringResource(R.string.lockreq_title), stringResource(R.string.lockreq_body), halo = Tc.Peach)
    }
}

@Composable
private fun GateScreen(vm: AppViewModel, onUnlock: () -> Unit) {
    TenCharScreen(
        contentArrangement = Arrangement.Center,
        contentAlignment = Alignment.CenterHorizontally,
        bottomBar = { TcButton(stringResource(R.string.gate_unlock), onUnlock, enabled = !vm.unlocking) },
    ) {
        HeroBlock(stringResource(R.string.gate_title), stringResource(R.string.gate_body))
        if (vm.unlockFailed) {
            Text(
                text = stringResource(R.string.gate_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = Tc.Peach,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** First run only: the choices that shape how the app talks to the world. */
@Composable
private fun SetupScreen(vm: AppViewModel, onUnlock: () -> Unit) {
    var showRelay by remember { mutableStateOf(false) }
    var showFallback by remember { mutableStateOf(false) }
    TenCharScreen(
        bottomBar = {
            TcButton(stringResource(R.string.setup_continue), {
                vm.finishSetup()
                onUnlock()
            })
        },
    ) {
        HeroBlock(
            title = stringResource(R.string.setup_title),
            body = stringResource(R.string.setup_body),
            diameter = 120.dp,
        )
        SectionTitle(stringResource(R.string.setup_relays))
        GlassCard {
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
        }
        Text(
            text = stringResource(R.string.setup_relays_note),
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkFaint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        )
        SectionTitle(stringResource(R.string.settings_autolock))
        AutoLockCard(vm)
        Spacer(Modifier.height(16.dp))
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
}

@Composable
private fun PairScreen(vm: AppViewModel) {
    val hasChats = vm.chats.isNotEmpty()
    TenCharScreen(
        title = if (hasChats) stringResource(R.string.chats_new) else null,
        onBack = if (hasChats) vm::back else null,
        contentArrangement = Arrangement.Center,
        contentAlignment = Alignment.CenterHorizontally,
        bottomBar = {
            TcButton(stringResource(R.string.pair_make), vm::startWaiting)
            TcButton(stringResource(R.string.pair_have), vm::openEnterCode, kind = ButtonKind.Glass)
        },
    ) {
        val notice = vm.notice
        if (notice != null) {
            NoticeCard(notice)
            Spacer(Modifier.height(28.dp))
        }
        HeroBlock(stringResource(R.string.pair_title), stringResource(R.string.pair_body), diameter = 132.dp)
        Spacer(Modifier.height(28.dp))
        RelayLink(vm)
    }
}

/**
 * Shows which relay will be used and lets the person change it. It sits on the
 * pairing screens because both phones must use the same relay to find each other.
 */
@Composable
internal fun RelayLink(vm: AppViewModel) {
    var show by remember { mutableStateOf(false) }
    GlassCard(shape = RoundedCornerShape(22.dp)) {
        GlassRow(
            text = stringResource(R.string.relay_for_chat),
            onClick = { show = true },
            detail = vm.relayUrl.removePrefix("https://"),
        )
    }
    if (show) {
        RelayDialog(
            title = stringResource(R.string.relay_title),
            current = vm.relayUrl,
            onSave = vm::saveRelay,
            onReset = vm::resetRelay,
            onDismiss = { show = false },
        )
    }
}

@Composable
private fun EnterCodeScreen(vm: AppViewModel) {
    val valid = Pairing.isValid(vm.entry)
    val symbols = vm.entry.length
    var focused by remember { mutableStateOf(false) }
    // Only complain once the person has left the field with an unfinished code.
    val showError = !focused && symbols in 1 until Constants.CODE_LEN
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    TenCharScreen(
        title = stringResource(R.string.enter_title),
        onBack = vm::back,
        bottomBar = { TcButton(stringResource(R.string.enter_join), vm::join, enabled = valid) },
    ) {
        Spacer(Modifier.height(16.dp))
        GlassCard(shape = RoundedCornerShape(24.dp)) {
            TextField(
                value = vm.entry,
                onValueChange = vm::onEntry,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = EntryStyle,
                // The field holds plain letters; the hyphens are only drawn, so typing never rewrites the text and the
                // cursor stays after the letter just typed (it used to jump back in front of it after the 4th, 8th and 12th).
                visualTransformation = CodeTransformation,
                placeholder = { Text(stringResource(R.string.enter_hint), style = EntryStyle) },
                isError = showError,
                colors = tcFieldColors(),
                // Password type asks keyboards not to learn or suggest from the code.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (valid) vm.join() }),
            )
        }
        CodeProgress(filled = symbols)
        Spacer(Modifier.height(24.dp))
        RelayLink(vm)
        if (showError) {
            Text(
                text = stringResource(R.string.enter_invalid),
                style = MaterialTheme.typography.bodyMedium,
                color = Tc.Peach,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Draws XXXX-XXXX-XXXX-XXXX over the plain letters the field holds. See [CodeFormat] for the cursor mapping. */
private object CodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val rawLength = text.text.length
        return TransformedText(
            AnnotatedString(CodeFormat.display(text.text)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = CodeFormat.originalToTransformed(offset)
                override fun transformedToOriginal(offset: Int): Int = CodeFormat.transformedToOriginal(offset, rawLength)
            },
        )
    }
}

/** Sixteen dots in four groups. Each lights up mint as its character is typed. */
@Composable
private fun CodeProgress(filled: Int) {
    val groups = Constants.CODE_LEN / Constants.CODE_GROUP_LEN
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.Center,
    ) {
        for (g in 0 until groups) {
            if (g > 0) Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until Constants.CODE_GROUP_LEN) {
                    CodeDot(on = g * Constants.CODE_GROUP_LEN + i < filled)
                }
            }
        }
    }
}

@Composable
private fun CodeDot(on: Boolean) {
    val lit by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "dot",
    )
    Box(
        Modifier
            .size(10.dp)
            .drawBehind {
                val r = this.size.minDimension / 2f
                drawCircle(Color.White.copy(alpha = 0.16f), radius = r)
                val k = lit.coerceAtLeast(0f)
                if (k > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Tc.Mint.copy(alpha = (0.55f * k).coerceIn(0f, 1f)), Color.Transparent),
                            center = center,
                            radius = r * 2.8f,
                        ),
                        radius = r * 2.8f,
                    )
                    drawCircle(Tc.Mint, radius = r * k)
                }
            },
    )
}

@Composable
private fun WaitingScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val code = vm.code
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    TenCharScreen(
        title = stringResource(if (vm.joining) R.string.joining_title else R.string.waiting_title),
        onBack = vm::back,
        bottomBar = {
            if (code != null) {
                TcButton(
                    text = stringResource(if (copied) R.string.waiting_copied else R.string.waiting_copy),
                    onClick = {
                        copySensitive(context, code)
                        copied = true
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    },
                    kind = ButtonKind.Glass,
                )
            }
            TcButton(stringResource(R.string.cancel), vm::cancelWaiting, kind = ButtonKind.Glass)
        },
    ) {
        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(if (vm.joining) R.string.joining_body else R.string.waiting_body))
        Spacer(Modifier.height(24.dp))
        if (code != null) {
            // XXXX-XXXX-XXXX-XXXX shown as two lines of two groups.
            CodeBlock(
                lines = code.split("-").chunked(2).map { it.joinToString("  ") },
                spoken = code.filter { it != '-' }.toList().joinToString(" "),
            )
        }
        Spacer(Modifier.height(28.dp))
        WaitingDots(Modifier.align(Alignment.CenterHorizontally))
    }
}

/** The three accent dots, rising in turn. Still when animations are off. */
@Composable
private fun WaitingDots(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "waiting")
    val colors = listOf(Tc.Mint, Tc.Peach, Tc.LavenderSoft)
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        colors.forEachIndexed { i, color ->
            val lift: State<Float> = if (reduceMotion) {
                remember { mutableFloatStateOf(0f) }
            } else {
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(i * 160),
                    ),
                    label = "wait-dot-$i",
                )
            }
            GlowDot(
                color = color,
                diameter = 10.dp,
                modifier = Modifier.graphicsLayer {
                    translationY = -lift.value * 10.dp.toPx()
                    alpha = 0.55f + 0.45f * lift.value
                },
            )
        }
    }
}

/**
 * The code is the one secret the spec allows on the clipboard. It is marked
 * sensitive so the system hides it from clipboard previews (Android 13+; older
 * versions ignore the extra).
 */
internal fun copySensitive(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun VerifyScreen(vm: AppViewModel) {
    TenCharScreen(
        title = stringResource(R.string.verify_title),
        onBack = vm::back,
        bottomBar = {
            TcButton(stringResource(R.string.verify_match), vm::verifyMatches)
            TcButton(stringResource(R.string.verify_mismatch), vm::verifyMismatch, kind = ButtonKind.Caution)
        },
    ) {
        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.verify_body))
        Spacer(Modifier.height(24.dp))
        val number = vm.verification.orEmpty()
        CodeBlock(
            lines = listOf(number),
            spoken = number.filter { it != ' ' }.toList().joinToString(" "),
        )
    }
}

@Composable
private fun ChatScreen(vm: AppViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }
    // Remembers which messages have already played their entrance, so scrolling
    // back to an old one does not replay it.
    val seen = remember { HashSet<Long>() }
    val chat = vm.current
    var viewing by remember { mutableStateOf<ChatMessage?>(null) }
    // Closing the viewer when the chat changes or locks keeps decrypted media from outliving its screen.
    LaunchedEffect(vm.currentId) { viewing = null }
    TenCharScreen(
        title = if (chat != null) chatName(chat, vm.chats.indexOf(chat)) else stringResource(R.string.chat_title),
        onBack = vm::back,
        scroll = false,
        leading = if (chat != null) ({ Avatar(chat.avatar, chat.name, chat.color, 40.dp) }) else null,
        actions = { GlassChip(stringResource(R.string.chat_settings), { vm.open(Screen.ChatSettings) }) },
    ) {
        ConnectionBanner(vm.connection)
        if (vm.messages.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LogoHero(diameter = 112.dp)
                Spacer(Modifier.height(20.dp))
                BodyText(stringResource(R.string.chat_empty), center = true)
            }
        } else {
            val lastMineId = remember(vm.messages.size) { vm.messages.lastOrNull { it.mine }?.id }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(vm.messages, key = { _, m -> m.id }) { index, message ->
                    val previous = vm.messages.getOrNull(index - 1)
                    val next = vm.messages.getOrNull(index + 1)
                    val animateIn = remember(message.id) { seen.add(message.id) }
                    // The time shows under the last bubble of a run from one person, not under every bubble.
                    val endOfRun = next == null || next.mine != message.mine || message.at == 0L || next.at - message.at > RUN_GAP_MS
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (message.at > 0L && (previous == null || previous.at == 0L || !isSameDay(previous.at, message.at))) {
                            DayChip(message.at)
                        }
                        MessageRow(
                            vm = vm,
                            message = message,
                            animateIn = animateIn,
                            showTime = endOfRun,
                            showSent = message.id == lastMineId,
                            onOpenMedia = { viewing = it },
                        )
                    }
                }
            }
        }
        vm.mediaNotice?.let { MediaNoticeBanner(it, vm::dismissMediaNotice) }
        if (vm.mediaBusy) PreparingBar()
        Composer(vm)
    }
    viewing?.let { MediaViewer(vm, it) { viewing = null } }
}

/** Messages from one person closer together than this share one time label. */
private const val RUN_GAP_MS = 2 * 60 * 1000L

private fun isSameDay(a: Long, b: Long): Boolean {
    val day = DateUtils.DAY_IN_MILLIS
    val zone = java.util.TimeZone.getDefault()
    return (a + zone.getOffset(a)) / day == (b + zone.getOffset(b)) / day
}

@Composable
private fun DayChip(at: Long) {
    val context = LocalContext.current
    val text = when {
        DateUtils.isToday(at) -> stringResource(R.string.day_today)
        DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.day_yesterday)
        else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkMuted,
            modifier = Modifier.glass(CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Says so when no relay can be reached, or when the relays no longer hold the conversation. */
@Composable
private fun ConnectionBanner(connection: Connection) {
    if (connection == Connection.Online) return
    val gone = connection == Connection.Gone
    GlassCard(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        tint = tintOf(Tc.Peach, Palette.TINT_CAUTION),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlowDot(Tc.Peach, 7.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(if (gone) R.string.chat_gone else R.string.chat_offline),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MessageRow(
    vm: AppViewModel,
    message: ChatMessage,
    animateIn: Boolean,
    showTime: Boolean,
    showSent: Boolean,
    onOpenMedia: (ChatMessage) -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    val enter = remember { Animatable(if (animateIn && !reduceMotion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
    }
    val attachment = message.attachment
    // "Queued" is always worth saying. "Sent" only under your latest message, so a long run of bubbles stays quiet.
    val deliveryText = when (message.delivery) {
        Delivery.Queued -> stringResource(R.string.delivery_queued)
        Delivery.Sent -> if (showSent) stringResource(R.string.delivery_sent) else null
        null -> null
    }
    val timeText = if (showTime && message.at > 0L) DateUtils.formatDateTime(context, message.at, DateUtils.FORMAT_SHOW_TIME) else null
    val content = attachment?.let { attachmentLabel(it) } ?: message.text
    val spoken = stringResource(
        if (message.mine) R.string.msg_from_you else R.string.msg_from_them,
        content,
    ) + (timeText?.let { ", $it" } ?: "") + (deliveryText?.let { ", $it" } ?: "")
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = if (message.mine) 22.dp else 6.dp,
        bottomEnd = if (message.mine) 6.dp else 22.dp,
    )
    val tint = if (message.mine) {
        Brush.linearGradient(
            listOf(Tc.Indigo.copy(alpha = Palette.BUBBLE_MINE_INDIGO), Tc.Lavender.copy(alpha = Palette.BUBBLE_MINE_LAVENDER)),
        )
    } else {
        tintOf(Tc.Peach, Palette.BUBBLE_THEIRS)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = enter.value
                alpha = (p * 1.8f).coerceIn(0f, 1f)
                scaleX = 0.86f + 0.14f * p
                scaleY = 0.86f + 0.14f * p
                translationY = (1f - p) * 14.dp.toPx()
                transformOrigin = TransformOrigin(if (message.mine) 1f else 0f, 1f)
            }
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
    ) {
        if (attachment != null) {
            // A photo or video sits in the bubble with a small margin; a voice message brings its own padding.
            val inset = if (attachment.kind == Constants.KIND_VOICE) 0.dp else 4.dp
            Box(Modifier.glass(shape, tint).padding(inset)) {
                AttachmentContent(vm, message, onOpenMedia)
            }
        } else {
            Box(Modifier.widthIn(max = 300.dp).glass(shape, tint)) {
                Text(
                    text = message.text,
                    // Short messages stay large; a paragraph at that size would fill the screen.
                    style = (if (message.text.length <= 24) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                        .copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
        if (timeText != null || deliveryText != null) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (timeText != null) Text(timeText, style = MaterialTheme.typography.labelSmall, color = Tc.InkMuted)
                if (timeText != null && deliveryText != null) Spacer(Modifier.width(8.dp))
                if (deliveryText != null) {
                    GlowDot(if (message.delivery == Delivery.Sent) Tc.Mint else Tc.Peach, 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(deliveryText, style = MaterialTheme.typography.labelSmall, color = Tc.InkMuted)
                }
            }
        }
    }
}

@Composable
private fun Composer(vm: AppViewModel) {
    val view = LocalView.current
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val count = MessageLimits.graphemeCount(vm.draft)
    val fraction = MessageLimits.fillFraction(vm.draft)
    val canSend = vm.draft.isNotBlank()
    // Set when the byte limit, not the character limit, refused an edit: the message box is full.
    var byteLimitHit by remember { mutableStateOf(false) }
    // Text beyond one message is sent as several; say how many before it goes.
    val messageCount = remember(vm.draft, fraction) {
        if (fraction >= 1f) MessageLimits.split(vm.draft).size else 1
    }
    val showByteHint = byteLimitHit && vm.draft.isNotEmpty()
    val atLimit = count >= Constants.MAX_CHARS || showByteHint

    // The system photo picker needs no storage permission: the app only ever sees what the person picks.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachPicked(uri)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.micDenied()
    }
    fun startVoice() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun send() {
        if (!canSend) return
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        vm.send()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
                .graphicsLayer { translationX = shake.value * density }
                .glass(RoundedCornerShape(34.dp))
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.recording) {
                RecordingRow(vm, Modifier.weight(1f))
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .glassClickable(!vm.mediaBusy, Role.Button) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        .semantics { contentDescription = context.getString(R.string.media_attach) },
                    contentAlignment = Alignment.Center,
                ) {
                    TcIconView(TcIcon.Plus, if (vm.mediaBusy) Tc.InkFaint else Tc.Ink, Modifier.size(24.dp))
                }
                TextField(
                    value = vm.draft,
                    onValueChange = { raw ->
                        if (!MessageLimits.fitsDraft(raw)) {
                            // Under the character limit but still refused: the byte limit is what bound.
                            byteLimitHit = MessageLimits.graphemeCount(raw) <= Constants.MAX_DRAFT_CHARS
                            // The box is full: the text is cut, so say so with a nudge.
                            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            if (!reduceMotion) {
                                scope.launch {
                                    shake.snapTo(0f)
                                    shake.animateTo(
                                        0f,
                                        keyframes {
                                            durationMillis = 300
                                            -9f at 50
                                            7f at 110
                                            -4f at 180
                                            2f at 240
                                        },
                                    )
                                }
                            }
                        } else {
                            byteLimitHit = false
                        }
                        vm.onDraft(raw)
                    },
                    modifier = Modifier.weight(1f),
                    // Up to 500 characters in a message: let the field grow to a few lines instead of scrolling sideways.
                    singleLine = false,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                    placeholder = { Text(stringResource(R.string.chat_input_hint), style = MaterialTheme.typography.bodyLarge) },
                    colors = tcFieldColors(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                if (canSend) {
                    Text(
                        text = stringResource(R.string.chat_counter, count, Constants.MAX_CHARS),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (atLimit) Tc.PeachText else Tc.InkMuted,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    SendButton(fraction = fraction, enabled = true, label = stringResource(R.string.chat_send), onClick = ::send)
                } else {
                    MicButton(enabled = !vm.mediaBusy, label = stringResource(R.string.media_record), onClick = ::startVoice)
                }
            }
        }
        val hint = when {
            showByteHint -> stringResource(R.string.chat_limit_bytes)
            messageCount > 1 -> stringResource(R.string.chat_split_hint, messageCount)
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelMedium,
                color = if (showByteHint) Tc.Peach else Tc.InkMuted,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Where the send button sits while the message box is empty: tap to start a voice message. */
@Composable
private fun MicButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .glassClickable(enabled, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(if (enabled) Tc.Brand else SolidColor(Color.White.copy(alpha = 0.10f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TcIconView(TcIcon.Mic, if (enabled) Tc.Space else Tc.InkFaint, Modifier.size(22.dp))
        }
    }
}

/**
 * A send button wrapped in a ring that fills as the message uses up its room. [fraction] is how close
 * the message is to whichever limit is nearer, the 500 characters or the 2,048 bytes of one message.
 */
@Composable
private fun SendButton(fraction: Float, enabled: Boolean, label: String, onClick: () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "ring",
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            fraction >= 1f -> Tc.Peach
            fraction >= 0.8f -> Tc.Lavender
            else -> Tc.Mint
        },
        label = "ring-color",
    )
    Box(
        modifier = Modifier
            .size(52.dp)
            .glassClickable(enabled, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceAtMost(1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier
                .size(38.dp)
                .background(if (enabled) Tc.Brand else SolidColor(Color.White.copy(alpha = 0.10f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TcIconView(TcIcon.ArrowUp, if (enabled) Tc.Space else Tc.InkFaint, Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun RelayDialog(
    title: String,
    current: String,
    onSave: (String) -> Boolean,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    /** Overrides the default explanation, which is about relays. */
    body: String? = null,
) {
    var input by remember { mutableStateOf(current) }
    var invalid by remember { mutableStateOf(false) }

    fun save() {
        if (onSave(input)) onDismiss() else invalid = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        containerColor = Tc.Plum,
        titleContentColor = Tc.Ink,
        textContentColor = Tc.InkMuted,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body ?: stringResource(R.string.relay_body), style = MaterialTheme.typography.bodyMedium)
                GlassCard(shape = RoundedCornerShape(16.dp)) {
                    TextField(
                        value = input,
                        onValueChange = {
                            input = it
                            invalid = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = { Text(stringResource(R.string.relay_hint)) },
                        isError = invalid,
                        colors = tcFieldColors(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                }
                if (invalid) {
                    Text(
                        text = stringResource(R.string.relay_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tc.Peach,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                TextButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.Lavender),
                ) { Text(stringResource(R.string.relay_reset)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { save() },
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
internal fun AutoLockCard(vm: AppViewModel) {
    GlassCard {
        Column(Modifier.selectableGroup()) {
            AutoLock.entries.forEachIndexed { index, option ->
                if (index > 0) RowDivider()
                LockOptionRow(
                    selected = vm.autoLock == option,
                    label = stringResource(
                        when (option) {
                            AutoLock.Immediately -> R.string.lock_immediately
                            AutoLock.Seconds30 -> R.string.lock_30s
                            AutoLock.Minutes5 -> R.string.lock_5m
                        },
                    ),
                    onSelect = { vm.chooseAutoLock(option) },
                )
            }
        }
    }
}

@Composable
internal fun LockOptionRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                onClick = {
                    tick()
                    onSelect()
                },
                role = Role.RadioButton,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        GlowRadio(selected)
    }
}

@Composable
private fun AboutScreen(vm: AppViewModel) {
    TenCharScreen(title = stringResource(R.string.about_title), onBack = vm::back) {
        Spacer(Modifier.height(8.dp))
        GlassCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.about_intro), style = MaterialTheme.typography.titleMedium)
                BodyText(stringResource(R.string.about_status))
                BodyText(stringResource(R.string.about_review))
            }
        }
        SectionTitle(stringResource(R.string.about_not_title))
        GlassCard {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Bullet(stringResource(R.string.about_not_1))
                Bullet(stringResource(R.string.about_not_2))
                Bullet(stringResource(R.string.about_not_3))
                Bullet(stringResource(R.string.about_not_4))
                Bullet(stringResource(R.string.about_not_5))
                Bullet(stringResource(R.string.about_not_6))
                Bullet(stringResource(R.string.about_not_7))
                Bullet(stringResource(R.string.about_not_8))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}