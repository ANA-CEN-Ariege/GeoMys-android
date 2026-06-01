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

import fr.ariegenature.geonat.network.MonitoringApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing du schéma serveur /config/<code> : une propriété de formulaire, le bloc cruved,
 *  et les petites heuristiques (code nomenclature, id liste taxonomique). */
class MonitoringApiParsingTest {

    // ── parserCruved ──────────────────────────────────────────────────────────
    @Test
    fun cruved_null_renvoie_null() {
        assertNull(MonitoringApi.parserCruved(null))
    }

    @Test
    fun cruved_normalise_les_cles_en_majuscules() {
        val obj = JSONObject("""{"C":1,"R":2,"U":0}""")
        val cruved = MonitoringApi.parserCruved(obj)!!
        assertEquals(1, cruved["C"])
        assertEquals(2, cruved["R"])
        assertEquals(0, cruved["U"])
    }

    @Test
    fun cruved_accepte_niveaux_en_string() {
        val cruved = MonitoringApi.parserCruved(JSONObject("""{"c":"3"}"""))!!
        assertEquals(3, cruved["C"])
    }

    @Test
    fun cruved_vide_renvoie_null() {
        assertNull(MonitoringApi.parserCruved(JSONObject("{}")))
    }

    // ── parserUnePropriete ────────────────────────────────────────────────────
    @Test
    fun propriete_texte_basique() {
        val v = JSONObject("""{"type_widget":"text","label":"Commentaire","required":true}""")
        val p = MonitoringApi.parserUnePropriete("comment", v)!!
        assertEquals("comment", p.nom)
        assertEquals("text", p.typeWidget)
        assertEquals("Commentaire", p.label)
        assertTrue(p.obligatoire)
    }

    @Test
    fun label_deduit_du_nom_si_absent() {
        val p = MonitoringApi.parserUnePropriete("date_min", JSONObject("""{"type_widget":"date"}"""))!!
        assertEquals("Date min", p.label) // underscores → espaces + capitale initiale
        assertFalse(p.obligatoire)
    }

    @Test
    fun type_widget_repli_sur_widget_puis_type() {
        assertEquals("select", MonitoringApi.parserUnePropriete("a", JSONObject("""{"widget":"select"}"""))!!.typeWidget)
        assertEquals("number", MonitoringApi.parserUnePropriete("b", JSONObject("""{"type":"number"}"""))!!.typeWidget)
    }

    @Test
    fun hidden_true_conserve_le_champ_en_technique() {
        val p = MonitoringApi.parserUnePropriete("id_module", JSONObject("""{"hidden":true}"""))!!
        assertTrue(p.hiddenBool)
    }

    @Test
    fun hidden_expression_string_devient_hiddenExpr() {
        // Une valeur `hidden` de type String est une expression d'affichage conditionnel
        // (stockée telle quelle dans hiddenExpr), pas un masquage technique (hiddenBool).
        val v = JSONObject("""{"type_widget":"text","hidden":"statut === 'X'"}""")
        val p = MonitoringApi.parserUnePropriete("c", v)!!
        assertFalse(p.hiddenBool)
        assertEquals("statut === 'X'", p.hiddenExpr)
    }

    @Test
    fun champ_sans_widget_ni_hidden_est_ignore() {
        assertNull(MonitoringApi.parserUnePropriete("parasite", JSONObject("""{"foo":"bar"}""")))
    }

    @Test
    fun champ_specific_sans_widget_infere_text_ou_date() {
        // En bloc specific, un champ sans widget reçoit text (ou date si type_util=date).
        assertEquals("text", MonitoringApi.parserUnePropriete("x", JSONObject("{}"), enSpecific = true)!!.typeWidget)
        val d = MonitoringApi.parserUnePropriete("d", JSONObject("""{"type_util":"date"}"""), enSpecific = true)!!
        assertEquals("date", d.typeWidget)
    }

    // ── heuristiques pures ─────────────────────────────────────────────────────
    @Test
    fun infere_code_nomenclature_depuis_api_ou_nom() {
        assertEquals("STADE_VIE", MonitoringApi.infererCodeNomenclature("x", "ref/nomenclatures/nomenclature/STADE_VIE"))
        assertEquals("stade_vie", MonitoringApi.infererCodeNomenclature("id_nomenclature_stade_vie", null))
        assertNull(MonitoringApi.infererCodeNomenclature("autre", null))
    }

    @Test
    fun extrait_id_liste_taxonomique_depuis_api() {
        assertEquals(100, MonitoringApi.extraireIdListeAllnamebylist("taxref/allnamebylist/100"))
        assertNull(MonitoringApi.extraireIdListeAllnamebylist("taxref/autre/100"))
    }
}
