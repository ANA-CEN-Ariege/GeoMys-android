package com.example.birdstrace.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.birdstrace.model.PointTrace

class LocationTracker(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _position = MutableLiveData<Location?>()
    val position: LiveData<Location?> = _position

    private val _parcours = MutableLiveData<List<PointTrace>>(emptyList())
    val parcours: LiveData<List<PointTrace>> = _parcours

    private val _estEnCours = MutableLiveData(false)
    val estEnCours: LiveData<Boolean> = _estEnCours

    private val _distanceTotale = MutableLiveData(0.0)
    val distanceTotale: LiveData<Double> = _distanceTotale

    private var dernierLoc: Location? = null
    private val _parcoursMutable = mutableListOf<PointTrace>()
    private var listening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.accuracy > 50f) return
            _position.postValue(loc)
            if (_estEnCours.value == true) {
                _parcoursMutable.add(PointTrace(loc.latitude, loc.longitude))
                _parcours.postValue(_parcoursMutable.toList())
                dernierLoc?.let { prev ->
                    _distanceTotale.postValue((_distanceTotale.value ?: 0.0) + loc.distanceTo(prev))
                }
                dernierLoc = loc
            }
        }

        @Deprecated("Deprecated")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun demarrer() {
        if (listening) return
        val gpsOk = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netOk = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gpsOk && !netOk) return

        // Position immédiate depuis le cache système
        val lastKnown = when {
            gpsOk -> locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: if (netOk) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
            netOk -> locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            else -> null
        }
        lastKnown?.let { _position.postValue(it) }

        val provider = if (gpsOk) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        locationManager.requestLocationUpdates(provider, 2000L, 5f, locationListener)
        listening = true
    }

    fun arreter() {
        if (!listening) return
        locationManager.removeUpdates(locationListener)
        listening = false
    }

    fun demarrerParcours() {
        _parcoursMutable.clear()
        _parcours.postValue(emptyList())
        _distanceTotale.postValue(0.0)
        dernierLoc = null
        _estEnCours.postValue(true)
    }

    fun arreterParcours() {
        _estEnCours.postValue(false)
    }

    fun reinitialiser() {
        _parcoursMutable.clear()
        _parcours.postValue(emptyList())
        _distanceTotale.postValue(0.0)
        dernierLoc = null
        _estEnCours.postValue(false)
    }

    fun restaurerParcours(points: List<PointTrace>) {
        _parcoursMutable.clear()
        _parcoursMutable.addAll(points)
        _parcours.postValue(_parcoursMutable.toList())
    }
}