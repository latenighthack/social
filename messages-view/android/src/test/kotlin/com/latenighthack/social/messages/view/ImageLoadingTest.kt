package com.latenighthack.social.messages.view

import android.app.Activity
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class ImageLoadingTest {
    @Test fun detachedViewsCancelRequestsAndIgnoreLateCallbacks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity); activity.setContentView(root)
        val view = ImageView(activity)
        var callback: ((Bitmap?) -> Unit)? = null
        var cancelled = false
        val loader = object : CancellableImageLoader {
            override fun loadCancellable(url: String, onBitmap: (Bitmap?) -> Unit): ImageRequest {
                callback = onBitmap; return ImageRequest { cancelled = true }
            }
        }
        bindImage(view, loader, "https://test/image") { host, bitmap -> host.setImageBitmap(bitmap) }
        root.addView(view)
        assertNotNull(callback)
        root.removeView(view)
        assertTrue(cancelled)
        callback!!(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull((view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
    }
}
