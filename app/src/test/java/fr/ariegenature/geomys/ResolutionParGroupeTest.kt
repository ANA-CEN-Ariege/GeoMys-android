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

import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.TaxRefEntry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RÉSOLUTION D'UN NOM RESTREINTE AU GROUPE DE TAXONS (terrain 2026-09-16).
 *
 * Cas réel relevé sur l'appareil : « gobemouche gris » est le nom usuel de *Muscicapa striata*
 * (cd_nom 4319, l'oiseau) ET le quatrième nom vernaculaire de *Menemerus bivittatus* (cd_nom 2080,
 * une araignée sauteuse, après « Atte muscivore », « Araignéechat » et « araignée sauteuse à deux
 * bandes »). Le cache principal étant indexé par nom et ne gardant qu'UNE entrée par clé, celle-ci
 * pointait sur l'araignée : dans le groupe OISEAUX, saisir « gobemouche gris » affichait
 * « Menemerus bivittatus » et attachait ce cd_nom à l'observation, qui partait ainsi à GeoNature.
 *
 * Filtrer par groupe ne suffisait pas — un filtre ne sait que REJETER l'intrus, jamais RETROUVER le
 * bon. La résolution consulte donc désormais l'index vernaculaire pour trouver, parmi les cd_nom du
 * groupe, celui qui porte ce nom. Le correctif agit à la LECTURE : il répare les caches déjà
 * présents sur les appareils, sans rechargement des données.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolutionParGroupeTest {

    private val oiseaux = setOf(4319)
    private val invertebres = setOf(2080)

    @Before
    fun setup() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        // Le cache tel qu'il est réellement sur l'appareil : la clé « gobemouche gris » a été
        // gagnée par l'araignée (plus petit cd_nom, les deux étant dans la même liste).
        TaxRefCache.remplacerTout(
            mapOf(
                TaxRefCache.normaliser("gobemouche gris") to
                    TaxRefEntry(2080, "Menemerus bivittatus", "gobemouche gris"),
                TaxRefCache.normaliser("Atte muscivore") to
                    TaxRefEntry(2080, "Menemerus bivittatus", "Atte muscivore"),
                // L'oiseau a PERDU la clé vernaculaire, mais il garde sa clé SCIENTIFIQUE : c'est
                // elle qui permet de le retrouver. Tout taxon d'une liste synchronisée en a une
                // (GeoNatureSync pose les clés scientifiques avant les vernaculaires).
                TaxRefCache.normaliser("Muscicapa striata") to
                    TaxRefEntry(4319, "Muscicapa striata", null),
            )
        )
        TaxRefCache.ajouterVerns(
            mapOf(
                2080 to listOf("Atte muscivore", "Araignéechat", "araignée sauteuse à deux bandes", "gobemouche gris"),
                4319 to listOf("Gobemouche gris"),
            )
        )
    }

    @Test
    fun dans_le_groupe_OISEAUX_gobemouche_gris_designe_l_oiseau() {
        val e = TaxRefCache.get("gobemouche gris", oiseaux)
        assertEquals("c'est l'oiseau qu'il faut résoudre, pas l'araignée", 4319, e?.cdNom)
        assertEquals("Muscicapa striata", e?.sciNom)
    }

    @Test
    fun le_nom_affiche_reste_celui_que_l_utilisateur_a_choisi() {
        // Il a tapé « gobemouche gris » : il doit relire « Gobemouche gris », pas un autre nom
        // vernaculaire du taxon retrouvé.
        val e = TaxRefCache.get("gobemouche gris", oiseaux)
        assertEquals("Gobemouche gris", e?.nomFrOriginal)
    }

    @Test
    fun dans_le_groupe_INVERTEBRES_le_meme_nom_designe_l_araignee() {
        // Contre-épreuve : la résolution suit le groupe, elle ne privilégie pas les vertébrés.
        val e = TaxRefCache.get("gobemouche gris", invertebres)
        assertEquals(2080, e?.cdNom)
    }

    @Test
    fun sans_groupe_le_comportement_global_est_inchange() {
        // Les appels existants (get(nom) sans groupe) ne changent pas de sémantique.
        assertEquals(2080, TaxRefCache.get("gobemouche gris")?.cdNom)
        assertEquals(2080, TaxRefCache.get("gobemouche gris", null)?.cdNom)
    }

    @Test
    fun quand_deux_taxons_du_groupe_portent_le_nom_le_rang_departage() {
        // Audit 2026-09-17 (C4) : le repli rendait « le premier du Set », donc l'ordre de hachage
        // d'un HashSet décidait de la détermination. Départage explicite désormais : le nom
        // SCIENTIFIQUE d'abord, puis le RANG du nom vernaculaire (TaxRef énumère nom_vern par
        // ordre de préférence), puis le plus petit cd_nom.
        // L'intrus doit avoir une entrée dans le cache principal, sinon le repli le saute
        // (`cd !in parCdNom`) et le test ne prouverait rien.
        TaxRefCache.set("Fictivus testus", 999, "Fictivus testus", null)
        TaxRefCache.ajouterVerns(
            mapOf(
                2080 to listOf("Atte muscivore", "gobemouche gris"),
                4319 to listOf("Gobemouche gris"),
                // Intrus du même groupe qui ne porte ce nom qu'en 4ᵉ position : il ne doit PAS
                // l'emporter sur le taxon dont c'est LE nom usuel, quel que soit son cd_nom.
                999 to listOf("a", "b", "c", "Gobemouche gris"),
            )
        )
        val e = TaxRefCache.get("gobemouche gris", setOf(4319, 999))
        assertEquals(4319, e?.cdNom)
        // Et l'ordre de construction du Set n'y change rien.
        assertEquals(4319, TaxRefCache.get("gobemouche gris", setOf(999, 4319))?.cdNom)
    }

    @Test
    fun un_nom_absent_du_groupe_rend_l_entree_globale_a_charge_de_l_appelant_de_la_rejeter() {
        // « Atte muscivore » n'existe que chez l'araignée : dans le groupe OISEAUX, la résolution
        // n'invente rien — elle rend l'entrée globale, que TaxRefService rejettera via son filtre.
        assertEquals(2080, TaxRefCache.get("Atte muscivore", oiseaux)?.cdNom)
    }
}
