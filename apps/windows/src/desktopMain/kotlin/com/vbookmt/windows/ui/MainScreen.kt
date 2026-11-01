package com.vbookmt.windows.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import com.vbookmt.windows.backend.WindowsState
import com.vbookmt.windows.ui.screens.ModelsScreen
import com.vbookmt.windows.ui.screens.OverviewScreen
import com.vbookmt.windows.ui.screens.SettingsScreen

/**
 * The window's content: the shell plus whichever screen is selected.
 *
 * Three destinations, matching the phone's three tabs. There is no scaffold, top bar or card stack
 * here — [AppShell] owns the frame and each screen owns its own scroll behaviour, because a log
 * terminal and a settings list want different behaviour and a shared scrolling parent made the
 * terminal scroll twice.
 */
@Composable
fun MainScreen(
    appIconPainter: Painter,
    onMinimizeToTray: () -> Unit,
) {
    var destination by remember { mutableStateOf(Destination.OVERVIEW) }
    val isRunning by WindowsState.isRunning.collectAsState()
    val isDark by WindowsState.isDarkMode.collectAsState()

    AppShell(
        destination = destination,
        onDestinationChange = { destination = it },
        appIconPainter = appIconPainter,
        isDark = isDark,
        isRunning = isRunning,
    ) {
        when (destination) {
            Destination.OVERVIEW -> OverviewScreen(
                onOpenModels = { destination = Destination.MODELS },
            )
            Destination.MODELS -> ModelsScreen()
            Destination.SETTINGS -> SettingsScreen()
        }
    }
}
