package com.naten.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

interface ExecutionListener {
    fun onStatus(message: String)
    fun onLog(message: String)
    fun onFinished(success: Boolean, message: String)
}

class WorkflowEngine(private val context: Context) {
    private val executor = Executors.newCachedThreadPool()

    fun run(state: WorkflowState, listener: ExecutionListener? = null, background: Boolean = false) {
        executor.execute {
            val runId = UUID.randomUUID().toString()
            val started = System.currentTimeMillis()
            var success = true
            var finishMessage = "Completed"
            val logs = mutableListOf<String>()

            fun report(message: String) {
                logs.add(message)
                listener?.onLog(message)
            }

            fun status(message: String) {
                listener?.onStatus(message)
            }

            try {
                if (state.nodes.isEmpty()) throw IllegalStateException("Workflow has no nodes.")

                val byId = state.nodes.associateBy { it.id }
                val outgoing = state.edges.groupBy { it.from }
                val startNodes = state.nodes.filter { it.type.endsWith("Trigger") }
                    .ifEmpty { listOf(state.nodes.minByOrNull { it.id }!!) }

                val queue = java.util.ArrayDeque<Int>()
                startNodes.forEach { queue.add(it.id) }
                val visited = mutableSetOf<Int>()
                val vars = mutableMapOf<String, String>()
                var data = JSONObject().apply {
                    put("trigger", "manual")
                    put("timestamp", now())
                }

                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    if (!visited.add(id)) continue
                    val node = byId[id] ?: continue
                    node.ensureDefaultConfig()

                    status("Running: " + node.title)
                    report("▶ " + node.type + ": " + node.title)

                    val result = executeNode(node, data, vars, background, report)
                    data = result.data

                    if (result.stop) {
                        if (!result.success) throw IllegalStateException(result.message)
                        finishMessage = result.message
                        break
                    }

                    val next = outgoing[id].orEmpty()
                    val selected = when {
                        result.branch.isNullOrBlank() -> next
                        else -> {
                            val matching = next.filter {
                                it.branch.equals(result.branch, ignoreCase = true)
                            }
                            if (matching.isNotEmpty()) matching
                            else next.filter { it.branch.equals("default", ignoreCase = true) }
                        }
                    }

                    selected.forEach { edge ->
                        if (byId.containsKey(edge.to)) queue.add(edge.to)
                    }
                }

                if (finishMessage == "Completed") finishMessage = "Completed " + visited.size + " node(s)"
                status("Workflow completed")
                report("✓ " + finishMessage)
            } catch (t: Throwable) {
                success = false
                finishMessage = t.message ?: "Workflow failed"
                status("Workflow failed")
                report("✕ " + finishMessage)
            } finally {
                val history = JSONObject().apply {
                    put("id", runId)
                    put("workflow", state.name)
                    put("started", started)
                    put("durationMs", System.currentTimeMillis() - started)
                    put("success", success)
                    put("message", finishMessage)
                    put("nodes", state.nodes.size)
                }
                WorkflowJson.appendHistory(context, history)

                if (background) {
                    postNotification(
                        "NATEN • " + state.name,
                        if (success) finishMessage else "Failed: " + finishMessage
                    )
                }

                listener?.onFinished(success, finishMessage)
            }
        }
    }

    private data class NodeResult(
        val data: JSONObject,
        val branch: String? = null,
        val stop: Boolean = false,
        val success: Boolean = true,
        val message: String = ""
    )

    private fun executeNode(
        node: FlowNode,
        input: JSONObject,
        vars: MutableMap<String, String>,
        background: Boolean,
        report: (String) -> Unit
    ): NodeResult {
        val c = node.config

        return when (node.type) {
            "Manual Trigger", "Schedule Trigger", "Webhook Trigger" -> {
                NodeResult(JSONObject(input.toString()))
            }

            "HTTP Request" -> {
                val method = c.optString("method", "GET").uppercase(Locale.US)
                val urlText = render(c.optString("url"), input, vars)
                if (urlText.isBlank()) throw IllegalArgumentException("HTTP Request needs a URL.")

                val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    useCaches = false
                    doInput = true
                }

                parseHeaders(render(c.optString("headers"), input, vars)).forEach { pair ->
                    conn.setRequestProperty(pair.first, pair.second)
                }

                if (method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    val body = render(c.optString("body"), input, vars)
                    if (body.isNotBlank()) {
                        if (conn.getRequestProperty("Content-Type").isNullOrBlank()) {
                            conn.setRequestProperty("Content-Type", "application/json")
                        }
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }

                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val text = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
                } ?: ""
                conn.disconnect()

                report("HTTP " + method + " " + code + " " + urlText.take(70))

                NodeResult(
                    JSONObject().apply {
                        put("statusCode", code)
                        put("ok", code in 200..399)
                        put("body", text)
                        if (text.trim().startsWith("{")) {
                            runCatching { put("json", JSONObject(text)) }
                        }
                        if (text.trim().startsWith("[")) {
                            runCatching { put("json", JSONArray(text)) }
                        }
                    }
                )
            }

            "Edit Fields" -> {
                val out = JSONObject(input.toString())
                parseFields(render(c.optString("fields"), input, vars)).forEach { pair ->
                    out.put(pair.first, pair.second)
                }
                NodeResult(out)
            }

            "IF" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: lookup(input, field.removePrefix("value.")) ?: ""
                val expected = render(c.optString("value"), input, vars)
                val op = c.optString("operator", "equals")
                val ok = compare(actual, expected, op)
                report("IF " + field + " " + op + " " + expected + " → " + ok)
                NodeResult(JSONObject(input.toString()), branch = if (ok) "true" else "false")
            }

            "Switch" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val cases = c.optString("cases").lines().map { it.trim() }.filter { it.isNotBlank() }
                val match = cases.firstOrNull { it == actual } ?: "default"
                report("Switch " + field + " → " + match)
                NodeResult(JSONObject(input.toString()), branch = match)
            }

            "Wait" -> {
                val seconds = c.optInt("seconds", 2).coerceIn(0, 86_400)
                report("Waiting " + seconds + "s")
                if (seconds > 0) Thread.sleep(seconds * 1000L)
                NodeResult(JSONObject(input.toString()))
            }

            "AI Text" -> {
                val provider = c.optString("provider", "OpenAI-compatible")
                val prompt = render(c.optString("prompt"), input, vars)
                val result = if (provider == "Gemini") {
                    callGemini(
                        endpoint = c.optString("endpoint"),
                        key = c.optString("apiKey"),
                        model = c.optString("model"),
                        prompt = prompt,
                        temperature = c.optDouble("temperature", 0.4)
                    )
                } else {
                    callOpenAiCompatible(
                        endpoint = c.optString("endpoint"),
                        key = c.optString("apiKey"),
                        model = c.optString("model"),
                        prompt = prompt,
                        temperature = c.optDouble("temperature", 0.4)
                    )
                }
                NodeResult(JSONObject(input.toString()).apply {
                    put("ai", result)
                    put("text", result)
                })
            }

            "Code" -> {
                val operation = c.optString("operation", "uppercase").lowercase(Locale.US)
                val field = c.optString("field", "text")
                val outputField = c.optString("outputField", field)
                val out = JSONObject(input.toString())
                val value = lookup(out, field) ?: ""
                val transformed: Any = when (operation) {
                    "lowercase" -> value.lowercase(Locale.US)
                    "uppercase" -> value.uppercase(Locale.US)
                    "length" -> value.length
                    "reverse" -> value.reversed()
                    "trim" -> value.trim()
                    "set value" -> render(c.optString("value"), input, vars)
                    else -> value
                }
                out.put(outputField, transformed)
                report("Transform: " + operation)
                NodeResult(out)
            }

            "Set Variable" -> {
                val key = c.optString("key", "value")
                val value = render(c.optString("value"), input, vars)
                vars[key] = value
                val out = JSONObject(input.toString()).apply { put(key, value) }
                report("Variable " + key + " set")
                NodeResult(out)
            }

            "Notification" -> {
                val title = render(c.optString("title", "NATEN"), input, vars)
                val message = render(c.optString("message", "Workflow finished"), input, vars)
                postNotification(title, message)
                report("Notification sent")
                NodeResult(JSONObject(input.toString()))
            }

            "Open URL" -> {
                val url = render(c.optString("url"), input, vars)
                if (!background && url.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    report("Opened " + url)
                } else {
                    report("Open URL skipped in background")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Share Text" -> {
                val shareText = render(c.optString("text"), input, vars)
                if (!background) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "Share with…")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Share sheet opened")
                } else {
                    report("Share Text skipped in background")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Read File" -> {
                val name = safeFileName(render(c.optString("filename", "input.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                val text = if (file.exists()) file.readText() else ""
                report("Read " + file.name + " (" + text.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", text)
                })
            }

            "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                file.writeText(render(c.optString("data"), input, vars))
                report("Wrote " + file.name)
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("filePath", file.absolutePath)
                })
            }

            "Log" -> {
                report(render(c.optString("message", "Log"), input, vars))
                NodeResult(JSONObject(input.toString()))
            }

            "Stop / Error" -> {
                val msg = render(c.optString("message", "Stopped"), input, vars)
                NodeResult(
                    JSONObject(input.toString()),
                    stop = true,
                    success = false,
                    message = msg
                )
            }

            "Merge" -> NodeResult(JSONObject(input.toString()))
            else -> NodeResult(JSONObject(input.toString()))
        }
    }

    private fun parseFields(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val clean = line.trim()
            if (clean.isBlank() || clean.startsWith("#")) return@forEach
            val idx = clean.indexOf('=')
            if (idx > 0) map[clean.substring(0, idx).trim()] = clean.substring(idx + 1).trim()
        }
        return map
    }

    private fun parseHeaders(text: String): Map<String, String> {
        return text.lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()
    }

    private fun safeFileName(text: String): String {
        val cleaned = text.replace(Regex("[\\\\/:*?\\\"<>|]"), "_")
        return cleaned.substringAfterLast('/').ifBlank { "file.txt" }
    }

    private fun compare(actualRaw: String, expectedRaw: String, op: String): Boolean {
        val actual = actualRaw.trim()
        val expected = expectedRaw.trim()

        return when (op.lowercase(Locale.US)) {
            "equals" -> actual == expected
            "not equals" -> actual != expected
            "contains" -> actual.contains(expected, ignoreCase = true)
            "starts with" -> actual.startsWith(expected, ignoreCase = true)
            "ends with" -> actual.endsWith(expected, ignoreCase = true)
            "greater than" -> actual.toDoubleOrNull()?.let { a ->
                expected.toDoubleOrNull()?.let { b -> a > b }
            } == true
            "less than" -> actual.toDoubleOrNull()?.let { a ->
                expected.toDoubleOrNull()?.let { b -> a < b }
            } == true
            "exists" -> actual.isNotBlank()
            "not exists" -> actual.isBlank()
            else -> actual == expected
        }
    }

    private fun lookup(root: JSONObject, path: String): String? {
        val clean = path
            .removePrefix("{{\$json.")
            .removeSuffix("}}")
            .removePrefix("json.")

        if (clean.isBlank() || clean == "json") return root.toString()

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }

        return when (cur) {
            JSONObject.NULL -> null
            else -> cur.toString()
        }
    }

    private fun render(input: String, data: JSONObject, vars: Map<String, String>): String {
        var out = input

        out = out.replace("{{\$now}}", now())
        out = out.replace("{{\$json}}", data.toString())

        Regex("\\{\\{\\$json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
            .findAll(out).toList().asReversed().forEach { match ->
                val key = match.groupValues.getOrElse(1) { "" }
                val replacement = if (key.isBlank()) data.toString()
                else lookup(data, key) ?: ""
                out = out.replace(match.value, replacement)
            }

        Regex("\\{\\{\\$vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
            .findAll(out).toList().asReversed().forEach { match ->
                out = out.replace(match.value, vars[match.groupValues[1]] ?: "")
            }

        return out
    }

    private fun callOpenAiCompatible(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        require(endpoint.isNotBlank()) { "AI endpoint is empty." }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
            put("temperature", temperature)
        }

        val json = postJson(endpoint, key, body)
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("output")
                .ifBlank { json.optString("text") }
                .ifBlank { json.toString() }
    }

    private fun callGemini(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        val finalEndpoint = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        }
        val url = if (finalEndpoint.contains("?")) {
            finalEndpoint + "&key=" + key
        } else {
            finalEndpoint + "?key=" + key
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().put("temperature", temperature))
        }

        val json = postJson(url, "", body)
        return json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?: json.toString()
    }

    private fun postJson(endpoint: String, key: String, body: JSONObject): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""

        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("AI request failed (" + code + "): " + text.take(180))
        }

        return runCatching { JSONObject(text) }.getOrElse {
            JSONObject().put("text", text)
        }
    }

    private fun postNotification(title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "naten_runs"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "NATEN workflow runs",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message.take(140))
            .setAutoCancel(true)
            .build()

        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
