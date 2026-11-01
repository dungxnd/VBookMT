package com.vbookmt.windows.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the desktop host.
 *
 * The 1.1.0 UI was card-in-card: every block was a 1dp-bordered rounded rectangle, all-caps
 * Vietnamese shout labels, emoji, and a chip for every number. The result read as a settings dump
 * rather than a tool, and the hierarchy was flat — nothing told you what mattered.
 *
 * Three rules replace it, borrowed from the mobile (Material 3) language the app already ships:
 *
 *  1. **Depth comes from tonal surfaces, not borders.** A card is a lighter panel on a calmer canvas.
 *     A hairline is used only where two surfaces of the *same* tone meet and would otherwise blur.
 *  2. **One primary action per screen.** It is the only filled button; everything else is tonal or
 *     text, so the eye lands where the work is.
 *  3. **Type carries hierarchy.** Sentence-case, four real steps (display / title / body / caption),
 *     with weight and colour doing the rest. No caps-lock, no emoji.
 *
 * Colours are warm neutrals so long reading sessions do not glare, with a single indigo accent for
 * interaction and three semantic tones for state.
 */
@Immutable
data class VBookPalette(
    /** Window background, the calmest tone. */
    val canvas: Color,
    /** Panels and cards: one step up from the canvas. */
    val surface: Color,
    /** Inset areas inside a card — inputs, code, list wells. */
    val surfaceMuted: Color,
    /** Hover / pressed feedback on an interactive surface. */
    val surfaceHover: Color,

    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceFaint: Color,

    val accent: Color,
    val onAccent: Color,
    /** Tonal fill for selected nav items and quiet accent buttons. */
    val accentTonal: Color,
    val onAccentTonal: Color,

    val ok: Color,
    val okTonal: Color,
    val warn: Color,
    val warnTonal: Color,
    val danger: Color,
    val dangerTonal: Color,

    /** Only where two equal tones meet. */
    val hairline: Color,
)

private val LightPalette = VBookPalette(
    canvas = Color(0xFFF6F6F3),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFEFEFEA),
    surfaceHover = Color(0xFFE9E9E3),
    onSurface = Color(0xFF17181A),
    onSurfaceMuted = Color(0xFF5E6167),
    onSurfaceFaint = Color(0xFF8B8F96),
    accent = Color(0xFF4356D6),
    onAccent = Color(0xFFFFFFFF),
    accentTonal = Color(0xFFE7EAFB),
    onAccentTonal = Color(0xFF2A369B),
    ok = Color(0xFF1C7F49),
    okTonal = Color(0xFFE1F3E8),
    warn = Color(0xFF9A6100),
    warnTonal = Color(0xFFFBEFD9),
    danger = Color(0xFFB23A2E),
    dangerTonal = Color(0xFFFAE8E6),
    hairline = Color(0xFFE2E2DC),
)

private val DarkPalette = VBookPalette(
    canvas = Color(0xFF111216),
    surface = Color(0xFF1A1C21),
    surfaceMuted = Color(0xFF23262C),
    surfaceHover = Color(0xFF2B2F36),
    onSurface = Color(0xFFF1F2F4),
    onSurfaceMuted = Color(0xFFA4A9B2),
    onSurfaceFaint = Color(0xFF737981),
    accent = Color(0xFF94A2FF),
    onAccent = Color(0xFF11184A),
    accentTonal = Color(0xFF232A4E),
    onAccentTonal = Color(0xFFC5CEFF),
    ok = Color(0xFF57D38C),
    okTonal = Color(0xFF16301F),
    warn = Color(0xFFE8B341),
    warnTonal = Color(0xFF33280F),
    danger = Color(0xFFEF7C72),
    dangerTonal = Color(0xFF3A1C1A),
    hairline = Color(0xFF2A2E35),
)

val LocalVBookPalette = staticCompositionLocalOf { LightPalette }

/**
 * Spacing scale. Every gap in the UI is one of these, which is what makes unrelated screens line up
 * without anyone measuring.
 */
@Immutable
data class VBookSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val huge: Dp = 48.dp,
)

val LocalVBookSpacing = staticCompositionLocalOf { VBookSpacing() }

@Immutable
data class VBookRadii(
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
) {
    val smShape: RoundedCornerShape get() = RoundedCornerShape(sm)
    val mdShape: RoundedCornerShape get() = RoundedCornerShape(md)
    val lgShape: RoundedCornerShape get() = RoundedCornerShape(lg)
    val xlShape: RoundedCornerShape get() = RoundedCornerShape(xl)
    val pill: RoundedCornerShape get() = RoundedCornerShape(percent = 50)
}

val LocalVBookRadii = staticCompositionLocalOf { VBookRadii() }

/** Four real steps. Sizes are small on purpose: a desktop tool is read at 50 cm, not 20. */
private val VBookTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * Maps the palette onto Material's colour roles so stock components (Switch, text field, progress
 * bar) inherit the same tokens instead of needing a colour argument at every call site.
 */
private fun paletteToColorScheme(p: VBookPalette, dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.accentTonal,
            onPrimaryContainer = p.onAccentTonal,
            secondary = p.accent,
            onSecondary = p.onAccent,
            secondaryContainer = p.accentTonal,
            onSecondaryContainer = p.onAccentTonal,
            tertiary = p.warn,
            onTertiary = Color.White,
            tertiaryContainer = p.warnTonal,
            onTertiaryContainer = p.warn,
            background = p.canvas,
            onBackground = p.onSurface,
            surface = p.surface,
            onSurface = p.onSurface,
            surfaceVariant = p.surfaceMuted,
            onSurfaceVariant = p.onSurfaceMuted,
            surfaceContainer = p.surfaceMuted,
            surfaceContainerHigh = p.surfaceHover,
            error = p.danger,
            onError = Color.White,
            errorContainer = p.dangerTonal,
            onErrorContainer = p.danger,
            outline = p.hairline,
            outlineVariant = p.hairline,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.accentTonal,
            onPrimaryContainer = p.onAccentTonal,
            secondary = p.accent,
            onSecondary = p.onAccent,
            secondaryContainer = p.accentTonal,
            onSecondaryContainer = p.onAccentTonal,
            tertiary = p.warn,
            onTertiary = Color.White,
            tertiaryContainer = p.warnTonal,
            onTertiaryContainer = p.warn,
            background = p.canvas,
            onBackground = p.onSurface,
            surface = p.surface,
            onSurface = p.onSurface,
            surfaceVariant = p.surfaceMuted,
            onSurfaceVariant = p.onSurfaceMuted,
            surfaceContainer = p.surfaceMuted,
            surfaceContainerHigh = p.surfaceHover,
            error = p.danger,
            onError = Color.White,
            errorContainer = p.dangerTonal,
            onErrorContainer = p.danger,
            outline = p.hairline,
            outlineVariant = p.hairline,
            scrim = Color.Black,
        )
    }

/** Token accessor used by every screen: `VBookTheme.palette`, `.spacing`, `.radii`. */
object VBookTheme {
    val palette: VBookPalette
        @Composable get() = LocalVBookPalette.current

    val spacing: VBookSpacing
        @Composable get() = LocalVBookSpacing.current

    val radii: VBookRadii
        @Composable get() = LocalVBookRadii.current
}

@Composable
fun VBookMTDesktopTheme(
    isDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = if (isDark) DarkPalette else LightPalette
    CompositionLocalProvider(
        LocalVBookPalette provides palette,
        LocalVBookSpacing provides VBookSpacing(),
        LocalVBookRadii provides VBookRadii(),
    ) {
        MaterialTheme(
            colorScheme = paletteToColorScheme(palette, isDark),
            typography = VBookTypography,
            content = content,
        )
    }
}
