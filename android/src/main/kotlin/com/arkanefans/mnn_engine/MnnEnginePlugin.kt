package com.arkanefans.mnn_engine

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import com.arkanefans.mnn_engine.logging.MnnLogEntry
import com.arkanefans.mnn_engine.runtime.MnnRuntimeManager
import com.arkanefans.mnn_engine.service.MnnEngineService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.util.concurrent.Executors

class MnnEnginePlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware,
    PluginRegistry.ActivityResultListener {
    private lateinit var applicationContext: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var logChannel: EventChannel
    private var service: MnnEngineService? = null
    private var bound = false
    private var bindingInProgress = false
    private val pendingCalls = ArrayDeque<PendingCall>()
    private var runtimeEventSink: EventChannel.EventSink? = null
    private var logEventSink: EventChannel.EventSink? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingImportResult: MethodChannel.Result? = null
    private var pendingReplaceExisting = true
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val runtimeListener: (Map<String, Any?>) -> Unit = { event ->
        mainHandler.post { runtimeEventSink?.success(event) }
    }
    private val logListener: (MnnLogEntry) -> Unit = { entry ->
        mainHandler.post { logEventSink?.success(entry.toMap()) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MnnEngineService.LocalBinder).getService()
            bindingInProgress = false
            bound = true
            service?.addRuntimeListener(runtimeListener)
            service?.logStore?.addListener(logListener)
            while (pendingCalls.isNotEmpty()) {
                val pending = pendingCalls.removeFirst()
                dispatch(pending.call, pending.result)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeRuntimeListener(runtimeListener)
            service?.logStore?.removeListener(logListener)
            service = null
            bindingInProgress = false
            bound = false
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        logChannel = EventChannel(binding.binaryMessenger, LOG_CHANNEL)
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(runtimeStreamHandler)
        logChannel.setStreamHandler(logStreamHandler)
        bindService()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (service == null) {
            pendingCalls.addLast(PendingCall(call, result))
            bindService()
            return
        }
        dispatch(call, result)
    }

    private fun dispatch(call: MethodCall, result: MethodChannel.Result) {
        val currentService = service
        if (currentService == null) {
            result.error("service_not_connected", "MNN engine service is not connected.", null)
            return
        }
        try {
            when (call.method) {
                "initialize" -> result.success(currentService.initializeEngine())
                "getSnapshot" -> result.success(currentService.getSnapshot())
                "getTestRootPath" -> result.success(currentService.getTestRootPath())
                "listImportedModels" -> executeIo(result) { currentService.listImportedModels() }
                "importModelDirectory" -> startModelDirectoryPicker(
                    result,
                    call.argument<Boolean>("replaceExisting") ?: true,
                )
                "deleteImportedModel" -> {
                    val modelId = call.argument<String>("modelId")
                        ?: throw IllegalArgumentException("modelId is required.")
                    executeIo(result) {
                        currentService.deleteImportedModel(modelId)
                        null
                    }
                }
                "loadModel" -> {
                    val modelId = call.argument<String>("modelId")
                        ?: throw IllegalArgumentException("modelId is required.")
                    executeIo(result) { currentService.loadModel(modelId) }
                }
                "unloadModel" -> executeIo(result) {
                    currentService.unloadModel()
                    null
                }
                "cancelGeneration" -> {
                    currentService.cancelGeneration()
                    result.success(null)
                }
                "startServer" -> executeIo(result) {
                    currentService.startServer(
                        host = call.argument<String>("host") ?: "127.0.0.1",
                        port = call.argument<Int>("port") ?: 8081,
                        apiKey = call.argument<String>("apiKey"),
                    )
                }
                "stopServer" -> executeIo(result) {
                    currentService.stopServer()
                    null
                }
                "checkPort" -> executeIo(result) {
                    currentService.checkPort(
                        call.argument<String>("host") ?: "127.0.0.1",
                        call.argument<Int>("port") ?: 8081,
                    )
                }
                "getLogSnapshot" -> result.success(
                    currentService.logStore.snapshot().map(MnnLogEntry::toMap),
                )
                "clearLogs" -> {
                    currentService.logStore.clear()
                    result.success(null)
                }
                else -> result.error(
                    "not_implemented",
                    "${call.method} is not implemented yet.",
                    null,
                )
            }
        } catch (error: Throwable) {
            currentService.logStore.error("plugin", "${call.method} failed", error)
            sendError(result, error)
        }
    }

    private fun executeIo(
        result: MethodChannel.Result,
        operation: () -> Any?,
    ) {
        ioExecutor.execute {
            val outcome = runCatching(operation)
            mainHandler.post {
                outcome.onSuccess(result::success).onFailure { error ->
                    service?.logStore?.error("plugin", "Background operation failed", error)
                    sendError(result, error)
                }
            }
        }
    }

    private fun startModelDirectoryPicker(
        result: MethodChannel.Result,
        replaceExisting: Boolean,
    ) {
        val activity = activityBinding?.activity
        if (activity == null) {
            result.error("activity_unavailable", "An Android activity is required to import a model.", null)
            return
        }
        if (pendingImportResult != null) {
            result.error("import_in_progress", "A model import is already in progress.", null)
            return
        }
        pendingImportResult = result
        pendingReplaceExisting = replaceExisting
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        activity.startActivityForResult(intent, IMPORT_MODEL_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != IMPORT_MODEL_REQUEST_CODE) return false
        val result = pendingImportResult ?: return true
        pendingImportResult = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            result.error("import_cancelled", "Model directory selection was cancelled.", null)
            return true
        }
        val treeUri = data.data!!
        runCatching {
            applicationContext.contentResolver.takePersistableUriPermission(
                treeUri,
                data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val replaceExisting = pendingReplaceExisting
        ioExecutor.execute {
            val operation = runCatching {
                service?.importModelDirectory(treeUri, replaceExisting)
                    ?: throw IllegalStateException("MNN engine service is not connected.")
            }
            mainHandler.post {
                operation.onSuccess(result::success).onFailure { error ->
                    service?.logStore?.error("plugin", "Model import failed", error)
                    sendError(result, error, fallbackCode = "model_import_failed")
                }
            }
        }
        return true
    }

    private fun bindService() {
        if (bound || bindingInProgress) return
        val intent = Intent(applicationContext, MnnEngineService::class.java)
        bindingInProgress = applicationContext.bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bindingInProgress) {
            while (pendingCalls.isNotEmpty()) {
                pendingCalls.removeFirst().result.error(
                    "service_bind_failed",
                    "Failed to bind MNN engine service.",
                    null,
                )
            }
        }
    }

    private fun sendError(
        result: MethodChannel.Result,
        error: Throwable,
        fallbackCode: String = "native_error",
    ) {
        val operationError = error as? MnnEngineOperationException
        val code = when {
            operationError != null -> operationError.code
            error is MnnRuntimeManager.GenerationBusyException -> "model_busy"
            error is IllegalArgumentException -> "invalid_argument"
            else -> fallbackCode
        }
        result.error(
            code,
            error.message ?: error.javaClass.simpleName,
            operationError?.details,
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        logChannel.setStreamHandler(null)
        service?.removeRuntimeListener(runtimeListener)
        service?.logStore?.removeListener(logListener)
        if (bound || bindingInProgress) {
            applicationContext.unbindService(serviceConnection)
        }
        service = null
        bindingInProgress = false
        bound = false
        pendingCalls.clear()
        pendingImportResult?.error("plugin_detached", "Plugin detached during model import.", null)
        pendingImportResult = null
        ioExecutor.shutdownNow()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity()
    }

    private fun detachActivity() {
        activityBinding?.removeActivityResultListener(this)
        activityBinding = null
    }

    private val runtimeStreamHandler = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            runtimeEventSink = events
            service?.getSnapshot()?.let { snapshot ->
                events?.success(mapOf("type" to "snapshot", "snapshot" to snapshot))
            }
        }

        override fun onCancel(arguments: Any?) {
            runtimeEventSink = null
        }
    }

    private val logStreamHandler = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            logEventSink = events
        }

        override fun onCancel(arguments: Any?) {
            logEventSink = null
        }
    }

    private data class PendingCall(
        val call: MethodCall,
        val result: MethodChannel.Result,
    )

    private companion object {
        const val METHOD_CHANNEL = "com.arkanefans.mnn_engine/methods"
        const val EVENT_CHANNEL = "com.arkanefans.mnn_engine/events"
        const val LOG_CHANNEL = "com.arkanefans.mnn_engine/logs"
        const val IMPORT_MODEL_REQUEST_CODE = 5801
    }
}
