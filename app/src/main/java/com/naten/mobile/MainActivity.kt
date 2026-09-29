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
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var editorFrame: FrameLayout
    private var nodePanel: View? = null

    private var state = WorkflowState()
    private val logLines = mutableListOf<String>()
    private val engine by lazy { WorkflowEngine(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching {
            state = WorkflowStore.loadCurrent(this) ?: starterWorkflow()
            state.nodes.forEach { it.ensureDefaultConfig() }

            buildUi()
            WorkflowStore.save(this, state)
            refreshUi()
            requestNotificationPermission()

            window.decorView.post {
                runCatching {
                    syncAutomation()
                }.onFailure {
                    setStatus("Automation paused", it.message ?: "Android blocked background startup")
                }
            }
        }.onFailure { error ->
            showStartupRecovery(error)
        }
    }

    private fun showStartupRecovery(error: Throwable) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(19, 20, 23))
        }

        root.addView(TextView(this).apply {
            text = "NATEN"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "NATEN could not load the workspace."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(224, 226, 231))
            setPadding(0, dp(14), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = (error.message ?: error.javaClass.simpleName).take(500)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(155, 159, 168))
        })

        val retry = n8nChromeButton("Restart workspace")
        retry.setOnClickListener {
            recreate()
        }
        root.addView(retry, LinearLayout.LayoutParams(-2, dp(44)).apply {
            topMargin = dp(20)
        })

        val reset = n8nChromeButton("Reset local workflow")
        reset.setOnClickListener {
            getSharedPreferences("naten", Context.MODE_PRIVATE)
                .edit()
                .remove("currentWorkflowId")
                .remove("workflow")
                .apply()
            recreate()
        }
        root.addView(reset, LinearLayout.LayoutParams(-2, dp(44)).apply {
            topMargin = dp(8)
        })

        setContentView(root)
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(14, 15, 17)
        window.navigationBarColor = Color.rgb(14, 15, 17)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(19, 20, 23))
        }

        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(workspace, FrameLayout.LayoutParams(-1, -1))

        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(10))
            setBackgroundColor(Color.rgb(14, 15, 17))
        }
        workspace.addView(sidebar, LinearLayout.LayoutParams(dp(178), -1))

        val brandRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(16))
        }
        brandRow.addView(TextView(this).apply {
            text = "n8n"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(42), -2))
        brandRow.addView(TextView(this).apply {
            text = "NATEN"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(150, 154, 163))
        })
        sidebar.addView(brandRow)

        fun nav(label: String, active: Boolean = false, action: (() -> Unit)? = null) {
            val item = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                setTextColor(if (active) Color.WHITE else Color.rgb(164, 168, 178))
                background = rounded(
                    if (active) Color.rgb(38, 40, 45) else Color.TRANSPARENT,
                    8f,
                    Color.TRANSPARENT,
                    0
                )
                isClickable = action != null
                setOnClickListener { action?.invoke() }
            }
            sidebar.addView(item, LinearLayout.LayoutParams(-1, dp(38)).apply {
                bottomMargin = dp(3)
            })
        }

        nav("⌂   Overview", action = { showMoreMenu() })
        nav("▦   Workflows", active = true, action = { showWorkflows() })
        nav("▤   Credentials", action = { showCredentials() })
        nav("◷   Executions", action = { showHistory() })
        nav("☆   Templates", action = {
            Toast.makeText(this, "Templates are on the roadmap for this editor pass.", Toast.LENGTH_SHORT).show()
        })
        nav("◇   Variables", action = {
            Toast.makeText(this, "Workflow variables are available through node configuration.", Toast.LENGTH_SHORT).show()
        })

        sidebar.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        nav("⚙   Settings", action = { showMoreMenu() })

        val editor = FrameLayout(this)
        editorFrame = editor
        workspace.addView(editor, LinearLayout.LayoutParams(0, -1, 1f))

        val topbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            setBackgroundColor(Color.rgb(29, 30, 33))
        }

        val breadcrumb = TextView(this).apply {
            text = "Personal  /  " + state.name
            textSize = 13f
            setTextColor(Color.rgb(207, 209, 215))
            setOnClickListener { renameWorkflow() }
        }
        topbar.addView(breadcrumb, LinearLayout.LayoutParams(0, -1, 1f))

        val editorTab = TextView(this).apply {
            text = "Editor"
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(56, 57, 62), 7f, Color.TRANSPARENT, 0)
            setPadding(dp(14), 0, dp(14), 0)
        }
        topbar.addView(editorTab, LinearLayout.LayoutParams(-2, dp(34)).apply {
            marginEnd = dp(3)
        })

        val executionsTab = TextView(this).apply {
            text = "Executions"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(155, 158, 166))
            setOnClickListener { showHistory() }
            setPadding(dp(12), 0, dp(12), 0)
        }
        topbar.addView(executionsTab, LinearLayout.LayoutParams(-2, dp(34)))

        val evaluationsTab = TextView(this).apply {
            text = "Evaluations"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(155, 158, 166))
            setOnClickListener { showHistory() }
            setPadding(dp(12), 0, dp(12), 0)
        }
        topbar.addView(evaluationsTab, LinearLayout.LayoutParams(-2, dp(34)))

        activeSwitch = Switch(this).apply {
            text = "Active"
            textSize = 11f
            setTextColor(Color.rgb(190, 192, 198))
            isChecked = state.active
            setOnCheckedChangeListener { _, checked ->
                state.active = checked
                WorkflowStore.save(this@MainActivity, state)
                syncAutomation()
                setStatus(
                    if (checked) "Active" else "Inactive",
                    if (checked) "Background automation enabled for this workflow."
                    else "Background automation disabled for this workflow."
                )
            }
        }
        topbar.addView(activeSwitch, LinearLayout.LayoutParams(-2, dp(44)).apply {
            marginStart = dp(8)
        })

        val share = n8nChromeButton("Share")
        share.setOnClickListener { shareWorkflow() }
        topbar.addView(share, LinearLayout.LayoutParams(-2, dp(34)).apply {
            marginStart = dp(5)
        })

        val save = n8nChromeButton("Save")
        save.setOnClickListener {
            WorkflowStore.save(this, state)
            syncAutomation()
            setStatus("Saved", "Workflow saved on this device.")
        }
        topbar.addView(save, LinearLayout.LayoutParams(-2, dp(34)).apply {
            marginStart = dp(5)
        })

        val more = n8nChromeButton("⋯")
        more.setOnClickListener { showMoreMenu() }
        topbar.addView(more, LinearLayout.LayoutParams(dp(40), dp(34)).apply {
            marginStart = dp(5)
        })

        editor.addView(topbar, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))

        val canvasHost = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(25, 26, 29))
        }
        canvas = WorkflowCanvas(this)
        canvasHost.addView(canvas, FrameLayout.LayoutParams(-1, -1))

        val addNode = TextView(this).apply {
            text = "+"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(244, 246, 249))
            background = rounded(Color.rgb(255, 109, 90), 18f, Color.TRANSPARENT, 0)
            elevation = dpF(8f)
            setOnClickListener { showNodeLibrary() }
        }
        canvasHost.addView(
            addNode,
            FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(18)
                rightMargin = dp(18)
            }
        )

        val canvasTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(Color.rgb(38, 39, 43), 9f, Color.rgb(65, 67, 73), 1)
        }

        fun canvasTool(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(226, 228, 232))
            background = rounded(Color.TRANSPARENT, 7f, Color.TRANSPARENT, 0)
            setOnClickListener { action() }
        }

        val fit = canvasTool("⌗") { canvas.fitView() }
        val zoomOut = canvasTool("−") { canvas.zoomBy(0.82f) }
        val zoomIn = canvasTool("+") { canvas.zoomBy(1.22f) }
        val undo = canvasTool("↶") { Toast.makeText(this, "Undo is reserved for the next history layer.", Toast.LENGTH_SHORT).show() }
        val redo = canvasTool("↷") { Toast.makeText(this, "Redo is reserved for the next history layer.", Toast.LENGTH_SHORT).show() }

        listOf(fit, zoomOut, zoomIn, undo, redo).forEach { v ->
            canvasTools.addView(v, LinearLayout.LayoutParams(dp(38), dp(34)))
        }

        canvasHost.addView(
            canvasTools,
            FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(16)
                bottomMargin = dp(16)
            }
        )

        editor.addView(
            canvasHost,
            FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = dp(56)
                bottomMargin = dp(58)
                gravity = Gravity.TOP
            }
        )

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundColor(Color.rgb(29, 30, 33))
        }

        val bottomMeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(225, 226, 231))
        }
        logView = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.rgb(146, 149, 157))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bottomMeta.addView(statusView)
        bottomMeta.addView(logView)
        bottomBar.addView(bottomMeta, LinearLayout.LayoutParams(0, -2, 1f))

        val run = TextView(this).apply {
            text = "⚗  Execute workflow"
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(255, 109, 90), 7f, Color.TRANSPARENT, 0)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { runWorkflow() }
        }
        bottomBar.addView(run, LinearLayout.LayoutParams(-2, dp(40)))

        editor.addView(bottomBar, FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM))

        setContentView(root)
    }

    private fun shareWorkflow() {
        val content = n8nJson().toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_SUBJECT, state.name)
        }
        startActivity(Intent.createChooser(intent, "Share workflow"))
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
        "YouTube" -> "YouTube"
        "Unsupported / Imported" -> "Unsupported imported node"
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
        nodePanel?.let { editorFrame.removeView(it) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(29, 30, 33))
            elevation = dpF(18f)
        }
        nodePanel = panel

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "What happens next?"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))

        val close = TextView(this).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(180, 183, 191))
            setOnClickListener { nodePanel?.let { editorFrame.removeView(it) }; nodePanel = null }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "Add a node to continue building this workflow."
            textSize = 11f
            setTextColor(Color.rgb(145, 149, 158))
            setPadding(0, 0, 0, dp(10))
        })

        val search = EditText(this).apply {
            hint = "Search nodes"
            textSize = 13f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 124, 133))
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(Color.rgb(38, 39, 43), 8f, Color.rgb(73, 75, 81), 1)
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(40)).apply {
            bottomMargin = dp(8)
        })

        val category = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                NodeCatalog.categories()
            )
        }
        panel.addView(category, LinearLayout.LayoutParams(-1, dp(38)).apply {
            bottomMargin = dp(8)
        })

        val list = ListView(this)
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        fun refresh() {
            val defs = NodeCatalog.search(
                search.text.toString(),
                category.selectedItem?.toString() ?: "All"
            )
            val labels = defs.map { it.type + "\n" + it.description }
            list.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                labels
            )
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

        list.setOnItemClickListener { _, _, position, _ ->
            val defs = NodeCatalog.search(
                search.text.toString(),
                category.selectedItem?.toString() ?: "All"
            )
            defs.getOrNull(position)?.let {
                addNode(it.type)
                nodePanel?.let { v -> editorFrame.removeView(v) }
                nodePanel = null
            }
        }

        refresh()

        editorFrame.addView(
            panel,
            FrameLayout.LayoutParams(dp(330), -1, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(56)
                bottomMargin = dp(58)
            }
        )
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
                spinners["mode"] = spinner("mode", "Schedule mode", listOf("interval", "daily", "weekly", "once"))
                fields["interval"] = textField("interval", "Interval")
                fields["interval"]?.inputType = InputType.TYPE_CLASS_NUMBER
                spinners["unit"] = spinner("unit", "Interval unit", listOf("seconds", "minutes", "hours", "days"))
                fields["hour"] = textField("hour", "Daily/weekly hour (0–23)")
                fields["hour"]?.inputType = InputType.TYPE_CLASS_NUMBER
                fields["minute"] = textField("minute", "Daily/weekly minute (0–59)")
                fields["minute"]?.inputType = InputType.TYPE_CLASS_NUMBER
                fields["days"] = textField("days", "Weekly days (MON,TUE,WED...)")
                fields["timezone"] = textField("timezone", "Timezone, e.g. Asia/Kolkata")
                fields["runAtEpochMs"] = textField("runAtEpochMs", "One-time run timestamp (milliseconds)")
                label("Android alarms can be deferred by the OS. NATEN uses exact alarms when the required Android access is available.")
            }

            "Webhook Trigger", "Chat Trigger" -> {
                spinners["method"] = spinner("method", "HTTP method", listOf("POST", "GET", "ANY"))
                fields["path"] = textField("path", "Path, e.g. hook/my-workflow")
                label("Webhook listener: http://PHONE_IP:8787/<path>")
            }

            "YouTube" -> {
                spinners["operation"] = spinner(
                    "operation",
                    "Operation",
                    listOf("Search", "Get Channel", "Search Channel Videos", "Get Video", "My Channel")
                )
                fields["query"] = textField("query", "Search query")
                fields["channelId"] = textField("channelId", "Channel ID")
                fields["videoId"] = textField("videoId", "Video ID")
                fields["maxResults"] = textField("maxResults", "Maximum results")
                fields["order"] = textField("order", "Order (relevance/date/viewCount)")
                fields["credentialName"] = textField("credentialName", "YouTube API key credential")
                fields["accessTokenCredential"] = textField("accessTokenCredential", "OAuth access-token credential (for private account operations)")
                fields["apiKey"] = textField("apiKey", "API key (leave blank when using credential)")
                fields["accessToken"] = textField("accessToken", "OAuth access token (leave blank when using credential)")
                label("API keys access public YouTube data. Private account operations require an OAuth access token with the required YouTube scope.")
            }

            in NodeCatalog.apiBackedTypes -> {
                spinners["method"] = spinner("method", "Method", listOf("GET", "POST", "PUT", "PATCH", "DELETE"))
                fields["url"] = textField("url", "URL")
                fields["headers"] = textField("headers", "Headers (one per line: Key: Value)", true)
                fields["body"] = textField("body", "Body", true)
                fields["queryParams"] = textField("queryParams", "Query parameters (one per line: key=value)", true)
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
            "JSON Parse", "JSON Stringify", "Markdown", "HTML", "XML", "Crypto" ->
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
                spinners["provider"] = spinner(
                    "provider",
                    "Provider",
                    listOf("OpenAI", "Gemini", "Anthropic", "OpenRouter", "Groq", "DeepSeek", "Mistral")
                )
                fields["endpoint"] = textField("endpoint", "Endpoint")
                fields["credentialName"] = textField("credentialName", "Saved credential name (optional)")
                fields["apiKey"] = textField("apiKey", "API key (leave blank when using saved credential)")
                fields["apiKey"]?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                fields["model"] = textField("model", "Model")
                fields["systemPrompt"] = textField("systemPrompt", "System prompt", true)
                fields["prompt"] = textField("prompt", "Prompt", true)
                fields["temperature"] = textField("temperature", "Temperature")
                spinners["responseFormat"] = spinner("responseFormat", "Response format", listOf("text", "json"))
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
            val cfg = JSONObject(
                (o.optJSONObject("parameters") ?: JSONObject()).toString()
            )
            if (mapped == "Unsupported / Imported") {
                cfg.put("originalType", o.optString("type", "unknown"))
                val credentialRef = o.optJSONObject("credentials")
                if (credentialRef != null) {
                    cfg.put("credentialReference", credentialRef.toString())
                }
            }

            val node = FlowNode(
                id = id,
                type = mapped,
                title = o.optString("name", mapped),
                x = pos.optDouble(0, 24.0).toFloat(),
                y = pos.optDouble(1, 24.0).toFloat(),
                config = cfg
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
            else -> "Unsupported / Imported"
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
        // Legacy nameView is not part of the current editor hierarchy.

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

    private fun n8nChromeButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(222, 224, 229))
        background = rounded(Color.rgb(39, 40, 44), 7f, Color.rgb(73, 75, 81), 1)
        setPadding(dp(11), 0, dp(11), 0)
        isClickable = true
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
        var selectedId: Int? = null

        private val nodeW = dpF(188f)
        private val nodeH = dpF(96f)

        private var scale = 1f
        private var offsetX = 48f
        private var offsetY = 42f

        private var dragId: Int? = null
        private var connectId: Int? = null
        private var moved = false
        private var panning = false
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var tempX = 0f
        private var tempY = 0f

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(47, 49, 53)
        }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(117, 121, 129)
            strokeWidth = dpF(1.8f)
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 35, 38)
            style = Paint.Style.FILL
        }
        private val nodeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(88, 91, 98)
            style = Paint.Style.STROKE
            strokeWidth = dpF(1f)
        }

        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = scale
                val next = (scale * detector.scaleFactor).coerceIn(0.5f, 1.65f)
                if (next == oldScale) return true

                val logicalFocusX = (detector.focusX - offsetX) / oldScale
                val logicalFocusY = (detector.focusY - offsetY) / oldScale
                scale = next
                offsetX = detector.focusX - logicalFocusX * scale
                offsetY = detector.focusY - logicalFocusY * scale
                invalidate()
                return true
            }
        })

        init {
            isClickable = true
            setBackgroundColor(Color.rgb(25, 26, 29))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            post { fitView() }
        }

        fun zoomBy(factor: Float) {
            val centerX = width / 2f
            val centerY = height / 2f
            val logicalX = (centerX - offsetX) / scale
            val logicalY = (centerY - offsetY) / scale
            scale = (scale * factor).coerceIn(0.5f, 1.65f)
            offsetX = centerX - logicalX * scale
            offsetY = centerY - logicalY * scale
            invalidate()
        }

        fun fitView() {
            if (width <= 0 || height <= 0 || state.nodes.isEmpty()) return

            val minX = state.nodes.minOf { it.x }
            val minY = state.nodes.minOf { it.y }
            val maxX = state.nodes.maxOf { it.x + nodeW }
            val maxY = state.nodes.maxOf { it.y + nodeH }

            val contentW = (maxX - minX).coerceAtLeast(dpF(240f))
            val contentH = (maxY - minY).coerceAtLeast(dpF(160f))
            val targetScale = minOf(
                (width - dpF(90f)) / contentW,
                (height - dpF(90f)) / contentH
            ).coerceIn(0.5f, 1.25f)

            scale = targetScale
            offsetX = (width - contentW * scale) / 2f - minX * scale
            offsetY = (height - contentH * scale) / 2f - minY * scale
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(25, 26, 29))

            canvas.save()
            drawGrid(canvas)

            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)

            state.edges.forEach { edge ->
                val from = state.nodes.firstOrNull { it.id == edge.from }
                val to = state.nodes.firstOrNull { it.id == edge.to }
                if (from != null && to != null) drawEdge(canvas, from, to, edge.branch)
            }

            if (connectId != null) {
                val source = state.nodes.firstOrNull { it.id == connectId }
                if (source != null) {
                    val sx = source.x + nodeW
                    val sy = source.y + nodeH / 2f
                    val tx = (tempX - offsetX) / scale
                    val ty = (tempY - offsetY) / scale
                    val path = Path()
                    path.moveTo(sx, sy)
                    val mid = (sx + tx) / 2f
                    path.cubicTo(mid, sy, mid, ty, tx, ty)
                    edgePaint.color = Color.rgb(255, 109, 90)
                    canvas.drawPath(path, edgePaint)
                }
            }

            state.nodes.forEach { drawNode(canvas, it) }
            canvas.restore()
            canvas.restore()
        }

        private fun drawGrid(canvas: Canvas) {
            val spacing = dpF(28f)
            val screenOffsetX = offsetX % (spacing * scale)
            val screenOffsetY = offsetY % (spacing * scale)

            var x = screenOffsetX
            while (x < width) {
                var y = screenOffsetY
                while (y < height) {
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(52, 54, 59)
                    }
                    canvas.drawCircle(x, y, dpF(0.9f), dot)
                    y += spacing * scale
                }
                x += spacing * scale
            }
        }

        private fun drawEdge(canvas: Canvas, from: FlowNode, to: FlowNode, branch: String) {
            val sx = from.x + nodeW
            val sy = from.y + nodeH / 2f
            val ex = to.x
            val ey = to.y + nodeH / 2f
            val path = Path()
            path.moveTo(sx, sy)
            val gap = (ex - sx).coerceAtLeast(dpF(44f))
            val bend = (sx + ex) / 2f
            path.cubicTo(sx + gap * 0.34f, sy, bend, ey, ex, ey)

            edgePaint.color = when {
                from.type == "IF" && branch.equals("true", true) -> Color.rgb(76, 175, 112)
                from.type == "IF" && branch.equals("false", true) -> Color.rgb(232, 94, 94)
                else -> Color.rgb(137, 140, 147)
            }
            canvas.drawPath(path, edgePaint)

            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = edgePaint.color
                style = Paint.Style.FILL
            }
            val arrow = Path().apply {
                moveTo(ex, ey)
                lineTo(ex - dpF(7f), ey - dpF(4f))
                lineTo(ex - dpF(7f), ey + dpF(4f))
                close()
            }
            canvas.drawPath(arrow, arrowPaint)

            if (branch.isNotBlank()) {
                smallPaint.color = edgePaint.color
                smallPaint.textSize = dpF(10f)
                smallPaint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(branch, (sx + ex) / 2f, (sy + ey) / 2f - dpF(7f), smallPaint)
            }
        }

        private fun drawNode(canvas: Canvas, node: FlowNode) {
            val rect = RectF(node.x, node.y, node.x + nodeW, node.y + nodeH)
            val accent = accent(node.type)

            nodeFill.color = Color.rgb(34, 35, 38)
            nodeStroke.color = if (selectedId == node.id) Color.rgb(255, 109, 90) else Color.rgb(83, 86, 93)
            nodeStroke.strokeWidth = if (selectedId == node.id) dpF(2f) else dpF(1f)

            canvas.drawRoundRect(rect, dpF(9f), dpF(9f), nodeFill)
            canvas.drawRoundRect(rect, dpF(9f), dpF(9f), nodeStroke)

            val iconRect = RectF(
                node.x + dpF(10f),
                node.y + dpF(11f),
                node.x + dpF(42f),
                node.y + dpF(43f)
            )
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(iconRect, dpF(7f), dpF(7f), iconPaint)

            val abbreviation = node.type
                .replace(Regex("[^A-Za-z]"), "")
                .take(2)
                .uppercase(Locale.US)

            textPaint.color = Color.WHITE
            textPaint.textSize = dpF(10f)
            textPaint.typeface = Typeface.DEFAULT_BOLD
            val tw = textPaint.measureText(abbreviation)
            canvas.drawText(
                abbreviation,
                iconRect.centerX() - tw / 2f,
                iconRect.centerY() + dpF(3.5f),
                textPaint
            )

            textPaint.color = Color.rgb(244, 245, 247)
            textPaint.textSize = dpF(13.5f)
            textPaint.typeface = Typeface.DEFAULT_BOLD
            drawEllipsized(
                canvas,
                node.title.ifBlank { node.type },
                node.x + dpF(51f),
                node.y + dpF(25f),
                nodeW - dpF(62f),
                textPaint
            )

            smallPaint.color = Color.rgb(159, 162, 170)
            smallPaint.textSize = dpF(10.5f)
            smallPaint.typeface = Typeface.DEFAULT
            drawEllipsized(
                canvas,
                node.type,
                node.x + dpF(51f),
                node.y + dpF(43f),
                nodeW - dpF(62f),
                smallPaint
            )

            smallPaint.color = Color.rgb(105, 108, 115)
            smallPaint.textSize = dpF(9f)
            canvas.drawText("#" + node.id, node.x + dpF(12f), node.y + nodeH - dpF(12f), smallPaint)

            val portFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(25, 26, 29)
                style = Paint.Style.FILL
            }
            val portStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(152, 155, 162)
                style = Paint.Style.STROKE
                strokeWidth = dpF(1.4f)
            }
            val cy = node.y + nodeH / 2f

            canvas.drawCircle(node.x, cy, dpF(5f), portFill)
            canvas.drawCircle(node.x, cy, dpF(5f), portStroke)
            canvas.drawCircle(node.x + nodeW, cy, dpF(5f), portFill)
            canvas.drawCircle(node.x + nodeW, cy, dpF(5f), portStroke)
        }

        private fun drawEllipsized(
            canvas: Canvas,
            value: String,
            x: Float,
            y: Float,
            maxWidth: Float,
            paint: Paint
        ) {
            if (paint.measureText(value) <= maxWidth) {
                canvas.drawText(value, x, y, paint)
                return
            }
            var text = value
            while (text.length > 1 && paint.measureText(text + "…") > maxWidth) {
                text = text.dropLast(1)
            }
            canvas.drawText(text + "…", x, y, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    moved = false
                    panning = false

                    if (event.pointerCount > 1) return true

                    val logicalX = (event.x - offsetX) / scale
                    val logicalY = (event.y - offsetY) / scale
                    val hit = findNode(logicalX, logicalY)

                    if (hit != null && isOutput(hit, logicalX, logicalY)) {
                        connectId = hit.id
                        tempX = event.x
                        tempY = event.y
                        selectedId = hit.id
                        invalidate()
                        return true
                    }

                    dragId = hit?.id
                    selectedId = hit?.id
                    if (hit == null) panning = true
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1 || scaleDetector.isInProgress) return true

                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(event.x - downX) + abs(event.y - downY) > dpF(7f)) moved = true
                    lastX = event.x
                    lastY = event.y

                    if (connectId != null) {
                        tempX = event.x
                        tempY = event.y
                        invalidate()
                        return true
                    }

                    val id = dragId
                    if (id != null) {
                        val node = state.nodes.firstOrNull { it.id == id } ?: return true
                        node.x = (node.x + dx / scale).coerceAtLeast(0f)
                        node.y = (node.y + dy / scale).coerceAtLeast(0f)
                    } else if (panning) {
                        offsetX += dx
                        offsetY += dy
                    }

                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (connectId != null) {
                        val source = state.nodes.firstOrNull { it.id == connectId }
                        connectId = null

                        val logicalX = (event.x - offsetX) / scale
                        val logicalY = (event.y - offsetY) / scale
                        val target = findInput(logicalX, logicalY)

                        if (source != null && target != null) {
                            connectNodes(source, target)
                        } else {
                            setStatus("Connection cancelled", "Drop on another node's input port.")
                        }

                        invalidate()
                        return true
                    }

                    val selected = state.nodes.firstOrNull { it.id == dragId }
                    if (!moved && selected != null) {
                        selectedId = selected.id
                        showNodeEditor(selected)
                    } else if (moved && dragId != null) {
                        WorkflowStore.save(this@MainActivity, state)
                    }

                    dragId = null
                    panning = false
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragId = null
                    connectId = null
                    panning = false
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun isOutput(node: FlowNode, x: Float, y: Float): Boolean {
            val hx = node.x + nodeW
            val hy = node.y + nodeH / 2f
            return distance(x, y, hx, hy) <= dpF(18f)
        }

        private fun findInput(x: Float, y: Float): FlowNode? =
            state.nodes.asReversed().firstOrNull {
                distance(x, y, it.x, it.y + nodeH / 2f) <= dpF(24f)
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
                Color.rgb(85, 115, 245)
            "HTTP Request", "Generic API", "GraphQL" -> Color.rgb(38, 177, 169)
            "IF", "Filter", "Switch" -> Color.rgb(232, 163, 69)
            "AI Text", "AI Agent" -> Color.rgb(156, 95, 221)
            "Wait", "Limit", "Loop Over Items" -> Color.rgb(143, 148, 158)
            "Notification", "Open URL", "Share Text" -> Color.rgb(53, 177, 119)
            "Stop / Error" -> Color.rgb(229, 91, 91)
            else -> Color.rgb(108, 113, 124)
        }
    }

    companion object {
        private const val REQ_IMPORT = 7401
        private const val REQ_EXPORT = 7402
        private const val REQ_NOTIFICATIONS = 7403
    }
}
