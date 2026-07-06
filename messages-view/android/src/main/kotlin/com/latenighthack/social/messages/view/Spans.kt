package com.latenighthack.social.messages.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.StrikethroughSpan
import android.view.View

/** A span that reports taps back to [MessageTextView]. */
internal interface Clickable {
    fun onClick(widget: View)
}

/** A span whose pressed/selected state can be toggled for touch feedback. */
internal interface TappableSpan {
    fun setIsSelected(selected: Boolean)
}

internal fun interface SpanClickListener {
    fun onClick()
}

/** Tappable inline link. Highlights on press, invokes its listener on tap. */
internal class LinkSpan(private val linkColor: Int) : ClickableSpan(), TappableSpan, Clickable {
    private var isSelected = false
    var onClickListener: SpanClickListener? = null

    override fun setIsSelected(selected: Boolean) {
        isSelected = selected
    }

    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.color = linkColor
        ds.isUnderlineText = true
        ds.bgColor = if (isSelected) (linkColor and 0x00ffffff) or 0x40000000 else 0
    }

    override fun onClick(widget: View) {
        onClickListener?.onClick()
    }
}

/** A redaction "blackout": paints a solid rounded rect over the covered text. */
internal class RedactionSpan(private val color: Int) : android.text.style.ReplacementSpan() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        paint.measureText(text, start, end).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val width = paint.measureText(text, start, end)
        val radius = (bottom - top) * 0.15f
        canvas.drawRoundRect(RectF(x, top.toFloat(), x + width, bottom.toFloat()), radius, radius, this.paint)
    }
}

/** Reuse the platform strikethrough span. */
internal fun strikethroughSpan(): StrikethroughSpan = StrikethroughSpan()

/** Inline icon scaled to the font cap height. */
internal class IconSpan(context: Context) :
    ImageSpan(context, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, false)) {
    private val matrix = Matrix()
    private var overrideDrawable: Drawable? = null

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            overrideDrawable = value?.let { BitmapDrawable(null, it) }
        }

    override fun getDrawable(): Drawable? = overrideDrawable

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val b = overrideDrawable as? BitmapDrawable
        val width = paint.fontMetrics.descent - paint.fontMetrics.ascent
        val baseline = y + paint.fontMetrics.descent
        if (b != null) {
            matrix.setRectToRect(
                RectF(0f, 0f, b.bitmap.width.toFloat(), b.bitmap.height.toFloat()),
                RectF(x, baseline - width, x + width, baseline),
                Matrix.ScaleToFit.CENTER,
            )
            canvas.drawBitmap(b.bitmap, matrix, paint)
        }
    }
}
