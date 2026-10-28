package online.virivky.amap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import online.virivky.amap.net.Http
import online.virivky.amap.util.Bg
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Dlaždice mapy OpenStreetMap: pamäťová cache, cache na disku a sťahovanie na pozadí.
 * Najnovšie požiadavky majú prednosť (LIFO), staré sa pri preplnení zahodia.
 */
object TileCache {
    private const val URL_TEMPLATE = "https://tile.openstreetmap.org/%d/%d/%d.png"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETRY_AFTER_MS = 15_000L
    private const val MAX_QUEUE = 80
    // Pravidlá OSM dlaždíc povoľujú len málo súbežných spojení.
    private const val WORKERS = 2

    private lateinit var dir: File
    private val memory = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(16 * 1024 * 1024)
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Long>()
    private val failed = HashMap<Long, Long>()
    private val queue = LinkedBlockingDeque<Long>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val notifyPosted = AtomicBoolean(false)
    private var started = false

    fun init(context: Context) {
        dir = File(context.cacheDir, "tiles")
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Dlaždica z pamäte bez sťahovania (na náhradné vykreslenie z nižšieho priblíženia). */
    fun peek(z: Int, x: Int, y: Int): Bitmap? = memory.get(key(z, x, y))

    /** Dlaždica z pamäte; ak chýba, naplánuje jej načítanie a vráti null. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val key = key(z, x, y)
        memory.get(key)?.let { return it }
        request(key)
        return null
    }

    private fun key(z: Int, x: Int, y: Int): Long =
        (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

    private fun request(key: Long) {
        synchronized(pending) {
            if (key in pending) return
            val failedAt = failed[key]
            if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS) return
            pending.add(key)
            queue.offerFirst(key)
            while (queue.size > MAX_QUEUE) {
                queue.pollLast()?.let { pending.remove(it) }
            }
            if (!started) {
                started = true
                repeat(WORKERS) { i ->
                    thread(isDaemon = true, name = "amap-tiles-$i") {
                        while (true) load(queue.takeFirst())
                    }
                }
            }
        }
    }

    private fun load(key: Long) {
        val z = (key shr 50).toInt()
        val x = ((key shr 25) and 0x1ffffff).toInt()
        val y = (key and 0x1ffffff).toInt()
        try {
            val file = File(dir, "$z/$x/$y.png")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
            val bytes = if (fresh) {
                file.readBytes()
            } else {
                try {
                    Http.get(String.format(java.util.Locale.US, URL_TEMPLATE, z, x, y)).also { data ->
                        file.parentFile?.mkdirs()
                        file.writeBytes(data)
                    }
                } catch (e: IOException) {
                    // Bez internetu použijeme aj staršiu dlaždicu z disku.
                    if (file.exists()) file.readBytes() else throw e
                }
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Nepodarilo sa dekódovať dlaždicu")
            memory.put(key, bitmap)
            synchronized(pending) {
                pending.remove(key)
                failed.remove(key)
            }
            notifyListeners()
        } catch (e: Exception) {
            synchronized(pending) {
                pending.remove(key)
                failed[key] = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun notifyListeners() {
        if (notifyPosted.compareAndSet(false, true)) {
            Bg.main.postDelayed({
                notifyPosted.set(false)
                listeners.forEach { it() }
            }, 60)
        }
    }
}
