package com.edib.openwhispr

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

internal object OpenWisprUi {
    const val BACKGROUND = 0xFF0B0D10.toInt()
    const val SURFACE = 0xFF171A1F.toInt()
    const val SURFACE_PRESSED = 0xFF22262D.toInt()
    const val TEXT = 0xFFF4F5F7.toInt()
    const val TEXT_SECONDARY = 0xFFA5ABB5.toInt()
    const val TEXT_MUTED = 0xFF747B86.toInt()
    const val BLUE = 0xFF5B8DEF.toInt()
    const val RED = 0xFFE65353.toInt()
    const val GREEN = 0xFF4CAF78.toInt()

    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun surface(context: Context, radiusDp: Int = 12, color: Int = SURFACE) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
        }

    fun iconButton(context: Context, glyph: String, description: String): TextView =
        TextView(context).apply {
            text = glyph
            contentDescription = description
            textSize = 21f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            minWidth = context.dp(48)
            minHeight = context.dp(48)
            isClickable = true
            isFocusable = true
            background = context.getDrawable(android.R.drawable.list_selector_background)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
}
