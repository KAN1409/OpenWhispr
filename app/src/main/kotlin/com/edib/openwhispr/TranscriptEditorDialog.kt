package com.edib.openwhispr

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TranscriptEditorDialog(
    context: Context,
    private val noteId: String,
    private val onSaved: () -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val repo = NotesRepository.getInstance(context)
    private lateinit var editor: EditText
    private var initialText = ""
    private fun dp(value: Int) = with(OpenWisprUi) { context.dp(value) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(OpenWisprUi.BACKGROUND))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val note = repo.getNote(noteId) ?: run { dismiss(); return }
        initialText = note.displayTranscript.orEmpty()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(OpenWisprUi.BACKGROUND)
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
        bar.addView(OpenWisprUi.iconButton(context, R.drawable.ic_back, "Back") { requestClose() })
        bar.addView(TextView(context).apply {
            text = "Edit transcript"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(OpenWisprUi.ON_BACKGROUND)
            gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        })
        bar.addView(TextView(context).apply {
            text = "Save"; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(OpenWisprUi.PRIMARY)
            gravity = Gravity.CENTER; minimumWidth = dp(64); minimumHeight = dp(48); isClickable = true; isFocusable = true
            contentDescription = "Save transcript"; setOnClickListener { save() }
        })
        root.addView(bar)
        root.addView(TextView(context).apply {
            text = "Original audio and transcription are preserved."; textSize = 13f; setTextColor(OpenWisprUi.MUTED)
            setPadding(0, dp(4), 0, dp(12))
        })
        editor = EditText(context).apply {
            setText(initialText); textSize = 18f; setTextColor(OpenWisprUi.ON_BACKGROUND); setHintTextColor(OpenWisprUi.MUTED)
            gravity = Gravity.TOP or Gravity.START; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = OpenWisprUi.rounded(OpenWisprUi.SURFACE, 12, context)
            minLines = 14; isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(editor)
        root.addView(TextView(context).apply {
            text = "View original transcription"; textSize = 15f; setTextColor(OpenWisprUi.PRIMARY)
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48); isClickable = true; isFocusable = true
            setOnClickListener { showOriginal(note.originalTranscript.orEmpty()) }
        })
        setContentView(root)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editor.requestFocus()
    }

    private fun save() {
        val value = editor.text.toString().trim()
        if (value.isNotBlank()) { repo.updateEditedTranscript(noteId, value); onSaved(); dismiss() }
    }

    private fun requestClose() {
        if (editor.text.toString() == initialText) dismiss()
        else MaterialAlertDialogBuilder(context).setTitle("Discard changes?")
            .setMessage("Your transcript edits haven't been saved.")
            .setNegativeButton("Keep editing", null).setPositiveButton("Discard") { _, _ -> dismiss() }.show()
    }

    private fun showOriginal(value: String) {
        val text = TextView(context).apply {
            this.text = value.ifBlank { "No original transcription available" }; textSize = 17f
            setTextColor(OpenWisprUi.ON_BACKGROUND); setLineSpacing(dp(4).toFloat(), 1f)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        MaterialAlertDialogBuilder(context).setTitle("Original transcription").setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton("Close", null).show()
    }

    override fun onBackPressed() = requestClose()

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
