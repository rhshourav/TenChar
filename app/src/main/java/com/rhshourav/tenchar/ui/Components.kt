package com.rhshourav.tenchar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rhshourav.tenchar.R

private val ContentMaxWidth = 560.dp
private val ButtonShape = RoundedCornerShape(14.dp)

/**
 * Common frame: the preview banner, a header with an optional back button and
 * actions, and a content column. It sets the content colour, because Text
 * outside a Surface would otherwise be black in dark mode.
 */
@Composable
fun TenCharScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
        contentColor = colors.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PreviewBanner()
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .semantics { heading() },
                )
                actions()
            }
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                content = content,
            )
        }
    }
}

/** Says on every screen that this build protects nothing. */
@Composable
private fun PreviewBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = stringResource(R.string.preview_banner),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val sized = modifier.fillMaxWidth().heightIn(min = 52.dp)
    if (primary) {
        Button(onClick = onClick, modifier = sized, enabled = enabled, shape = ButtonShape) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = sized, enabled = enabled, shape = ButtonShape) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A code or number in large monospace type, read out character by character. */
@Composable
fun CodeBlock(lines: List<String>, spoken: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clearAndSetSemantics { contentDescription = spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (line in lines) Text(line, style = CodeStyle)
        }
    }
}

@Composable
fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("•", modifier = Modifier.padding(end = 12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
