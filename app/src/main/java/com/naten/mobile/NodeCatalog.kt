package com.naten.mobile

data class NodeDefinition(
    val type: String,
    val category: String,
    val description: String,
    val supported: Boolean = true
)

object NodeCatalog {
    val all = listOf(
        NodeDefinition("Manual Trigger", "Triggers", "Start manually from Run"),
        NodeDefinition("Schedule Trigger", "Triggers", "Run on a time interval"),
        NodeDefinition("Webhook Trigger", "Triggers", "Receive an HTTP request"),
        NodeDefinition("Error Trigger", "Triggers", "Trigger an error workflow"),
        NodeDefinition("Chat Trigger", "Triggers", "Receive chat-style webhook input"),
        NodeDefinition("HTTP Request", "Core", "Call any HTTP API"),
        NodeDefinition("GraphQL", "Core", "Call a GraphQL API"),
        NodeDefinition("Edit Fields", "Data", "Set and change fields"),
        NodeDefinition("Filter", "Data", "Keep items matching a rule"),
        NodeDefinition("IF", "Flow", "Split a flow by a condition"),
        NodeDefinition("Switch", "Flow", "Route by multiple values"),
        NodeDefinition("Merge", "Flow", "Join workflow paths"),
        NodeDefinition("Loop Over Items", "Flow", "Process items in batches"),
        NodeDefinition("Wait", "Flow", "Pause execution"),
        NodeDefinition("Limit", "Data", "Limit the number of items"),
        NodeDefinition("Remove Duplicates", "Data", "Remove duplicate items"),
        NodeDefinition("Rename Keys", "Data", "Rename JSON fields"),
        NodeDefinition("Sort", "Data", "Sort by a field"),
        NodeDefinition("Split Out", "Data", "Split an array field into items"),
        NodeDefinition("Summarize", "Data", "Count, sum, average or group data"),
        NodeDefinition("JSON Parse", "Data", "Parse text as JSON"),
        NodeDefinition("JSON Stringify", "Data", "Convert JSON to text"),
        NodeDefinition("Date & Time", "Data", "Create or format timestamps"),
        NodeDefinition("Code", "Code", "Safe text/data transformations"),
        NodeDefinition("Markdown", "Content", "Convert basic Markdown"),
        NodeDefinition("HTML", "Content", "Extract text from HTML"),
        NodeDefinition("XML", "Content", "Convert simple XML payloads"),
        NodeDefinition("Crypto", "Utility", "Create SHA-256 hashes"),
        NodeDefinition("Read File", "Files", "Read app-private files"),
        NodeDefinition("Write File", "Files", "Write app-private files"),
        NodeDefinition("Convert to File", "Files", "Store text data as a file"),
        NodeDefinition("Extract From File", "Files", "Read stored file content"),
        NodeDefinition("Set Variable", "Utility", "Set workflow variables"),
        NodeDefinition("Log", "Utility", "Write execution logs"),
        NodeDefinition("Notification", "Android", "Show an Android notification"),
        NodeDefinition("Open URL", "Android", "Open a URL in the browser"),
        NodeDefinition("Share Text", "Android", "Open Android share sheet"),
        NodeDefinition("AI Text", "AI", "Call Gemini or OpenAI-compatible AI"),
        NodeDefinition("AI Agent", "AI", "AI step using the same model gateway"),
        NodeDefinition("No Operation", "Utility", "Pass data through"),
        NodeDefinition("Stop / Error", "Flow", "Stop execution with an error"),
        NodeDefinition("Generic API", "Integrations", "Build custom API calls"),
        NodeDefinition("Email", "Integrations", "Send email through an HTTP API"),
        NodeDefinition("Telegram", "Integrations", "Send Telegram messages through Bot API"),
        NodeDefinition("Slack", "Integrations", "Send Slack messages through webhook/API"),
        NodeDefinition("Google Sheets", "Integrations", "Connect Sheets through Google API"),
        NodeDefinition("Gmail", "Integrations", "Connect Gmail through Google API")
    )

    fun search(query: String, category: String = "All"): List<NodeDefinition> {
        val q = query.trim().lowercase()
        return all.filter { def ->
            val categoryMatch = category == "All" || def.category == category
            val queryMatch = q.isBlank() ||
                def.type.lowercase().contains(q) ||
                def.category.lowercase().contains(q) ||
                def.description.lowercase().contains(q)
            categoryMatch && queryMatch
        }
    }

    fun categories(): List<String> =
        listOf("All") + all.map { it.category }.distinct().sorted()
}
