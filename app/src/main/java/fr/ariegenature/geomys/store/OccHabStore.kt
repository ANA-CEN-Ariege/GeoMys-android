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

package fr.ariegenature.geomys.store

import android.content.Context
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

/**
 * Stockage local des stations OccHab saisies (« Mes stations »), sur [JsonCollectionStore] comme
 * [SortieStore] : cache process-wide, quarantaine, écriture durable par `commit()`, normalisation
 * post-Gson, lire-modifier-écrire atomique.
 *
 * Ne contient QUE les stations créées localement. Les stations lues depuis le serveur
 * (consultation lecture seule) ne sont pas persistées ici.
 */
class OccHabStore(context: Context) : JsonCollectionStore<OccHabStation>() {
    private val prefs = context.getSharedPreferences("occhab_store", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir
    private val key = "stations_sauvegardees"

    override val nom = "OccHabStore"
    override val verrou get() = VERROU
    override var cache: List<OccHabStation>?
        get() = mem
        set(v) { mem = v }
    override val typeListe: Type = object : TypeToken<MutableList<OccHabStation?>>() {}.type

    override fun lireBrut(): String? = prefs.getString(key, null)
    override fun ecrireBrut(json: String): Boolean = prefs.edit().putString(key, json).commit()
    override fun quarantaine(json: String) {
        try {
            val q = File(filesDir, "occhab_store.corrupt.json")
            if (!q.exists()) q.writeText(json)
        } catch (_: Exception) {}
    }
    override fun normaliser(item: OccHabStation): OccHabStation? = normaliserStation(item)

    companion object {
        @Volatile private var mem: List<OccHabStation>? = null
        private val VERROU = Any()

        /** Réinitialise le cache mémoire process-wide. Réservé aux TESTS. */
        @androidx.annotation.VisibleForTesting
        fun reinitialiserCacheMemoire() { mem = null }
    }

    fun ajouter(station: OccHabStation): Boolean = muter { it.add(0, station) }

    /** Remplace la station [id] en préservant sa position (reprise d'édition). Ajoute en tête
     *  si l'id n'existe pas. */
    fun remplacer(id: String, stationMaj: OccHabStation): Boolean = muter { liste ->
        val idx = liste.indexOfFirst { it.id == id }
        if (idx >= 0) liste[idx] = stationMaj else liste.add(0, stationMaj)
    }

    fun supprimer(id: String) { muter { liste -> liste.removeAll { it.id == id } } }

    /** Marque la station comme envoyée (efface l'erreur d'un échec précédent) et enregistre
     *  l'id_station attribué par le serveur. */
    fun marquerEnvoyee(id: String, idStationServeur: Int?) {
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == id }
            if (idx >= 0) liste[idx] = liste[idx].copy(
                envoyeGeoNature = true,
                idStationServeur = idStationServeur ?: liste[idx].idStationServeur,
                derniereErreurEnvoi = null,
                envoiIncertain = false, // envoi confirmé : plus d'incertitude.
            )
        }
    }

    /** Mémorise un échec d'envoi NET (rejet serveur / requête non émise) : la station n'a PAS
     *  été créée. Efface l'incertitude éventuelle d'une tentative précédente. Cadre rouge. */
    fun marquerErreurEnvoi(id: String, message: String) {
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == id }
            if (idx >= 0) liste[idx] = liste[idx].copy(
                derniereErreurEnvoi = message.take(200),
                envoiIncertain = false,
            )
        }
    }

    /** Mémorise un envoi au statut INCERTAIN (réseau coupé après l'émission : la station a
     *  peut-être été créée). Le prochain envoi vérifiera d'abord l'existence côté serveur. */
    fun marquerEnvoiIncertain(id: String, message: String) {
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == id }
            if (idx >= 0) liste[idx] = liste[idx].copy(
                derniereErreurEnvoi = message.take(200),
                envoiIncertain = true,
            )
        }
    }
}

// ── Normalisation post-Gson ───────────────────────────────────────────────────────────────
// Gson instancie par Unsafe sans passer par le constructeur : les champs ABSENTS du JSON
// restent null, y compris les listes NON-NULLABLES ajoutées par des versions plus récentes.
// On reconstruit par CONSTRUCTEUR explicite (pas copy() : il crasherait sur les champs null).
// Même filet que SortieStore.normaliserSortie / OutboxMonitoring.normaliser.

// Écarte (retour null) une entrée structurellement invalide (id/habitats null via Gson), sinon
// reconstruit une station sûre par CONSTRUCTEUR explicite (pas copy() : il crasherait sur null).
@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
private fun normaliserStation(s: OccHabStation): OccHabStation? {
    if (s.id == null || s.habitats == null) return null
    return OccHabStation(
    id = s.id,
    // Stations d'avant l'ajout de l'UUID SINP (JSON ancien → null via Gson) : on en génère un
    // maintenant. Il se fige au prochain enregistrement (au fil de l'eau), donc reste stable pour
    // la vérification d'existence d'un ré-envoi.
    uuidStation = s.uuidStation ?: java.util.UUID.randomUUID().toString(),
    idStationServeur = s.idStationServeur,
    date = s.date,
    geometryType = s.geometryType ?: "Point",
    latitude = s.latitude,
    longitude = s.longitude,
    geometryCoordsJson = s.geometryCoordsJson,
    idDataset = s.idDataset,
    observateursIds = (s.observateursIds ?: emptyList()).filterNotNull(),
    observateursNoms = (s.observateursNoms ?: emptyList()).filterNotNull(),
    observateursTxt = s.observateursTxt,
    stationName = s.stationName,
    comment = s.comment,
    dateMin = s.dateMin,
    dateMax = s.dateMax,
    altitudeMin = s.altitudeMin,
    altitudeMax = s.altitudeMax,
    profondeurMin = s.profondeurMin,
    profondeurMax = s.profondeurMax,
    surface = s.surface,
    precision = s.precision,
    idNomExposition = s.idNomExposition,
    idNomCalculSurface = s.idNomCalculSurface,
    idNomObjetGeographique = s.idNomObjetGeographique,
    idNomTypeSol = s.idNomTypeSol,
    idNomTypeMosaique = s.idNomTypeMosaique,
    habitats = (s.habitats ?: emptyList()).mapNotNull { h -> if (h == null) null else normaliserHabitat(h) },
    envoyeGeoNature = s.envoyeGeoNature,
    origineServeur = s.origineServeur,
    derniereErreurEnvoi = s.derniereErreurEnvoi,
    envoiIncertain = s.envoiIncertain,
    )
}

@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
private fun normaliserHabitat(h: OccHabHabitat): OccHabHabitat? {
    if (h.id == null) return null
    return OccHabHabitat(
        id = h.id,
        cdHab = h.cdHab,
        habitatLabel = h.habitatLabel ?: "",
        nomCite = h.nomCite ?: "",
        determiner = h.determiner,
        recouvrement = h.recouvrement,
        precisionTechnique = h.precisionTechnique,
        idNomTypeDetermination = h.idNomTypeDetermination,
        idNomTechniqueCollecte = h.idNomTechniqueCollecte,
        idNomAbondance = h.idNomAbondance,
        idNomSensibilite = h.idNomSensibilite,
        idNomInteretCommunautaire = h.idNomInteretCommunautaire,
    )
}
