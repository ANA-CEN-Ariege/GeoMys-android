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

import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.network.GeoNatureUpload
import fr.ariegenature.geomys.util.AnaEval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lot C de l'audit 2026-08-27 (saisie OccHab / formulaires) — briques unitaires : géométrie
 * vierge détectée (station fantôme après mort du process), virgule décimale des champs
 * additionnels, bornes des entiers ANA-EVAL exposées à l'UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LotCAuditTest {

    @Test
    fun geometrie_definie_faux_pour_une_station_vierge() {
        assertFalse("OccHabStation() = ViewModel reconstruit après kill", OccHabStation().geometrieDefinie())
        assertTrue(OccHabStation(geometryType = "Point", latitude = 42.9, longitude = 1.4).geometrieDefinie())
        assertTrue(OccHabStation(geometryType = "Polygon", geometryCoordsJson = "[[1.4,42.9],[1.5,42.9],[1.5,43.0]]").geometrieDefinie())
        assertFalse("polygone sans sommets", OccHabStation(geometryType = "Polygon", geometryCoordsJson = "").geometrieDefinie())
    }

    @Test
    fun champs_additionnels_virgule_decimale_acceptee() {
        val json = GeoNatureUpload.jsonDepuisMap(mapOf(
            "recouvrement" to "12,5", "nombre" to "12", "point" to "12.5", "liste" to "1,2,3", "texte" to "a,b",
        ))
        assertEquals(12.5, json.getDouble("recouvrement"), 1e-9)
        assertEquals(12, json.getInt("nombre"))
        assertEquals(12.5, json.getDouble("point"), 1e-9)
        assertEquals("plusieurs virgules = texte", "1,2,3", json.getString("liste"))
        assertEquals("a,b", json.getString("texte"))
    }

    @Test
    fun champs_additionnels_texte_libre_envoyes_en_chaine_comme_le_web() {
        val defs = """[
          {"idField":1,"fieldName":"remarque","fieldLabel":"Remarque","widget":"TEXT"},
          {"idField":2,"fieldName":"note","fieldLabel":"Note","widget":"TEXTAREA"},
          {"idField":3,"fieldName":"exotique","fieldLabel":"Exotique","widget":"INCONNU"},
          {"idField":4,"fieldName":"effectif","fieldLabel":"Effectif","widget":"NUMBER"},
          {"idField":5,"fieldName":"vu","fieldLabel":"Vu","widget":"CHECKBOX"}
        ]"""
        val texte = GeoNatureUpload.champsAdditionnelsTexteLibre(defs)
        assertEquals(setOf("remarque", "note", "exotique"), texte)
        assertEquals("cache illisible → aucun champ texte (heuristique)", emptySet<String>(),
            GeoNatureUpload.champsAdditionnelsTexteLibre("<html>"))
        val json = GeoNatureUpload.jsonDepuisMap(
            mapOf("remarque" to "42", "note" to "true", "effectif" to "42", "vu" to "true"), texte)
        assertEquals("42", json.getString("remarque"))
        assertEquals("true", json.getString("note"))
        assertEquals(42, json.getInt("effectif"))
        assertTrue(json.getBoolean("vu"))
    }

    @Test
    fun bornes_des_entiers_ana_eval_exposees() {
        assertEquals(1 to 1_000_000, AnaEval.borneEntier("echelle"))
        assertNull(AnaEval.borneEntier("enjeu"))
    }
}
