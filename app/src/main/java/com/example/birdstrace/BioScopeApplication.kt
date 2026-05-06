package com.example.birdstrace

import android.app.Application
import com.example.birdstrace.location.LocationTracker
import com.example.birdstrace.store.MapTileCache
import com.example.birdstrace.store.NomenclatureCache
import com.example.birdstrace.store.TaxRefCache

class GeoNatApplication : Application() {

    val locationTracker: LocationTracker by lazy { LocationTracker(this) }

    override fun onCreate() {
        super.onCreate()
        TaxRefCache.init(this)
        NomenclatureCache.init(this)
        MapTileCache.purgerSiNecessaire(this)
    }
}
