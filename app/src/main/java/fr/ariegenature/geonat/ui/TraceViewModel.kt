package fr.ariegenature.geonat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import fr.ariegenature.geonat.GeoNatApplication
import fr.ariegenature.geonat.model.Observation

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

    /** Supprime toutes les obs partageant un [releveId] donné (= un "relevé" GeoNature au sens
     *  saisie multi-taxons : 1 point + N taxons en une session). */
    fun supprimerReleve(releveId: String) {
        val list = _observations.value ?: return
        list.removeAll { it.releveId == releveId }
        _observations.value = list
    }

    /** Suppression par liste d'IDs — utilisé par l'édition de relevé pour purger les obs
     *  retirées du formulaire entre le chargement et l'enregistrement. */
    fun supprimerObservations(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val list = _observations.value ?: return
        val set = ids.toHashSet()
        list.removeAll { it.id in set }
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
