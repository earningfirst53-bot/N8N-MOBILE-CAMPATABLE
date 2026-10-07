package com.manhuadirector.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var chapterUri: Uri? = null
    private var scriptUri: Uri? = null
    private var audioUri: Uri? = null
    private lateinit var chapterLabel: TextView
    private lateinit var scriptLabel: TextView
    private lateinit var audioLabel: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var produceButton: Button

    companion object {
        private const val PICK_CHAPTER = 1001
        private const val PICK_SCRIPT = 1002
        private const val PICK_AUDIO = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Manhua Director"
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 17, 20))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(TextView(this).apply {
            text = "MANHUA DIRECTOR"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = "Offline production  •  PDF + script + narration → MP4"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(170, 174, 184))
            setPadding(0, 0, 0, dp(18))
        })

        val chapterButton = actionButton("1. Choose chapter PDF")
        chapterButton.setOnClickListener { pickFile(PICK_CHAPTER, "application/pdf") }
        body.addView(chapterButton)
        chapterLabel = fileLabel("No chapter selected")
        body.addView(chapterLabel)

        val scriptButton = actionButton("2. Choose timestamp script PDF")
        scriptButton.setOnClickListener { pickFile(PICK_SCRIPT, "application/pdf") }
        body.addView(scriptButton)
        scriptLabel = fileLabel("No script selected")
        body.addView(scriptLabel)

        val audioButton = actionButton("3. Choose narration audio")
        audioButton.setOnClickListener { pickFile(PICK_AUDIO, "audio/*") }
        body.addView(audioButton)
        audioLabel = fileLabel("No audio selected")
        body.addView(audioLabel)

        body.addView(TextView(this).apply {
            text = "Best input: a two-column timestamp script (time on the left, panel number on the right). Yellow horizontal separators in the chapter PDF split panels."
            textSize = 12f
            setTextColor(Color.rgb(155, 159, 169))
            setPadding(0, dp(14), 0, dp(14))
        })

        produceButton = actionButton("CREATE VIDEO")
        produceButton.setOnClickListener { startProduction() }
        body.addView(produceButton, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })

        val settings = Button(this).apply {
            text = "Production settings"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Default: fast 720p, 15 fps, gentle motion. Audio is the master clock.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        body.addView(settings, LinearLayout.LayoutParams(-1, dp(44)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
        }
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(10) })

        status = TextView(this).apply {
            text = "Ready. Nothing is uploaded."
            textSize = 13f
            setTextColor(Color.rgb(198, 201, 208))
            setPadding(0, dp(14), 0, dp(18))
        }
        body.addView(status)

        root.addView(TextView(this).apply {
            text = "Offline by design. Long renders may make the phone warm."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(116, 120, 131))
        })

        setContentView(root)
    }

    private fun startProduction() {
        val chapter = chapterUri
        val audio = audioUri
        if (chapter == null || audio == null) {
            toast("Choose the chapter PDF and narration audio first.")
            return
        }
        produceButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Preparing production…"

        Thread {
            try {
                val result = ProductionEngine(this@MainActivity).produce(
                    chapterUri = chapter,
                    scriptUri = scriptUri,
                    audioUri = audio
                ) { p, message ->
                    runOnUiThread {
                        progress.progress = (p.coerceIn(0f, 1f) * 1000f).toInt()
                        status.text = message
                    }
                }
                runOnUiThread {
                    produceButton.isEnabled = true
                    status.text = "DONE: ${result.displayName}"
                    Toast.makeText(
                        this,
                        "Saved to Movies/ManhuaDirector",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    produceButton.isEnabled = true
                    status.text = "FAILED: ${(t.message ?: t.javaClass.simpleName).take(500)}"
                    Toast.makeText(this, "Production failed.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun pickFile(request: Int, type: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("Legacy callback kept for API compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        when (requestCode) {
            PICK_CHAPTER -> { chapterUri = uri; chapterLabel.text = "Chapter: ${displayName(uri)}" }
            PICK_SCRIPT -> { scriptUri = uri; scriptLabel.text = "Script: ${displayName(uri)}" }
            PICK_AUDIO -> { audioUri = uri; audioLabel.text = "Audio: ${displayName(uri)}" }
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else uri.lastPathSegment ?: "selected file"
            } ?: (uri.lastPathSegment ?: "selected file")
        }.getOrDefault(uri.lastPathSegment ?: "selected file")

    private fun fileLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.rgb(157, 161, 171))
        setPadding(dp(8), dp(4), dp(8), dp(12))
    }

    private fun actionButton(text: String) = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
