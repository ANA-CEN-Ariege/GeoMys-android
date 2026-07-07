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

import fr.ariegenature.geomys.network.GeoNatureSync.meilleurCdNomPrefListe
import org.junit.Assert.assertEquals
import org.junit.Test

/** Résolution des cd_nom HOMONYMES (même nom, plusieurs entrées nomenclaturales réparties sur
 *  des listes différentes). Cas réel qui a motivé le correctif : « Carex pendula » existe en
 *  cd_nom 88766 (listes OccTax 100 + 108) ET 88767 (liste 108 seule). Le cache indexé par nom
 *  ne garde qu'un cd_nom ; il DOIT garder celui de la liste de saisie configurée, sinon le
 *  taxon — pourtant dans la liste OccTax — devient invisible à la saisie. */
class CollisionCdNomTest {

    // Carex pendula : 88766 ∈ {100, 108}, 88767 ∈ {108}.
    private val listes = mapOf(88766 to setOf(100, 108), 88767 to setOf(108))

    @Test
    fun garde_le_cd_nom_de_la_liste_configuree_quel_que_soit_l_ordre() {
        // Liste OccTax 100 configurée → on garde 88766 (le seul des deux qui y appartient),
        // indépendamment de l'ordre d'arrivée (l'ancien « dernier écrit » gardait 88767).
        assertEquals(88766, meilleurCdNomPrefListe(listOf(88767, 88766), listes, 100))
        assertEquals(88766, meilleurCdNomPrefListe(listOf(88766, 88767), listes, 100))
    }

    @Test
    fun sans_liste_configuree_prend_le_plus_petit_cd_nom() {
        assertEquals(88766, meilleurCdNomPrefListe(listOf(88767, 88766), listes, null))
    }

    @Test
    fun si_les_deux_sont_dans_la_liste_prend_le_plus_petit() {
        // Liste 108 : 88766 ET 88767 y sont → départage par plus petit cd_nom.
        assertEquals(88766, meilleurCdNomPrefListe(listOf(88767, 88766), listes, 108))
    }

    @Test
    fun garde_l_homonyme_de_la_liste_meme_si_ce_n_est_pas_le_plus_petit() {
        // Ici seul le PLUS GRAND cd_nom appartient à la liste configurée : la préférence de
        // liste prime sur le plus-petit-cd_nom.
        val l = mapOf(500 to setOf(9), 600 to setOf(7))
        assertEquals(600, meilleurCdNomPrefListe(listOf(500, 600), l, 7))
    }

    @Test
    fun cd_nom_unique_retourne_ce_cd_nom() {
        assertEquals(42, meilleurCdNomPrefListe(listOf(42), mapOf(42 to setOf(1)), 1))
    }
}
