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

package fr.ariegenature.geomys

import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.TaxRefCache

/** Construction des suggestions d'autocomplétion d'espèce à partir du **cache TaxRef synchronisé**
 *  depuis le serveur (plus aucune liste d'espèces codée en dur). Renvoie une liste vide tant que
 *  le cache n'a pas été chargé, et reste strictement limité à la liste sélectionnée le cas échéant. */
object TaxRefLocal {

    fun getSuggestionsAutocomplete(
        taxon: Taxon?,
        scientifique: Boolean,
        idListeFiltre: Int? = null,
    ): List<String> {
        val groupes2 = TaxRefCache.tousLesGroupes()
        val groupes1 = TaxRefCache.tousLesGroupes1()
        val regnes   = TaxRefCache.tousLesRegnes()

        // Avec une entrée par clé (alignement iOS), les noms français pour un cd_nom donné
        // sont dispersés sur plusieurs clés du cache : on passe par les helpers cdNom →
        // (sciNom, listeNomsFr) pour reconstruire les suggestions sans dupliquer le scan.
        val parCdNom = TaxRefCache.entreesParCdNom()
        val vernsParCdNom = TaxRefCache.vernsParCdNom()
        // Cache du set d'appartenance à la liste configurée (vide si pas de filtre).
        // Tous les `suggestionsPour` filtreront via cet ensemble — c'est ce qui rend
        // l'autocomplete fidèle à la liste sélectionnée même avec un cache exhaustif.
        val cdNomsDansListe: Set<Int>? = idListeFiltre?.let { TaxRefCache.cdNomsDansListe(it) }

        fun suggestionsPour(cdNomsBrut: Collection<Int>): List<String> {
            val cdNoms = if (cdNomsDansListe == null) cdNomsBrut
                         else cdNomsBrut.filter { it in cdNomsDansListe }
            if (scientifique) {
                return cdNoms.asSequence()
                    .mapNotNull { parCdNom[it]?.sciNom?.takeIf { s -> s.isNotEmpty() } }
                    .distinct().sorted().toList()
            }
            // Vernaculaire : UNIQUEMENT les noms français. Pas de repli sur le nom scientifique —
            // le mode français ne doit proposer que des noms français (décision produit). Un taxon
            // sans nom vernaculaire n'apparaît donc pas ici : il faut activer « Noms scientifiques »
            // pour le trouver. (Avant : repli sciNom, qui polluait le mode français sur les
            // référentiels à faible couverture vernaculaire — cf. liste « Saisie Occtax » CEN PdL.)
            val result = LinkedHashSet<String>()
            for (cd in cdNoms) {
                vernsParCdNom[cd]?.let { result.addAll(it) }
            }
            return result.sorted()
        }

        // Fast-path : index pré-calculé Taxon → cdNoms (construit lors de la synchro).
        if (taxon != null) {
            val cdNoms = TaxRefCache.indexParTaxon(taxon)
            if (!cdNoms.isNullOrEmpty()) {
                val res = suggestionsPour(cdNoms)
                if (res.isNotEmpty()) return res
            }
        }

        fun filtrerParGroup2(ensemble: Set<String>): List<String> {
            val cdNoms = HashSet<Int>()
            for ((cdStr, g2) in groupes2) if (g2 in ensemble) cdStr.toIntOrNull()?.let(cdNoms::add)
            return suggestionsPour(cdNoms)
        }

        // Cache de groupes non disponible (pas encore synchronisé) → aucune suggestion :
        // l'autocomplétion repose entièrement sur le cache TaxRef téléchargé du serveur.
        if (groupes2.isEmpty()) return emptyList()

        return when (taxon) {
            // Flore : group2_inpn botanique (Angiospermes, Trachéophytes, Mousses, Lichens…) —
            // critère principal, identique iOS. Complété par group1_inpn (Phanérogames,
            // Ptéridophytes, Bryophytes) et regne=Plantae quand ils sont disponibles.
            Taxon.PLANTE -> {
                val cdNoms = HashSet<Int>()
                for ((cdStr, g2) in groupes2) if (g2 in NomenclatureCache.GROUPES_BOTANIQUES) cdStr.toIntOrNull()?.let(cdNoms::add)
                for ((cdStr, g1) in groupes1) if (g1 in NomenclatureCache.GROUPES1_FLORE) cdStr.toIntOrNull()?.let(cdNoms::add)
                for ((cdStr, r) in regnes) if (r == "Plantae") cdStr.toIntOrNull()?.let(cdNoms::add)
                if (cdNoms.isNotEmpty()) return suggestionsPour(cdNoms)
                filtrerParGroup2(NomenclatureCache.groupesBotaniquesConnus())
            }

            // Insectes : group2_inpn = 'Insectes'
            Taxon.INSECTE -> filtrerParGroup2(setOf("Insectes"))

            // Fonge : règne = 'Fungi'
            Taxon.FONGE -> {
                if (regnes.isNotEmpty()) {
                    val cdNoms = HashSet<Int>()
                    for ((cdStr, r) in regnes) if (r == "Fungi") cdStr.toIntOrNull()?.let(cdNoms::add)
                    if (cdNoms.isNotEmpty()) return suggestionsPour(cdNoms)
                }
                filtrerParGroup2(NomenclatureCache.GROUPES_FONGE)
            }

            // Poissons : group2_inpn = 'Poissons' (priorité), fallback group1 ou ensemble v16/v17
            Taxon.POISSON -> {
                val parG2 = filtrerParGroup2(setOf("Poissons"))
                if (parG2.isNotEmpty()) return parG2
                if (groupes1.isNotEmpty()) {
                    val cdNoms = HashSet<Int>()
                    for ((cdStr, g1) in groupes1) if (g1 == "Poissons") cdStr.toIntOrNull()?.let(cdNoms::add)
                    if (cdNoms.isNotEmpty()) return suggestionsPour(cdNoms)
                }
                filtrerParGroup2(NomenclatureCache.GROUP2_POISSONS)
            }

            // Mollusques : group1_inpn = 'Mollusques'
            Taxon.MOLLUSQUE -> {
                val cdNoms = HashSet<Int>()
                for ((cdStr, g1) in groupes1) if (g1 == "Mollusques") cdStr.toIntOrNull()?.let(cdNoms::add)
                suggestionsPour(cdNoms)
            }

            // Autres invertébrés : règne = 'Animalia' AND group2 NOT IN vertébrés + insectes + poissons
            //                     AND group1 != 'Mollusques'
            Taxon.INVERTEBRES -> {
                val exclusG2 = setOf("Oiseaux", "Mammifères", "Reptiles", "Amphibiens", "Insectes", "Poissons")
                val cdNoms = HashSet<Int>()
                if (regnes.isNotEmpty()) {
                    for ((cdStr, r) in regnes) {
                        if (r != "Animalia") continue
                        val g2 = groupes2[cdStr] ?: ""
                        if (g2 in exclusG2) continue
                        if ((groupes1[cdStr] ?: "") == "Mollusques") continue
                        cdStr.toIntOrNull()?.let(cdNoms::add)
                    }
                } else {
                    val exclusFallback = exclusG2 + NomenclatureCache.GROUP2_POISSONS +
                        NomenclatureCache.GROUPES_BOTANIQUES + NomenclatureCache.GROUPES_FONGE
                    for ((cdStr, g2) in groupes2) {
                        if (g2 in exclusFallback) continue
                        if ((groupes1[cdStr] ?: "") == "Mollusques") continue
                        cdStr.toIntOrNull()?.let(cdNoms::add)
                    }
                }
                suggestionsPour(cdNoms)
            }

            else -> {
                val groupeCible = when (taxon) {
                    Taxon.MAMMIFERE -> "Mammifères"
                    Taxon.REPTILE   -> "Reptiles"
                    Taxon.BATRACIEN -> "Amphibiens"
                    else            -> "Oiseaux"
                }
                filtrerParGroup2(setOf(groupeCible))
            }
        }
    }
}
