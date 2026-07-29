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
import fr.ariegenature.geomys.store.GeoNatureConfig

/**
 * Détails de STATION communs à toute une session OccHab (jeu de données, observateurs, dates,
 * altitudes, surface + méthode, nature objet géo, commentaire). Conservés d'une station à l'autre
 * tant qu'on reste dans OccHab ; édités via le bouton « Détails ». Réinitialisés aux défauts
 * serveur au démarrage d'une nouvelle session (tuile OccHab de l'accueil).
 */
data class OccHabDetailsSession(
    var idDataset: Int? = null,
    var nomDataset: String? = null,
    var observateursIds: List<Int> = emptyList(),
    var observateursNoms: List<String> = emptyList(),
    var observateursTxt: String? = null,
    var dateMin: Long? = null,
    var dateMax: Long? = null,
    var altitudeMin: Int? = null,
    var altitudeMax: Int? = null,
    var surface: Long? = null,
    var idNomCalculSurface: Int? = null,
    var idNomObjetGeographique: Int? = null,
    var comment: String? = null,
)

/** Détails de session par défaut (défauts serveur) : observateur par défaut, date = maintenant,
 *  nomenclatures par défaut OccHab. Le JDD reste null (saisi au formulaire de la 1ʳᵉ station). */
fun detailsSessionParDefaut(config: GeoNatureConfig): OccHabDetailsSession {
    val obsId = config.observateurDefautId.trim().toIntOrNull()?.takeIf { it > 0 }
        ?: config.idRoleUtilisateur.takeIf { it > 0 }
    val obsNom = config.observateurDefautNom.ifBlank { config.nomUtilisateur }
    return OccHabDetailsSession(
        idDataset = null,
        observateursIds = listOfNotNull(obsId),
        observateursNoms = if (obsId != null && obsNom.isNotBlank()) listOf(obsNom) else emptyList(),
        dateMin = System.currentTimeMillis(),
        idNomCalculSurface = config.occhabDefautNomenclature("METHOD_CALCUL_SURFACE"),
        idNomObjetGeographique = config.occhabDefautNomenclature("NAT_OBJ_GEO"),
    )
}

/**
 * État de saisie OccHab, partagé (activity-scoped) entre l'écran géométrie ([OccHabCarteFragment])
 * et l'écran habitats ([OccHabStationFragment]). Distingue :
 *  - [details] : détails communs à la SESSION (voir [OccHabDetailsSession]) ;
 *  - [station] : la station en cours (géométrie + habitats), qui reçoit les [details] à l'envoi ;
 *  - [jddDefini] : le jeu de données a-t-il été saisi dans cette session (sinon on l'affiche).
 */
class OccHabViewModel : ViewModel() {

    var details = OccHabDetailsSession()
        private set
    var jddDefini = false
        private set
    var station = OccHabStation()
        private set

    /** Id de la SAISIE OccHab en cours (groupe des stations de la session). Une station enregistrée
     *  au fil de l'eau est écrite dans cette saisie (cf. [OccHabStore.upsertStation]). Nouvelle
     *  saisie à chaque [demarrerSession] ; repris à l'identique en réédition ([reprendreSaisie]). */
    var saisieId: String = java.util.UUID.randomUUID().toString()
        private set

    /** Démarre une nouvelle SESSION (tuile OccHab) = une nouvelle SAISIE : détails aux défauts
     *  serveur, JDD à saisir, station vierge. */
    fun demarrerSession(defauts: OccHabDetailsSession) {
        details = defauts
        jddDefini = false
        station = OccHabStation()
        saisieId = java.util.UUID.randomUUID().toString()
    }

    /** Nouvelle station dans la MÊME saisie : garde les [details] et [saisieId], repart d'une
     *  géométrie et d'habitats vierges. */
    fun nouvelleStation() { station = OccHabStation() }

    fun definirGeometrie(type: String, lat: Double, lon: Double, coordsJson: String?) {
        station = station.copy(
            geometryType = type, latitude = lat, longitude = lon, geometryCoordsJson = coordsJson,
        )
    }

    fun ajouterOuMajHabitat(h: OccHabHabitat) {
        val liste = station.habitats.toMutableList()
        val idx = liste.indexOfFirst { it.id == h.id }
        if (idx >= 0) liste[idx] = h else liste.add(h)
        station = station.copy(habitats = liste)
    }

    fun supprimerHabitat(id: String) {
        station = station.copy(habitats = station.habitats.filterNot { it.id == id })
    }

    /** Fixe le jeu de données (formulaire JDD de la 1ʳᵉ station). */
    fun definirJdd(id: Int?, nom: String?) {
        details.idDataset = id
        details.nomDataset = nom
        jddDefini = id != null
    }

    /** Applique une modification aux détails de session (bouton « Détails »). */
    fun majDetails(bloc: (OccHabDetailsSession) -> Unit) {
        bloc(details)
        if (details.idDataset != null) jddDefini = true
    }

    /** Reprend une SAISIE existante pour réédition (depuis « Mes stations ») : fixe la [saisieId]
     *  courante, hérite les détails de sa 1ʳᵉ station (pour une station AJOUTÉE), et repart d'une
     *  station vierge (la carte affichera les stations existantes, « Valider » en ajoute une). */
    fun reprendreSaisie(saisie: fr.ariegenature.geomys.model.OccHabSaisie) {
        saisieId = saisie.id
        station = OccHabStation()
        val premiere = saisie.stations.firstOrNull()
        details = if (premiere == null) OccHabDetailsSession() else OccHabDetailsSession(
            idDataset = premiere.idDataset,
            observateursIds = premiere.observateursIds,
            observateursNoms = premiere.observateursNoms,
            observateursTxt = premiere.observateursTxt,
            dateMin = premiere.dateMin,
            dateMax = premiere.dateMax,
            altitudeMin = premiere.altitudeMin,
            altitudeMax = premiere.altitudeMax,
            surface = premiere.surface,
            idNomCalculSurface = premiere.idNomCalculSurface,
            idNomObjetGeographique = premiere.idNomObjetGeographique,
            comment = premiere.comment,
        )
        jddDefini = premiere?.idDataset != null
    }

    /** Reprend une STATION existante de la saisie courante pour l'éditer (garde la [saisieId]) :
     *  charge sa géométrie, ses habitats ET ses détails ; le JDD est considéré défini. */
    fun reprendreStation(existante: OccHabStation) {
        station = existante.copy(habitats = existante.habitats.map { it.copy() })
        details = OccHabDetailsSession(
            idDataset = existante.idDataset,
            observateursIds = existante.observateursIds,
            observateursNoms = existante.observateursNoms,
            observateursTxt = existante.observateursTxt,
            dateMin = existante.dateMin,
            dateMax = existante.dateMax,
            altitudeMin = existante.altitudeMin,
            altitudeMax = existante.altitudeMax,
            surface = existante.surface,
            idNomCalculSurface = existante.idNomCalculSurface,
            idNomObjetGeographique = existante.idNomObjetGeographique,
            comment = existante.comment,
        )
        jddDefini = true
    }

    /** Station à enregistrer/envoyer = géométrie + habitats (station courante) fusionnés avec les
     *  détails communs de la session. Conserve l'id de [station] (nouvelle ou rééditée). */
    fun stationAEnregistrer(): OccHabStation = station.copy(
        idDataset = details.idDataset,
        observateursIds = details.observateursIds,
        observateursNoms = details.observateursNoms,
        observateursTxt = details.observateursTxt,
        dateMin = details.dateMin,
        dateMax = details.dateMax,
        altitudeMin = details.altitudeMin,
        altitudeMax = details.altitudeMax,
        surface = details.surface,
        idNomCalculSurface = details.idNomCalculSurface,
        idNomObjetGeographique = details.idNomObjetGeographique,
        comment = details.comment,
    )
}
