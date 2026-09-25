package com.edib.openwhispr

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.TextView

internal object OpenWisprUi {
    const val BACKGROUND = 0xFF101114.toInt()
    const val SURFACE = 0xFF1A1C20.toInt()
    const val SURFACE_HIGH = 0xFF24272C.toInt()
    const val PRIMARY = 0xFF8AB4F8.toInt()
    const val ON_BACKGROUND = 0xFFF1F3F4.toInt()
    const val ON_SURFACE = 0xFFDADCE0.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val DIVIDER = 0xFF303238.toInt()
    const val ERROR = 0xFFF28B82.toInt()
    const val RECORDING = 0xFFEA4335.toInt()

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, context: Context) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }

    fun iconButton(context: Context, icon: Int, description: String, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = description
            setColorFilter(ON_SURFACE)
            background = null
            minimumWidth = context.dp(48)
            minimumHeight = context.dp(48)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun sectionLabel(context: Context, textValue: String) = TextView(context).apply {
        text = textValue
        textSize = 12f
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(MUTED)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
