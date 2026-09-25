package com.edib.openwhispr

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale

/**
 * Primary Notes screen.
 * Dark. Minimal. Dense. Native Android. Consistent with OpenWispr.
 * No gradients, no glowing AI controls, no chatbot UI.
 */
class NotesTimelineView(
    context: Context,
    private val onOpenSettings: () -> Unit
) : FrameLayout(context) {

    private val repo = NotesRepository.getInstance(context)
    private val recorder = InAppNoteRecorder(context)

    private val container: LinearLayout
    private val notesListLayout: LinearLayout
    private val searchBarLayout: LinearLayout
    private val searchEditText: EditText
    private val emptyView: TextView
    private val recordFab: FloatingActionButton

    // Minimal In-App Recording Screen/Overlay
    private val recordingOverlay: LinearLayout
    private val recTimerText: TextView
    private val recLevelIndicator: LinearProgressIndicator
    private val recStopBtn: MaterialButton
    private val recCancelBtn: MaterialButton

    private var currentSearchQuery = ""
    private var isSearchVisible = false
    private val repositoryListener: () -> Unit = { post { refreshNotes() } }

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    init {
        setBackgroundColor(OpenWisprUi.BACKGROUND)

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(OpenWisprUi.BACKGROUND)
        }

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(100))
        }

        // ================= TOP BAR: OpenWispr | Search | Settings =================
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }

        val brandTitle = TextView(context).apply {
            text = "OpenWispr"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(brandTitle)

        val searchBtn = OpenWisprUi.iconButton(context, "⌕", "Search notes").apply {
            setOnClickListener { toggleSearch() }
        }
        topBar.addView(searchBtn)

        val settingsBtn = OpenWisprUi.iconButton(context, "⚙", "Settings").apply {
            setOnClickListener { onOpenSettings() }
        }
        topBar.addView(settingsBtn)

        container.addView(topBar)

        // ================= SECTION TITLE: Notes =================
        val notesTitle = TextView(context).apply {
            text = "Notes"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.TEXT)
            setPadding(0, 0, 0, dp(2))
        }
        container.addView(notesTitle)
        container.addView(TextView(context).apply {
            text = "Your voice, captured."
            textSize = 14f
            setTextColor(OpenWisprUi.TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(12))
        })

        // ================= SEARCH BAR (Expandable) =================
        searchBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = OpenWisprUi.surface(context)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
            layoutParams = lp
            visibility = View.GONE
        }

        searchEditText = EditText(context).apply {
            hint = "Search notes…"
            setHintTextColor(0xFF757575.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            background = null
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    currentSearchQuery = s?.toString()?.trim() ?: ""
                    refreshNotes()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBarLayout.addView(searchEditText)

        val clearSearchBtn = TextView(context).apply {
            text = "×"
            contentDescription = "Close search"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                searchEditText.setText("")
                toggleSearch()
            }
        }
        searchBarLayout.addView(clearSearchBtn)
        container.addView(searchBarLayout)

        // ================= NOTES LIST CONTAINER =================
        notesListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(notesListLayout)

        emptyView = TextView(context).apply {
            text = "No voice notes yet\n\nYour voice, captured.\nTap the microphone to record your first note."
            textSize = 16f
            setTextColor(OpenWisprUi.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
            visibility = View.GONE
        }
        container.addView(emptyView)

        scrollView.addView(container)
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ================= BOTTOM MICROPHONE FAB =================
        recordFab = FloatingActionButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            backgroundTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = "Record voice note"
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = dp(24)
                bottomMargin = dp(24)
            }
            layoutParams = lp
            setOnClickListener {
                startRecordingFlow()
            }
        }
        addView(recordFab)

        // ================= MINIMAL RECORDING UI =================
        // Layout:
        // 00:37
        // [ restrained audio level/waveform ]
        // Recording voice note
        // [ STOP ]
        // Cancel
        recordingOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(OpenWisprUi.BACKGROUND)
            setPadding(dp(32), dp(48), dp(32), dp(48))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        recTimerText = TextView(context).apply {
            text = "00:00"
            textSize = 44f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        recordingOverlay.addView(recTimerText)

        recLevelIndicator = LinearProgressIndicator(context).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            trackColor = 0xFF2A2A2A.toInt()
            setIndicatorColor(0xFFEF4444.toInt())
            trackCornerRadius = dp(2)
            val lp = LinearLayout.LayoutParams(dp(180), dp(4)).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
            layoutParams = lp
        }
        recordingOverlay.addView(recLevelIndicator)

        val recLabel = TextView(context).apply {
            text = "Recording voice note\n\nTap stop when you're done"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(36)
            }
            layoutParams = lp
        }
        recordingOverlay.addView(recLabel)

        recStopBtn = MaterialButton(context).apply {
            text = "STOP"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xFFEF4444.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(24)
            minWidth = dp(140)
            setOnClickListener {
                stopAndSaveRecording()
            }
        }
        recordingOverlay.addView(recStopBtn)

        recCancelBtn = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
            layoutParams = lp
            setOnClickListener {
                cancelRecording()
            }
        }
        recordingOverlay.addView(recCancelBtn)

        addView(recordingOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Hook up recorder ticks to restrained audio level & timer
        recorder.onTickListener = { elapsedMs, amplitude ->
            val totalSec = elapsedMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            recTimerText.text = String.format(Locale.US, "%02d:%02d", min, sec)
            recLevelIndicator.progress = (amplitude * 100).toInt().coerceIn(0, 100)
        }

        // Listen for repository changes (insert, delete, update, transcribe)
        repo.addListener(repositoryListener)

        refreshNotes()
    }

    fun isRecording(): Boolean = recorder.isRecording

    private fun startRecordingFlow() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (context is Activity) {
                ActivityCompat.requestPermissions(context as Activity, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            }
            return
        }

        val ok = recorder.start { err ->
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
        }
        if (ok) {
            recordFab.visibility = View.GONE
            recordingOverlay.visibility = View.VISIBLE
            recTimerText.text = "00:00"
            recLevelIndicator.progress = 0
        }
    }

    private fun stopAndSaveRecording() {
        // Critical invariant: recorder.stopAndSave() durably persists raw PCM as .wav to disk
        // and inserts the Note record BEFORE triggering asynchronous transcription.
        val note = recorder.stopAndSave()
        recordingOverlay.visibility = View.GONE
        recordFab.visibility = View.VISIBLE

        if (note != null) {
            // Durable persistence has succeeded!
            Toast.makeText(context, "Note saved", Toast.LENGTH_SHORT).show()
            refreshNotes()
            // Return/show note detail immediately in PENDING state
            NoteDetailDialog(context, note.id) { refreshNotes() }.show()
        } else {
            Toast.makeText(context, "No audio recorded", Toast.LENGTH_SHORT).show()
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        recordingOverlay.visibility = View.GONE
        recordFab.visibility = View.VISIBLE
    }

    fun dispose() {
        if (recorder.isRecording) recorder.cancel()
        recorder.onTickListener = null
        repo.removeListener(repositoryListener)
    }

    private fun toggleSearch() {
        isSearchVisible = !isSearchVisible
        searchBarLayout.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
        if (isSearchVisible) {
            searchEditText.requestFocus()
        } else {
            searchEditText.setText("")
            currentSearchQuery = ""
            refreshNotes()
        }
    }

    fun refreshNotes() {
        notesListLayout.removeAllViews()

        val allNotes = if (currentSearchQuery.isEmpty()) {
            repo.getAllNotes()
        } else {
            repo.searchNotes(currentSearchQuery)
        }

        if (allNotes.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            if (currentSearchQuery.isNotEmpty()) {
                emptyView.text = "No results\n\nTry another search."
            } else {
                emptyView.text = "No voice notes yet\n\nYour voice, captured.\nTap the microphone to record your first note."
            }
            return
        } else {
            emptyView.visibility = View.GONE
        }

        // Group notes:
        // 1. PINNED (if any pinned notes exist)
        // 2. TODAY (if any today notes exist)
        // 3. YESTERDAY (if any yesterday notes exist)
        // 4. OLDER DATES
        val pinned = allNotes.filter { it.isPinned }
        val unpinned = allNotes.filter { !it.isPinned }

        if (pinned.isNotEmpty()) {
            notesListLayout.addView(buildSectionHeader("PINNED"))
            pinned.forEach { notesListLayout.addView(buildNoteCard(it)) }
        }

        if (unpinned.isNotEmpty()) {
            val groupedByDay = unpinned.groupBy { Note.formatDateHeader(it.createdAt) }
            groupedByDay.forEach { (dayLabel, notes) ->
                notesListLayout.addView(buildSectionHeader(dayLabel.uppercase()))
                notes.forEach { notesListLayout.addView(buildNoteCard(it)) }
            }
        }
    }

    private fun buildSectionHeader(title: String) = TextView(context).apply {
        text = title
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF888888.toInt())
        setPadding(dp(4), dp(16), dp(4), dp(8))
    }

    private fun buildNoteCard(note: Note): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = OpenWisprUi.surface(context)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                NoteDetailDialog(context, note.id) { refreshNotes() }.show()
            }
        }

        // Header: Title and Pin indicator
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(context).apply {
            text = note.title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleTv)

        val more = OpenWisprUi.iconButton(context, if (note.isPinned) "•" else "⋮", if (note.isPinned) "Pinned note options" else "Note options").apply {
            textSize = 22f
            setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    menu.add(if (note.isPinned) "Unpin note" else "Pin note")
                    menu.add("Retranscribe")
                    setOnMenuItemClickListener { item ->
                        when (item.title.toString()) {
                            "Pin note", "Unpin note" -> repo.togglePinned(note.id)
                            "Retranscribe" -> NoteTranscriber.transcribeNoteAsync(context, note.id)
                        }
                        refreshNotes()
                        true
                    }
                    show()
                }
            }
        }
        titleRow.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(titleRow)

        // Body content based on transcription state: PENDING, FAILED, COMPLETE
        when (note.transcriptionState) {
            Note.State.PENDING -> {
                val pendingTv = TextView(context).apply {
                    text = "Transcribing…"
                    textSize = 14f
                    setTextColor(0xFF9E9E9E.toInt())
                    setPadding(0, dp(4), 0, dp(6))
                }
                card.addView(pendingTv)
            }
            Note.State.FAILED -> {
                val failedTitle = TextView(context).apply {
                    text = "Couldn't transcribe"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFFEF4444.toInt())
                    setPadding(0, dp(4), 0, dp(2))
                }
                card.addView(failedTitle)

                val failedSub = TextView(context).apply {
                    text = "Your recording is safe."
                    textSize = 13f
                    setTextColor(0xFF9E9E9E.toInt())
                    setPadding(0, 0, 0, dp(6))
                }
                card.addView(failedSub)

                val retryBtn = TextView(context).apply {
                    text = "Retry transcription"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFF3B82F6.toInt())
                    setPadding(0, 0, 0, dp(6))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        NoteTranscriber.transcribeNoteAsync(context, note.id)
                        refreshNotes()
                    }
                }
                card.addView(retryBtn)
            }
            Note.State.COMPLETE -> {
                val transcriptPreview = TextView(context).apply {
                    text = note.displayTranscript ?: ""
                    textSize = 14f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFFE0E0E0.toInt())
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(0, dp(4), 0, dp(6))
                }
                card.addView(transcriptPreview)
            }
        }

        // Compact playback metadata. Opening the card exposes the full player.
        val footer = TextView(context).apply {
            text = "▶  ${Note.formatDuration(note.audioDurationMs)}                              ${Note.formatFooterTime(note.createdAt)}"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
        }
        card.addView(footer)

        return card
    }
}
