package com.naten.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.Locale

data class FlowNode(
    var id: Int,
    var type: String,
    var title: String,
    var x: Float,
    var y: Float,
    var config: JSONObject = JSONObject()
)

data class FlowEdge(
    var from: Int,
    var to: Int,
    var branch: String = ""
)

data class WorkflowState(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "My Workflow",
    val nodes: MutableList<FlowNode> = mutableListOf(),
    val edges: MutableList<FlowEdge> = mutableListOf(),
    var active: Boolean = false
)

object WorkflowJson {
    fun toJson(state: WorkflowState): JSONObject = JSONObject().apply {
        put("format", "naten-v3")
        put("id", state.id)
        put("name", state.name)
        put("active", state.active)

        put("nodes", JSONArray().also { nodes ->
            state.nodes.forEach { n ->
                nodes.put(JSONObject().apply {
                    put("id", n.id)
                    put("type", n.type)
                    put("title", n.title)
                    put("x", n.x)
                    put("y", n.y)
                    put("config", JSONObject(n.config.toString()))
                })
            }
        })

        put("edges", JSONArray().also { edges ->
            state.edges.forEach { e ->
                edges.put(JSONObject().apply {
                    put("from", e.from)
                    put("to", e.to)
                    put("branch", e.branch)
                })
            }
        })
    }

    fun fromJson(root: JSONObject): WorkflowState {
        val state = WorkflowState(
            id = root.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = root.optString("name", "My Workflow"),
            active = root.optBoolean("active", false)
        )

        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodes.length()) {
            val o = nodes.optJSONObject(i) ?: continue
            val cfg = o.optJSONObject("config") ?: JSONObject()
            state.nodes.add(
                FlowNode(
                    id = o.optInt("id", i + 1),
                    type = o.optString("type", "Manual Trigger"),
                    title = o.optString("title", o.optString("type", "Node")),
                    x = o.optDouble("x", 24.0).toFloat(),
                    y = o.optDouble("y", 24.0).toFloat(),
                    config = JSONObject(cfg.toString())
                )
            )
        }

        val edges = root.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until edges.length()) {
            val o = edges.optJSONObject(i) ?: continue
            state.edges.add(
                FlowEdge(
                    from = o.optInt("from"),
                    to = o.optInt("to"),
                    branch = o.optString("branch", "")
                )
            )
        }

        return state
    }
}

object WorkflowStore {
    private const val PREFS = "naten"
    private const val CURRENT = "currentWorkflowId"
    private const val WORKFLOWS_DIR = "workflows"

    private fun dir(context: Context): File =
        File(context.filesDir, WORKFLOWS_DIR).apply { mkdirs() }

    private fun file(context: Context, id: String): File =
        File(dir(context), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun loadCurrent(context: Context): WorkflowState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentId = prefs.getString(CURRENT, null)

        if (!currentId.isNullOrBlank()) {
            val current = load(context, currentId)
            if (current != null) return current
        }

        val legacy = prefs.getString("workflow", null)
        if (!legacy.isNullOrBlank()) {
            val restored = runCatching { WorkflowJson.fromJson(JSONObject(legacy)) }.getOrNull()
            if (restored != null) {
                save(context, restored)
                return restored
            }
        }
        return null
    }

    fun load(context: Context, id: String): WorkflowState? =
        runCatching {
            val raw = file(context, id).takeIf { it.exists() }?.readText() ?: return null
            WorkflowJson.fromJson(JSONObject(raw))
        }.getOrNull()

    fun save(context: Context, state: WorkflowState) {
        state.nodes.forEach { it.ensureDefaultConfig() }
        file(context, state.id).writeText(WorkflowJson.toJson(state).toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CURRENT, state.id)
            .putString("workflow", WorkflowJson.toJson(state).toString())
            .apply()
    }

    fun setCurrent(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CURRENT, id)
            .apply()
    }

    fun list(context: Context): List<WorkflowState> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { WorkflowJson.fromJson(JSONObject(f.readText())) }.getOrNull()
            }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()

    fun newWorkflow(context: Context, name: String = "My Workflow"): WorkflowState {
        val state = WorkflowState(name = name)
        save(context, state)
        return state
    }

    fun duplicate(context: Context, source: WorkflowState): WorkflowState {
        val copy = WorkflowState(
            name = source.name + " Copy",
            active = false
        )
        source.nodes.forEach { n ->
            copy.nodes.add(
                FlowNode(n.id, n.type, n.title, n.x, n.y, JSONObject(n.config.toString()))
            )
        }
        source.edges.forEach { e ->
            copy.edges.add(FlowEdge(e.from, e.to, e.branch))
        }
        save(context, copy)
        return copy
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentId = prefs.getString(CURRENT, null)
        if (currentId == id) {
            val replacement = list(context).firstOrNull()
            prefs.edit()
                .putString(CURRENT, replacement?.id)
                .apply()
        }
    }

    fun history(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("history", "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun appendHistory(context: Context, entry: JSONObject) {
        val arr = history(context)
        arr.put(entry)

        val limited = JSONArray()
        val start = maxOf(0, arr.length() - 100)
        for (i in start until arr.length()) {
            limited.put(arr.get(i))
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("history", limited.toString())
            .apply()
    }
}

fun FlowNode.ensureDefaultConfig() {
    if (!config.has("retries")) config.put("retries", 0)
    if (!config.has("timeoutSeconds")) config.put("timeoutSeconds", 60)
    if (!config.has("continueOnFail")) config.put("continueOnFail", false)

    when (type) {
        "Schedule Trigger" -> {
            if (!config.has("mode")) config.put("mode", "interval")
            if (!config.has("interval")) config.put("interval", 60)
            if (!config.has("unit")) config.put("unit", "minutes")
            if (!config.has("hour")) config.put("hour", 9)
            if (!config.has("minute")) config.put("minute", 0)
            if (!config.has("days")) config.put("days", "MON")
            if (!config.has("timezone")) config.put("timezone", java.util.TimeZone.getDefault().id)
            if (!config.has("runAtEpochMs")) config.put("runAtEpochMs", 0L)
        }

        "HTTP Request", "Generic API" -> {
            if (!config.has("method")) config.put("method", "GET")
            if (!config.has("url")) config.put(
                "url",
                NodeCatalog.find(type)?.defaultUrl.orEmpty().ifBlank { "https://example.com" }
            )
            if (!config.has("headers")) config.put("headers", "")
            if (!config.has("queryParams")) config.put("queryParams", "")
            if (!config.has("body")) config.put("body", "")
            if (!config.has("credentialName")) config.put("credentialName", "")
            if (!config.has("credentialHeader")) config.put("credentialHeader", "Authorization")
            if (!config.has("credentialPrefix")) config.put("credentialPrefix", "Bearer ")
            if (!config.has("maxResponseBytes")) config.put("maxResponseBytes", 2_000_000)
        }

        "YouTube" -> {
            if (!config.has("operation")) config.put("operation", "Search")
            if (!config.has("query")) config.put("query", "")
            if (!config.has("channelId")) config.put("channelId", "")
            if (!config.has("videoId")) config.put("videoId", "")
            if (!config.has("maxResults")) config.put("maxResults", 10)
            if (!config.has("order")) config.put("order", "relevance")
            if (!config.has("credentialName")) config.put("credentialName", "")
            if (!config.has("apiKey")) config.put("apiKey", "")
            if (!config.has("accessTokenCredential")) config.put("accessTokenCredential", "")
            if (!config.has("accessToken")) config.put("accessToken", "")
        }

        "GraphQL" -> {
            if (!config.has("url")) config.put("url", "https://example.com/graphql")
            if (!config.has("query")) config.put("query", "query { hello }")
            if (!config.has("variables")) config.put("variables", "{}")
            if (!config.has("headers")) config.put("headers", "")
        }

        "Webhook Trigger", "Chat Trigger" -> {
            if (!config.has("method")) config.put("method", "POST")
            if (!config.has("path")) config.put("path", "hook/" + id.toString().take(8))
        }

        "Edit Fields" -> if (!config.has("fields")) config.put("fields", "message=Hello")
        "IF", "Filter" -> {
            if (!config.has("field")) config.put("field", "value")
            if (!config.has("operator")) config.put("operator", "exists")
            if (!config.has("value")) config.put("value", "")
        }

        "Switch" -> {
            if (!config.has("field")) config.put("field", "value")
            if (!config.has("cases")) config.put("cases", "one
two
three")
        }

        "Wait" -> if (!config.has("seconds")) config.put("seconds", 2)
        "Limit" -> if (!config.has("count")) config.put("count", 10)
        "Remove Duplicates" -> if (!config.has("field")) config.put("field", "id")
        "Rename Keys" -> if (!config.has("mapping")) config.put("mapping", "old=new")

        "Sort" -> {
            if (!config.has("field")) config.put("field", "value")
            if (!config.has("descending")) config.put("descending", false)
        }

        "Split Out" -> if (!config.has("field")) config.put("field", "items")

        "Summarize" -> {
            if (!config.has("field")) config.put("field", "value")
            if (!config.has("operation")) config.put("operation", "count")
        }

        "JSON Parse" -> if (!config.has("field")) config.put("field", "text")
        "JSON Stringify" -> if (!config.has("field")) config.put("field", "json")

        "Date & Time" -> if (!config.has("format")) {
            config.put("format", "yyyy-MM-dd HH:mm:ss")
        }

        "Code" -> {
            if (!config.has("operation")) config.put("operation", "uppercase")
            if (!config.has("field")) config.put("field", "text")
            if (!config.has("outputField")) config.put("outputField", "text")
            if (!config.has("value")) config.put("value", "Hello")
        }

        "Markdown" -> if (!config.has("field")) config.put("field", "text")
        "HTML" -> if (!config.has("field")) config.put("field", "html")
        "XML" -> if (!config.has("field")) config.put("field", "xml")
        "Crypto" -> if (!config.has("field")) config.put("field", "text")

        "Read File", "Extract From File" -> if (!config.has("filename")) {
            config.put("filename", "input.txt")
        }

        "Write File", "Convert to File" -> {
            if (!config.has("filename")) config.put("filename", "output.txt")
            if (!config.has("data")) config.put("data", "{{" + '$' + "json}}")
        }

        "Set Variable" -> {
            if (!config.has("key")) config.put("key", "name")
            if (!config.has("value")) config.put("value", "NATEN")
        }

        "Respond to Webhook" -> {
            if (!config.has("body")) config.put("body", "{{" + '$' + "json}}")
            if (!config.has("statusCode")) config.put("statusCode", 200)
        }

        "Execute Sub-workflow" -> if (!config.has("workflowId")) {
            config.put("workflowId", "")
        }

        "Log" -> if (!config.has("message")) {
            config.put("message", "{{" + '$' + "json}}")
        }

        "Notification" -> {
            if (!config.has("title")) config.put("title", "NATEN")
            if (!config.has("message")) config.put("message", "Workflow finished")
        }

        "Open URL" -> if (!config.has("url")) config.put("url", "https://n8n.io")
        "Share Text" -> if (!config.has("text")) config.put("text", "Hello from NATEN")

        "AI Text", "AI Agent" -> {
            if (!config.has("provider")) config.put("provider", "OpenAI")
            if (!config.has("endpoint")) {
                config.put("endpoint", "https://api.openai.com/v1/chat/completions")
            }
            if (!config.has("apiKey")) config.put("apiKey", "")
            if (!config.has("model")) config.put("model", "gpt-4o-mini")
            if (!config.has("systemPrompt")) config.put("systemPrompt", "")
            if (!config.has("prompt")) {
                config.put("prompt", "Work with this data: {{" + '$' + "json}}")
            }
            if (!config.has("temperature")) config.put("temperature", 0.4)
            if (!config.has("credentialName")) config.put("credentialName", "")
            if (!config.has("responseFormat")) config.put("responseFormat", "text")
        }

        "Loop Over Items" -> {
            if (!config.has("batchSize")) config.put("batchSize", 1)
            if (!config.has("continueOnEmpty")) config.put("continueOnEmpty", false)
        }

        in NodeCatalog.apiBackedTypes -> {
            if (!config.has("method")) config.put("method", "GET")
            if (!config.has("url")) config.put("url", NodeCatalog.find(type)?.defaultUrl.orEmpty())
            if (!config.has("headers")) config.put("headers", "")
            if (!config.has("queryParams")) config.put("queryParams", "")
            if (!config.has("body")) config.put("body", "")
            if (!config.has("credentialName")) config.put("credentialName", "")
            if (!config.has("credentialHeader")) config.put("credentialHeader", "Authorization")
            if (!config.has("credentialPrefix")) config.put("credentialPrefix", "Bearer ")
            if (!config.has("maxResponseBytes")) config.put("maxResponseBytes", 2_000_000)
        }

        else -> Unit
    }
}
