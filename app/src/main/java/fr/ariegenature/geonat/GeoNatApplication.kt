package fr.ariegenature.geonat

import android.app.Application
import fr.ariegenature.geonat.location.LocationTracker
import fr.ariegenature.geonat.store.MapTileCache
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class GeoNatApplication : Application() {

    val locationTracker: LocationTracker by lazy { LocationTracker(this) }

    override fun onCreate() {
        super.onCreate()
        TaxRefCache.init(this)
        NomenclatureCache.init(this)
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
