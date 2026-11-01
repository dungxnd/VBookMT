package com.vbookmt.windows

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.vbookmt.windows.backend.WindowsServer
import com.vbookmt.windows.backend.WindowsState
import com.vbookmt.windows.ui.MainScreen
import com.vbookmt.windows.ui.VBookMTDesktopTheme
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun main() = application {
    val windowState = rememberWindowState(
        width = 1140.dp,
        height = 820.dp,
        position = WindowPosition(Alignment.Center)
    )

    var isWindowVisible by remember { mutableStateOf(true) }
    val isRunning by WindowsState.isRunning.collectAsState()

    // Load high-resolution app icon from classpath resources
    val appImage: BufferedImage? = remember {
        try {
            val resStream = object {}.javaClass.getResourceAsStream("/icon.png")
                ?: Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
            if (resStream != null) ImageIO.read(resStream) else null
        } catch (_: Exception) {
            null
        }
    }

    val appIconPainter: Painter = remember(appImage) {
        if (appImage != null) {
            BitmapPainter(appImage.toComposeImageBitmap())
        } else {
            createFallbackIconPainter()
        }
    }

    // Startup: restore remembered settings, replay the translation journal, load the model, then
    // bind the listener. Order matters — binding first would accept a chapter before the memo is
    // warm, turning a free re-read into a full cold translation.
    LaunchedEffect(Unit) {
        WindowsState.loadSettings()
        WindowsServer.warmUp()
        WindowsServer.start()
    }

    // System Tray
    Tray(
        icon = appIconPainter,
        tooltip = if (isRunning) "VBookMT — máy chủ đang chạy · cổng 8765" else "VBookMT — máy chủ đang tắt",
        onAction = { isWindowVisible = true },
        menu = {
            Item("Mở giao diện VBookMT", onClick = { isWindowVisible = true })
            Item(
                if (isRunning) "Dừng máy chủ" else "Bật máy chủ",
                onClick = {
                    if (isRunning) WindowsServer.stop()
                    else WindowsServer.start()
                }
            )
            Separator()
            Item("Thoát", onClick = {
                WindowsServer.stop()
                exitApplication()
            })
        }
    )

    // Main App Window
    Window(
        onCloseRequest = {
            // Minimize to tray instead of quitting outright
            isWindowVisible = false
        },
        state = windowState,
        visible = isWindowVisible,
        title = "VBookMT — Desktop AI Host",
        icon = appIconPainter
    ) {
        LaunchedEffect(window, appImage) {
            window.minimumSize = Dimension(760, 560)
            if (appImage != null) {
                window.iconImage = appImage
            }
        }

        val isDark by WindowsState.isDarkMode.collectAsState()
        VBookMTDesktopTheme(isDark = isDark) {
            MainScreen(
                appIconPainter = appIconPainter,
                onMinimizeToTray = { isWindowVisible = false }
            )
        }
    }
}

/**
 * Creates a graceful fallback vector-like icon in case resource loading fails.
 */
private fun createFallbackIconPainter(): Painter {
    val size = 64
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g2d = img.createGraphics() as Graphics2D
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    // Deep blue rounded square
    g2d.color = java.awt.Color(0x19, 0x76, 0xD2)
    g2d.fillRoundRect(2, 2, size - 4, size - 4, 16, 16)

    // White "V" symbol
    g2d.color = java.awt.Color.WHITE
    g2d.stroke = java.awt.BasicStroke(6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    g2d.drawPolyline(
        intArrayOf(16, 32, 48),
        intArrayOf(22, 46, 22),
        3
    )
    g2d.dispose()

    return BitmapPainter(img.toComposeImageBitmap())
}
