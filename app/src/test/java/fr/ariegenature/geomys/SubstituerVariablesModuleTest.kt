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

import fr.ariegenature.geomys.network.MonitoringApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Substitution en place des placeholders `__MODULE.XXX` du schéma serveur par leur valeur
 *  déclarée dans le bloc `custom` (parité substituteVariables de gn_mobile_monitoring). */
class SubstituerVariablesModuleTest {

    @Test
    fun remplace_dans_une_chaine_imbriquee() {
        val racine = JSONObject(
            """
            {
              "custom": { "__MODULE.LABEL": "STOM Ariège" },
              "module": { "titre": "Protocole __MODULE.LABEL" }
            }
            """.trimIndent(),
        )
        MonitoringApi.substituerVariablesModule(racine)
        assertEquals("Protocole STOM Ariège", racine.getJSONObject("module").getString("titre"))
    }

    @Test
    fun remplace_dans_un_tableau() {
        val racine = JSONObject(
            """
            {
              "custom": { "__MODULE.X": "valeurX" },
              "liste": ["a", "préfixe __MODULE.X", "b"]
            }
            """.trimIndent(),
        )
        MonitoringApi.substituerVariablesModule(racine)
        assertEquals("préfixe valeurX", racine.getJSONArray("liste").getString(1))
    }

    @Test
    fun plusieurs_placeholders_dans_la_meme_chaine() {
        val racine = JSONObject(
            """
            {
              "custom": { "__MODULE.A": "1", "__MODULE.B": "2" },
              "n": { "v": "__MODULE.A et __MODULE.B" }
            }
            """.trimIndent(),
        )
        MonitoringApi.substituerVariablesModule(racine)
        assertEquals("1 et 2", racine.getJSONObject("n").getString("v"))
    }

    @Test
    fun sans_bloc_custom_aucun_changement() {
        val racine = JSONObject("""{"module":{"titre":"Inchangé __MODULE.X"}}""")
        MonitoringApi.substituerVariablesModule(racine)
        // Pas de custom → on laisse la chaîne telle quelle.
        assertEquals("Inchangé __MODULE.X", racine.getJSONObject("module").getString("titre"))
    }

    @Test
    fun chaine_sans_placeholder_intacte() {
        val racine = JSONObject(
            """{"custom":{"__MODULE.X":"v"},"module":{"titre":"Texte normal"}}""",
        )
        MonitoringApi.substituerVariablesModule(racine)
        assertEquals("Texte normal", racine.getJSONObject("module").getString("titre"))
    }
}
