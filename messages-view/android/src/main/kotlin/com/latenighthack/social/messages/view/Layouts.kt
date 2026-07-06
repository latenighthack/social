package com.latenighthack.social.messages.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** A frame that pins every child to its bottom edge (respecting margins). */
internal open class BoxLayout(context: Context) : FrameLayout(context) {
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as MarginLayoutParams
            child.layout(
                lp.leftMargin,
                (bottom - top) - child.measuredHeight - lp.bottomMargin,
                (right - left) - lp.rightMargin,
                (bottom - top) - lp.bottomMargin,
            )
        }
    }
}

/** A [BoxLayout] that clips its contents to a rounded-rect bubble. */
internal class MessageBubble(context: Context) : BoxLayout(context) {
    private val radius: Float = context.dpToPx(18f)
    private var clipPathValid = false
    private val clipPath = Path()

    private fun rebuildPath() {
        clipPath.reset()
        val radii = FloatArray(8) { radius }
        clipPath.addRoundRect(RectF(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat()), radii, Path.Direction.CW)
        clipPathValid = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        clipPathValid = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        if (!clipPathValid) rebuildPath()
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        if (!clipPathValid) rebuildPath()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restore()
    }
}

/** A [BoxLayout] that paints a translucent dark scrim behind its children. */
internal class OverlayLayout(context: Context) : BoxLayout(context) {
    private val scrimPaint = Paint().apply {
        color = 0xFF000000.toInt()
        alpha = 0x60
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        super.dispatchDraw(canvas)
    }
}

/** A frame with a vertical accent bar on the left, for quoted content. */
internal class QuoteLayout(context: Context, color: Int) : FrameLayout(context) {
    private val linePaint = Paint().apply { this.color = color }
    private val barInset = context.dpToPx(16f)
    private val barTopBottom = context.dpToPx(4f)
    private val barWidth = context.dpToPx(3f)

    init {
        setPadding(context.dpToPxInt(24f), context.dpToPxInt(4f), 0, context.dpToPxInt(4f))
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRect(barInset, barTopBottom, barInset + barWidth, height - barTopBottom, linePaint)
        super.dispatchDraw(canvas)
    }
}

/** Vertical LinearLayout; stretches DividerView children to the measured width. */
internal class VerticalStackLayout(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is DividerView) {
                val lp = child.layoutParams as MarginLayoutParams
                child.measure(
                    MeasureSpec.makeMeasureSpec(measuredWidth - lp.leftMargin - lp.rightMargin, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
                )
            }
        }
    }
}

/** Horizontal LinearLayout; stretches DividerView children to the measured height. */
internal class HorizontalStackLayout(context: Context) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is DividerView) {
                val lp = child.layoutParams as MarginLayoutParams
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight - lp.topMargin - lp.bottomMargin, MeasureSpec.EXACTLY),
                )
            }
        }
    }
}
