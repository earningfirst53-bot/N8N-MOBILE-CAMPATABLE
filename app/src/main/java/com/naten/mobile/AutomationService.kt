package com.naten.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutomationService : Service() {
    private var webServer: ServerSocket? = null
    private var webThread: Thread? = null
    private val serverPool = Executors.newCachedThreadPool()
    private val engine by lazy { WorkflowEngine(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("NATEN automation active", "Background automation is running")

        when (intent?.action) {
            ACTION_RUN -> {
                val workflowId = WorkflowScheduler.workflowId(intent)
                val state = workflowId?.let { WorkflowStore.load(this, it) }
                    ?: WorkflowStore.loadCurrent(this)

                if (state == null || !state.active) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                runWorkflowAndStop(state, startId)
            }

            null, ACTION_START -> {
                val webhooks = WorkflowStore.list(this).filter { state ->
                    state.active && state.nodes.any {
                        it.type == "Webhook Trigger" || it.type == "Chat Trigger"
                    }
                }
                if (webhooks.isEmpty()) {
                    stopSelf(startId)
                } else {
                    startWebhookServer()
                }
            }

            else -> Unit
        }

        val keepAlive = webServer != null
        return if (keepAlive) START_STICKY else START_NOT_STICKY
    }

    private fun runWorkflowAndStop(state: WorkflowState, startId: Int) {
        engine.run(
            state = state,
            listener = object : ExecutionListener {
                override fun onStatus(message: String) {
                    updateNotification("NATEN • " + state.name, message)
                }

                override fun onLog(message: String) = Unit

                override fun onFinished(success: Boolean, message: String, output: JSONObject) {
                    updateNotification(
                        "NATEN • " + state.name,
                        if (success) message else "Failed: " + message
                    )
                    stopSelf(startId)
                }
            },
            background = true
        )
    }

    private fun startWebhookServer() {
        if (webServer != null) return

        webThread = Thread {
            try {
                val server = ServerSocket(WEBHOOK_PORT)
                webServer = server
                updateNotification(
                    "NATEN webhook server",
                    "Listening on port " + WEBHOOK_PORT
                )

                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        serverPool.execute { handleConnection(socket) }
                    } catch (_: Throwable) {
                        if (!server.isClosed) continue
                    }
                }
            } catch (t: Throwable) {
                updateNotification(
                    "NATEN webhook server",
                    "Server error: " + (t.message ?: "unknown")
                )
            }
        }.apply {
            name = "NATEN-Webhook"
            start()
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            it.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendResponse(it, 400, JSONObject().put("error", "Bad request"))
                return
            }

            val method = parts[0].uppercase(Locale.US)
            val rawTarget = parts[1]
            val headers = linkedMapOf<String, String>()

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                        line.substring(idx + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
            val bodyChars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(bodyChars, read, contentLength - read)
                if (n <= 0) break
                read += n
            }

            val body = String(bodyChars, 0, read)
            val path = rawTarget.substringBefore('?')
            val query = rawTarget.substringAfter('?', "")
            val matched = findWebhookWorkflow(path, method)

            if (matched == null) {
                sendResponse(
                    it,
                    404,
                    JSONObject().put("error", "No active NATEN webhook matches this path")
                )
                return
            }

            val input = JSONObject().apply {
                put("method", method)
                put("path", path)
                put("query", parseQuery(query))
                put("headers", JSONObject().apply {
                    headers.forEach { pair -> put(pair.key, pair.value) }
                })
                put("body", body)
                if (body.trim().startsWith("{")) {
                    runCatching { put("json", JSONObject(body)) }
                }
                if (body.trim().startsWith("[")) {
                    runCatching { put("json", JSONArray(body)) }
                }
            }

            val latch = CountDownLatch(1)
            var success = false
            var message = "Execution started"
            var output = JSONObject()

            engine.runWithInput(
                state = matched,
                input = input,
                listener = object : ExecutionListener {
                    override fun onStatus(message: String) {
                        updateNotification("Webhook • " + matched.name, message)
                    }

                    override fun onLog(message: String) = Unit

                    override fun onFinished(ok: Boolean, result: String) {
                        success = ok
                        message = result
                        latch.countDown()
                    }
                },
                background = true
            )

            latch.await(5, TimeUnit.MINUTES)

            sendResponse(
                it,
                if (success) 200 else 500,
                JSONObject().apply {
                    put("workflow", matched.name)
                    put("success", success)
                    put("message", message)
                    put("output", output)
                }
            )
        }
    }

    private fun findWebhookWorkflow(path: String, method: String): WorkflowState? {
        return WorkflowStore.list(this).firstOrNull { state ->
            if (!state.active) return@firstOrNull false

            state.nodes.any { node ->
                if (node.type != "Webhook Trigger" && node.type != "Chat Trigger") {
                    return@any false
                }
                node.ensureDefaultConfig()

                val configuredPath = node.config.optString(
                    "path",
                    "hook/" + state.id.take(8)
                ).trim('/')

                val configuredMethod = node.config.optString("method", "POST").uppercase(Locale.US)
                val methodOk = configuredMethod == "ANY" || configuredMethod == method
                val normalized = path.trim('/')

                methodOk && (
                    normalized == configuredPath ||
                    normalized == "hook/" + state.id ||
                    normalized == "hook/" + state.id.take(8)
                )
            }
        }
    }

    private fun parseQuery(value: String): JSONObject {
        val out = JSONObject()
        if (value.isBlank()) return out

        value.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val raw = if (idx >= 0) pair.substring(idx + 1) else ""
            out.put(
                URLDecoder.decode(key, "UTF-8"),
                URLDecoder.decode(raw, "UTF-8")
            )
        }
        return out
    }

    private fun sendResponse(socket: Socket, code: Int, body: JSONObject) {
        val output = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        val text = body.toString()
        output.write(
            "HTTP/1.1 " + code + " " + statusText(code) + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + text.toByteArray(Charsets.UTF_8).size + "\r\n" +
                "Connection: close\r\n\r\n" +
                text
        )
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun startAsForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text.take(160))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NATEN Automation",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        webServer?.close()
        webServer = null
        webThread?.interrupt()
        webThread = null
        serverPool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_RUN = "com.naten.mobile.RUN_WORKFLOW"
        const val ACTION_START = "com.naten.mobile.START_AUTOMATION"
        const val WEBHOOK_PORT = 8787
        private const val CHANNEL_ID = "naten_automation"
        private const val NOTIFICATION_ID = 19017
    }
}
