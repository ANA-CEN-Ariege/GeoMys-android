/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.network

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.MonitoringCache
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

/** Orchestration du « Recharger les données » découplée de toute UI, pour pouvoir tourner dans
 *  un [fr.ariegenature.geonat.sync.SyncForegroundService] (= service au premier plan) et donc
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
    suspend fun executer(context: Context) {
        // Garde atomique : un seul executer() à la fois, même appelé concurremment.
        if (!enCours.compareAndSet(false, true)) return
        try {
            val config = GeoNatureConfig(context.applicationContext)
            publier("Préparation…")

            // Ardoise propre côté local avant de recharger (parité avec l'ancien
            // viderTousLesCaches() en tête de chargerToutesLesDonnees).
            TaxRefCache.vider()
            NomenclatureCache.vider()
            MonitoringCache.vider()
            MonitoringApi.invaliderCaches()

            val echecs = mutableListOf<String>()

            // Étapes 1-4 en parallèle (indépendantes, rapides). Chaque lambda écrit son cache
            // JSON et renvoie un message d'échec ou null.
            publier("Jeux de données, listes, observateurs…")
            coroutineScope {
                val ds = async {
                    try {
                        val r = GeoNatureBrowse.chargerDatasets(config)
                        if (r.isNotEmpty()) { config.datasetsCacheJson = gson.toJson(r); null }
                        else "Aucun jeu de données"
                    } catch (e: Exception) { e.message ?: "Erreur datasets" }
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
                listOf(
                    "Jeux de données" to ds.await(),
                    "Listes de taxons" to li.await(),
                    "Observateurs" to obs.await(),
                    "Champs additionnels" to add.await(),
                ).forEach { (nom, err) -> if (err != null) echecs += "$nom ($err)" }
            }

            // Étape 5 : TaxRef exhaustif (toutes les biblistes).
            val (nbTaxons, msgTaxRef) = GeoNatureSync.synchroniserTaxRef(config) { fait, listeIdx, listesTotales ->
                publier(
                    if (listesTotales == 0) "Récupération des listes de taxons…"
                    else "Liste $listeIdx/$listesTotales — $fait taxons cumulés…"
                )
            }
            if (nbTaxons == 0) echecs += "TaxRef (${msgTaxRef.take(80)})"

            // Étape 6 : nomenclatures + defaults par module.
            publier("Nomenclatures…")
            val (nbNom, msgNom) = GeoNatureSync.synchroniserNomenclatures(config)
            if (nbNom == 0) echecs += "Nomenclatures ($msgNom)"

            // Étape 7 : pré-chargement module Suivis (best-effort).
            val (nbModulesOk, msgSuivis) = MonitoringSync.synchroniserSuivis(config) { moduleIdx, modulesTotaux, objets ->
                publier(
                    if (modulesTotaux == 0) "Récupération des protocoles Suivis…"
                    else "Suivis : protocole $moduleIdx/$modulesTotaux — $objets objets en cache…"
                )
            }
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
