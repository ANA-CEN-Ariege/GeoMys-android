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

import fr.ariegenature.geomys.network.GeoNatureSync.meilleurCandidatVernaculaire
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

    // ── Collision de noms VERNACULAIRES (terrain 2026-09-16) ──

    /** Cas réel, relevé sur l'appareil : « gobemouche gris » est le nom PRINCIPAL de
     *  Muscicapa striata (4319, l'oiseau) et le QUATRIÈME nom de Menemerus bivittatus (2080, une
     *  araignée sauteuse : « Atte muscivore », « Araignéechat », « araignée sauteuse à deux
     *  bandes », « gobemouche gris »). Les deux appartiennent à la liste 100, donc la préférence
     *  de liste ne les départage pas — et le « plus petit cd_nom » donnait l'ARAIGNÉE. Saisir
     *  « gobemouche gris » affichait « Menemerus bivittatus » et envoyait ce cd_nom à GeoNature. */
    private val gobemouche = listOf(
        Triple(2080, "gobemouche gris", 3),   // araignée : 4e nom vernaculaire
        Triple(4319, "Gobemouche gris", 0),   // oiseau : nom principal
    )
    private val listesGobemouche = mapOf(2080 to setOf(100), 4319 to setOf(100))

    @Test
    fun le_nom_principal_l_emporte_sur_un_synonyme_secondaire() {
        val best = meilleurCandidatVernaculaire(gobemouche, listesGobemouche, 100)
        assertEquals("« gobemouche gris » doit désigner l'oiseau, pas l'araignée", 4319, best?.first)
    }

    @Test
    fun le_rang_departage_aussi_sans_liste_configuree() {
        val best = meilleurCandidatVernaculaire(gobemouche, listesGobemouche, null)
        assertEquals(4319, best?.first)
    }

    @Test
    fun la_liste_configuree_prime_toujours_sur_le_rang() {
        // Le protocole de l'utilisateur reste le critère le plus fort : si seul le taxon dont le
        // nom est secondaire appartient à sa liste, c'est lui qu'il faut proposer.
        val listes = mapOf(2080 to setOf(100), 4319 to setOf(999))
        val best = meilleurCandidatVernaculaire(gobemouche, listes, 100)
        assertEquals(2080, best?.first)
    }

    @Test
    fun a_rang_egal_le_plus_petit_cd_nom_departage() {
        // Espèce et sous-espèce partageant leur nom principal : on reste déterministe.
        val cands = listOf(Triple(600, "Mésange bleue", 0), Triple(500, "Mésange bleue", 0))
        assertEquals(500, meilleurCandidatVernaculaire(cands, emptyMap(), null)?.first)
    }
}
