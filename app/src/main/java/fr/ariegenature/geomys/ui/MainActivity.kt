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

package fr.ariegenature.geomys.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import fr.ariegenature.geomys.GeoMysApplication
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.location.LocationForegroundService
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity() {
    private lateinit var navController: NavController

    /** Vrai tant que la destination courante appartient au flux de saisie. Sert à ne couper
     *  le tracé GPS que sur une VRAIE transition saisie → hors-saisie (et pas sur une simple
     *  recréation d'Activity qui restaure une destination de saisie). */
    private var dansFluxSaisie = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        fr.ariegenature.geomys.store.MapTileCache.configurer(this)
        createNotificationChannel()
        setContentView(R.layout.activity_main)
        // Les insets sont gérés au cas par cas dans chaque fragment via les helpers
        // de Insets.kt (applyStatusBarInset, applyNavBarInset, applySystemBarInsets).
        // Permet aux backgrounds/cartes de rester edge-to-edge tout en gardant
        // les boutons à l'écart des barres système.

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Tracé GPS : le service de premier plan doit SURVIVRE à la mise en arrière-plan /
        // veille tant qu'on reste dans le flux de saisie, mais s'ARRÊTER dès qu'on navigue
        // hors de ce flux DANS l'appli (retour accueil, Mes saisies, Explorer, monitoring…).
        // On se base sur la NAVIGATION (et non sur le cycle de vie de l'Activity, qui se
        // déclencherait aussi en arrière-plan) ; et seulement sur une vraie TRANSITION
        // saisie → hors-saisie, pour qu'une recréation d'Activity ne coupe pas le tracé.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val dansSaisie = destination.id in DESTINATIONS_SAISIE
            if (dansFluxSaisie && !dansSaisie) {
                // On quitte le flux de saisie DANS l'appli (≠ arrière-plan / veille) :
                //  1) persister la sortie en cours AVEC sa trace — sinon la trace accumulée
                //     depuis le dernier ajout d'observation (ou une trace sans observation)
                //     serait perdue, puisqu'on ne passe pas par « Terminer » ;
                //  2) puis couper le tracé GPS et le service de premier plan.
                ViewModelProvider(this)[TraceViewModel::class.java].sauvegarderBrouillon()
                (application as GeoMysApplication).locationTracker.arreterParcours()
                LocationForegroundService.stop(this)
            }
            dansFluxSaisie = dansSaisie
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            LocationForegroundService.CHANNEL_ID,
            "Suivi GPS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Suivi GPS en arrière-plan pendant une sortie"
        }
        val canalSync = NotificationChannel(
            fr.ariegenature.geomys.sync.SyncForegroundService.CHANNEL_ID,
            "Synchronisation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Chargement des données GeoNature en arrière-plan"
        }
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            createNotificationChannel(canalSync)
        }
    }

    companion object {
        /** Destinations du flux de saisie où le tracé GPS reste actif (y compris app en
         *  arrière-plan / écran éteint). Naviguer vers toute AUTRE destination dans l'appli
         *  arrête le service de localisation. Couvre multi-taxons ET mono-taxon (qui utilisent
         *  tous deux le service), plus leurs sous-écrans (caractérisation, dénombrement,
         *  détails du relevé, observations). */
        private val DESTINATIONS_SAISIE = setOf(
            R.id.traceFragment,
            R.id.saisieObservationFragment,
            R.id.saisieRapideFragment,
            R.id.caracterisationFragment,
            R.id.denombrementFragment,
            R.id.detailsReleveFragment,
            R.id.observationsFragment,
        )
    }
}