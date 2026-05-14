package fr.ariegenature.geonat

import android.app.Application
import fr.ariegenature.geonat.location.LocationTracker
import fr.ariegenature.geonat.store.MapTileCache
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache
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
    }
}
