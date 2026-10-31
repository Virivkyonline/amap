package online.virivky.amap.util

import android.content.Context

/** Číslo verzie aplikácie („1.0.52“ – posledné číslo je číslo zostavenia na GitHube). */
object AppVersion {
    fun name(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }
}
