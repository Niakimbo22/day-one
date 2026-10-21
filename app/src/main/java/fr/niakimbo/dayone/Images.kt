package fr.niakimbo.dayone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Mini chargeur d'images (cache mémoire + 4 threads), sans aucune dépendance. */
object Images {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, view: ImageView) {
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = runCatching { Rawg.bytes(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
            if (bmp != null) {
                cache.put(url, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            }
        }
    }
}
