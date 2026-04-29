package com.example.birdstrace.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.birdstrace.BioScopeApplication
import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.PointTrace
import com.example.birdstrace.model.Sortie
import com.example.birdstrace.store.SortieStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TraceViewModel(application: Application) : AndroidViewModel(application) {
    val locationTracker = (application as BioScopeApplication).locationTracker

    private val _observations = MutableLiveData<MutableList<Observation>>(mutableListOf())
    val observations: LiveData<MutableList<Observation>> = _observations

    private val prefs = application.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val gson = Gson()

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
        val obs = _observations.value ?: emptyList<Observation>()
        prefs.edit().putString("observations", gson.toJson(obs)).apply()
        val pts = locationTracker.parcours.value ?: emptyList<PointTrace>()
        prefs.edit().putString("parcours", gson.toJson(pts)).apply()
    }

    private fun charger() {
        val obsJson = prefs.getString("observations", null)
        if (obsJson != null) {
            try {
                val type = object : TypeToken<MutableList<Observation>>() {}.type
                _observations.value = gson.fromJson(obsJson, type) ?: mutableListOf()
            } catch (_: Exception) {}
        }
        val parcoursJson = prefs.getString("parcours", null)
        if (parcoursJson != null) {
            try {
                val type = object : TypeToken<List<PointTrace>>() {}.type
                val pts: List<PointTrace> = gson.fromJson(parcoursJson, type) ?: emptyList()
                locationTracker.restaurerParcours(pts)
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationTracker.arreter()
    }
}