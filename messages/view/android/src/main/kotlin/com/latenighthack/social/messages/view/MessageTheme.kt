package com.latenighthack.social.messages.view

import android.content.Context
import android.graphics.Color

/**
 * Visual configuration for the renderer. Ported from the reference client's `MessageTheme`,
 * with colours/margins supplied here rather than pulled from a global theming singleton so the
 * library stays pure and importable. Text sizes are in sp; margins/radius are resolved to px.
 */
data class MessageTheme(
    val defaultTextSize: Float,
    val titleTextSize: Float,
    val subtitleTextSize: Float,
    val descriptionTextSize: Float,
    val textHorizontalMargin: Int,
    val textVerticalMargin: Int,
    val textBottomMargin: Int,
    val textColor: Int,
    val linkTextColor: Int,
    val titleTextColor: Int,
    val subtitleTextColor: Int,
    val descriptionTextColor: Int,
    val bubbleBackgroundColor: Int,
    val overlayTextColor: Int,
    val overlayTitleTextColor: Int,
    val overlaySubtitleTextColor: Int,
    val overlayDescriptionTextColor: Int,
    val dividerColor: Int,
    val redactionColor: Int,
    val bubbleRadius: Float,
    val buttonTextColor: Int,
    val ctaTextColor: Int,
    val ctaBackgroundColor: Int,
    val isImmersive: Boolean = false,
) {
    companion object {
        // Palette shared with the web/iOS renderers so screenshots line up.
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val WHITE70 = 0xB3FFFFFF.toInt()
        private const val WHITE60 = 0x99FFFFFF.toInt()
        private const val WHITE50 = 0x80FFFFFF.toInt()
        private const val WHITE15 = 0x26FFFFFF.toInt()
        private const val LINK = 0xFF8E84FA.toInt()
        private const val REDACTION = 0xD9000000.toInt()
        private const val INCOMING_BUBBLE = 0xFF262532.toInt()
        private const val OUTGOING_BUBBLE = 0xFF7924FF.toInt()

        private fun base(
            context: Context,
            textColor: Int,
            titleColor: Int,
            subtitleColor: Int,
            descriptionColor: Int,
            bubble: Int,
        ): MessageTheme = MessageTheme(
            defaultTextSize = 16f,
            titleTextSize = 18f,
            subtitleTextSize = 15f,
            descriptionTextSize = 13f,
            textHorizontalMargin = context.dpToPxInt(12f),
            textVerticalMargin = context.dpToPxInt(6f),
            textBottomMargin = context.dpToPxInt(8f),
            textColor = textColor,
            linkTextColor = LINK,
            titleTextColor = titleColor,
            subtitleTextColor = subtitleColor,
            descriptionTextColor = descriptionColor,
            bubbleBackgroundColor = bubble,
            overlayTextColor = WHITE,
            overlayTitleTextColor = WHITE,
            overlaySubtitleTextColor = WHITE70,
            overlayDescriptionTextColor = WHITE50,
            dividerColor = WHITE15,
            redactionColor = REDACTION,
            bubbleRadius = context.dpToPx(18f),
            buttonTextColor = LINK,
            ctaTextColor = WHITE,
            ctaBackgroundColor = LINK,
        )

        /** Message received from someone else: neutral dark bubble. */
        fun incoming(context: Context): MessageTheme =
            base(context, WHITE, WHITE, WHITE70, WHITE50, INCOMING_BUBBLE)

        /** Our own message: accent bubble. */
        fun outgoing(context: Context): MessageTheme =
            base(context, WHITE, WHITE, WHITE70, WHITE50, OUTGOING_BUBBLE)

        /** Compact preview used in a room/conversation list row. */
        fun preview(context: Context): MessageTheme =
            base(context, WHITE60, WHITE60, WHITE60, WHITE60, Color.TRANSPARENT)
    }
}
