package com.assistant.adi.ui.buddy

import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

fun AlertDialog.withActionIcons(positive: Int = 0, negative: Int = 0, neutral: Int = 0): AlertDialog {
    listOf(
        AlertDialog.BUTTON_POSITIVE to positive,
        AlertDialog.BUTTON_NEGATIVE to negative,
        AlertDialog.BUTTON_NEUTRAL to neutral
    ).forEach { (which, icon) ->
        if (icon != 0) getButton(which)?.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(context, icon), null, null, null)
            compoundDrawableTintList = textColors
            compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        }
    }
    return this
}
