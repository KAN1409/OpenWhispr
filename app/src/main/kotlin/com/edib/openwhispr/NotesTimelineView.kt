package com.edib.openwhispr

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale

class NotesTimelineView(context: Context, private val onOpenSettings: () -> Unit) : FrameLayout(context) {
    private val repo = NotesRepository.getInstance(context)
    private val recorder = InAppNoteRecorder(context)
    private val notesList = LinearLayout(context)
    private val emptyView = TextView(context)
    private val titleText = TextView(context)
    private val searchField = EditText(context)
    private lateinit var clearSearch: View
    private lateinit var searchButton: View
    private lateinit var settingsButton: View
    private lateinit var searchBack: View
    private val recordFab: FloatingActionButton
    private val recordingOverlay: LinearLayout
    private val recTimerText: TextView
    private val recLevelIndicator: LinearProgressIndicator
    private var query = ""
    private var searchMode = false
    private val repositoryListener: () -> Unit = { post { refreshNotes() } }
    private fun dp(value: Int) = with(OpenWisprUi) { context.dp(value) }

    init {
        setBackgroundColor(OpenWisprUi.BACKGROUND)
        val scroller = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(104))
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        searchBack = OpenWisprUi.iconButton(context, R.drawable.ic_back, "Close search") { exitSearch() }.apply { visibility = GONE }
        titleText.apply {
            text = "OpenWispr"; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.ON_BACKGROUND); gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }
        searchField.apply {
            hint = "Search notes…"; setHintTextColor(OpenWisprUi.MUTED); setTextColor(OpenWisprUi.ON_BACKGROUND)
            textSize = 17f; setSingleLine(true); background = null; visibility = GONE
            textDirection = TEXT_DIRECTION_FIRST_STRONG
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString()?.trim().orEmpty()
                    clearSearch.visibility = if (query.isEmpty()) GONE else VISIBLE
                    refreshNotes()
                }
            })
        }
        clearSearch = OpenWisprUi.iconButton(context, R.drawable.ic_close, "Clear search") { searchField.setText("") }.apply { visibility = GONE }
        searchButton = OpenWisprUi.iconButton(context, R.drawable.ic_search, "Search voice notes") { enterSearch() }
        settingsButton = OpenWisprUi.iconButton(context, R.drawable.ic_settings, "Open settings", onOpenSettings)
        topBar.addView(searchBack); topBar.addView(titleText); topBar.addView(searchField)
        topBar.addView(clearSearch); topBar.addView(searchButton); topBar.addView(settingsButton)
        content.addView(topBar)
        content.addView(TextView(context).apply {
            text = "Notes"; textSize = 28f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.ON_BACKGROUND); setPadding(0, dp(12), 0, dp(10))
        })
        notesList.orientation = LinearLayout.VERTICAL
        content.addView(notesList)
        emptyView.apply {
            textSize = 16f; setTextColor(OpenWisprUi.MUTED); gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f); setPadding(dp(16), dp(64), dp(16), dp(64))
        }
        content.addView(emptyView)
        scroller.addView(content)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        recordFab = FloatingActionButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            backgroundTintList = ColorStateList.valueOf(OpenWisprUi.PRIMARY)
            imageTintList = ColorStateList.valueOf(OpenWisprUi.BACKGROUND)
            contentDescription = "Record voice note"
            layoutParams = LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(20); bottomMargin = dp(20) }
            setOnClickListener { startRecordingFlow() }
        }
        addView(recordFab)

        recTimerText = TextView(context).apply {
            text = "00:00"; textSize = 44f; setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(OpenWisprUi.ON_BACKGROUND); gravity = Gravity.CENTER
        }
        recLevelIndicator = LinearProgressIndicator(context).apply {
            isIndeterminate = false; max = 100; trackColor = OpenWisprUi.SURFACE_HIGH
            setIndicatorColor(OpenWisprUi.RECORDING)
            layoutParams = LinearLayout.LayoutParams(dp(176), dp(4)).apply { topMargin = dp(24); bottomMargin = dp(18) }
        }
        recordingOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(OpenWisprUi.BACKGROUND)
            setPadding(dp(24), dp(48), dp(24), dp(48)); visibility = GONE; isClickable = true
            addView(recTimerText); addView(recLevelIndicator)
            addView(TextView(context).apply {
                text = "Recording voice note"; textSize = 15f; setTextColor(OpenWisprUi.MUTED)
                gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(32))
            })
            addView(MaterialButton(context).apply {
                text = "STOP"; contentDescription = "Stop and save voice note"; minWidth = dp(152); minimumHeight = dp(52)
                cornerRadius = dp(26); backgroundTintList = ColorStateList.valueOf(OpenWisprUi.RECORDING)
                setTextColor(0xFFFFFFFF.toInt()); setOnClickListener { stopAndSaveRecording() }
            })
            addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Cancel"; minimumHeight = dp(48); setTextColor(OpenWisprUi.MUTED)
                setOnClickListener { cancelRecording() }
            })
        }
        addView(recordingOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        recorder.onTickListener = { elapsedMs, amplitude ->
            val seconds = elapsedMs / 1000
            recTimerText.text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
            recLevelIndicator.progress = (amplitude * 100).toInt().coerceIn(0, 100)
        }
        repo.addListener(repositoryListener)
        refreshNotes()
    }

    fun isRecording() = recorder.isRecording

    private fun enterSearch() {
        searchMode = true; titleText.visibility = GONE; searchButton.visibility = GONE; settingsButton.visibility = GONE
        searchBack.visibility = VISIBLE; searchField.visibility = VISIBLE; searchField.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun exitSearch() {
        searchField.setText(""); searchField.clearFocus(); searchMode = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchField.windowToken, 0)
        titleText.visibility = VISIBLE; searchButton.visibility = VISIBLE; settingsButton.visibility = VISIBLE
        searchBack.visibility = GONE; searchField.visibility = GONE; clearSearch.visibility = GONE
        refreshNotes()
    }

    private fun startRecordingFlow() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (context is Activity) ActivityCompat.requestPermissions(context as Activity, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (recorder.start { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }) {
            recordFab.visibility = GONE; recordingOverlay.visibility = VISIBLE
            recTimerText.text = "00:00"; recLevelIndicator.progress = 0
        }
    }

    private fun stopAndSaveRecording() {
        val note = recorder.stopAndSave(); recordingOverlay.visibility = GONE; recordFab.visibility = VISIBLE
        if (note == null) Toast.makeText(context, "No audio recorded", Toast.LENGTH_SHORT).show()
        else { refreshNotes(); NoteDetailDialog(context, note.id) { refreshNotes() }.show() }
    }

    fun cancelRecording() { recorder.cancel(); recordingOverlay.visibility = GONE; recordFab.visibility = VISIBLE }
    fun dispose() { if (recorder.isRecording) recorder.cancel(); recorder.onTickListener = null; repo.removeListener(repositoryListener) }

    fun refreshNotes() {
        notesList.removeAllViews()
        val notes = if (query.isEmpty()) repo.getAllNotes() else repo.searchNotes(query)
        emptyView.visibility = if (notes.isEmpty()) VISIBLE else GONE
        emptyView.text = if (searchMode) "No notes found" else "No voice notes yet\n\nTap the microphone to record one."
        if (notes.isEmpty()) return
        val pinned = notes.filter { it.isPinned }
        if (pinned.isNotEmpty()) { addSection("PINNED"); pinned.forEach(::addCard) }
        notes.filterNot { it.isPinned }.groupBy { Note.formatDateHeader(it.createdAt) }.forEach { (day, items) ->
            addSection(day.uppercase(Locale.getDefault())); items.forEach(::addCard)
        }
    }

    private fun addSection(label: String) {
        notesList.addView(OpenWisprUi.sectionLabel(context, label).apply { setPadding(dp(4), dp(18), dp(4), dp(8)) })
    }

    private fun addCard(note: Note) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(10))
            background = OpenWisprUi.rounded(OpenWisprUi.SURFACE, 12, context); isClickable = true; isFocusable = true
            contentDescription = "Voice note at ${Note.formatTime(note.createdAt)}"
            setOnClickListener { NoteDetailDialog(context, note.id) { refreshNotes() }.show() }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = Note.formatTime(note.createdAt); textSize = 13f; setTextColor(OpenWisprUi.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        if (note.isPinned) header.addView(TextView(context).apply {
            text = "PINNED"; textSize = 10f; letterSpacing = 0.08f; setTypeface(typeface, Typeface.BOLD); setTextColor(OpenWisprUi.PRIMARY)
        })
        card.addView(header)
        val preview = when (note.transcriptionState) {
            Note.State.PENDING -> "Transcribing…"
            Note.State.FAILED -> "Couldn't transcribe\nYour recording is safe."
            Note.State.COMPLETE -> note.displayTranscript.orEmpty()
        }
        card.addView(TextView(context).apply {
            text = preview; textSize = 16f
            setTextColor(if (note.transcriptionState == Note.State.FAILED) OpenWisprUi.ERROR else OpenWisprUi.ON_BACKGROUND)
            setLineSpacing(dp(3).toFloat(), 1f); maxLines = if (note.transcriptionState == Note.State.COMPLETE) 3 else 2
            ellipsize = TextUtils.TruncateAt.END; textDirection = TEXT_DIRECTION_FIRST_STRONG; textAlignment = TEXT_ALIGNMENT_VIEW_START
            setPadding(0, dp(8), 0, dp(8))
        })
        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_play); setColorFilter(OpenWisprUi.ON_SURFACE)
                contentDescription = null; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            addView(TextView(context).apply {
                text = Note.formatDuration(note.audioDurationMs); textSize = 13f; setTextColor(OpenWisprUi.ON_SURFACE)
                setPadding(dp(6), 0, 0, 0)
            })
        })
        if (note.transcriptionState == Note.State.FAILED) footer.addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Retry"; minimumHeight = dp(48); setTextColor(OpenWisprUi.PRIMARY)
            setOnClickListener { NoteTranscriber.transcribeNoteAsync(context, note.id); refreshNotes() }
        }) else footer.addView(TextView(context).apply {
            text = Note.formatFooterTime(note.createdAt); textSize = 12f; setTextColor(OpenWisprUi.MUTED); gravity = Gravity.CENTER_VERTICAL or Gravity.END
        })
        card.addView(footer); notesList.addView(card)
    }

    fun handleBack(): Boolean {
        if (!searchMode) return false
        exitSearch()
        return true
    }
}
