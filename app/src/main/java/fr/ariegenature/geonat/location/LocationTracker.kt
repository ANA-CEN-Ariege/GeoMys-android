/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import fr.ariegenature.geonat.model.PointTrace

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

    @Volatile private var dernierLoc: Location? = null
    @Volatile private var distanceAccumulee = 0.0
    @Volatile private var listening = false
    private val _parcoursMutable = mutableListOf<PointTrace>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // Toujours mettre à jour la position courante (sinon l'UI reste vide
            // tant que la précision ne descend pas sous 50 m).
            _position.postValue(loc)
            // Filtre des points imprécis uniquement pour l'enregistrement du parcours.
            if (loc.accuracy > 50f) return
            if (_estEnCours.value == true) {
                synchronized(_parcoursMutable) {
                    _parcoursMutable.add(PointTrace(loc.latitude, loc.longitude))
                    _parcours.postValue(_parcoursMutable.toList())
                }
                dernierLoc?.let { prev ->
                    val d = distanceAccumulee + loc.distanceTo(prev)
                    distanceAccumulee = d
                    _distanceTotale.postValue(d)
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
        synchronized(_parcoursMutable) { _parcoursMutable.clear() }
        _parcours.postValue(emptyList())
        distanceAccumulee = 0.0
        _distanceTotale.postValue(0.0)
        dernierLoc = null
        _estEnCours.postValue(true)
    }

    fun arreterParcours() {
        _estEnCours.postValue(false)
    }

    fun reinitialiser() {
        synchronized(_parcoursMutable) { _parcoursMutable.clear() }
        _parcours.postValue(emptyList())
        distanceAccumulee = 0.0
        _distanceTotale.postValue(0.0)
        dernierLoc = null
        _estEnCours.postValue(false)
    }

    fun restaurerParcours(points: List<PointTrace>) {
        synchronized(_parcoursMutable) {
            _parcoursMutable.clear()
            _parcoursMutable.addAll(points)
            _parcours.postValue(_parcoursMutable.toList())
        }
    }

    /** Restaure la distance accumulée — utilisé lors de la reprise d'une sortie sauvegardée
     *  pour que le compteur affiché continue depuis sa valeur précédente, pas depuis 0. */
    fun definirDistance(distance: Double) {
        distanceAccumulee = distance
        _distanceTotale.postValue(distance)
    }

    /** Démarre l'état "tracking actif" SANS réinitialiser le parcours en cours (contrairement
     *  à [demarrerParcours] qui efface tout). Utilisé après [restaurerParcours] pour
     *  continuer une sortie sauvegardée. */
    fun reprendreParcours() {
        _estEnCours.postValue(true)
    }
}