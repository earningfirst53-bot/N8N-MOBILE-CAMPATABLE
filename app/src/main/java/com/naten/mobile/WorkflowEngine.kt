package com.naten.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

    fun run(
        state: WorkflowState,
        listener: ExecutionListener? = null,
        background: Boolean = false
    ) {
        runWithInput(state, null, listener, background)
    }

    fun runWithInput(
        state: WorkflowState,
        input: JSONObject?,
        listener: ExecutionListener? = null,
        background: Boolean = false
    ) {
        executor.execute {
            val started = System.currentTimeMillis()
            val runId = UUID.randomUUID().toString()
            var success = true
            var message = "Completed"
            val vars = mutableMapOf<String, String>()
            val logs = mutableListOf<String>()

            fun report(text: String) {
                logs.add(text)
                listener?.onLog(text)
            }

            try {
                require(state.nodes.isNotEmpty()) { "Workflow has no nodes." }

                val byId = state.nodes.associateBy { it.id }
                val outgoing = state.edges.groupBy { it.from }

                val preferred = if (background) {
                    state.nodes.filter { it.type == "Schedule Trigger" }
                } else {
                    state.nodes.filter { it.type == "Manual Trigger" || it.type == "Webhook Trigger" || it.type == "Chat Trigger" }
                }

                val starts = preferred.ifEmpty {
                    state.nodes.filter { it.type.endsWith("Trigger") }
                }.ifEmpty {
                    listOf(state.nodes.minByOrNull { it.id }!!)
                }

                val queue = java.util.ArrayDeque<Int>()
                starts.forEach { queue.addLast(it.id) }

                val visited = mutableSetOf<Int>()
                var data = input?.let { JSONObject(it.toString()) } ?: JSONObject().apply {
                    put("trigger", if (background) "schedule" else "manual")
                    put("timestamp", now())
                }

                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    if (!visited.add(id)) continue

                    val node = byId[id] ?: continue
                    node.ensureDefaultConfig()
                    listener?.onStatus("Running: " + node.title)
                    report("▶ " + node.type + ": " + node.title)

                    val result = executeNode(node, data, vars, background, ::report)
                    data = result.data

                    if (result.stop) {
                        if (!result.success) throw IllegalStateException(result.message)
                        message = result.message
                        break
                    }

                    val next = outgoing[id].orEmpty()
                    val selected = if (result.branch.isNullOrBlank()) {
                        next
                    } else {
                        val exact = next.filter {
                            it.branch.equals(result.branch, ignoreCase = true)
                        }
                        if (exact.isNotEmpty()) exact
                        else next.filter { it.branch.equals("default", ignoreCase = true) }
                    }

                    selected.forEach { edge ->
                        if (byId.containsKey(edge.to)) queue.addLast(edge.to)
                    }
                }

                if (message == "Completed") {
                    message = "Completed " + visited.size + " node(s)"
                }
                listener?.onStatus("Workflow completed")
                report("✓ " + message)
            } catch (t: Throwable) {
                success = false
                message = t.message ?: "Workflow failed"
                listener?.onStatus("Workflow failed")
                report("✕ " + message)
            } finally {
                WorkflowJson.appendHistory(
                    context,
                    JSONObject().apply {
                        put("runId", runId)
                        put("workflow", state.name)
                        put("workflowId", state.id)
                        put("started", started)
                        put("durationMs", System.currentTimeMillis() - started)
                        put("success", success)
                        put("message", message)
                        put("nodes", state.nodes.size)
                    }
                )

                if (background) {
                    postNotification(
                        "NATEN • " + state.name,
                        if (success) message else "Failed: " + message
                    )
                }

                listener?.onFinished(success, message)
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
            "Manual Trigger", "Schedule Trigger", "Webhook Trigger", "Merge" ->
                NodeResult(JSONObject(input.toString()))

            "HTTP Request", "Generic API",
            "Email", "Telegram", "Slack", "Google Sheets", "Gmail" ->
                executeHttp(c, input, vars, report)

            "GraphQL" -> executeGraphQl(c, input, vars, report)

            "JSON Parse" -> {
                val field = c.optString("field", "text")
                val expression = "{{" + '
                val out = JSONObject(input.toString())
                fields(render(c.optString("fields"), input, vars)).forEach { pair ->
                    out.put(pair.key, pair.value)
                }
                NodeResult(out)
            }

            "IF" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val expected = render(c.optString("value"), input, vars)
                val op = c.optString("operator", "equals")
                val result = compare(actual, expected, op)
                report("IF " + field + " " + op + " " + expected + " → " + result)
                NodeResult(JSONObject(input.toString()), branch = if (result) "true" else "false")
            }

            "Switch" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val cases = c.optString("cases").lines().map { it.trim() }.filter { it.isNotBlank() }
                val route = cases.firstOrNull { it == actual } ?: "default"
                report("Switch " + field + " → " + route)
                NodeResult(JSONObject(input.toString()), branch = route)
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
                val answer = if (provider == "Gemini") {
                    gemini(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                } else {
                    openAiCompatible(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                }
                report("AI response received (" + answer.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            "Code" -> {
                val operation = c.optString("operation", "uppercase").lowercase(Locale.US)
                val field = c.optString("field", "text")
                val outputField = c.optString("outputField", field)
                val value = lookup(input, field) ?: ""
                val out = JSONObject(input.toString())
                val transformed: Any = when (operation) {
                    "uppercase" -> value.uppercase(Locale.US)
                    "lowercase" -> value.lowercase(Locale.US)
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
                NodeResult(JSONObject(input.toString()).apply { put(key, value) })
            }

            "Notification" -> {
                val title = render(c.optString("title", "NATEN"), input, vars)
                val body = render(c.optString("message", "Workflow finished"), input, vars)
                postNotification(title, body)
                report("Notification sent")
                NodeResult(JSONObject(input.toString()))
            }

            "Open URL" -> {
                val url = render(c.optString("url"), input, vars)
                if (!background && url.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Opened " + url)
                } else {
                    report("Open URL skipped in background")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Share Text" -> {
                val value = render(c.optString("text"), input, vars)
                if (!background) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share with…")
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
                val body = if (file.exists()) file.readText() else ""
                report("Read " + name)
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", body)
                })
            }

            "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                file.writeText(render(c.optString("data"), input, vars))
                report("Wrote " + name)
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
                val text = render(c.optString("message", "Stopped"), input, vars)
                NodeResult(JSONObject(input.toString()), stop = true, success = false, message = text)
            }

            else -> NodeResult(JSONObject(input.toString()))
        }
    }

    private fun executeGraphQl(
        c: JSONObject,
        input: JSONObject,
        vars: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val url = render(c.optString("url"), input, vars)
        require(url.isNotBlank()) { "GraphQL needs a URL." }

        val variablesText = render(c.optString("variables", "{}"), input, vars)
        val body = JSONObject().apply {
            put("query", render(c.optString("query"), input, vars))
            put("variables", runCatching { JSONObject(variablesText) }.getOrElse { JSONObject() })
        }

        val result = postJsonWithHeaders(
            url,
            "",
            body,
            headers(render(c.optString("headers"), input, vars))
        )
        report("GraphQL request completed")
        return NodeResult(result)
    }

    private fun executeHttp(
        c: JSONObject,
        input: JSONObject,
        vars: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val method = c.optString("method", "GET").uppercase(Locale.US)
        val urlText = render(c.optString("url"), input, vars)
        require(urlText.isNotBlank()) { "HTTP Request needs a URL." }

        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            doInput = true
        }

        headers(render(c.optString("headers"), input, vars)).forEach {
            conn.setRequestProperty(it.key, it.value)
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        report("HTTP " + method + " " + code + " " + urlText.take(80))

        val out = JSONObject().apply {
            put("statusCode", code)
            put("ok", code < 400)
            put("body", body)
            if (body.trim().startsWith("{")) runCatching { put("json", JSONObject(body)) }
            if (body.trim().startsWith("[")) runCatching { put("json", JSONArray(body)) }
        }

        if (code >= 400) {
            throw IllegalStateException("HTTP " + code + ": " + body.take(180))
        }
        return NodeResult(out)
    }

    private fun fields(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val s = line.trim()
            if (s.isBlank() || s.startsWith("#")) return@forEach
            val idx = s.indexOf('=')
            if (idx > 0) result[s.substring(0, idx).trim()] = s.substring(idx + 1).trim()
        }
        return result
    }

    private fun headers(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return result
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return cleaned.substringAfterLast('/').ifBlank { "file.txt" }
    }

    private fun compare(actual: String, expected: String, op: String): Boolean {
        return when (op.lowercase(Locale.US)) {
            "equals" -> actual == expected
            "not equals" -> actual != expected
            "contains" -> actual.contains(expected, true)
            "starts with" -> actual.startsWith(expected, true)
            "ends with" -> actual.endsWith(expected, true)
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

    private fun lookupRaw(root: JSONObject, path: String): Any? {
        val dollar = '
        val dollar = '$'
        val prefix = "{{" + dollar + "json."
        val clean = path.removePrefix(prefix).removeSuffix("}}").removePrefix("json.")
        if (clean.isBlank() || clean == "json") return root.toString()

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return cur.toString()
    }

    private fun render(input: String, data: JSONObject, vars: Map<String, String>): String {
        var out = input
        val dollar = '$'

        out = out.replace("{{" + dollar + "now}}", now())
        out = out.replace("{{" + dollar + "json}}", data.toString())

        val jsonPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
        jsonPattern.findAll(out).toList().asReversed().forEach { match ->
            val key = match.groupValues.getOrElse(1) { "" }
            out = out.replace(match.value, if (key.isBlank()) data.toString() else lookup(data, key).orEmpty())
        }

        val varsPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
        varsPattern.findAll(out).toList().asReversed().forEach { match ->
            out = out.replace(match.value, vars[match.groupValues[1]].orEmpty())
        }
        return out
    }

    private fun openAiCompatible(
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

    private fun gemini(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        val base = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        }
        val url = if (base.contains("?")) base + "&key=" + key else base + "?key=" + key
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        if (code >= 400) {
            throw IllegalStateException("AI request failed (" + code + "): " + text.take(180))
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
    }

    private fun postJsonWithHeaders(
        endpoint: String,
        key: String,
        body: JSONObject,
        extraHeaders: Map<String, String>
    ): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer " + key)
            extraHeaders.forEach { pair -> setRequestProperty(pair.key, pair.value) }
        }

        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        if (code >= 400) {
            throw IllegalStateException("Request failed (" + code + "): " + text.take(180))
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("body", text) }
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

        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message.take(140))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
 + "json." + field + "}}"
                val raw = render(c.optString("value").ifBlank { expression }, input, vars)
                val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("value", raw) }
                NodeResult(JSONObject(input.toString()).apply { put(field, parsed) })
            }

            "JSON Stringify" -> {
                val field = c.optString("field", "json")
                val value = lookupRaw(input, field) ?: input
                NodeResult(JSONObject(input.toString()).apply { put(field + "Text", value.toString()) })
            }

            "Filter" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field).orEmpty()
                val expected = render(c.optString("value"), input, vars)
                val ok = compare(actual, expected, c.optString("operator", "exists"))
                NodeResult(JSONObject(input.toString()), branch = if (ok) "true" else "false")
            }

            "Limit" -> NodeResult(JSONObject(input.toString()).apply {
                put("_limit", c.optInt("count", 10).coerceAtLeast(0))
            })

            "Remove Duplicates" -> NodeResult(JSONObject(input.toString()))

            "Rename Keys" -> {
                val out = JSONObject(input.toString())
                fields(c.optString("mapping")).forEach { pair ->
                    if (out.has(pair.key)) {
                        val value = out.opt(pair.key)
                        out.remove(pair.key)
                        out.put(pair.value, value)
                    }
                }
                NodeResult(out)
            }

            "Sort" -> {
                val field = c.optString("field", "value")
                NodeResult(JSONObject(input.toString()).apply {
                    put("_sortKey", lookup(input, field).orEmpty())
                    put("_sortDescending", c.optBoolean("descending", false))
                })
            }

            "Split Out" -> {
                val field = c.optString("field", "items")
                val raw = lookupRaw(input, field)
                if (raw is JSONArray) {
                    NodeResult(JSONObject(input.toString()).apply {
                        put("_splitCount", raw.length())
                        put("_splitItems", raw)
                    })
                } else {
                    NodeResult(JSONObject(input.toString()))
                }
            }

            "Summarize" -> {
                val op = c.optString("operation", "count").lowercase(Locale.US)
                val field = c.optString("field", "value")
                val number = lookup(input, field)?.toDoubleOrNull()
                val result: Any = when (op) {
                    "sum" -> number ?: 0.0
                    "average" -> number ?: 0.0
                    "count" -> 1
                    else -> lookup(input, field).orEmpty()
                }
                NodeResult(JSONObject().put("summary", result).put("source", input))
            }

            "Date & Time" -> {
                val format = c.optString("format", "yyyy-MM-dd HH:mm:ss")
                val formatted = runCatching {
                    java.text.SimpleDateFormat(format, Locale.getDefault()).format(java.util.Date())
                }.getOrElse { now() }
                NodeResult(JSONObject(input.toString()).apply { put("dateTime", formatted) })
            }

            "Markdown" -> {
                val field = c.optString("field", "text")
                val raw = lookup(input, field).orEmpty()
                val inlineCode = Character.toString(96)
                val clean = raw
                    .replace(Regex("\\*\\*(.*?)\\*\\*")) { it.groupValues[1] }
                    .replace(Regex(inlineCode + "([^" + inlineCode + "]*)" + inlineCode)) { it.groupValues[1] }
                    .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
                NodeResult(JSONObject(input.toString()).apply { put(field + "Plain", clean) })
            }

            "HTML", "XML" -> {
                val field = c.optString("field", if (node.type == "HTML") "html" else "xml")
                val raw = lookup(input, field).orEmpty()
                val clean = raw.replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                NodeResult(JSONObject(input.toString()).apply { put(field + "Text", clean) })
            }

            "Crypto" -> {
                val field = c.optString("field", "text")
                val value = lookup(input, field).orEmpty()
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                NodeResult(JSONObject(input.toString()).apply { put("sha256", digest) })
            }

            "Convert to File", "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                val dataValue = c.optString("data").ifBlank {
                    "{{" + '
                val out = JSONObject(input.toString())
                fields(render(c.optString("fields"), input, vars)).forEach { pair ->
                    out.put(pair.key, pair.value)
                }
                NodeResult(out)
            }

            "IF" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val expected = render(c.optString("value"), input, vars)
                val op = c.optString("operator", "equals")
                val result = compare(actual, expected, op)
                report("IF " + field + " " + op + " " + expected + " → " + result)
                NodeResult(JSONObject(input.toString()), branch = if (result) "true" else "false")
            }

            "Switch" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val cases = c.optString("cases").lines().map { it.trim() }.filter { it.isNotBlank() }
                val route = cases.firstOrNull { it == actual } ?: "default"
                report("Switch " + field + " → " + route)
                NodeResult(JSONObject(input.toString()), branch = route)
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
                val answer = if (provider == "Gemini") {
                    gemini(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                } else {
                    openAiCompatible(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                }
                report("AI response received (" + answer.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            "Code" -> {
                val operation = c.optString("operation", "uppercase").lowercase(Locale.US)
                val field = c.optString("field", "text")
                val outputField = c.optString("outputField", field)
                val value = lookup(input, field) ?: ""
                val out = JSONObject(input.toString())
                val transformed: Any = when (operation) {
                    "uppercase" -> value.uppercase(Locale.US)
                    "lowercase" -> value.lowercase(Locale.US)
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
                NodeResult(JSONObject(input.toString()).apply { put(key, value) })
            }

            "Notification" -> {
                val title = render(c.optString("title", "NATEN"), input, vars)
                val body = render(c.optString("message", "Workflow finished"), input, vars)
                postNotification(title, body)
                report("Notification sent")
                NodeResult(JSONObject(input.toString()))
            }

            "Open URL" -> {
                val url = render(c.optString("url"), input, vars)
                if (!background && url.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Opened " + url)
                } else {
                    report("Open URL skipped in background")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Share Text" -> {
                val value = render(c.optString("text"), input, vars)
                if (!background) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share with…")
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
                val body = if (file.exists()) file.readText() else ""
                report("Read " + name)
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", body)
                })
            }

            "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                file.writeText(render(c.optString("data"), input, vars))
                report("Wrote " + name)
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
                val text = render(c.optString("message", "Stopped"), input, vars)
                NodeResult(JSONObject(input.toString()), stop = true, success = false, message = text)
            }

            else -> NodeResult(JSONObject(input.toString()))
        }
    }

    private fun executeHttp(
        c: JSONObject,
        input: JSONObject,
        vars: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val method = c.optString("method", "GET").uppercase(Locale.US)
        val urlText = render(c.optString("url"), input, vars)
        require(urlText.isNotBlank()) { "HTTP Request needs a URL." }

        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            doInput = true
        }

        headers(render(c.optString("headers"), input, vars)).forEach {
            conn.setRequestProperty(it.key, it.value)
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        report("HTTP " + method + " " + code + " " + urlText.take(80))

        val out = JSONObject().apply {
            put("statusCode", code)
            put("ok", code < 400)
            put("body", body)
            if (body.trim().startsWith("{")) runCatching { put("json", JSONObject(body)) }
            if (body.trim().startsWith("[")) runCatching { put("json", JSONArray(body)) }
        }

        if (code >= 400) {
            throw IllegalStateException("HTTP " + code + ": " + body.take(180))
        }
        return NodeResult(out)
    }

    private fun fields(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val s = line.trim()
            if (s.isBlank() || s.startsWith("#")) return@forEach
            val idx = s.indexOf('=')
            if (idx > 0) result[s.substring(0, idx).trim()] = s.substring(idx + 1).trim()
        }
        return result
    }

    private fun headers(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return result
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return cleaned.substringAfterLast('/').ifBlank { "file.txt" }
    }

    private fun compare(actual: String, expected: String, op: String): Boolean {
        return when (op.lowercase(Locale.US)) {
            "equals" -> actual == expected
            "not equals" -> actual != expected
            "contains" -> actual.contains(expected, true)
            "starts with" -> actual.startsWith(expected, true)
            "ends with" -> actual.endsWith(expected, true)
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
        val dollar = '$'
        val prefix = "{{" + dollar + "json."
        val clean = path.removePrefix(prefix).removeSuffix("}}").removePrefix("json.")
        if (clean.isBlank() || clean == "json") return root.toString()

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return cur.toString()
    }

    private fun render(input: String, data: JSONObject, vars: Map<String, String>): String {
        var out = input
        val dollar = '$'

        out = out.replace("{{" + dollar + "now}}", now())
        out = out.replace("{{" + dollar + "json}}", data.toString())

        val jsonPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
        jsonPattern.findAll(out).toList().asReversed().forEach { match ->
            val key = match.groupValues.getOrElse(1) { "" }
            out = out.replace(match.value, if (key.isBlank()) data.toString() else lookup(data, key).orEmpty())
        }

        val varsPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
        varsPattern.findAll(out).toList().asReversed().forEach { match ->
            out = out.replace(match.value, vars[match.groupValues[1]].orEmpty())
        }
        return out
    }

    private fun openAiCompatible(
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

    private fun gemini(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        val base = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        }
        val url = if (base.contains("?")) base + "&key=" + key else base + "?key=" + key
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        if (code >= 400) {
            throw IllegalStateException("AI request failed (" + code + "): " + text.take(180))
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
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

        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message.take(140))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
 + "json}}"
                }
                file.writeText(render(dataValue, input, vars))
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("filePath", file.absolutePath)
                })
            }

            "Extract From File", "Read File" -> {
                val name = safeFileName(render(c.optString("filename", "input.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                val body = if (file.exists()) file.readText() else ""
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", body)
                })
            }

            "AI Text", "AI Agent" -> {
                val provider = c.optString("provider", "OpenAI-compatible")
                val prompt = render(c.optString("prompt"), input, vars)
                val answer = if (provider == "Gemini") {
                    gemini(c.optString("endpoint"), c.optString("apiKey"), c.optString("model"), prompt, c.optDouble("temperature", 0.4))
                } else {
                    openAiCompatible(c.optString("endpoint"), c.optString("apiKey"), c.optString("model"), prompt, c.optDouble("temperature", 0.4))
                }
                report("AI response received (" + answer.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            "No Operation", "Error Trigger", "Chat Trigger" ->
                NodeResult(JSONObject(input.toString()))

            "Edit Fields" -> {
                val out = JSONObject(input.toString())
                fields(render(c.optString("fields"), input, vars)).forEach { pair ->
                    out.put(pair.key, pair.value)
                }
                NodeResult(out)
            }

            "IF" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val expected = render(c.optString("value"), input, vars)
                val op = c.optString("operator", "equals")
                val result = compare(actual, expected, op)
                report("IF " + field + " " + op + " " + expected + " → " + result)
                NodeResult(JSONObject(input.toString()), branch = if (result) "true" else "false")
            }

            "Switch" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val cases = c.optString("cases").lines().map { it.trim() }.filter { it.isNotBlank() }
                val route = cases.firstOrNull { it == actual } ?: "default"
                report("Switch " + field + " → " + route)
                NodeResult(JSONObject(input.toString()), branch = route)
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
                val answer = if (provider == "Gemini") {
                    gemini(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                } else {
                    openAiCompatible(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                }
                report("AI response received (" + answer.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            "Code" -> {
                val operation = c.optString("operation", "uppercase").lowercase(Locale.US)
                val field = c.optString("field", "text")
                val outputField = c.optString("outputField", field)
                val value = lookup(input, field) ?: ""
                val out = JSONObject(input.toString())
                val transformed: Any = when (operation) {
                    "uppercase" -> value.uppercase(Locale.US)
                    "lowercase" -> value.lowercase(Locale.US)
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
                NodeResult(JSONObject(input.toString()).apply { put(key, value) })
            }

            "Notification" -> {
                val title = render(c.optString("title", "NATEN"), input, vars)
                val body = render(c.optString("message", "Workflow finished"), input, vars)
                postNotification(title, body)
                report("Notification sent")
                NodeResult(JSONObject(input.toString()))
            }

            "Open URL" -> {
                val url = render(c.optString("url"), input, vars)
                if (!background && url.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Opened " + url)
                } else {
                    report("Open URL skipped in background")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Share Text" -> {
                val value = render(c.optString("text"), input, vars)
                if (!background) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share with…")
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
                val body = if (file.exists()) file.readText() else ""
                report("Read " + name)
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", body)
                })
            }

            "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                file.writeText(render(c.optString("data"), input, vars))
                report("Wrote " + name)
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
                val text = render(c.optString("message", "Stopped"), input, vars)
                NodeResult(JSONObject(input.toString()), stop = true, success = false, message = text)
            }

            else -> NodeResult(JSONObject(input.toString()))
        }
    }

    private fun executeHttp(
        c: JSONObject,
        input: JSONObject,
        vars: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val method = c.optString("method", "GET").uppercase(Locale.US)
        val urlText = render(c.optString("url"), input, vars)
        require(urlText.isNotBlank()) { "HTTP Request needs a URL." }

        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            doInput = true
        }

        headers(render(c.optString("headers"), input, vars)).forEach {
            conn.setRequestProperty(it.key, it.value)
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        report("HTTP " + method + " " + code + " " + urlText.take(80))

        val out = JSONObject().apply {
            put("statusCode", code)
            put("ok", code < 400)
            put("body", body)
            if (body.trim().startsWith("{")) runCatching { put("json", JSONObject(body)) }
            if (body.trim().startsWith("[")) runCatching { put("json", JSONArray(body)) }
        }

        if (code >= 400) {
            throw IllegalStateException("HTTP " + code + ": " + body.take(180))
        }
        return NodeResult(out)
    }

    private fun fields(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val s = line.trim()
            if (s.isBlank() || s.startsWith("#")) return@forEach
            val idx = s.indexOf('=')
            if (idx > 0) result[s.substring(0, idx).trim()] = s.substring(idx + 1).trim()
        }
        return result
    }

    private fun headers(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return result
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return cleaned.substringAfterLast('/').ifBlank { "file.txt" }
    }

    private fun compare(actual: String, expected: String, op: String): Boolean {
        return when (op.lowercase(Locale.US)) {
            "equals" -> actual == expected
            "not equals" -> actual != expected
            "contains" -> actual.contains(expected, true)
            "starts with" -> actual.startsWith(expected, true)
            "ends with" -> actual.endsWith(expected, true)
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
        val dollar = '$'
        val prefix = "{{" + dollar + "json."
        val clean = path.removePrefix(prefix).removeSuffix("}}").removePrefix("json.")
        if (clean.isBlank() || clean == "json") return root.toString()

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return cur.toString()
    }

    private fun render(input: String, data: JSONObject, vars: Map<String, String>): String {
        var out = input
        val dollar = '$'

        out = out.replace("{{" + dollar + "now}}", now())
        out = out.replace("{{" + dollar + "json}}", data.toString())

        val jsonPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
        jsonPattern.findAll(out).toList().asReversed().forEach { match ->
            val key = match.groupValues.getOrElse(1) { "" }
            out = out.replace(match.value, if (key.isBlank()) data.toString() else lookup(data, key).orEmpty())
        }

        val varsPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
        varsPattern.findAll(out).toList().asReversed().forEach { match ->
            out = out.replace(match.value, vars[match.groupValues[1]].orEmpty())
        }
        return out
    }

    private fun openAiCompatible(
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

    private fun gemini(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        val base = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        }
        val url = if (base.contains("?")) base + "&key=" + key else base + "?key=" + key
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        if (code >= 400) {
            throw IllegalStateException("AI request failed (" + code + "): " + text.take(180))
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
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

        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message.take(140))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}

        val clean = path
            .removePrefix("{{" + dollar + "json.")
            .removeSuffix("}}")
            .removePrefix("json.")

        if (clean.isBlank() || clean == "json") return root

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return cur
    }

    private fun lookup(root: JSONObject, path: String): String? {
        val dollar = '$'
        val prefix = "{{" + dollar + "json."
        val clean = path.removePrefix(prefix).removeSuffix("}}").removePrefix("json.")
        if (clean.isBlank() || clean == "json") return root.toString()

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return cur.toString()
    }

    private fun render(input: String, data: JSONObject, vars: Map<String, String>): String {
        var out = input
        val dollar = '$'

        out = out.replace("{{" + dollar + "now}}", now())
        out = out.replace("{{" + dollar + "json}}", data.toString())

        val jsonPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
        jsonPattern.findAll(out).toList().asReversed().forEach { match ->
            val key = match.groupValues.getOrElse(1) { "" }
            out = out.replace(match.value, if (key.isBlank()) data.toString() else lookup(data, key).orEmpty())
        }

        val varsPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
        varsPattern.findAll(out).toList().asReversed().forEach { match ->
            out = out.replace(match.value, vars[match.groupValues[1]].orEmpty())
        }
        return out
    }

    private fun openAiCompatible(
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

    private fun gemini(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        val base = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        }
        val url = if (base.contains("?")) base + "&key=" + key else base + "?key=" + key
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        if (code >= 400) {
            throw IllegalStateException("AI request failed (" + code + "): " + text.take(180))
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
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

        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message.take(140))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
 + "json." + field + "}}"
                val raw = render(c.optString("value").ifBlank { expression }, input, vars)
                val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("value", raw) }
                NodeResult(JSONObject(input.toString()).apply { put(field, parsed) })
            }

            "JSON Stringify" -> {
                val field = c.optString("field", "json")
                val value = lookupRaw(input, field) ?: input
                NodeResult(JSONObject(input.toString()).apply { put(field + "Text", value.toString()) })
            }

            "Filter" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field).orEmpty()
                val expected = render(c.optString("value"), input, vars)
                val ok = compare(actual, expected, c.optString("operator", "exists"))
                NodeResult(JSONObject(input.toString()), branch = if (ok) "true" else "false")
            }

            "Limit" -> NodeResult(JSONObject(input.toString()).apply {
                put("_limit", c.optInt("count", 10).coerceAtLeast(0))
            })

            "Remove Duplicates" -> NodeResult(JSONObject(input.toString()))

            "Rename Keys" -> {
                val out = JSONObject(input.toString())
                fields(c.optString("mapping")).forEach { pair ->
                    if (out.has(pair.key)) {
                        val value = out.opt(pair.key)
                        out.remove(pair.key)
                        out.put(pair.value, value)
                    }
                }
                NodeResult(out)
            }

            "Sort" -> {
                val field = c.optString("field", "value")
                NodeResult(JSONObject(input.toString()).apply {
                    put("_sortKey", lookup(input, field).orEmpty())
                    put("_sortDescending", c.optBoolean("descending", false))
                })
            }

            "Split Out" -> {
                val field = c.optString("field", "items")
                val raw = lookupRaw(input, field)
                if (raw is JSONArray) {
                    NodeResult(JSONObject(input.toString()).apply {
                        put("_splitCount", raw.length())
                        put("_splitItems", raw)
                    })
                } else {
                    NodeResult(JSONObject(input.toString()))
                }
            }

            "Summarize" -> {
                val op = c.optString("operation", "count").lowercase(Locale.US)
                val field = c.optString("field", "value")
                val number = lookup(input, field)?.toDoubleOrNull()
                val result: Any = when (op) {
                    "sum" -> number ?: 0.0
                    "average" -> number ?: 0.0
                    "count" -> 1
                    else -> lookup(input, field).orEmpty()
                }
                NodeResult(JSONObject().put("summary", result).put("source", input))
            }

            "Date & Time" -> {
                val format = c.optString("format", "yyyy-MM-dd HH:mm:ss")
                val formatted = runCatching {
                    java.text.SimpleDateFormat(format, Locale.getDefault()).format(java.util.Date())
                }.getOrElse { now() }
                NodeResult(JSONObject(input.toString()).apply { put("dateTime", formatted) })
            }

            "Markdown" -> {
                val field = c.optString("field", "text")
                val raw = lookup(input, field).orEmpty()
                val inlineCode = Character.toString(96)
                val clean = raw
                    .replace(Regex("\\*\\*(.*?)\\*\\*")) { it.groupValues[1] }
                    .replace(Regex(inlineCode + "([^" + inlineCode + "]*)" + inlineCode)) { it.groupValues[1] }
                    .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
                NodeResult(JSONObject(input.toString()).apply { put(field + "Plain", clean) })
            }

            "HTML", "XML" -> {
                val field = c.optString("field", if (node.type == "HTML") "html" else "xml")
                val raw = lookup(input, field).orEmpty()
                val clean = raw.replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                NodeResult(JSONObject(input.toString()).apply { put(field + "Text", clean) })
            }

            "Crypto" -> {
                val field = c.optString("field", "text")
                val value = lookup(input, field).orEmpty()
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                NodeResult(JSONObject(input.toString()).apply { put("sha256", digest) })
            }

            "Convert to File", "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                val dataValue = c.optString("data").ifBlank {
                    "{{" + '
                val out = JSONObject(input.toString())
                fields(render(c.optString("fields"), input, vars)).forEach { pair ->
                    out.put(pair.key, pair.value)
                }
                NodeResult(out)
            }

            "IF" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val expected = render(c.optString("value"), input, vars)
                val op = c.optString("operator", "equals")
                val result = compare(actual, expected, op)
                report("IF " + field + " " + op + " " + expected + " → " + result)
                NodeResult(JSONObject(input.toString()), branch = if (result) "true" else "false")
            }

            "Switch" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val cases = c.optString("cases").lines().map { it.trim() }.filter { it.isNotBlank() }
                val route = cases.firstOrNull { it == actual } ?: "default"
                report("Switch " + field + " → " + route)
                NodeResult(JSONObject(input.toString()), branch = route)
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
                val answer = if (provider == "Gemini") {
                    gemini(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                } else {
                    openAiCompatible(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                }
                report("AI response received (" + answer.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            "Code" -> {
                val operation = c.optString("operation", "uppercase").lowercase(Locale.US)
                val field = c.optString("field", "text")
                val outputField = c.optString("outputField", field)
                val value = lookup(input, field) ?: ""
                val out = JSONObject(input.toString())
                val transformed: Any = when (operation) {
                    "uppercase" -> value.uppercase(Locale.US)
                    "lowercase" -> value.lowercase(Locale.US)
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
                NodeResult(JSONObject(input.toString()).apply { put(key, value) })
            }

            "Notification" -> {
                val title = render(c.optString("title", "NATEN"), input, vars)
                val body = render(c.optString("message", "Workflow finished"), input, vars)
                postNotification(title, body)
                report("Notification sent")
                NodeResult(JSONObject(input.toString()))
            }

            "Open URL" -> {
                val url = render(c.optString("url"), input, vars)
                if (!background && url.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Opened " + url)
                } else {
                    report("Open URL skipped in background")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Share Text" -> {
                val value = render(c.optString("text"), input, vars)
                if (!background) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share with…")
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
                val body = if (file.exists()) file.readText() else ""
                report("Read " + name)
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", body)
                })
            }

            "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                file.writeText(render(c.optString("data"), input, vars))
                report("Wrote " + name)
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
                val text = render(c.optString("message", "Stopped"), input, vars)
                NodeResult(JSONObject(input.toString()), stop = true, success = false, message = text)
            }

            else -> NodeResult(JSONObject(input.toString()))
        }
    }

    private fun executeHttp(
        c: JSONObject,
        input: JSONObject,
        vars: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val method = c.optString("method", "GET").uppercase(Locale.US)
        val urlText = render(c.optString("url"), input, vars)
        require(urlText.isNotBlank()) { "HTTP Request needs a URL." }

        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            doInput = true
        }

        headers(render(c.optString("headers"), input, vars)).forEach {
            conn.setRequestProperty(it.key, it.value)
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        report("HTTP " + method + " " + code + " " + urlText.take(80))

        val out = JSONObject().apply {
            put("statusCode", code)
            put("ok", code < 400)
            put("body", body)
            if (body.trim().startsWith("{")) runCatching { put("json", JSONObject(body)) }
            if (body.trim().startsWith("[")) runCatching { put("json", JSONArray(body)) }
        }

        if (code >= 400) {
            throw IllegalStateException("HTTP " + code + ": " + body.take(180))
        }
        return NodeResult(out)
    }

    private fun fields(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val s = line.trim()
            if (s.isBlank() || s.startsWith("#")) return@forEach
            val idx = s.indexOf('=')
            if (idx > 0) result[s.substring(0, idx).trim()] = s.substring(idx + 1).trim()
        }
        return result
    }

    private fun headers(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return result
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return cleaned.substringAfterLast('/').ifBlank { "file.txt" }
    }

    private fun compare(actual: String, expected: String, op: String): Boolean {
        return when (op.lowercase(Locale.US)) {
            "equals" -> actual == expected
            "not equals" -> actual != expected
            "contains" -> actual.contains(expected, true)
            "starts with" -> actual.startsWith(expected, true)
            "ends with" -> actual.endsWith(expected, true)
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
        val dollar = '$'
        val prefix = "{{" + dollar + "json."
        val clean = path.removePrefix(prefix).removeSuffix("}}").removePrefix("json.")
        if (clean.isBlank() || clean == "json") return root.toString()

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return cur.toString()
    }

    private fun render(input: String, data: JSONObject, vars: Map<String, String>): String {
        var out = input
        val dollar = '$'

        out = out.replace("{{" + dollar + "now}}", now())
        out = out.replace("{{" + dollar + "json}}", data.toString())

        val jsonPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
        jsonPattern.findAll(out).toList().asReversed().forEach { match ->
            val key = match.groupValues.getOrElse(1) { "" }
            out = out.replace(match.value, if (key.isBlank()) data.toString() else lookup(data, key).orEmpty())
        }

        val varsPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
        varsPattern.findAll(out).toList().asReversed().forEach { match ->
            out = out.replace(match.value, vars[match.groupValues[1]].orEmpty())
        }
        return out
    }

    private fun openAiCompatible(
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

    private fun gemini(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        val base = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        }
        val url = if (base.contains("?")) base + "&key=" + key else base + "?key=" + key
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        if (code >= 400) {
            throw IllegalStateException("AI request failed (" + code + "): " + text.take(180))
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
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

        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message.take(140))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
 + "json}}"
                }
                file.writeText(render(dataValue, input, vars))
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("filePath", file.absolutePath)
                })
            }

            "Extract From File", "Read File" -> {
                val name = safeFileName(render(c.optString("filename", "input.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                val body = if (file.exists()) file.readText() else ""
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", body)
                })
            }

            "AI Text", "AI Agent" -> {
                val provider = c.optString("provider", "OpenAI-compatible")
                val prompt = render(c.optString("prompt"), input, vars)
                val answer = if (provider == "Gemini") {
                    gemini(c.optString("endpoint"), c.optString("apiKey"), c.optString("model"), prompt, c.optDouble("temperature", 0.4))
                } else {
                    openAiCompatible(c.optString("endpoint"), c.optString("apiKey"), c.optString("model"), prompt, c.optDouble("temperature", 0.4))
                }
                report("AI response received (" + answer.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            "No Operation", "Error Trigger", "Chat Trigger" ->
                NodeResult(JSONObject(input.toString()))

            "Edit Fields" -> {
                val out = JSONObject(input.toString())
                fields(render(c.optString("fields"), input, vars)).forEach { pair ->
                    out.put(pair.key, pair.value)
                }
                NodeResult(out)
            }

            "IF" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val expected = render(c.optString("value"), input, vars)
                val op = c.optString("operator", "equals")
                val result = compare(actual, expected, op)
                report("IF " + field + " " + op + " " + expected + " → " + result)
                NodeResult(JSONObject(input.toString()), branch = if (result) "true" else "false")
            }

            "Switch" -> {
                val field = render(c.optString("field"), input, vars)
                val actual = lookup(input, field) ?: ""
                val cases = c.optString("cases").lines().map { it.trim() }.filter { it.isNotBlank() }
                val route = cases.firstOrNull { it == actual } ?: "default"
                report("Switch " + field + " → " + route)
                NodeResult(JSONObject(input.toString()), branch = route)
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
                val answer = if (provider == "Gemini") {
                    gemini(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                } else {
                    openAiCompatible(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                }
                report("AI response received (" + answer.length + " chars)")
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            "Code" -> {
                val operation = c.optString("operation", "uppercase").lowercase(Locale.US)
                val field = c.optString("field", "text")
                val outputField = c.optString("outputField", field)
                val value = lookup(input, field) ?: ""
                val out = JSONObject(input.toString())
                val transformed: Any = when (operation) {
                    "uppercase" -> value.uppercase(Locale.US)
                    "lowercase" -> value.lowercase(Locale.US)
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
                NodeResult(JSONObject(input.toString()).apply { put(key, value) })
            }

            "Notification" -> {
                val title = render(c.optString("title", "NATEN"), input, vars)
                val body = render(c.optString("message", "Workflow finished"), input, vars)
                postNotification(title, body)
                report("Notification sent")
                NodeResult(JSONObject(input.toString()))
            }

            "Open URL" -> {
                val url = render(c.optString("url"), input, vars)
                if (!background && url.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Opened " + url)
                } else {
                    report("Open URL skipped in background")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Share Text" -> {
                val value = render(c.optString("text"), input, vars)
                if (!background) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, value)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share with…")
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
                val body = if (file.exists()) file.readText() else ""
                report("Read " + name)
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", body)
                })
            }

            "Write File" -> {
                val name = safeFileName(render(c.optString("filename", "output.txt"), input, vars))
                val file = java.io.File(context.filesDir, name)
                file.writeText(render(c.optString("data"), input, vars))
                report("Wrote " + name)
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
                val text = render(c.optString("message", "Stopped"), input, vars)
                NodeResult(JSONObject(input.toString()), stop = true, success = false, message = text)
            }

            else -> NodeResult(JSONObject(input.toString()))
        }
    }

    private fun executeHttp(
        c: JSONObject,
        input: JSONObject,
        vars: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val method = c.optString("method", "GET").uppercase(Locale.US)
        val urlText = render(c.optString("url"), input, vars)
        require(urlText.isNotBlank()) { "HTTP Request needs a URL." }

        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            doInput = true
        }

        headers(render(c.optString("headers"), input, vars)).forEach {
            conn.setRequestProperty(it.key, it.value)
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        report("HTTP " + method + " " + code + " " + urlText.take(80))

        val out = JSONObject().apply {
            put("statusCode", code)
            put("ok", code < 400)
            put("body", body)
            if (body.trim().startsWith("{")) runCatching { put("json", JSONObject(body)) }
            if (body.trim().startsWith("[")) runCatching { put("json", JSONArray(body)) }
        }

        if (code >= 400) {
            throw IllegalStateException("HTTP " + code + ": " + body.take(180))
        }
        return NodeResult(out)
    }

    private fun fields(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val s = line.trim()
            if (s.isBlank() || s.startsWith("#")) return@forEach
            val idx = s.indexOf('=')
            if (idx > 0) result[s.substring(0, idx).trim()] = s.substring(idx + 1).trim()
        }
        return result
    }

    private fun headers(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return result
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return cleaned.substringAfterLast('/').ifBlank { "file.txt" }
    }

    private fun compare(actual: String, expected: String, op: String): Boolean {
        return when (op.lowercase(Locale.US)) {
            "equals" -> actual == expected
            "not equals" -> actual != expected
            "contains" -> actual.contains(expected, true)
            "starts with" -> actual.startsWith(expected, true)
            "ends with" -> actual.endsWith(expected, true)
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
        val dollar = '$'
        val prefix = "{{" + dollar + "json."
        val clean = path.removePrefix(prefix).removeSuffix("}}").removePrefix("json.")
        if (clean.isBlank() || clean == "json") return root.toString()

        var cur: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            cur = when (cur) {
                is JSONObject -> cur.opt(key) ?: return null
                is JSONArray -> cur.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }
        return cur.toString()
    }

    private fun render(input: String, data: JSONObject, vars: Map<String, String>): String {
        var out = input
        val dollar = '$'

        out = out.replace("{{" + dollar + "now}}", now())
        out = out.replace("{{" + dollar + "json}}", data.toString())

        val jsonPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
        jsonPattern.findAll(out).toList().asReversed().forEach { match ->
            val key = match.groupValues.getOrElse(1) { "" }
            out = out.replace(match.value, if (key.isBlank()) data.toString() else lookup(data, key).orEmpty())
        }

        val varsPattern = Regex("\\{\\{" + Regex.escape(dollar.toString()) + "vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
        varsPattern.findAll(out).toList().asReversed().forEach { match ->
            out = out.replace(match.value, vars[match.groupValues[1]].orEmpty())
        }
        return out
    }

    private fun openAiCompatible(
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

    private fun gemini(
        endpoint: String,
        key: String,
        model: String,
        prompt: String,
        temperature: Double
    ): String {
        val base = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        }
        val url = if (base.contains("?")) base + "&key=" + key else base + "?key=" + key
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
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        } ?: ""
        conn.disconnect()

        if (code >= 400) {
            throw IllegalStateException("AI request failed (" + code + "): " + text.take(180))
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
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

        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message.take(140))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
