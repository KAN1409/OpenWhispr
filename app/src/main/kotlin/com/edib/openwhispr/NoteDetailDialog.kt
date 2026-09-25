package com.edib.openwhispr

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class NoteDetailDialog(
    context: Context,
    private val noteId: String,
    private val onNoteChanged: () -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val repo = NotesRepository.getInstance(context)
    private val player = NoteAudioPlayer()
    private var currentNote: Note? = null
    private lateinit var playButton: View
    private lateinit var seekBar: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var totalText: TextView
    private lateinit var dateText: TextView
    private lateinit var speedButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var transcriptSection: LinearLayout
    private lateinit var transcriptText: TextView
    private lateinit var editedLabel: TextView
    private lateinit var originalButton: MaterialButton
    private var speed = 1f
    private var paused = false
    private var tracking = false
    private val repositoryListener: () -> Unit = { window?.decorView?.post { loadNote() } }
    private fun dp(value: Int) = with(OpenWisprUi) { context.dp(value) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(OpenWisprUi.BACKGROUND))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        val topBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
        topBar.addView(OpenWisprUi.iconButton(context, R.drawable.ic_back, "Back") { dismiss() })
        topBar.addView(TextView(context).apply {
            text = "Voice note"; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(OpenWisprUi.ON_BACKGROUND); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        })
        val more = OpenWisprUi.iconButton(context, R.drawable.ic_more, "More actions") {}
        more.setOnClickListener { showMoreMenu(more) }
        topBar.addView(more)
        content.addView(topBar)

        dateText = TextView(context).apply {
            tag = "date"; textSize = 14f; setTextColor(OpenWisprUi.MUTED)
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(20))
        }
        content.addView(dateText)
        content.addView(buildPlayer())

        statusText = TextView(context).apply {
            textSize = 15f; setLineSpacing(dp(3).toFloat(), 1f); setTextColor(OpenWisprUi.MUTED)
            gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(12))
        }
        content.addView(statusText)
        retryButton = MaterialButton(context).apply {
            text = "Retry transcription"; minimumHeight = dp(48); visibility = View.GONE
            backgroundTintList = ColorStateList.valueOf(OpenWisprUi.SURFACE_HIGH)
            setTextColor(OpenWisprUi.PRIMARY); setOnClickListener {
                NoteTranscriber.transcribeNoteAsync(context, noteId); loadNote(); onNoteChanged()
            }
        }
        content.addView(retryButton)

        transcriptSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        transcriptSection.addView(OpenWisprUi.sectionLabel(context, "TRANSCRIPT").apply { setPadding(0, dp(24), 0, dp(12)) })
        editedLabel = TextView(context).apply {
            text = "Edited"; textSize = 12f; setTextColor(OpenWisprUi.PRIMARY); visibility = View.GONE
        }
        transcriptSection.addView(editedLabel)
        transcriptText = TextView(context).apply {
            textSize = 18f; setTextColor(OpenWisprUi.ON_BACKGROUND); setLineSpacing(dp(5).toFloat(), 1f)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true); setPadding(0, dp(6), 0, dp(16))
        }
        transcriptSection.addView(transcriptText)
        transcriptSection.addView(MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Edit transcript"; minimumHeight = dp(48); gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(OpenWisprUi.PRIMARY); setOnClickListener { openEditor() }
        })
        originalButton = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "View original transcription"; minimumHeight = dp(48); gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(OpenWisprUi.MUTED); visibility = View.GONE; setOnClickListener { showOriginal() }
        }
        transcriptSection.addView(originalButton)
        content.addView(transcriptSection)

        val scroll = ScrollView(context).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        repo.addListener(repositoryListener)
        loadNote()
    }

    private fun buildPlayer(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(8))
            background = OpenWisprUi.rounded(OpenWisprUi.SURFACE, 14, context)
        }
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playButton = OpenWisprUi.iconButton(context, R.drawable.ic_play, "Play recording") { togglePlayback() }
        controls.addView(playButton)
        seekBar = SeekBar(context).apply {
            progressTintList = ColorStateList.valueOf(OpenWisprUi.PRIMARY)
            thumbTintList = ColorStateList.valueOf(OpenWisprUi.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { player.seekTo(value); elapsedText.text = Note.formatElapsed(value.toLong()) }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { tracking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) { tracking = false }
            })
        }
        controls.addView(seekBar)
        totalText = TextView(context).apply {
            text = "0:00"; textSize = 13f; setTextColor(OpenWisprUi.ON_SURFACE); gravity = Gravity.END; minWidth = dp(44)
        }
        controls.addView(totalText)
        card.addView(controls)
        val meta = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(52), 0, 0, 0) }
        elapsedText = TextView(context).apply {
            text = "00:00"; textSize = 12f; setTextColor(OpenWisprUi.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f); gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(elapsedText)
        speedButton = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "1×"; contentDescription = "Playback speed, one times"; minWidth = dp(48); minimumHeight = dp(48)
            setTextColor(OpenWisprUi.ON_SURFACE); setOnClickListener { cycleSpeed() }
        }
        meta.addView(speedButton); card.addView(meta)
        return card
    }

    private fun loadNote() {
        if (!::statusText.isInitialized) return
        val note = repo.getNote(noteId) ?: run { dismiss(); return }
        currentNote = note
        dateText.text = "${Note.formatDateHeader(note.createdAt)}, ${Note.formatTime(note.createdAt)}"
        totalText.text = Note.formatDuration(note.audioDurationMs)
        seekBar.max = note.audioDurationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        when (note.transcriptionState) {
            Note.State.PENDING -> {
                statusText.text = "Transcribing…\nYour recording is safe."
                statusText.setTextColor(OpenWisprUi.MUTED); statusText.visibility = View.VISIBLE
                retryButton.visibility = View.GONE; transcriptSection.visibility = View.GONE
            }
            Note.State.FAILED -> {
                statusText.text = "Couldn't transcribe\nYour recording is safe."
                statusText.setTextColor(OpenWisprUi.ERROR); statusText.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE; transcriptSection.visibility = View.GONE
            }
            Note.State.COMPLETE -> {
                statusText.visibility = View.GONE; retryButton.visibility = View.GONE; transcriptSection.visibility = View.VISIBLE
                transcriptText.text = note.displayTranscript.orEmpty()
                editedLabel.visibility = if (note.hasEditedTranscript) View.VISIBLE else View.GONE
                originalButton.visibility = if (note.hasEditedTranscript) View.VISIBLE else View.GONE
            }
        }
    }

    private fun togglePlayback() {
        val note = currentNote ?: return
        when {
            player.isPlaying -> {
                player.pause(); paused = true; setPlayIcon(false)
            }
            paused -> {
                player.resume(); paused = false; setPlayIcon(true)
            }
            else -> {
                paused = false; setPlayIcon(true)
                player.play(note.audioPath, speed, { current, total ->
                    if (!tracking) { seekBar.max = total; seekBar.progress = current; elapsedText.text = Note.formatElapsed(current.toLong()) }
                }, {
                    paused = false; setPlayIcon(false); seekBar.progress = 0; elapsedText.text = "00:00"
                }, {
                    paused = false; setPlayIcon(false); Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    private fun setPlayIcon(playing: Boolean) {
        (playButton as? android.widget.ImageButton)?.apply {
            setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = if (playing) "Pause recording" else "Play recording"
        }
    }

    private fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        speedButton.text = "${speed.toString().removeSuffix(".0")}×"
        speedButton.contentDescription = "Playback speed, ${speed.toString().removeSuffix(".0")} times"
        player.setSpeed(speed)
    }

    private fun showMoreMenu(anchor: View) {
        val note = currentNote ?: return
        PopupMenu(context, anchor).apply {
            menu.add(if (note.isPinned) "Unpin" else "Pin")
            menu.add("Share")
            menu.add("Retranscribe")
            menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Pin", "Unpin" -> { repo.togglePinned(note.id); onNoteChanged(); true }
                    "Share" -> { showShareOptions(); true }
                    "Retranscribe" -> { NoteTranscriber.transcribeNoteAsync(context, note.id); loadNote(); true }
                    "Delete" -> { confirmDelete(note); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showShareOptions() {
        val note = currentNote ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle("Share note")
            .setItems(arrayOf("Transcript", "Audio", "Audio + transcript")) { _, which ->
                when (which) { 0 -> NoteShareHelper.shareTranscript(context, note); 1 -> NoteShareHelper.shareAudio(context, note); else -> NoteShareHelper.shareAudioAndTranscript(context, note) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openEditor() {
        val note = currentNote ?: return
        TranscriptEditorDialog(context, note.id) { loadNote(); onNoteChanged() }.show()
    }

    private fun showOriginal() {
        val note = currentNote ?: return
        val value = note.originalTranscript ?: "No original transcription available"
        val text = TextView(context).apply {
            this.text = value; textSize = 17f; setTextColor(OpenWisprUi.ON_BACKGROUND)
            setLineSpacing(dp(4).toFloat(), 1f); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setTextIsSelectable(true); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        MaterialAlertDialogBuilder(context).setTitle("Original transcription").setView(text)
            .setPositiveButton("Revert to original") { _, _ -> repo.revertToOriginalTranscript(note.id) }
            .setNegativeButton("Close", null).show()
    }

    private fun confirmDelete(note: Note) {
        MaterialAlertDialogBuilder(context).setTitle("Delete voice note?")
            .setMessage("This permanently deletes the recording and transcript.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> player.stop(); repo.deleteNote(note.id); onNoteChanged(); dismiss() }
            .show()
    }

    override fun onDetachedFromWindow() {
        repo.removeListener(repositoryListener); player.release(); super.onDetachedFromWindow()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
