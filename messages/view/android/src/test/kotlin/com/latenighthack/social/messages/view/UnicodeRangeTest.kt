package com.latenighthack.social.messages.view

import android.widget.TextView
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.messages.v1.fromByteArray
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UnicodeRangeTest {
    @Test fun sharedBinaryFixtureConcealsExactlyTheUtf16Range() {
        val fixture = File(System.getProperty("social.fixtureDirectory"), "unicode-rich.pb")
        val component = Component.fromByteArray(fixture.readBytes())
        val context = RuntimeEnvironment.getApplication()
        val rendered = MessageLayoutBuilder(context).buildPreview(MessageTheme.preview(context), component) as TextView
        assertEquals("😀 café é ██████", rendered.text.toString())
    }
}
