package com.edib.openwhispr

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * UI for the local-model benchmark is deliberately isolated from the normal
 * transcription UI. It only reads benchmark sidecars and never mutates the
 * primary/original transcript.
 */
object BenchmarkUi {

    fun settingsRow(context: Context): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val prefs = context.getSharedPreferences("openwhispr", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(LocalModelBenchmark.PREF_ENABLED, false)

        val toggle = MaterialSwitch(context).apply {
            isChecked = enabled
            isClickable = false
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = true
            isFocusable = true

            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    this.text = "Benchmark all installed local models"
                    textSize = 18f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                addView(TextView(context).apply {
                    this.text = "Testing mode · runs each installed model on new voice notes"
                    textSize = 14f
                    setTextColor(0xFF9E9E9E.toInt())
                    setPadding(0, dp(2), 0, 0)
                })
            }

            addView(text)
            addView(toggle)

            setOnClickListener {
                val newValue = !toggle.isChecked
                prefs.edit().putBoolean(LocalModelBenchmark.PREF_ENABLED, newValue).apply()
                toggle.isChecked = newValue
            }
        }
    }

    /**
     * Returns a self-updating benchmark section. Listener registration is tied
     * to View attachment so NoteDetailDialog does not need benchmark lifecycle
     * fields or teardown code.
     */
    fun noteSection(context: Context, noteId: String): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(28)
            }
        }

        val status = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, 0, 0, dp(12))
        }

        val results = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val rerun = MaterialButton(context).apply {
            text = "Run benchmark again"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A2A.toInt())
            cornerRadius = dp(8)
            setOnClickListener {
                LocalModelBenchmark.rerun(context.applicationContext, noteId)
            }
        }

        root.addView(TextView(context).apply {
            text = "MODEL BENCHMARK"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(status)
        root.addView(results)
        root.addView(rerun)

        fun render() {
            val snapshot = LocalModelBenchmark.getSnapshot(context.applicationContext, noteId)
            if (snapshot == null) {
                root.visibility = View.GONE
                return
            }

            root.visibility = View.VISIBLE
            status.text = when (snapshot.state) {
                BenchmarkRunState.QUEUED -> "Queued — primary transcript has priority"
                BenchmarkRunState.RUNNING -> "Running installed models sequentially…"
                BenchmarkRunState.COMPLETE -> "Complete"
                BenchmarkRunState.FAILED -> "Benchmark interrupted"
            }

            rerun.isEnabled = !LocalModelBenchmark.isRunning(noteId)
            results.removeAllViews()

            snapshot.results.forEach { result ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = OpenWisprUi.surface(context)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(10)
                    }
                }

                card.addView(TextView(context).apply {
                    text = result.modelName
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                })

                card.addView(TextView(context).apply {
                    text = when (result.state) {
                        BenchmarkModelState.WAITING -> "Waiting"
                        BenchmarkModelState.RUNNING -> "Transcribing…"
                        BenchmarkModelState.COMPLETE -> if (result.elapsedMs > 0L) {
                            "Complete · ${result.elapsedMs} ms"
                        } else {
                            "Complete"
                        }
                        BenchmarkModelState.FAILED -> "Failed"
                        BenchmarkModelState.NOT_INSTALLED -> "Not installed"
                    }
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                    setPadding(0, dp(3), 0, dp(8))
                })

                when (result.state) {
                    BenchmarkModelState.COMPLETE -> {
                        card.addView(TextView(context).apply {
                            text = result.transcript.ifBlank { "No speech detected" }
                            textSize = 15f
                            setTextColor(0xFFFFFFFF.toInt())
                            setLineSpacing(dp(3).toFloat(), 1f)
                            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                            gravity = Gravity.START
                            setTextIsSelectable(true)
                        })

                        if (result.transcript.isNotBlank()) {
                            card.addView(MaterialButton(context).apply {
                                text = "Logical order"
                                textSize = 12f
                                setOnClickListener {
                                    showLogicalOrder(context, result.modelName, result.transcript)
                                }
                            })
                        }
                    }

                    BenchmarkModelState.FAILED -> {
                        card.addView(TextView(context).apply {
                            text = result.error ?: "Unknown error"
                            textSize = 13f
                            setTextColor(0xFFEF4444.toInt())
                            setTextIsSelectable(true)
                        })
                    }

                    else -> Unit
                }

                results.addView(card)
            }
        }

        val listener: (String) -> Unit = { changedId ->
            if (changedId == noteId) root.post { render() }
        }

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                LocalModelBenchmark.addListener(listener)
                render()
            }

            override fun onViewDetachedFromWindow(v: View) {
                LocalModelBenchmark.removeListener(listener)
            }
        })

        render()
        return root
    }

    private fun showLogicalOrder(context: Context, modelName: String, transcript: String) {
        val tokens = transcript
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val logical = if (tokens.isEmpty()) {
            "No tokens"
        } else {
            tokens.mapIndexed { index, token ->
                "%03d  %s".format(index + 1, token)
            }.joinToString("\n")
        }

        AlertDialog.Builder(context)
            .setTitle("$modelName · logical token order")
            .setMessage(logical)
            .setPositiveButton("Close", null)
            .show()
    }
}
