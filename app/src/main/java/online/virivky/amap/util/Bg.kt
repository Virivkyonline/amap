package online.virivky.amap.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Jednoduché spúšťanie práce na pozadí s výsledkom späť na hlavnom vlákne. */
object Bg {
    val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)

    /** Pomalé doplnky mapy (priechody z Overpass, čakanie aj minútu) – jeden po druhom a mimo [run]. */
    private val slow = Executors.newSingleThreadExecutor()

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(work)
            main.post { done(result) }
        }
    }

    /** Ako [run], ale vo vlastnom rade, aby pomalé načítanie nezdržalo výpočet trasy. */
    fun <T> runSlow(work: () -> T, done: (Result<T>) -> Unit) {
        slow.execute {
            val result = runCatching(work)
            main.post { done(result) }
        }
    }
}
