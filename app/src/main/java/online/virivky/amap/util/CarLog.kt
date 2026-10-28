package online.virivky.amap.util

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Denník pre Android Auto: kedy sa auto k apke pripojilo, čo sa podarilo a presné chyby (aj pády),
 * aby sa dalo zistiť, prečo apka v aute nefunguje. Zobrazuje sa v nastaveniach.
 */
object CarLog {
    private const val KEY = "car_log"
    private const val MAX = 40
    private lateinit var prefs: SharedPreferences
    private val time = DateTimeFormatter.ofPattern("d. M. HH:mm:ss")

    fun init(context: Context) {
        prefs = context.getSharedPreferences("amap", Context.MODE_PRIVATE)
        // Pád kdekoľvek v apke sa zapíše skôr, než systém apku ukončí.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                write(entry("PÁD (${thread.name}): ${describe(e)}"), now = true)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun add(event: String) = write(entry(event), now = false)

    fun error(where: String, e: Throwable) = write(entry("CHYBA $where: ${describe(e)}"), now = false)

    /** Záznamy, najnovší prvý. */
    fun lines(): List<String> =
        if (!::prefs.isInitialized) emptyList() else prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun entry(text: String) = LocalDateTime.now().format(time) + "  " + text

    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val where = root.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
        return "${root.javaClass.simpleName}: ${root.message} ($where)"
    }

    @Synchronized
    private fun write(line: String, now: Boolean) {
        if (!::prefs.isInitialized) return
        val all = (listOf(line.replace('\n', ' ')) + lines()).take(MAX)
        val editor = prefs.edit().putString(KEY, all.joinToString("\n"))
        if (now) editor.commit() else editor.apply()
    }
}
