package vn.dungxnd.vbookmt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import vn.dungxnd.vbookmt.service.ServiceContract
import vn.dungxnd.vbookmt.service.TranslationService
import vn.dungxnd.vbookmt.ui.MainScreen
import vn.dungxnd.vbookmt.ui.VBookMTTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            VBookMTTheme {
                MainScreen(
                    onStartService = { modelId ->
                        val intent = Intent(this, TranslationService::class.java).apply {
                            action = ServiceContract.ACTION_START
                            putExtra(ServiceContract.EXTRA_ACTIVE_MODEL, modelId)
                        }
                        startForegroundService(intent)
                    },
                    onStopService = {
                        val intent = Intent(this, TranslationService::class.java).apply {
                            action = ServiceContract.ACTION_STOP
                        }
                        startService(intent)
                    }
                )
            }
        }
    }
}
