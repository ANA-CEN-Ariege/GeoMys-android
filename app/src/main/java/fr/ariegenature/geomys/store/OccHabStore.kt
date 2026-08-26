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
import fr.ariegenature.geomys.model.OccHabSaisie
import fr.ariegenature.geomys.model.OccHabStation
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

/**
 * Stockage local des SAISIES OccHab (« Mes stations »), sur [JsonCollectionStore] comme
 * [SortieStore] : cache process-wide, quarantaine, écriture durable par `commit()`, normalisation
 * post-Gson, lire-modifier-écrire atomique. Une saisie ([OccHabSaisie]) regroupe les stations
 * saisies dans une même session (cf. `Sortie` regroupe des `Observation`).
 *
 * Ne contient QUE des saisies créées localement. Les stations lues depuis le serveur (consultation
 * lecture seule) ne sont pas persistées ici.
 */
class OccHabStore(context: Context) : JsonCollectionStore<OccHabSaisie>() {
    private val prefs = context.getSharedPreferences("occhab_store", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir
    private val key = "saisies_sauvegardees"

    init {
        // Ancien format « une station = une entrée » (clé stations_sauvegardees) : on repart de
        // zéro (décision produit — OccHab récent, peu de données en attente). Purge une seule fois.
        if (!ancienStorePurge) {
            synchronized(VERROU) {
                if (!ancienStorePurge) {
                    prefs.edit().remove("stations_sauvegardees").apply()
                    ancienStorePurge = true
                }
            }
        }
    }

    override val nom = "OccHabStore"
    override val verrou get() = VERROU
    override var cache: List<OccHabSaisie>?
        get() = mem
        set(v) { mem = v }
    override val typeListe: Type = object : TypeToken<MutableList<OccHabSaisie?>>() {}.type

    override fun lireBrut(): String? = prefs.getString(key, null)
    override fun ecrireBrut(json: String): Boolean = prefs.edit().putString(key, json).commit()
    override fun quarantaine(json: String) {
        try {
            val q = File(filesDir, "occhab_store.corrupt.json")
            if (!q.exists()) q.writeText(json)
        } catch (_: Exception) {}
    }
    override fun normaliser(item: OccHabSaisie): OccHabSaisie? = normaliserSaisie(item)

    companion object {
        @Volatile private var mem: List<OccHabSaisie>? = null
        private val VERROU = Any()
        @Volatile private var ancienStorePurge = false

        /** Réinitialise le cache mémoire process-wide. Réservé aux TESTS. */
        @androidx.annotation.VisibleForTesting
        fun reinitialiserCacheMemoire() { mem = null }
    }

    /** Supprime toute la saisie [id] (cascade sur ses stations). */
    fun supprimer(id: String) { muter { liste -> liste.removeAll { it.id == id } } }

    /** Sauvegarde AU FIL DE L'EAU d'une station dans la saisie [saisieId] : insère ou remplace la
     *  station (par son id), crée la saisie si elle n'existe pas encore (1ʳᵉ station de la session). */
    fun upsertStation(saisieId: String, station: OccHabStation): Boolean = muter { liste ->
        val idx = liste.indexOfFirst { it.id == saisieId }
        if (idx < 0) {
            liste.add(0, OccHabSaisie(id = saisieId, stations = listOf(station)))
        } else {
            val stations = liste[idx].stations.toMutableList()
            val j = stations.indexOfFirst { it.id == station.id }
            if (j >= 0) stations[j] = station else stations.add(station)
            liste[idx] = recalc(liste[idx].copy(stations = stations))
        }
    }

    /** Retire une station de la saisie ; supprime la saisie si elle devient vide. */
    fun supprimerStation(saisieId: String, stationId: String) {
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == saisieId }
            if (idx >= 0) {
                val stations = liste[idx].stations.filterNot { it.id == stationId }
                if (stations.isEmpty()) liste.removeAt(idx)
                else liste[idx] = recalc(liste[idx].copy(stations = stations))
            }
        }
    }

    /** Stations d'une saisie (lecture — pour la carte de réédition). Vide si saisie inconnue. */
    fun stationsDeSaisie(saisieId: String): List<OccHabStation> =
        charger().firstOrNull { it.id == saisieId }?.stations ?: emptyList()

    /** Marque une station envoyée + recalcule l'état de la saisie (envoyée = toutes envoyées). */
    fun marquerStationEnvoyee(saisieId: String, stationId: String, idStationServeur: Int?) =
        majStation(saisieId, stationId) {
            it.copy(
                envoyeGeoNature = true,
                idStationServeur = idStationServeur ?: it.idStationServeur,
                derniereErreurEnvoi = null,
                envoiIncertain = false, // envoi confirmé.
            )
        }

    /** Échec NET d'une station (rejet serveur / non émise) : la station n'a PAS été créée. */
    fun marquerStationErreur(saisieId: String, stationId: String, message: String) =
        majStation(saisieId, stationId) {
            it.copy(derniereErreurEnvoi = message.take(200), envoiIncertain = false)
        }

    /** Envoi INCERTAIN d'une station (réseau coupé après émission — peut-être créée). */
    fun marquerStationIncertain(saisieId: String, stationId: String, message: String) =
        majStation(saisieId, stationId) {
            it.copy(derniereErreurEnvoi = message.take(200), envoiIncertain = true)
        }

    /** Message d'erreur AU NIVEAU SAISIE (résumé d'un envoi partiel). N'affecte pas l'envoi
     *  station par station (déjà persisté). */
    fun marquerErreurSaisie(saisieId: String, message: String) {
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == saisieId }
            if (idx >= 0) liste[idx] = liste[idx].copy(
                derniereErreurEnvoi = message.take(200),
                envoyeGeoNature = false,
            )
        }
    }

    /** Applique [transform] à une station et recalcule l'état de la saisie. Renvoie le succès de
     *  la PERSISTANCE (false = commit disque échoué → l'appelant peut requalifier en incertain). */
    private fun majStation(saisieId: String, stationId: String, transform: (OccHabStation) -> OccHabStation): Boolean =
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == saisieId }
            if (idx >= 0) {
                val stations = liste[idx].stations.toMutableList()
                val j = stations.indexOfFirst { it.id == stationId }
                if (j >= 0) {
                    stations[j] = transform(stations[j])
                    liste[idx] = recalc(liste[idx].copy(stations = stations))
                }
            }
        }

    /** Recalcule l'état DÉRIVÉ d'une saisie : envoyée ssi toutes ses stations le sont ; l'erreur
     *  saisie reflète la 1ʳᵉ station restant à envoyer en erreur (effacée si tout est parti). */
    private fun recalc(saisie: OccHabSaisie): OccHabSaisie {
        val toutesEnvoyees = saisie.stations.isNotEmpty() && saisie.stations.all { it.envoyeGeoNature }
        val erreur = if (toutesEnvoyees) null else saisie.stations
            .firstOrNull { !it.envoyeGeoNature && it.derniereErreurEnvoi != null }?.derniereErreurEnvoi
        return saisie.copy(envoyeGeoNature = toutesEnvoyees, derniereErreurEnvoi = erreur)
    }
}

// ── Normalisation post-Gson ───────────────────────────────────────────────────────────────
// Gson instancie par Unsafe sans passer par le constructeur : les champs ABSENTS du JSON restent
// null, y compris les listes NON-NULLABLES. On reconstruit par CONSTRUCTEUR explicite. Même filet
// que SortieStore.normaliserSortie / OutboxMonitoring.normaliser.

@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
private fun normaliserSaisie(s: OccHabSaisie): OccHabSaisie? {
    if (s.id == null || s.stations == null) return null
    return OccHabSaisie(
        id = s.id,
        date = s.date,
        stations = s.stations.mapNotNull { st -> if (st == null) null else normaliserStation(st) },
        envoyeGeoNature = s.envoyeGeoNature,
        derniereErreurEnvoi = s.derniereErreurEnvoi,
    )
}

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
        idHabitatServeur = h.idHabitatServeur,
        uuidHabitat = h.uuidHabitat,
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
