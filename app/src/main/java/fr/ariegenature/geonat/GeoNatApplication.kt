package fr.ariegenature.geonat

import android.app.Application
import fr.ariegenature.geonat.location.LocationTracker
import fr.ariegenature.geonat.store.MapTileCache
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache

class GeoNatApplication : Application() {

    val locationTracker: LocationTracker by lazy { LocationTracker(this) }

    override fun onCreate() {
        super.onCreate()
        TaxRefCache.init(this)
        NomenclatureCache.init(this)
        MapTileCache.purgerSiNecessaire(this)
    }
}
