package com.rhshourav.tenchar.ui

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.R
import com.rhshourav.tenchar.media.ByteArrayDataSource
import com.rhshourav.tenchar.media.VoiceRecorder
import com.rhshourav.tenchar.store.Attachment
import kotlinx.coroutines.delay

/** 1:23 for 830 tenths of a second. */
internal fun formatDs(durationDs: Int): String {
    val seconds = (durationDs + 5) / 10
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** What a screen reader says for an attachment. */
@Composable
internal fun attachmentLabel(att: AttachmentUi): String = when (att.kind) {
    Constants.KIND_IMAGE -> stringResource(R.string.media_photo)
    Constants.KIND_VIDEO -> stringResource(R.string.media_video, formatDs(att.durationDs))
    else -> stringResource(R.string.media_voice, formatDs(att.durationDs))
}

/** The inside of a message bubble that holds a photo, a video or a voice message. */
@Composable
internal fun AttachmentContent(vm: AppViewModel, message: ChatMessage, onOpen: (ChatMessage) -> Unit) {
    val att = message.attachment ?: return
    if (att.kind == Constants.KIND_VOICE) VoiceContent(vm, message, att) else PictureContent(vm, message, att, onOpen)
}

@Composable
private fun PictureContent(vm: AppViewModel, message: ChatMessage, att: AttachmentUi, onOpen: (ChatMessage) -> Unit) {
    val ratio = (att.width.toFloat() / att.height.coerceAtLeast(1)).coerceIn(0.6f, 1.8f)
    val ready = att.state == Attachment.READY
    val isVideo = att.kind == Constants.KIND_VIDEO
    // (picture, finished loading)
    val loaded by produceState(Pair<Bitmap?, Boolean>(null, false), message.id, att.state) {
        value = if (ready) {
            val bmp = if (isVideo) vm.attachmentVideoFrame(message.id, 480) else vm.attachmentImage(message.id, 640)
            bmp to true
        } else {
            null to false
        }
    }
    val bitmap = loaded.first
    val tappable = (ready && (bitmap != null || isVideo)) || att.state == Attachment.FAILED
    Box(
        modifier = Modifier
            .width(240.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .clickable(enabled = tappable, role = Role.Button) {
                if (att.state == Attachment.FAILED) vm.retryAttachment(message.id) else onOpen(message)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            att.state == Attachment.PENDING -> CircularProgressIndicator(Modifier.size(30.dp), color = Tc.Mint, strokeWidth = 3.dp)
            att.state == Attachment.FAILED -> CaptionOnPicture(stringResource(R.string.media_retry))
            loaded.second && bitmap == null && !isVideo -> CaptionOnPicture(stringResource(R.string.media_unavailable))
            isVideo -> {
                Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                    TcIconView(TcIcon.Play, Color.White, Modifier.size(26.dp))
                }
                Text(
                    text = formatDs(att.durationDs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun CaptionOnPicture(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Tc.Ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun VoiceContent(vm: AppViewModel, message: ChatMessage, att: AttachmentUi) {
    val bars = remember(att.id) {
        VoiceRecorder.bars(att.wave).let { if (it.isEmpty()) FloatArray(Constants.WAVE_BARS) { 0.3f } else it }
    }
    val active = vm.voice.playingId == att.id
    val playing = active && vm.voice.playing
    val progress = if (active) vm.voice.progress else 0f
    val ready = att.state == Attachment.READY
    val label = attachmentLabel(att)
    Row(
        modifier = Modifier
            .width(250.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(if (ready) Tc.Brand else SolidColor(Color.White.copy(alpha = 0.12f)), CircleShape)
                .clickable(
                    enabled = att.state != Attachment.PENDING,
                    role = Role.Button,
                    onClickLabel = stringResource(if (playing) R.string.media_pause else R.string.media_play),
                ) { if (att.state == Attachment.FAILED) vm.retryAttachment(message.id) else vm.toggleVoice(message.id) },
            contentAlignment = Alignment.Center,
        ) {
            when (att.state) {
                Attachment.PENDING -> CircularProgressIndicator(Modifier.size(24.dp), color = Tc.Mint, strokeWidth = 3.dp)
                Attachment.FAILED -> Text("!", style = MaterialTheme.typography.titleMedium, color = Tc.PeachText)
                else -> TcIconView(if (playing) TcIcon.Pause else TcIcon.Play, Tc.Space, Modifier.size(24.dp))
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(34.dp)
                .padding(horizontal = 10.dp),
        ) {
            val n = bars.size
            val gap = 2.5.dp.toPx()
            val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            for (i in 0 until n) {
                val h = size.height * (0.16f + 0.84f * bars[i])
                val played = (i + 0.5f) / n <= progress
                drawRoundRect(
                    color = if (played) Tc.Mint else Tc.Ink.copy(alpha = 0.62f),
                    topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        Text(
            text = if (att.state == Attachment.FAILED) stringResource(R.string.media_retry_short) else formatDs(att.durationDs),
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkMuted,
        )
    }
}

// ---- full-screen viewer -------------------------------------------------------------------------------------------

/**
 * A photo or video shown full screen. The window is secure (no screenshots or screen recording), and the decrypted
 * content lives only in memory and is wiped when the viewer closes.
 */
@Composable
internal fun MediaViewer(vm: AppViewModel, message: ChatMessage, onClose: () -> Unit) {
    val att = message.attachment ?: return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f)).safeDrawingPadding()) {
            if (att.kind == Constants.KIND_VIDEO) {
                val data by produceState<ByteArray?>(null, message.id) { value = vm.attachmentBytes(message.id) }
                val bytes = data
                if (bytes != null) {
                    VideoSurface(bytes, (att.width.toFloat() / att.height.coerceAtLeast(1)).coerceIn(0.3f, 3f), Modifier.align(Alignment.Center))
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Tc.Mint)
                }
            } else {
                val bitmap by produceState<Bitmap?>(null, message.id) { value = vm.attachmentImage(message.id, 2048) }
                bitmap?.let { ZoomableImage(it) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Tc.Mint)
            }
            GlassIconButton(
                icon = TcIcon.Close,
                description = stringResource(R.string.media_close),
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale <= 1f) Offset.Zero else clamp(offset + pan * scale, scale)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** Plays decrypted video from memory. Tap to pause or play. [data] is wiped when this leaves the screen. */
@Composable
private fun VideoSurface(data: ByteArray, ratio: Float, modifier: Modifier = Modifier) {
    val player = remember { MediaPlayer() }
    var started by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            data.fill(0)
        }
    }
    Box(
        modifier = modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
            if (!started) return@clickable
            try {
                if (player.isPlaying) {
                    player.pause()
                    playing = false
                } else {
                    player.start()
                    playing = true
                }
            } catch (e: IllegalStateException) {
                // Not ready, or already released.
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            try {
                                player.setDisplay(h)
                                if (!started) {
                                    player.setDataSource(ByteArrayDataSource(data))
                                    player.setOnPreparedListener {
                                        it.start()
                                        started = true
                                        playing = true
                                    }
                                    player.setOnCompletionListener { playing = false }
                                    player.setOnErrorListener { _, _, _ ->
                                        playing = false
                                        true
                                    }
                                    player.prepareAsync()
                                }
                            } catch (e: Exception) {
                                playing = false
                            }
                        }

                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            runCatching { player.setDisplay(null) }
                        }
                    })
                }
            },
        )
        if (started && !playing) {
            Box(Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                TcIconView(TcIcon.Play, Color.White, Modifier.size(32.dp))
            }
        }
    }
}

// ---- recording and notices ----------------------------------------------------------------------------------------

/** Replaces the message field while a voice message is being recorded: cancel, a live meter, the time, send. */
@Composable
internal fun RecordingRow(vm: AppViewModel, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val pulse: State<Float> = if (reduceMotion) {
        remember { mutableFloatStateOf(1f) }
    } else {
        rememberInfiniteTransition(label = "rec").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "rec-dot",
        )
    }
    val levels = vm.recordLevels
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(TcIcon.Close, stringResource(R.string.media_cancel_recording), vm::cancelRecording)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse.value }.background(Tc.Peach, CircleShape))
        Spacer(Modifier.width(8.dp))
        // Not a live region: a timer that changes every moment must not be read out every moment.
        Text(
            text = formatDs(vm.recordMs / 100),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(34.dp)
                .padding(horizontal = 12.dp),
        ) {
            val shown = 32
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (shown - 1)) / shown).coerceAtLeast(1f)
            val padded = List(shown - levels.size.coerceAtMost(shown)) { 0f } + levels.takeLast(shown)
            for (i in 0 until shown) {
                val h = size.height * (0.10f + 0.90f * padded[i])
                drawRoundRect(
                    color = Tc.Ink.copy(alpha = if (padded[i] > 0f) 0.85f else 0.25f),
                    topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Tc.Brand, CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.media_send_voice)) { vm.finishRecording() },
            contentAlignment = Alignment.Center,
        ) {
            TcIconView(TcIcon.ArrowUp, Tc.Space, Modifier.size(24.dp))
        }
    }
}

/** A thin bar and a few words while a photo or video is being shrunk. */
@Composable
internal fun PreparingBar() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.media_preparing),
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tc.Mint, trackColor = Color.White.copy(alpha = 0.12f))
    }
}

/** Says why something could not be sent. Clears itself after a few seconds; tap to dismiss at once. */
@Composable
internal fun MediaNoticeBanner(notice: MediaNotice, onDismiss: () -> Unit) {
    LaunchedEffect(notice) {
        delay(6_000)
        onDismiss()
    }
    GlassCard(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(onClick = onDismiss),
        tint = tintOf(Tc.Peach, Palette.TINT_CAUTION),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlowDot(Tc.Peach, 7.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(
                    when (notice) {
                        MediaNotice.TooLong -> R.string.media_notice_too_long
                        MediaNotice.TooBig -> R.string.media_notice_too_big
                        MediaNotice.TooShort -> R.string.media_notice_too_short
                        MediaNotice.Unsupported -> R.string.media_notice_unsupported
                        MediaNotice.Failed -> R.string.media_notice_failed
                        MediaNotice.MicBusy -> R.string.media_notice_mic_busy
                        MediaNotice.MicDenied -> R.string.media_notice_mic_denied
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
