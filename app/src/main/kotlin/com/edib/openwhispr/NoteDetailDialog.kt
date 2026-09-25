package com.edib.openwhispr

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Note Detail screen / dialog.
 * Dark, minimal, dense, native Android, consistent with OpenWispr.
 * No gradients, no glowing AI controls.
 */
class NoteDetailDialog(
    context: Context,
    private val initialNoteId: String,
    private val onNoteChanged: () -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val repo = NotesRepository.getInstance(context)
    private val player = NoteAudioPlayer()
    private var currentNote: Note? = null

    private lateinit var titleText: TextView
    private lateinit var dateText: TextView
    private lateinit var playBtn: MaterialButton
    private lateinit var seekBar: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var totalText: TextView
    private lateinit var speedBtn: MaterialButton
    private lateinit var statusBadge: TextView
    private lateinit var retryBtn: MaterialButton

    private lateinit var transcriptContainer: LinearLayout
    private lateinit var transcriptText: TextView
    private lateinit var editedBadge: TextView
    private lateinit var viewOriginalBtn: MaterialButton
    private lateinit var editBtn: MaterialButton
    private lateinit var shareBtn: MaterialButton

    private var playbackSpeed = 1.0f
    private var isTrackingTouch = false
    private var repoListenerRegistered = false
    private val repoListener: () -> Unit = {
        if (isShowing) loadNote()
    }

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(OpenWisprUi.BACKGROUND))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OpenWisprUi.BACKGROUND)
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        // ================= TOP BAR: Back | More =================
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(24)
            }
            layoutParams = lp
        }

        val backBtn = OpenWisprUi.iconButton(context, "‹", "Back to notes").apply {
            textSize = 32f
            setOnClickListener { dismiss() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(32) + bars.bottom)
            insets
        }
        topBar.addView(backBtn)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer)

        val moreBtn = OpenWisprUi.iconButton(context, "⋮", "More note actions").apply {
            setOnClickListener { v -> showMoreMenu(v) }
        }
        topBar.addView(moreBtn)
        root.addView(topBar)

        // ================= HEADER: Title & Date =================
        titleText = TextView(context).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(titleText)

        dateText = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(4), 0, dp(24))
        }
        root.addView(dateText)

        // ================= AUDIO PLAYER CARD =================
        // [ Play ] ━━━━━●━━━━━━━━ 2:43
        //          00:41            1×
        val playerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = OpenWisprUi.surface(context)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(24)
            }
            layoutParams = lp
        }

        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        playBtn = MaterialButton(context).apply {
            text = "▶"
            contentDescription = "Play recording"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A2A.toInt())
            cornerRadius = dp(8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener { togglePlay() }
        }
        controlsRow.addView(playBtn)

        seekBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            }
            progressTintList = ColorStateList.valueOf(0xFF3B82F6.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        player.seekTo(progress)
                        elapsedText.text = Note.formatElapsed(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isTrackingTouch = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { isTrackingTouch = false }
            })
        }
        controlsRow.addView(seekBar)

        totalText = TextView(context).apply {
            text = "0:00"
            textSize = 13f
            setTextColor(0xFF9E9E9E.toInt())
            minWidth = dp(38)
            gravity = Gravity.END
        }
        controlsRow.addView(totalText)
        playerCard.addView(controlsRow)

        // Elapsed time & Speed toggle row
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
        }

        // Space aligned under play button
        val dummySpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), 1)
        }
        timeRow.addView(dummySpacer)

        elapsedText = TextView(context).apply {
            text = "00:00"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeRow.addView(elapsedText)

        speedBtn = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "1×"
            contentDescription = "Playback speed, 1 times"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF9E9E9E.toInt())
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = dp(40)
            minHeight = dp(48)
            setOnClickListener { cycleSpeed() }
        }
        timeRow.addView(speedBtn)
        playerCard.addView(timeRow)

        root.addView(playerCard)

        // ================= STATUS / PENDING / FAILED =================
        statusBadge = TextView(context).apply {
            textSize = 14f
            visibility = View.GONE
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(statusBadge)

        retryBtn = MaterialButton(context).apply {
            text = "Retry transcription"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A2A.toInt())
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
            layoutParams = lp
            setOnClickListener {
                currentNote?.let {
                    NoteTranscriber.transcribeNoteAsync(context, it.id)
                    loadNote()
                    onNoteChanged()
                }
            }
        }
        root.addView(retryBtn)

        // ================= TRANSCRIPT SECTION =================
        transcriptContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val transcriptLabelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val transcriptLabel = TextView(context).apply {
            text = "TRANSCRIPT"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF888888.toInt())
        }
        transcriptLabelRow.addView(transcriptLabel)

        editedBadge = TextView(context).apply {
            text = "  Edited"
            textSize = 12f
            setTextColor(0xFF3B82F6.toInt())
            visibility = View.GONE
        }
        transcriptLabelRow.addView(editedBadge)
        transcriptContainer.addView(transcriptLabelRow)

        // Transcript text with native Android BiDi support
        transcriptText = TextView(context).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(16))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true)
        }
        transcriptContainer.addView(transcriptText)

        // View original button (if edited)
        viewOriginalBtn = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Edited   ·   Original"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            layoutParams = lp
            setOnClickListener { showOriginalTranscriptDialog() }
        }
        transcriptContainer.addView(viewOriginalBtn)

        // Action buttons: Edit, Share
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
        }

        editBtn = MaterialButton(context).apply {
            text = "Edit"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A2A.toInt())
            cornerRadius = dp(8)
            setOnClickListener { promptEditTranscript() }
        }
        actionRow.addView(editBtn)

        shareBtn = MaterialButton(context).apply {
            text = "Share"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A2A.toInt())
            cornerRadius = dp(8)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            }
            layoutParams = lp
            setOnClickListener { showShareOptions() }
        }
        actionRow.addView(shareBtn)

        transcriptContainer.addView(actionRow)
        root.addView(transcriptContainer)
        root.addView(BenchmarkUi.noteSection(context, initialNoteId))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        loadNote()
    }

    private fun loadNote() {
        val note = repo.getNote(initialNoteId)
        currentNote = note
        if (note == null) {
            dismiss()
            return
        }

        titleText.text = note.title
        dateText.text = "${Note.formatDateHeader(note.createdAt)}, ${Note.formatTime(note.createdAt)}"
        totalText.text = Note.formatDuration(note.audioDurationMs)
        seekBar.max = note.audioDurationMs.toInt()

        when (note.transcriptionState) {
            Note.State.PENDING -> {
                statusBadge.text = "Transcribing…\nYour recording is safe."
                statusBadge.setTextColor(0xFF9E9E9E.toInt())
                statusBadge.visibility = View.VISIBLE
                retryBtn.visibility = View.GONE
                transcriptContainer.visibility = View.GONE
            }
            Note.State.FAILED -> {
                statusBadge.text = "Couldn't transcribe\nYour recording is safe."
                statusBadge.setTextColor(0xFFEF4444.toInt())
                statusBadge.visibility = View.VISIBLE
                retryBtn.visibility = View.VISIBLE
                transcriptContainer.visibility = View.GONE
            }
            Note.State.COMPLETE -> {
                statusBadge.visibility = View.GONE
                retryBtn.visibility = View.GONE
                transcriptContainer.visibility = View.VISIBLE
                transcriptText.text = note.displayTranscript ?: ""
                editedBadge.visibility = if (note.hasEditedTranscript) View.VISIBLE else View.GONE
                viewOriginalBtn.visibility = if (note.hasEditedTranscript) View.VISIBLE else View.GONE
            }
        }
    }

    private fun togglePlay() {
        val note = currentNote ?: return
        if (player.isPlaying) {
            player.pause()
            playBtn.text = "▶"
            playBtn.contentDescription = "Resume recording"
        } else {
            playBtn.text = "Ⅱ"
            playBtn.contentDescription = "Pause recording"
            player.play(
                audioPath = note.audioPath,
                speed = playbackSpeed,
                onProgress = { current, total ->
                    if (!isTrackingTouch) {
                        seekBar.max = total
                        seekBar.progress = current
                        elapsedText.text = Note.formatElapsed(current.toLong())
                    }
                },
                onCompletion = {
                    playBtn.text = "▶"
                    playBtn.contentDescription = "Play recording"
                    seekBar.progress = 0
                    elapsedText.text = "00:00"
                },
                onError = { err ->
                    playBtn.text = "▶"
                    playBtn.contentDescription = "Play recording"
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun cycleSpeed() {
        playbackSpeed = when (playbackSpeed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        speedBtn.text = "${playbackSpeed}×"
        speedBtn.contentDescription = "Playback speed, ${playbackSpeed} times"
        player.setSpeed(playbackSpeed)
    }

    /**
     * MORE MENU: ONLY:
     * - Pin / Unpin
     * - Retranscribe
     * - Delete
     */
    private fun showMoreMenu(anchor: View) {
        val note = currentNote ?: return
        val popup = PopupMenu(context, anchor)
        popup.menu.add(if (note.isPinned) "Unpin" else "Pin")
        popup.menu.add("Retranscribe")
        popup.menu.add("Delete")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Pin", "Unpin" -> {
                    repo.togglePinned(note.id)
                    loadNote()
                    onNoteChanged()
                    true
                }
                "Retranscribe" -> {
                    NoteTranscriber.transcribeNoteAsync(context, note.id)
                    loadNote()
                    onNoteChanged()
                    true
                }
                "Delete" -> {
                    confirmDelete(note)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showShareOptions() {
        val note = currentNote ?: return
        val sheet = BottomSheetDialog(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
            setBackgroundColor(OpenWisprUi.SURFACE)
            addView(TextView(context).apply {
                text = "Share"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(OpenWisprUi.TEXT)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
        }
        fun option(title: String, subtitle: String, action: () -> Unit) = TextView(context).apply {
            text = "$title\n$subtitle"
            textSize = 16f
            setTextColor(OpenWisprUi.TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(64)
            isClickable = true
            isFocusable = true
            setOnClickListener { sheet.dismiss(); action() }
        }
        body.addView(option("Share transcript", "Share text only") { NoteShareHelper.shareTranscript(context, note) })
        body.addView(option("Share audio", "Share the original recording") { NoteShareHelper.shareAudio(context, note) })
        body.addView(option("Share audio & transcript", "Share both files together") { NoteShareHelper.shareAudioAndTranscript(context, note) })
        sheet.setContentView(body)
        sheet.show()
    }

    private fun promptEditTranscript() {
        val note = currentNote ?: return
        val currentText = note.displayTranscript ?: ""

        val input = EditText(context).apply {
            setText(currentText)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF222222.toInt())
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Edit transcript")
            .setMessage("Original audio and original ASR transcript remain preserved.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotBlank()) {
                    repo.updateEditedTranscript(note.id, newText)
                    loadNote()
                    onNoteChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOriginalTranscriptDialog() {
        val note = currentNote ?: return
        val original = note.originalTranscript ?: "No original transcript available"

        val tv = TextView(context).apply {
            text = original
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Original transcription")
            .setView(tv)
            .setPositiveButton("Revert to this") { _, _ ->
                repo.revertToOriginalTranscript(note.id)
                loadNote()
                onNoteChanged()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmDelete(note: Note) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Delete voice note?")
            .setMessage("This permanently deletes the recording and transcript.")
            .setPositiveButton("Delete") { _, _ ->
                player.stop()
                repo.deleteNote(note.id)
                onNoteChanged()
                dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!repoListenerRegistered) {
            repo.addListener(repoListener)
            repoListenerRegistered = true
        }
        loadNote()
    }

    override fun onStop() {
        if (repoListenerRegistered) {
            repo.removeListener(repoListener)
            repoListenerRegistered = false
        }
        player.release()
        super.onStop()
    }

    override fun onDetachedFromWindow() {
        if (repoListenerRegistered) {
            repo.removeListener(repoListener)
            repoListenerRegistered = false
        }
        player.release()
        super.onDetachedFromWindow()
    }
}
