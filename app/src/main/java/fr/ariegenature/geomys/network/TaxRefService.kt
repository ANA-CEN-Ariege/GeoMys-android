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

import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.TaxRefCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class TaxRefStatut {
    data class Trouve(val cdNom: Int, val nomScientifique: String, val nomFrancais: String? = null) : TaxRefStatut()
    object NonTrouve : TaxRefStatut()

    /** La liste de taxons configurée n'est pas en cache : aucune proposition n'est faite, donc
     *  aucun nom n'est résolvable. Ne devrait pas se produire — le chargement des données est
     *  exigé avant la saisie — mais un cache purgé ou partiel doit le dire au lieu d'accepter
     *  n'importe quel nom du référentiel global. */
    object PasDeDonnees : TaxRefStatut()
}

object TaxRefService {

    /**
     * Essaie les [candidats] de la reconnaissance vocale (hypothèses ASR, dans l'ordre) contre
     * TaxRef, recherche ÉTENDUE activée (mots + approché). Renvoie le premier [TaxRefStatut.Trouve]
     * avec le TEXTE candidat gagnant (pour réafficher/réinjecter dans le champ) ; sinon
     * [TaxRefStatut.NonTrouve] avec le 1ᵉʳ candidat. Sur le chemin VOCAL uniquement.
     */
    suspend fun rechercherParmiCandidats(
        candidats: List<String>,
        taxon: Taxon? = null,
        gnConfig: GeoNatureConfig? = null,
        scientifique: Boolean = false,
    ): Pair<TaxRefStatut, String?> = withContext(Dispatchers.IO) {
        val liste = candidats.map { it.trim() }.filter { it.isNotEmpty() }
        if (liste.isEmpty()) return@withContext Pair(TaxRefStatut.NonTrouve, null)
        // DEUX PASSES : un match EXACT sur n'importe quel candidat prime sur un match APPROCHÉ
        // (Levenshtein) du 1er — sinon « Rouge-gorge » approché sur l'hypothèse n°1 gagnait sur
        // l'hypothèse n°2 exacte (audit 2026-08-27).
        for (cand in liste) {
            val statut = rechercher(cand, taxon, gnConfig, scientifique, avecRechercheEtendue = false)
            if (statut is TaxRefStatut.Trouve) return@withContext Pair(statut, cand)
        }
        for (cand in liste) {
            val statut = rechercher(cand, taxon, gnConfig, scientifique, avecRechercheEtendue = true)
            if (statut is TaxRefStatut.Trouve) return@withContext Pair(statut, cand)
        }
        Pair(TaxRefStatut.NonTrouve, liste.first())
    }

    /**
     * Résolution d'un nom d'espèce — **exclusivement sur le cache local** (décision produit
     * 2026-09-17).
     *
     * L'interrogation de l'API TaxHub a été retirée : elle était lancée à chaque pause de frappe
     * sur un texte que le cache ne résolvait pas, avec 5 s de timeout — donc autant d'attentes
     * sur le terrain en réseau faible — pour un apport devenu nul. Depuis que la saisie n'accepte
     * que les noms PROPOSÉS, et que les propositions sortent du cache, un taxon que l'API sait
     * résoudre mais que le cache ignore ne serait de toute façon pas proposable. Le cache est
     * donc la seule source, et il est complet par construction : recharger les données est exigé
     * avant d'entrer dans les saisies.
     */
    suspend fun rechercher(
        nom: String,
        taxon: Taxon? = null,
        gnConfig: GeoNatureConfig? = null,
        /** Mode d'affichage de l'autocomplétion : il définit l'ensemble des noms PROPOSÉS, donc
         *  celui des noms acceptables. */
        scientifique: Boolean = false,
        /** true (chemin VOCAL) : après échec du match exact, tente une résolution ÉTENDUE sur le
         *  cache local — index par MOTS puis APPROCHÉ. Jamais activé par l'autocomplétion
         *  clavier (perf sur 15-50k entrées, à chaque frappe). */
        avecRechercheEtendue: Boolean = false,
    ): TaxRefStatut =
        withContext(Dispatchers.IO) {
            // Périmètre = groupe sélectionné ET liste de saisie configurée : le même que celui
            // des propositions d'autocomplétion (règle 2026-09-17 — seuls les noms proposés sont
            // acceptés). Liste absente du cache ⇒ [indexParTaxon] rend une liste vide et on
            // reste permissif (groupe seul), comme pour un index de groupe non synchronisé.
            val idListeFiltre = gnConfig?.taxaListeId?.trim()?.toIntOrNull()
            // Liste configurée absente du cache : la saisie ne propose rien, elle ne doit rien
            // accepter non plus (décision produit 2026-09-17).
            if (idListeFiltre != null && TaxRefCache.listeAbsenteDuCache(idListeFiltre)) {
                return@withContext TaxRefStatut.PasDeDonnees
            }
            val cdNomsAutorises: Set<Int>? = if (taxon != null) {
                TaxRefCache.indexParTaxon(taxon, idListeFiltre)?.takeIf { it.isNotEmpty() }?.toHashSet()
                    ?: TaxRefCache.indexParTaxon(taxon)?.takeIf { it.isNotEmpty() }?.toHashSet()
            } else null

            // 1. Résolution dans les PROPOSITIONS du périmètre : mêmes groupe, liste et mode
            // d'affichage que la liste déroulante — seuls les noms proposés sont acceptés.
            if (taxon != null) {
                fr.ariegenature.geomys.TaxRefLocal
                    .resoudreDansPropositions(nom, taxon, scientifique, idListeFiltre)
                    ?.let { s ->
                        val sci = TaxRefCache.entreesParCdNom()[s.cdNom]?.sciNom.orEmpty()
                        return@withContext TaxRefStatut.Trouve(
                            s.cdNom,
                            sci.ifEmpty { s.nom },
                            if (scientifique) s.secondaire else s.nom,
                        )
                    }
            } else {
                // Pas de groupe (chemin vocal générique, tests) : cache entier.
                TaxRefCache.get(nom, cdNomsAutorises)?.let { entry ->
                    val nomFr = entry.nomFrOriginal ?: TaxRefCache.getVernaculaireParCdNom(entry.cdNom)
                    return@withContext TaxRefStatut.Trouve(entry.cdNom, entry.sciNom, nomFr)
                }
            }

            // 2. Recherche ÉTENDUE (chemin vocal) sur le cache local, en dernier recours.
            //    Ordre : index par MOTS (déterministe, sans faux positif) AVANT l'APPROCHÉ
            //    (Levenshtein, seul tier pouvant se tromper). Les deux filtrent par périmètre.
            if (avecRechercheEtendue) {
                // Ces deux niveaux balaient les clés du cache : le taxon retenu doit, lui aussi,
                // faire partie des noms PROPOSÉS — sinon la dictée pourrait enregistrer un taxon
                // que l'écran n'aurait jamais montré.
                val proposes = if (taxon != null)
                    fr.ariegenature.geomys.TaxRefLocal.cdNomsProposes(taxon, scientifique, idListeFiltre)
                else null
                fun accepte(cd: Int) = proposes == null || cd in proposes
                val norm = TaxRefCache.normaliser(TaxRefCache.nettoyerSuffixeArticle(nom))
                TaxRefCache.chercherParMots(norm, cdNomsAutorises)?.takeIf { accepte(it.cdNom) }?.let { e ->
                    val nomFr = e.nomFrOriginal ?: TaxRefCache.getVernaculaireParCdNom(e.cdNom)
                    return@withContext TaxRefStatut.Trouve(e.cdNom, e.sciNom, nomFr)
                }
                TaxRefCache.chercherApproche(norm, cdNomsAutorises)?.takeIf { accepte(it.cdNom) }?.let { e ->
                    val nomFr = e.nomFrOriginal ?: TaxRefCache.getVernaculaireParCdNom(e.cdNom)
                    return@withContext TaxRefStatut.Trouve(e.cdNom, e.sciNom, nomFr)
                }
            }

            TaxRefStatut.NonTrouve
        }
}
