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

import androidx.lifecycle.ViewModel
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation

/**
 * État de saisie d'une station OccHab, partagé entre l'écran de géométrie
 * ([OccHabCarteFragment]) et le formulaire ([OccHabStationFragment]). Activity-scoped
 * (via `by activityViewModels()`) — la station en cours survit à la navigation carte→formulaire,
 * comme [TraceViewModel] pour Occtax.
 */
class OccHabViewModel : ViewModel() {

    /** Station en cours de saisie. Remplacée en bloc (copies immuables) pour rester cohérente. */
    var station: OccHabStation = OccHabStation()
        private set

    /** Démarre une nouvelle station vierge (bouton « OccHab » de l'accueil). */
    fun nouvelle() { station = OccHabStation() }

    /** Reprend une station existante pour édition (copie défensive, habitats inclus). */
    fun reprendre(existante: OccHabStation) {
        station = existante.copy(habitats = existante.habitats.map { it.copy() })
    }

    /** Fixe la géométrie choisie sur la carte. */
    fun definirGeometrie(type: String, lat: Double, lon: Double, coordsJson: String?) {
        station = station.copy(
            geometryType = type, latitude = lat, longitude = lon, geometryCoordsJson = coordsJson,
        )
    }

    /** Applique une transformation au modèle station (édition d'un champ du formulaire). */
    fun maj(bloc: (OccHabStation) -> OccHabStation) { station = bloc(station) }

    /** Ajoute l'habitat, ou le remplace si un habitat de même id existe déjà. */
    fun ajouterOuMajHabitat(h: OccHabHabitat) {
        val liste = station.habitats.toMutableList()
        val idx = liste.indexOfFirst { it.id == h.id }
        if (idx >= 0) liste[idx] = h else liste.add(h)
        station = station.copy(habitats = liste)
    }

    fun supprimerHabitat(id: String) {
        station = station.copy(habitats = station.habitats.filterNot { it.id == id })
    }
}
