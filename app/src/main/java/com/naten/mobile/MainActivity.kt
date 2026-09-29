package com.naten.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var canvas: WorkflowCanvas
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var nameView: TextView
    private lateinit var activeSwitch: Switch

    private val prefs by lazy { getSharedPreferences("naten", MODE_PRIVATE) }
    private var state = WorkflowState()
    private var logLines = mutableListOf<String>()
    private val engine by lazy { WorkflowEngine(this) }

    private data class PaletteItem(val type: String, val title: String, val hint: String)

    private val paletteItems = listOf(
        PaletteItem("Manual Trigger", "Manual", "Start"),
        PaletteItem("Schedule Trigger", "Schedule", "Timer"),
        PaletteItem("Webhook Trigger", "Webhook", "HTTP"),
        PaletteItem("HTTP Request", "HTTP", "API"),
        PaletteItem("Edit Fields", "Set", "Data"),
        PaletteItem("IF", "IF", "Branch"),
        PaletteItem("Switch", "Switch", "Routes"),
        PaletteItem("Wait", "Wait", "Delay"),
        PaletteItem("AI Text", "AI", "LLM"),
        PaletteItem("Code", "Code", "Transform"),
        PaletteItem("Notification", "Notify", "Phone"),
        PaletteItem("Open URL", "Open URL", "Browser"),
        PaletteItem("Share Text", "Share", "Android"),
        PaletteItem("Read File", "Read File", "Storage"),
        PaletteItem("Write File", "Write File", "Storage"),
        PaletteItem("Set Variable", "Variable", "State"),
        PaletteItem("Log", "Log", "Debug"),
        PaletteItem("Stop / Error", "Stop", "End"),
        PaletteItem("Merge", "Merge", "Flow")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = WorkflowJson.load(this)
        state = saved ?: starterWorkflow()
        state.nodes.forEach { it.ensureDefaultConfig() }
        buildUi()
        refreshUi()
        WorkflowJson.save(this, state)
        requestNotificationPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        nameView = TextView(this).apply {
            text = state.name
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 28, 34))
            setPadding(0, 0, dp(6), 0)
            isClickable = true
            setOnClickListener { renameWorkflow() }
        }
        top.addView(nameView, LinearLayout.LayoutParams(0, dp(44), 1f))

        activeSwitch = Switch(this).apply {
            text = "Active"
            textSize = 12f
            isChecked = state.active
            setOnCheckedChangeListener { _, checked ->
                state.active = checked
                WorkflowJson.save(this@MainActivity, state)
                if (checked) {
                    val schedule = WorkflowScheduler.schedule(this@MainActivity, state)
                    setStatus("Automation active", schedule ?: "Add a Schedule Trigger to run in background.")
                } else {
                    WorkflowScheduler.cancel(this@MainActivity)
                    setStatus("Automation inactive", "Saved workflow will still run from Run.")
                }
            }
        }
        top.addView(activeSwitch)

        toolbar.addView(top)

        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val save = toolbarButton("Save")
        val run = toolbarButton("Run")
        val history = toolbarButton("History")
        val more = toolbarButton("More")

        save.setOnClickListener {
            WorkflowJson.save(this, state)
            if (state.active) WorkflowScheduler.schedule(this, state)
            setStatus("Saved", "Workflow stored on this phone.")
        }
        run.setOnClickListener { runWorkflow() }
        history.setOnClickListener { showHistory() }
        more.setOnClickListener { showMoreMenu() }

        buttons.addView(save)
        buttons.addView(run)
        buttons.addView(history)
        buttons.addView(more)
        toolbar.addView(buttons)

        val paletteScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        val palette = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        paletteItems.forEach { item ->
            val b = Button(this).apply {
                text = "+" + item.title
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.rgb(35, 40, 48))
                background = rounded(Color.WHITE, 12f, Color.rgb(211, 216, 224), 1)
                setPadding(dp(10), 0, dp(10), 0)
                minHeight = dp(38)
                minimumHeight = dp(38)
                layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply {
                    marginEnd = dp(6)
                }
                contentDescription = item.type + " — " + item.hint
            }
            b.setOnClickListener { addNode(item.type) }
            palette.addView(b)
        }

        paletteScroll.addView(palette)
        toolbar.addView(paletteScroll)
        root.addView(toolbar)

        canvas = WorkflowCanvas(this)
        root.addView(
            canvas,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 44, 52))
        }

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(86, 92, 102))
            setPadding(0, dp(3), 0, 0)
            maxLines = 6
        }

        bottom.addView(statusView)
        bottom.addView(logView)
        root.addView(bottom)

        setContentView(root)
    }

    private fun addNode(type: String) {
        val id = nextId()
        val index = state.nodes.size
        val x = dpF(24 + (index % 2) * 198)
        val y = dpF(22 + (index / 2) * 120)
        val title = defaultTitle(type)
        val node = FlowNode(id, type, title, x, y)
        node.ensureDefaultConfig()
        state.nodes.add(node)
        canvas.selectedId = id
        canvas.invalidate()
        WorkflowJson.save(this, state)
        setStatus("Added " + title, "Tap to configure. Drag from a node's output dot to another node.")
        showNodeEditor(node)
    }

    private fun nextId(): Int = (state.nodes.maxOfOrNull { it.id } ?: 0) + 1

    private fun starterWorkflow(): WorkflowState {
        val s = WorkflowState(name = "NATEN Starter")
        val trigger = FlowNode(1, "Manual Trigger", "Press Run", dpF(24), dpF(20))
        val set = FlowNode(2, "Edit Fields", "Create message", dpF(24), dpF(132))
        set.config.put("fields", "message=Hello from NATEN")
        val notify = FlowNode(3, "Notification", "Show phone notification", dpF(24), dpF(244))
        notify.config.put("title", "NATEN")
        notify.config.put("message", "{{\$json.message}}")
        val log = FlowNode(4, "Log", "Write execution log", dpF(24), dpF(356))
        log.config.put("message", "{{\$json}}")
        s.nodes.addAll(listOf(trigger, set, notify, log))
        s.edges.addAll(
            listOf(
                FlowEdge(1, 2),
                FlowEdge(2, 3),
                FlowEdge(3, 4)
            )
        )
        return s
    }

    private fun defaultTitle(type: String): String = when (type) {
        "Manual Trigger" -> "When I press Run"
        "Schedule Trigger" -> "Every 60 minutes"
        "Webhook Trigger" -> "Receive HTTP"
        "HTTP Request" -> "GET request"
        "Edit Fields" -> "Set message"
        "IF" -> "Check value"
        "Switch" -> "Route by value"
        "Wait" -> "Wait 2 seconds"
        "AI Text" -> "Ask AI"
        "Code" -> "Transform text"
        "Notification" -> "Send notification"
        "Open URL" -> "Open a page"
        "Share Text" -> "Share text"
        "Read File" -> "Read a file"
        "Write File" -> "Write a file"
        "Set Variable" -> "Set a variable"
        "Log" -> "Write to log"
        "Stop / Error" -> "Stop workflow"
        "Merge" -> "Join paths"
        else -> type
    }

    private fun renameWorkflow() {
        val field = editField("Workflow name", state.name)
        AlertDialog.Builder(this)
            .setTitle("Workflow name")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                state.name = field.text.toString().trim().ifBlank { "My Workflow" }
                nameView.text = state.name
                WorkflowJson.save(this, state)
            }
            .show()
    }

    private fun runWorkflow() {
        if (state.nodes.isEmpty()) {
            setStatus("Nothing to run", "Add a trigger and at least one action.")
            return
        }

        WorkflowJson.save(this, state)
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

                override fun onFinished(success: Boolean, message: String) {
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

    private fun showNodeEditor(node: FlowNode) {
        node.ensureDefaultConfig()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val titleField = editField("Node name", node.title)
        form.addView(titleField)

        var methodSpinner: Spinner? = null
        var providerSpinner: Spinner? = null
        var unitSpinner: Spinner? = null
        var operatorSpinner: Spinner? = null
        var operationSpinner: Spinner? = null

        fun addLabel(text: String) {
            form.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.rgb(96, 102, 112))
                setPadding(0, dp(9), 0, dp(3))
            })
        }

        fun addText(label: String, key: String, multi: Boolean = false, password: Boolean = false): EditText {
            addLabel(label)
            val e = editField(label, node.config.optString(key, ""))
            if (multi) {
                e.minLines = 4
                e.gravity = Gravity.TOP
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            if (password) {
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            form.addView(e)
            e.setTag(key)
            return e
        }

        fun addSpinner(label: String, key: String, options: List<String>): Spinner {
            addLabel(label)
            val s = Spinner(this)
            s.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                options
            )
            val current = node.config.optString(key)
            val index = options.indexOf(current).coerceAtLeast(0)
            s.setSelection(index)
            form.addView(s)
            return s
        }

        val extraFields = mutableListOf<Pair<String, EditText>>()

        when (node.type) {
            "Manual Trigger", "Webhook Trigger" -> {
                addLabel(if (node.type == "Webhook Trigger") {
                    "This trigger is prepared for a local HTTP webhook workflow."
                } else {
                    "This trigger starts when Run is pressed, or when another node targets it."
                })
            }

            "Schedule Trigger" -> {
                val i = addText("Interval", "interval")
                i.inputType = InputType.TYPE_CLASS_NUMBER
                unitSpinner = addSpinner(
                    "Unit",
                    "unit",
                    listOf("seconds", "minutes", "hours", "days")
                )
                addLabel("Android may throttle very short background intervals.")
            }

            "HTTP Request" -> {
                methodSpinner = addSpinner("Method", "method", listOf("GET", "POST", "PUT", "PATCH", "DELETE"))
                extraFields.add("url" to addText("URL", "url"))
                extraFields.add("headers" to addText("Headers", "headers", multi = true))
                extraFields.add("body" to addText("Body", "body", multi = true))
            }

            "Edit Fields" -> {
                extraFields.add(
                    "fields" to addText(
                        "Fields (one per line: key=value)",
                        "fields",
                        multi = true
                    )
                )
                addLabel("Use {{\$json.field}}, {{\$vars.name}} and {{\$now}} in values.")
            }

            "IF" -> {
                extraFields.add("field" to addText("Field / JSON path", "field"))
                operatorSpinner = addSpinner(
                    "Operator",
                    "operator",
                    listOf(
                        "equals",
                        "not equals",
                        "contains",
                        "starts with",
                        "ends with",
                        "greater than",
                        "less than",
                        "exists",
                        "not exists"
                    )
                )
                extraFields.add("value" to addText("Compare with", "value"))
                addLabel("Outputs split into TRUE and FALSE. Connect each branch separately.")
            }

            "Switch" -> {
                extraFields.add("field" to addText("Field / JSON path", "field"))
                extraFields.add(
                    "cases" to addText(
                        "Cases (one per line)",
                        "cases",
                        multi = true
                    )
                )
                addLabel("When you connect from Switch, choose the case label for that connection.")
            }

            "Wait" -> {
                val s = addText("Seconds", "seconds")
                s.inputType = InputType.TYPE_CLASS_NUMBER
            }

            "AI Text" -> {
                providerSpinner = addSpinner(
                    "Provider",
                    "provider",
                    listOf("OpenAI-compatible", "Gemini")
                )
                extraFields.add("endpoint" to addText("Endpoint", "endpoint"))
                extraFields.add("apiKey" to addText("API key", "apiKey", password = true))
                extraFields.add("model" to addText("Model", "model"))
                extraFields.add("prompt" to addText("Prompt", "prompt", multi = true))
                extraFields.add("temperature" to addText("Temperature", "temperature"))
                addLabel("AI calls are direct from the phone. The key is stored locally in this workflow.")
            }

            "Code" -> {
                operationSpinner = addSpinner(
                    "Safe transform",
                    "operation",
                    listOf("uppercase", "lowercase", "length", "reverse", "trim", "set value")
                )
                extraFields.add("field" to addText("Input field", "field"))
                extraFields.add("outputField" to addText("Output field", "outputField"))
                extraFields.add("value" to addText("Value (for set value)", "value"))
            }

            "Notification" -> {
                extraFields.add("title" to addText("Title", "title"))
                extraFields.add("message" to addText("Message", "message", multi = true))
            }

            "Open URL" -> extraFields.add("url" to addText("URL", "url"))
            "Share Text" -> extraFields.add("text" to addText("Text", "text", multi = true))
            "Read File" -> extraFields.add("filename" to addText("Filename in NATEN storage", "filename"))
            "Write File" -> {
                extraFields.add("filename" to addText("Filename in NATEN storage", "filename"))
                extraFields.add("data" to addText("Data to write", "data", multi = true))
            }
            "Set Variable" -> {
                extraFields.add("key" to addText("Variable name", "key"))
                extraFields.add("value" to addText("Variable value", "value", multi = true))
            }
            "Log" -> extraFields.add("message" to addText("Message", "message", multi = true))
            "Stop / Error" -> extraFields.add("message" to addText("Stop / error message", "message", multi = true))
            "Merge" -> addLabel("Merge joins incoming paths at this node.")
        }

        val scroll = ScrollView(this).apply {
            addView(form)
        }

        AlertDialog.Builder(this)
            .setTitle(node.type)
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                deleteNode(node)
            }
            .setPositiveButton("Save") { _, _ ->
                node.title = titleField.text.toString().trim().ifBlank { defaultTitle(node.type) }

                extraFields.forEach { pair ->
                    node.config.put(pair.first, pair.second.text.toString())
                }
                methodSpinner?.let {
                    node.config.put("method", it.selectedItem.toString())
                }
                providerSpinner?.let {
                    node.config.put("provider", it.selectedItem.toString())
                }
                unitSpinner?.let {
                    node.config.put("unit", it.selectedItem.toString())
                }
                operatorSpinner?.let {
                    node.config.put("operator", it.selectedItem.toString())
                }
                operationSpinner?.let {
                    node.config.put("operation", it.selectedItem.toString())
                }

                WorkflowJson.save(this, state)
                if (state.active) WorkflowScheduler.schedule(this, state)
                canvas.invalidate()
                setStatus("Updated " + node.title, "Changes saved to this device.")
            }
            .show()
    }

    private fun deleteNode(node: FlowNode) {
        state.nodes.removeAll { it.id == node.id }
        state.edges.removeAll { it.from == node.id || it.to == node.id }
        if (state.nodes.none { it.type == "Schedule Trigger" }) {
            WorkflowScheduler.cancel(this)
        }
        WorkflowJson.save(this, state)
        canvas.selectedId = null
        canvas.invalidate()
        setStatus("Node deleted", "Connections to the node were removed.")
    }

    private fun connectNodes(from: FlowNode, to: FlowNode) {
        if (from.id == to.id) return

        fun saveEdge(branch: String) {
            state.edges.removeAll { it.from == from.id && it.to == to.id && it.branch == branch }
            state.edges.add(FlowEdge(from.id, to.id, branch))
            WorkflowJson.save(this, state)
            canvas.invalidate()
            setStatus("Connected", from.title + " → " + to.title + if (branch.isNotBlank()) " [" + branch + "]" else "")
        }

        when (from.type) {
            "IF" -> AlertDialog.Builder(this)
                .setTitle("Choose branch")
                .setSingleChoiceItems(arrayOf("true", "false"), 0) { dialog, which ->
                    saveEdge(if (which == 0) "true" else "false")
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()

            "Switch" -> {
                from.ensureDefaultConfig()
                val values = from.config.optString("cases")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toMutableList()
                values.add("default")
                AlertDialog.Builder(this)
                    .setTitle("Choose route")
                    .setItems(values.toTypedArray()) { _, which ->
                        saveEdge(values[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            else -> saveEdge("")
        }
    }

    private fun setStatus(status: String, detail: String) {
        statusView.text = status
        logLines.add(detail)
        while (logLines.size > 7) logLines.removeAt(0)
        logView.text = logLines.joinToString("\n")
    }

    private fun showHistory() {
        val arr = WorkflowJson.history(this)
        if (arr.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle("Execution history")
                .setMessage("No executions yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val lines = ArrayList<String>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            val mark = if (o.optBoolean("success")) "OK" else "FAIL"
            lines.add(
                mark + "  " +
                    o.optString("workflow") +
                    "\\n" +
                    o.optString("message") +
                    "  •  " +
                    o.optLong("durationMs") +
                    " ms"
            )
        }

        val text = TextView(this).apply {
            setTextColor(Color.rgb(48, 54, 62))
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            this.text = lines.take(30).joinToString("\\n\\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Execution history")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            "New workflow",
            "Import workflow JSON",
            "Export workflow JSON",
            "Export n8n-style JSON",
            "Help"
        )

        AlertDialog.Builder(this)
            .setTitle("Workflow")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> newWorkflow()
                    1 -> importJson()
                    2 -> exportJson(false)
                    3 -> exportJson(true)
                    4 -> showHelp()
                }
            }
            .show()
    }

    private fun newWorkflow() {
        AlertDialog.Builder(this)
            .setTitle("New workflow")
            .setMessage("Clear the current workflow and start from an empty canvas?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                WorkflowScheduler.cancel(this)
                state = WorkflowState()
                logLines.clear()
                nameView.text = state.name
                activeSwitch.isChecked = false
                canvas.invalidate()
                WorkflowJson.save(this, state)
                setStatus("New workflow", "Canvas cleared.")
            }
            .show()
    }

    private fun exportJson(n8nStyle: Boolean) {
        val content = if (n8nStyle) toN8nJson().toString(2) else WorkflowJson.toJson(state).toString(2)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, state.name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
        }
        pendingExport = content
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
            contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(value) }
            }
            setStatus("Exported", "Workflow JSON written.")
        } else if (requestCode == REQ_IMPORT) {
            val raw = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            } ?: return

            runCatching {
                val root = JSONObject(raw)
                state = if (root.optString("format") == "naten-v1") {
                    WorkflowJson.fromJson(root)
                } else {
                    importN8nJson(root)
                }
                state.nodes.forEach { it.ensureDefaultConfig() }
                WorkflowJson.save(this, state)
                if (state.active) WorkflowScheduler.schedule(this, state) else WorkflowScheduler.cancel(this)
                nameView.text = state.name
                activeSwitch.setOnCheckedChangeListener(null)
                activeSwitch.isChecked = state.active
                activeSwitch.setOnCheckedChangeListener { _, checked ->
                    state.active = checked
                    WorkflowJson.save(this, state)
                    if (checked) WorkflowScheduler.schedule(this, state) else WorkflowScheduler.cancel(this)
                }
                canvas.invalidate()
                setStatus("Imported", "Loaded " + state.nodes.size + " node(s).")
            }.onFailure {
                setStatus("Import failed", it.message ?: "Invalid JSON")
            }
        }
    }

    private fun toN8nJson(): JSONObject {
        val root = JSONObject()
            .put("name", state.name)
            .put("active", state.active)
            .put("settings", JSONObject())
            .put("connections", JSONObject())

        val nodes = JSONArray()
        state.nodes.forEach { n ->
            n.ensureDefaultConfig()
            nodes.put(
                JSONObject()
                    .put("id", n.id.toString())
                    .put("name", n.title)
                    .put("type", n8nType(n.type))
                    .put("typeVersion", 1)
                    .put("position", JSONArray().put(n.x).put(n.y))
                    .put("parameters", JSONObject(n.config.toString()))
            )
        }
        root.put("nodes", nodes)

        val connections = JSONObject()
        state.edges.forEach { e ->
            val from = state.nodes.firstOrNull { it.id == e.from } ?: return@forEach
            val to = state.nodes.firstOrNull { it.id == e.to } ?: return@forEach

            val branchIndex = when {
                from.type == "IF" && e.branch.equals("false", true) -> 1
                else -> 0
            }

            val fromConnections = connections.optJSONObject(from.title) ?: JSONObject()
            val main = fromConnections.optJSONArray("main") ?: JSONArray()
            while (main.length() <= branchIndex) main.put(JSONArray())
            val line = main.optJSONArray(branchIndex) ?: JSONArray()
            line.put(
                JSONObject()
                    .put("node", to.title)
                    .put("type", "main")
                    .put("index", 0)
            )
            main.put(branchIndex, line)
            fromConnections.put("main", main)
            connections.put(from.title, fromConnections)
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
        else -> "n8n-nodes-base.noOp"
    }

    private fun importN8nJson(root: JSONObject): WorkflowState {
        val out = WorkflowState(
            name = root.optString("name", "Imported Workflow"),
            active = root.optBoolean("active", false)
        )
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        val names = mutableMapOf<String, Int>()

        for (i in 0 until nodes.length()) {
            val o = nodes.getJSONObject(i)
            val id = o.optString("id").toIntOrNull() ?: i + 1
            val mapped = mapN8nType(o.optString("type"))
            val position = o.optJSONArray("position") ?: JSONArray()
            val cfg = o.optJSONObject("parameters") ?: JSONObject()
            val node = FlowNode(
                id = id,
                type = mapped,
                title = o.optString("name", mapped),
                x = position.optDouble(0, 24.0).toFloat(),
                y = position.optDouble(1, 24.0).toFloat(),
                config = JSONObject(cfg.toString())
            )
            node.ensureDefaultConfig()
            out.nodes.add(node)
            names[node.title] = node.id
        }

        val connections = root.optJSONObject("connections") ?: JSONObject()
        val keys = connections.keys()
        while (keys.hasNext()) {
            val sourceName = keys.next()
            val sourceId = names[sourceName] ?: continue
            val sourceObj = connections.optJSONObject(sourceName) ?: continue
            val main = sourceObj.optJSONArray("main") ?: continue

            for (branch in 0 until main.length()) {
                val line = main.optJSONArray(branch) ?: continue
                for (i in 0 until line.length()) {
                    val targetName = line.optJSONObject(i)?.optString("node") ?: continue
                    val targetId = names[targetName] ?: continue
                    val branchLabel = if (out.nodes.firstOrNull { it.id == sourceId }?.type == "IF") {
                        if (branch == 1) "false" else "true"
                    } else ""
                    out.edges.add(FlowEdge(sourceId, targetId, branchLabel))
                }
            }
        }

        return out
    }

    private fun mapN8nType(value: String): String {
        val t = value.lowercase(Locale.US)
        return when {
            "manualtrigger" in t -> "Manual Trigger"
            "scheduletrigger" in t -> "Schedule Trigger"
            "webhook" in t -> "Webhook Trigger"
            "httprequest" in t -> "HTTP Request"
            ".set" in t || "editfields" in t -> "Edit Fields"
            ".if" in t -> "IF"
            ".switch" in t -> "Switch"
            ".wait" in t -> "Wait"
            ".code" in t -> "Code"
            else -> "Log"
        }
    }

    private fun showHelp() {
        val help = """
NATEN is a mobile workflow engine inspired by n8n's node-and-connection model.

Create nodes from the palette.
Tap a node to configure it.
Drag a node to move it.
Drag from the output dot on the right to another node to create a connection.
IF connections ask for true/false. Switch connections ask for a route.

Run executes the connected graph on the phone.
HTTP Request performs real network calls.
AI Text can call Gemini or OpenAI-compatible endpoints.
Wait, files, variables, notifications, sharing and URL actions are executed by Android.
The Active switch schedules Schedule Trigger workflows using Android alarms.

Expressions supported: JSON fields, workflow variables, and current time.

Files are stored in this app's private NATEN storage area.
""".trimIndent()

        AlertDialog.Builder(this)
            .setTitle("NATEN help")
            .setMessage(help)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toolbarButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.rgb(35, 40, 48))
        background = rounded(Color.WHITE, 12f, Color.rgb(211, 216, 224), 1)
        setPadding(dp(11), 0, dp(11), 0)
        minHeight = dp(40)
        minimumHeight = dp(40)
        layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply {
            marginStart = dp(6)
        }
    }

    private fun editField(label: String, value: String): EditText = EditText(this).apply {
        hint = label
        setText(value)
        textSize = 14f
        setSingleLine(false)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(Color.WHITE, 10f, Color.rgb(209, 214, 222), 1)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(3), 0, dp(5))
        }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int, width: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke(width, stroke)
        }

    private fun refreshUi() {
        nameView.text = state.name
        activeSwitch.isChecked = state.active
        statusView.text = if (state.active) "Automation active" else "Ready"
        logView.text = if (state.nodes.isEmpty()) {
            "Build a flow from the palette. Start with Manual or Schedule."
        } else {
            state.nodes.size.toString() + " node(s), " + state.edges.size + " connection(s)."
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Int): Float = v * resources.displayMetrics.density
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    inner class WorkflowCanvas(context: Context) : View(context) {
        val nodes = state.nodes
        var selectedId: Int? = null
        private val nodeW = dpF(178)
        private val nodeH = dpF(90)
        private var dragId: Int? = null
        private var connectId: Int? = null
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var tempX = 0f
        private var tempY = 0f

        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(224, 228, 235)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(149, 158, 171)
            strokeWidth = dpF(2.4f)
            style = Paint.Style.STROKE
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val small = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(245, 247, 250))
            drawGrid(c)

            state.edges.forEach { edge ->
                val a = state.nodes.firstOrNull { it.id == edge.from }
                val b = state.nodes.firstOrNull { it.id == edge.to }
                if (a != null && b != null) drawEdge(c, a, b, edge.branch)
            }

            if (connectId != null) {
                val a = state.nodes.firstOrNull { it.id == connectId }
                if (a != null) {
                    line.color = Color.rgb(74, 104, 218)
                    val startX = a.x + nodeW
                    val startY = a.y + nodeH / 2f
                    val p = Path()
                    p.moveTo(startX, startY)
                    val mid = (startX + tempX) / 2f
                    p.cubicTo(mid, startY, mid, tempY, tempX, tempY)
                    c.drawPath(p, line)
                    line.color = Color.rgb(149, 158, 171)
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
                    c.drawCircle(x, y, dpF(1.05f), grid)
                    y += step
                }
                x += step
            }
        }

        private fun drawEdge(c: Canvas, a: FlowNode, b: FlowNode, branch: String) {
            val startX = a.x + nodeW
            val startY = a.y + nodeH / 2f
            val endX = b.x
            val endY = b.y + nodeH / 2f

            val path = Path().apply {
                moveTo(startX, startY)
                val mid = (startX + endX) / 2f
                cubicTo(mid, startY, mid, endY, endX, endY)
            }
            line.color = if (a.type == "IF") {
                if (branch == "true") Color.rgb(35, 155, 102) else Color.rgb(211, 87, 85)
            } else Color.rgb(149, 158, 171)

            c.drawPath(path, line)

            val angle = Math.atan2((endY - (endY)).toDouble(), (endX - (endX - dpF(1))).toDouble())
            val tip = PointF(endX, endY)
            val size = dpF(6)
            val arrow = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - size, tip.y - size / 2)
                lineTo(tip.x - size, tip.y + size / 2)
                close()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = line.color
            }
            c.drawPath(arrow, fill)

            if (branch.isNotBlank()) {
                small.textSize = dpF(10)
                small.color = line.color
                val lx = (startX + endX) / 2f
                val ly = (startY + endY) / 2f - dpF(6)
                c.drawText(branch, lx, ly, small)
            }
        }

        private fun drawNode(c: Canvas, n: FlowNode) {
            val selected = selectedId == n.id
            val rect = RectF(n.x, n.y, n.x + nodeW, n.y + nodeH)

            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(
                    if (selected) dpF(9) else dpF(5),
                    0f,
                    dpF(3),
                    Color.argb(42, 0, 0, 0)
                )
            }
            c.drawRoundRect(rect, dpF(15), dpF(15), shadow)

            val accent = accentColor(n.type)
            val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            c.drawRoundRect(
                RectF(n.x, n.y, n.x + dpF(7), n.y + nodeH),
                dpF(15),
                dpF(15),
                stripe
            )

            text.color = Color.rgb(27, 31, 38)
            text.textSize = dpF(14.5f)
            text.typeface = Typeface.DEFAULT_BOLD
            c.drawText(n.type, n.x + dpF(16), n.y + dpF(27), text)

            small.color = Color.rgb(95, 102, 113)
            small.textSize = dpF(11.2f)
            drawEllipsized(c, n.title, n.x + dpF(16), n.y + dpF(50), nodeW - dpF(30))

            small.color = Color.rgb(135, 142, 153)
            small.textSize = dpF(9.5f)
            c.drawText("#" + n.id, n.x + dpF(16), n.y + dpF(72), small)

            val input = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = dpF(2)
            }

            c.drawCircle(n.x, n.y + nodeH / 2f, dpF(6), input)
            c.drawCircle(n.x, n.y + nodeH / 2f, dpF(6), outline)
            c.drawCircle(n.x + nodeW, n.y + nodeH / 2f, dpF(6), input)
            c.drawCircle(n.x + nodeW, n.y + nodeH / 2f, dpF(6), outline)

            if (selected) {
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(77, 103, 210)
                    style = Paint.Style.STROKE
                    strokeWidth = dpF(2)
                }
                c.drawRoundRect(rect, dpF(15), dpF(15), border)
            }
        }

        private fun drawEllipsized(c: Canvas, value: String, x: Float, y: Float, max: Float) {
            if (small.measureText(value) <= max) {
                c.drawText(value, x, y, small)
                return
            }
            var s = value
            while (s.length > 1 && small.measureText(s + "…") > max) s = s.dropLast(1)
            c.drawText(s + "…", x, y, small)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false

                    val hit = findNode(event.x, event.y)
                    if (hit != null && isOutputHandle(hit, event.x, event.y)) {
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

                    node.x = (node.x + dx).coerceIn(0f, width - nodeW)
                    node.y = (node.y + dy).coerceIn(0f, height - nodeH)
                    downX = event.x
                    downY = event.y
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (connectId != null) {
                        val fromId = connectId
                        connectId = null
                        val target = findInputTarget(event.x, event.y)
                        if (target != null) {
                            val from = state.nodes.firstOrNull { it.id == fromId }
                            if (from != null) connectNodes(from, target)
                        } else {
                            setStatus("Connection cancelled", "Drop on a target node's input dot.")
                        }
                        invalidate()
                        return true
                    }

                    val selected = state.nodes.firstOrNull { it.id == dragId }
                    if (!moved && selected != null) {
                        showNodeEditor(selected)
                    } else {
                        WorkflowJson.save(this@MainActivity, state)
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

        private fun isOutputHandle(n: FlowNode, x: Float, y: Float): Boolean {
            val hx = n.x + nodeW
            val hy = n.y + nodeH / 2f
            return distance(x, y, hx, hy) <= dpF(18)
        }

        private fun findInputTarget(x: Float, y: Float): FlowNode? {
            return state.nodes.asReversed().firstOrNull { n ->
                val hx = n.x
                val hy = n.y + nodeH / 2f
                distance(x, y, hx, hy) <= dpF(22)
            }
        }

        private fun findNode(x: Float, y: Float): FlowNode? =
            state.nodes.asReversed().firstOrNull {
                x >= it.x && x <= it.x + nodeW &&
                    y >= it.y && y <= it.y + nodeH
            }

        private fun distance(a: Float, b: Float, c: Float, d: Float): Float {
            val dx = a - c
            val dy = b - d
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private fun accentColor(type: String): Int = when (type) {
            "Manual Trigger", "Schedule Trigger", "Webhook Trigger" -> Color.rgb(58, 110, 232)
            "HTTP Request" -> Color.rgb(23, 131, 125)
            "Edit Fields" -> Color.rgb(96, 106, 221)
            "IF", "Switch" -> Color.rgb(213, 138, 37)
            "Wait" -> Color.rgb(115, 123, 135)
            "AI Text" -> Color.rgb(133, 76, 199)
            "Code" -> Color.rgb(54, 108, 177)
            "Notification", "Open URL", "Share Text" -> Color.rgb(29, 147, 102)
            "Read File", "Write File", "Set Variable" -> Color.rgb(106, 98, 167)
            "Log" -> Color.rgb(95, 103, 115)
            "Stop / Error" -> Color.rgb(205, 76, 77)
            else -> Color.rgb(92, 100, 112)
        }
    }

    companion object {
        private const val REQ_IMPORT = 7401
        private const val REQ_EXPORT = 7402
        private const val REQ_NOTIFICATIONS = 7403
    }
}
