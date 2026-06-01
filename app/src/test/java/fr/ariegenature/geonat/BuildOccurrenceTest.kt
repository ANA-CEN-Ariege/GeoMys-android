/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat

import fr.ariegenature.geonat.model.Denombrement
import fr.ariegenature.geonat.model.Observation
import fr.ariegenature.geonat.network.GeoNatureUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Construction du payload OccTax d'une occurrence (cd_nom, nom_cite, countings) et résolution
 *  des id_nomenclature envoyés au serveur. */
class BuildOccurrenceTest {

    private val sansNomenclatures = emptyMap<String, Map<String, Int>>()

    private fun obs(
        cdNom: Int = 4001,
        espece: String = "Merle noir",
        nombre: Int = 1,
        nombreMax: Int? = null,
        notes: String = "",
    ) = Observation(
        espece = espece, cdNom = cdNom, latitude = 42.9, longitude = 1.4,
        nombre = nombre, nombreMax = nombreMax, notes = notes,
    )

    @Test
    fun occurrence_minimale_structure_de_base() {
        val o = GeoNatureUpload.buildOccurrence(obs(nombre = 2), sansNomenclatures)
        assertEquals(4001, o.getInt("cd_nom"))
        assertEquals("Merle noir", o.getString("nom_cite"))
        assertFalse("pas de commentaire si notes vides", o.has("comment"))

        val countings = o.getJSONArray("cor_counting_occtax")
        assertEquals(1, countings.length())
        val c0 = countings.getJSONObject(0)
        assertEquals(2, c0.getInt("count_min"))
        assertEquals(2, c0.getInt("count_max"))
    }

    @Test
    fun notes_deviennent_comment() {
        val o = GeoNatureUpload.buildOccurrence(obs(notes = "posé sur un fil"), sansNomenclatures)
        assertEquals("posé sur un fil", o.getString("comment"))
    }

    @Test
    fun count_max_jamais_inferieur_a_count_min() {
        // nombre=5 mais nombreMax=2 (incohérent) → count_max coercé à 5.
        val o = GeoNatureUpload.buildOccurrence(obs(nombre = 5, nombreMax = 2), sansNomenclatures)
        val c0 = o.getJSONArray("cor_counting_occtax").getJSONObject(0)
        assertEquals(5, c0.getInt("count_min"))
        assertEquals(5, c0.getInt("count_max"))
    }

    @Test
    fun denombrements_additionnels_ajoutent_des_countings() {
        val o = obs().apply {
            denombrementsAdditionnels = listOf(
                Denombrement(nombreMin = 3, nombreMax = 4),
                Denombrement(nombreMin = 1, nombreMax = 1),
            )
        }
        val payload = GeoNatureUpload.buildOccurrence(o, sansNomenclatures)
        assertEquals(3, payload.getJSONArray("cor_counting_occtax").length()) // #0 + 2
    }

    @Test
    fun nomenclatures_absentes_omettent_les_champs_optionnels() {
        val o = obs().apply { statutBio = "1"; etaBio = "2" }
        val payload = GeoNatureUpload.buildOccurrence(o, sansNomenclatures)
        // Sans table de nomenclatures serveur, aucun id_nomenclature_* n'est résolu.
        assertFalse(payload.has("id_nomenclature_bio_status"))
        assertFalse(payload.has("id_nomenclature_bio_condition"))
    }

    // ── resolverIdNomenclature : ordre de résolution ───────────────────────────
    @Test
    fun id_serveur_passe_en_priorite() {
        // code "3" est un id serveur existant ET labels["3"] pointe ailleurs : l'id serveur gagne.
        val nomenclatures = mapOf("X" to mapOf("adulte" to 3))
        val labels = mapOf("3" to "Juvénile")
        assertEquals(3, GeoNatureUpload.resolverIdNomenclature("3", "X", labels, nomenclatures))
    }

    @Test
    fun code_interne_resolu_via_label() {
        val nomenclatures = mapOf("SEXE" to mapOf("mâle" to 42))
        val labels = mapOf("1" to "Mâle")
        assertEquals(42, GeoNatureUpload.resolverIdNomenclature("1", "SEXE", labels, nomenclatures))
    }

    @Test
    fun fallback_texte_brut() {
        val nomenclatures = mapOf("SEXE" to mapOf("femelle" to 7))
        assertEquals(7, GeoNatureUpload.resolverIdNomenclature("Femelle", "SEXE", emptyMap(), nomenclatures))
    }

    @Test
    fun code_non_resolu_renvoie_null() {
        assertEquals(null, GeoNatureUpload.resolverIdNomenclature("inconnu", "SEXE", emptyMap(), emptyMap()))
    }
}
