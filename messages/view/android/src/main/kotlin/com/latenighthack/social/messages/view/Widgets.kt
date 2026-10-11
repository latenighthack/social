package com.latenighthack.social.messages.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.style.CharacterStyle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.latenighthack.social.messages.v1.Image
import kotlin.math.min

/** Marker for children that should span the full width of their parent (buttons). */
internal interface FullWidth

/** A 1px line; horizontal in a vertical stack, vertical in a horizontal stack. */
internal class DividerView(context: Context, color: Int, private val isHorizontal: Boolean) : View(context) {
    private val thickness = context.dpToPxInt(1f).coerceAtLeast(1)

    init {
        setBackgroundColor(color)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isHorizontal) {
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(thickness, MeasureSpec.EXACTLY))
            } else {
                setMeasuredDimension(0, thickness)
            }
        } else {
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(thickness, MeasureSpec.EXACTLY), heightMeasureSpec)
            } else {
                setMeasuredDimension(thickness, 0)
            }
        }
    }
}

/** TextView that shrinks to its longest line and dispatches taps to Clickable spans. */
internal class MessageTextView(context: Context) : AppCompatTextView(context) {
    private var selectedSpan: CharacterStyle? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val l = layout ?: return
        var maxLineLength = 0
        var i = 0
        while (i < l.lineCount && maxLineLength < measuredWidth) {
            maxLineLength = maxOf(l.getLineWidth(i).toInt(), maxLineLength)
            ++i
        }
        setMeasuredDimension(maxLineLength, measuredHeight)
    }

    private fun clickableSpanForPoint(px: Int, py: Int): Any? {
        val current = text
        if (current !is Spanned) return null
        var x = px - paddingLeft + scrollX
        val y = py - paddingTop + scrollY
        val line = layout.getLineForVertical(y)
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        return current.getSpans(off, off, Clickable::class.java).firstOrNull()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val spannable = text as? Spanned
        if (spannable == null || !isClickable || event == null) return super.onTouchEvent(event)
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val span = clickableSpanForPoint(event.x.toInt(), event.y.toInt())
                if (span != null) {
                    selectedSpan = span as CharacterStyle
                    (span as? TappableSpan)?.let { it.setIsSelected(true); invalidate() }
                    true
                } else {
                    super.onTouchEvent(event)
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                (selectedSpan as? TappableSpan)?.let { it.setIsSelected(false); invalidate() }
                selectedSpan = null
                super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                val active = selectedSpan
                (active as? TappableSpan)?.let { it.setIsSelected(false); invalidate() }
                selectedSpan = null
                if (active != null) {
                    val span = clickableSpanForPoint(event.x.toInt(), event.y.toInt())
                    if (span != null && active === span && span is Clickable) span.onClick(this)
                    true
                } else {
                    super.onTouchEvent(event)
                }
            }
            else -> super.onTouchEvent(event)
        }
    }
}

/** Aspect-ratio image with a preview-colour placeholder; a bitmap can be supplied by an ImageLoader. */
internal class MessageImageView(
    context: Context,
    previewColor: Int,
    aspectRatio: Float,
    private val style: Image.Style,
) : View(context) {
    private val ar = if (aspectRatio > 0f) aspectRatio else 1f
    private val maxWidth = context.dpToPxInt(240f)
    private val smallSize = context.dpToPxInt(64f)
    private val mediumSize = context.dpToPxInt(160f)
    private val cornerRadius = context.dpToPx(12f)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = previewColor }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val matrix = Matrix()

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val (w, h) = when (style) {
            Image.Style.SMALL -> smallSize to smallSize
            Image.Style.CIRCULAR -> smallSize to smallSize
            Image.Style.MEDIUM -> mediumSize to (mediumSize / ar).toInt()
            Image.Style.SQUARE -> min(avail, maxWidth).let { it to it }
            else -> min(avail, maxWidth).let { it to (it / ar).toInt() }
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        if (style == Image.Style.CIRCULAR) {
            clipPath.addOval(rect, Path.Direction.CW)
        } else {
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(rect, bgPaint)
        bitmap?.let { bmp ->
            val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate((width - bmp.width * scale) / 2f, (height - bmp.height * scale) / 2f)
            canvas.drawBitmap(bmp, matrix, bitmapPaint)
        }
        canvas.restore()
    }
}

/** Full-width tappable button. Optional rounded fill (CTA/pill) or plain text (default). */
internal class BasicButton(
    context: Context,
    textColor: Int,
    backgroundColor: Int,
    cornerRadius: Float,
) : AppCompatTextView(context), FullWidth {
    private val fixedHeight = context.dpToPxInt(44f)

    init {
        setTextColor(textColor)
        textSize = 16f
        gravity = Gravity.CENTER
        isClickable = true
        if (backgroundColor != android.graphics.Color.TRANSPARENT) {
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                setCornerRadius(cornerRadius)
            }
        } else {
            setBackgroundResource(context.selectableItemBackgroundRes())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(exactlyFullWidthMeasureSpec(widthMeasureSpec), MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY))
    }
}

/** Full-width button with a top separator line (grouped style). */
internal class GroupedButton(context: Context, textColor: Int, lineColor: Int) : AppCompatTextView(context), FullWidth {
    private val fixedHeight = context.dpToPxInt(44f)
    private val linePaint = Paint().apply { color = lineColor }

    init {
        setTextColor(textColor)
        textSize = 16f
        gravity = Gravity.CENTER
        isClickable = true
        setBackgroundResource(context.selectableItemBackgroundRes())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(exactlyFullWidthMeasureSpec(widthMeasureSpec), MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, linePaint)
    }
}
