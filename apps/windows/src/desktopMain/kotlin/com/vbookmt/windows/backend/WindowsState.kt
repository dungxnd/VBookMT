package com.vbookmt.windows.backend

import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import com.vbookmt.core.processor.TranslationCache
import com.vbookmt.core.state.BaseHostState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Shared observable state for VBookMT Desktop host, inheriting from [BaseHostState] in `core`.
 */
object WindowsState : BaseHostState(ModelRegistry.HACHIMI_60_QT.id, 200) {

    private val _selectedModel = MutableStateFlow<ModelSpec>(ModelRegistry.HACHIMI_60_QT)
    val selectedModel: StateFlow<ModelSpec> = _selectedModel.asStateFlow()

    private val _lanDiscoveryEnabled = MutableStateFlow(false)
    val lanDiscoveryEnabled: StateFlow<Boolean> = _lanDiscoveryEnabled.asStateFlow()

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    private val _savedThreads = MutableStateFlow(0)
    val savedThreads: StateFlow<Int> = _savedThreads.asStateFlow()

    private val _savedComputeType = MutableStateFlow("")
    val savedComputeType: StateFlow<String> = _savedComputeType.asStateFlow()

    private fun settingsFile(): File {
        val localAppData = System.getenv("LOCALAPPDATA")
        val root = if (!localAppData.isNullOrBlank()) {
            File(localAppData, "VBookMT")
        } else {
            File(System.getProperty("user.home") ?: ".", ".vbookmt")
        }
        root.mkdirs()
        return File(root, "settings.properties")
    }

    fun loadSettings() {
        try {
            val file = settingsFile()
            if (!file.exists()) return
            val props = java.util.Properties()
            file.inputStream().use { props.load(it) }

            props.getProperty("threads")?.toIntOrNull()?.let { if (it > 0) _savedThreads.value = it }
            props.getProperty("computeType")?.let { _savedComputeType.value = it }
            props.getProperty("modelId")?.takeIf { it.isNotBlank() }?.let { id ->
                _activeModel.value = ModelRegistry.getById(id).id
                _selectedModel.value = ModelRegistry.getById(id)
            }
            props.getProperty("lanDiscovery")?.toBooleanStrictOrNull()?.let {
                _lanDiscoveryEnabled.value = it
            }
            props.getProperty("darkMode")?.toBooleanStrictOrNull()?.let { _isDarkMode.value = it }
        } catch (e: Exception) {
            log("Không đọc được cài đặt đã lưu: ${e.message}")
        }
    }

    fun saveSettings() {
        try {
            val props = java.util.Properties().apply {
                setProperty("threads", _savedThreads.value.toString())
                setProperty("computeType", _savedComputeType.value)
                setProperty("modelId", _activeModel.value)
                setProperty("lanDiscovery", _lanDiscoveryEnabled.value.toString())
                setProperty("darkMode", _isDarkMode.value.toString())
            }
            settingsFile().outputStream().use { props.store(it, "VBookMT desktop settings") }
        } catch (e: Exception) {
            log("Không ghi được cài đặt: ${e.message}")
        }
    }

    fun setSavedTuning(threads: Int, computeType: String) {
        if (threads > 0) _savedThreads.value = threads
        _savedComputeType.value = computeType
        saveSettings()
    }

    val cacheLock = Any()
    val cache = TranslationCache()

    fun forgetCacheEntries(keys: Iterable<String>) {
        synchronized(cacheLock) {
            for (key in keys) cache.remove(key)
        }
    }

    val modelsDir: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        File(userHome, ".vbookmt/models").apply { mkdirs() }
    }

    override fun setRunning(running: Boolean) {
        super.setRunning(running)
        log(if (running) "Máy chủ VBookMT đã BẬT (cổng 8765)" else "Máy chủ VBookMT đã DỪNG")
    }

    override fun setActiveModel(modelId: String) {
        super.setActiveModel(modelId)
        _selectedModel.value = ModelRegistry.getById(modelId)
    }

    private val variantPrefs: File by lazy { File(modelsDir, "variants.properties") }

    private val variantCache: MutableMap<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        runCatching {
            if (variantPrefs.isFile) {
                variantPrefs.forEachLine { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
        }
        map
    }

    fun installedVariant(modelId: String): QuantVariant {
        val modelDir = File(modelsDir, modelId)
        val diskVariant = File(modelDir, ".variant").takeIf { it.exists() }?.readText()?.trim()
            ?.let { QuantVariant.parse(it) }
        if (diskVariant != null) return diskVariant

        return variantCache[modelId]?.let { QuantVariant.parse(it) } ?: QuantVariant.DEFAULT
    }

    fun setInstalledVariant(modelId: String, variant: QuantVariant) {
        val modelDir = File(modelsDir, modelId)
        if (modelDir.exists()) {
            runCatching { File(modelDir, ".variant").writeText(variant.folder) }
        }
        variantCache[modelId] = variant.folder
        runCatching {
            modelsDir.mkdirs()
            val separator = System.lineSeparator()
            variantPrefs.writeText(
                variantCache.entries.joinToString(separator) { "${it.key}=${it.value}" } + separator
            )
        }
    }

    override fun log(message: String) {
        val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        super.log("[$time] $message")
    }

    override fun clearLogs(initialMessage: String) {
        super.clearLogs(initialMessage)
    }

    fun clearLogs() {
        clearLogs("[Init] Nhật ký đã được làm mới.")
    }

    fun setLanDiscoveryEnabled(enabled: Boolean) {
        _lanDiscoveryEnabled.value = enabled
        val lanIp = getLocalLanIp()
        log(
            if (enabled) "Chế độ LAN: ĐÃ BẬT (mở cổng 8765 cho mạng nội bộ, IP máy: $lanIp)"
            else "Chế độ LAN: ĐÃ TẮT (chỉ chấp nhận kết nối loopback 127.0.0.1)"
        )
    }

    fun getLocalLanIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            val candidates = mutableListOf<String>()
            for (iface in interfaces.asSequence()) {
                if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
                val name = iface.name.lowercase()
                val display = iface.displayName.lowercase()
                val isVirtual = name.contains("vethernet") ||
                        display.contains("virtual") ||
                        display.contains("wsl") ||
                        display.contains("vmware") ||
                        display.contains("box")
                for (addr in iface.inetAddresses.asSequence()) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            if (!isVirtual) {
                                return host
                            }
                            candidates.add(host)
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) return candidates.first()
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
