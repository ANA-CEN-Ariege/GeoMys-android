/*
 * GeoMys-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geomys.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import fr.ariegenature.geomys.GeoMysApplication
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.store.SortieStore

class TraceViewModel(application: Application) : AndroidViewModel(application) {
    val locationTracker = (application as GeoMysApplication).locationTracker

    /** Libellé du type de saisie en cours ("Saisie mono-taxons" / "Saisie multi-taxons"),
     *  positionné par l'écran d'entrée du flux (SaisieRapideFragment / TraceFragment) et lu
     *  par tous les écrans de saisie pour afficher le bandeau "🏠 › <type>"
     *  (cf. [appliquerBandeauNavigation]). Porté ici car partagé au niveau de l'Activity. */
    var typeSaisieLabel: String = ""

    /** Store durable des sorties — sert à l'auto-save « au fil de l'eau » de la saisie en
     *  cours (cf. [persisterBrouillon]). */
    private val sortieStore = SortieStore(application)

    private val _observations = MutableLiveData<MutableList<Observation>>(mutableListOf())
    val observations: LiveData<MutableList<Observation>> = _observations

    /** Id de la sortie en cours d'édition (= reprise depuis l'onglet "À envoyer"). Quand
     *  non-null, [TraceFragment.terminerSortie] doit remplacer la sortie existante au
     *  lieu d'en créer une nouvelle. Reset par [reinitialiser]. */
    var sortieEnEditionId: String? = null
        private set

    fun ajouterObservation(obs: Observation) {
        val list = _observations.value ?: mutableListOf()
        list.add(obs)
        _observations.value = list
        persisterBrouillon()
    }


    fun supprimerObservation(id: String) {
        val list = _observations.value ?: return
        list.removeAll { it.id == id }
        _observations.value = list
        persisterBrouillon()
    }

    /** Supprime toutes les obs partageant un [releveId] donné (= un "relevé" GeoNature au sens
     *  saisie multi-taxons : 1 point + N taxons en une session). */
    fun supprimerReleve(releveId: String) {
        val list = _observations.value ?: return
        list.removeAll { it.releveId == releveId }
        _observations.value = list
        persisterBrouillon()
    }

    /** Suppression par liste d'IDs — utilisé par l'édition de relevé pour purger les obs
     *  retirées du formulaire entre le chargement et l'enregistrement. */
    fun supprimerObservations(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val list = _observations.value ?: return
        val set = ids.toHashSet()
        list.removeAll { it.id in set }
        _observations.value = list
        persisterBrouillon()
    }

    /** Remplace en bloc toutes les observations d'un [releveId] par [nouvelles] (qui portent
     *  ce même releveId), en une seule mutation → un seul auto-save. Utilisé par la saisie
     *  multi-taxons pour refléter le lot en cours au fil de l'eau sans réécrire le store une
     *  fois par espèce. Préserve l'ordre des autres relevés.
     *
     *  Purge AUSSI par id (upsert) : une obs MONO-taxon éditée porte releveId=null à l'origine
     *  mais ressort de l'écran d'édition avec le releveId de la session — la purge par seul
     *  releveId ne la retrouvait pas, l'originale survivait et l'espèce apparaissait en DOUBLE
     *  sur le point (avec le même id d'obs, en plus). */
    fun remplacerObservationsDuReleve(releveId: String, nouvelles: List<Observation>) {
        val list = _observations.value ?: mutableListOf()
        val idsNouvelles = nouvelles.map { it.id }.toHashSet()
        list.removeAll { it.releveId == releveId || it.id in idsNouvelles }
        list.addAll(nouvelles)
        _observations.value = list
        persisterBrouillon()
    }

    fun mettreAJourObservationPosition(id: String, lat: Double, lon: Double) {
        val list = _observations.value ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(latitude = lat, longitude = lon)
            _observations.value = list
            persisterBrouillon()
        }
    }

    /** Variante BATCH de [mettreAJourObservationPosition] : déplace N obs (relevé
     *  multi-taxons repositionné d'un coup) en UNE mutation → UN seul auto-save. L'appel
     *  par obs re-sérialisait TOUT le store (commit synchrone sur le thread UI) N fois
     *  d'affilée pendant une interaction carte — jank garanti, ANR possible sur une
     *  saisie chargée (audit 2026-07). */
    fun mettreAJourObservationsPositions(ids: Collection<String>, lat: Double, lon: Double) {
        if (ids.isEmpty()) return
        val list = _observations.value ?: return
        var modifie = false
        ids.forEach { id ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(latitude = lat, longitude = lon)
                modifie = true
            }
        }
        if (modifie) {
            _observations.value = list
            persisterBrouillon()
        }
    }

    /** Met à jour les sommets d'un relevé ligne/polygone : applique le nouveau
     *  geometryCoordsJson et recentre lat/lon (centroïde) sur toutes les obs ciblées. */
    fun mettreAJourGeometrieReleve(ids: Collection<String>, coordsJson: String, lat: Double, lon: Double) {
        val list = _observations.value ?: return
        var modifie = false
        ids.forEach { id ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(geometryCoordsJson = coordsJson, latitude = lat, longitude = lon)
                modifie = true
            }
        }
        if (modifie) {
            _observations.value = list
            persisterBrouillon()
        }
    }

    /** Auto-save « au fil de l'eau » de la saisie en cours : à chaque modification des
     *  observations (mono ou multi-taxons), on écrit/met à jour la sortie courante dans
     *  [SortieStore] sous une id stable ([sortieEnEditionId]). Elle apparaît ainsi dans
     *  « Mes saisies → À envoyer » et survit à un kill de l'appli ; on la reprend via
     *  « Continuer la saisie » (= [reprendreSortie]).
     *
     *  - 1ère obs d'une nouvelle saisie → crée la sortie et fixe [sortieEnEditionId].
     *  - obs suivantes / reprise → met à jour la sortie existante (préserve id + date).
     *  - saisie vidée (plus aucune obs ni trace) → purge le brouillon.
     *  Ne touche jamais une sortie déjà envoyée à GeoNature. */
    private fun persisterBrouillon() {
        val obs = _observations.value?.toList() ?: emptyList()
        val parcours = locationTracker.parcours.value ?: emptyList()
        val distance = locationTracker.distanceTotale.value ?: 0.0
        val id = sortieEnEditionId
        if (obs.isEmpty() && parcours.isEmpty()) {
            id?.let { sortieStore.supprimer(it) }
            return
        }
        if (id != null) {
            val existante = sortieStore.charger().firstOrNull { it.id == id }
            if (existante?.envoyeGeoNature == true) return
            val base = existante ?: Sortie(id = id)
            sortieStore.remplacer(id, base.copy(
                observations = obs,
                pointsParcours = parcours,
                distanceTotale = distance,
            ))
        } else {
            val nouvelle = Sortie(
                observations = obs,
                pointsParcours = parcours,
                distanceTotale = distance,
            )
            sortieStore.ajouter(nouvelle)
            sortieEnEditionId = nouvelle.id
        }
    }

    /** Persiste IMMÉDIATEMENT la sortie en cours (observations + trace) dans le store.
     *  Appelée quand on QUITTE le flux de saisie sans passer par « Terminer » (ex. retour
     *  à l'accueil via le fil d'Ariane) : sans ça, la trace accumulée depuis le dernier ajout
     *  d'observation — voire une trace sans aucune observation — serait perdue. Le store
     *  garde la sortie même si le VM est ensuite réinitialisé au prochain démarrage de saisie. */
    fun sauvegarderBrouillon() = persisterBrouillon()

    fun reinitialiser() {
        _observations.value = mutableListOf()
        locationTracker.reinitialiser()
        sortieEnEditionId = null
    }

    /** Force la prochaine ouverture de l'écran trace à RECHARGER la sortie depuis le store
     *  et à recadrer la carte dessus. À appeler à l'entrée depuis « Mes saisies » : sans ça,
     *  rééditer une sortie déjà éditée dans la session laissait [sortieEnEditionId] inchangé,
     *  donc la reprise (et le recadrage doitCentrerSurObs) était sautée → carte centrée sur
     *  le GPS au lieu de la géométrie. */
    fun forcerRepriseAuProchainEcran() {
        sortieEnEditionId = null
    }

    /** Reprend une sortie sauvegardée : recharge les observations et la trace dans le
     *  tracker, mémorise l'id pour que la sauvegarde finale remplace la sortie existante
     *  (au lieu d'en créer une nouvelle). N'active pas le tracking GPS — c'est à
     *  l'utilisateur de relancer le suivi via le switch dédié s'il veut continuer la
     *  trace. */
    fun reprendreSortie(sortie: Sortie) {
        _observations.value = sortie.observations.toMutableList()
        locationTracker.restaurerParcours(sortie.pointsParcours)
        locationTracker.definirDistance(sortie.distanceTotale)
        sortieEnEditionId = sortie.id
    }

    override fun onCleared() {
        super.onCleared()
        locationTracker.arreter()
    }
}
