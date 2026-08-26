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
 * méthode de calcul de surface, nature objet géo, commentaire). Conservés d'une station à
 * l'autre tant qu'on reste dans OccHab ; édités via le bouton « Détails ». Réinitialisés aux
 * défauts serveur au démarrage d'une nouvelle session (tuile OccHab de l'accueil).
 * NB : altitudes et surface ne sont PAS ici — ce sont des propriétés PAR STATION (dérivées de
 * sa géométrie : surface auto-calculée, altitudes MNT), portées par [OccHabStation] directement.
 */
data class OccHabDetailsSession(
    var idDataset: Int? = null,
    var nomDataset: String? = null,
    var observateursIds: List<Int> = emptyList(),
    var observateursNoms: List<String> = emptyList(),
    var observateursTxt: String? = null,
    var dateMin: Long? = null,
    var dateMax: Long? = null,
    var idNomCalculSurface: Int? = null,
    var idNomObjetGeographique: Int? = null,
    var comment: String? = null,
    /** Switch du formulaire de démarrage : afficher sur la carte les stations DÉJÀ présentes
     *  sur le serveur GeoNature (consultation lecture seule + emprise adaptée). Mémorisé d'un
     *  relevé à l'autre comme les autres champs (occhabDetailsPrecedentsJson). */
    var chargerStationsServeur: Boolean = false,
)

/** Détails de session par défaut (défauts serveur) : observateur par défaut, dates début ET fin
 *  = date du JOUR (sans heure — le serveur OccHab attend des dates yyyy-MM-dd), nomenclatures
 *  par défaut OccHab. Le JDD reste null (saisi au formulaire de la 1ʳᵉ station). */
fun detailsSessionParDefaut(config: GeoNatureConfig): OccHabDetailsSession {
    val obsId = config.observateurDefautId.trim().toIntOrNull()?.takeIf { it > 0 }
        ?: config.idRoleUtilisateur.takeIf { it > 0 }
    val obsNom = config.observateurDefautNom.ifBlank { config.nomUtilisateur }
    val aujourdhui = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    // RELEVÉ PRÉCÉDENT : le formulaire des informations obligatoires repart des valeurs
    // validées la dernière fois (JDD, observateurs, nomenclatures — demande terrain
    // 2026-08-26) ; seules les DATES repartent systématiquement du jour.
    config.occhabDetailsPrecedentsJson.takeIf { it.isNotEmpty() }?.let { json ->
        runCatching {
            com.google.gson.Gson().fromJson(json, OccHabDetailsSession::class.java)
        }.getOrNull()
    }?.let { precedent ->
        return precedent.copy(dateMin = aujourdhui, dateMax = aujourdhui)
    }
    return OccHabDetailsSession(
        idDataset = null,
        observateursIds = listOfNotNull(obsId),
        observateursNoms = if (obsId != null && obsNom.isNotBlank()) listOf(obsNom) else emptyList(),
        dateMin = aujourdhui,
        dateMax = aujourdhui,
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

    /** Surface (m²) de la STATION courante — auto-calculée à la validation de la géométrie
     *  (parité web : patchGeoValue → area arrondie), écrasée à chaque re-validation. */
    fun definirSurface(m2: Long?) { station = station.copy(surface = m2) }

    /** Altitudes min/max (MNT serveur, /geo/info) de la STATION courante — remplies en
     *  best-effort quand il y a du réseau (parité web : patchGeoValue → getGeoInfo). */
    fun definirAltitudes(min: Int?, max: Int?) {
        station = station.copy(altitudeMin = min, altitudeMax = max)
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
            // altitudes/surface : par STATION, jamais héritées en session (cf. stationAEnregistrer).
            idNomCalculSurface = premiere.idNomCalculSurface,
            idNomObjetGeographique = premiere.idNomObjetGeographique,
            comment = premiere.comment,
        )
        jddDefini = premiere?.idDataset != null
    }

    /** Reprend une STATION existante de la saisie courante pour l'éditer (garde la [saisieId]) :
     *  charge SEULEMENT sa géométrie et ses habitats. On NE recharge PAS [details] : le JDD /
     *  observateurs / dates sont COMMUNS à la saisie (édités via « Détails ») — les écraser avec
     *  ceux d'une station rétrograderait silencieusement la session en cours. La station rééditée
     *  ré-hérite les détails de session à l'enregistrement ([stationAEnregistrer]). */
    fun reprendreStation(existante: OccHabStation) {
        station = existante.copy(habitats = existante.habitats.map { it.copy() })
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
        // altitudes/surface : PAS de fusion session — propriétés PAR STATION (dérivées de la
        // géométrie : surface auto-calculée à la validation de la carte, altitudes MNT serveur),
        // déjà portées par [station]. Les fusionner écrasait la valeur d'une station par celle
        // de la dernière éditée.
        idNomCalculSurface = details.idNomCalculSurface,
        idNomObjetGeographique = details.idNomObjetGeographique,
        comment = details.comment,
    )
}
