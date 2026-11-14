package com.rhshourav.tenchar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.R

/** The words that stand for a message in a quote or a reply bar: its text, or the kind of attachment it is. */
@Composable
private fun snippetOf(kind: Int, text: String): String = when (kind) {
    Constants.KIND_IMAGE -> stringResource(R.string.media_photo)
    Constants.KIND_VIDEO -> stringResource(R.string.reply_video)
    Constants.KIND_VOICE -> stringResource(R.string.reply_voice)
    else -> text
}

/**
 * The message a reply answers, shown at the top of the reply's bubble. It is read from this phone's own copy of the
 * chat, so it can only show something that really was said. Tap to jump to the original, if it is still there.
 */
@Composable
internal fun QuoteBlock(quote: QuoteUi, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val who = stringResource(if (quote.mine) R.string.profile_you else R.string.profile_them)
    val body = if (quote.targetId == null) stringResource(R.string.reply_missing) else snippetOf(quote.kind, quote.text)
    val accent = if (quote.mine) Tc.Mint else Tc.Peach
    val jump = onClick != null && quote.targetId != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .then(if (jump) Modifier.clickable(role = Role.Button, onClick = onClick!!) else Modifier),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(who, style = MaterialTheme.typography.labelSmall, color = if (quote.mine) Tc.Mint else Tc.PeachText)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Tc.InkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Above the message box while a reply is being written: who is being answered, what they said, and a way to cancel. */
@Composable
internal fun ReplyBar(message: ChatMessage, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val who = stringResource(if (message.mine) R.string.profile_you else R.string.profile_them)
    val snippet = snippetOf(message.attachment?.kind ?: 0, message.text)
    val accent = if (message.mine) Tc.Mint else Tc.PeachText
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp))
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(34.dp).background(if (message.mine) Tc.Mint else Tc.Peach, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.reply_bar, who), style = MaterialTheme.typography.labelMedium, color = accent)
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = Tc.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reply_cancel), onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            TcIconView(TcIcon.Close, Tc.Ink, Modifier.size(20.dp))
        }
    }
}
