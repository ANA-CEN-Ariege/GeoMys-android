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
import fr.ariegenature.geomys.model.Denombrement
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Sortie
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

/** Store local des sorties Occtax (« Mes saisies »), sur [JsonCollectionStore] : cache process-wide,
 *  quarantaine, normalisation post-Gson, lire-modifier-écrire atomique. Backend : SharedPreferences
 *  (`commit()` synchrone → durable même sur kill brutal ; données petites, écritures peu fréquentes).
 *  Copie de secours d'un JSON illisible dans filesDir (les prefs n'offrent pas de fichier annexe). */
class SortieStore(context: Context) : JsonCollectionStore<Sortie>() {
    private val prefs = context.getSharedPreferences("sorties_store", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir
    private val key = "sorties_sauvegardees"

    override val nom = "SortieStore"
    override val verrou get() = VERROU
    override var cache: List<Sortie>?
        get() = mem
        set(v) { mem = v }
    override val typeListe: Type = object : TypeToken<MutableList<Sortie?>>() {}.type

    override fun lireBrut(): String? = prefs.getString(key, null)
    override fun ecrireBrut(json: String): Boolean = prefs.edit().putString(key, json).commit()
    override fun quarantaine(json: String) {
        try {
            val q = File(filesDir, "sorties_store.corrupt.json")
            if (!q.exists()) q.writeText(json)
        } catch (_: Exception) {}
    }
    override fun normaliser(item: Sortie): Sortie? = normaliserSortie(item)

    companion object {
        // Cache + verrou process-wide : toutes les instances visent le même fichier de prefs, on
        // évite de RE-DÉSÉRIALISER tout le store à chaque action de saisie, et le verrou sérialise
        // les écritures croisées (auto-save UI vs marquage depuis le chemin d'envoi sur IO).
        @Volatile private var mem: List<Sortie>? = null
        private val VERROU = Any()

        /** Réinitialise le cache mémoire process-wide. Réservé aux TESTS (le cache statique fuit
         *  sinon d'un test à l'autre). */
        @androidx.annotation.VisibleForTesting
        fun reinitialiserCacheMemoire() { mem = null }
    }

    fun ajouter(sortie: Sortie): Boolean = muter { it.add(0, sortie) }

    /** Remplace la sortie [id] par [sortieMaj] en préservant sa position dans la liste. Si
     *  l'id n'existe pas, ajoute en tête (= comportement [ajouter]). Utilisé pour la reprise
     *  d'une sortie depuis l'onglet "À envoyer". */
    fun remplacer(id: String, sortieMaj: Sortie): Boolean = muter { liste ->
        val idx = liste.indexOfFirst { it.id == id }
        if (idx >= 0) liste[idx] = sortieMaj else liste.add(0, sortieMaj)
    }

    fun supprimer(id: String) { muter { liste -> liste.removeAll { it.id == id } } }

    fun marquerEnvoyee(id: String) {
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == id }
            // Succès → on efface aussi l'éventuelle erreur d'un échec précédent.
            if (idx >= 0) liste[idx] = liste[idx].copy(envoyeGeoNature = true, derniereErreurEnvoi = null)
        }
    }

    /** Marque [obsIds] comme créées côté serveur ([Observation.envoyeeServeur]) après un
     *  envoi PARTIEL : au prochain envoi de la sortie, elles ne seront pas re-postées
     *  (anti-doublon), seules les obs restantes partiront. À appeler AVANT tout marquage
     *  global de la sortie pour que l'acquis survive même si la suite échoue. */
    fun marquerObservationsEnvoyees(id: String, obsIds: Collection<String>) {
        if (obsIds.isEmpty()) return
        val ids = obsIds.toSet()
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == id }
            if (idx >= 0) liste[idx] = liste[idx].copy(observations = liste[idx].observations.map { o ->
                if (o.id in ids) o.copy(envoyeeServeur = true) else o
            })
        }
    }

    /** Mémorise l'échec du dernier envoi (message humanisé) — affiché en cadre rouge dans
     *  « Mes saisies » pour que l'échec reste visible après la fermeture du dialog. */
    fun marquerErreurEnvoi(id: String, message: String) {
        muter { liste ->
            val idx = liste.indexOfFirst { it.id == id }
            if (idx >= 0) liste[idx] = liste[idx].copy(derniereErreurEnvoi = message.take(200))
        }
    }
}

// ── Normalisation post-Gson ───────────────────────────────────────────────────────────────
// Gson instancie par Unsafe sans passer par le constructeur : les champs ABSENTS du JSON
// restent null, y compris les listes/maps NON-NULLABLES ajoutées par des versions plus
// récentes que la saisie (ex. denombrementsAdditionnels, observateursReleveIds…). Sans ce
// filet, NPE différée à l'ENVOI (GeoNatureUpload lit denombrementsAdditionnels) ou à la
// reprise d'édition (copy() → checkNotNullParameter). Même précédent que le bug 0.10.4
// corrigé dans OutboxMonitoring.normaliser — porté ici sur SortieStore.
// NB : on reconstruit par CONSTRUCTEUR explicite (pas copy() : il crasherait justement sur
// les champs null qu'on cherche à réparer).

// Gson ne valide pas les types Kotlin : un JSON corrompu-mais-parsable peut produire des champs
// non-nullables à null (entrée nulle, id manquant) → crash différé (NPE) au premier usage. On
// ÉCARTE ces entrées (retour null) au chargement ; sinon on reconstruit une entrée sûre.
@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS") // Gson viole la non-nullabilité Kotlin
private fun normaliserSortie(s: Sortie): Sortie? {
    if (s.id == null || s.observations == null || s.pointsParcours == null) return null
    return s.copy(
        // copy() est sûr ICI : les champs non-nullables (id/observations/pointsParcours) viennent
        // d'être vérifiés non-null juste au-dessus.
        pointsParcours = s.pointsParcours.filterNotNull(),
        observations = s.observations.mapNotNull { o -> if (o == null) null else normaliserObservation(o) },
    )
}

@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
private fun normaliserObservation(o: Observation): Observation? {
    if (o.id == null) return null
    return Observation(
        id = o.id,
        espece = o.espece ?: "",
        taxon = o.taxon,
        cdNom = o.cdNom,
        latitude = o.latitude,
        longitude = o.longitude,
        geometryType = o.geometryType,
        geometryCoordsJson = o.geometryCoordsJson,
        date = o.date,
        notes = o.notes ?: "",
        nombre = o.nombre,
        sexe = o.sexe,
        stadeVie = o.stadeVie,
        objDenbr = o.objDenbr,
        typDenbr = o.typDenbr,
        techniqueObs = o.techniqueObs,
        statutBio = o.statutBio,
        etaBio = o.etaBio,
        preuveExist = o.preuveExist,
        comportement = o.comportement,
        methDetermin = o.methDetermin,
        naturalite = o.naturalite,
        determinateur = o.determinateur,
        releveId = o.releveId,
        nombreMax = o.nombreMax,
        denombrementsAdditionnels = (o.denombrementsAdditionnels ?: emptyList())
            .mapNotNull { d -> if (d == null) null else normaliserDenombrement(d) },
        statutObs = o.statutObs,
        mediaUrisCounting0 = (o.mediaUrisCounting0 ?: emptyList()).filterNotNull(),
        additionalFieldsReleve = o.additionalFieldsReleve ?: emptyMap(),
        champsOccExtra = o.champsOccExtra ?: emptyMap(),
        additionalFieldsOccurrence = o.additionalFieldsOccurrence ?: emptyMap(),
        additionalFieldsCounting0 = o.additionalFieldsCounting0 ?: emptyMap(),
        idDatasetReleve = o.idDatasetReleve,
        observateursReleveIds = (o.observateursReleveIds ?: emptyList()).filterNotNull(),
        observateursReleveNoms = (o.observateursReleveNoms ?: emptyList()).filterNotNull(),
        observateurReleveId = o.observateurReleveId,
        observateurReleveNom = o.observateurReleveNom,
        commentReleve = o.commentReleve,
        cdHabReleve = o.cdHabReleve,
        habitatReleveLabel = o.habitatReleveLabel,
        typGrpReleve = o.typGrpReleve,
        dateDebutReleve = o.dateDebutReleve,
        dateFinReleve = o.dateFinReleve,
        champsReleveExtra = o.champsReleveExtra ?: emptyMap(),
        envoyeeServeur = o.envoyeeServeur,
    )
}

@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
private fun normaliserDenombrement(d: Denombrement): Denombrement? {
    if (d.id == null) return null
    return Denombrement(
        id = d.id,
        nombreMin = d.nombreMin,
        nombreMax = d.nombreMax,
        sexe = d.sexe,
        stadeVie = d.stadeVie,
        objDenbr = d.objDenbr,
        typDenbr = d.typDenbr,
        mediaUris = (d.mediaUris ?: emptyList()).filterNotNull(),
        additionalFields = d.additionalFields ?: emptyMap(),
    )
}
