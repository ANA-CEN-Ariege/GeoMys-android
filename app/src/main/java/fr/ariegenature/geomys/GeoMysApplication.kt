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

package fr.ariegenature.geomys

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import fr.ariegenature.geomys.location.LocationTracker
import fr.ariegenature.geomys.store.MapTileCache
import fr.ariegenature.geomys.store.MonitoringCache
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.OutboxMonitoring
import fr.ariegenature.geomys.store.TaxRefCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class GeoMysApplication : Application() {

    val locationTracker: LocationTracker by lazy { LocationTracker(this) }

    override fun onCreate() {
        super.onCreate()
        // App de terrain plein soleil → on force le mode sombre globalement. Combiné avec le
        // windowBackground = bg_accueil du thème, ça donne :
        //   - fond sombre dégradé (forêt → nuit) partout pour optimiser le contraste,
        //   - widgets Material en couleurs sombres + texte clair (onSurface = white-ish)
        //     pour éviter d'avoir à patcher chaque TextView à la main.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        TaxRefCache.init(this)
        NomenclatureCache.init(this)
        fr.ariegenature.geomys.store.HabitatCache.init(this)
        fr.ariegenature.geomys.store.HabitatCacheOccHab.init(this)
        MonitoringCache.init(this)
        OutboxMonitoring.init(this)
        fr.ariegenature.geomys.store.RelevesOrphelins.init(this)
        // OsmDroid doit être initialisé AVANT toute lecture/écriture du cache de tuiles
        // (sinon Configuration.getInstance().osmdroidTileCache déclenche une NPE asynchrone
        // qui crashe l'app via le UncaughtExceptionHandler).
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        MapTileCache.configurer(this)
        MapTileCache.purgerSiNecessaire(this)

        // Pré-chauffe les vues mémoisées du cache TaxRef en tâche de fond : sans ça, le
        // premier appel à getSuggestionsAutocomplete lit le disque + parcourt 15k+ entrées
        // sur le thread Default — assez lent pour qu'un utilisateur qui tape « M » dès
        // l'ouverture de l'écran de saisie tombe dans un adapter encore vide.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                TaxRefCache.entreesParCdNom()
                TaxRefCache.vernsParCdNom()
                TaxRefCache.tousLesGroupes()
                TaxRefCache.tousLesGroupes1()
                TaxRefCache.tousLesRegnes()
            }
        }
    }
}
