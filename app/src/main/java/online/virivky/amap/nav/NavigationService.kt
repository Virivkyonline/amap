package online.virivky.amap.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import online.virivky.amap.R
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.ui.MainActivity

/** Služba v popredí, aby navigácia a GPS bežali aj pri zhasnutom telefóne. */
class NavigationService : Service(), NavigationController.Listener {
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Navigácia", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NavigationController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground) {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
                foreground = true
                LocationTracker.acquire(this)
                NavigationController.addListener(this)
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NavigationController.removeListener(this)
        LocationTracker.release(this)
        super.onDestroy()
    }

    override fun onNavigationChanged() {
        if (NavigationController.phase != NavigationController.Phase.NAVIGATING) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val nav = NavigationController
        val step = nav.progress?.let { nav.route?.steps?.getOrNull(it.nextStepIndex) }
        val title = step?.let { "${Instructions.displayDistance(nav.progress!!.distanceToNextStep)} · ${Instructions.title(it)}" }
            ?: "Navigácia"
        val text = nav.destination?.name?.let { "Do cieľa: $it" } ?: ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, "Ukončiť", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "online.virivky.amap.STOP"

        fun start(context: Context) {
            try {
                context.startService(Intent(context, NavigationService::class.java))
            } catch (e: Exception) {
                // Na pozadí sa službu niekedy nedá spustiť; v aute poloha beží aj tak.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
