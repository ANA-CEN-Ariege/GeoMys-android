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

import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.TaxRefCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

sealed class TaxRefStatut {
    data class Trouve(val cdNom: Int, val nomScientifique: String, val nomFrancais: String? = null) : TaxRefStatut()
    object NonTrouve : TaxRefStatut()
    object Indisponible : TaxRefStatut()
}

object TaxRefService {

    suspend fun rechercher(nom: String, taxon: Taxon? = null, gnConfig: GeoNatureConfig? = null): Pair<TaxRefStatut, Boolean> =
        withContext(Dispatchers.IO) {
            // Set des cd_nom autorisés pour le groupe sélectionné. Null si pas de groupe
            // demandé OU si l'index par taxon n'a pas (encore) été synchronisé pour ce groupe
            // — dans ce cas on reste permissif pour ne pas bloquer le user qui n'a pas sync.
            val cdNomsAutorises: Set<Int>? = if (taxon != null) {
                TaxRefCache.indexParTaxon(taxon)?.takeIf { it.isNotEmpty() }?.toHashSet()
            } else null
            fun appartientAuGroupe(cd: Int): Boolean = cdNomsAutorises?.contains(cd) ?: true

            // 1. Cache synchronisé depuis le serveur GeoNature (cd_nom autoritatif du serveur)
            //    On valide l'appartenance au groupe avant d'accepter le match — sinon
            //    "Tourterelle turque" matche même quand le user a sélectionné Mammifères.
            TaxRefCache.get(nom)?.let { entry ->
                if (appartientAuGroupe(entry.cdNom)) {
                    val nomFr = entry.nomFrOriginal ?: TaxRefCache.getVernaculaireParCdNom(entry.cdNom)
                    return@withContext Pair(TaxRefStatut.Trouve(entry.cdNom, entry.sciNom, nomFr), false)
                }
            }

            // 2. API TaxRef GeoNature en direct (si configuré) — filtre &regne déjà appliqué
            //    côté URL, on revérifie tout de même contre l'index local pour rester cohérent.
            if (gnConfig != null && gnConfig.connexionConfiguree) {
                rechercherViaGeoNature(nom, taxon, gnConfig)?.let { statut ->
                    if (statut is TaxRefStatut.Trouve && appartientAuGroupe(statut.cdNom)) {
                        TaxRefCache.set(nom, statut.cdNom, statut.nomScientifique, statut.nomFrancais)
                        return@withContext Pair(statut, true)
                    }
                    if (statut !is TaxRefStatut.Trouve) {
                        return@withContext Pair(statut, true)
                    }
                }
            }

            Pair(TaxRefStatut.NonTrouve, false)
        }

    private suspend fun rechercherViaGeoNature(nom: String, taxon: Taxon?, config: GeoNatureConfig): TaxRefStatut? =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val encoded = nom.trim().replace(" ", "%20")
                
                val regneParam = when(taxon) {
                    Taxon.FONGE -> "&regne=Fungi"
                    Taxon.PLANTE -> "&regne=Plantae"
                    Taxon.OISEAU, Taxon.MAMMIFERE, Taxon.REPTILE, Taxon.BATRACIEN,
                    Taxon.POISSON, Taxon.INSECTE, Taxon.MOLLUSQUE, Taxon.INVERTEBRES -> "&regne=Animalia"
                    null -> ""
                }

                val url = URL("$base/api/taxhub/api/taxref/?nom_cite=$encoded&limit=10$regneParam")
                val conn = HttpClient.get(url, timeoutMs = 5000)
                if (conn.responseCode != 200) return@withContext null
                val array = JSONArray(conn.inputStream.bufferedReader().readText())
                val nomNettoye = TaxRefCache.nettoyerSuffixeArticle(nom)
                val nomNorm = TaxRefCache.normaliser(nomNettoye)
                val nomLc = nomNettoye.lowercase()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val cdNom = item.optInt("cd_nom", -1)
                    val lbNom = item.optString("lb_nom", "")
                    if (cdNom <= 0) continue
                    // Correspondance sur nom vernaculaire (peut contenir plusieurs valeurs séparées par virgule).
                    val vernRaw = item.optString("nom_vern", "")
                    val vernMatch = vernRaw.split(",")
                        .map { TaxRefCache.nettoyerSuffixeArticle(it.trim()) }
                        .any { TaxRefCache.normaliser(it) == nomNorm }
                    if (vernMatch) {
                        return@withContext TaxRefStatut.Trouve(cdNom, lbNom, vernRaw.ifEmpty { null })
                    }
                    // Correspondance sur nom scientifique (lb_nom)
                    if (lbNom.lowercase() == nomLc) {
                        return@withContext TaxRefStatut.Trouve(cdNom, lbNom, vernRaw.ifEmpty { null })
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
}