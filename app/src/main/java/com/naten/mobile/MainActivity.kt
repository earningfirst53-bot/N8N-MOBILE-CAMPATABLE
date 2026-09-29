package com.naten.mobile

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        val title = TextView(this).apply { text = "NATEN"; textSize = 28f; setTextColor(Color.BLACK) }
        val subtitle = TextView(this).apply { text = "Mobile automation builder"; textSize = 16f; setTextColor(Color.DKGRAY) }
        val add = Button(this).apply { text = "+ Add Node" }
        val run = Button(this).apply { text = "Run Workflow" }
        val status = TextView(this).apply { text = "No workflow yet"; gravity = Gravity.CENTER; textSize = 18f }
        root.addView(title); root.addView(subtitle); root.addView(add); root.addView(run); root.addView(status, LinearLayout.LayoutParams(-1,0,1f))
        add.setOnClickListener { status.text = "Node 1: Trigger\n\nTap Run Workflow to test" }
        run.setOnClickListener { status.text = "Workflow executed locally." }
        setContentView(root)
    }
}
