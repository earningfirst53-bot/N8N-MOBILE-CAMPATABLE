package com.naten.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var canvas: WorkflowCanvas
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var nameView: TextView
    private lateinit var activeSwitch: Switch

    private var state = WorkflowState()
    private val logLines = mutableListOf<String>()
    private val engine by lazy { WorkflowEngine(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        state = WorkflowStore.loadCurrent(this) ?: starterWorkflow()
        state.nodes.forEach { it.ensureDefaultConfig() }

        buildUi()
        WorkflowStore.save(this, state)
        refreshUi()
        requestNotificationPermission()
        syncAutomation()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 246, 249))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(7))
            setBackgroundColor(Color.WHITE)
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        nameView = TextView(this).apply {
            text = state.name
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 29, 36))
            setPadding(0, 0, dp(8), 0)
            setOnClickListener { renameWorkflow() }
        }
        titleRow.addView(nameView, LinearLayout.LayoutParams(0, dp(42), 1f))

        val workflowsButton = toolbarButton("Workflows")
        workflowsButton.setOnClickListener { showWorkflows() }
        titleRow.addView(workflowsButton)

        activeSwitch = Switch(this).apply {
            text = "Active"
            textSize = 12f
            isChecked = state.active
            setOnCheckedChangeListener { _, checked ->
                state.active = checked
                WorkflowStore.save(this@MainActivity, state)
                syncAutomation()
                if (checked) {
                    setStatus(
                        "Automation active",
                        "Scheduled workflows can run while NATEN is closed. Webhook workflows keep a background listener."
                    )
                } else {
                    setStatus("Automation inactive", "This workflow will not run automatically.")
                }
            }
        }
        titleRow.addView(activeSwitch)
        toolbar.addView(titleRow)

        val actionRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val add = toolbarButton("+ Add Node")
        add.setOnClickListener { showNodeLibrary() }
        val save = toolbarButton("Save")
        save.setOnClickListener {
            WorkflowStore.save(this, state)
            syncAutomation()
            setStatus("Saved", "Workflow stored on this device.")
        }
        val run = toolbarButton("Run")
        run.setOnClickListener { runWorkflow() }
        val history = toolbarButton("History")
        history.setOnClickListener { showHistory() }
        val more = toolbarButton("More")
        more.setOnClickListener { showMoreMenu() }

        actions.addView(add)
        actions.addView(save)
        actions.addView(run)
        actions.addView(history)
        actions.addView(more)

        listOf(
            "Manual Trigger",
            "Schedule Trigger",
            "HTTP Request",
            "IF",
            "AI Text",
            "Notification"
        ).forEach { type ->
            val quick = toolbarButton("+ " + type.removeSuffix(" Trigger"))
            quick.setOnClickListener { addNode(type) }
            actions.addView(quick)
        }

        actionRow.addView(actions)
        toolbar.addView(actionRow)

        val canvasHost = FrameLikeScroll(this)
        canvas = WorkflowCanvas(this)
        canvasHost.addView(canvas)
        root.addView(
            canvasHost,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(10))
            setBackgroundColor(Color.WHITE)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 44, 51))
        }
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(84, 90, 100))
            setPadding(0, dp(3), 0, 0)
            maxLines = 6
        }

        bottom.addView(statusView)
        bottom.addView(logView)

        root.addView(toolbar)
        root.addView(bottom)

        setContentView(root)
    }

    private fun addNode(type: String) {
        val index = state.nodes.size
        val id = state.nodes.maxOfOrNull { it.id }?.plus(1) ?: 1
        val node = FlowNode(
            id = id,
            type = type,
            title = defaultTitle(type),
            x = dpF(24 + (index % 2) * 205),
            y = dpF(24 + (index / 2) * 124)
        )
        node.ensureDefaultConfig()
        state.nodes.add(node)
        canvas.selectedId = id
        WorkflowStore.save(this, state)
        canvas.invalidate()
        setStatus("Added " + node.title, "Configure the node, then connect it to the next node.")
        showNodeEditor(node)
    }

    private fun defaultTitle(type: String): String = when (type) {
        "Manual Trigger" -> "Manual start"
        "Schedule Trigger" -> "Scheduled start"
        "Webhook Trigger" -> "Webhook"
        "Chat Trigger" -> "Chat webhook"
        "Error Trigger" -> "Error trigger"
        "HTTP Request" -> "HTTP request"
        "Generic API" -> "Generic API"
        "GraphQL" -> "GraphQL request"
        "Edit Fields" -> "Set fields"
        "Filter" -> "Filter data"
        "IF" -> "Check condition"
        "Switch" -> "Route by value"
        "Merge" -> "Merge paths"
        "Loop Over Items" -> "Loop items"
        "Wait" -> "Wait"
        "Limit" -> "Limit items"
        "Remove Duplicates" -> "Remove duplicates"
        "Rename Keys" -> "Rename keys"
        "Sort" -> "Sort data"
        "Split Out" -> "Split array"
        "Summarize" -> "Summarize"
        "JSON Parse" -> "Parse JSON"
        "JSON Stringify" -> "Stringify JSON"
        "Date & Time" -> "Date & time"
        "Code" -> "Transform"
        "Markdown" -> "Markdown"
        "HTML" -> "HTML text"
        "XML" -> "XML text"
        "Crypto" -> "SHA-256"
        "Read File", "Extract From File" -> "Read file"
        "Write File", "Convert to File" -> "Write file"
        "Set Variable" -> "Set variable"
        "Log" -> "Execution log"
        "Notification" -> "Notification"
        "Open URL" -> "Open URL"
        "Share Text" -> "Share text"
        "AI Text" -> "AI text"
        "AI Agent" -> "AI agent"
        "Email" -> "Email via API"
        "Telegram" -> "Telegram via API"
        "Slack" -> "Slack via API"
        "Google Sheets" -> "Google Sheets via API"
        "Gmail" -> "Gmail via API"
        "No Operation" -> "No operation"
        "Stop / Error" -> "Stop / error"
        "Respond to Webhook" -> "Webhook response"
        "Execute Sub-workflow" -> "Run another workflow"
        else -> type
    }

    private fun nextId(): Int = state.nodes.maxOfOrNull { it.id }?.plus(1) ?: 1

    private fun starterWorkflow(): WorkflowState {
        val s = WorkflowState(name = "NATEN Starter")
        val trigger = FlowNode(1, "Manual Trigger", "Press Run", dpF(24), dpF(20))
        val edit = FlowNode(2, "Edit Fields", "Create message", dpF(24), dpF(144))
        edit.config.put("fields", "message=Hello from NATEN")
        val notify = FlowNode(3, "Notification", "Show notification", dpF(24), dpF(268))
        notify.config.put("title", "NATEN")
        notify.config.put("message", "Hello from NATEN")
        val log = FlowNode(4, "Log", "Write execution log", dpF(24), dpF(392))
        log.config.put("message", "Starter workflow completed")
        s.nodes.addAll(listOf(trigger, edit, notify, log))
        s.edges.addAll(
            listOf(
                FlowEdge(1, 2),
                FlowEdge(2, 3),
                FlowEdge(3, 4)
            )
        )
        return s
    }

    private fun showNodeLibrary() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }

        val search = EditText(this).apply {
            hint = "Search nodes…"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.WHITE, 10f, Color.rgb(208, 213, 221), 1)
        }
        box.addView(search)

        val category = Spinner(this)
        category.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            NodeCatalog.categories()
        )
        box.addView(category)

        val list = ListView(this)
        box.addView(list, LinearLayout.LayoutParams(-1, dp(420)))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Node Library")
            .setView(box)
            .setNegativeButton("Close", null)
            .create()

        fun refresh() {
            val defs = NodeCatalog.search(
                search.text.toString(),
                category.selectedItem?.toString() ?: "All"
            )
            val labels = defs.map { it.type + "  •  " + it.category + "\n" + it.description }
            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                labels
            )
            list.setOnItemClickListener { _, _, position, _ ->
                addNode(defs[position].type)
                dialog.dismiss()
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        category.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                refresh()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        refresh()
        dialog.show()
    }

    private fun showNodeEditor(node: FlowNode) {
        node.ensureDefaultConfig()

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val title = EditText(this).apply {
            hint = "Node name"
            setText(node.title)
            textSize = 14f
        }
        form.addView(title)

        fun label(value: String) {
            form.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(Color.rgb(92, 98, 108))
                setPadding(0, dp(8), 0, dp(2))
            })
        }

        fun textField(key: String, hint: String = key, multi: Boolean = false): EditText {
            label(hint)
            val e = EditText(this).apply {
                setText(node.config.optString(key, ""))
                textSize = 14f
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = rounded(Color.WHITE, 10f, Color.rgb(209, 214, 222), 1)
                if (!multi) setSingleLine(true)
                else {
                    minLines = 4
                    gravity = Gravity.TOP
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
            }
            form.addView(e)
            return e
        }

        fun spinner(key: String, hint: String, options: List<String>): Spinner {
            label(hint)
            val s = Spinner(this)
            s.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                options
            )
            val current = node.config.optString(key)
            s.setSelection(options.indexOf(current).coerceAtLeast(0))
            form.addView(s)
            return s
        }

        val fields = linkedMapOf<String, EditText>()
        val spinners = linkedMapOf<String, Spinner>()

        fields["retries"] = textField("retries", "Retries (0–5)")
        fields["retries"]?.inputType = InputType.TYPE_CLASS_NUMBER
        fields["timeoutSeconds"] = textField("timeoutSeconds", "Node timeout (seconds)")
        fields["timeoutSeconds"]?.inputType = InputType.TYPE_CLASS_NUMBER
        spinners["continueOnFail"] = spinner(
            "continueOnFail",
            "Continue on failure",
            listOf("false", "true")
        )

        when (node.type) {
            "Schedule Trigger" -> {
                fields["interval"] = textField("interval", "Interval")
                fields["interval"]?.inputType = InputType.TYPE_CLASS_NUMBER
                spinners["unit"] = spinner("unit", "Unit", listOf("seconds", "minutes", "hours", "days"))
                label("Background schedules use Android alarms and may be deferred slightly by the OS.")
            }

            "Webhook Trigger", "Chat Trigger" -> {
                spinners["method"] = spinner("method", "HTTP method", listOf("POST", "GET", "ANY"))
                fields["path"] = textField("path", "Path, e.g. hook/my-workflow")
                label("Webhook listener: http://PHONE_IP:8787/<path>")
            }

            in NodeCatalog.apiBackedTypes -> {
                spinners["method"] = spinner("method", "Method", listOf("GET", "POST", "PUT", "PATCH", "DELETE"))
                fields["url"] = textField("url", "URL")
                fields["headers"] = textField("headers", "Headers (one per line: Key: Value)", true)
                fields["body"] = textField("body", "Body", true)
                fields["credentialName"] = textField("credentialName", "Saved credential name (optional)")
                fields["credentialHeader"] = textField("credentialHeader", "Credential header")
                fields["credentialPrefix"] = textField("credentialPrefix", "Credential prefix")
            }

            "GraphQL" -> {
                fields["url"] = textField("url", "GraphQL URL")
                fields["headers"] = textField("headers", "Headers", true)
                fields["query"] = textField("query", "Query", true)
                fields["variables"] = textField("variables", "Variables JSON", true)
            }

            "Edit Fields" -> fields["fields"] = textField(
                "fields",
                "Fields (one per line: key=value)",
                true
            )

            "IF", "Filter" -> {
                fields["field"] = textField("field", "JSON field/path")
                spinners["operator"] = spinner(
                    "operator",
                    "Operator",
                    listOf(
                        "equals", "not equals", "contains",
                        "starts with", "ends with",
                        "greater than", "less than", "exists", "not exists"
                    )
                )
                fields["value"] = textField("value", "Compare with")
            }

            "Switch" -> {
                fields["field"] = textField("field", "JSON field/path")
                fields["cases"] = textField("cases", "Cases (one per line)", true)
                label("Connect each Switch output and select a route.")
            }

            "Wait" -> {
                fields["seconds"] = textField("seconds", "Seconds")
                fields["seconds"]?.inputType = InputType.TYPE_CLASS_NUMBER
            }

            "Limit" -> {
                fields["count"] = textField("count", "Maximum items")
                fields["count"]?.inputType = InputType.TYPE_CLASS_NUMBER
            }

            "Remove Duplicates" -> fields["field"] = textField("field", "Duplicate key")
            "Rename Keys" -> fields["mapping"] = textField("mapping", "Mapping old=new", true)
            "Sort" -> {
                fields["field"] = textField("field", "Sort field")
                spinners["descending"] = spinner("descending", "Order", listOf("false", "true"))
            }
            "Split Out" -> fields["field"] = textField("field", "Array field")
            "Summarize" -> {
                fields["field"] = textField("field", "Field")
                spinners["operation"] = spinner("operation", "Operation", listOf("count", "sum", "average"))
            }
            "JSON Parse", "JSON Stringify", "Date & Time", "Markdown", "HTML", "XML", "Crypto" ->
                fields["field"] = textField("field", "Field")
            "Date & Time" -> fields["format"] = textField("format", "Format")
            "Code" -> {
                spinners["operation"] = spinner(
                    "operation",
                    "Transform",
                    listOf("uppercase", "lowercase", "length", "reverse", "trim", "set value")
                )
                fields["field"] = textField("field", "Input field")
                fields["outputField"] = textField("outputField", "Output field")
                fields["value"] = textField("value", "Value for set value")
            }
            "Read File", "Extract From File" -> fields["filename"] = textField("filename", "Filename")
            "Write File", "Convert to File" -> {
                fields["filename"] = textField("filename", "Filename")
                fields["data"] = textField("data", "Data", true)
            }
            "Set Variable" -> {
                fields["key"] = textField("key", "Variable name")
                fields["value"] = textField("value", "Variable value", true)
            }
            "Notification" -> {
                fields["title"] = textField("title", "Title")
                fields["message"] = textField("message", "Message", true)
            }
            "Open URL" -> fields["url"] = textField("url", "URL")
            "Share Text" -> fields["text"] = textField("text", "Text", true)
            "AI Text", "AI Agent" -> {
                spinners["provider"] = spinner("provider", "Provider", listOf("OpenAI-compatible", "Gemini"))
                fields["endpoint"] = textField("endpoint", "Endpoint")
                fields["credentialName"] = textField("credentialName", "Saved credential name (optional)")
                fields["apiKey"] = textField("apiKey", "API key (leave blank when using saved credential)")
                fields["apiKey"]?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                fields["model"] = textField("model", "Model")
                fields["prompt"] = textField("prompt", "Prompt", true)
                fields["temperature"] = textField("temperature", "Temperature")
            }
            "Log", "Stop / Error" -> fields["message"] = textField("message", "Message", true)
            "Respond to Webhook" -> {
                fields["body"] = textField("body", "Response body", true)
                fields["statusCode"] = textField("statusCode", "HTTP status code")
            }
            "Execute Sub-workflow" -> {
                fields["workflowId"] = textField("workflowId", "Target workflow ID")
                label("Use Workflows to find the target workflow ID. The child workflow receives the current JSON input.")
            }
            else -> {
                label("Advanced configuration JSON")
                fields["__json"] = textField("__json", "JSON", true).also {
                    it.setText(node.config.toString(2))
                }
            }
        }

        label("Connections are made by dragging the output dot on the right of this node to another node's input dot.")

        val scroll = ScrollView(this).apply {
            addView(form)
        }

        AlertDialog.Builder(this)
            .setTitle(node.type)
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ -> deleteNode(node) }
            .setPositiveButton("Save") { _, _ ->
                node.title = title.text.toString().trim().ifBlank { defaultTitle(node.type) }

                if (fields.containsKey("__json")) {
                    runCatching {
                        node.config = JSONObject(fields["__json"]!!.text.toString())
                    }.onFailure {
                        Toast.makeText(this, "Invalid JSON: " + it.message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    fields.forEach { pair -> node.config.put(pair.key, pair.value.text.toString()) }
                    spinners.forEach { pair ->
                        node.config.put(pair.key, pair.value.selectedItem.toString())
                    }

                    val credentialName = node.config.optString("credentialName").trim()
                    val apiKey = node.config.optString("apiKey")
                    if (credentialName.isNotBlank() && apiKey.isNotBlank()) {
                        CredentialVault.put(this, credentialName, apiKey)
                        node.config.put("apiKey", "")
                        Toast.makeText(
                            this,
                            "Credential saved securely as " + credentialName,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                node.ensureDefaultConfig()
                WorkflowStore.save(this, state)
                syncAutomation()
                canvas.invalidate()
                setStatus("Saved node", node.title)
            }
            .show()
    }

    private fun deleteNode(node: FlowNode) {
        state.nodes.removeAll { it.id == node.id }
        state.edges.removeAll { it.from == node.id || it.to == node.id }
        WorkflowStore.save(this, state)
        canvas.selectedId = null
        canvas.invalidate()
        syncAutomation()
        setStatus("Node deleted", "Connections to this node were removed.")
    }

    private fun connectNodes(from: FlowNode, to: FlowNode) {
        if (from.id == to.id) return

        fun save(branch: String) {
            if (state.edges.none { it.from == from.id && it.to == to.id && it.branch == branch }) {
                state.edges.add(FlowEdge(from.id, to.id, branch))
            }
            WorkflowStore.save(this, state)
            canvas.invalidate()
            setStatus(
                "Connected",
                from.title + " → " + to.title +
                    if (branch.isBlank()) "" else " [" + branch + "]"
            )
        }

        when (from.type) {
            "IF", "Filter" -> AlertDialog.Builder(this)
                .setTitle("Choose branch")
                .setSingleChoiceItems(arrayOf("true", "false"), 0) { dialog, which ->
                    save(if (which == 0) "true" else "false")
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()

            "Switch" -> {
                val routes = from.config.optString("cases")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toMutableList()
                routes.add("default")

                AlertDialog.Builder(this)
                    .setTitle("Choose route")
                    .setItems(routes.toTypedArray()) { _, which ->
                        save(routes[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            else -> save("")
        }
    }

    private fun runWorkflow() {
        if (state.nodes.isEmpty()) {
            setStatus("Nothing to run", "Add a trigger node.")
            return
        }

        WorkflowStore.save(this, state)
        logLines.clear()
        logView.text = "Starting execution…"
        statusView.text = "Running…"

        engine.run(
            state,
            object : ExecutionListener {
                override fun onStatus(message: String) {
                    runOnUiThread { statusView.text = message }
                }

                override fun onLog(message: String) {
                    runOnUiThread {
                        logLines.add(message)
                        while (logLines.size > 7) logLines.removeAt(0)
                        logView.text = logLines.joinToString("\n")
                    }
                }

                override fun onFinished(success: Boolean, message: String, output: JSONObject) {
                    runOnUiThread {
                        statusView.text = if (success) "Completed" else "Failed"
                        logLines.add(message)
                        while (logLines.size > 7) logLines.removeAt(0)
                        logView.text = logLines.joinToString("\n")
                    }
                }
            }
        )
    }

    private fun syncAutomation() {
        WorkflowScheduler.rescheduleAll(this)

        val anyWebhook = WorkflowStore.list(this).any { workflow ->
            workflow.active && workflow.nodes.any {
                it.type == "Webhook Trigger" || it.type == "Chat Trigger"
            }
        }

        if (anyWebhook) {
            WorkflowScheduler.startAlwaysOnService(this)
        } else {
            stopService(Intent(this, AutomationService::class.java))
        }
    }

    private fun showWorkflows() {
        val workflows = WorkflowStore.list(this)
        if (workflows.isEmpty()) {
            val created = WorkflowStore.newWorkflow(this, "My Workflow")
            state = created
            refreshUi()
            return
        }

        val labels = workflows.map {
            val active = if (it.active) " • ACTIVE" else ""
            it.name + active + "\n" +
                it.nodes.size + " nodes • " + it.edges.size + " connections"
        }

        AlertDialog.Builder(this)
            .setTitle("Workflows")
            .setItems(labels.toTypedArray()) { _, which ->
                switchWorkflow(workflows[which].id)
            }
            .setNegativeButton("Delete current") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete " + state.name + "?")
                    .setMessage("This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        val deleting = state.id
                        WorkflowScheduler.cancel(this, deleting)
                        WorkflowStore.delete(this, deleting)
                        state = WorkflowStore.loadCurrent(this)
                            ?: WorkflowStore.newWorkflow(this, "My Workflow")
                        refreshUi()
                        syncAutomation()
                    }
                    .show()
            }
            .setNeutralButton("Duplicate current") { _, _ ->
                state = WorkflowStore.duplicate(this, state)
                refreshUi()
                syncAutomation()
            }
            .setPositiveButton("New workflow") { _, _ ->
                state = WorkflowStore.newWorkflow(this, "My Workflow")
                refreshUi()
                syncAutomation()
            }
            .show()
    }

    private fun switchWorkflow(id: String) {
        val next = WorkflowStore.load(this, id) ?: return
        state = next
        WorkflowStore.setCurrent(this, next.id)
        refreshUi()
        syncAutomation()
        setStatus("Opened workflow", next.name)
    }

    private fun renameWorkflow() {
        val input = EditText(this).apply {
            setText(state.name)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("Workflow name")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                state.name = input.text.toString().trim().ifBlank { "My Workflow" }
                WorkflowStore.save(this, state)
                refreshUi()
            }
            .show()
    }

    private fun showHistory() {
        val arr = WorkflowStore.history(this)
        if (arr.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle("Execution history")
                .setMessage("No executions yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val lines = mutableListOf<String>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            lines.add(
                (if (o.optBoolean("success")) "OK" else "FAIL") +
                    " • " + o.optString("workflow") +
                    "\n" + o.optString("message") +
                    "\n" + o.optLong("durationMs") + " ms" +
                    "\nOutput: " + o.optString("output", "").take(700)
            )
        }

        val text = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(45, 50, 58))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = lines.take(50).joinToString("\n\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Execution history")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showAutomationStatus() {
        val active = WorkflowStore.list(this).filter { it.active }
        val webhook = active.any { workflow ->
            workflow.nodes.any { it.type == "Webhook Trigger" || it.type == "Chat Trigger" }
        }

        val message = buildString {
            append("Active workflows: ").append(active.size).append("\n\n")
            active.forEach {
                append("• ").append(it.name).append(" — ")
                append(it.nodes.size).append(" nodes\n")
            }
            append("\nSchedule alarms: configured per active Schedule Trigger.")
            append("\nWebhook listener: ")
            append(if (webhook) "ON" else "OFF")
            if (webhook) {
                append("\nLocal webhook port: 8787")
                append("\nUse http://PHONE_IP:8787/<your-path>")
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                append("\nBattery optimization exemption: ")
                append(if (pm.isIgnoringBatteryOptimizations(packageName)) "ON" else "OFF")
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    append("\nFor reliability, allow NATEN to run without battery optimization.")
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Automation status")
            .setMessage(message)
            .setPositiveButton("Resync", { _, _ ->
                syncAutomation()
                Toast.makeText(this, "Automation resynced", Toast.LENGTH_SHORT).show()
            })
            .setNeutralButton("Battery settings") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            "Node Library",
            "Automation status",
            "Credentials",
            "Import workflow JSON",
            "Export NATEN JSON",
            "Export n8n-style JSON",
            "New workflow",
            "Help"
        )

        AlertDialog.Builder(this)
            .setTitle("NATEN")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showNodeLibrary()
                    1 -> showAutomationStatus()
                    2 -> showCredentials()
                    3 -> importJson()
                    4 -> exportJson(false)
                    5 -> exportJson(true)
                    6 -> {
                        state = WorkflowStore.newWorkflow(this, "My Workflow")
                        refreshUi()
                        syncAutomation()
                    }
                    7 -> showHelp()
                }
            }
            .show()
    }

    private fun exportJson(n8nStyle: Boolean) {
        val content = if (n8nStyle) n8nJson().toString(2)
        else WorkflowJson.toJson(state).toString(2)

        pendingExport = content

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            putExtra(
                Intent.EXTRA_TITLE,
                state.name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json"
            )
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private var pendingExport: String? = null

    private fun importJson() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!

        if (requestCode == REQ_EXPORT) {
            val value = pendingExport ?: return
            contentResolver.openOutputStream(uri)?.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writer.write(value) }
            }
            setStatus("Exported", "Workflow JSON written.")
            return
        }

        if (requestCode == REQ_IMPORT) {
            val raw = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            } ?: return

            runCatching {
                val root = JSONObject(raw)
                state = if (root.optString("format").startsWith("naten")) {
                    WorkflowJson.fromJson(root)
                } else {
                    importN8nJson(root)
                }
                WorkflowStore.save(this, state)
                refreshUi()
                syncAutomation()
                setStatus("Imported", state.name)
            }.onFailure {
                setStatus("Import failed", it.message ?: "Invalid JSON")
            }
        }
    }

    private fun n8nJson(): JSONObject {
        val root = JSONObject().apply {
            put("name", state.name)
            put("active", state.active)
            put("settings", JSONObject())
        }

        val nodes = JSONArray()
        state.nodes.forEach { node ->
            node.ensureDefaultConfig()
            nodes.put(
                JSONObject().apply {
                    put("id", node.id.toString())
                    put("name", node.title)
                    put("type", n8nType(node.type))
                    put("typeVersion", 1)
                    put("position", JSONArray().put(node.x).put(node.y))
                    put("parameters", JSONObject(node.config.toString()))
                }
            )
        }
        root.put("nodes", nodes)

        val connections = JSONObject()
        state.edges.forEach { edge ->
            val from = state.nodes.firstOrNull { it.id == edge.from } ?: return@forEach
            val to = state.nodes.firstOrNull { it.id == edge.to } ?: return@forEach

            val source = connections.optJSONObject(from.title) ?: JSONObject()
            val main = source.optJSONArray("main") ?: JSONArray()
            val branchIndex = if (from.type == "IF" && edge.branch.equals("false", true)) 1 else 0
            while (main.length() <= branchIndex) main.put(JSONArray())

            val line = main.optJSONArray(branchIndex) ?: JSONArray()
            line.put(
                JSONObject()
                    .put("node", to.title)
                    .put("type", "main")
                    .put("index", 0)
            )
            main.put(branchIndex, line)
            source.put("main", main)
            connections.put(from.title, source)
        }

        root.put("connections", connections)
        return root
    }

    private fun n8nType(type: String): String = when (type) {
        "Manual Trigger" -> "n8n-nodes-base.manualTrigger"
        "Schedule Trigger" -> "n8n-nodes-base.scheduleTrigger"
        "Webhook Trigger" -> "n8n-nodes-base.webhook"
        "HTTP Request" -> "n8n-nodes-base.httpRequest"
        "Edit Fields" -> "n8n-nodes-base.set"
        "IF" -> "n8n-nodes-base.if"
        "Switch" -> "n8n-nodes-base.switch"
        "Wait" -> "n8n-nodes-base.wait"
        "Code" -> "n8n-nodes-base.code"
        "Respond to Webhook" -> "n8n-nodes-base.respondToWebhook"
        "Execute Sub-workflow" -> "n8n-nodes-base.executeWorkflow"
        else -> if (type in NodeCatalog.apiBackedTypes) "n8n-nodes-base.httpRequest" else "n8n-nodes-base.noOp"
    }

    private fun importN8nJson(root: JSONObject): WorkflowState {
        val imported = WorkflowState(
            name = root.optString("name", "Imported workflow"),
            active = root.optBoolean("active", false)
        )

        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        val nameToId = mutableMapOf<String, Int>()

        for (i in 0 until nodes.length()) {
            val o = nodes.optJSONObject(i) ?: continue
            val id = o.optString("id").toIntOrNull() ?: i + 1
            val mapped = mapN8nType(o.optString("type"))
            val pos = o.optJSONArray("position") ?: JSONArray()
            val cfg = o.optJSONObject("parameters") ?: JSONObject()

            val node = FlowNode(
                id = id,
                type = mapped,
                title = o.optString("name", mapped),
                x = pos.optDouble(0, 24.0).toFloat(),
                y = pos.optDouble(1, 24.0).toFloat(),
                config = JSONObject(cfg.toString())
            )
            node.ensureDefaultConfig()
            imported.nodes.add(node)
            nameToId[node.title] = node.id
        }

        val connectionRoot = root.optJSONObject("connections") ?: JSONObject()
        val keys = connectionRoot.keys()
        while (keys.hasNext()) {
            val sourceName = keys.next()
            val sourceId = nameToId[sourceName] ?: continue
            val source = connectionRoot.optJSONObject(sourceName) ?: continue
            val main = source.optJSONArray("main") ?: continue

            for (branch in 0 until main.length()) {
                val line = main.optJSONArray(branch) ?: continue
                for (i in 0 until line.length()) {
                    val targetName = line.optJSONObject(i)?.optString("node") ?: continue
                    val targetId = nameToId[targetName] ?: continue
                    val sourceNode = imported.nodes.firstOrNull { it.id == sourceId }
                    val branchName = if (sourceNode?.type == "IF") {
                        if (branch == 1) "false" else "true"
                    } else ""
                    imported.edges.add(FlowEdge(sourceId, targetId, branchName))
                }
            }
        }

        return imported
    }

    private fun mapN8nType(type: String): String {
        val value = type.lowercase(Locale.US)
        return when {
            "manualtrigger" in value -> "Manual Trigger"
            "scheduletrigger" in value -> "Schedule Trigger"
            "webhook" in value -> "Webhook Trigger"
            "httprequest" in value -> "HTTP Request"
            "graphq" in value -> "GraphQL"
            ".set" in value || "editfields" in value -> "Edit Fields"
            ".if" in value -> "IF"
            ".switch" in value -> "Switch"
            ".wait" in value -> "Wait"
            ".code" in value -> "Code"
            "respondtowebhook" in value -> "Respond to Webhook"
            "executeworkflow" in value || "subworkflow" in value -> "Execute Sub-workflow"
            else -> "Generic API"
        }
    }

    private fun showCredentials() {
        val names = CredentialVault.list(this)
        val list = ListView(this)

        if (names.isEmpty()) {
            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                listOf("No saved credentials yet.")
            )
        } else {
            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                names
            )
            list.setOnItemClickListener { _, _, position, _ ->
                val name = names[position]
                AlertDialog.Builder(this)
                    .setTitle("Delete credential?")
                    .setMessage("Delete " + name + " from secure storage?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        CredentialVault.delete(this, name)
                        showCredentials()
                    }
                    .show()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Saved credentials")
            .setView(list)
            .setNegativeButton("Close", null)
            .setPositiveButton("Add") { _, _ ->
                showAddCredential()
            }
            .show()
    }

    private fun showAddCredential() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }

        val name = EditText(this).apply {
            hint = "Credential name"
            setSingleLine(true)
        }
        val value = EditText(this).apply {
            hint = "Secret / API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        box.addView(name)
        box.addView(value)

        AlertDialog.Builder(this)
            .setTitle("Add credential")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val credentialName = name.text.toString().trim()
                val secret = value.text.toString()
                if (credentialName.isBlank() || secret.isBlank()) {
                    Toast.makeText(this, "Both fields are required.", Toast.LENGTH_LONG).show()
                } else {
                    CredentialVault.put(this, credentialName, secret)
                    Toast.makeText(this, "Credential saved.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showHelp() {
        val help = """
NATEN is a mobile-first workflow automation engine inspired by n8n.

Core controls:
• + Add Node opens the searchable node library.
• Tap a node to configure it.
• Drag a node to move it.
• Drag from the right output dot to another node's left input dot to connect.
• IF and Filter connections can be TRUE/FALSE.
• Switch connections can use named routes.
• Run executes the graph on the phone.
• Active turns on background automation.

Background:
• Schedule Trigger workflows use Android alarms.
• Webhook/Chat workflows can keep an Android foreground service listening on port 8787.
• The phone OS can still defer or restrict background work according to its power-management rules.

Expressions:
Use JSON fields, workflow variables and current-time expressions in node parameters.

Integrations:
Generic API/HTTP/GraphQL nodes are the universal escape hatch for services that don't have a dedicated adapter. Dedicated integration buttons currently use the same API execution layer.
""".trimIndent()

        AlertDialog.Builder(this)
            .setTitle("NATEN help")
            .setMessage(help.replace("$" , ""))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    private fun refreshUi() {
        nameView.text = state.name

        activeSwitch.setOnCheckedChangeListener(null)
        activeSwitch.isChecked = state.active
        activeSwitch.setOnCheckedChangeListener { _, checked ->
            state.active = checked
            WorkflowStore.save(this, state)
            syncAutomation()
        }

        statusView.text = if (state.active) "Automation active" else "Ready"
        logView.text =
            state.nodes.size.toString() + " nodes • " +
                state.edges.size + " connections"
        canvas.invalidate()
    }

    private fun setStatus(status: String, detail: String) {
        statusView.text = status
        logLines.add(detail)
        while (logLines.size > 7) logLines.removeAt(0)
        logView.text = logLines.joinToString("\n")
    }

    private fun toolbarButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.rgb(35, 40, 48))
        background = rounded(Color.WHITE, 12f, Color.rgb(211, 216, 224), 1)
        setPadding(dp(10), 0, dp(10), 0)
        minHeight = dp(38)
        minimumHeight = dp(38)
        layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply {
            marginStart = dp(5)
        }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int, width: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke(width, stroke)
        }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun dpF(v: Int): Float =
        v * resources.displayMetrics.density

    private fun dpF(v: Float): Float =
        v * resources.displayMetrics.density


    inner class FrameLikeScroll(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)
            for (i in 0 until childCount) {
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            if (childCount == 0) return
            getChildAt(0).layout(0, 0, r - l, b - t)
        }
    }

    inner class WorkflowCanvas(context: Context) : View(context) {
        val nodes get() = state.nodes
        var selectedId: Int? = null

        private val nodeW = dpF(182)
        private val nodeH = dpF(92)
        private var dragId: Int? = null
        private var connectId: Int? = null
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var tempX = 0f
        private var tempY = 0f

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(222, 226, 233)
        }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(149, 158, 171)
            strokeWidth = dpF(2.4f)
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(244, 246, 249))
            drawGrid(c)

            state.edges.forEach { edge ->
                val a = state.nodes.firstOrNull { it.id == edge.from }
                val b = state.nodes.firstOrNull { it.id == edge.to }
                if (a != null && b != null) drawEdge(c, a, b, edge.branch)
            }

            if (connectId != null) {
                val a = state.nodes.firstOrNull { it.id == connectId }
                if (a != null) {
                    val p = Path()
                    val sx = a.x + nodeW
                    val sy = a.y + nodeH / 2
                    p.moveTo(sx, sy)
                    val mid = (sx + tempX) / 2
                    p.cubicTo(mid, sy, mid, tempY, tempX, tempY)
                    edgePaint.color = Color.rgb(70, 103, 214)
                    c.drawPath(p, edgePaint)
                    edgePaint.color = Color.rgb(149, 158, 171)
                }
            }

            state.nodes.sortedBy { it.id }.forEach { drawNode(c, it) }
        }

        private fun drawGrid(c: Canvas) {
            val step = dpF(24)
            var x = 0f
            while (x < width) {
                var y = 0f
                while (y < height) {
                    c.drawCircle(x, y, dpF(1.0f), gridPaint)
                    y += step
                }
                x += step
            }
        }

        private fun drawEdge(c: Canvas, a: FlowNode, b: FlowNode, branch: String) {
            val sx = a.x + nodeW
            val sy = a.y + nodeH / 2
            val ex = b.x
            val ey = b.y + nodeH / 2
            val p = Path()
            p.moveTo(sx, sy)
            val mid = (sx + ex) / 2
            p.cubicTo(mid, sy, mid, ey, ex, ey)

            edgePaint.color = when {
                a.type == "IF" && branch.equals("true", true) -> Color.rgb(40, 150, 99)
                a.type == "IF" && branch.equals("false", true) -> Color.rgb(205, 80, 79)
                else -> Color.rgb(149, 158, 171)
            }
            c.drawPath(p, edgePaint)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = edgePaint.color
                style = Paint.Style.FILL
            }
            val arrow = Path().apply {
                moveTo(ex, ey)
                lineTo(ex - dpF(7), ey - dpF(4))
                lineTo(ex - dpF(7), ey + dpF(4))
                close()
            }
            c.drawPath(arrow, fill)

            if (branch.isNotBlank()) {
                smallPaint.color = edgePaint.color
                smallPaint.textSize = dpF(10)
                c.drawText(branch, (sx + ex) / 2, (sy + ey) / 2 - dpF(5), smallPaint)
            }
        }

        private fun drawNode(c: Canvas, n: FlowNode) {
            val rect = RectF(n.x, n.y, n.x + nodeW, n.y + nodeH)
            val accent = accent(n.type)

            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(
                    if (selectedId == n.id) dpF(10) else dpF(5),
                    0f,
                    dpF(3),
                    Color.argb(45, 0, 0, 0)
                )
            }
            c.drawRoundRect(rect, dpF(15), dpF(15), shadow)

            c.drawRoundRect(
                RectF(n.x, n.y, n.x + dpF(7), n.y + nodeH),
                dpF(15),
                dpF(15),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            )

            textPaint.color = Color.rgb(27, 31, 38)
            textPaint.textSize = dpF(14.5f)
            textPaint.typeface = Typeface.DEFAULT_BOLD
            c.drawText(n.type, n.x + dpF(16), n.y + dpF(27), textPaint)

            smallPaint.color = Color.rgb(91, 98, 109)
            smallPaint.textSize = dpF(11.2f)
            drawEllipsized(c, n.title, n.x + dpF(16), n.y + dpF(50), nodeW - dpF(31), smallPaint)

            smallPaint.color = Color.rgb(140, 146, 156)
            smallPaint.textSize = dpF(9.5f)
            c.drawText("#" + n.id, n.x + dpF(16), n.y + dpF(73), smallPaint)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = dpF(2)
            }

            val cy = n.y + nodeH / 2
            c.drawCircle(n.x, cy, dpF(6), fill)
            c.drawCircle(n.x, cy, dpF(6), outline)
            c.drawCircle(n.x + nodeW, cy, dpF(6), fill)
            c.drawCircle(n.x + nodeW, cy, dpF(6), outline)

            if (selectedId == n.id) {
                c.drawRoundRect(
                    rect,
                    dpF(15),
                    dpF(15),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(78, 104, 210)
                        style = Paint.Style.STROKE
                        strokeWidth = dpF(2)
                    }
                )
            }
        }

        private fun drawEllipsized(
            c: Canvas,
            value: String,
            x: Float,
            y: Float,
            maxWidth: Float,
            paint: Paint
        ) {
            if (paint.measureText(value) <= maxWidth) {
                c.drawText(value, x, y, paint)
                return
            }
            var text = value
            while (text.length > 1 && paint.measureText(text + "…") > maxWidth) {
                text = text.dropLast(1)
            }
            c.drawText(text + "…", x, y, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    val hit = findNode(event.x, event.y)

                    if (hit != null && isOutput(hit, event.x, event.y)) {
                        connectId = hit.id
                        tempX = event.x
                        tempY = event.y
                        selectedId = hit.id
                        invalidate()
                        return true
                    }

                    dragId = hit?.id
                    selectedId = hit?.id
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (connectId != null) {
                        tempX = event.x
                        tempY = event.y
                        invalidate()
                        return true
                    }

                    val id = dragId ?: return true
                    val node = state.nodes.firstOrNull { it.id == id } ?: return true

                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) + abs(dy) > dpF(5)) moved = true

                    node.x = (node.x + dx).coerceIn(0f, (width - nodeW).coerceAtLeast(0f))
                    node.y = (node.y + dy).coerceIn(0f, (height - nodeH).coerceAtLeast(0f))

                    downX = event.x
                    downY = event.y
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (connectId != null) {
                        val source = state.nodes.firstOrNull { it.id == connectId }
                        connectId = null

                        val target = findInput(event.x, event.y)
                        if (source != null && target != null) {
                            connectNodes(source, target)
                        } else {
                            setStatus("Connection cancelled", "Drop on an input dot.")
                        }

                        invalidate()
                        return true
                    }

                    val selected = state.nodes.firstOrNull { it.id == dragId }
                    if (!moved && selected != null) {
                        showNodeEditor(selected)
                    } else {
                        WorkflowStore.save(this@MainActivity, state)
                    }

                    dragId = null
                    invalidate()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun isOutput(node: FlowNode, x: Float, y: Float): Boolean {
            val hx = node.x + nodeW
            val hy = node.y + nodeH / 2
            return distance(x, y, hx, hy) <= dpF(18)
        }

        private fun findInput(x: Float, y: Float): FlowNode? =
            state.nodes.asReversed().firstOrNull {
                distance(x, y, it.x, it.y + nodeH / 2) <= dpF(22)
            }

        private fun findNode(x: Float, y: Float): FlowNode? =
            state.nodes.asReversed().firstOrNull {
                x >= it.x && x <= it.x + nodeW &&
                    y >= it.y && y <= it.y + nodeH
            }

        private fun distance(a: Float, b: Float, c: Float, d: Float): Float {
            val dx = a - c
            val dy = b - d
            return sqrt(dx * dx + dy * dy)
        }

        private fun accent(type: String): Int = when (type) {
            "Manual Trigger", "Schedule Trigger", "Webhook Trigger", "Chat Trigger", "Error Trigger" ->
                Color.rgb(55, 109, 232)
            "HTTP Request", "Generic API", "GraphQL" -> Color.rgb(26, 137, 131)
            "IF", "Filter", "Switch" -> Color.rgb(214, 139, 36)
            "AI Text", "AI Agent" -> Color.rgb(133, 76, 199)
            "Wait", "Limit", "Loop Over Items" -> Color.rgb(110, 118, 131)
            "Notification", "Open URL", "Share Text" -> Color.rgb(31, 150, 104)
            "Stop / Error" -> Color.rgb(205, 77, 78)
            else -> Color.rgb(91, 102, 118)
        }
    }

    companion object {
        private const val REQ_IMPORT = 7401
        private const val REQ_EXPORT = 7402
        private const val REQ_NOTIFICATIONS = 7403
    }
}
