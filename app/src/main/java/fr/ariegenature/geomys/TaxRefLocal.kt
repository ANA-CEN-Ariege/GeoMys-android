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
import fr.ariegenature.geomys.store.SuggestionTaxon
import fr.ariegenature.geomys.store.TaxRefCache

/** Construction des suggestions d'autocomplétion d'espèce à partir du **cache TaxRef synchronisé**
 *  depuis le serveur (plus aucune liste d'espèces codée en dur). Renvoie une liste vide tant que
 *  le cache n'a pas été chargé, et reste strictement limité à la liste sélectionnée le cas échéant. */
object TaxRefLocal {

    /** Propositions d'un périmètre de saisie (groupe + liste + mode d'affichage) : la LISTE
     *  telle qu'elle s'affiche, et l'INDEX par nom normalisé qui sert à la résolution. Les deux
     *  sortent du même calcul, donc proposé ⇔ accepté (règle produit 2026-09-17). */
    private class Propositions(val liste: List<SuggestionTaxon>) {
        /** (clé normalisée, proposition), calculé UNE fois : l'écran s'en sert pour filtrer la
         *  liste déroulante, la résolution pour retrouver le taxon. Normaliser 46 000 noms deux
         *  fois — une fois ici, une fois dans l'écran — était l'essentiel du temps de bascule
         *  vers le mode « noms scientifiques » (terrain 2026-09-17). */
        val normalisees: List<Pair<String, SuggestionTaxon>> =
            liste.map { TaxRefCache.normaliser(TaxRefCache.nettoyerSuffixeArticle(it.nom)) to it }

        val index: Map<String, SuggestionTaxon> = LinkedHashMap<String, SuggestionTaxon>().apply {
            // La liste est déjà ordonnée « nom usuel d'abord » : le premier arrivé gagne la clé,
            // donc taper un nom partagé par deux taxons désigne le même que la 1ʳᵉ ligne.
            for ((cle, s) in normalisees) putIfAbsent(cle, s)
        }
    }

    /** (groupe, mode, liste, version du cache) → propositions. Les DERNIÈRES configurations sont
     *  gardées — basculer « noms scientifiques » ou revenir au groupe précédent est alors
     *  instantané, là où un mémo à une seule entrée refaisait tout le calcul à chaque aller-retour.
     *  Le numéro de version du cache est dans la clé : une synchro ou un vidage les périme. */
    private val memPropositions = object : LinkedHashMap<List<Any?>, Propositions>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, Propositions>) =
            size > 4
    }

    private fun propositions(taxon: Taxon?, scientifique: Boolean, idListeFiltre: Int?): Propositions {
        val cle = listOf(taxon?.name, scientifique, idListeFiltre, TaxRefCache.versionDonnees)
        // Verrou : l'écran (reconstruction de la liste) et la frappe (résolution) demandent la
        // même configuration en parallèle — sans lui, le calcul serait fait deux fois.
        synchronized(memPropositions) {
            memPropositions[cle]?.let { return it }
            return Propositions(calculerSuggestions(taxon, scientifique, idListeFiltre))
                .also { memPropositions[cle] = it }
        }
    }

    /**
     * Résout un nom SAISI dans le périmètre exact des propositions — groupe, liste configurée ET
     * mode d'affichage.
     *
     * Le mode compte (terrain 2026-09-17) : en « noms français », les propositions ne contiennent
     * que des noms vernaculaires, alors que la résolution acceptait aussi les noms scientifiques
     * du cache. Sur ce référentiel, 42 256 insectes sur 46 382 n'ont aucun nom français : taper
     * « Pieris » était accepté (cd_nom 196270) sans avoir jamais été proposé. Les deux ensembles
     * sont désormais le même.
     */
    fun resoudreDansPropositions(
        nom: String,
        taxon: Taxon?,
        scientifique: Boolean,
        idListeFiltre: Int?,
    ): SuggestionTaxon? {
        val index = propositions(taxon, scientifique, idListeFiltre).index
        for (cle in TaxRefCache.variantesCle(nom)) index[cle]?.let { return it }
        return null
    }

    /** Propositions d'un périmètre AVEC leurs clés de recherche déjà normalisées — à donner tel
     *  quel à l'adapter d'autocomplétion, qui n'a donc plus rien à recalculer. */
    fun getPropositionsNormalisees(
        taxon: Taxon?,
        scientifique: Boolean,
        idListeFiltre: Int? = null,
    ): Pair<List<SuggestionTaxon>, List<Pair<String, SuggestionTaxon>>> =
        propositions(taxon, scientifique, idListeFiltre).let { it.liste to it.normalisees }

    /** Les cd_nom effectivement PROPOSÉS dans ce périmètre — un taxon du groupe dont aucun nom
     *  n'est proposé (pas de nom français en mode français) n'en fait pas partie. */
    fun cdNomsProposes(taxon: Taxon?, scientifique: Boolean, idListeFiltre: Int?): Set<Int> =
        propositions(taxon, scientifique, idListeFiltre).liste.mapTo(HashSet()) { it.cdNom }

    /** Noms seuls — pour les appelants qui n'ont pas besoin du cd_nom. */
    fun getSuggestionsAutocomplete(
        taxon: Taxon?,
        scientifique: Boolean,
        idListeFiltre: Int? = null,
    ): List<String> = getSuggestionsTaxon(taxon, scientifique, idListeFiltre).map { it.nom }

    fun getSuggestionsTaxon(
        taxon: Taxon?,
        scientifique: Boolean,
        idListeFiltre: Int? = null,
    ): List<SuggestionTaxon> = propositions(taxon, scientifique, idListeFiltre).liste

    private fun calculerSuggestions(
        taxon: Taxon?,
        scientifique: Boolean,
        idListeFiltre: Int? = null,
    ): List<SuggestionTaxon> {
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

        fun suggestionsPour(cdNomsBrut: Collection<Int>): List<SuggestionTaxon> {
            val cdNoms = if (cdNomsDansListe == null) cdNomsBrut
                         else cdNomsBrut.filter { it in cdNomsDansListe }
            if (scientifique) {
                // Une ligne par TAXON, pas par nom : deux taxons homonymes (un genre d'insecte
                // et un genre de plante portent « Solenopsis ») sont deux propositions
                // distinctes, départagées à l'œil par le nom français affiché en dessous.
                return cdNoms.asSequence()
                    .mapNotNull { cd ->
                        val s = parCdNom[cd]?.sciNom?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        SuggestionTaxon(s, cd, vernsParCdNom[cd]?.firstOrNull())
                    }
                    .distinct()
                    .sortedWith(compareBy({ it.nom }, { it.cdNom }))
                    .toList()
            }
            // Vernaculaire : UNIQUEMENT les noms français. Pas de repli sur le nom scientifique —
            // le mode français ne doit proposer que des noms français (décision produit). Un taxon
            // sans nom vernaculaire n'apparaît donc pas ici : il faut activer « Noms scientifiques »
            // pour le trouver. (Avant : repli sciNom, qui polluait le mode français sur les
            // référentiels à faible couverture vernaculaire — cf. liste « Saisie Occtax » CEN PdL.)
            //
            // Un nom porté par PLUSIEURS taxons du périmètre donne PLUSIEURS propositions — une
            // par taxon — au lieu d'une seule tranchée par une heuristique : le nom scientifique
            // affiché sous le nom français rend le choix explicite (« Bousier rhinocéros /
            // Copris lunaris » et « Bousier rhinocéros / Odonteus armiger »). Les lignes d'un
            // même nom sont ordonnées par usage : rang du nom dans sa liste vernaculaire (ordre
            // de préférence TaxRef), puis plus petit cd_nom — le nom usuel d'abord.
            val lignes = ArrayList<Triple<String, Int, Int>>() // (nom, rang, cd_nom)
            for (cd in cdNoms) {
                vernsParCdNom[cd]?.forEachIndexed { rang, nom -> lignes.add(Triple(nom, rang, cd)) }
            }
            return lignes.asSequence()
                .distinctBy { it.first to it.third }
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
                .map { (nom, _, cd) -> SuggestionTaxon(nom, cd, parCdNom[cd]?.sciNom) }
                .toList()
        }

        // Fast-path : index pré-calculé Taxon → cdNoms (construit lors de la synchro).
        if (taxon != null) {
            val cdNoms = TaxRefCache.indexParTaxon(taxon)
            if (!cdNoms.isNullOrEmpty()) {
                val res = suggestionsPour(cdNoms)
                if (res.isNotEmpty()) return res
            }
        }

        fun filtrerParGroup2(ensemble: Set<String>): List<SuggestionTaxon> {
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
