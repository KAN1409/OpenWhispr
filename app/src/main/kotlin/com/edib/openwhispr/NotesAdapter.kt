package com.edib.openwhispr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.edib.openwhispr.OpenWisprUi.dp

/**
 * Adapter for the Notes timeline.
 *
 * Replaces the previous ScrollView + LinearLayout that rebuilt every note view
 * on each refresh (and on every keystroke while searching). Rows are recycled
 * and updates are diffed, so the list stays responsive with 200+ notes.
 *
 * BiDi note: the note title is derived from the transcript and is therefore
 * frequently Arabic. It lives in its own weight-1 cell with FIRST_STRONG /
 * VIEW_START, while duration and the overflow button are separate views, so
 * neither can be re-ordered by the bidirectional algorithm.
 */
internal class NotesAdapter(
    private val onOpen: (Note) -> Unit,
    private val onPinToggle: (Note) -> Unit,
    private val onRetranscribe: (Note) -> Unit,
    private val onCopy: (Note) -> Unit,
    private val onDelete: (Note) -> Unit,
) : ListAdapter<NotesAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    /** One list entry: either a date/pinned header, or a note. */
    sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val note: Note) : Row()
    }

    class HeaderVH(val view: TextView) : RecyclerView.ViewHolder(view)
    class NoteVH(val root: View) : RecyclerView.ViewHolder(root)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Item -> TYPE_NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val inflater = LayoutInflater.from(ctx)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(OpenWisprUi.sectionHeader(ctx, ""))
        } else {
            NoteVH(buildNoteCard(ctx))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).view.text = row.label
            is Row.Item -> bind((holder as NoteVH).root, row.note)
        }
    }

    /** Build the card view tree once per recycled row. */
    private fun buildNoteCard(ctx: android.content.Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padH = ctx.dp(OpenWisprUi.SPACE_LG)
            val padV = ctx.dp(OpenWisprUi.SPACE_SM + 4)
            setPadding(padH, padV, padH, padV)
            background = OpenWisprUi.surface(ctx)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ctx.dp(OpenWisprUi.SPACE_SM) }
            isClickable = true
            isFocusable = true
        }

        // Row 1: transcript preview, 2 lines, right-aligned for Arabic.
        // There is deliberately NO separate title: the transcript is the note's
        // only meaningful text, and showing its first line twice (bold title +
        // preview) was worse than the "Voice note · <time>" placeholder.
        val body = TextView(ctx).apply {
            tag = TAG_BODY
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setLineSpacing(0f, 1.3f)
        }
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Row 2: duration · time · overflow, in that order.
        val meta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(OpenWisprUi.SPACE_XS), 0, 0)
        }
        val metaText = TextView(ctx).apply {
            tag = TAG_META
            textSize = 12f
            setTextColor(OpenWisprUi.mutedText(ctx))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        meta.addView(metaText)
        // The menu is bound in bind(), which knows the note; this row view is
        // shared across every card through the recycler.
        val more = OpenWisprUi.iconImageButton(
            ctx, R.drawable.ic_more_vert, "Note options"
        ) { }
        more.tag = TAG_MORE
        more.setColorFilter(OpenWisprUi.secondaryText(ctx))
        more.layoutParams = LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48))
        meta.addView(more)
        root.addView(meta)

        return root
    }

    private fun bind(root: View, note: Note) {
        val ctx = root.context
        val body = root.findViewWithTag<TextView>(TAG_BODY)
        val meta = root.findViewWithTag<TextView>(TAG_META)
        val more = root.findViewWithTag<android.widget.ImageView>(TAG_MORE)
        more.setImageResource(
            if (note.isPinned) R.drawable.ic_push_pin else R.drawable.ic_more_vert
        )
        more.contentDescription =
            if (note.isPinned) "Pinned note options" else "Note options"

        when (note.transcriptionState) {
            Note.State.PENDING -> {
                body.setTextColor(OpenWisprUi.secondaryText(ctx))
                body.text = "Transcribing…"
            }
            Note.State.FAILED -> {
                body.setTextColor(OpenWisprUi.DANGER)
                body.text = "Couldn't transcribe — your recording is safe."
            }
            Note.State.COMPLETE -> {
                body.setTextColor(OpenWisprUi.primaryText(ctx))
                body.text = note.displayTranscript?.takeIf { it.isNotBlank() }
                    ?: "No speech detected"
            }
        }
        meta.text = "▶  ${Note.formatDuration(note.audioDurationMs)}  ·  ${Note.formatFooterTime(note.createdAt)}"

        root.setOnClickListener { onOpen(note) }
        more.setOnClickListener { anchor -> showNoteMenu(anchor, note) }
    }

    private fun showNoteMenu(anchor: View, note: Note) {
        val ctx = anchor.context
        PopupMenu(ctx, anchor).apply {
            menu.add(if (note.isPinned) "Unpin note" else "Pin note")
            menu.add("Copy transcript")
            menu.add("Retranscribe")
            menu.add("Delete")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Pin note", "Unpin note" -> onPinToggle(note)
                    "Copy transcript" -> onCopy(note)
                    "Retranscribe" -> onRetranscribe(note)
                    "Delete" -> onDelete(note)
                }
                true
            }
            show()
        }
    }

    /**
     * Card text comes straight from the transcript. There is no separate title:
     * an earlier revision derived the title from the transcript's first line,
     * which printed the same sentence twice per card.
     */

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_NOTE = 1

        private const val TAG_BODY = "ow_body"
        private const val TAG_META = "ow_meta"
        private const val TAG_MORE = "ow_more"

        /**
         * Group notes exactly as the previous implementation did, so the
         * ordering a user already sees is preserved: pinned first, then
         * unpinned grouped by day.
         */
        fun buildRows(notes: List<Note>, searchQuery: String): List<Row> {
            if (notes.isEmpty()) return emptyList()
            val rows = ArrayList<Row>(notes.size + 4)
            val pinned = notes.filter { it.isPinned }
            val unpinned = notes.filter { !it.isPinned }
            if (pinned.isNotEmpty()) {
                rows.add(Row.Header("PINNED"))
                pinned.forEach { rows.add(Row.Item(it)) }
            }
            val grouped = unpinned.groupBy { Note.formatDateHeader(it.createdAt) }
            for ((day, items) in grouped) {
                rows.add(Row.Header(day.uppercase()))
                items.forEach { rows.add(Row.Item(it)) }
            }
            return rows
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> a.label == b.label
                a is Row.Item && b is Row.Item -> a.note.id == b.note.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row) = when {
                a is Row.Header && b is Row.Header -> true
                a is Row.Item && b is Row.Item ->
                    a.note == b.note || a.note.transcriptionState == b.note.transcriptionState &&
                        a.note.displayTranscript == b.note.displayTranscript
                else -> false
            }
        }
    }
}
