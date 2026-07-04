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

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geomys.model.Denombrement
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.PointTrace
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.SaisieEnAttente
import org.junit.Assert.assertEquals
import org.junit.Test

/** Round-trip Gson des modèles PERSISTÉS (SortieStore, OutboxMonitoring) : sérialise un
 *  objet intégralement peuplé puis le relit et vérifie l'égalité structurelle. Contrat à
 *  maintenir avant toute activation de R8 : ces classes sont (dé)sérialisées par réflexion,
 *  et un renommage/suppression de champ par la minification casserait silencieusement la
 *  relecture des saisies terrain (cf. proguard-rules.pro, audit 2026-07). Tout nouveau champ
 *  d'un modèle persisté doit passer par ce test — et être couvert par une règle -keep. */
class GsonRoundTripTest {

    private val gson = Gson()

    @Test
    fun sortie_integralement_peuplee_survit_au_round_trip() {
        val obs = Observation(
            id = "obs-1",
            espece = "Turdus merula",
            taxon = Taxon.OISEAU,
            cdNom = 4001,
            latitude = 42.912345,
            longitude = 1.412345,
            geometryType = "LineString",
            geometryCoordsJson = "[[1.4,42.9],[1.5,43.0]]",
            date = 1_750_000_000_000L,
            notes = "chant entendu",
            nombre = 2,
            sexe = "2",
            stadeVie = "3",
            objDenbr = "IND",
            typDenbr = "Co",
            techniqueObs = "0",
            statutBio = "1",
            etaBio = "2",
            preuveExist = "0",
            comportement = "30",
            methDetermin = "1",
            naturalite = "1",
            determinateur = "DUPONT jean",
            releveId = "rel-uuid-1",
            nombreMax = 5,
            denombrementsAdditionnels = listOf(Denombrement(
                id = "den-1", nombreMin = 1, nombreMax = 3, sexe = "3", stadeVie = "2",
                objDenbr = "IND", typDenbr = "Co",
                mediaUris = listOf("file:///data/medias/p1.jpg"),
                additionalFields = mapOf("champ_cnt" to "42"),
            )),
            statutObs = "Pr",
            mediaUrisCounting0 = listOf("file:///data/medias/p0.jpg"),
            additionalFieldsReleve = mapOf("champ_rel" to "abc"),
            champsOccExtra = mapOf("blurring" to "5"),
            additionalFieldsOccurrence = mapOf("champ_occ" to "[\"1\",\"3\"]"),
            additionalFieldsCounting0 = mapOf("champ_c0" to "true"),
            idDatasetReleve = 12,
            observateursReleveIds = listOf(7, 8),
            observateursReleveNoms = listOf("DUPONT jean", "MARTIN paul"),
            observateurReleveId = 7,
            observateurReleveNom = "DUPONT jean",
            commentReleve = "relevé de test",
            cdHabReleve = 629,
            habitatReleveLabel = "Prairie de fauche",
            typGrpReleve = "OBS",
            dateDebutReleve = 1_749_990_000_000L,
            dateFinReleve = 1_750_000_000_000L,
            champsReleveExtra = mapOf("altitude_min" to "400", "place_name" to "Col de Port"),
            envoyeeServeur = true,
        )
        val sortie = Sortie(
            id = "sortie-1",
            date = 1_750_000_000_000L,
            pointsParcours = listOf(PointTrace(42.9, 1.4), PointTrace(42.91, 1.41)),
            observations = listOf(obs),
            distanceTotale = 1234.5,
            envoyeGeoNature = false,
            estImportee = false,
            derniereErreurEnvoi = "Envoi partiel : 1/2",
        )

        val json = gson.toJson(listOf(sortie))
        val type = object : TypeToken<MutableList<Sortie?>>() {}.type
        val relue = gson.fromJson<MutableList<Sortie?>>(json, type).filterNotNull().single()

        assertEquals(sortie, relue)
    }

    @Test
    fun saisie_en_attente_integralement_peuplee_survit_au_round_trip() {
        val saisie = SaisieEnAttente(
            uuid = "uuid-1",
            moduleCode = "STOM",
            objectType = "visit",
            parentObjectType = "site",
            parentIdServeur = 33,
            parentUuidLocal = "uuid-parent",
            parentIdField = "id_base_site",
            nomsChampsSchema = listOf("visit_date_min", "comments", "medias"),
            champsTexteLibre = listOf("comments"),
            valeursJson = """{"visit_date_min":"2026-07-04","comments":"42"}""",
            dateLocale = 1_750_000_000_000L,
            etat = SaisieEnAttente.Etat.ERROR,
            messageErreur = "Objet créé (#5) mais média(s) non envoyé(s)",
            idServeur = 5,
            objetCree = true,
            dejaTentee = true,
            uuidPayload = "uuid-payload",
            uuidFieldName = "uuid_base_visit",
            mediaPathLocal = null,
            mediaPathsLocal = listOf("file:///data/medias/v1.jpg"),
            mediaSchemaDotTable = "gn_monitoring.t_base_visits",
        )

        val json = gson.toJson(listOf(saisie))
        val type = object : TypeToken<List<SaisieEnAttente?>>() {}.type
        val relue = gson.fromJson<List<SaisieEnAttente?>>(json, type).filterNotNull().single()

        assertEquals(saisie, relue)
    }
}
