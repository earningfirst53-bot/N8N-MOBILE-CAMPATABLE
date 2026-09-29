package com.naten.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class FlowNode(var id: Int, var type: String, var title: String, var x: Float, var y: Float, var config: JSONObject = JSONObject())
data class FlowEdge(var from: Int, var to: Int, var branch: String = "")
data class WorkflowState(var id: String = UUID.randomUUID().toString(), var name: String = "My Workflow", val nodes: MutableList<FlowNode> = mutableListOf(), val edges: MutableList<FlowEdge> = mutableListOf(), var active: Boolean = false)

object WorkflowJson {
    fun toJson(state: WorkflowState): JSONObject = JSONObject().apply {
        put("format", "naten-v2"); put("id", state.id); put("name", state.name); put("active", state.active)
        put("nodes", JSONArray().also { a -> state.nodes.forEach { n -> a.put(JSONObject().apply { put("id", n.id); put("type", n.type); put("title", n.title); put("x", n.x); put("y", n.y); put("config", n.config) }) } })
        put("edges", JSONArray().also { a -> state.edges.forEach { e -> a.put(JSONObject().apply { put("from", e.from); put("to", e.to); put("branch", e.branch) }) } })
    }
    fun fromJson(root: JSONObject): WorkflowState {
        val s = WorkflowState(root.optString("id").ifBlank { UUID.randomUUID().toString() }, root.optString("name", "My Workflow"), active = root.optBoolean("active", false))
        val ns = root.optJSONArray("nodes") ?: JSONArray(); for (i in 0 until ns.length()) { val o = ns.optJSONObject(i) ?: continue; s.nodes.add(FlowNode(o.optInt("id", i + 1), o.optString("type", "Manual Trigger"), o.optString("title", o.optString("type", "Node")), o.optDouble("x", 24.0).toFloat(), o.optDouble("y", 24.0).toFloat(), JSONObject((o.optJSONObject("config") ?: JSONObject()).toString()))) }
        val es = root.optJSONArray("edges") ?: JSONArray(); for (i in 0 until es.length()) { val o = es.optJSONObject(i) ?: continue; s.edges.add(FlowEdge(o.optInt("from"), o.optInt("to"), o.optString("branch", ""))) }
        return s
    }
}

object WorkflowStore {
    private const val PREFS = "naten"; private const val CURRENT = "currentWorkflowId"; private const val WORKFLOWS_DIR = "workflows"
    private fun dir(c: Context): File = File(c.filesDir, WORKFLOWS_DIR).apply { mkdirs() }
    private fun file(c: Context, id: String): File = File(dir(c), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    fun loadCurrent(c: Context): WorkflowState? { val id = c.getSharedPreferences(PREFS, 0).getString(CURRENT, null); if (!id.isNullOrBlank()) load(c, id)?.let { return it }; val legacy = c.getSharedPreferences(PREFS, 0).getString("workflow", null); if (!legacy.isNullOrBlank()) { val r = runCatching { WorkflowJson.fromJson(JSONObject(legacy)) }.getOrNull(); if (r != null) { save(c, r); return r } }; return null }
    fun load(c: Context, id: String): WorkflowState? = runCatching { val raw = file(c, id).takeIf { it.exists() }?.readText() ?: return null; WorkflowJson.fromJson(JSONObject(raw)) }.getOrNull()
    fun save(c: Context, s: WorkflowState) { s.nodes.forEach { it.ensureDefaultConfig() }; file(c, s.id).writeText(WorkflowJson.toJson(s).toString()); c.getSharedPreferences(PREFS, 0).edit().putString(CURRENT, s.id).putString("workflow", WorkflowJson.toJson(s).toString()).apply() }
    fun setCurrent(c: Context, id: String) = c.getSharedPreferences(PREFS, 0).edit().putString(CURRENT, id).apply()
    fun list(c: Context): List<WorkflowState> = dir(c).listFiles { f -> f.extension == "json" }?.mapNotNull { runCatching { WorkflowJson.fromJson(JSONObject(it.readText())) }.getOrNull() }?.sortedBy { it.name.lowercase() } ?: emptyList()
    fun newWorkflow(c: Context, name: String = "My Workflow") = WorkflowState(name = name).also { save(c, it) }
    fun duplicate(c: Context, source: WorkflowState): WorkflowState { val x = WorkflowState(name = source.name + " Copy"); source.nodes.forEach { n -> x.nodes.add(FlowNode(n.id, n.type, n.title, n.x, n.y, JSONObject(n.config.toString()))) }; source.edges.forEach { e -> x.edges.add(FlowEdge(e.from, e.to, e.branch)) }; save(c, x); return x }
    fun delete(c: Context, id: String) { file(c, id).delete(); val current = c.getSharedPreferences(PREFS, 0).getString(CURRENT, null); if (current == id) c.getSharedPreferences(PREFS, 0).edit().putString(CURRENT, list(c).firstOrNull()?.id).apply() }
    fun history(c: Context): JSONArray = runCatching { JSONArray(c.getSharedPreferences(PREFS, 0).getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
    fun appendHistory(c: Context, e: JSONObject) { val a = history(c); a.put(e); val out = JSONArray(); for (i in maxOf(0, a.length() - 100) until a.length()) out.put(a.get(i)); c.getSharedPreferences(PREFS, 0).edit().putString("history", out.toString()).apply() }
}

fun FlowNode.ensureDefaultConfig() {
    if (!config.has("retries")) config.put("retries", 0); if (!config.has("timeoutSeconds")) config.put("timeoutSeconds", 60); if (!config.has("continueOnFail")) config.put("continueOnFail", false)
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
            if (!config.has("url")) config.put("url", NodeCatalog.find(type)?.defaultUrl.orEmpty().ifBlank { "https://example.com" })
            if (!config.has("headers")) config.put("headers", "")
            if (!config.has("queryParams")) config.put("queryParams", "")
            if (!config.has("body")) config.put("body", "")
            if (!config.has("credentialName")) config.put("credentialName", "")
            if (!config.has("credentialHeader")) config.put("credentialHeader", "Authorization")
            if (!config.has("credentialPrefix")) config.put("credentialPrefix", "Bearer ")
            if (!config.has("maxResponseBytes")) config.put("maxResponseBytes", 2_000_000)
        }
        "YouTube" -> { if (!config.has("method")) config.put("method", "GET"); if (!config.has("url")) config.put("url", "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=10&q=technology"); if (!config.has("headers")) config.put("headers", ""); if (!config.has("body")) config.put("body", ""); if (!config.has("credentialName")) config.put("credentialName", "youtube_api_key"); if (!config.has("credentialHeader")) config.put("credentialHeader", "X-Goog-Api-Key"); if (!config.has("credentialPrefix")) config.put("credentialPrefix", "") }
        "GraphQL" -> { if (!config.has("url")) config.put("url", "https://example.com/graphql"); if (!config.has("query")) config.put("query", "query { hello }"); if (!config.has("variables")) config.put("variables", "{}"); if (!config.has("headers")) config.put("headers", "") }
        "Webhook Trigger", "Chat Trigger" -> { if (!config.has("method")) config.put("method", "POST"); if (!config.has("path")) config.put("path", "hook/" + id.toString().take(8)) }
        "Edit Fields" -> if (!config.has("fields")) config.put("fields", "message=Hello")
        "Filter", "IF" -> { if (!config.has("field")) config.put("field", "value"); if (!config.has("operator")) config.put("operator", "exists"); if (!config.has("value")) config.put("value", "") }
        "Switch" -> { if (!config.has("field")) config.put("field", "value"); if (!config.has("cases")) config.put("cases", "one\ntwo\nthree") }
        "Wait" -> if (!config.has("seconds")) config.put("seconds", 2)
        "Limit" -> if (!config.has("count")) config.put("count", 10)
        "Remove Duplicates" -> if (!config.has("field")) config.put("field", "id")
        "Rename Keys" -> if (!config.has("mapping")) config.put("mapping", "old=new")
        "Sort" -> { if (!config.has("field")) config.put("field", "value"); if (!config.has("descending")) config.put("descending", false) }
        "Split Out" -> if (!config.has("field")) config.put("field", "items")
        "Summarize" -> { if (!config.has("field")) config.put("field", "value"); if (!config.has("operation")) config.put("operation", "count") }
        "JSON Parse" -> if (!config.has("field")) config.put("field", "text")
        "JSON Stringify" -> if (!config.has("field")) config.put("field", "json")
        "Date & Time" -> if (!config.has("format")) config.put("format", "yyyy-MM-dd HH:mm:ss")
        "Code" -> { if (!config.has("operation")) config.put("operation", "uppercase"); if (!config.has("field")) config.put("field", "text"); if (!config.has("outputField")) config.put("outputField", "text"); if (!config.has("value")) config.put("value", "Hello") }
        "Markdown" -> if (!config.has("field")) config.put("field", "text")
        "HTML" -> if (!config.has("field")) config.put("field", "html")
        "XML" -> if (!config.has("field")) config.put("field", "xml")
        "Crypto" -> if (!config.has("field")) config.put("field", "text")
        "Read File", "Extract From File" -> if (!config.has("filename")) config.put("filename", "input.txt")
        "Write File", "Convert to File" -> { if (!config.has("filename")) config.put("filename", "output.txt"); if (!config.has("data")) config.put("data", "{{" + '$' + "json}}") }
        "Set Variable" -> { if (!config.has("key")) config.put("key", "name"); if (!config.has("value")) config.put("value", "NATEN") }
        "Respond to Webhook" -> { if (!config.has("body")) config.put("body", "{{" + '$' + "json}}"); if (!config.has("statusCode")) config.put("statusCode", 200) }
        "Execute Sub-workflow" -> if (!config.has("workflowId")) config.put("workflowId", "")
        "Log" -> if (!config.has("message")) config.put("message", "{{" + '$' + "json}}")
        "Notification" -> { if (!config.has("title")) config.put("title", "NATEN"); if (!config.has("message")) config.put("message", "Workflow finished") }
        "Open URL" -> if (!config.has("url")) config.put("url", "https://n8n.io")
        "Share Text" -> if (!config.has("text")) config.put("text", "Hello from NATEN")
        "AI Text", "AI Agent" -> {
            if (!config.has("provider")) config.put("provider", "OpenAI")
            if (!config.has("endpoint")) config.put("endpoint", "https://api.openai.com/v1/chat/completions")
            if (!config.has("apiKey")) config.put("apiKey", "")
            if (!config.has("model")) config.put("model", "gpt-4o-mini")
            if (!config.has("systemPrompt")) config.put("systemPrompt", "")
            if (!config.has("prompt")) config.put("prompt", "Work with this data: {{" + '
        "Loop Over Items" -> {
            if (!config.has("batchSize")) config.put("batchSize", 1)
            if (!config.has("continueOnEmpty")) config.put("continueOnEmpty", false)
        }
        in NodeCatalog.apiBackedTypes -> { if (!config.has("method")) config.put("method", "GET"); if (!config.has("url")) config.put("url", NodeCatalog.find(type)?.defaultUrl.orEmpty()); if (!config.has("headers")) config.put("headers", ""); if (!config.has("body")) config.put("body", ""); if (!config.has("credentialName")) config.put("credentialName", ""); if (!config.has("credentialHeader")) config.put("credentialHeader", "Authorization"); if (!config.has("credentialPrefix")) config.put("credentialPrefix", "Bearer ") }
        else -> Unit
    }
}
 + "json}}")
            if (!config.has("temperature")) config.put("temperature", 0.4)
            if (!config.has("credentialName")) config.put("credentialName", "")
            if (!config.has("responseFormat")) config.put("responseFormat", "text")
        }
        "Loop Over Items" -> if (!config.has("batchSize")) config.put("batchSize", 1)
        in NodeCatalog.apiBackedTypes -> { if (!config.has("method")) config.put("method", "GET"); if (!config.has("url")) config.put("url", NodeCatalog.find(type)?.defaultUrl.orEmpty()); if (!config.has("headers")) config.put("headers", ""); if (!config.has("body")) config.put("body", ""); if (!config.has("credentialName")) config.put("credentialName", ""); if (!config.has("credentialHeader")) config.put("credentialHeader", "Authorization"); if (!config.has("credentialPrefix")) config.put("credentialPrefix", "Bearer ") }
        else -> Unit
    }
}
