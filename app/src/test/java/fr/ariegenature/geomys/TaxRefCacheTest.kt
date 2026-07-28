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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cache TaxRef : appartenance d'un cd_nom aux listes UsersHub (alimente la visibilité des
 *  champs additionnels par taxon) + comptes par groupe (alimente la branche PLANTE). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxRefCacheTest {

    @Before
    fun setup() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
    }

    @Test
    fun listes_pour_cd_nom_roundtrip() {
        TaxRefCache.ajouterListesParCdNom(mapOf(60585 to listOf(100, 200), 4001 to listOf(100)))
        assertEquals(listOf(100, 200), TaxRefCache.listesPourCdNom(60585))
        assertEquals(listOf(100), TaxRefCache.listesPourCdNom(4001))
    }

    @Test
    fun cd_nom_inconnu_renvoie_liste_vide() {
        assertTrue(TaxRefCache.listesPourCdNom(999999).isEmpty())
    }

    @Test
    fun cd_noms_dans_liste_inverse_l_index() {
        TaxRefCache.ajouterListesParCdNom(mapOf(1 to listOf(100), 2 to listOf(100, 200), 3 to listOf(200)))
        assertEquals(setOf(1, 2), TaxRefCache.cdNomsDansListe(100))
        assertEquals(setOf(2, 3), TaxRefCache.cdNomsDansListe(200))
        assertTrue(TaxRefCache.cdNomsDansListe(999).isEmpty())
    }

    @Test
    fun comptes_groupes_roundtrip() {
        TaxRefCache.comptesGroupes = mapOf("Oiseaux" to 320, "Angiospermes" to 1500)
        assertEquals(320, TaxRefCache.comptesGroupes["Oiseaux"])
        assertEquals(1500, TaxRefCache.comptesGroupes["Angiospermes"])
    }

    @Test
    fun vider_remet_a_zero() {
        TaxRefCache.ajouterListesParCdNom(mapOf(1 to listOf(100)))
        TaxRefCache.comptesGroupes = mapOf("X" to 1)
        TaxRefCache.vider()
        assertTrue(TaxRefCache.listesPourCdNom(1).isEmpty())
        assertTrue(TaxRefCache.comptesGroupes.isEmpty())
    }

    // ── Recherche vocale (niveaux 1b / 3 / 2) ─────────────────────────────────────────────────
    // Les clés du cache sont stockées NORMALISÉES au sync — on reproduit ça via normaliser().

    private fun peupler(vararg noms: Pair<String, Int>) {
        TaxRefCache.remplacerTout(noms.associate { (nom, cd) ->
            TaxRefCache.normaliser(nom) to TaxRefEntry(cd, nom, nom)
        })
    }

    @Test
    fun get_tolere_tiret_espace_et_espaces_multiples() {
        peupler("Pique-prune" to 5000) // séparateur UNIQUE (un tiret)
        // Clé stockée avec tiret ; requêtes vocales avec espace / espaces multiples / tiret.
        assertEquals(5000, TaxRefCache.get("pique prune")?.cdNom)
        assertEquals(5000, TaxRefCache.get("pique  prune")?.cdNom)
        assertEquals(5000, TaxRefCache.get("Pique-prune")?.cdNom)
    }

    @Test
    fun mots_tolere_separateurs_mixtes() {
        peupler("Grand-duc d'Europe" to 3000)
        // Séparateurs MIXTES (tiret + espace) : hors du ressort de get(), résolu par l'index mots.
        assertEquals(3000, TaxRefCache.chercherParMots(TaxRefCache.normaliser("grand duc d'europe"))?.cdNom)
    }

    @Test
    fun get_tolere_concatenation_des_separateurs() {
        peupler("Rougegorge" to 4001) // nom INPN en un mot
        assertEquals(4001, TaxRefCache.get("rouge gorge")?.cdNom)
    }

    @Test
    fun mots_matche_nom_en_un_mot_depuis_deux_mots_dictes() {
        peupler("Rougegorge familier" to 4001, "Mésange bleue" to 4002)
        // « rouge gorge » (ASR, 2 mots) → « Rougegorge familier » (index par mots + concat).
        assertEquals(4001, TaxRefCache.chercherParMots(TaxRefCache.normaliser("rouge gorge"))?.cdNom)
    }

    @Test
    fun mots_tolere_ordre_et_mot_isole() {
        peupler("Rougegorge familier" to 4001, "Tichodrome échelette" to 4494, "Bombus lucorum" to 240)
        // Mots inversés.
        assertEquals(4001, TaxRefCache.chercherParMots(TaxRefCache.normaliser("familier rougegorge"))?.cdNom)
        // Un seul mot discriminant.
        assertEquals(4494, TaxRefCache.chercherParMots(TaxRefCache.normaliser("tichodrome"))?.cdNom)
        assertEquals(240, TaxRefCache.chercherParMots(TaxRefCache.normaliser("lucorum"))?.cdNom)
    }

    @Test
    fun mots_ambigu_ne_matche_pas() {
        peupler("Milan royal" to 2000, "Milan noir" to 2001)
        // « milan » seul désigne 2 espèces → aucun match (pas de faux positif en auto-ajout vocal).
        assertNull(TaxRefCache.chercherParMots(TaxRefCache.normaliser("milan")))
        // …sauf si le groupe taxon restreint à un seul cd_nom.
        assertEquals(2000, TaxRefCache.chercherParMots(TaxRefCache.normaliser("milan"), setOf(2000))?.cdNom)
    }

    @Test
    fun approche_rattrape_une_faute_de_transcription() {
        peupler("Bombus lucorum" to 240, "Mésange bleue" to 4002)
        // « lucorun » (n au lieu de m) → 1 substitution → match.
        assertEquals(240, TaxRefCache.chercherApproche(TaxRefCache.normaliser("bombus lucorun"))?.cdNom)
    }

    @Test
    fun approche_refuse_ambiguite_et_trop_loin() {
        peupler("abcd" to 10, "abce" to 11, "Bombus lucorum" to 240)
        // Deux clés à distance 1 égale → ambigu → null (pas de faux positif).
        assertNull(TaxRefCache.chercherApproche("abcf"))
        // Trop éloigné de tout.
        assertNull(TaxRefCache.chercherApproche("zzzzzz"))
    }
}
