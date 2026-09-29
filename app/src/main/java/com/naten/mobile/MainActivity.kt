package com.naten.mobile

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var canvas: LinearLayout
    private lateinit var status: TextView
    private var count = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20) }
        val title = TextView(this).apply { text = "NATEN"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK) }
        val subtitle = TextView(this).apply { text = "Mobile automation builder"; textSize = 15f; setTextColor(Color.DKGRAY) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val add = Button(this).apply { text = "+ Add Node" }
        val run = Button(this).apply { text = "Run" }
        bar.addView(add, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(run)
        canvas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4,20,4,4) }
        status = TextView(this).apply { text = "Build a workflow by adding nodes."; textSize = 16f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }
        root.addView(title); root.addView(subtitle); root.addView(bar); root.addView(canvas, LinearLayout.LayoutParams(-1,0,1f)); root.addView(status)
        add.setOnClickListener { addNode() }
        run.setOnClickListener { status.text = if (count == 0) "Nothing to run." else "Workflow executed: $count node(s)." }
        setContentView(root)
    }

    private fun addNode() {
        count++
        val node = TextView(this).apply {
            text = "Node $count  •  Action"
            textSize = 17f
            setTextColor(Color.BLACK)
            setPadding(24,22,24,22)
            setBackgroundColor(0xFFEDEDED.toInt())
            setOnLongClickListener { canvas.removeView(this); count--; status.text = "Node removed."; true }
        }
        canvas.addView(node, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        status.text = "Long-press a node to remove it."
    }
}
