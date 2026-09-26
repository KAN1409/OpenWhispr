package com.edib.openwhispr

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Shared visual language for OpenWispr.
 *
 * Colours are resolved from the active theme so light mode is a real
 * counterpart of dark mode rather than a dark-only palette with inverted
 * widgets. [resolve] walks up the view tree to find the resolved theme
 * attribute, falling back to the dark defaults that match the brand.
 */
internal object OpenWisprUi {

    // --- Spacing scale (4dp derived) ---
    const val SPACE_XS = 4
    const val SPACE_SM = 8
    const val SPACE_MD = 12
    const val SPACE_LG = 16
    const val SPACE_XL = 24
    const val SPACE_2XL = 32

    // --- Shape ---
    const val RADIUS = 12

    // --- Brand accent (theme-independent: one restrained accent) ---
    const val ACCENT = 0xFF5B8DEF.toInt()
    const val RECORD = 0xFFEF4444.toInt()
    const val DANGER = 0xFFE5484D.toInt()
    const val SUCCESS = 0xFF3FA66A.toInt()

    // --- Fallbacks (dark values) ---
    private const val BG_DARK = 0xFF0B0D10.toInt()
    private const val SURFACE_DARK = 0xFF171A1F.toInt()
    private const val TEXT_DARK = 0xFFF4F5F7.toInt()
    private const val TEXT2_DARK = 0xFFA5ABB5.toInt()
    private const val TEXT3_DARK = 0xFF7C838E.toInt()
    private const val BG_LIGHT = 0xFFF6F7F9.toInt()
    private const val SURFACE_LIGHT = 0xFFFFFFFF.toInt()
    private const val TEXT_LIGHT = 0xFF15171A.toInt()
    private const val TEXT2_LIGHT = 0xFF4A5058.toInt()
    private const val TEXT3_LIGHT = 0xFF6B727C.toInt()

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun Context.sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /** True when the resolved theme is a light (DayNight "light") configuration. */
    fun isLight(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    // Legacy names, kept so presentation code that has not been migrated to
    // the theme-aware accessors below still compiles. They resolve to the
    // dark palette.
    const val BACKGROUND = BG_DARK
    const val SURFACE = SURFACE_DARK
    const val SURFACE_PRESSED = 0xFF22262D.toInt()
    const val TEXT = TEXT_DARK
    const val TEXT_SECONDARY = TEXT2_DARK
    const val TEXT_MUTED = TEXT3_DARK
    const val BLUE = ACCENT
    const val RED = DANGER
    const val GREEN = SUCCESS

    // Theme-resolved colours. Call from a themed Context.
    fun bg(context: Context): Int =
        if (isLight(context)) BG_LIGHT else BG_DARK

    fun card(context: Context): Int =
        if (isLight(context)) SURFACE_LIGHT else SURFACE_DARK

    fun primaryText(context: Context): Int =
        if (isLight(context)) TEXT_LIGHT else TEXT_DARK

    fun secondaryText(context: Context): Int =
        if (isLight(context)) TEXT2_LIGHT else TEXT2_DARK

    fun mutedText(context: Context): Int =
        if (isLight(context)) TEXT3_LIGHT else TEXT3_DARK

    fun surface(context: Context, radiusDp: Int = RADIUS, color: Int = card(context)) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
        }

    fun pressable(context: Context, radiusDp: Int = RADIUS) = GradientDrawable().apply {
        setColor(card(context))
        cornerRadius = context.dp(radiusDp).toFloat()
    }

    /**
     * Section header ("TODAY", "TRANSCRIPTION"). Small, spaced, and using
     * [secondaryText] rather than the dimmest tone: at 12sp bold the muted
     * grey sat below 4.5:1 contrast on the card/background.
     */
    fun sectionHeader(context: Context, title: String): TextView = TextView(context).apply {
        text = title
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(secondaryText(context))
        letterSpacing = 0.08f
        setPadding(0, context.dp(SPACE_LG), 0, context.dp(SPACE_SM))
    }

    /**
     * Icon button backed by a real VectorDrawable.
     *
     * The previous implementation used text glyphs ("⌕", "⚙", "⋮") rendered in
     * the UI's own typeface. Those depend on the font, render at different
     * weights per device, and were what made the icon row look inconsistent. A
     * tinted vector is identical everywhere and scales cleanly.
     */
    fun iconImageButton(
        context: Context,
        iconRes: Int,
        description: String,
        onClick: (View) -> Unit
    ): android.widget.ImageButton {
        val dp48 = context.dp(48)
        val dp12 = context.dp(12)
        val tint = primaryText(context)
        return android.widget.ImageButton(context).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp12, dp12, dp12, dp12)
            // A 48dp minimum keeps the target usable even though the icon
            // itself is only 24dp.
            minimumWidth = dp48
            minimumHeight = dp48
            // A circular ripple, not a rounded-rect surface. The rounded rect
            // was clipped against the screen edge on the top-bar icons and
            // showed as a stray pale sliver down the right side in light mode.
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22808080),
                null,
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                }
            )
            stateListAnimator = null
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { v -> onClick(v) }
        }
    }
}
