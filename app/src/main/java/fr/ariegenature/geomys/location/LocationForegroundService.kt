/*
 * GeoMys-Android — application Android de saisie naturaliste pour GeoNature.
 * Copyright (C) 2026 ANA - CEN Ariège
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package fr.ariegenature.geomys.location

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
import fr.ariegenature.geomys.GeoMysApplication
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.ui.MainActivity

class LocationForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "GeoMys_location"
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

    private val tracker get() = (application as GeoMysApplication).locationTracker

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

    // START_NOT_STICKY : si Android tue le process pendant une saisie, il ne doit PAS
    // recréer le service SEUL — le système le relançait avec un GPS actif en continu et une
    // notification « en attente » alors qu'aucune saisie n'existait plus (le ViewModel est
    // perdu avec le process, et MainActivity ne stoppe le service que sur une TRANSITION
    // saisie→hors-saisie qui ne se produit jamais après redémarrage) : drain batterie
    // silencieux sur le terrain. Les écrans de saisie redémarrent le service à chaque
    // entrée ; les données, elles, sont déjà protégées par l'auto-save au fil de l'eau.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

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
            .setContentTitle("GeoMys – suivi GPS actif")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentIntent(pi)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}