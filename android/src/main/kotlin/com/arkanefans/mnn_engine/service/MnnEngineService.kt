package com.arkanefans.mnn_engine.service

import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.arkanefans.mnn_engine.MnnEngineOperationException
import com.arkanefans.mnn_engine.logging.MnnLogStore
import com.arkanefans.mnn_engine.model.MnnTestDirectories
import com.arkanefans.mnn_engine.model.MnnModelImporter
import com.arkanefans.mnn_engine.model.MnnModelValidator
import com.arkanefans.mnn_engine.model.MnnTestModelRepository
import com.arkanefans.mnn_engine.runtime.MnnNativeBridge
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.arkanefans.mnn_engine.runtime.RuntimeSnapshot
import com.arkanefans.mnn_engine.server.MnnOpenAiServer
import com.arkanefans.mnn_engine.server.MnnBindMode
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class MnnEngineService : Service() {
    val logStore = MnnLogStore()
    private lateinit var directories: MnnTestDirectories
    private lateinit var repository: MnnTestModelRepository
    private lateinit var importer: MnnModelImporter
    lateinit var runtimeManager: MnnRuntimeManager
        private set
    private lateinit var notificationManager: MnnNotificationManager
    private lateinit var openAiServer: MnnOpenAiServer
    private val revision = AtomicLong(0)
    private val runtimeListeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()
    @Volatile
    private var snapshot = RuntimeSnapshot()

    override fun onCreate() {
        super.onCreate()
        directories = MnnTestDirectories(this)
        val validator = MnnModelValidator()
        repository = MnnTestModelRepository(directories, validator)
        importer = MnnModelImporter(this, directories, validator, repository, logStore)
        runtimeManager = MnnRuntimeManager(
            directories = directories,
            repository = repository,
            logStore = logStore,
        ) { modelState, generationState, activeModel, lastError ->
            updateSnapshot(
                snapshot.copy(
                    modelState = modelState,
                    generationState = generationState,
                    activeModel = activeModel?.toMap(),
                    lastError = lastError,
                ),
            )
        }
        notificationManager = MnnNotificationManager(this)
        openAiServer = MnnOpenAiServer(
            context = this,
            runtimeManager = runtimeManager,
            snapshotProvider = ::getSnapshot,
            logStore = logStore,
        )
        directories.ensureCreated()
        cleanupStaging()
        logStore.info(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_FOREGROUND) {
            val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: "http://127.0.0.1:8081"
            val testUrl = intent.getStringExtra(EXTRA_TEST_URL) ?: baseUrl
            val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "MNN model"
            val notification = notificationManager.build(baseUrl, testUrl, modelName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    MnnNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(MnnNotificationManager.NOTIFICATION_ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    fun initializeEngine(): Map<String, Any?> {
        directories.ensureCreated()
        val loaded = MnnNativeBridge.loaded
        val version = if (loaded) {
            runCatching { MnnNativeBridge.version() }.getOrElse { error ->
                logStore.error(TAG, "Failed to query MNN version", error)
                "unavailable"
            }
        } else {
            MnnNativeBridge.loadFailureMessage()?.let { message ->
                logStore.warn(TAG, "Native library is not ready: $message")
            }
            "unavailable"
        }
        updateSnapshot(
            snapshot.copy(
                engineState = if (loaded) "ready" else "native_unavailable",
                lastError = if (loaded) null else MnnNativeBridge.loadFailureMessage(),
            ),
        )
        logStore.info(TAG, "Engine initialized, nativeLoaded=$loaded, version=$version")
        return mapOf(
            "pluginVersion" to "0.0.1",
            "mnnVersion" to version.substringBefore(" (").ifBlank { "unavailable" },
            "mnnCommit" to version.substringAfter("(", "unknown").substringBefore(")"),
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
            "androidApiLevel" to Build.VERSION.SDK_INT,
            "ndkVersion" to "27.3.13750724",
            "nativeLibraryLoaded" to loaded,
            "testRootPath" to directories.rootDir.absolutePath,
        )
    }

    fun getSnapshot(): Map<String, Any?> = snapshot.toMap()

    fun getTestRootPath(): String {
        directories.ensureCreated()
        return directories.rootDir.absolutePath
    }

    fun listImportedModels(): List<Map<String, Any?>> {
        val active = runtimeManager.activeModel()
        return repository.list(active?.modelId).map { model ->
            if (active != null && model.modelId == active.modelId) active.toMap() else model.toMap()
        }
    }

    fun importModelDirectory(treeUri: Uri, replaceExisting: Boolean): Map<String, Any?> {
        requireServerStopped("import a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        return try {
            importer.import(treeUri, replaceExisting, activeModelId).toMap()
        } catch (error: MnnEngineOperationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            val code = if (error.message.orEmpty().contains("config.json")) {
                "model_config_not_found"
            } else {
                "model_path_not_readable"
            }
            throw MnnEngineOperationException(code, error.message ?: "Model import failed.", cause = error)
        }
    }

    fun deleteImportedModel(modelId: String) {
        requireServerStopped("delete a model")
        val activeModelId = snapshot.activeModel?.get("modelId") as String?
        repository.delete(modelId, activeModelId)
        logStore.info(TAG, "Deleted imported model: $modelId")
    }

    fun loadModel(modelId: String): Map<String, Any?> {
        requireServerStopped("load or switch models")
        return try {
            runtimeManager.load(modelId).toMap()
        } catch (error: MnnEngineOperationException) {
            throw error
        } catch (error: MnnRuntimeManager.GenerationBusyException) {
            throw MnnEngineOperationException("model_busy", error.message ?: "The model is busy.", cause = error)
        } catch (error: IllegalArgumentException) {
            throw MnnEngineOperationException("model_config_not_found", error.message ?: "Model not found.", cause = error)
        } catch (error: Throwable) {
            throw MnnEngineOperationException("model_load_failed", error.message ?: "MNN model load failed.", cause = error)
        }
    }

    fun unloadModel() {
        requireServerStopped("unload the model")
        try {
            runtimeManager.unload()
        } catch (error: MnnRuntimeManager.GenerationBusyException) {
            throw MnnEngineOperationException("model_busy", error.message ?: "The model is busy.", cause = error)
        } catch (error: Throwable) {
            throw MnnEngineOperationException("runtime_release_failed", error.message ?: "Failed to release MNN Runtime.", cause = error)
        }
    }

    fun cancelGeneration() {
        runtimeManager.cancelGeneration()
    }

    fun startServer(bindMode: String, port: Int, apiKey: String?): Map<String, Any?> {
        val mode = try {
            MnnBindMode.parse(bindMode)
        } catch (error: IllegalArgumentException) {
            throw MnnEngineOperationException("invalid_argument", error.message ?: "Invalid bind mode.")
        }
        if (mode == MnnBindMode.ALL_INTERFACES && (apiKey?.length ?: 0) < MIN_LAN_API_KEY_LENGTH) {
            throw MnnEngineOperationException(
                "invalid_argument",
                "allInterfaces mode requires an API key of at least $MIN_LAN_API_KEY_LENGTH characters.",
            )
        }
        val model = runtimeManager.activeModel()
            ?: throw MnnEngineOperationException("model_not_loaded", "Load a model before starting the MNN Server.")
        openAiServer.info()?.let { existing ->
            if (existing.bindMode == mode.wireName && existing.port == port) return existing.toMap()
            throw MnnEngineOperationException("server_start_failed", "MNN Server is already running at ${existing.baseUrl}.")
        }
        val portCheck = checkPort(mode.wireName, port)
        if (portCheck["available"] != true) {
            throw MnnEngineOperationException(
                "port_in_use",
                portCheck["message"]?.toString() ?: "Port is unavailable.",
                mapOf("bindMode" to mode.wireName, "port" to port),
            )
        }
        updateSnapshot(snapshot.copy(serverState = "starting", lastError = null))
        val baseUrl = "http://127.0.0.1:$port"
        val testUrl = baseUrl
        val foregroundIntent = Intent(this, MnnEngineService::class.java).apply {
            action = ACTION_START_FOREGROUND
            putExtra(EXTRA_BASE_URL, baseUrl)
            putExtra(EXTRA_TEST_URL, testUrl)
            putExtra(EXTRA_MODEL_NAME, model.displayName)
        }
        ContextCompat.startForegroundService(this, foregroundIntent)
        return try {
            val info = openAiServer.start(mode, port, apiKey)
            updateSnapshot(
                snapshot.copy(
                    serverState = "running",
                    server = info.toMap(),
                    lastError = null,
                ),
            )
            info.toMap()
        } catch (error: Throwable) {
            stopForegroundCompat()
            stopSelf()
            updateSnapshot(snapshot.copy(serverState = "error", server = null, lastError = error.message))
            if (error is MnnEngineOperationException) throw error
            throw MnnEngineOperationException(
                "server_start_failed",
                error.message ?: "Failed to start MNN Server.",
                cause = error,
            )
        }
    }

    fun stopServer() {
        if (openAiServer.info() == null) return
        updateSnapshot(snapshot.copy(serverState = "stopping"))
        openAiServer.stop()
        stopForegroundCompat()
        stopSelf()
        updateSnapshot(snapshot.copy(serverState = "stopped", server = null, lastError = null))
    }

    fun checkPort(bindMode: String, port: Int): Map<String, Any?> {
        val mode = runCatching { MnnBindMode.parse(bindMode) }.getOrElse { error ->
            return mapOf("available" to false, "ownedByMnn" to false, "message" to error.message)
        }
        if (port !in 1024..65535) {
            return mapOf(
                "available" to false,
                "ownedByMnn" to false,
                "message" to "Port must be between 1024 and 65535.",
            )
        }
        openAiServer.info()?.let { info ->
            if (info.bindMode == mode.wireName && info.port == port) {
                return mapOf("available" to false, "ownedByMnn" to true, "message" to null)
            }
        }
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(mode.host, port))
            }
            mapOf("available" to true, "ownedByMnn" to false, "message" to null)
        } catch (error: Exception) {
            mapOf(
                "available" to false,
                "ownedByMnn" to false,
                "message" to (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    fun addRuntimeListener(listener: (Map<String, Any?>) -> Unit) {
        runtimeListeners.addIfAbsent(listener)
    }

    fun removeRuntimeListener(listener: (Map<String, Any?>) -> Unit) {
        runtimeListeners.remove(listener)
    }

    private fun requireServerStopped(operation: String) {
        openAiServer.info()?.let { info ->
            throw MnnEngineOperationException(
                "model_busy",
                "Stop the MNN Server at ${info.baseUrl} before attempting to $operation.",
            )
        }
    }

    private fun updateSnapshot(newSnapshot: RuntimeSnapshot) {
        snapshot = newSnapshot.copy(revision = revision.incrementAndGet())
        val event = mapOf("type" to "snapshot", "snapshot" to snapshot.toMap())
        runtimeListeners.forEach { listener -> listener(event) }
    }

    private fun cleanupStaging() {
        val cutoff = System.currentTimeMillis() - STAGING_MAX_AGE_MS
        directories.stagingDir.listFiles().orEmpty().forEach { entry ->
            if (entry.lastModified() < cutoff || entry.listFiles().isNullOrEmpty()) {
                entry.deleteRecursively()
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        runCatching { openAiServer.stop() }
        runtimeManager.release()
        logStore.info(TAG, "Service destroyed")
        runtimeListeners.clear()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): MnnEngineService = this@MnnEngineService
    }

    private companion object {
        const val TAG = "MnnEngineService"
        const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val MIN_LAN_API_KEY_LENGTH = 16
        const val ACTION_START_FOREGROUND = "com.arkanefans.mnn_engine.START_FOREGROUND"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_TEST_URL = "test_url"
        const val EXTRA_MODEL_NAME = "model_name"
    }
}
