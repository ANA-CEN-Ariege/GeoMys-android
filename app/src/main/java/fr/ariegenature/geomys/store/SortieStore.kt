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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class SortieStore(context: Context) {
    private val prefs = context.getSharedPreferences("sorties_store", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "sorties_sauvegardees"
    // Pour la quarantaine d'un JSON illisible (cf. charger) — les prefs n'offrent pas de
    // fichier annexe, on écrit la copie de secours dans filesDir.
    private val filesDir = context.filesDir

    companion object {
        // Cache mémoire process-wide : toutes les instances visent le même fichier de prefs, on
        // évite donc de RE-DÉSÉRIALISER tout le store à chaque action de saisie (l'auto-save « au
        // fil de l'eau » appelait charger() plusieurs fois par ajout/suppression d'espèce).
        // [charger] renvoie une COPIE défensive (les appelants mutent la liste) ; [sauvegarder]
        // remplace le cache. Le cache n'est invalidé que par une écriture → toujours cohérent.
        @Volatile private var mem: List<Sortie>? = null

        /** Réinitialise le cache mémoire process-wide. Réservé aux TESTS (le cache statique fuit
         *  sinon d'un test à l'autre). Inutile en production : le cache n'est invalidé que par une
         *  écriture, et toutes les instances visent le même fichier de prefs. */
        @androidx.annotation.VisibleForTesting
        fun reinitialiserCacheMemoire() { mem = null }
    }

    @Suppress("SENSELESS_COMPARISON") // Gson peut violer la non-nullabilité Kotlin (cf. filtre)
    fun charger(): MutableList<Sortie> {
        mem?.let { return ArrayList(it) }
        val json = prefs.getString(key, null)
        val parsed: MutableList<Sortie> = if (json == null) mutableListOf() else try {
            val type = object : TypeToken<MutableList<Sortie?>>() {}.type
            val brutes = (gson.fromJson<MutableList<Sortie?>>(json, type) ?: mutableListOf())
                .filterNotNull()
            // Gson ne valide pas les types Kotlin : un JSON corrompu-mais-parsable peut
            // produire des champs non-nullables à null (entrée nulle, id manquant) et un
            // crash différé (NPE) au premier usage. On écarte ces entrées au chargement.
            val valides = brutes
                .filter { it.id != null && it.observations != null && it.pointsParcours != null }
                .map(::normaliserSortie)
                .toMutableList()
            if (valides.size < brutes.size) {
                android.util.Log.w("SortieStore",
                    "charger : ${brutes.size - valides.size} sortie(s) illisible(s) écartée(s)")
                mettreEnQuarantaine(json)
            }
            valides
        } catch (e: Exception) {
            // Quarantaine AVANT retour vide : sans ça, la prochaine sauvegarde (ajouter =
            // charger()+sortie) écraserait la clé prefs avec une liste quasi vide — perte
            // définitive et silencieuse de toutes les saisies. Le fichier .corrupt reste
            // récupérable (adb run-as / support).
            android.util.Log.e("SortieStore", "charger : JSON illisible, mise en quarantaine", e)
            mettreEnQuarantaine(json)
            mutableListOf()
        }
        mem = parsed
        return ArrayList(parsed)
    }

    /** Copie de secours du JSON du store quand il est illisible ou partiellement écarté.
     *  Un seul fichier conservé (le PREMIER incident — c'est lui qui porte les données
     *  d'origine, les suivants seraient des copies déjà dégradées). */
    private fun mettreEnQuarantaine(json: String?) {
        if (json == null) return
        try {
            val quarantaine = File(filesDir, "sorties_store.corrupt.json")
            if (!quarantaine.exists()) quarantaine.writeText(json)
        } catch (_: Exception) {}
    }

    fun sauvegarder(sorties: List<Sortie>): Boolean {
        // commit() (synchrone) et non apply() : l'auto-save « au fil de l'eau » doit être
        // durable même sur un kill brutal (SIGKILL via stop Android Studio / force-stop /
        // OOM) qui surviendrait avant le flush disque asynchrone d'apply(). Données petites
        // + écritures peu fréquentes (au rythme des obs) → coût négligeable.
        // Le cache mémoire n'est mis à jour QU'APRÈS un commit confirmé : sinon l'app
        // afficherait comme sauvées des saisies qui disparaîtraient au redémarrage
        // (disque plein, I/O en échec).
        val ok = prefs.edit().putString(key, gson.toJson(sorties)).commit()
        if (ok) {
            mem = ArrayList(sorties)
        } else {
            android.util.Log.e("SortieStore",
                "sauvegarder ECHEC commit — cache mémoire NON mis à jour (disque conservé)")
        }
        return ok
    }

    fun ajouter(sortie: Sortie): Boolean {
        val sorties = charger()
        sorties.add(0, sortie)
        return sauvegarder(sorties)
    }

    /** Remplace la sortie [id] par [sortieMaj] en préservant sa position dans la liste. Si
     *  l'id n'existe pas, ajoute en tête (= comportement [ajouter]). Utilisé pour la reprise
     *  d'une sortie depuis l'onglet "À envoyer". */
    fun remplacer(id: String, sortieMaj: Sortie): Boolean {
        val sorties = charger()
        val idx = sorties.indexOfFirst { it.id == id }
        return if (idx >= 0) {
            sorties[idx] = sortieMaj
            sauvegarder(sorties)
        } else {
            sorties.add(0, sortieMaj)
            sauvegarder(sorties)
        }
    }

    fun supprimer(id: String) {
        val sorties = charger()
        sorties.removeAll { it.id == id }
        sauvegarder(sorties)
    }

    fun marquerEnvoyee(id: String) {
        val sorties = charger()
        val idx = sorties.indexOfFirst { it.id == id }
        if (idx >= 0) {
            // Succès → on efface aussi l'éventuelle erreur d'un échec précédent.
            sorties[idx] = sorties[idx].copy(envoyeGeoNature = true, derniereErreurEnvoi = null)
            sauvegarder(sorties)
        }
    }

    /** Marque [obsIds] comme créées côté serveur ([Observation.envoyeeServeur]) après un
     *  envoi PARTIEL : au prochain envoi de la sortie, elles ne seront pas re-postées
     *  (anti-doublon), seules les obs restantes partiront. À appeler AVANT tout marquage
     *  global de la sortie pour que l'acquis survive même si la suite échoue. */
    fun marquerObservationsEnvoyees(id: String, obsIds: Collection<String>) {
        if (obsIds.isEmpty()) return
        val sorties = charger()
        val idx = sorties.indexOfFirst { it.id == id }
        if (idx < 0) return
        val ids = obsIds.toSet()
        sorties[idx] = sorties[idx].copy(observations = sorties[idx].observations.map { o ->
            if (o.id in ids) o.copy(envoyeeServeur = true) else o
        })
        sauvegarder(sorties)
    }

    /** Mémorise l'échec du dernier envoi (message humanisé) — affiché en cadre rouge dans
     *  « Mes saisies » pour que l'échec reste visible après la fermeture du dialog. */
    fun marquerErreurEnvoi(id: String, message: String) {
        val sorties = charger()
        val idx = sorties.indexOfFirst { it.id == id }
        if (idx >= 0) {
            sorties[idx] = sorties[idx].copy(derniereErreurEnvoi = message.take(200))
            sauvegarder(sorties)
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

@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS") // Gson viole la non-nullabilité Kotlin
private fun normaliserSortie(s: Sortie): Sortie = s.copy(
    // copy() est sûr ICI : les champs non-nullables de Sortie (id/observations/pointsParcours)
    // ont été vérifiés par le filtre de charger() avant l'appel.
    pointsParcours = s.pointsParcours.filterNotNull(),
    observations = s.observations.mapNotNull { o -> if (o == null) null else normaliserObservation(o) },
)

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
