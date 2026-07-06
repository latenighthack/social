package com.latenighthack.social.messages.view.demo

import android.content.Context
import android.view.View
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.view.MessageLayoutBuilder
import com.latenighthack.social.messages.view.MessageTheme
import org.json.JSONObject

data class Fixture(val name: String, val incoming: Boolean, val mode: String, val bytes: ByteArray)

object Fixtures {
    fun load(context: Context): List<Fixture> {
        val text = context.assets.open("bundles/manifest.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("fixtures")
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            val name = entry.getString("name")
            val bytes = context.assets.open("bundles/$name.pb").use { it.readBytes() }
            Fixture(name, entry.getBoolean("incoming"), entry.getString("mode"), bytes)
        }
    }

    fun render(context: Context, fixture: Fixture): View {
        val builder = MessageLayoutBuilder(context)
        val component = Component.fromByteArray(fixture.bytes)
        return if (fixture.mode == "preview") {
            builder.buildPreview(MessageTheme.preview(context), component)
        } else {
            val theme = if (fixture.incoming) MessageTheme.incoming(context) else MessageTheme.outgoing(context)
            builder.build(theme, component)
        }
    }
}
