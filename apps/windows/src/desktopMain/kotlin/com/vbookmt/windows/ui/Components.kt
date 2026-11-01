package com.vbookmt.windows.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// Panels — depth from tone, not from borders
// ============================================================================

/**
 * A block of content on the canvas.
 *
 * This is the *only* container primitive. 1.1.0 had cards inside cards inside cards, each with its
 * own 1dp border, which is why every screen looked like a wireframe. Here a panel is simply a lighter
 * surface on the canvas; nesting is expressed with [InsetPanel] (a muted well) instead of another
 * card, so the depth reading is unambiguous: panel > well.
 *
 * @param padding inner padding; pass `PaddingValues(0.dp)` when the content draws to the edges (a list
 *   that scrolls, for example) and pad inside instead.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = VBookTheme.spacing.lg,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = VBookTheme.radii.lgShape,
        color = VBookTheme.palette.surface,
    ) {
        Column(modifier = Modifier.padding(padding)) { content() }
    }
}

/** Alias so callers do not need a ColumnScope import for the trailing lambda. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * An inset well inside a [Panel]: inputs, code, log output, list rows.
 *
 * Muted rather than bordered, because a border here would compete with the panel edge above it and
 * reintroduce the outline-everything look.
 */
@Composable
fun InsetPanel(
    modifier: Modifier = Modifier,
    padding: Dp = VBookTheme.spacing.md,
    background: Color = VBookTheme.palette.surfaceMuted,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = VBookTheme.radii.mdShape,
        color = background,
    ) {
        Column(modifier = Modifier.padding(padding)) { content() }
    }
}

// ============================================================================
// Typography helpers
// ============================================================================

/** Screen title. One per screen, at the top of the content column. */
@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = VBookTheme.palette.onSurface,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = VBookTheme.palette.onSurfaceMuted,
            )
        }
    }
}

/** Section heading inside a screen or a panel. Sentence case — never caps-lock. */
@Composable
fun SectionHeading(
    text: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = VBookTheme.palette.onSurface,
        )
        trailing?.invoke()
    }
}

/** Secondary line under a heading, or a caption on its own. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = VBookTheme.palette.onSurfaceMuted,
    )
}

/**
 * Label + value pair, the workhorse of the diagnostics screens.
 *
 * Value is monospaced so numbers align down the column, which is the difference between "some
 * statistics" and a readout you can scan.
 */
@Composable
fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = VBookTheme.palette.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = VBookTheme.palette.onSurfaceMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ============================================================================
// Status
// ============================================================================

enum class StatusTone { Ok, Warn, Danger, Idle }

/**
 * The app's one status indicator: a dot plus a word.
 *
 * Replaces the 1.1.0 pill that mixed an icon, a coloured border, a background tint and an all-caps
 * label to say one thing. A dot and a sentence-case word are readable at a glance and do not have to
 * be restyled for every surface they appear on.
 */
@Composable
fun StatusDot(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val palette = VBookTheme.palette
    val color = when (tone) {
        StatusTone.Ok -> palette.ok
        StatusTone.Warn -> palette.warn
        StatusTone.Danger -> palette.danger
        StatusTone.Idle -> palette.onSurfaceFaint
    }

    // Deliberately NOT animated.
    //
    // This used to drive a `rememberInfiniteTransition` alpha pulse while the server was running.
    // An infinite transition never reaches a settled frame, so Compose keeps requesting a redraw
    // forever: measured on a Ryzen 5800H, the process held ~12% of a core with the window idle on
    // screen and ~13% with it *hidden* -- the repaint loop does not stop when the window is not
    // visible. A thread dump showed Skiko's `Direct3DRedrawer.draw` pinned at 60 fps on
    // `pool-1-thread-1`, plus ~14 s/min of `AWT-EventQueue-0`.
    //
    // A status dot that pulses tells the user nothing the colour has not already said, and it costs
    // a core forever. `pulsing` is kept in the signature so call sites read the same, but it no
    // longer animates. If motion is ever wanted again, it must be tied to a bounded transition or
    // suspended when the window is not visible -- never an unconditional infinite one.
    val alpha = if (pulsing) 0.85f else 1f

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * A short metadata tag — "INT8", "arm64", a licence name.
 *
 * Tonal and lower-case-ish: no border, no caps, so a row of them reads as metadata rather than as a
 * control strip.
 */
@Composable
fun Tag(
    text: String,
    tone: StatusTone = StatusTone.Idle,
    modifier: Modifier = Modifier,
) {
    val palette = VBookTheme.palette
    val (bg, fg) = when (tone) {
        StatusTone.Ok -> palette.okTonal to palette.ok
        StatusTone.Warn -> palette.warnTonal to palette.warn
        StatusTone.Danger -> palette.dangerTonal to palette.danger
        StatusTone.Idle -> palette.surfaceMuted to palette.onSurfaceMuted
    }
    Surface(shape = VBookTheme.radii.smShape, color = bg, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/**
 * A large number with a unit, for the three figures that actually matter on the overview screen.
 *
 * The unit is small and muted so the digits dominate — 1.1.0 rendered "~1,600 ký tự/giây" as one
 * string at one weight, which made every metric equally loud.
 */
@Composable
fun MetricBlock(
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = if (emphasis) 26.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                color = if (emphasis) VBookTheme.palette.accent else VBookTheme.palette.onSurface,
            )
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = VBookTheme.palette.onSurfaceFaint,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VBookTheme.palette.onSurfaceMuted,
        )
    }
}

// ============================================================================
// Dividers
// ============================================================================

/**
 * A hairline, for the two places where equal tones meet: a list row divider and a nav rail edge.
 *
 * Deliberately *not* exported as a general-purpose border. If it is used to outline content, the
 * design has regressed to 1.1.0.
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(VBookTheme.palette.hairline),
    )
}

/** An icon-only button with hover feedback — window controls, copy actions. */
@Composable
fun QuietIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> VBookTheme.palette.accentTonal
        hovered -> VBookTheme.palette.surfaceHover
        else -> Color.Transparent
    }
    val tint = when {
        !enabled -> VBookTheme.palette.onSurfaceFaint
        selected -> VBookTheme.palette.onAccentTonal
        else -> VBookTheme.palette.onSurfaceMuted
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(VBookTheme.radii.smShape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * A toggle that reads as a control in both states.
 *
 * `Switch` styled with only `checkedThumbColor` / `checkedTrackColor` leaves the **unchecked** state
 * on Material's defaults, which are tuned for Material's own surfaces. Against this app's palette that
 * lands on a grey pill with a barely-visible thumb: it reads as disabled, so a working toggle looks
 * broken and users do not try it. Both states are therefore specified here, once.
 *
 * Unchecked uses `surfaceMuted` for the track and `onSurfaceFaint` for the thumb — visibly recessed,
 * but with a thumb that separates from the track. Checked uses the accent. Border colours follow the
 * same two tones so the outline never disappears in either theme.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = VBookTheme.palette
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = palette.onAccent,
            checkedTrackColor = palette.accent,
            checkedBorderColor = palette.accent,
            uncheckedThumbColor = palette.onSurfaceFaint,
            uncheckedTrackColor = palette.surfaceMuted,
            uncheckedBorderColor = palette.hairline,
        ),
    )
}
