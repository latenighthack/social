package com.latenighthack.social.messages.view

import android.graphics.Bitmap
import android.view.View
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface ImageRequest { fun cancel() }
interface CancellableImageLoader : ImageLoader {
    fun loadCancellable(url: String, onBitmap: (Bitmap?) -> Unit): ImageRequest
    override fun load(url: String, onBitmap: (Bitmap?) -> Unit) { loadCancellable(url, onBitmap) }
}
fun interface SuspendingImageLoader { suspend fun load(url: String): Bitmap? }

/** The host supplies the parent scope; individual view detachments cancel their child jobs. */
fun coroutineImageLoader(scope: CoroutineScope, loader: SuspendingImageLoader): CancellableImageLoader = object : CancellableImageLoader {
    override fun loadCancellable(url: String, onBitmap: (Bitmap?) -> Unit): ImageRequest {
        val job = scope.launch {
            val bitmap = try { loader.load(url) } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
            withContext(Dispatchers.Main.immediate) { onBitmap(bitmap) }
        }
        return ImageRequest { job.cancel() }
    }
}

internal fun <V : View> bindImage(view: V, loader: ImageLoader?, url: String, apply: (V, Bitmap?) -> Unit) {
    if (loader == null || url.isEmpty()) return
    val reference = WeakReference(view)
    var generation = 0
    var request: ImageRequest? = null
    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(host: View) {
            val current = ++generation
            val callback: (Bitmap?) -> Unit = { bitmap ->
                reference.get()?.post {
                    reference.get()?.takeIf { it.isAttachedToWindow && generation == current }?.let { apply(it, bitmap) }
                }
            }
            request = if (loader is CancellableImageLoader) loader.loadCancellable(url, callback)
                else { loader.load(url, callback); null }
        }
        override fun onViewDetachedFromWindow(host: View) {
            generation++; request?.cancel(); request = null
            reference.get()?.let { apply(it, null) }
        }
    }
    view.addOnAttachStateChangeListener(listener)
    if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
}
