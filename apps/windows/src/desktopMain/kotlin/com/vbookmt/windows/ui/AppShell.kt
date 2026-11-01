package com.vbookmt.windows.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.windows.backend.WindowsServer
import com.vbookmt.windows.backend.WindowsState
import com.vbookmt.windows.ui.icons.AppIcons

/**
 * The three places the app can be — the same three the phone shows.
 *
 * Desktop used to have four (Tổng quan / Mô hình / Hiệu năng / Nhật ký) while mobile had three
 * (Trang chính / Mô hình / Cài đặt). Nothing about a performance panel or a log console justifies its
 * own top-level destination on either host: they are settings, and both apps now group them the same
 * way, so the same muscle memory works whichever screen you are in front of.
 *
 * The one deliberate difference is inside Tổng quan: the LAN/network panel is desktop-only, because
 * only the PC serves over the network.
 */
enum class Destination(val title: String, val icon: ImageVector) {
    OVERVIEW("Tổng quan", AppIcons.ViewAgenda),
    MODELS("Mô hình", AppIcons.Memory),
    SETTINGS("Cài đặt", AppIcons.Settings),
}

/**
 * The application frame: a persistent nav rail on the left, content on the right.
 *
 * 1.1.0 put three segmented tabs in a 56dp top bar, which forced every screen into a single scrolling
 * column and made the app feel like a phone layout stretched sideways. A rail is the desktop-native
 * answer — Material 3 names it the canonical large-screen pattern — and it frees the full height of
 * the window for content, which is what a logs screen and a translation playground actually need.
 *
 * The rail carries the product mark, the destinations, a live server indicator, and the theme
 * toggle. Nothing else: a status *card* does not belong in navigation, and duplicating state here
 * and in the content is how 1.1.0 ended up showing server status in three places at once.
 */
@Composable
fun AppShell(
    destination: Destination,
    onDestinationChange: (Destination) -> Unit,
    appIconPainter: Painter,
    isDark: Boolean,
    isRunning: Boolean,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(VBookTheme.palette.canvas),
    ) {
        NavRail(
            destination = destination,
            onDestinationChange = onDestinationChange,
            appIconPainter = appIconPainter,
            isDark = isDark,
            isRunning = isRunning,
        )

        // Content column. Scroll handling belongs to each screen: a logs terminal and a settings
        // list want different behaviour, and a single scrolling shell made the terminal scroll twice.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = VBookTheme.spacing.xl,
                    end = VBookTheme.spacing.xl,
                    top = VBookTheme.spacing.lg,
                    bottom = VBookTheme.spacing.lg,
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun NavRail(
    destination: Destination,
    onDestinationChange: (Destination) -> Unit,
    appIconPainter: Painter,
    isDark: Boolean,
    isRunning: Boolean,
) {
    val palette = VBookTheme.palette

    Column(
        modifier = Modifier
            .width(216.dp)
            .fillMaxHeight()
            .background(palette.surface)
            .padding(vertical = VBookTheme.spacing.lg),
    ) {
        // --- Product mark -----------------------------------------------------
        Row(
            modifier = Modifier.padding(horizontal = VBookTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = appIconPainter,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(VBookTheme.spacing.sm))
            Column {
                Text(
                    text = "VBookMT",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onSurface,
                )
                Text(
                    text = "Desktop 1.2",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceFaint,
                )
            }
        }

        Spacer(Modifier.height(VBookTheme.spacing.xl))

        // --- Destinations -----------------------------------------------------
        for (item in Destination.entries) {
            NavRailItem(
                destination = item,
                selected = item == destination,
                onClick = { onDestinationChange(item) },
            )
        }

        Spacer(Modifier.weight(1f))

        // --- Live status ------------------------------------------------------
        // One place, and it is the truth the server object reports — not a copy of a copy.
        Row(
            modifier = Modifier.padding(horizontal = VBookTheme.spacing.lg, vertical = VBookTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(
                text = if (isRunning) "Đang chạy · ${WindowsServer.PORT}" else "Đã tắt",
                tone = if (isRunning) StatusTone.Ok else StatusTone.Idle,
                pulsing = isRunning,
            )
        }

        // Theme toggle. The icon is the whole control: the sun/moon already says which mode you are
        // in, so a "Giao diện sáng" label beside it was a caption for something self-evident — and
        // since the label was not part of the button, clicking it did nothing.
        Row(
            modifier = Modifier.padding(start = VBookTheme.spacing.md, top = VBookTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietIconButton(
                icon = if (isDark) AppIcons.LightMode else AppIcons.DarkMode,
                contentDescription = if (isDark) "Chuyển sang giao diện sáng" else "Chuyển sang giao diện tối",
                onClick = { WindowsState.toggleDarkMode() },
            )
        }
    }
}

@Composable
private fun NavRailItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = VBookTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Selected = tonal fill + accent label. Hover = a lighter tint of the same idea. Nothing else
    // changes, so the rail never looks busy.
    val background by animateColorAsState(
        targetValue = when {
            selected -> palette.accentTonal
            hovered -> palette.surfaceHover
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "nav-bg",
    )
    val contentColor = if (selected) palette.onAccentTonal else palette.onSurfaceMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VBookTheme.spacing.sm, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = VBookTheme.spacing.md, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = destination.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) palette.onAccentTonal else palette.onSurface,
        )
    }
}

/**
 * A page header with room for one primary action on the right.
 *
 * Every screen that has a primary action uses this, so the button lands in the same place on all four
 * screens and the eye learns it once.
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    actions: (@Composable RowActions.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VBookTheme.spacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        ScreenTitle(title, subtitle)
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(VBookTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowActions.actions()
            }
        }
    }
}

/** Marker object so [PageHeader]'s action lambda reads as a small DSL. */
object RowActions

/** A horizontally scrollable-free column of panels with a consistent gap. */
@Composable
fun PanelColumn(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = VBookTheme.spacing.lg,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Unused-but-exported spacer helper: keeps call sites from importing layout internals. */
@Composable
fun VSpace(height: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.height(height))
}
