package com.example.birdstrace.location

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.example.birdstrace.GeoNatApplication
import com.example.birdstrace.R
import com.example.birdstrace.ui.MainActivity

class LocationForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "GeoNat_location"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }

    private val tracker get() = (application as GeoNatApplication).locationTracker

    private val distanceObserver = Observer<Double> { dist ->
        updateNotification(dist ?: 0.0)
    }

    private val enCoursObserver = Observer<Boolean> { _ ->
        updateNotification(tracker.distanceTotale.value ?: 0.0)
    }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0.0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        tracker.distanceTotale.observeForever(distanceObserver)
        tracker.estEnCours.observeForever(enCoursObserver)
        tracker.demarrer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        tracker.distanceTotale.removeObserver(distanceObserver)
        tracker.estEnCours.removeObserver(enCoursObserver)
        tracker.arreter()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(dist: Double) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(dist))
    }

    private fun buildNotification(dist: Double): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val enCours = tracker.estEnCours.value == true
        val text = if (enCours) "%.0f m parcourus".format(dist) else "En attente de démarrage"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoNat – suivi GPS actif")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentIntent(pi)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}