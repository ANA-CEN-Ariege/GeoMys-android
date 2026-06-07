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

package fr.ariegenature.geomys.sync

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
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.network.SyncRunner
import fr.ariegenature.geomys.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Service au premier plan qui exécute la synchro globale ([SyncRunner]) en arrière-plan.
 *  Tant qu'il tourne, le système garde le process vivant et autorise le réseau même écran
 *  éteint / app en arrière-plan — c'est ce qui permet au « Recharger les données » de NE PAS
 *  s'interrompre quand l'utilisateur quitte l'écran ou met le téléphone en veille.
 *
 *  Calqué sur [fr.ariegenature.geomys.location.LocationForegroundService]. */
class SyncForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "GeoMys_sync"
        private const val NOTIF_ID = 2

        private const val EXTRA_FORCE = "forcer_taxref"

        /** @param forcerTaxRef recharge TaxRef même si version+listes inchangées (forçage manuel). */
        fun start(context: Context, forcerTaxRef: Boolean = false) {
            ContextCompat.startForegroundService(
                context, Intent(context, SyncForegroundService::class.java)
                    .putExtra(EXTRA_FORCE, forcerTaxRef)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Vrai dès qu'on a lancé l'exécution pour cette instance de service — évite de la relancer
     *  si onStartCommand est rappelé. */
    private var demarre = false

    // Sert UNIQUEMENT à mettre à jour le texte de la notification au fil de la progression.
    // L'arrêt du service est piloté par la coroutine d'exécution (cf. onStartCommand), pas par
    // cet observateur : on évite ainsi qu'un état terminal résiduel d'une synchro précédente
    // n'arrête le service avant même que la nouvelle exécution démarre.
    private val notifObserver = Observer<SyncRunner.Etat> { etat ->
        if (etat != null) updateNotification(etat.texte.ifEmpty { "Synchronisation…" })
    }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Démarrage de la synchronisation…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        SyncRunner.etat.observeForever(notifObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!demarre) {
            demarre = true
            val forcer = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
            // La coroutine du service exécute la synchro (SyncRunner ignore un appel concurrent)
            // PUIS arrête le service. Le scope du service survit aux changements d'écran ; il
            // n'est annulé qu'à l'arrêt du service.
            scope.launch {
                SyncRunner.executer(applicationContext, forcer)
                arreter()
            }
        }
        // NOT_STICKY : une synchro tuée en cours ne se relance pas toute seule avec un intent nul.
        return START_NOT_STICKY
    }

    private fun arreter() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncRunner.etat.removeObserver(notifObserver)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(texte: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(texte))
    }

    private fun buildNotification(texte: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoMys — synchronisation")
            .setContentText(texte)
            .setSmallIcon(R.drawable.ic_settings)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
