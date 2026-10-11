package com.latenighthack.social.messages.view

import android.widget.TextView
import com.latenighthack.social.messages.v1.*
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RedactionTest {
    @Test fun previewAndTextDoNotExposeConcealedCharacters() {
        val component = Component { contents.text {
            text = "Before secret after"
            inlines = listOf(Inline { offset = 7; length = 6; rule { contents.redaction { } } })
        } }
        val builder = MessageLayoutBuilder(RuntimeEnvironment.getApplication())
        val preview = builder.buildPreview(MessageTheme.preview(RuntimeEnvironment.getApplication()), component) as TextView
        assertEquals("Before ██████ after", preview.text.toString())
    }
}
