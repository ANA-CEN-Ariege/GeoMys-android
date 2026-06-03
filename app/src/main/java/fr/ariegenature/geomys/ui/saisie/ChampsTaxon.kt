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

/** Logique d'affichage des champs de caractérisation/dénombrement selon le taxon, factorisée
 *  depuis CaracterisationFragment / DenombrementFragment / ObservationDetailsFragment (qui
 *  en avaient chacun une copie). Logique pure (testable hors device) : le filtrage des
 *  valeurs de nomenclature se fait ensuite via [NomenclatureCache.filtrerPourGroupes]. */
object ChampsTaxon {

    /** Champs de caractérisation de l'OCCURRENCE visibles pour ce taxon (écran multi-taxons,
     *  CaracterisationFragment). N'inclut PAS les champs de dénombrement (sexe, stade, OBJ/TYP). */
    fun champsCaracterisation(taxon: Taxon): Set<String> = when (taxon) {
        Taxon.OISEAU    -> setOf("METH_OBS", "STATUT_BIO", "ETA_BIO", "PREUVE_EXIST", "OCC_COMPORTEMENT", "METH_DETERMIN")
        Taxon.MAMMIFERE -> setOf("METH_OBS", "ETA_BIO", "PREUVE_EXIST", "OCC_COMPORTEMENT", "METH_DETERMIN")
        Taxon.REPTILE,
        Taxon.BATRACIEN,
        Taxon.POISSON,
        Taxon.INSECTE,
        Taxon.MOLLUSQUE,
        Taxon.INVERTEBRES -> setOf("METH_OBS", "ETA_BIO", "PREUVE_EXIST", "METH_DETERMIN")
        Taxon.FONGE       -> setOf("METH_OBS", "PREUVE_EXIST", "METH_DETERMIN")
        Taxon.PLANTE      -> setOf("METH_OBS", "PREUVE_EXIST", "METH_DETERMIN")
    }

    /** Champs visibles sur l'écran de détails mono-taxon legacy (ObservationDetailsFragment) :
     *  caractérisation + dénombrement (sexe, stade de vie, OBJ_DENBR, TYP_DENBR). */
    fun champsObservationDetails(taxon: Taxon): Set<String> = when (taxon) {
        Taxon.OISEAU    -> setOf("METH_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
                                 "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN")
        Taxon.MAMMIFERE -> setOf("METH_OBS", "SEXE", "STADE_VIE", "ETA_BIO",
                                 "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN")
        Taxon.REPTILE,
        Taxon.BATRACIEN,
        Taxon.POISSON,
        Taxon.INSECTE,
        Taxon.MOLLUSQUE,
        Taxon.INVERTEBRES -> setOf("METH_OBS", "SEXE", "STADE_VIE", "ETA_BIO",
                                   "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "METH_DETERMIN")
        Taxon.FONGE       -> setOf("METH_OBS", "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "METH_DETERMIN")
        Taxon.PLANTE      -> setOf("METH_OBS", "STADE_VIE", "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "METH_DETERMIN")
    }

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
