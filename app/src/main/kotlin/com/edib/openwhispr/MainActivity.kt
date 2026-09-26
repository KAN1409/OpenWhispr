package com.edib.openwhispr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRow: LinearLayout
    private lateinit var audioRowSub: TextView
    private lateinit var audioDot: View
    private lateinit var accRow: LinearLayout
    private lateinit var accRowSub: TextView
    private lateinit var accDot: View
    private lateinit var accCaption: TextView
    private lateinit var batteryRow: LinearLayout
    private lateinit var batteryRowSub: TextView
    private lateinit var batteryDot: View
    private lateinit var setupCollapsedRow: LinearLayout
    private lateinit var setupCollapsedRowSub: TextView
    private lateinit var setupDoneSummary: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var customInstructionsRowSub: TextView
    private lateinit var customInstructionsRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var voiceCommandsDetailContainer: LinearLayout
    private lateinit var triggerPhraseRowSub: TextView
    private lateinit var statusContainer: LinearLayout
    private lateinit var dictationContainer: LinearLayout
    private lateinit var settingsContainer: LinearLayout
    private lateinit var rootContainer: FrameLayout
    private lateinit var notesView: NotesTimelineView
    private lateinit var settingsScrollView: ScrollView

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private lateinit var engineSummary: TextView
    private var batteryWarningShown = false
    private var setupExpanded = false

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val actionBtn: MaterialButton
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Best-effort: lets the background service show its "still running"
        // notification (Android 13+ requires this permission for any
        // notification, including the foreground-service one). Not gated on
        // anything -- dictation works fine without it, this just makes the
        // service more likely to survive being swiped from Recents.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        checkForUpdate()
        Thread { NoteTranscriber.resumePendingNotes(applicationContext) }.start()

        val outer = vertical(0, 0).apply {
            setBackgroundColor(OpenWisprUi.bg(this@MainActivity))
        }

        val backToNotesBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), 0)
            val backText = TextView(this@MainActivity).apply {
                text = "‹"
                contentDescription = "Back to notes"
                textSize = 32f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF3B82F6.toInt())
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener { showNotesScreen() }
            }
            addView(backText)
            addView(TextView(this@MainActivity).apply {
                text = "Settings"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(OpenWisprUi.TEXT)
            })
        }
        outer.addView(backToNotesBar)

        statusContainer = vertical(0)
        dictationContainer = vertical(0)
        settingsContainer = vertical(0)

        // Setup and status live inside the unified Settings destination.
        statusContainer.addView(sectionHeader("SETUP & STATUS"))

        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        statusContainer.addView(statusRow)

        // --- Setup checklist card ---
        setupCollapsedRow = settingsRow("Setup", "Checking...") {
            setupExpanded = !setupExpanded
            refresh()
        }
        setupCollapsedRowSub = setupCollapsedRow.findViewWithTag("subtitle")
        statusContainer.addView(setupCollapsedRow)

        setupDoneSummary = TextView(this).apply {
            textSize = 14f
            setTextColor(DOT_GREEN)
            setPadding(dp(24), 0, dp(24), dp(8))
        }
        statusContainer.addView(setupDoneSummary)

        audioDot = statusDot()
        audioRow = settingsRow("Audio permission", "Checking...", leading = audioDot) {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        statusContainer.addView(audioRow)

        accDot = statusDot()
        accRow = settingsRow("Accessibility service", "Checking...", leading = accDot) {
            val alreadyEnabled = WhisperAccessibilityService.instance != null
            if (!alreadyEnabled && android.os.Build.VERSION.SDK_INT >= 33) {
                showRestrictedSettingsHelp()
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        statusContainer.addView(accRow)

        accCaption = TextView(this).apply {
            text = "Used to insert dictated text into the active text field."
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            alpha = 0.8f
            setPadding(dp(24), 0, dp(24), dp(12))
        }
        statusContainer.addView(accCaption)

        batteryDot = statusDot()
        batteryRow = settingsRow("Battery optimization", "Checking...", leading = batteryDot) {
            requestBatteryExemption()
        }
        batteryRowSub = batteryRow.findViewWithTag("subtitle")
        statusContainer.addView(batteryRow)

        // --- Background service ---
        val serviceEnabled = prefs().getBoolean("service_master_enabled", true)
        val serviceSwitch = MaterialSwitch(this).apply {
            isChecked = serviceEnabled
            isClickable = false
        }
        val serviceRow = settingsRow(
            "Background service",
            "Pause the mic overlay without disabling accessibility",
            serviceSwitch
        ) {
            val newVal = !serviceSwitch.isChecked
            prefs().edit().putBoolean("service_master_enabled", newVal).apply()
            serviceSwitch.isChecked = newVal
            WhisperAccessibilityService.instance?.refreshMasterEnabled()
        }
        statusContainer.addView(serviceRow)

        // ================= Transcription and dictation =================

        dictationContainer.addView(sectionHeader("TRANSCRIPTION"))

        // One plain line stating the engine that will actually be used, so the
        // answer to "am I local or cloud?" does not require reading a switch.
        engineSummary = TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.25f)
            setTextColor(OpenWisprUi.secondaryText(this@MainActivity))
            setPadding(0, 0, 0, dp(12))
        }
        dictationContainer.addView(engineSummary)

        val isCloud = !prefs().getBoolean("use_local", true)
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "Requires Groq API key", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        dictationContainer.addView(cloudRow)

        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))
        dictationContainer.addView(modelContainer)

        dictationContainer.addView(sectionHeader("POST-PROCESSING"))

        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        val postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow("Cleanup transcript", "Uses Groq Chat API to fix grammar and punctuation", postProcessSwitch) {
            val newVal = !postProcessSwitch.isChecked
            prefs().edit().putBoolean("use_post_processing", newVal).apply()
            postProcessSwitch.isChecked = newVal
            refresh()
        }
        dictationContainer.addView(postProcessRow)

        customInstructionsRow = settingsRow("Add custom instructions", "Tap to add extra refinements") {
            promptCustomInstructions()
        }
        customInstructionsRowSub = customInstructionsRow.findViewWithTag("subtitle")
        customInstructionsRowSub.maxLines = 2
        customInstructionsRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        dictationContainer.addView(customInstructionsRow)

        dictationContainer.addView(sectionHeader("DICTATION & OVERLAY"))

        val isVoiceCommands = prefs().getBoolean("voice_commands_enabled", false)
        val voiceCommandsSwitch = MaterialSwitch(this).apply {
            isChecked = isVoiceCommands
            isClickable = false
        }
        val voiceCommandsRow = settingsRow(
            "Voice commands",
            "Say a trigger phrase to translate, summarize, and more",
            voiceCommandsSwitch
        ) {
            val newVal = !voiceCommandsSwitch.isChecked
            prefs().edit().putBoolean("voice_commands_enabled", newVal).apply()
            voiceCommandsSwitch.isChecked = newVal
            refresh()
        }
        dictationContainer.addView(voiceCommandsRow)

        voiceCommandsDetailContainer = vertical(0)

        val triggerPhraseRow = settingsRow("Trigger phrase", "Tap to change") { promptTriggerPhrase() }
        triggerPhraseRowSub = triggerPhraseRow.findViewWithTag("subtitle")
        voiceCommandsDetailContainer.addView(triggerPhraseRow)

        val examplesRow = settingsRow("Command examples", "See what you can say") { showCommandExamples() }
        voiceCommandsDetailContainer.addView(examplesRow)

        dictationContainer.addView(voiceCommandsDetailContainer)

        // ================= Settings tab =================

        settingsContainer.addView(sectionHeader("ACCOUNT & API"))

        val keyRow = settingsRow("Groq API Key", "Tap to set") { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        settingsContainer.addView(keyRow)

        settingsContainer.addView(sectionHeader("ABOUT"))

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        settingsContainer.addView(settingsRow("Version", versionName))

        settingsContainer.addView(settingsRow("GitHub", "View source & releases") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/EdiBianco/OpenWhispr")))
            } catch (e: Exception) {
                toast("Couldn't open browser: ${e.message}")
            }
        })

        settingsContainer.addView(settingsRow("Check for updates", "Tap to check now") {
            checkForUpdate(force = true)
        })

        outer.addView(dictationContainer)
        outer.addView(statusContainer)
        outer.addView(settingsContainer)

        settingsScrollView = ScrollView(this).apply {
            setBackgroundColor(OpenWisprUi.bg(this@MainActivity))
            addView(outer)
            visibility = View.GONE
        }

        notesView = NotesTimelineView(this) {
            showSettingsScreen()
        }

        rootContainer = FrameLayout(this).apply {
            addView(notesView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(settingsScrollView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(rootContainer)

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (::notesView.isInitialized) {
            notesView.refreshNotes()
        }
    }

    override fun onDestroy() {
        if (::notesView.isInitialized) notesView.dispose()
        super.onDestroy()
    }

    private fun showNotesScreen() {
        settingsScrollView.visibility = View.GONE
        notesView.visibility = View.VISIBLE
        notesView.refreshNotes()
    }

    private fun showSettingsScreen() {
        notesView.visibility = View.GONE
        settingsScrollView.visibility = View.VISIBLE
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::notesView.isInitialized && notesView.isRecording()) {
            notesView.cancelRecording()
            return
        }
        if (::settingsScrollView.isInitialized && settingsScrollView.visibility == View.VISIBLE) {
            showNotesScreen()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Model Rows ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = ColorStateList.valueOf(OpenWisprUi.ACCENT)
        }

        // The single action for this row. Its label says which of the three
        // distinct operations it performs: Download, Use model, or nothing at
        // all when the model is already active.
        val actionBtn = MaterialButton(this).apply {
            textSize = 13f
            isAllCaps = false
            minWidth = dp(104)
            minHeight = dp(48)
            cornerRadius = dp(10)
            insetTop = 0
            insetBottom = 0
        }

        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(actionBtn)
            addView(radio)
            (actionBtn.layoutParams as LinearLayout.LayoutParams).apply {
                gravity = Gravity.END
            }
        }

        // A casual tap anywhere on the row must NOT change the active model:
        // an accidental tap previously switched the app off Whisper Large v3.
        // Only the explicit button activates a downloaded model.
        val row = settingsRow(
            model.name,
            "",                                  // set by refreshCard()
            rightContainer
        ) {
            // A casual tap on the row is intentionally inert: activating a
            // different model is an explicit choice, not something that should
            // happen because a finger landed on a card.
        }
        row.isClickable = false
        row.isFocusable = false

        actionBtn.setOnClickListener { onModelAction(model) }

        // The quality blurb sits on its own line under the state, so the state
        // ("Downloaded · Active") is never buried inside a wrapped sentence.
        val qualityTv = TextView(this).apply {
            text = model.quality
            textSize = 12f
            setTextColor(OpenWisprUi.mutedText(this@MainActivity))
            setPadding(0, dp(2), 0, 0)
        }
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        textContainer.addView(qualityTv)

        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), actionBtn
        )
        refreshCard(model)

        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return

        // Reached only from the explicit per-row button, never from a row tap.
        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }

        views.actionBtn.isEnabled = false
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.subtitle.text = "Downloading · 0%"

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = "Downloading: ${(state.progress * 100).toInt()}%"
                    }
                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = "Extracting..."
                    }
                    is DownloadState.Done -> {
                        // Download completion and native model activation are deliberately
                        // separate operations. Loading sherpa-onnx immediately from this
                        // callback can terminate the whole app process if a device/native
                        // model combination faults below the JVM (SIGSEGV/SIGABRT), which
                        // Kotlin try/catch cannot intercept. Keep the freshly downloaded
                        // model installed but inactive; the user can explicitly select it
                        // after the UI has returned to a stable state.
                        views.progress.visibility = View.GONE
                        views.actionBtn.isEnabled = true
                        refreshAllCards()
                        refresh()
                        toast("${model.name} downloaded. Tap Use model to activate it.")
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Download failed. Error: ${state.message}"
                        views.actionBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (active) View.VISIBLE else View.GONE

        if (views.progress.visibility == View.GONE) {
            // State is stated in words. The button label states the available
            // operation, so Download and Activate can never be confused.
            val stateText = when {
                active -> "Downloaded · Active · ${model.sizeMb} MB"
                installed -> "Downloaded · ${model.sizeMb} MB"
                else -> "Not downloaded · ${model.sizeMb} MB"
            }
            views.subtitle.text = stateText

            val btn = views.actionBtn
            btn.isEnabled = true
            btn.alpha = 1f
            when {
                active -> {
                    // Already in use: no action to offer.
                    btn.visibility = View.GONE
                }
                installed -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "Use model"
                    styleModelAction(btn)
                }
                else -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "Download"
                    styleModelAction(btn)
                }
            }
        }
    }

    private fun styleModelAction(btn: MaterialButton) {
        btn.setTextColor(OpenWisprUi.ACCENT)
        // Tinted, not a hard-coded colour, so it follows the theme in light mode.
        btn.backgroundTintList = ColorStateList.valueOf(0x1F5B8DEF)
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val hasKey = !prefs().getString("api_key", "").isNullOrBlank()
        val selectedModel = prefs().getString("model_name", "") ?: ""
        val hasModel = selectedModel.isNotBlank() &&
            File(filesDir, "models/$selectedModel").isDirectory
        val unrestricted = isIgnoringBatteryOptimizations()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"
        batteryRowSub.text = if (unrestricted)
            "Unrestricted — won't be shut down to save battery"
        else
            "Tap to allow background activity (recommended)"

        // State the active engine in words, and say plainly when local is
        // selected but unusable, so the mode is never a guess.
        if (::engineSummary.isInitialized) {
            val activeName = MODEL_CATALOG.firstOrNull { it.archive == selectedModel }?.name
                ?: selectedModel.substringAfterLast('/').ifBlank { "local model" }
            engineSummary.text = when {
                !useLocal && hasKey -> "Using cloud transcription (Groq). Requires internet."
                !useLocal -> "Cloud selected, but no API key is set. Transcription will fail."
                hasModel -> "Using $activeName. Runs on this device, works offline."
                else -> "Local selected, but no model is installed. Download one below."
            }
        }

        // --- Setup checklist card ---
        val allOk = audio && acc && unrestricted
        val doneCount = listOf(audio, acc, unrestricted).count { it }

        setupCollapsedRow.visibility = if (allOk) View.VISIBLE else View.GONE
        setupCollapsedRowSub.text = if (setupExpanded) "Tap to collapse" else "Tap to review"

        setupDoneSummary.visibility = if (!allOk && doneCount > 0) View.VISIBLE else View.GONE
        setupDoneSummary.text = "✓ $doneCount of 3 setup steps ready"

        fun rowVisibility(ok: Boolean) =
            if (!ok || (allOk && setupExpanded)) View.VISIBLE else View.GONE

        audioRow.visibility = rowVisibility(audio)
        accRow.visibility = rowVisibility(acc)
        accCaption.visibility = accRow.visibility
        batteryRow.visibility = rowVisibility(unrestricted)

        audioDot.background = dotDrawable(if (audio) DOT_GREEN else DOT_RED)
        accDot.background = dotDrawable(if (acc) DOT_GREEN else DOT_RED)
        batteryDot.background = dotDrawable(if (unrestricted) DOT_GREEN else DOT_RED)

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        customInstructionsRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE

        val voiceCommandsEnabled = prefs().getBoolean("voice_commands_enabled", false)
        voiceCommandsDetailContainer.visibility = if (voiceCommandsEnabled) View.VISIBLE else View.GONE
        triggerPhraseRowSub.text = "\"${prefs().getString("command_trigger_phrase", "Whisper Command")}\""

        val apiKey = prefs().getString("api_key", "") ?: ""
        keyRowSub.text = if (apiKey.isBlank()) "Tap to set"
                         else if (apiKey.length > 7) "gsk_...${apiKey.takeLast(4)}"
                         else "gsk_...***"

        val customInstructions = prefs().getString("custom_instructions", "") ?: ""
        customInstructionsRowSub.text = if (customInstructions.isBlank())
            "Tap to add extra refinements"
        else
            customInstructions.replace("\n", " ")

        // Ready logic
        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && hasKey
        val postReady = !usePostProcessing || hasKey
        val ready = audio && acc && (localReady || cloudReady) && postReady

        statusSubtitle.text = if (ready) "Ready — tap the overlay dot to dictate" else "Setup required"
        statusSubtitle.setTextColor(if (ready) attrColor(androidx.appcompat.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))

        refreshAllCards()
        maybeShowBatteryWarning(acc, unrestricted)
    }

    /** Android 13+ silently disables the Accessibility toggle for apps
     * installed outside the Play Store ("Restricted settings"), with no
     * explanation in the Settings UI itself -- it just looks broken. Walks
     * the user through unlocking it before sending them to the system
     * screen, instead of letting them hit a dead end and assume the app
     * doesn't work. */
    private fun showRestrictedSettingsHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle("One extra step on Android 13+")
            .setMessage(
                "Android blocks this permission by default for apps installed outside the Play Store -- that's normal, not a bug.\n\n" +
                "If the Accessibility toggle looks greyed out or won't switch on:\n" +
                "1. Long-press the OpenWispr icon -> App info\n" +
                "2. Tap the \u22ee menu (top right) -> \"Allow restricted settings\"\n" +
                "3. Come back and enable Accessibility as usual"
            )
            .setPositiveButton("Open Accessibility settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Battery optimization ---

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) { toast("Already unrestricted"); return }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            // Some OEMs block the direct per-app request intent -- fall back
            // to the general battery-optimization list where the user can
            // find OpenWispr and exempt it manually.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Couldn't open battery settings: ${e2.message}")
            }
        }
    }

    /** Checks this repo's GitHub Releases. No backend involved. Shows a
     * dialog linking to the release page when a newer version is
     * available. Runs automatically (and silently, when nothing's new)
     * once per app-open; [force] bypasses the cache interval and always
     * gives feedback, for the manual "Check for updates" row. */
    private fun checkForUpdate(force: Boolean = false) {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        } ?: return

        UpdateChecker.checkForUpdate(prefs(), currentVersion, force) { info ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (info != null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Update available")
                        .setMessage(
                            buildString {
                                append("OpenWispr ${info.version} is available. You're on $currentVersion.")
                                if (!info.notes.isNullOrBlank()) {
                                    append("\n\nWhat's new:\n")
                                    append(info.notes)
                                }
                            }
                        )
                        .setPositiveButton("Update") { _, _ -> downloadAndInstallUpdate(info) }
                        .setNegativeButton("Later", null)
                        .show()
                } else if (force) {
                    toast("You're up to date (v$currentVersion)")
                }
            }
        }
    }

    /** Downloads the release's .apk directly in-app and hands it to the
     * system installer -- no browser tab, no external navigation. The final
     * "install this app?" confirmation is a mandatory Android system dialog
     * for a sideloaded APK and can't be skipped, but everything up to that
     * point (download, progress) happens invisibly inside OpenWispr. */
    private fun downloadAndInstallUpdate(info: UpdateChecker.UpdateInfo) {
        val apkUrl = info.apkUrl
        if (apkUrl == null) {
            // Release has no .apk asset (shouldn't normally happen) -- fall
            // back to the release page rather than doing nothing.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
            } catch (e: Exception) {
                toast("Couldn't open browser: ${e.message}")
            }
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Allow installing updates")
                .setMessage("To install updates in-app, allow OpenWispr to install unknown apps on the next screen, then come back and tap Update again.")
                .setPositiveButton("Continue") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        toast("Couldn't open settings: ${e.message}")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        toast("Downloading update…")
        UpdateChecker.downloadApk(this, apkUrl) { file, error ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    toast("Download failed: ${error ?: "unknown error"}")
                    return@runOnUiThread
                }
                installApk(file)
            }
        }
    }

    private fun installApk(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't start installer: ${e.message}")
        }
    }

    /** Nags the user, once per app-open, if the accessibility service is on
     * but Android is still free to kill it to save battery -- this is the
     * single biggest cause of the overlay silently disappearing until the
     * user re-opens the app. */
    private fun maybeShowBatteryWarning(accessibilityEnabled: Boolean, unrestricted: Boolean) {
        if (!accessibilityEnabled || unrestricted || batteryWarningShown) return
        batteryWarningShown = true
        android.app.AlertDialog.Builder(this)
            .setTitle("Keep dictation running")
            .setMessage(
                "Android's battery saver can shut down OpenWispr's background " +
                "service to save power, which makes the mic overlay disappear until " +
                "you reopen the app.\n\n" +
                "Allow it to run unrestricted so it stays available.\n\n" +
                "On some phones (Samsung, Xiaomi, OnePlus, and others) you may also " +
                "need to allow \"autostart\" or remove OpenWispr from any " +
                "battery/app-sleep manager in your phone's own settings, separately " +
                "from the Android dialog this opens."
            )
            .setPositiveButton("Disable restrictions") { _, _ -> requestBatteryExemption() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptApiKey() {
        val link = TextView(this).apply {
            text = android.text.Html.fromHtml(
                "Don't have one? Get a free key at <a href=\"https://console.groq.com/keys\">console.groq.com/keys</a>",
                android.text.Html.FROM_HTML_MODE_LEGACY
            )
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        }
        val input = EditText(this).apply {
            hint = "gsk_..."
            setText(prefs().getString("api_key", ""))
        }
        val container = vertical(dp(24), dp(8)).apply {
            addView(link)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Groq API Key")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("api_key", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomInstructions() {
        // The base cleanup prompt itself is fixed in PostProcessor and never
        // shown here -- this only lets the user append their own extra
        // refinements on top of it (see PostProcessor.effectivePrompt).
        val input = EditText(this).apply {
            hint = "e.g. always spell out \"NASA\" in full"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setText(prefs().getString("custom_instructions", ""))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Add custom instructions")
            .setMessage("These are appended to OpenWispr's built-in cleanup rules. They can't override its safety, formatting, or self-correction behavior.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("custom_instructions", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptTriggerPhrase() {
        val input = EditText(this).apply {
            hint = "Whisper Command"
            setText(prefs().getString("command_trigger_phrase", "Whisper Command"))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Trigger phrase")
            .setMessage("Say this phrase at the start of a recording to switch into command mode instead of normal dictation.")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val phrase = input.text.toString().trim()
                prefs().edit()
                    .putString("command_trigger_phrase", if (phrase.isBlank()) "Whisper Command" else phrase)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommandExamples() {
        val trigger = prefs().getString("command_trigger_phrase", "Whisper Command") ?: "Whisper Command"
        val message = """
            Say the trigger phrase, then one of these -- applies to whatever's already in the field, or to text you dictate right after the command:

            • "$trigger, summarize this in two sentences"
            • "$trigger, enhance the flow"
            • "$trigger, translate to Italian"
            • "$trigger, make this more formal"
            • "$trigger, turn this into a list"

            You can chain more than one: "$trigger, translate to Italian and turn it into a list" applies them in that order.
        """.trimIndent()
        android.app.AlertDialog.Builder(this)
            .setTitle("Command examples")
            .setMessage(message)
            .setPositiveButton("Got it", null)
            .show()
    }

    // --- UI Helpers ---

    private fun settingsRow(
        title: String,
        subtitle: String,
        widget: View? = null,
        leading: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        if (leading != null) row.addView(leading)

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }

        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })

        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun dotDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun statusDot(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
            marginEnd = dp(12)
        }
        background = dotDrawable(DOT_RED)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("openwhispr", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val DOT_GREEN = 0xFF34C759.toInt()
        private const val DOT_RED = 0xFFEF4444.toInt()
    }
}
