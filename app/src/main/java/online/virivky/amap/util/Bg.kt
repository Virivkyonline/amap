package online.virivky.amap.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Jednoduché spúšťanie práce na pozadí s výsledkom späť na hlavnom vlákne. */
object Bg {
    val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(work)
            main.post { done(result) }
        }
    }
}
