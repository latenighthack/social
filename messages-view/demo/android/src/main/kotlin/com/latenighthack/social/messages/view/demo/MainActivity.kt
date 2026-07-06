package com.latenighthack.social.messages.view.demo

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0E17.toInt())
            setPadding(pad, pad, pad, pad)
        }

        for (fixture in Fixtures.load(this)) {
            val row = FrameLayout(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
            val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (fixture.mode == "message" && !fixture.incoming) Gravity.END else Gravity.START
            }
            row.addView(Fixtures.render(this, fixture), lp)
            column.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        setContentView(ScrollView(this).apply { addView(column) })
    }
}
