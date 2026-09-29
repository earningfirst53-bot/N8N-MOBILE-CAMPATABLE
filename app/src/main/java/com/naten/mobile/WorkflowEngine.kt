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
            val variables = mutableMapOf<String, String>()
            var success = true
            var message = "Completed"

            fun report(text: String) {
                listener?.onLog(text)
            }

            try {
                require(state.nodes.isNotEmpty()) { "Workflow has no nodes." }

                val byId = state.nodes.associateBy { it.id }
                val outgoing = state.edges.groupBy { it.from }

                val starts = when {
                    input != null -> state.nodes.filter {
                        it.type == "Webhook Trigger" || it.type == "Chat Trigger"
                    }
                    background -> state.nodes.filter {
                        it.type == "Schedule Trigger"
                    }
                    else -> state.nodes.filter {
                        it.type == "Manual Trigger"
                    }
                }.ifEmpty {
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

                    val maxAttempts = node.config.optInt("retries", 0).coerceIn(0, 5)
                    var result: NodeResult? = null
                    var lastError: Throwable? = null

                    for (attempt in 0..maxAttempts) {
                        try {
                            result = executeNode(node, data, variables, background, ::report)
                            lastError = null
                            break
                        } catch (t: Throwable) {
                            lastError = t
                            if (attempt < maxAttempts) {
                                report("Retry " + (attempt + 1) + "/" + maxAttempts)
                            }
                        }
                    }

                    if (result == null) {
                        if (node.config.optBoolean("continueOnFail", false)) {
                            report("Continuing after error")
                            data = JSONObject(data.toString()).apply {
                                put("error", lastError?.message ?: "Node failed")
                                put("errorNode", node.title)
                            }
                        } else {
                            throw lastError ?: IllegalStateException("Node failed")
                        }
                    } else {
                        data = result.data

                        if (result.stop) {
                            if (!result.success) {
                                throw IllegalStateException(result.message)
                            }
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
                            else next.filter {
                                it.branch.equals("default", ignoreCase = true)
                            }
                        }

                        selected.forEach { edge ->
                            if (byId.containsKey(edge.to)) queue.addLast(edge.to)
                        }
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
                WorkflowStore.appendHistory(
                    context,
                    JSONObject().apply {
                        put("runId", runId)
                        put("workflow", state.name)
                        put("workflowId", state.id)
                        put("started", started)
                        put("durationMs", System.currentTimeMillis() - started)
                        put("success", success)
                        put("message", message)
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
        variables: MutableMap<String, String>,
        background: Boolean,
        report: (String) -> Unit
    ): NodeResult {
        val c = node.config

        return when (node.type) {
            "Manual Trigger", "Schedule Trigger", "Webhook Trigger",
            "Chat Trigger", "Error Trigger", "No Operation", "Merge" ->
                NodeResult(JSONObject(input.toString()))

            "HTTP Request", "Generic API", "Email", "Telegram", "Slack",
            "Google Sheets", "Gmail" ->
                executeHttp(c, input, variables, report)

            "GraphQL" ->
                executeGraphQl(c, input, variables, report)

            "Edit Fields" -> {
                val out = JSONObject(input.toString())
                parsePairs(render(c.optString("fields"), input, variables))
                    .forEach { pair -> out.put(pair.first, pair.second) }
                NodeResult(out)
            }

            "Filter", "IF" -> {
                val field = render(c.optString("field"), input, variables)
                val actual = lookup(input, field).orEmpty()
                val expected = render(c.optString("value"), input, variables)
                val ok = compare(actual, expected, c.optString("operator", "exists"))
                report(
                    node.type + ": " + field + " " +
                        c.optString("operator", "exists") + " → " + ok
                )
                NodeResult(JSONObject(input.toString()), branch = if (ok) "true" else "false")
            }

            "Switch" -> {
                val field = render(c.optString("field"), input, variables)
                val actual = lookup(input, field).orEmpty()
                val route = c.optString("cases")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .firstOrNull { it == actual }
                    ?: "default"
                report("Switch → " + route)
                NodeResult(JSONObject(input.toString()), branch = route)
            }

            "Wait" -> {
                val seconds = c.optInt("seconds", 2).coerceIn(0, 86_400)
                report("Waiting " + seconds + "s")
                if (seconds > 0) Thread.sleep(seconds * 1000L)
                NodeResult(JSONObject(input.toString()))
            }

            "Limit" -> {
                val out = JSONObject(input.toString())
                val items = input.optJSONArray("_items")
                if (items != null) {
                    val count = c.optInt("count", 10).coerceAtLeast(0)
                    val limited = JSONArray()
                    for (i in 0 until minOf(count, items.length())) {
                        limited.put(items.get(i))
                    }
                    out.put("_items", limited)
                }
                NodeResult(out)
            }

            "Remove Duplicates" -> {
                val out = JSONObject(input.toString())
                val items = input.optJSONArray("_items")
                if (items != null) {
                    val field = c.optString("field", "id")
                    val seen = mutableSetOf<String>()
                    val unique = JSONArray()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val key = lookup(item, field).orEmpty()
                        if (seen.add(key)) unique.put(item)
                    }
                    out.put("_items", unique)
                }
                NodeResult(out)
            }

            "Rename Keys" -> {
                val out = JSONObject(input.toString())
                parsePairs(c.optString("mapping")).forEach { pair ->
                    if (out.has(pair.first)) {
                        val value = out.opt(pair.first)
                        out.remove(pair.first)
                        out.put(pair.second, value)
                    }
                }
                NodeResult(out)
            }

            "Sort" -> {
                val out = JSONObject(input.toString())
                val items = input.optJSONArray("_items")
                if (items != null) {
                    val field = c.optString("field", "value")
                    val sorted = (0 until items.length())
                        .mapNotNull { items.optJSONObject(it) }
                        .sortedWith(
                            compareBy<JSONObject> {
                                lookup(it, field).orEmpty()
                            }.let { if (c.optBoolean("descending")) it.reversed() else it }
                        )
                    val array = JSONArray()
                    sorted.forEach { array.put(it) }
                    out.put("_items", array)
                }
                NodeResult(out)
            }

            "Split Out" -> {
                val out = JSONObject(input.toString())
                val field = c.optString("field", "items")
                val raw = lookupRaw(input, field)
                if (raw is JSONArray) {
                    out.put("_items", raw)
                    out.put("_splitCount", raw.length())
                }
                NodeResult(out)
            }

            "Loop Over Items" -> {
                val out = JSONObject(input.toString())
                val items = input.optJSONArray("_items")
                if (items != null) {
                    out.put("_loopBatchSize", c.optInt("batchSize", 1).coerceAtLeast(1))
                    out.put("_loopCount", items.length())
                }
                NodeResult(out)
            }

            "Summarize" -> {
                val op = c.optString("operation", "count").lowercase(Locale.US)
                val field = c.optString("field", "value")
                val items = input.optJSONArray("_items")
                val result = if (items != null) {
                    when (op) {
                        "sum" -> {
                            var total = 0.0
                            for (i in 0 until items.length()) {
                                total += lookup(items.optJSONObject(i) ?: JSONObject(), field)
                                    ?.toDoubleOrNull() ?: 0.0
                            }
                            total
                        }
                        "average" -> {
                            var total = 0.0
                            var count = 0
                            for (i in 0 until items.length()) {
                                val n = lookup(items.optJSONObject(i) ?: JSONObject(), field)
                                    ?.toDoubleOrNull()
                                if (n != null) {
                                    total += n
                                    count++
                                }
                            }
                            if (count == 0) 0.0 else total / count
                        }
                        else -> items.length()
                    }
                } else {
                    when (op) {
                        "sum", "average" -> lookup(input, field)?.toDoubleOrNull() ?: 0.0
                        else -> 1
                    }
                }
                NodeResult(JSONObject().put("summary", result).put("source", input))
            }

            "JSON Parse" -> {
                val field = c.optString("field", "text")
                val value = renderExpressionField(c, field, input, variables)
                val parsed = runCatching { JSONObject(value) }
                    .getOrElse { JSONObject().put("value", value) }
                NodeResult(JSONObject(input.toString()).apply { put(field, parsed) })
            }

            "JSON Stringify" -> {
                val field = c.optString("field", "json")
                val raw = lookupRaw(input, field) ?: input
                NodeResult(JSONObject(input.toString()).apply {
                    put(field + "Text", raw.toString())
                })
            }

            "Date & Time" -> {
                val format = c.optString("format", "yyyy-MM-dd HH:mm:ss")
                val formatted = runCatching {
                    SimpleDateFormat(format, Locale.getDefault()).format(Date())
                }.getOrElse { now() }
                NodeResult(JSONObject(input.toString()).apply {
                    put("dateTime", formatted)
                })
            }

            "Code" -> {
                val operation = c.optString("operation", "uppercase").lowercase(Locale.US)
                val field = c.optString("field", "text")
                val output = c.optString("outputField", field)
                val value = lookup(input, field).orEmpty()
                val transformed: Any = when (operation) {
                    "uppercase" -> value.uppercase(Locale.US)
                    "lowercase" -> value.lowercase(Locale.US)
                    "length" -> value.length
                    "reverse" -> value.reversed()
                    "trim" -> value.trim()
                    "set value" -> render(c.optString("value"), input, variables)
                    else -> value
                }
                NodeResult(JSONObject(input.toString()).apply { put(output, transformed) })
            }

            "Markdown" -> {
                val field = c.optString("field", "text")
                val raw = lookup(input, field).orEmpty()
                val tick = Character.toString(96)
                val clean = raw
                    .replace(Regex("\\*\\*(.*?)\\*\\*")) { it.groupValues[1] }
                    .replace(Regex(tick + "([^" + tick + "]*)" + tick)) { it.groupValues[1] }
                    .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
                NodeResult(JSONObject(input.toString()).apply { put(field + "Plain", clean) })
            }

            "HTML", "XML" -> {
                val field = c.optString("field", if (node.type == "HTML") "html" else "xml")
                val clean = lookup(input, field).orEmpty()
                    .replace(Regex("<[^>]*>"), " ")
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

            "Read File", "Extract From File" -> {
                val name = safeFileName(
                    render(c.optString("filename", "input.txt"), input, variables)
                )
                val file = java.io.File(context.filesDir, name)
                val text = if (file.exists()) file.readText() else ""
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("fileText", text)
                })
            }

            "Write File", "Convert to File" -> {
                val name = safeFileName(
                    render(c.optString("filename", "output.txt"), input, variables)
                )
                val expression = c.optString("data").ifBlank {
                    "{{\\$json}}"
                }
                val file = java.io.File(context.filesDir, name)
                file.writeText(render(expression, input, variables))
                NodeResult(JSONObject(input.toString()).apply {
                    put("fileName", name)
                    put("filePath", file.absolutePath)
                })
            }

            "Set Variable" -> {
                val key = c.optString("key", "value")
                val value = render(c.optString("value"), input, variables)
                variables[key] = value
                NodeResult(JSONObject(input.toString()).apply { put(key, value) })
            }

            "Log" -> {
                report(render(c.optString("message", "Log"), input, variables))
                NodeResult(JSONObject(input.toString()))
            }

            "Notification" -> {
                val title = render(c.optString("title", "NATEN"), input, variables)
                val body = render(c.optString("message", "Workflow finished"), input, variables)
                postNotification(title, body)
                report("Notification sent")
                NodeResult(JSONObject(input.toString()))
            }

            "Open URL" -> {
                val url = render(c.optString("url"), input, variables)
                if (!background && url.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Opened " + url)
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Share Text" -> {
                val text = render(c.optString("text"), input, variables)
                if (!background) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        this.type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share with…")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    report("Share sheet opened")
                }
                NodeResult(JSONObject(input.toString()))
            }

            "Stop / Error" -> {
                val body = render(c.optString("message", "Stopped"), input, variables)
                NodeResult(
                    JSONObject(input.toString()),
                    stop = true,
                    success = false,
                    message = body
                )
            }

            "AI Text", "AI Agent" -> {
                val provider = c.optString("provider", "OpenAI-compatible")
                val prompt = render(c.optString("prompt"), input, variables)
                val answer = if (provider == "Gemini") {
                    callGemini(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                } else {
                    callOpenAiCompatible(
                        c.optString("endpoint"),
                        c.optString("apiKey"),
                        c.optString("model"),
                        prompt,
                        c.optDouble("temperature", 0.4)
                    )
                }
                NodeResult(JSONObject(input.toString()).apply {
                    put("text", answer)
                    put("ai", answer)
                })
            }

            else -> NodeResult(JSONObject(input.toString()))
        }
    }

    private fun renderExpressionField(
        c: JSONObject,
        field: String,
        input: JSONObject,
        variables: Map<String, String>
    ): String {
        val value = c.optString("value")
        if (value.isNotBlank()) return render(value, input, variables)
        return lookup(input, field).orEmpty()
    }

    private fun executeHttp(
        c: JSONObject,
        input: JSONObject,
        variables: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val method = c.optString("method", "GET").uppercase(Locale.US)
        val urlText = render(c.optString("url"), input, variables)
        require(urlText.isNotBlank()) { "HTTP node needs a URL." }

        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            doInput = true
        }

        parseHeaders(render(c.optString("headers"), input, variables))
            .forEach { pair ->
                connection.setRequestProperty(pair.key, pair.value)
            }

        if (method != "GET" && method != "HEAD") {
            connection.doOutput = true
            val body = render(c.optString("body"), input, variables)
            if (body.isNotBlank()) {
                if (connection.getRequestProperty("Content-Type").isNullOrBlank()) {
                    connection.setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
            }
        }

        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } ?: ""
        connection.disconnect()

        report("HTTP " + method + " " + code + " " + urlText.take(80))

        val out = JSONObject().apply {
            put("statusCode", code)
            put("ok", code < 400)
            put("body", body)
            if (body.trim().startsWith("{")) {
                runCatching { put("json", JSONObject(body)) }
            }
            if (body.trim().startsWith("[")) {
                runCatching { put("json", JSONArray(body)) }
            }
        }

        if (code >= 400) {
            throw IllegalStateException("HTTP " + code + ": " + body.take(180))
        }

        return NodeResult(out)
    }

    private fun executeGraphQl(
        c: JSONObject,
        input: JSONObject,
        variables: Map<String, String>,
        report: (String) -> Unit
    ): NodeResult {
        val url = render(c.optString("url"), input, variables)
        require(url.isNotBlank()) { "GraphQL node needs a URL." }

        val variablesText = render(c.optString("variables", "{}"), input, variables)
        val body = JSONObject().apply {
            put("query", render(c.optString("query"), input, variables))
            put(
                "variables",
                runCatching { JSONObject(variablesText) }.getOrElse { JSONObject() }
            )
        }

        val response = postJsonWithHeaders(
            url,
            "",
            body,
            parseHeaders(render(c.optString("headers"), input, variables))
        )
        report("GraphQL request completed")
        return NodeResult(response)
    }

    private fun parsePairs(text: String): List<Pair<String, String>> {
        return text.lines().mapNotNull { line ->
            val clean = line.trim()
            val index = clean.indexOf('=')
            if (index <= 0) null
            else clean.substring(0, index).trim() to clean.substring(index + 1).trim()
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        return text.lines().mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null
            else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }.toMap()
    }

    private fun safeFileName(value: String): String {
        val clean = value.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return clean.substringAfterLast('/').ifBlank { "file.txt" }
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

    private fun lookupRaw(root: JSONObject, path: String): Any? {
        val clean = path
            .removePrefix("{{\\$json.")
            .removeSuffix("}}")
            .removePrefix("json.")

        if (clean.isBlank() || clean == "json") return root

        var current: Any = root
        for (key in clean.split('.').filter { it.isNotBlank() }) {
            current = when (current) {
                is JSONObject -> current.opt(key) ?: return null
                is JSONArray -> current.opt(key.toIntOrNull() ?: return null)
                else -> return null
            }
        }

        return current
    }

    private fun lookup(root: JSONObject, path: String): String? {
        return lookupRaw(root, path)?.let {
            if (it == JSONObject.NULL) null else it.toString()
        }
    }

    private fun render(
        source: String,
        data: JSONObject,
        variables: Map<String, String>
    ): String {
        var result = source
        result = result.replace("{{\\$now}}", now())
        result = result.replace("{{\\$json}}", data.toString())

        val jsonPattern = Regex("\\{\\{\\$json(?:\\.([A-Za-z0-9_\\-.]+))?\\}\\}")
        jsonPattern.findAll(result).toList().asReversed().forEach { match ->
            val key = match.groupValues.getOrElse(1) { "" }
            result = result.replace(
                match.value,
                if (key.isBlank()) data.toString() else lookup(data, key).orEmpty()
            )
        }

        val varsPattern = Regex("\\{\\{\\$vars\\.([A-Za-z0-9_\\-.]+)\\}\\}")
        varsPattern.findAll(result).toList().asReversed().forEach { match ->
            result = result.replace(
                match.value,
                variables[match.groupValues[1]].orEmpty()
            )
        }

        return result
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
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )
            )
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
        val base = endpoint.ifBlank {
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                model + ":generateContent"
        }

        val url = if (base.contains("?")) {
            base + "&key=" + key
        } else {
            base + "?key=" + key
        }

        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().put("temperature", temperature)
            )
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

    private fun postJson(
        endpoint: String,
        key: String,
        body: JSONObject
    ): JSONObject {
        return postJsonWithHeaders(endpoint, key, body, emptyMap())
    }

    private fun postJsonWithHeaders(
        endpoint: String,
        key: String,
        body: JSONObject,
        extraHeaders: Map<String, String>
    ): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer " + key)
            }
            extraHeaders.forEach { pair ->
                setRequestProperty(pair.key, pair.value)
            }
        }

        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } ?: ""

        connection.disconnect()

        if (code >= 400) {
            throw IllegalStateException(
                "Request failed (" + code + "): " + text.take(180)
            )
        }

        return runCatching { JSONObject(text) }
            .getOrElse { JSONObject().put("body", text) }
    }

    private fun postNotification(title: String, message: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "naten_runs"

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "NATEN workflow results",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        manager.notify(
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
