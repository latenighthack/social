package com.latenighthack.social.messages.view.demo

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @Test
    fun captureAllFixtures() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_MessagesViewDemo)
        controller.setup()

        val density = activity.resources.displayMetrics.density
        val widthPx = (360 * density).toInt()
        val pad = (16 * density).toInt()
        val outDir = "../../screenshots/android"

        for (fixture in Fixtures.load(activity)) {
            val root = FrameLayout(activity).apply {
                setBackgroundColor(0xFF0F0E17.toInt())
                setPadding(pad, pad, pad, pad)
            }
            val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = if (fixture.mode == "message" && !fixture.incoming) Gravity.END else Gravity.START
            }
            root.addView(Fixtures.render(activity, fixture), lp)

            // Attach to the activity so Roborazzi can rasterise, then pin width to 360dp and wrap height.
            activity.setContentView(root, ViewGroup.LayoutParams(widthPx, WRAP_CONTENT))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)

            root.captureRoboImage("$outDir/${fixture.name}.png")
        }
    }
}
