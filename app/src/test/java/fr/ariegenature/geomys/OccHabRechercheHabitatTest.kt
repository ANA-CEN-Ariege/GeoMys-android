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
import fr.ariegenature.geomys.network.HabitatSuggestion
import fr.ariegenature.geomys.store.HabitatCacheStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parité de la recherche HABREF OccHab avec la route serveur `/habref/habitats/autocomplete`
 * (`ilike` insensible à la casse mais SENSIBLE aux accents, espaces du terme = jokers `%`,
 * ORDER BY lb_code avec collation Postgres qui ignore ponctuation/espaces). Cas réels du
 * diagnostic 2026-07-25 sur l'ANA (« genévrier », codes parenthésés, préfixes). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabRechercheHabitatTest {

    // Sous-ensemble réel de la réponse serveur pour « genévrier » (ordre serveur attendu),
    // volontairement mélangé à l'insertion.
    private val habitats = listOf(
        HabitatSuggestion(2, "16.271 - Fourrés dunaires à genévrier oxycèdre"),
        HabitatSuggestion(7, "(9560 et 5210)-2 - Peuplements alpins de genévrier thurifère du supraméditerranéen inférieur"),
        HabitatSuggestion(5, "42.A - Forêts dominées par les Cyprès, les Genévriers et les Ifs"),
        HabitatSuggestion(1, "16.27 - Dunes à genévrier"),
        HabitatSuggestion(4, "(4070 et 4060)-3 - Fourrés à Pinus mugo sur landes à genévrier nain"),
        HabitatSuggestion(6, "42.A27 - Bois pyrénéens de Genévriers thurifères"),
        HabitatSuggestion(3, "4060-9 - Fourrés xérophiles et mésoxérophiles des Alpes internes à genévrier"),
    )

    private fun storeOccHab(nom: String): HabitatCacheStore =
        HabitatCacheStore(nom, sensibleAuxAccents = true).apply {
            init(ApplicationProvider.getApplicationContext())
            remplacerTout(habitats)
        }

    @Test
    fun ordre_identique_au_serveur_collation_lb_code() {
        // Codes courts avant leurs extensions (16.27 < 16.271, 42.A < 42.A27), ponctuation
        // IGNORÉE par la collation : « (4070 et 4060)-3 » se classe comme « 4070et40603 »
        // (entre 4060-9 et 42.A) et « (9560… » en DERNIER — pas en tête à cause de la parenthèse.
        val res = storeOccHab("test_occhab_ordre.json").rechercher("genévrier", 20)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), res.map { it.cdHab })
    }

    @Test
    fun sensible_aux_accents_comme_le_ilike_serveur() {
        // « genevrier » sans accent ne matche RIEN (tous les libellés portent l'accent) —
        // même comportement que le serveur (cf. « Foret » → 1 seul résultat sur l'ANA).
        val store = storeOccHab("test_occhab_accents.json")
        assertTrue(store.rechercher("genevrier", 20).isEmpty())
        // Mais la casse est ignorée (ilike) : « GENÉVRIER » matche.
        assertEquals(7, store.rechercher("GENÉVRIER", 20).size)
    }

    @Test
    fun espaces_du_terme_sont_des_jokers_mots_dans_l_ordre() {
        // « genévrier thurifère » = ilike '%genévrier%thurifère%' : seuls les libellés portant
        // les deux mots DANS L'ORDRE matchent.
        val res = storeOccHab("test_occhab_jokers.json").rechercher("genévrier thurifère", 20)
        assertEquals(listOf(6, 7), res.map { it.cdHab })
    }

    @Test
    fun limite_respectee() {
        val res = storeOccHab("test_occhab_limite.json").rechercher("genévrier", 3)
        assertEquals(listOf(1, 2, 3), res.map { it.cdHab })
    }

    @Test
    fun mode_occtax_reste_insensible_aux_accents() {
        // Le cache OCCTAX historique fold les accents (confort de saisie terrain) : « genevrier »
        // sans accent doit continuer d'y matcher.
        val store = HabitatCacheStore("test_occtax_accents.json").apply {
            init(ApplicationProvider.getApplicationContext())
            remplacerTout(habitats)
        }
        assertEquals(7, store.rechercher("genevrier", 20).size)
    }
}
