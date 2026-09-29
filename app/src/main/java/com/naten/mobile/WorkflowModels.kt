package com.naten.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    var name: String = "My Workflow",
    val nodes: MutableList<FlowNode> = mutableListOf(),
    val edges: MutableList<FlowEdge> = mutableListOf(),
    var active: Boolean = false
)

object WorkflowJson {
    fun toJson(state: WorkflowState): JSONObject {
        val root = JSONObject()
        root.put("format", "naten-v1")
        root.put("name", state.name)
        root.put("active", state.active)

        val nodes = JSONArray()
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
        root.put("nodes", nodes)

        val edges = JSONArray()
        state.edges.forEach { e ->
            edges.put(JSONObject().apply {
                put("from", e.from)
                put("to", e.to)
                put("branch", e.branch)
            })
        }
        root.put("edges", edges)
        return root
    }

    fun fromJson(root: JSONObject): WorkflowState {
        val state = WorkflowState(
            name = root.optString("name", "My Workflow"),
            active = root.optBoolean("active", false)
        )
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodes.length()) {
            val o = nodes.getJSONObject(i)
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
            val o = edges.getJSONObject(i)
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

    fun load(context: Context): WorkflowState? {
        val raw = context.getSharedPreferences("naten", Context.MODE_PRIVATE)
            .getString("workflow", null) ?: return null
        return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun save(context: Context, state: WorkflowState) {
        context.getSharedPreferences("naten", Context.MODE_PRIVATE)
            .edit()
            .putString("workflow", toJson(state).toString())
            .apply()
    }

    fun history(context: Context): JSONArray {
        val raw = context.getSharedPreferences("naten", Context.MODE_PRIVATE)
            .getString("history", "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    fun appendHistory(context: Context, entry: JSONObject) {
        val arr = history(context)
        arr.put(entry)
        while (arr.length() > 50) {
            val keep = JSONArray()
            for (i in 1 until arr.length()) keep.put(arr.get(i))
            while (keep.length() < arr.length() - 1) {
                keep.put(JSONObject())
            }
            // rebuild simply below
            break
        }
        val limited = JSONArray()
        val start = maxOf(0, arr.length() - 50)
        for (i in start until arr.length()) limited.put(arr.get(i))
        context.getSharedPreferences("naten", Context.MODE_PRIVATE)
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
        "HTTP Request" -> {
            config.put("method", "GET")
            config.put("url", "https://example.com")
            config.put("headers", "")
            config.put("body", "")
        }
        "Edit Fields" -> config.put("fields", "message=Hello")
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
        "AI Text" -> {
            config.put("provider", "OpenAI-compatible")
            config.put("endpoint", "https://api.openai.com/v1/chat/completions")
            config.put("apiKey", "")
            config.put("model", "gpt-4o-mini")
            config.put("prompt", "Summarize this: {{$json}}")
            config.put("temperature", 0.4)
        }
        "Notification" -> {
            config.put("title", "NATEN")
            config.put("message", "Workflow finished")
        }
        "Open URL" -> config.put("url", "https://n8n.io")
        "Share Text" -> config.put("text", "Hello from NATEN")
        "Read File" -> config.put("filename", "input.txt")
        "Write File" -> {
            config.put("filename", "output.txt")
            config.put("data", "{{$json}}")
        }
        "Set Variable" -> {
            config.put("key", "name")
            config.put("value", "NATEN")
        }
        "Log" -> config.put("message", "{{$json}}")
        "Stop / Error" -> config.put("message", "Stopped by workflow")
        "Webhook Trigger", "Manual Trigger", "Merge" -> Unit
    }
}
