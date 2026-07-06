package com.latenighthack.social.messages.view

import android.content.Context
import android.util.TypedValue
import android.view.View

internal fun Context.dpToPx(value: Float): Float = value * resources.displayMetrics.density

internal fun Context.dpToPxInt(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun exactlyFullWidthMeasureSpec(widthMeasureSpec: Int): Int =
    View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.EXACTLY)

internal fun Context.selectableItemBackgroundRes(): Int {
    val outValue = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
    return outValue.resourceId
}
