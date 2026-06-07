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

package fr.ariegenature.geomys.network

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.TaxRefCache
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

/** Orchestration du « Recharger les données » découplée de toute UI, pour pouvoir tourner dans
 *  un [fr.ariegenature.geomys.sync.SyncForegroundService] (= service au premier plan) et donc
 *  CONTINUER même si l'utilisateur quitte l'écran de config, met l'écran en veille ou passe
 *  l'app en arrière-plan. L'état est exposé via [etat] (LiveData) ; le service et l'écran de
 *  config s'y abonnent tous les deux (notification d'un côté, bandeau de progression de l'autre).
 *
 *  Reprend exactement les 7 étapes de l'ancien `chargerToutesLesDonnees` :
 *  datasets / listes / observateurs / champs additionnels (parallèle) → TaxRef → nomenclatures →
 *  Suivis. Seule la partie réseau + écriture des caches est ici ; le re-peuplement des spinners
 *  reste côté fragment (qui relit les caches JSON à la fin via `restaurerCaches`). */
object SyncRunner {

    /** État courant de la synchro globale. */
    data class Etat(
        val enCours: Boolean,
        val texte: String,
        /** Vrai une fois la synchro terminée (succès ou échec partiel). */
        val termine: Boolean = false,
        /** Faux si au moins une étape a échoué. */
        val succes: Boolean = true,
        /** Compte-rendu final affichable (étapes en échec, résumé TaxRef…). */
        val resume: String? = null,
    )

    private val _etat = MutableLiveData(Etat(enCours = false, texte = ""))
    val etat: LiveData<Etat> = _etat

    /** Vrai tant qu'une synchro est en cours — garde atomique contre un double lancement. */
    private val enCours = java.util.concurrent.atomic.AtomicBoolean(false)
    val actif: Boolean get() = enCours.get()

    private val gson = Gson()

    private fun publier(texte: String) {
        _etat.postValue(Etat(enCours = true, texte = texte))
    }

    /** Exécute la synchro complète. Idempotent vis-à-vis d'un appel concurrent : si une synchro
     *  est déjà en cours, retourne immédiatement. Ne lève pas — les échecs d'étape sont agrégés
     *  dans l'[Etat] terminal. */
    suspend fun executer(context: Context, forcerTaxRef: Boolean = false) {
        // Garde atomique : un seul executer() à la fois, même appelé concurremment.
        if (!enCours.compareAndSet(false, true)) return
        try {
            val config = GeoNatureConfig(context.applicationContext)
            publier("Préparation…")

            // Ardoise propre pour les caches toujours rafraîchis. TaxRef N'EST PAS vidé ici :
            // synchroniserTaxRef décide de le conserver (version+listes inchangées) ou de le vider
            // lui-même avant rechargement. Ça permet de sauter le re-téléchargement lourd de TaxRef.
            NomenclatureCache.vider()
            MonitoringCache.vider()
            MonitoringApi.invaliderCaches()

            val echecs = mutableListOf<String>()

            // Étapes 1-4 en parallèle (indépendantes, rapides). Chaque lambda écrit son cache
            // JSON et renvoie un message d'échec ou null. On charge AUSSI ici les modules
            // monitoring (une fois) pour en extraire les listes taxonomiques de protocoles, et les
            // passer à TaxRef — ça évite un chargerModules concurrent quand on lancera TaxRef et
            // Suivis en parallèle juste après.
            publier("Jeux de données, listes, observateurs…")
            var protocolListIds: Set<Int> = emptySet()
            coroutineScope {
                val mod = async {
                    try { MonitoringApi.chargerModules(config).mapNotNull { it.idListTaxonomy }.toSet() }
                    catch (_: Exception) { emptySet() }
                }
                val ds = async {
                    try {
                        val r = GeoNatureBrowse.chargerDatasets(config)
                        if (r.isNotEmpty()) { config.datasetsCacheJson = gson.toJson(r); null }
                        else "Aucun jeu de données"
                    } catch (e: Exception) { e.message ?: "Erreur datasets" }
                }
                // Datasets CRÉABLES en Occtax (CRUVED C) — pour aligner la liste proposée sur le web.
                // Best-effort : si indisponible, set vide = pas de restriction.
                val dsCre = async {
                    config.datasetsCreablesOcctax = GeoNatureBrowse.chargerIdsDatasetsCreables(config)
                    null
                }
                val li = async {
                    try {
                        val r = GeoNatureBrowse.chargerListesTaxons(config)
                        if (r.isNotEmpty()) { config.listesCacheJson = gson.toJson(r); null }
                        else "Aucune liste de taxons"
                    } catch (e: Exception) { e.message ?: "Erreur listes" }
                }
                val obs = async {
                    try {
                        val r = GeoNatureBrowse.chargerObservateurs(config)
                        if (r.isNotEmpty()) { config.observateursCacheJson = gson.toJson(r); null }
                        else "Aucun observateur"
                    } catch (e: Exception) { e.message ?: "Erreur observateurs" }
                }
                val add = async {
                    try {
                        val r = AdditionalFieldsApi.charger(config, "OCCTAX")
                        if (r.isNotEmpty()) config.additionalFieldsOcctaxJson = gson.toJson(r)
                        else if (config.additionalFieldsOcctaxJson.isEmpty()) config.additionalFieldsOcctaxJson = "[]"
                        null
                    } catch (e: Exception) { e.message ?: "Erreur champs additionnels" }
                }
                // Config de visibilité des champs Occtax (settings.json serveur). Best-effort, non
                // bloquant : sans config publiée le registre par défaut s'applique (tous champs visibles).
                val set = async {
                    GeoNatureSync.synchroniserSettingsOcctax(config)
                    null
                }
                listOf(
                    "Jeux de données" to ds.await(),
                    "Listes de taxons" to li.await(),
                    "Observateurs" to obs.await(),
                    "Champs additionnels" to add.await(),
                    "Config champs OCCTAX" to set.await(),
                    "Datasets créables" to dsCre.await(),
                ).forEach { (nom, err) -> if (err != null) echecs += "$nom ($err)" }
                protocolListIds = mod.await()
            }

            // Étapes 5-7 EN PARALLÈLE : caches indépendants (TaxRef / nomenclatures / monitoring),
            // et seul Suivis charge les modules → pas de course. On masque ainsi la durée des
            // étapes courtes (nomenclatures, Suivis) sous l'étape longue (TaxRef), qui pilote seule
            // le bandeau de progression (sinon les textes des 3 étapes clignoteraient).
            var nbTaxons = 0
            var msgTaxRef = ""
            var nbNom = 0
            var msgNom = ""
            var nbModulesOk = 0
            var msgSuivis = ""
            coroutineScope {
                val taxJob = async {
                    GeoNatureSync.synchroniserTaxRef(config, protocolListIds, forcerTaxRef) { fait, listeIdx, listesTotales ->
                        publier(
                            if (listesTotales == 0) "Récupération des taxons…"
                            else "Liste $listeIdx/$listesTotales — $fait taxons cumulés…"
                        )
                    }
                }
                val nomJob = async { GeoNatureSync.synchroniserNomenclatures(config) }
                val suiviJob = async { MonitoringSync.synchroniserSuivis(config) { _, _, _ -> } }
                taxJob.await().let { (n, m) -> nbTaxons = n; msgTaxRef = m }
                nomJob.await().let { (n, m) -> nbNom = n; msgNom = m }
                suiviJob.await().let { (n, m) -> nbModulesOk = n; msgSuivis = m }
            }
            if (nbTaxons == 0) echecs += "TaxRef (${msgTaxRef.take(80)})"
            if (nbNom == 0) echecs += "Nomenclatures ($msgNom)"
            // "Aucun module monitoring exposé" n'est pas une erreur (instance sans monitoring).
            if (nbModulesOk == 0 && !msgSuivis.startsWith("Aucun")) echecs += "Suivis (${msgSuivis.take(80)})"

            val resume = buildString {
                if (echecs.isNotEmpty()) {
                    append("⚠ Chargement incomplet — étape(s) en échec :\n")
                    echecs.forEach { append("  • $it\n") }
                    append("Vous pouvez relancer « Recharger les données ».\n\n")
                }
                append(msgTaxRef)
                if (nbTaxons > 0 && nbNom == 0) append("\n⚠ Nomenclatures : $msgNom")
                if (nbModulesOk > 0 || msgSuivis.startsWith("Aucun")) append("\nSuivis : $msgSuivis")
            }
            _etat.postValue(Etat(enCours = false, texte = "Terminé", termine = true, succes = echecs.isEmpty(), resume = resume))
        } catch (e: Exception) {
            _etat.postValue(
                Etat(enCours = false, texte = "Échec", termine = true, succes = false,
                    resume = "Synchronisation interrompue : ${e.message ?: e.javaClass.simpleName}")
            )
        } finally {
            enCours.set(false)
        }
    }

    /** Réarme l'état après que le fragment a consommé un résultat terminal — évite de re-traiter
     *  le même « termine » à chaque réabonnement. */
    fun accuserReception() {
        if (_etat.value?.termine == true) _etat.postValue(Etat(enCours = false, texte = ""))
    }
}
