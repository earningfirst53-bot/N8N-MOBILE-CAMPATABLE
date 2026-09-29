package com.naten.mobile

data class NodeDefinition(
    val type: String,
    val category: String,
    val description: String,
    val supported: Boolean = true,
    val defaultUrl: String = ""
)

object NodeCatalog {
    val all = listOf(
        NodeDefinition("Manual Trigger", "Triggers", "Start manually from the Run button"),
        NodeDefinition("Schedule Trigger", "Triggers", "Start on an Android schedule"),
        NodeDefinition("Webhook Trigger", "Triggers", "Start from an HTTP request"),
        NodeDefinition("Chat Trigger", "Triggers", "Receive chat-style webhook input"),
        NodeDefinition("Error Trigger", "Triggers", "Start an error-handling workflow"),
        NodeDefinition("Respond to Webhook", "Webhooks", "Return a configured HTTP response"),
        NodeDefinition("Wait", "Flow", "Pause execution"),
        NodeDefinition("IF", "Flow", "Branch on a condition"),
        NodeDefinition("Filter", "Flow", "Route matching data"),
        NodeDefinition("Switch", "Flow", "Route by multiple values"),
        NodeDefinition("Merge", "Flow", "Join paths"),
        NodeDefinition("Loop Over Items", "Flow", "Run downstream nodes for each item or batch"),
        NodeDefinition("Execute Sub-workflow", "Flow", "Run another NATEN workflow"),
        NodeDefinition("Stop / Error", "Flow", "Stop execution with an error"),
        NodeDefinition("No Operation", "Flow", "Pass data through"),
        NodeDefinition("Unsupported / Imported", "Compatibility", "Preserved n8n node that NATEN cannot execute yet"),
        NodeDefinition("HTTP Request", "Core", "Call any HTTP API"),
        NodeDefinition("Generic API", "Core", "Build a configurable API request"),
        NodeDefinition("GraphQL", "Core", "Call a GraphQL API"),
        NodeDefinition("Edit Fields", "Data", "Set and change fields"),
        NodeDefinition("Limit", "Data", "Limit an item collection"),
        NodeDefinition("Remove Duplicates", "Data", "Remove duplicate items"),
        NodeDefinition("Rename Keys", "Data", "Rename JSON keys"),
        NodeDefinition("Sort", "Data", "Sort items"),
        NodeDefinition("Split Out", "Data", "Split an array into workflow items"),
        NodeDefinition("Summarize", "Data", "Count, sum or average values"),
        NodeDefinition("JSON Parse", "Data", "Parse a JSON string"),
        NodeDefinition("JSON Stringify", "Data", "Convert data into text"),
        NodeDefinition("Date & Time", "Data", "Create formatted timestamps"),
        NodeDefinition("Code", "Code", "Safe built-in transformations"),
        NodeDefinition("Markdown", "Content", "Strip common Markdown formatting"),
        NodeDefinition("HTML", "Content", "Extract text from HTML"),
        NodeDefinition("XML", "Content", "Extract text from XML"),
        NodeDefinition("Crypto", "Utility", "Create SHA-256 hashes"),
        NodeDefinition("Log", "Utility", "Write execution logs"),
        NodeDefinition("Set Variable", "Utility", "Store workflow variables"),
        NodeDefinition("Read File", "Files", "Read a file stored by NATEN"),
        NodeDefinition("Write File", "Files", "Write a NATEN app file"),
        NodeDefinition("Convert to File", "Files", "Write workflow data to a file"),
        NodeDefinition("Extract From File", "Files", "Read stored file content"),
        NodeDefinition("Notification", "Android", "Show an Android notification"),
        NodeDefinition("Open URL", "Android", "Open a URL in the browser"),
        NodeDefinition("Share Text", "Android", "Open the Android share sheet"),
        NodeDefinition("AI Text", "AI", "Call an LLM through a provider gateway"),
        NodeDefinition("AI Agent", "AI", "LLM step with extensible tool support"),

        NodeDefinition("Email", "Integrations", "Generic email/API request"),
        NodeDefinition("Gmail", "Integrations", "Gmail API", defaultUrl = "https://gmail.googleapis.com/gmail/v1/users/me/"),
        NodeDefinition("Google Sheets", "Integrations", "Google Sheets API", defaultUrl = "https://sheets.googleapis.com/v4/spreadsheets"),
        NodeDefinition("Google Drive", "Integrations", "Google Drive API", defaultUrl = "https://www.googleapis.com/drive/v3/files"),
        NodeDefinition("Google Calendar", "Integrations", "Google Calendar API", defaultUrl = "https://www.googleapis.com/calendar/v3/calendars"),
        NodeDefinition("Google Docs", "Integrations", "Google Docs API", defaultUrl = "https://docs.googleapis.com/v1/documents"),
        NodeDefinition("Google Slides", "Integrations", "Google Slides API", defaultUrl = "https://slides.googleapis.com/v1/presentations"),
        NodeDefinition("YouTube", "Integrations", "YouTube Data API v3 with public API key or OAuth access token", defaultUrl = "https://www.googleapis.com/youtube/v3/"),
        NodeDefinition("Telegram", "Integrations", "Telegram Bot API", defaultUrl = "https://api.telegram.org/"),
        NodeDefinition("Slack", "Integrations", "Slack Web API", defaultUrl = "https://slack.com/api/"),
        NodeDefinition("Discord", "Integrations", "Discord HTTP API", defaultUrl = "https://discord.com/api/v10/"),
        NodeDefinition("Microsoft Teams", "Integrations", "Microsoft Graph / Teams API", defaultUrl = "https://graph.microsoft.com/v1.0/"),
        NodeDefinition("Outlook", "Integrations", "Microsoft Graph Outlook API", defaultUrl = "https://graph.microsoft.com/v1.0/me/"),
        NodeDefinition("OneDrive", "Integrations", "Microsoft Graph OneDrive API", defaultUrl = "https://graph.microsoft.com/v1.0/me/drive/"),
        NodeDefinition("GitHub", "Integrations", "GitHub REST API", defaultUrl = "https://api.github.com/"),
        NodeDefinition("GitLab", "Integrations", "GitLab REST API", defaultUrl = "https://gitlab.com/api/v4/"),
        NodeDefinition("Bitbucket", "Integrations", "Bitbucket REST API", defaultUrl = "https://api.bitbucket.org/2.0/"),
        NodeDefinition("Notion", "Integrations", "Notion API", defaultUrl = "https://api.notion.com/v1/"),
        NodeDefinition("Airtable", "Integrations", "Airtable API", defaultUrl = "https://api.airtable.com/v0/"),
        NodeDefinition("Trello", "Integrations", "Trello REST API", defaultUrl = "https://api.trello.com/1/"),
        NodeDefinition("Asana", "Integrations", "Asana API", defaultUrl = "https://app.asana.com/api/1.0/"),
        NodeDefinition("ClickUp", "Integrations", "ClickUp API", defaultUrl = "https://api.clickup.com/api/v2/"),
        NodeDefinition("Jira", "Integrations", "Atlassian Jira API"),
        NodeDefinition("Linear", "Integrations", "Linear GraphQL API", defaultUrl = "https://api.linear.app/graphql"),
        NodeDefinition("HubSpot", "Integrations", "HubSpot API", defaultUrl = "https://api.hubapi.com/"),
        NodeDefinition("Salesforce", "Integrations", "Salesforce REST API"),
        NodeDefinition("Pipedrive", "Integrations", "Pipedrive API", defaultUrl = "https://api.pipedrive.com/v1/"),
        NodeDefinition("Shopify", "Integrations", "Shopify Admin API"),
        NodeDefinition("WooCommerce", "Integrations", "WooCommerce REST API"),
        NodeDefinition("Stripe", "Integrations", "Stripe API", defaultUrl = "https://api.stripe.com/v1/"),
        NodeDefinition("Razorpay", "Integrations", "Razorpay API", defaultUrl = "https://api.razorpay.com/v1/"),
        NodeDefinition("PayPal", "Integrations", "PayPal REST API"),
        NodeDefinition("WordPress", "Integrations", "WordPress REST API"),
        NodeDefinition("Mailchimp", "Integrations", "Mailchimp API"),
        NodeDefinition("SendGrid", "Integrations", "SendGrid API", defaultUrl = "https://api.sendgrid.com/v3/"),
        NodeDefinition("Resend", "Integrations", "Resend API", defaultUrl = "https://api.resend.com/"),
        NodeDefinition("Brevo", "Integrations", "Brevo API", defaultUrl = "https://api.brevo.com/v3/"),
        NodeDefinition("Twilio", "Integrations", "Twilio API"),
        NodeDefinition("WhatsApp Cloud", "Integrations", "WhatsApp Cloud API"),
        NodeDefinition("Meta Graph", "Integrations", "Meta Graph API"),
        NodeDefinition("Instagram Graph", "Integrations", "Instagram Graph API"),
        NodeDefinition("Reddit", "Integrations", "Reddit API", defaultUrl = "https://oauth.reddit.com/"),
        NodeDefinition("X / Twitter", "Integrations", "X API", defaultUrl = "https://api.x.com/2/"),
        NodeDefinition("Medium", "Integrations", "Medium API", defaultUrl = "https://api.medium.com/v1/"),
        NodeDefinition("RSS Feed", "Integrations", "Fetch an RSS/Atom feed"),
        NodeDefinition("OpenWeather", "Integrations", "OpenWeather API", defaultUrl = "https://api.openweathermap.org/data/2.5/"),
        NodeDefinition("Supabase", "Integrations", "Supabase REST API"),
        NodeDefinition("Firebase", "Integrations", "Firebase REST API"),
        NodeDefinition("OpenRouter", "Integrations", "OpenRouter model API", defaultUrl = "https://openrouter.ai/api/v1/chat/completions"),
        NodeDefinition("Groq", "Integrations", "Groq API", defaultUrl = "https://api.groq.com/openai/v1/chat/completions"),
        NodeDefinition("Mistral", "Integrations", "Mistral API", defaultUrl = "https://api.mistral.ai/v1/chat/completions"),
        NodeDefinition("DeepSeek", "Integrations", "DeepSeek API", defaultUrl = "https://api.deepseek.com/chat/completions"),
        NodeDefinition("Cohere", "Integrations", "Cohere API", defaultUrl = "https://api.cohere.com/v2/chat"),
        NodeDefinition("Hugging Face", "Integrations", "Hugging Face Inference API"),
        NodeDefinition("Tavily", "Integrations", "Tavily search API", defaultUrl = "https://api.tavily.com/search"),
        NodeDefinition("Exa", "Integrations", "Exa search API", defaultUrl = "https://api.exa.ai/search"),
        NodeDefinition("Zapier", "Integrations", "Zapier webhook/API")
    )

    val apiBackedTypes: Set<String> =
        setOf("HTTP Request", "Generic API") + all.filter { it.category == "Integrations" }.map { it.type }

    fun search(query: String, category: String = "All"): List<NodeDefinition> {
        val q = query.trim().lowercase()
        return all.filter { def ->
            (category == "All" || def.category == category) &&
                (q.isBlank() || def.type.lowercase().contains(q) ||
                    def.category.lowercase().contains(q) ||
                    def.description.lowercase().contains(q))
        }
    }

    fun categories(): List<String> =
        listOf("All") + all.map { it.category }.distinct().sorted()

    fun find(type: String): NodeDefinition? = all.firstOrNull { it.type == type }
}
