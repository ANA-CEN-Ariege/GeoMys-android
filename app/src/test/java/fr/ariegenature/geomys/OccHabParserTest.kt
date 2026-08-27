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

import fr.ariegenature.geomys.network.OccHabApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parser des stations serveur ([OccHabApi.parserFeatureCollection]) : depuis que les stations
 * du serveur sont IMPORTABLES pour modification (renvoi en MISE À JOUR), le parser doit capturer
 * TOUT ce que l'update renvoie — un champ non capturé serait perdu ou écrasé côté serveur.
 * Fixture construite d'après le dump de StationSchema/OccurenceHabitatSchema (include_fk=True :
 * les colonnes `id_nomenclature_*` sont à plat dans les properties, les objets imbriqués
 * n'apparaissent qu'avec `nomenclatures=1`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabParserTest {

    private fun jour(s: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)!!.time

    // Réponse GET /stations/?format=geojson&habitats=1&nomenclatures=1 — une station complète.
    private val fixtureComplete = """
    {
      "type": "FeatureCollection",
      "features": [
        {
          "type": "Feature",
          "id": 42,
          "geometry": {"type": "Polygon",
                       "coordinates": [[[1.40,42.90],[1.50,42.90],[1.50,43.00],[1.40,42.90]]]},
          "properties": {
            "id_station": 42,
            "unique_id_sinp_station": "bbbbbbbb-1111-2222-3333-444444444444",
            "id_dataset": 12,
            "id_digitiser": 7,
            "date_min": "2026-05-12",
            "date_max": "2026-05-13",
            "observers": [
              {"id_role": 7, "nom_complet": "DUPONT jean"},
              {"id_role": 8, "nom_role": "MARTIN", "prenom_role": "paul"}
            ],
            "observers_txt": "DUPONT jean",
            "station_name": "Tourbière du col",
            "comment": "station de test",
            "altitude_min": 400,
            "altitude_max": 420,
            "depth_min": 0,
            "depth_max": 2,
            "area": 1234,
            "precision": 10,
            "id_nomenclature_exposure": 21,
            "id_nomenclature_area_surface_calculation": 22,
            "id_nomenclature_geographic_object": 23,
            "id_nomenclature_type_sol": 24,
            "id_nomenclature_type_mosaique_habitat": 25,
            "habitats": [
              {
                "id_habitat": 91,
                "id_station": 42,
                "unique_id_sinp_hab": "aaaaaaaa-1111-2222-3333-444444444444",
                "cd_hab": 629,
                "nom_cite": "Prairie de fauche",
                "determiner": "DUPONT jean",
                "recovery_percentage": 80.5,
                "technical_precision": "relevé de terrain",
                "id_nomenclature_determination_type": 11,
                "id_nomenclature_collection_technique": 12,
                "id_nomenclature_abundance": 13,
                "id_nomenclature_sensitivity": 14,
                "id_nomenclature_community_interest": 15,
                "habref": {"cd_hab": 629, "lb_hab_fr": "Prairies de fauche de basse altitude"}
              }
            ]
          }
        }
      ]
    }
    """.trimIndent()

    @Test
    fun polygone_serveur_le_point_de_fermeture_n_est_pas_un_sommet() {
        // GeoJSON ferme l'anneau (dernier = premier) ; l'éditeur travaille sur des sommets
        // DISTINCTS (sinon deux markers superposés + poignée sur arête nulle — audit 2026-08-27) ;
        // construireGeometrie referme à l'envoi.
        val st = OccHabApi.parserFeatureCollection(fixtureComplete).single()
        val ring = org.json.JSONArray(st.geometryCoordsJson)
        assertEquals(3, ring.length())
        assertEquals(1.4, ring.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(43.0, ring.getJSONArray(2).getDouble(1), 1e-9)
        // Centroïde sur les sommets distincts.
        assertEquals((42.9 + 42.9 + 43.0) / 3, st.latitude, 1e-9)
    }

    @Test
    fun station_complete_tout_est_capture_pour_le_round_trip() {
        val st = OccHabApi.parserFeatureCollection(fixtureComplete).single()

        // Identité + géométrie.
        assertEquals(42, st.idStationServeur)
        assertEquals("bbbbbbbb-1111-2222-3333-444444444444", st.uuidStation)
        assertEquals("Polygon", st.geometryType)
        assertTrue(st.envoyeGeoNature)
        assertTrue(st.origineServeur)
        // Champs station.
        assertEquals(12, st.idDataset)
        assertEquals(jour("2026-05-12"), st.dateMin)
        assertEquals(jour("2026-05-13"), st.dateMax)
        assertEquals(listOf(7, 8), st.observateursIds)
        assertEquals(listOf("DUPONT jean", "MARTIN paul"), st.observateursNoms)
        assertEquals("DUPONT jean", st.observateursTxt)
        assertEquals("Tourbière du col", st.stationName)
        assertEquals("station de test", st.comment)
        // Sans bloc ANA-EVAL : anaEvalJson null, textes STRICTEMENT inchangés (standard).
        assertNull(st.anaEvalJson)
        assertEquals(400, st.altitudeMin)
        assertEquals(420, st.altitudeMax)
        assertEquals(0, st.profondeurMin)
        assertEquals(2, st.profondeurMax)
        assertEquals(1234L, st.surface)
        assertEquals(10, st.precision)
        assertEquals(21, st.idNomExposition)
        assertEquals(22, st.idNomCalculSurface)
        assertEquals(23, st.idNomObjetGeographique)
        assertEquals(24, st.idNomTypeSol)
        assertEquals(25, st.idNomTypeMosaique)
        // Habitat.
        val h = st.habitats.single()
        assertEquals(91, h.idHabitatServeur)
        assertEquals("aaaaaaaa-1111-2222-3333-444444444444", h.uuidHabitat)
        assertEquals(629, h.cdHab)
        assertEquals("Prairies de fauche de basse altitude", h.habitatLabel)
        assertEquals("Prairie de fauche", h.nomCite)
        assertEquals("DUPONT jean", h.determiner)
        assertEquals(80.5, h.recouvrement!!, 1e-9)
        assertEquals("relevé de terrain", h.precisionTechnique)
        assertNull(h.anaEvalJson)
        assertEquals(11, h.idNomTypeDetermination)
        assertEquals(12, h.idNomTechniqueCollecte)
        assertEquals(13, h.idNomAbondance)
        assertEquals(14, h.idNomSensibilite)
        assertEquals(15, h.idNomInteretCommunautaire)
    }

    /** Repli : serveur qui n'exposerait PAS les colonnes `id_nomenclature_*` à plat mais
     *  seulement les OBJETS imbriqués (`nomenclatures=1`) — l'id est lu dans l'objet. NB : la
     *  relation mosaïque s'appelle `type_mosaique_habitat` (sans préfixe) côté serveur. */
    @Test
    fun nomenclatures_imbriquees_seules_repli_sur_l_objet() {
        val texte = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
          "geometry":{"type":"Point","coordinates":[1.4,42.9]},
          "properties":{
            "id_station": 7,
            "nomenclature_exposure": {"id_nomenclature": 31},
            "nomenclature_area_surface_calculation": {"id_nomenclature": 32},
            "nomenclature_geographic_object": {"id_nomenclature": 33},
            "nomenclature_type_sol": {"id_nomenclature": 34},
            "type_mosaique_habitat": {"id_nomenclature": 35},
            "habitats": [{
              "cd_hab": 629, "nom_cite": "x",
              "nomenclature_determination_type": {"id_nomenclature": 41},
              "nomenclature_collection_technique": {"id_nomenclature": 42},
              "nomenclature_abundance": {"id_nomenclature": 43},
              "nomenclature_sensitivity": {"id_nomenclature": 44},
              "nomenclature_community_interest": {"id_nomenclature": 45}
            }]
          }}]}
        """.trimIndent()
        val st = OccHabApi.parserFeatureCollection(texte).single()
        assertEquals(31, st.idNomExposition)
        assertEquals(32, st.idNomCalculSurface)
        assertEquals(33, st.idNomObjetGeographique)
        assertEquals(34, st.idNomTypeSol)
        assertEquals(35, st.idNomTypeMosaique)
        val h = st.habitats.single()
        assertEquals(41, h.idNomTypeDetermination)
        assertEquals(42, h.idNomTechniqueCollecte)
        assertEquals(43, h.idNomAbondance)
        assertEquals(44, h.idNomSensibilite)
        assertEquals(45, h.idNomInteretCommunautaire)
    }

    /** Les `null` JSON du dump serveur restent des null Kotlin (org.json les transformerait en
     *  0 / « null » littéral avec optInt/optString) — sinon l'update renverrait ces valeurs
     *  inventées au serveur. */
    @Test
    fun champs_null_json_restent_null() {
        val texte = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
          "geometry":{"type":"Point","coordinates":[1.4,42.9]},
          "properties":{
            "id_station": 7,
            "unique_id_sinp_station": null,
            "comment": null, "station_name": null, "observers_txt": null,
            "altitude_min": null, "altitude_max": null, "area": null, "precision": null,
            "id_nomenclature_exposure": null,
            "habitats": [{"cd_hab": 629, "nom_cite": "x", "id_habitat": null,
                          "unique_id_sinp_hab": null, "determiner": null,
                          "technical_precision": null,
                          "id_nomenclature_abundance": null}]
          }}]}
        """.trimIndent()
        val st = OccHabApi.parserFeatureCollection(texte).single()
        assertNull(st.comment)
        assertNull(st.stationName)
        assertNull(st.observateursTxt)
        assertNull(st.altitudeMin)
        assertNull(st.altitudeMax)
        assertNull(st.surface)
        assertNull(st.precision)
        assertNull(st.idNomExposition)
        assertTrue("uuid absent → un uuid local est généré (jamais vide)", st.uuidStation.isNotBlank())
        val h = st.habitats.single()
        assertNull(h.idHabitatServeur)
        assertNull(h.uuidHabitat)
        assertNull(h.determiner)
        assertNull(h.precisionTechnique)
        assertNull(h.idNomAbondance)
    }

    /** Stations du plugin QGIS occhab-qgis : le bloc « [ANA-EVAL] {json} [/ANA-EVAL] » est
     *  EXTRAIT de comment/technical_precision (textes stockés purement humains, JSON normalisé
     *  dans anaEvalJson — il sera re-fusionné à l'envoi). Le recouvrement est un champ DOUBLE :
     *  la clé du bloc fait foi sur la colonne `recovery_percentage` à la relecture. */
    @Test
    fun bloc_ana_eval_extrait_de_comment_et_technical_precision() {
        val texte = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
          "geometry":{"type":"Point","coordinates":[1.4,42.9]},
          "properties":{
            "id_station": 7,
            "comment": "Note de terrain.\n\n[ANA-EVAL] {\"enjeu\": \"fort\", \"zone_humide\": \"oui\", \"echelle\": 5000} [/ANA-EVAL]",
            "habitats": [{
              "cd_hab": 629, "nom_cite": "Prairie",
              "recovery_percentage": 45.0,
              "technical_precision": "relevé de terrain\n\n[ANA-EVAL] {\"typicite\": \"bonne\", \"recouvrement\": 60, \"corresp\": {\"EUNIS\": {\"cd_hab\": 5678, \"src\": \"manuel\"}}} [/ANA-EVAL]"
            }]
          }}]}
        """.trimIndent()
        val st = OccHabApi.parserFeatureCollection(texte).single()

        // Station : comment = part humaine seule, bloc normalisé à part.
        assertEquals("Note de terrain.", st.comment)
        val anaStation = fr.ariegenature.geomys.util.AnaEval.depuisJson(st.anaEvalJson)
        assertEquals("fort", anaStation["enjeu"])
        assertEquals("oui", anaStation["zone_humide"])
        assertEquals(5000, anaStation["echelle"])

        // Habitat : même extraction ; le bloc (60) PRIME sur la colonne (45) pour le
        // recouvrement, et la clé structurée `corresp` est préservée telle quelle.
        val h = st.habitats.single()
        assertEquals("relevé de terrain", h.precisionTechnique)
        assertEquals(60.0, h.recouvrement!!, 1e-9)
        val anaHabitat = fr.ariegenature.geomys.util.AnaEval.depuisJson(h.anaEvalJson)
        assertEquals("bonne", anaHabitat["typicite"])
        assertEquals(mapOf("EUNIS" to mapOf("cd_hab" to 5678, "src" to "manuel")), anaHabitat["corresp"])
    }

    /** Ancien format `clé=valeur` du bloc : encore lu (stations synchronisées avant la 0.10),
     *  converti en JSON normalisé dans anaEvalJson — alias hérités compris. */
    @Test
    fun bloc_ana_eval_ancien_format_converti() {
        val texte = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
          "geometry":{"type":"Point","coordinates":[1.4,42.9]},
          "properties":{
            "id_station": 7,
            "comment": "Relevé.\n\n[ANA-EVAL] enjeu=majeur | zone_humide=true [/ANA-EVAL]",
            "habitats": []
          }}]}
        """.trimIndent()
        val st = OccHabApi.parserFeatureCollection(texte).single()
        assertEquals("Relevé.", st.comment)
        assertEquals(
            mapOf("enjeu" to "tres_fort", "zone_humide" to "oui"),
            fr.ariegenature.geomys.util.AnaEval.depuisJson(st.anaEvalJson),
        )
    }

    /** Un commentaire qui ne PORTE que le bloc (pas de texte humain) : comment devient null,
     *  le bloc seul repartira à l'envoi. Et un bloc inexploitable laisse tout inchangé. */
    @Test
    fun bloc_ana_eval_sans_texte_humain_et_bloc_inexploitable() {
        val texte = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[1.4,42.9]},
           "properties":{"id_station": 1,
             "comment": "[ANA-EVAL] {\"enjeu\": \"faible\"} [/ANA-EVAL]", "habitats": []}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[1.5,42.8]},
           "properties":{"id_station": 2,
             "comment": "Note. [ANA-EVAL] nimporte quoi [/ANA-EVAL]", "habitats": []}}
        ]}
        """.trimIndent()
        val stations = OccHabApi.parserFeatureCollection(texte)
        val avecBloc = stations.first { it.idStationServeur == 1 }
        assertNull(avecBloc.comment)
        assertEquals("faible",
            fr.ariegenature.geomys.util.AnaEval.depuisJson(avecBloc.anaEvalJson)["enjeu"])
        // Bloc illisible → RIEN n'est extrait ni modifié : le texte complet reste tel quel
        // (non destructif, il repartira verbatim au serveur).
        val sansBloc = stations.first { it.idStationServeur == 2 }
        assertNull(sansBloc.anaEvalJson)
        assertEquals("Note. [ANA-EVAL] nimporte quoi [/ANA-EVAL]", sansBloc.comment)
    }

    /** Le filtre propriétaire (numérisateur OU observateur) survit à l'enrichissement du parser :
     *  la station d'autrui est écartée, celle où le compte est observateur est gardée. */
    @Test
    fun filtre_par_role_conserve_ses_stations_et_ecarte_celles_d_autrui() {
        val texte = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[1.4,42.9]},
           "properties":{"id_station": 1, "id_digitiser": 99,
                         "observers": [{"id_role": 7, "nom_complet": "DUPONT jean"}]}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[1.5,42.8]},
           "properties":{"id_station": 2, "id_digitiser": 99,
                         "observers": [{"id_role": 88, "nom_complet": "AUTRUI paul"}]}}
        ]}
        """.trimIndent()
        val stations = OccHabApi.parserFeatureCollection(texte, idRoleFiltre = 7)
        assertEquals(listOf(1), stations.map { it.idStationServeur })
    }
}
