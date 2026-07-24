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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Stockage local des stations OccHab saisies (« Mes stations »), calqué sur [SortieStore] :
 * cache mémoire process-wide, quarantaine d'un JSON illisible, écriture durable par `commit()`,
 * normalisation post-Gson (champs/listes absents d'un JSON ancien remis à leur défaut).
 *
 * Ne contient QUE les stations créées localement. Les stations lues depuis le serveur
 * (consultation lecture seule) ne sont pas persistées ici.
 */
class OccHabStore(context: Context) {
    private val prefs = context.getSharedPreferences("occhab_store", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "stations_sauvegardees"
    private val filesDir = context.filesDir

    companion object {
        @Volatile private var mem: List<OccHabStation>? = null

        /** Réinitialise le cache mémoire process-wide. Réservé aux TESTS. */
        @androidx.annotation.VisibleForTesting
        fun reinitialiserCacheMemoire() { mem = null }
    }

    @Suppress("SENSELESS_COMPARISON") // Gson peut violer la non-nullabilité Kotlin
    fun charger(): MutableList<OccHabStation> {
        mem?.let { return ArrayList(it) }
        val json = prefs.getString(key, null)
        val parsed: MutableList<OccHabStation> = if (json == null) mutableListOf() else try {
            val type = object : TypeToken<MutableList<OccHabStation?>>() {}.type
            val brutes = (gson.fromJson<MutableList<OccHabStation?>>(json, type) ?: mutableListOf())
                .filterNotNull()
            val valides = brutes
                .filter { it.id != null && it.habitats != null }
                .map(::normaliserStation)
                .toMutableList()
            if (valides.size < brutes.size) {
                android.util.Log.w("OccHabStore",
                    "charger : ${brutes.size - valides.size} station(s) illisible(s) écartée(s)")
                mettreEnQuarantaine(json)
            }
            valides
        } catch (e: Exception) {
            android.util.Log.e("OccHabStore", "charger : JSON illisible, mise en quarantaine", e)
            mettreEnQuarantaine(json)
            mutableListOf()
        }
        mem = parsed
        return ArrayList(parsed)
    }

    private fun mettreEnQuarantaine(json: String?) {
        if (json == null) return
        try {
            val quarantaine = File(filesDir, "occhab_store.corrupt.json")
            if (!quarantaine.exists()) quarantaine.writeText(json)
        } catch (_: Exception) {}
    }

    fun sauvegarder(stations: List<OccHabStation>): Boolean {
        val ok = prefs.edit().putString(key, gson.toJson(stations)).commit()
        if (ok) {
            mem = ArrayList(stations)
        } else {
            android.util.Log.e("OccHabStore",
                "sauvegarder ECHEC commit — cache mémoire NON mis à jour (disque conservé)")
        }
        return ok
    }

    fun ajouter(station: OccHabStation): Boolean {
        val stations = charger()
        stations.add(0, station)
        return sauvegarder(stations)
    }

    /** Remplace la station [id] en préservant sa position (reprise d'édition). Ajoute en tête
     *  si l'id n'existe pas. */
    fun remplacer(id: String, stationMaj: OccHabStation): Boolean {
        val stations = charger()
        val idx = stations.indexOfFirst { it.id == id }
        return if (idx >= 0) {
            stations[idx] = stationMaj
            sauvegarder(stations)
        } else {
            stations.add(0, stationMaj)
            sauvegarder(stations)
        }
    }

    fun supprimer(id: String) {
        val stations = charger()
        stations.removeAll { it.id == id }
        sauvegarder(stations)
    }

    /** Marque la station comme envoyée (efface l'erreur d'un échec précédent) et enregistre
     *  l'id_station attribué par le serveur. */
    fun marquerEnvoyee(id: String, idStationServeur: Int?) {
        val stations = charger()
        val idx = stations.indexOfFirst { it.id == id }
        if (idx >= 0) {
            stations[idx] = stations[idx].copy(
                envoyeGeoNature = true,
                idStationServeur = idStationServeur ?: stations[idx].idStationServeur,
                derniereErreurEnvoi = null,
            )
            sauvegarder(stations)
        }
    }

    /** Mémorise l'échec du dernier envoi (message humanisé) — affiché en cadre rouge. */
    fun marquerErreurEnvoi(id: String, message: String) {
        val stations = charger()
        val idx = stations.indexOfFirst { it.id == id }
        if (idx >= 0) {
            stations[idx] = stations[idx].copy(derniereErreurEnvoi = message.take(200))
            sauvegarder(stations)
        }
    }
}

// ── Normalisation post-Gson ───────────────────────────────────────────────────────────────
// Gson instancie par Unsafe sans passer par le constructeur : les champs ABSENTS du JSON
// restent null, y compris les listes NON-NULLABLES ajoutées par des versions plus récentes.
// On reconstruit par CONSTRUCTEUR explicite (pas copy() : il crasherait sur les champs null).
// Même filet que SortieStore.normaliserSortie / OutboxMonitoring.normaliser.

@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
private fun normaliserStation(s: OccHabStation): OccHabStation = OccHabStation(
    id = s.id,
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
)

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
