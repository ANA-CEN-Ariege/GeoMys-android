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
import fr.ariegenature.geomys.network.OccHabApi
import fr.ariegenature.geomys.ui.OccHabViewModel
import fr.ariegenature.geomys.ui.airePolygoneM2
import fr.ariegenature.geomys.util.GeoJsonCoords
import org.osmdroid.util.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * STATIONS À TROU (« polygone intérieur », bug terrain 2026-08-31, ex. station 2805) : une
 * station dessinée sous QGIS peut porter des anneaux INTÉRIEURS. L'appli n'en lisait que
 * l'anneau extérieur → le trou était invisible sur la carte, et surtout un simple ré-envoi
 * (mise à jour) le SUPPRIMAIT côté serveur. Couvre aussi le MultiPolygon (colonne serveur
 * `Geometry("GEOMETRY")` : tout type est possible).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabPolygoneTrouTest {

    /** Carré extérieur 0..10 (fermé) avec un carré intérieur 2..4 (fermé) — le format que
     *  renvoie GeoNature (`ST_AsGeoJSON`, anneaux fermés). */
    private fun featureCollection(geometry: String) = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":$geometry,
           "properties":{"id_station":2805,"id_dataset":12,"station_name":"Tourbière"}}
        ]}
    """.trimIndent()

    private val polygoneATrou = """
        {"type":"Polygon","coordinates":[
          [[0,0],[10,0],[10,10],[0,10],[0,0]],
          [[2,2],[4,2],[4,4],[2,4],[2,2]]
        ]}
    """.trimIndent()

    // ── Lecture depuis le serveur ──

    @Test
    fun polygone_a_trou_lu_le_trou_est_conserve() {
        val st = OccHabApi.parserFeatureCollection(featureCollection(polygoneATrou)).single()
        assertEquals("Polygon", st.geometryType)
        // Anneau extérieur : point de fermeture retiré (convention de l'éditeur).
        assertEquals(4, GeoJsonCoords.parse(st.geometryCoordsJson).size)
        val trous = GeoJsonCoords.parseAnneaux(st.geometryTrousJson)
        assertEquals("un trou conservé", 1, trous.size)
        assertEquals("fermeture retirée aussi sur le trou", 4, trous[0].size)
        assertEquals(2.0, trous[0][0].longitude, 1e-9)
        assertEquals(2.0, trous[0][0].latitude, 1e-9)
        assertFalse(st.geometryPartielle)
    }

    @Test
    fun polygone_plein_pas_de_trou() {
        val st = OccHabApi.parserFeatureCollection(
            featureCollection("""{"type":"Polygon","coordinates":[[[0,0],[10,0],[10,10],[0,0]]]}""")
        ).single()
        assertNull(st.geometryTrousJson)
        assertFalse(st.geometryPartielle)
    }

    @Test
    fun centroide_calcule_sur_l_anneau_exterieur() {
        val st = OccHabApi.parserFeatureCollection(featureCollection(polygoneATrou)).single()
        // Moyenne des 4 sommets extérieurs (0,0)(10,0)(10,10)(0,10) — le trou n'entre pas dedans.
        assertEquals(5.0, st.latitude, 1e-9)
        assertEquals(5.0, st.longitude, 1e-9)
    }

    @Test
    fun trou_degenere_ignore() {
        val st = OccHabApi.parserFeatureCollection(featureCollection("""
            {"type":"Polygon","coordinates":[[[0,0],[10,0],[10,10],[0,0]],[[2,2],[3,3]]]}
        """.trimIndent())).single()
        assertNull("anneau de 2 sommets = pas un trou", st.geometryTrousJson)
    }

    // ── MultiPolygon (colonne serveur générique : QGIS peut en produire) ──

    @Test
    fun multipolygon_a_une_seule_partie_traite_comme_un_polygone() {
        val st = OccHabApi.parserFeatureCollection(featureCollection("""
            {"type":"MultiPolygon","coordinates":[[
              [[0,0],[10,0],[10,10],[0,10],[0,0]],
              [[2,2],[4,2],[4,4],[2,4],[2,2]]
            ]]}
        """.trimIndent())).single()
        assertEquals("Polygon", st.geometryType)
        assertEquals(4, GeoJsonCoords.parse(st.geometryCoordsJson).size)
        assertEquals(1, GeoJsonCoords.parseAnneaux(st.geometryTrousJson).size)
        assertFalse("une seule partie = géométrie complète", st.geometryPartielle)
    }

    @Test
    fun multipolygon_multi_parties_marque_partielle() {
        val st = OccHabApi.parserFeatureCollection(featureCollection("""
            {"type":"MultiPolygon","coordinates":[
              [[[0,0],[10,0],[10,10],[0,0]]],
              [[[20,20],[30,20],[30,30],[20,20]]]
            ]}
        """.trimIndent())).single()
        assertEquals("Polygon", st.geometryType)
        assertTrue("non importable : un renvoi perdrait les autres parties", st.geometryPartielle)
        // La 1ʳᵉ partie reste AFFICHABLE (repère sur la carte + aimantage).
        assertEquals(3, GeoJsonCoords.parse(st.geometryCoordsJson).size)
    }

    // ── Renvoi au serveur (mise à jour) ──

    @Test
    fun envoi_referme_l_exterieur_et_les_trous() {
        val g = GeoNatureUpload.construireGeometrie(
            type = "Polygon",
            coordsJson = "[[0,0],[10,0],[10,10],[0,10]]",
            lat = 5.0, lon = 5.0,
            trousJson = "[[[2,2],[4,2],[4,4],[2,4]]]",
        )
        assertEquals("Polygon", g.getString("type"))
        val anneaux = g.getJSONArray("coordinates")
        assertEquals("extérieur + 1 trou", 2, anneaux.length())
        val ext = anneaux.getJSONArray(0)
        assertEquals("anneau refermé", 5, ext.length())
        assertEquals(0.0, ext.getJSONArray(4).getDouble(0), 1e-9)
        val trou = anneaux.getJSONArray(1)
        assertEquals("trou refermé", 5, trou.length())
        assertEquals(2.0, trou.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(2.0, trou.getJSONArray(4).getDouble(0), 1e-9)
    }

    @Test
    fun envoi_sans_trou_inchange() {
        // Non-régression Occtax : aucun appelant hors OccHab ne passe de trous.
        val g = GeoNatureUpload.construireGeometrie("Polygon", "[[0,0],[10,0],[10,10]]", 0.0, 0.0)
        assertEquals(1, g.getJSONArray("coordinates").length())
    }

    @Test
    fun trous_illisibles_envoi_du_polygone_plein_sans_crash() {
        val g = GeoNatureUpload.construireGeometrie(
            "Polygon", "[[0,0],[10,0],[10,10]]", 0.0, 0.0, trousJson = "{pas du json")
        assertEquals("l'extérieur part quand même", 1, g.getJSONArray("coordinates").length())
    }

    @Test
    fun round_trip_serveur_le_trou_survit_a_une_mise_a_jour() {
        // LE bug : lire une station à trou puis la renvoyer telle quelle doit redonner la
        // MÊME géométrie (avant, le trou disparaissait du serveur).
        val st = OccHabApi.parserFeatureCollection(featureCollection(polygoneATrou)).single()
        val g = GeoNatureUpload.construireGeometrie(
            st.geometryType, st.geometryCoordsJson, st.latitude, st.longitude, st.geometryTrousJson)
        val anneaux = g.getJSONArray("coordinates")
        assertEquals(2, anneaux.length())
        assertEquals(5, anneaux.getJSONArray(0).length()) // extérieur fermé, 4 sommets + retour
        assertEquals(5, anneaux.getJSONArray(1).length()) // trou fermé
        // Sommets du trou identiques à l'origine.
        val trou = anneaux.getJSONArray(1)
        assertEquals(2.0, trou.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(4.0, trou.getJSONArray(1).getDouble(0), 1e-9)
        assertEquals(4.0, trou.getJSONArray(2).getDouble(1), 1e-9)
    }

    // ── Empreinte de contenu (« Mes stations » à la 1ʳᵉ modification) ──

    @Test
    fun un_trou_modifie_change_l_empreinte() {
        val base = OccHabStation(
            geometryType = "Polygon", geometryCoordsJson = "[[0,0],[10,0],[10,10]]",
            geometryTrousJson = "[[[2,2],[4,2],[4,4]]]",
        )
        assertNotEquals(
            base.empreinteContenu(),
            base.copy(geometryTrousJson = "[[[2,2],[5,2],[5,5]]]").empreinteContenu(),
        )
        assertNotEquals(
            "trou retiré = modification",
            base.empreinteContenu(), base.copy(geometryTrousJson = null).empreinteContenu(),
        )
    }

    @Test
    fun empreinte_insensible_au_formatage_des_trous() {
        val a = OccHabStation(geometryType = "Polygon", geometryCoordsJson = "[[0,0],[10,0],[10,10]]",
            geometryTrousJson = "[[[2,2],[4,2],[4,4]]]")
        val b = a.copy(geometryTrousJson = "[[[2.0,2.0],[4.0,2.0],[4.0,4.0]]]")
        assertEquals(a.empreinteContenu(), b.empreinteContenu())
    }

    // ── Anneaux : sérialisation pour la persistance de l'éditeur ──

    @Test
    fun anneaux_round_trip_et_null_si_aucun_trou() {
        val anneaux = listOf(
            listOf(GeoPoint(42.0, 1.0), GeoPoint(42.0, 1.1), GeoPoint(42.1, 1.1)),
            listOf(GeoPoint(43.0, 2.0), GeoPoint(43.0, 2.1), GeoPoint(43.1, 2.1)),
        )
        val json = GeoJsonCoords.formatAnneaux(anneaux)
        val relus = GeoJsonCoords.parseAnneaux(json)
        assertEquals(2, relus.size)
        assertEquals(3, relus[0].size)
        assertEquals(42.0, relus[0][0].latitude, 1e-9)
        assertEquals(1.0, relus[0][0].longitude, 1e-9)
        assertEquals(43.1, relus[1][2].latitude, 1e-9)
        // Aucun trou = champ ABSENT (jamais un tableau vide) : le polygone est plein.
        assertNull(GeoJsonCoords.formatAnneaux(emptyList()))
        assertNull(GeoJsonCoords.formatAnneaux(listOf(emptyList())))
        assertTrue(GeoJsonCoords.parseAnneaux(null).isEmpty())
        assertTrue(GeoJsonCoords.parseAnneaux("{pas du json").isEmpty())
    }

    // ── Surface : aire NETTE (parité ST_Area côté serveur) ──

    @Test
    fun surface_deduit_les_trous() {
        // Carré ~1° de côté, trou de ~0,5° → l'aire nette vaut environ les 3/4 de la brute.
        val ext = listOf(GeoPoint(42.0, 1.0), GeoPoint(42.0, 2.0), GeoPoint(43.0, 2.0), GeoPoint(43.0, 1.0))
        val trou = listOf(GeoPoint(42.2, 1.2), GeoPoint(42.2, 1.7), GeoPoint(42.7, 1.7), GeoPoint(42.7, 1.2))
        val brute = airePolygoneM2(ext)
        val nette = airePolygoneM2(ext, listOf(trou))
        assertTrue("le trou est déduit", nette < brute)
        assertEquals(brute - airePolygoneM2(trou), nette, 1.0)
        assertEquals("sans trou = aire brute", brute, airePolygoneM2(ext, emptyList()), 1e-6)
    }

    @Test
    fun surface_jamais_negative() {
        val ext = listOf(GeoPoint(42.0, 1.0), GeoPoint(42.0, 1.1), GeoPoint(42.1, 1.1))
        val trouEnorme = listOf(GeoPoint(40.0, 0.0), GeoPoint(40.0, 5.0), GeoPoint(45.0, 5.0))
        assertEquals(0.0, airePolygoneM2(ext, listOf(trouEnorme)), 1e-9)
    }

    // ── ViewModel : trous conservés à l'édition, purgés au redessin ──

    @Test
    fun definir_geometrie_conserve_ou_purge_les_trous() {
        val vm = OccHabViewModel()
        val trous = "[[[2,2],[4,2],[4,4]]]"
        vm.definirGeometrie("Polygon", 5.0, 5.0, "[[0,0],[10,0],[10,10]]", trous)
        assertEquals(trous, vm.station.geometryTrousJson)
        // Redessin (tracé neuf) : aucun trou transmis → la station repart PLEINE.
        vm.definirGeometrie("Polygon", 1.0, 1.0, "[[0,0],[1,0],[1,1]]", null)
        assertNull(vm.station.geometryTrousJson)
        // Bascule en Point : les trous n'ont plus de sens.
        vm.definirGeometrie("Polygon", 5.0, 5.0, "[[0,0],[10,0],[10,10]]", trous)
        vm.definirGeometrie("Point", 42.9, 1.4, null)
        assertNull(vm.station.geometryTrousJson)
    }
}
