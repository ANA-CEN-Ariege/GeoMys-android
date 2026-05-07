package com.example.birdstrace.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.birdstrace.GeoNatApplication
import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.PointTrace
import com.example.birdstrace.model.Sortie
import com.example.birdstrace.store.SortieStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TraceViewModel(application: Application) : AndroidViewModel(application) {
    val locationTracker = (application as GeoNatApplication).locationTracker

    private val _observations = MutableLiveData<MutableList<Observation>>(mutableListOf())
    val observations: LiveData<MutableList<Observation>> = _observations

    private val prefs = application.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val gson = Gson()

    private data class SessionSnapshot(
        val observations: List<Observation> = emptyList(),
        val parcours: List<PointTrace> = emptyList()
    )

    val estActive: Boolean
        get() = (locationTracker.estEnCours.value == true)
            || (locationTracker.parcours.value?.isNotEmpty() == true)
            || (_observations.value?.isNotEmpty() == true)

    init {
        charger()
    }

    fun ajouterObservation(obs: Observation) {
        val list = _observations.value ?: mutableListOf()
        list.add(obs)
        _observations.value = list
        sauvegarder()
    }

    fun supprimerObservation(id: String) {
        val list = _observations.value ?: return
        list.removeAll { it.id == id }
        _observations.value = list
        sauvegarder()
    }

    fun mettreAJourObservationPosition(id: String, lat: Double, lon: Double) {
        val list = _observations.value ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(latitude = lat, longitude = lon)
            _observations.value = list
            sauvegarder()
        }
    }

    fun reinitialiser() {
        _observations.value = mutableListOf()
        locationTracker.reinitialiser()
        sauvegarder()
    }

    fun sauvegarder() {
        val snapshot = SessionSnapshot(
            observations = _observations.value ?: emptyList(),
            parcours = locationTracker.parcours.value ?: emptyList()
        )
        prefs.edit()
            .putString(KEY_SESSION, gson.toJson(snapshot))
            .remove("observations")
            .remove("parcours")
            .apply()
    }

    private fun charger() {
        val json = prefs.getString(KEY_SESSION, null)
        if (json != null) {
            try {
                val type = object : TypeToken<SessionSnapshot>() {}.type
                val snapshot: SessionSnapshot = gson.fromJson(json, type) ?: return
                _observations.value = snapshot.observations.toMutableList()
                locationTracker.restaurerParcours(snapshot.parcours)
            } catch (_: Exception) {}
            return
        }
        // Migration depuis les anciennes clés séparées
        migrerAnciennesClés()
    }

    private fun migrerAnciennesClés() {
        val obsJson = prefs.getString("observations", null)
        val parcoursJson = prefs.getString("parcours", null)
        if (obsJson == null && parcoursJson == null) return
        try {
            val obsType = object : TypeToken<MutableList<Observation>>() {}.type
            val obs: MutableList<Observation> = obsJson?.let { gson.fromJson(it, obsType) } ?: mutableListOf()
            val ptsType = object : TypeToken<List<PointTrace>>() {}.type
            val pts: List<PointTrace> = parcoursJson?.let { gson.fromJson(it, ptsType) } ?: emptyList()
            _observations.value = obs
            locationTracker.restaurerParcours(pts)
        } catch (_: Exception) {}
        sauvegarder()
    }

    companion object {
        private const val KEY_SESSION = "session_v2"
    }

    override fun onCleared() {
        super.onCleared()
        locationTracker.arreter()
    }
}