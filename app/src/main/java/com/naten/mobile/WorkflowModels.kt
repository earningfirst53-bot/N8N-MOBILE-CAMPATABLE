package com.naten.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
        put("format", "naten-v2")
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
                    put("config", n.config)
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
        val currentId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CURRENT, null)

        if (!currentId.isNullOrBlank()) {
            val current = load(context, currentId)
            if (current != null) return current
        }

        // Migrate the original single-workflow format.
        val legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("workflow", null)
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
            .edit().putString(CURRENT, id).apply()
    }

    fun list(context: Context): List<WorkflowState> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { WorkflowJson.fromJson(JSONObject(f.readText())) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
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
        source.edges.forEach { e -> copy.edges.add(FlowEdge(e.from, e.to, e.branch)) }
        save(context, copy)
        return copy
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()

        val currentId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CURRENT, null)
        if (currentId == id) {
            val replacement = list(context).firstOrNull()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
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
        for (i in start until arr.length()) limited.put(arr.get(i))

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("history", limited.toString()).apply()
    }
}

fun FlowNode.ensureDefaultConfig() {
    if (config.length() > 0) return

    when (type) {
        "Schedule Trigger" -> {
            config.put("interval", 60)
            config.put("unit", "minutes")
        }
        "HTTP Request", "Generic API" -> {
            config.put("method", "GET")
            config.put("url", "https://example.com")
            config.put("headers", "")
            config.put("body", "")
        }
        "GraphQL" -> {
            config.put("url", "https://example.com/graphql")
            config.put("query", "query { hello }")
            config.put("variables", "{}")
            config.put("headers", "")
        }
        "Webhook Trigger", "Chat Trigger" -> {
            val suffix = id.take(8)
            config.put("method", "POST")
            config.put("path", "hook/" + suffix)
        }
        "Edit Fields" -> config.put("fields", "message=Hello")
        "Filter" -> {
            config.put("field", "value")
            config.put("operator", "exists")
            config.put("value", "")
        }
        "IF" -> {
            config.put("field", "value")
            config.put("operator", "equals")
            config.put("value", "")
        }
        "Switch" -> {
            config.put("field", "value")
            config.put("cases", "one\ntwo\nthree")
        }
        "Wait" -> config.put("seconds", 2)
        "Limit" -> config.put("count", 10)
        "Remove Duplicates" -> config.put("field", "id")
        "Rename Keys" -> config.put("mapping", "old=new")
        "Sort" -> {
            config.put("field", "value")
            config.put("descending", false)
        }
        "Split Out" -> config.put("field", "items")
        "Summarize" -> {
            config.put("field", "value")
            config.put("operation", "count")
        }
        "JSON Parse" -> config.put("field", "text")
        "JSON Stringify" -> config.put("field", "json")
        "Date & Time" -> config.put("format", "yyyy-MM-dd HH:mm:ss")
        "Code" -> {
            config.put("operation", "uppercase")
            config.put("field", "text")
            config.put("outputField", "text")
            config.put("value", "Hello")
        }
        "Markdown" -> config.put("field", "text")
        "HTML" -> config.put("field", "html")
        "XML" -> config.put("field", "xml")
        "Crypto" -> config.put("field", "text")
        "Read File", "Extract From File" -> config.put("filename", "input.txt")
        "Write File", "Convert to File" -> {
            config.put("filename", "output.txt")
            config.put("data", "{{\$json}}")
        }
        "Set Variable" -> {
            config.put("key", "name")
            config.put("value", "NATEN")
        }
        "Log" -> config.put("message", "{{\$json}}")
        "Notification" -> {
            config.put("title", "NATEN")
            config.put("message", "Workflow finished")
        }
        "Open URL" -> config.put("url", "https://n8n.io")
        "Share Text" -> config.put("text", "Hello from NATEN")
        "AI Text", "AI Agent" -> {
            config.put("provider", "OpenAI-compatible")
            config.put("endpoint", "https://api.openai.com/v1/chat/completions")
            config.put("apiKey", "")
            config.put("model", "gpt-4o-mini")
            config.put("prompt", "Work with this data: {{\$json}}")
            config.put("temperature", 0.4)
        }
        "Loop Over Items" -> config.put("batchSize", 1)
        "No Operation", "Merge", "Manual Trigger", "Webhook Trigger",
        "Error Trigger", "Chat Trigger", "Email", "Telegram", "Slack",
        "Google Sheets", "Gmail", "Stop / Error" -> Unit
    }
}
