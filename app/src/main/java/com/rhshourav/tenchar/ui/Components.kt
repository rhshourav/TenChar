package com.rhshourav.tenchar.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import com.rhshourav.tenchar.R

private val ContentMaxWidth = 560.dp

/**
 * Common frame. It draws no background: the aurora lives once at the app root.
 * Primary actions go in [bottomBar], which sits in thumb reach and rides above
 * the keyboard.
 */
@Composable
fun TenCharScreen(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    scroll: Boolean = true,
    contentArrangement: Arrangement.Vertical = Arrangement.Top,
    contentAlignment: Alignment.Horizontal = Alignment.Start,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewChip()
        if (title != null) {
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    GlassIconButton(TcIcon.ChevronLeft, stringResource(R.string.back), onBack)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                actions()
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .weight(1f)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = contentArrangement,
            horizontalAlignment = contentAlignment,
            content = content,
        )
        if (bottomBar != null) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = bottomBar,
            )
        }
    }
}

/** Says on every screen that this build protects nothing. Must stay until the crypto is real. */
@Composable
private fun PreviewChip() {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .widthIn(max = ContentMaxWidth)
            .glass(RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(Tc.Peach, 7.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.preview_banner),
            style = MaterialTheme.typography.labelMedium,
            color = Tc.InkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

enum class ButtonKind { Primary, Glass, Caution }

@Composable
fun TcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
) {
    val shape = CircleShape
    val look = when (kind) {
        ButtonKind.Primary -> Modifier
            .glowShadow(shape)
            .background(Tc.Brand, shape)
        ButtonKind.Glass -> Modifier.glass(shape)
        ButtonKind.Caution -> Modifier.glass(shape, tintOf(Tc.Peach, 0.14f))
    }
    val textColor = when (kind) {
        ButtonKind.Primary -> Tc.Space
        ButtonKind.Glass -> Tc.Ink
        ButtonKind.Caution -> Tc.Peach
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .glassClickable(enabled, Role.Button, onClick)
            .then(look)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = textColor, textAlign = TextAlign.Center)
    }
}

/** Soft violet glow under the primary button. */
private fun Modifier.glowShadow(shape: Shape): Modifier =
    this.shadow(elevation = 18.dp, shape = shape, ambientColor = Tc.Lavender, spotColor = Tc.Lavender)

@Composable
fun GlassIconButton(icon: TcIcon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .glassClickable(true, Role.Button, onClick)
            .glass(CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        TcIconView(icon, Tc.Ink, Modifier.size(22.dp))
    }
}

@Composable
fun GlassChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .glassClickable(true, Role.Button, onClick)
            .glass(CircleShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Brush? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().glass(shape, tint), content = content)
}

@Composable
fun Headline(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = Tc.InkMuted,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = modifier,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Tc.InkMuted,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 10.dp).semantics { heading() },
    )
}

/** Logo, headline and one paragraph, centred. Used by the full-screen states. */
@Composable
fun HeroBlock(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 176.dp,
    halo: Color = Tc.Lavender,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoHero(diameter = diameter, halo = halo)
        Spacer(Modifier.size(28.dp))
        Headline(title, center = true)
        Spacer(Modifier.size(12.dp))
        BodyText(body, center = true)
    }
}

/** A code or number in large gradient monospace, read out character by character. */
@Composable
fun CodeBlock(lines: List<String>, spoken: String) {
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .clearAndSetSemantics { contentDescription = spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (line in lines) {
                Text(line, style = CodeStyle.copy(brush = Tc.Brand), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun Bullet(text: String, color: Color = Tc.Peach) {
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        GlowDot(color, 7.dp, Modifier.padding(top = 9.dp, end = 4.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** One tappable line inside a [GlassCard], with a chevron. */
@Composable
fun GlassRow(text: String, onClick: () -> Unit, caution: Boolean = false) {
    val tick = rememberTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) {
                tick()
                onClick()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (caution) Tc.Peach else Tc.Ink,
            modifier = Modifier.weight(1f),
        )
        TcIconView(TcIcon.ChevronRight, if (caution) Tc.Peach else Tc.InkFaint, Modifier.size(20.dp))
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = Color.White.copy(alpha = 0.10f))
}

/** Radio dot: a ring that fills with a mint glow when chosen. */
@Composable
fun GlowRadio(selected: Boolean) {
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "radio",
    )
    Box(
        Modifier
            .size(24.dp)
            .drawBehind {
                val ring = 2.dp.toPx()
                drawCircle(
                    color = if (selected) Tc.Mint else Color.White.copy(alpha = 0.35f),
                    radius = this.size.minDimension / 2f - ring / 2f,
                    style = Stroke(ring),
                )
                val r = (this.size.minDimension / 2f - 6.dp.toPx()) * fill.coerceAtLeast(0f)
                if (r > 0f) drawCircle(Tc.Mint, radius = r)
            },
    )
}

/** Transparent text fields, so the glass plate behind them shows through. */
@Composable
fun tcFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = Tc.Ink,
    unfocusedTextColor = Tc.Ink,
    disabledTextColor = Tc.InkMuted,
    errorTextColor = Tc.Ink,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    cursorColor = Tc.Mint,
    errorCursorColor = Tc.Peach,
    selectionColors = TextSelectionColors(handleColor = Tc.Mint, backgroundColor = Tc.Mint.copy(alpha = 0.30f)),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    focusedPlaceholderColor = Tc.InkFaint,
    unfocusedPlaceholderColor = Tc.InkFaint,
)

val EntryStyle: TextStyle = CodeStyle.copy(fontSize = 20.sp, letterSpacing = 1.sp, lineHeight = 28.sp)