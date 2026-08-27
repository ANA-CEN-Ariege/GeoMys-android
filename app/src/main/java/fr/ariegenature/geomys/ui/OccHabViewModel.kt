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

    /** DÉFAUTS de la session (formulaire de démarrage) : ce dont hérite chaque NOUVELLE station.
     *  Décision terrain 2026-08-27 : les détails sont PAR STATION — [details] n'est que le tampon
     *  d'édition de la station sélectionnée (chargé par [reprendreStation], écrit par le « i »),
     *  et revient à ces défauts à chaque [nouvelleStation]. Modifier une station n'en touche
     *  jamais une autre. */
    var defautsSession = OccHabDetailsSession()
        private set

    /** Démarre une nouvelle SESSION (tuile OccHab) = une nouvelle SAISIE : détails aux défauts
     *  serveur, JDD à saisir, station vierge. */
    fun demarrerSession(defauts: OccHabDetailsSession) {
        defautsSession = defauts
        details = defauts.copy()
        jddDefini = false
        station = OccHabStation()
        saisieId = java.util.UUID.randomUUID().toString()
    }

    /** Nouvelle station dans la MÊME saisie : garde [saisieId], repart d'une géométrie et
     *  d'habitats vierges et des DÉFAUTS de session (détails par station). */
    fun nouvelleStation() {
        station = OccHabStation()
        details = defautsSession.copy()
    }

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

    /** Applique une modification aux détails de la STATION SÉLECTIONNÉE (bouton « i » /
     *  « Détails »). [demarrage] = formulaire de démarrage du relevé : ses valeurs deviennent
     *  aussi les DÉFAUTS des nouvelles stations de la session. */
    fun majDetails(demarrage: Boolean = false, bloc: (OccHabDetailsSession) -> Unit) {
        bloc(details)
        if (demarrage) defautsSession = details.copy()
        if (details.idDataset != null) jddDefini = true
    }

    /** Reprend une SAISIE existante pour réédition (depuis « Mes stations ») : fixe la [saisieId]
     *  courante, hérite les détails de sa 1ʳᵉ station (pour une station AJOUTÉE), et repart d'une
     *  station vierge (la carte affichera les stations existantes, « Valider » en ajoute une). */
    fun reprendreSaisie(saisie: fr.ariegenature.geomys.model.OccHabSaisie) {
        saisieId = saisie.id
        station = OccHabStation()
        val premiere = saisie.stations.firstOrNull()
        defautsSession = if (premiere == null) OccHabDetailsSession() else detailsDe(premiere)
        details = defautsSession.copy()
        jddDefini = premiere?.idDataset != null
    }

    /** Détails PROPRES à une station (tampon d'édition du « i »). Un JDD / des dates absents
     *  (station ancienne) retombent sur les défauts de session. */
    private fun detailsDe(s: OccHabStation) = OccHabDetailsSession(
        idDataset = s.idDataset ?: defautsSession.idDataset,
        nomDataset = if (s.idDataset == null || s.idDataset == defautsSession.idDataset) defautsSession.nomDataset else null,
        observateursIds = s.observateursIds,
        observateursNoms = s.observateursNoms,
        observateursTxt = s.observateursTxt,
        dateMin = s.dateMin ?: defautsSession.dateMin,
        dateMax = s.dateMax ?: defautsSession.dateMax,
        // altitudes/surface : par STATION, portées par [station] (cf. stationAEnregistrer).
        idNomCalculSurface = s.idNomCalculSurface,
        idNomObjetGeographique = s.idNomObjetGeographique,
        comment = s.comment,
        chargerStationsServeur = defautsSession.chargerStationsServeur,
    )

    /** Reprend une STATION existante de la saisie courante pour l'éditer (garde la [saisieId]) :
     *  charge SEULEMENT sa géométrie et ses habitats. On NE recharge PAS [details] : le JDD /
     *  observateurs / dates sont COMMUNS à la saisie (édités via « Détails ») — les écraser avec
     *  ceux d'une station rétrograderait silencieusement la session en cours. La station rééditée
     *  ré-hérite les détails de session à l'enregistrement ([stationAEnregistrer]). */
    fun reprendreStation(existante: OccHabStation) {
        station = existante.copy(habitats = existante.habitats.map { it.copy() })
        // Détails PAR STATION (décision terrain 2026-08-27) : le tampon [details] reflète la
        // station sélectionnée — JDD, observateurs, dates, commentaire, nomenclatures. Le « i »
        // modifie CETTE station et aucune autre ; [stationAEnregistrer] réinjecte ce tampon dans
        // la station (le tampon ET la station décrivent la même chose). Une station importée du
        // serveur reçoit ses dates de session À L'IMPORT (carte), pas ici : ses propres dates,
        // éventuellement corrigées via « i », sont conservées à la resélection.
        details = detailsDe(existante)
        if (details.idDataset != null) jddDefini = true
    }

    /** Valeurs « dérivées » saisissables à la main dans le dialogue Détails (altitudes, surface),
     *  EXCLUES de l'empreinte de contenu : à photographier avant le dialogue pour
     *  [forcerPersistanceSiDeriveesChangees]. */
    fun deriveesManuelles(): List<Any?> = listOf(station.altitudeMin, station.altitudeMax, station.surface)

    /** Après validation du dialogue Détails : une correction MANUELLE des altitudes / de la
     *  surface est une modification réelle même si l'empreinte ne la voit pas (audit 2026-08-27)
     *  → on lève l'empreinte d'origine pour que la prochaine persistance écrive la station. */
    fun forcerPersistanceSiDeriveesChangees(avant: List<Any?>) {
        if (station.empreinteOrigine != null && avant != deriveesManuelles()) {
            station = station.copy(empreinteOrigine = null)
        }
    }

    /** Fige l'empreinte d'ORIGINE de la station courante = son contenu tel qu'il serait persisté
     *  MAINTENANT (détails de session fusionnés, cf. [stationAEnregistrer]). Appelé juste après
     *  l'import d'une station serveur ou la remise en édition d'une station envoyée : tant que
     *  ni la géométrie, ni les habitats, ni les détails ne changent, `OccHabStore.upsertStation`
     *  ne l'écrit pas dans « Mes stations » (demande terrain 2026-08-27). */
    fun figerEmpreinteOrigine() {
        station = station.copy(empreinteOrigine = stationAEnregistrer().empreinteContenu())
    }

    /** Station à enregistrer/envoyer = géométrie + habitats (station courante) fusionnés avec le
     *  tampon [details] — qui est PAR STATION : chargé de la station à sa sélection, ou des
     *  défauts de session pour une nouvelle station. Conserve l'id de [station]. */
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
