package com.naten.mobile

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

private data class FlowNode(
    var id: Int,
    var type: String,
    var title: String,
    var x: Float,
    var y: Float
)

class MainActivity : Activity() {
    private lateinit var canvas: WorkflowCanvas
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private val prefs by lazy { getSharedPreferences("naten", MODE_PRIVATE) }

    private val nodeTypes = listOf(
        "Trigger" to "Start the workflow",
        "AI" to "Process with AI",
        "Action" to "Perform an action",
        "Condition" to "Check a rule",
        "Delay" to "Wait before continuing"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadWorkflow()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            setBackgroundColor(Color.WHITE)
        }

        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "NATEN"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 27, 34))
        })
        titleBox.addView(TextView(this).apply {
            text = "Mobile automation builder"
            textSize = 13f
            setTextColor(Color.rgb(95, 101, 112))
        })
        titleRow.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val newBtn = actionButton("New")
        val saveBtn = actionButton("Save")
        val runBtn = actionButton("Run")
        titleRow.addView(newBtn)
        titleRow.addView(saveBtn)
        titleRow.addView(runBtn)
        header.addView(titleRow)

        val palette = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, 0)
        }
        val paletteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nodeTypes.forEach { pair ->
            val b = actionButton("+ ${pair.first}")
            b.setOnClickListener { addNode(pair.first, pair.second) }
            paletteRow.addView(b)
        }
        palette.addView(paletteRow)
        header.addView(palette)

        root.addView(header)

        canvas = WorkflowCanvas(this)
        root.addView(
            canvas,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
                leftMargin = dp(10)
                rightMargin = dp(10)
            }
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(38, 42, 50))
        }
        logView = TextView(this).apply {
            text = "Add nodes to create your first workflow."
            textSize = 12f
            setTextColor(Color.rgb(95, 101, 112))
            setPadding(0, dp(4), 0, 0)
            maxLines = 3
        }
        bottom.addView(statusView)
        bottom.addView(logView)
        root.addView(bottom)

        newBtn.setOnClickListener {
            canvas.clearNodes()
            statusView.text = "New workflow"
            logView.text = "Canvas cleared."
            saveWorkflow()
        }
        saveBtn.setOnClickListener {
            saveWorkflow()
            statusView.text = "Saved"
            logView.text = "Workflow saved on this device."
        }
        runBtn.setOnClickListener { runWorkflow() }

        setContentView(root)
    }

    private fun addNode(type: String, description: String) {
        val index = canvas.nodes.size
        val col = index % 2
        val row = index / 2
        canvas.addNode(
            FlowNode(
                id = canvas.nextId(),
                type = type,
                title = description,
                x = dpF(24 + col * 182),
                y = dpF(24 + row * 118)
            )
        )
        statusView.text = "Added $type"
        logView.text = "Tap a node to select it. Drag nodes to arrange the flow."
        saveWorkflow()
    }

    private fun runWorkflow() {
        val nodes = canvas.nodes.toList()
        if (nodes.isEmpty()) {
            statusView.text = "Nothing to run"
            logView.text = "Add at least one node."
            return
        }
        statusView.text = "Running ${nodes.size} node(s)…"
        val steps = nodes.sortedBy { it.id }.mapIndexed { i, n -> "${i + 1}. ${n.type} • ${n.title}" }
        logView.text = "Workflow test complete.\n" + steps.takeLast(3).joinToString("\n")
    }

    private fun saveWorkflow() {
        prefs.edit().putString("workflow", canvas.toJson().toString()).apply()
    }

    private fun loadWorkflow() {
        val raw = prefs.getString("workflow", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val restored = mutableListOf<FlowNode>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                restored.add(
                    FlowNode(
                        id = o.getInt("id"),
                        type = o.getString("type"),
                        title = o.getString("title"),
                        x = o.getDouble("x").toFloat(),
                        y = o.getDouble("y").toFloat()
                    )
                )
            }
            canvas.setNodes(restored)
            statusView.text = "Loaded"
            logView.text = "${restored.size} node(s) restored."
        }
    }

    private fun actionButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.rgb(35, 40, 48))
        background = rounded(Color.WHITE, 14f, Color.rgb(215, 219, 226), 1)
        setPadding(dp(11), dp(1), dp(11), dp(1))
        minHeight = dp(40)
        minimumHeight = dp(40)
        layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(6) }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int, width: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke(width, stroke)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Int): Float = v * resources.displayMetrics.density

    inner class WorkflowCanvas(context: Context) : View(context) {
        val nodes = mutableListOf<FlowNode>()
        private var selectedId: Int? = null
        private var dragId: Int? = null
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private val nodeW = dpF(158).toInt()
        private val nodeH = dpF(82).toInt()

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(35, 0, 0, 0) }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(161, 169, 181)
            strokeWidth = dpF(3)
            style = Paint.Style.STROKE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(223, 226, 231)
            style = Paint.Style.FILL
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dpF(15)
            typeface = Typeface.DEFAULT_BOLD
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dpF(12) }

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            isClickable = true
        }

        fun nextId(): Int = (nodes.maxOfOrNull { it.id } ?: 0) + 1

        fun addNode(node: FlowNode) {
            nodes.add(node)
            selectedId = node.id
            invalidate()
        }

        fun setNodes(items: List<FlowNode>) {
            nodes.clear()
            nodes.addAll(items)
            selectedId = nodes.lastOrNull()?.id
            invalidate()
        }

        fun clearNodes() {
            nodes.clear()
            selectedId = null
            invalidate()
        }

        fun toJson(): JSONArray {
            val a = JSONArray()
            nodes.forEach { n ->
                a.put(JSONObject().apply {
                    put("id", n.id)
                    put("type", n.type)
                    put("title", n.title)
                    put("x", n.x)
                    put("y", n.y)
                })
            }
            return a
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawColor(Color.rgb(247, 248, 250))
            drawGrid(c)
            drawConnections(c)
            nodes.sortedBy { it.id }.forEach { drawNode(c, it) }
        }

        private fun drawGrid(c: Canvas) {
            val step = dpF(24)
            var x = 0f
            while (x < width) {
                var y = 0f
                while (y < height) {
                    c.drawCircle(x, y, dpF(1.25f), dotPaint)
                    y += step
                }
                x += step
            }
        }

        private fun drawConnections(c: Canvas) {
            val ordered = nodes.sortedBy { it.id }
            for (i in 0 until ordered.size - 1) {
                val a = ordered[i]
                val b = ordered[i + 1]
                val ax = a.x + nodeW
                val ay = a.y + nodeH / 2f
                val bx = b.x
                val by = b.y + nodeH / 2f
                val path = Path()
                path.moveTo(ax, ay)
                val mid = (ax + bx) / 2f
                path.cubicTo(mid, ay, mid, by, bx, by)
                c.drawPath(path, linePaint)
                c.drawCircle(bx, by, dpF(4), linePaint)
            }
        }

        private fun drawNode(c: Canvas, n: FlowNode) {
            val selected = selectedId == n.id
            val rect = RectF(n.x, n.y, n.x + nodeW, n.y + nodeH)
            shadowPaint.setShadowLayer(if (selected) dpF(10) else dpF(6), 0f, dpF(3), Color.argb(55, 0, 0, 0))
            shadowPaint.color = Color.WHITE
            c.drawRoundRect(rect, dpF(16), dpF(16), shadowPaint)

            val accent = when (n.type) {
                "Trigger" -> Color.rgb(61, 113, 240)
                "AI" -> Color.rgb(136, 80, 214)
                "Action" -> Color.rgb(23, 153, 110)
                "Condition" -> Color.rgb(221, 145, 31)
                else -> Color.rgb(92, 100, 112)
            }
            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            c.drawRoundRect(RectF(n.x, n.y, n.x + dpF(7), n.y + nodeH), dpF(16), dpF(16), accentPaint)

            titlePaint.color = Color.rgb(25, 28, 34)
            bodyPaint.color = Color.rgb(95, 101, 112)
            c.drawText(n.type, n.x + dpF(18), n.y + dpF(27), titlePaint)
            drawEllipsized(c, n.title, n.x + dpF(18), n.y + dpF(51), nodeW - dpF(34), bodyPaint)

            if (selected) {
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(80, 108, 225)
                    style = Paint.Style.STROKE
                    strokeWidth = dpF(2)
                }
                c.drawRoundRect(rect, dpF(16), dpF(16), border)
            }
        }

        private fun drawEllipsized(c: Canvas, text: String, x: Float, y: Float, maxW: Float, paint: Paint) {
            if (paint.measureText(text) <= maxW) {
                c.drawText(text, x, y, paint)
                return
            }
            var s = text
            while (s.isNotEmpty() && paint.measureText("$s…") > maxW) s = s.dropLast(1)
            c.drawText("$s…", x, y, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    val hit = findNode(event.x, event.y)
                    dragId = hit?.id
                    selectedId = hit?.id
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val id = dragId ?: return true
                    val node = nodes.firstOrNull { it.id == id } ?: return true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dpF(4)) moved = true
                    node.x = (node.x + dx).coerceIn(0f, width - nodeW.toFloat())
                    node.y = (node.y + dy).coerceIn(0f, height - nodeH.toFloat())
                    downX = event.x
                    downY = event.y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && dragId != null) {
                        selectedId = dragId
                        statusView.text = "Selected node #$dragId"
                        logView.text = "Drag to reposition the node."
                    }
                    dragId = null
                    saveWorkflow()
                    performClick()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun findNode(x: Float, y: Float): FlowNode? =
            nodes.asReversed().firstOrNull {
                x >= it.x && x <= it.x + nodeW && y >= it.y && y <= it.y + nodeH
            }
    }
}
