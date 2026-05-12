package com.example.birdstrace.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.birdstrace.GeoNatApplication
import com.example.birdstrace.model.Observation

class TraceViewModel(application: Application) : AndroidViewModel(application) {
    val locationTracker = (application as GeoNatApplication).locationTracker

    private val _observations = MutableLiveData<MutableList<Observation>>(mutableListOf())
    val observations: LiveData<MutableList<Observation>> = _observations

    val estActive: Boolean
        get() = (locationTracker.estEnCours.value == true)
            || (locationTracker.parcours.value?.isNotEmpty() == true)
            || (_observations.value?.isNotEmpty() == true)

    fun ajouterObservation(obs: Observation) {
        val list = _observations.value ?: mutableListOf()
        list.add(obs)
        _observations.value = list
    }

    fun modifierObservation(obs: Observation) {
        val list = _observations.value ?: return
        val idx = list.indexOfFirst { it.id == obs.id }
        if (idx >= 0) {
            list[idx] = obs
            _observations.value = list
        }
    }

    fun supprimerObservation(id: String) {
        val list = _observations.value ?: return
        list.removeAll { it.id == id }
        _observations.value = list
    }

    fun mettreAJourObservationPosition(id: String, lat: Double, lon: Double) {
        val list = _observations.value ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(latitude = lat, longitude = lon)
            _observations.value = list
        }
    }

    fun reinitialiser() {
        _observations.value = mutableListOf()
        locationTracker.reinitialiser()
    }

    override fun onCleared() {
        super.onCleared()
        locationTracker.arreter()
    }
}
