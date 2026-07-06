package com.latenighthack.social.messages.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.latenighthack.social.messages.v1.Action
import com.latenighthack.social.messages.v1.Button
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.messages.v1.Container
import com.latenighthack.social.messages.v1.Image
import com.latenighthack.social.messages.v1.Inline
import com.latenighthack.social.messages.v1.Text

/** Invoked when a component with an attached Action (button, tappable link, etc.) is activated. */
fun interface ActionHandler {
    fun performAction(action: Action)
}

/** Loads a remote image for [url] and delivers the decoded bitmap (or null) on the main thread. */
fun interface ImageLoader {
    fun load(url: String, onBitmap: (Bitmap?) -> Unit)
}

/**
 * Builds a classic Android View hierarchy for a messages.v1.Component tree.
 * Ported from the reference client's MessageLayoutBuilder, retargeted onto :messages-api's
 * generated types and made self-contained (no design-system singletons or image library).
 */
class MessageLayoutBuilder(
    private val context: Context,
    private val imageLoader: ImageLoader? = null,
    private val actionHandler: ActionHandler? = null,
) {
    /** Full render of a message. */
    fun build(theme: MessageTheme, root: Component): View = buildLayout(theme, root, null, false)

    /** Single-line text preview for a room/conversation list row. */
    fun buildPreview(theme: MessageTheme, root: Component): View {
        val discovered = mutableListOf<Text>()
        findPreviewText(root, discovered)
        val preview = pickPreview(discovered)

        val view = MessageTextView(context)
        view.setTextColor(theme.textColor)
        view.setLinkTextColor(theme.linkTextColor)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.defaultTextSize)
        view.setSingleLine(true)
        view.maxLines = 1
        view.ellipsize = TextUtils.TruncateAt.END
        if (preview != null) {
            if (preview.inlines.isEmpty()) {
                view.text = preview.text
            } else {
                view.setText(createSpannableInlines(theme, preview.text, preview.inlines), TextView.BufferType.SPANNABLE)
            }
        }
        return view
    }

    private fun findPreviewText(root: Component, out: MutableList<Text>) {
        val container = root.contents?.getContainer()
        if (container != null) {
            for (child in container.children) findPreviewText(child, out)
        } else {
            root.contents?.getText()?.let { out.add(it) }
        }
    }

    private fun pickPreview(discovered: List<Text>): Text? {
        val order = listOf(Text.Style.DEFAULT, Text.Style.TITLE, Text.Style.SUBTITLE, Text.Style.DESCRIPTION)
        for (style in order) {
            discovered.firstOrNull { it.style == style && it.text.isNotBlank() }?.let { return it }
        }
        return discovered.firstOrNull { it.text.isNotBlank() }
    }

    private fun buildLayout(theme: MessageTheme, root: Component, parent: Container?, inOverlay: Boolean): View {
        val container = root.contents?.getContainer()
        if (container == null) {
            root.contents?.getButton()?.let { return buildButton(theme, root, it) }
            root.contents?.getImage()?.let { return buildImage(theme, root, it) }
            root.contents?.getText()?.let { return buildText(theme, root, it, inOverlay) }
            root.contents?.getDivider()?.let {
                val horizontal = parent == null || parent.contents?.getVerticalStack() != null
                return DividerView(context, theme.dividerColor, horizontal)
            }
            return View(context)
        }

        val childInOverlay = inOverlay || parent?.contents?.getOverlay() != null
        val childViews = container.children.map { buildLayout(theme, it, container, childInOverlay) }

        container.contents?.getBox()?.let { return assemble(BoxLayout(context), childViews, theme, root) }
        container.contents?.getQuote()?.let { return assemble(QuoteLayout(context, theme.dividerColor), childViews, theme, root) }
        container.contents?.getVerticalStack()?.let { stack ->
            val layout = VerticalStackLayout(context)
            when (stack.alignment) {
                Container.HorizontalAlignment.LEFT -> layout.gravity = Gravity.LEFT
                Container.HorizontalAlignment.CENTER_HORIZONTAL -> layout.gravity = Gravity.CENTER_HORIZONTAL
                Container.HorizontalAlignment.RIGHT -> layout.gravity = Gravity.RIGHT
                else -> {}
            }
            return assemble(layout, childViews, theme, root)
        }
        container.contents?.getHorizontalStack()?.let { stack ->
            val layout = HorizontalStackLayout(context)
            when (stack.alignment) {
                Container.VerticalAlignment.TOP -> layout.gravity = Gravity.TOP
                Container.VerticalAlignment.CENTER_VERTICAL -> layout.gravity = Gravity.CENTER_VERTICAL
                Container.VerticalAlignment.BOTTOM -> layout.gravity = Gravity.BOTTOM
                else -> {}
            }
            return assemble(layout, childViews, theme, root)
        }
        container.contents?.getBubble()?.let {
            val layout: ViewGroup = if (theme.isImmersive) {
                FrameLayout(context)
            } else {
                MessageBubble(context).apply { setBackgroundColor(theme.bubbleBackgroundColor) }
            }
            return assemble(layout, childViews, theme, root)
        }
        container.contents?.getOverlay()?.let { return assemble(OverlayLayout(context), childViews, theme, root) }
        container.contents?.getGrid()?.let { return buildGrid(theme, root, it, childViews) }

        return View(context)
    }

    private fun assemble(layout: ViewGroup, children: List<View>, theme: MessageTheme, root: Component): View {
        var previous: View? = null
        for (child in children) {
            layout.addView(child, defaultLayoutParams(child, previous, theme))
            previous = child
        }
        return attachActionHandler(layout, root)
    }

    private fun buildText(theme: MessageTheme, root: Component, text: Text, inOverlay: Boolean): View {
        val view = MessageTextView(context)
        if (text.inlines.isEmpty()) {
            view.text = text.text
        } else {
            view.setText(createSpannableInlines(theme, text.text, text.inlines), TextView.BufferType.SPANNABLE)
            view.isClickable = true
        }
        view.setLinkTextColor(if (inOverlay) theme.overlayTextColor else theme.linkTextColor)
        when (text.style) {
            Text.Style.TITLE -> {
                view.setTextColor(if (inOverlay) theme.overlayTitleTextColor else theme.titleTextColor)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.titleTextSize)
                view.setTypeface(view.typeface, Typeface.BOLD)
                view.setSingleLine(true)
                view.maxLines = 1
                view.ellipsize = TextUtils.TruncateAt.END
            }
            Text.Style.SUBTITLE -> {
                view.setTextColor(if (inOverlay) theme.overlaySubtitleTextColor else theme.subtitleTextColor)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.subtitleTextSize)
                view.maxLines = 2
                view.ellipsize = TextUtils.TruncateAt.END
            }
            Text.Style.DESCRIPTION -> {
                view.setTextColor(if (inOverlay) theme.overlayDescriptionTextColor else theme.descriptionTextColor)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.descriptionTextSize)
            }
            else -> {
                view.setTextColor(if (inOverlay) theme.overlayTextColor else theme.textColor)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.defaultTextSize)
            }
        }
        return attachActionHandler(view, root)
    }

    private fun buildImage(theme: MessageTheme, root: Component, image: Image): View {
        val ref = image.image
        val previewColor = (ref?.previewColor ?: 0) or 0xff000000.toInt()
        val aspect = ref?.aspectRatio ?: 1f
        val view = MessageImageView(context, previewColor, aspect, image.style)
        val url = ref?.url ?: ""
        if (url.isNotEmpty()) imageLoader?.load(url) { view.bitmap = it }
        return attachActionHandler(view, root)
    }

    private fun buildButton(theme: MessageTheme, root: Component, button: Button): View {
        val view: AppCompatTextView = when (button.style) {
            Button.Style.GROUPED -> GroupedButton(context, theme.buttonTextColor, theme.dividerColor)
            Button.Style.CTA -> BasicButton(context, theme.ctaTextColor, theme.ctaBackgroundColor, context.dpToPx(10f))
            Button.Style.PILL -> BasicButton(context, theme.buttonTextColor, Color.TRANSPARENT, context.dpToPx(22f))
            else -> BasicButton(context, theme.buttonTextColor, Color.TRANSPARENT, 0f)
        }
        view.text = button.text
        return attachActionHandler(view, root)
    }

    private fun buildGrid(theme: MessageTheme, root: Component, grid: Container.Grid, childViews: List<View>): View {
        val cols = grid.columns.size.coerceAtLeast(1)
        val layout = GridLayout(context).apply {
            columnCount = cols
            useDefaultMargins = false
        }
        val striped = grid.style == Container.Grid.Style.STRIPED
        val bordered = grid.style == Container.Grid.Style.BORDER
        val pad = context.dpToPxInt(6f)
        if (bordered) layout.setBackgroundColor(theme.dividerColor)

        childViews.forEachIndexed { i, child ->
            val row = i / cols
            val col = i % cols
            val cell = FrameLayout(context).apply {
                setPadding(pad, pad, pad, pad)
                when {
                    bordered -> setBackgroundColor(0xFF1B1A24.toInt())
                    striped && row % 2 == 1 -> setBackgroundColor(0x0AFFFFFF)
                }
                addView(child, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col)).apply {
                if (bordered) setMargins(1, 1, 1, 1)
            }
            layout.addView(cell, lp)
        }
        return attachActionHandler(layout, root)
    }

    private fun defaultLayoutParams(child: View, previous: View?, theme: MessageTheme): ViewGroup.MarginLayoutParams {
        val lp = ViewGroup.MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        when {
            child is FullWidth -> {}
            child is AppCompatTextView -> {
                if (!theme.isImmersive) {
                    lp.leftMargin = theme.textHorizontalMargin
                    lp.rightMargin = theme.textHorizontalMargin
                }
                if (previous is AppCompatTextView) {
                    lp.topMargin = -theme.textBottomMargin
                    lp.bottomMargin = theme.textBottomMargin
                } else {
                    lp.topMargin = theme.textVerticalMargin
                    lp.bottomMargin = theme.textBottomMargin
                }
            }
            child is DividerView -> {
                if (!theme.isImmersive) {
                    lp.leftMargin = theme.textHorizontalMargin
                    lp.rightMargin = theme.textHorizontalMargin
                }
            }
        }
        return lp
    }

    private fun attachActionHandler(view: View, component: Component): View {
        val action = component.action ?: return view
        val handler = actionHandler ?: return view
        view.setOnClickListener { handler.performAction(action) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.foreground = context.getDrawable(context.selectableItemBackgroundRes())
        }
        return view
    }

    private fun createSpannableInlines(theme: MessageTheme, text: String, inlines: List<Inline>): Spannable {
        val builder = SpannableStringBuilder(text)
        val flags = Spannable.SPAN_INCLUSIVE_INCLUSIVE
        for (inline in inlines) {
            val start = inline.offset.coerceIn(0, text.length)
            val end = (inline.offset + inline.length).coerceIn(start, text.length)
            if (end <= start) continue
            val rule = inline.rule?.contents ?: continue
            when {
                rule.getBold() != null -> builder.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
                rule.getItalic() != null -> builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
                rule.getStrikethrough() != null -> builder.setSpan(strikethroughSpan(), start, end, flags)
                rule.getRedaction() != null -> builder.setSpan(RedactionSpan(theme.redactionColor), start, end, flags)
                rule.getUserLink() != null -> {
                    builder.setSpan(ForegroundColorSpan(theme.linkTextColor), start, end, flags)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
                }
                rule.getIcon() != null -> {
                    val icon = IconSpan(context)
                    builder.setSpan(icon, start, end, flags)
                    rule.getIcon()?.image?.url?.takeIf { it.isNotEmpty() }?.let { url ->
                        imageLoader?.load(url) { icon.bitmap = it }
                    }
                }
                rule.getTappable() != null -> {
                    val tappable = rule.getTappable()!!
                    if (tappable.style == Inline.Rule.Tappable.Style.LINK) {
                        val span = LinkSpan(theme.linkTextColor)
                        val action = tappable.action
                        if (action != null) {
                            span.onClickListener = SpanClickListener { actionHandler?.performAction(action) }
                        }
                        builder.setSpan(span, start, end, flags)
                    }
                }
            }
        }
        return builder
    }
}
