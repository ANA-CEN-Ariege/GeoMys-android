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

package fr.ariegenature.geomys.ui.saisie

import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.NomenclatureCache

/** Contexte taxonomique pour le filtrage des VALEURS de nomenclature (groupe2_inpn + regno),
 *  factorisé depuis les écrans de saisie. Logique pure (testable hors device) : le filtrage des
 *  valeurs se fait ensuite via [NomenclatureCache.filtrerPourGroupes].
 *
 *  NB : la VISIBILITÉ des champs n'est plus décidée ici (elle l'était par taxon, en dur) — elle
 *  est désormais pilotée par la config serveur via [fr.ariegenature.geomys.store.OcctaxFieldsConfig].
 *  Seul le filtrage par taxon des valeurs reste pertinent et vit ici. */
object ChampsTaxon {

    /** Groupes de filtrage (group2_inpn) + regno (Plantae/Fungi/Animalia) pour restreindre les
     *  nomenclatures proposées au taxon. Pour Plantes : union des groupes botaniques présents
     *  dans le cache TaxRef (fallback : tous). Pour les autres : groupe exact depuis l'argument
     *  `groupe2Inpn`, ou fallback par taxon. */
    fun groupesEtRegno(taxon: Taxon, groupe2Inpn: String): Pair<Set<String>, String> = when (taxon) {
        Taxon.PLANTE -> Pair(NomenclatureCache.groupesBotaniquesConnus(), "Plantae")
        Taxon.FONGE  -> Pair(NomenclatureCache.GROUPES_FONGE, "Fungi")
        Taxon.MOLLUSQUE, Taxon.INVERTEBRES -> Pair(setOf("Animalia"), "Animalia")
        else -> {
            val g = groupe2Inpn.ifEmpty {
                when (taxon) {
                    Taxon.OISEAU    -> "Oiseaux"
                    Taxon.MAMMIFERE -> "Mammifères"
                    Taxon.REPTILE   -> "Reptiles"
                    Taxon.BATRACIEN -> "Amphibiens"
                    Taxon.POISSON   -> "Poissons"
                    Taxon.INSECTE   -> "Insectes"
                    else            -> ""
                }
            }
            Pair(setOf(g), NomenclatureCache.regno(pourGroupe = g))
        }
    }
}
