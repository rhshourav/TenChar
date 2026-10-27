package com.rhshourav.tenchar.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
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
private val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

@Composable
fun TenCharApp(
    vm: DemoViewModel,
    deviceSecure: Boolean,
    onOpenSecuritySettings: () -> Unit,
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
                    enabled = vm.screen == Screen.Settings || vm.screen == Screen.About ||
                        vm.screen == Screen.EnterCode || vm.screen == Screen.Waiting,
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
private fun GateScreen(vm: DemoViewModel) {
    TenCharScreen(
        contentArrangement = Arrangement.Center,
        contentAlignment = Alignment.CenterHorizontally,
        bottomBar = { TcButton(stringResource(R.string.gate_unlock), vm::unlock) },
    ) {
        HeroBlock(stringResource(R.string.gate_title), stringResource(R.string.gate_body))
    }
}

@Composable
private fun PairScreen(vm: DemoViewModel) {
    TenCharScreen(
        contentArrangement = Arrangement.Center,
        contentAlignment = Alignment.CenterHorizontally,
        bottomBar = {
            TcButton(stringResource(R.string.pair_make), vm::startWaiting)
            TcButton(stringResource(R.string.pair_have), vm::openEnterCode, kind = ButtonKind.Glass)
        },
    ) {
        val notice = vm.notice
        if (notice != null) {
            val warning = notice == Notice.Mismatch
            GlassCard(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                tint = tintOf(if (warning) Tc.Peach else Tc.Lavender, 0.14f),
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(Modifier.padding(18.dp)) {
                    GlowDot(if (warning) Tc.Peach else Tc.Lavender, 8.dp, Modifier.padding(top = 9.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(if (warning) R.string.notice_mismatch else R.string.notice_forgotten),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        HeroBlock(stringResource(R.string.pair_title), stringResource(R.string.pair_body), diameter = 132.dp)
    }
}

@Composable
private fun EnterCodeScreen(vm: DemoViewModel) {
    val valid = Pairing.isValid(vm.entry)
    val symbols = vm.entry.count { it != '-' }
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
private fun WaitingScreen(vm: DemoViewModel) {
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
        title = stringResource(R.string.waiting_title),
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
            TextButton(
                onClick = vm::previewOtherPhoneJoined,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = Tc.InkMuted),
            ) { Text(stringResource(R.string.waiting_simulate)) }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.waiting_body))
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
    TenCharScreen(
        title = stringResource(R.string.verify_title),
        bottomBar = {
            TcButton(stringResource(R.string.verify_match), vm::verifyMatches)
            TcButton(stringResource(R.string.verify_mismatch), vm::verifyMismatch, kind = ButtonKind.Caution)
        },
    ) {
        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.verify_body))
        Spacer(Modifier.height(24.dp))
        CodeBlock(
            lines = listOf(DemoData.SAMPLE_NUMBER),
            spoken = DemoData.SAMPLE_NUMBER.filter { it != ' ' }.toList().joinToString(" "),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.verify_sample_note),
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkFaint,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun ChatScreen(vm: DemoViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }
    // Remembers which messages have already played their entrance, so scrolling
    // back to an old one does not replay it.
    val seen = remember { HashSet<Long>() }
    TenCharScreen(
        title = stringResource(R.string.chat_title),
        scroll = false,
        actions = { GlassChip(stringResource(R.string.chat_settings), { vm.open(Screen.Settings) }) },
    ) {
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
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.messages, key = { it.id }) { message ->
                    val animateIn = remember(message.id) { seen.add(message.id) }
                    MessageRow(message, animateIn)
                }
            }
        }
        Composer(vm)
    }
}

@Composable
private fun MessageRow(message: ChatMessage, animateIn: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    val enter = remember { Animatable(if (animateIn && !reduceMotion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
    }
    val deliveryText = when (message.delivery) {
        Delivery.Queued -> stringResource(R.string.delivery_queued)
        Delivery.Sent -> stringResource(R.string.delivery_sent)
        null -> null
    }
    val spoken = stringResource(
        if (message.mine) R.string.msg_from_you else R.string.msg_from_them,
        message.text,
    ) + (deliveryText?.let { ", $it" } ?: "")
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = if (message.mine) 22.dp else 6.dp,
        bottomEnd = if (message.mine) 6.dp else 22.dp,
    )
    val tint = if (message.mine) {
        Brush.linearGradient(listOf(Tc.Indigo.copy(alpha = 0.70f), Tc.Lavender.copy(alpha = 0.34f)))
    } else {
        tintOf(Tc.Peach, 0.07f)
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
        Box(Modifier.widthIn(max = 300.dp).glass(shape, tint)) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        if (deliveryText != null) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlowDot(if (message.delivery == Delivery.Sent) Tc.Mint else Tc.Peach, 6.dp)
                Spacer(Modifier.width(8.dp))
                Text(deliveryText, style = MaterialTheme.typography.labelSmall, color = Tc.InkMuted)
            }
        }
    }
}

@Composable
private fun Composer(vm: DemoViewModel) {
    val view = LocalView.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val count = MessageLimits.graphemeCount(vm.draft)
    val canSend = vm.draft.isNotBlank()
    val atLimit = count >= Constants.MAX_CHARS

    fun send() {
        if (!canSend) return
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        vm.send()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp)
            .graphicsLayer { translationX = shake.value * density }
            .glass(RoundedCornerShape(34.dp))
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = vm.draft,
            onValueChange = { raw ->
                if (!MessageLimits.fits(raw)) {
                    // Over the limit: the text is cut, so say so with a nudge.
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
                }
                vm.onDraft(raw)
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
            placeholder = { Text(stringResource(R.string.chat_input_hint), style = MaterialTheme.typography.bodyLarge) },
            colors = tcFieldColors(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        Text(
            text = stringResource(R.string.chat_counter, count, Constants.MAX_CHARS),
            style = MaterialTheme.typography.labelMedium,
            color = if (atLimit) Tc.Peach else Tc.InkMuted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SendButton(count = count, enabled = canSend, label = stringResource(R.string.chat_send), onClick = ::send)
    }
}

/** A send button wrapped in a ring that fills as the ten characters are used. */
@Composable
private fun SendButton(count: Int, enabled: Boolean, label: String, onClick: () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = count / Constants.MAX_CHARS.toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "ring",
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            count >= Constants.MAX_CHARS -> Tc.Peach
            count >= Constants.MAX_CHARS - 2 -> Tc.Lavender
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
private fun SettingsScreen(vm: DemoViewModel) {
    var showNumber by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }
    var showRelay by remember { mutableStateOf(false) }
    TenCharScreen(title = stringResource(R.string.settings_title), onBack = vm::back) {
        SectionTitle(stringResource(R.string.settings_autolock))
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
        Spacer(Modifier.height(24.dp))
        GlassCard {
            GlassRow(stringResource(R.string.settings_show_number), { showNumber = true })
            RowDivider()
            GlassRow(
                text = stringResource(R.string.settings_relay),
                onClick = { showRelay = true },
                detail = vm.relayUrl.removePrefix("https://"),
            )
            RowDivider()
            GlassRow(stringResource(R.string.settings_about), { vm.open(Screen.About) })
            RowDivider()
            GlassRow(stringResource(R.string.settings_forget), { showForget = true }, caution = true)
        }
        Spacer(Modifier.height(24.dp))
        TextButton(
            onClick = vm::showKeysReset,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = Tc.InkMuted),
        ) { Text(stringResource(R.string.settings_preview_keys_reset)) }
        Spacer(Modifier.height(16.dp))
    }

    if (showRelay) {
        RelayDialog(
            current = vm.relayUrl,
            onSave = vm::saveRelay,
            onReset = vm::resetRelay,
            onDismiss = { showRelay = false },
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
            text = { Text(DemoData.SAMPLE_NUMBER, style = CodeStyle.copy(brush = Tc.Brand)) },
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
                    onClick = { showForget = false; vm.forgetConversation() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Tc.Peach),
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

@Composable
private fun RelayDialog(
    current: String,
    onSave: (String) -> Boolean,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
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
        title = { Text(stringResource(R.string.relay_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.relay_body), style = MaterialTheme.typography.bodyMedium)
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
private fun LockOptionRow(selected: Boolean, label: String, onSelect: () -> Unit) {
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
private fun AboutScreen(vm: DemoViewModel) {
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
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun KeysResetScreen(vm: DemoViewModel) {
    TenCharScreen(
        contentArrangement = Arrangement.Center,
        contentAlignment = Alignment.CenterHorizontally,
        bottomBar = { TcButton(stringResource(R.string.keys_reset_pair), vm::pairAgain) },
    ) {
        HeroBlock(stringResource(R.string.keys_reset_title), stringResource(R.string.keys_reset_body), halo = Tc.Peach)
    }
}