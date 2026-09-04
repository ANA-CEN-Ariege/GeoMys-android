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
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.OccHabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * ROUND-TRIP DISQUE des stations OccHab : une station relue après redémarrage doit être
 * IDENTIQUE à celle qui a été écrite, champ par champ — habitats compris.
 *
 * `OccHabStore.normaliserStation` reconstruit chaque station par constructeur EXPLICITE (elle
 * doit tolérer des null venus d'un vieux JSON) : un champ ajouté au modèle et oublié là est
 * silencieusement remis à son défaut à la première relecture du disque. Cas réel (2026-09-03) :
 * `geometryTrousJson` oublié ⇒ une station à trou perdait son anneau intérieur au redémarrage,
 * et l'envoi l'aurait SUPPRIMÉ côté serveur — exactement le bug que ce champ corrigeait.
 * Contrôle PAR RÉFLEXION, pour que l'oubli suivant échoue ici et pas sur le terrain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabStoreRoundTripTest {

    private lateinit var store: OccHabStore

    @Before
    fun setup() {
        val ctx: android.content.Context = ApplicationProvider.getApplicationContext()
        OccHabStore.reinitialiserCacheMemoire()
        ctx.getSharedPreferences("occhab_store", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = OccHabStore(ctx)
    }

    /** Habitat dont chaque champ porte une valeur non triviale. */
    private fun habitatComplet() = OccHabHabitat(
        id = "h-1",
        idHabitatServeur = 77,
        uuidHabitat = "uuid-hab",
        cdHab = 1234,
        habitatLabel = "Forêts de Fagus",
        nomCite = "Hêtraie",
        determiner = "DUPONT jean",
        recouvrement = 12.5,
        precisionTechnique = "relevé partiel",
        anaEvalJson = """{"typicite":"bonne"}""",
        idNomTypeDetermination = 1,
        idNomTechniqueCollecte = 2,
        idNomAbondance = 3,
        idNomSensibilite = 4,
        idNomInteretCommunautaire = 5,
    )

    /** Station dont chaque champ porte une valeur non triviale — un champ perdu au round-trip
     *  devient donc forcément détectable. */
    private fun stationComplete() = OccHabStation(
        id = "s-1",
        uuidStation = "uuid-station",
        idStationServeur = 2805,
        date = 1_700_000_000_000L,
        geometryType = "Polygon",
        latitude = 42.96895,
        longitude = 1.61010,
        geometryCoordsJson = "[[1.6095,42.9678],[1.6108,42.9678],[1.6108,42.9694]]",
        geometryTrousJson = "[[[1.61,42.96908],[1.61025,42.96897],[1.61035,42.96871]]]",
        geometryPartielle = true,
        idDataset = 12,
        observateursIds = listOf(7, 8),
        observateursNoms = listOf("DUPONT jean", "MARTIN paul"),
        observateursTxt = "DUPONT jean",
        stationName = "Tourbière",
        comment = "Pelouse pâturée",
        anaEvalJson = """{"enjeu":"fort"}""",
        dateMin = 1_700_000_000_000L,
        dateMax = 1_700_003_600_000L,
        altitudeMin = 800,
        altitudeMax = 850,
        profondeurMin = 1,
        profondeurMax = 2,
        surface = 9745L,
        precision = 5,
        idNomExposition = 21,
        idNomCalculSurface = 22,
        idNomObjetGeographique = 23,
        idNomTypeSol = 24,
        idNomTypeMosaique = 25,
        habitats = listOf(habitatComplet()),
        envoyeGeoNature = true,
        origineServeur = true,
        derniereErreurEnvoi = "boum",
        envoiIncertain = true,
        empreinteOrigine = "empreinte",
        origineEnvoyee = true,
    )

    /** Force une relecture DEPUIS LE DISQUE (ce que fait un redémarrage de l'appli). */
    private fun relireDepuisLeDisque(): OccHabStation {
        OccHabStore.reinitialiserCacheMemoire()
        val frais = OccHabStore(ApplicationProvider.getApplicationContext())
        return frais.stationsDeSaisie("saisie-1").single()
    }

    private fun champsDe(classe: Class<*>) = classe.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.name.contains('$') }

    @Test
    fun tous_les_champs_d_une_station_survivent_a_un_redemarrage() {
        val ecrite = stationComplete()
        assertTrue(store.upsertStation("saisie-1", ecrite))
        val relue = relireDepuisLeDisque()

        val champs = champsDe(OccHabStation::class.java).filter { it.name != "habitats" }
        assertTrue("le modèle doit exposer des champs", champs.isNotEmpty())
        for (champ in champs) {
            champ.isAccessible = true
            assertEquals(
                "champ station « ${champ.name} » PERDU à la relecture du disque — l'ajouter à " +
                    "OccHabStore.normaliserStation()",
                champ.get(ecrite), champ.get(relue),
            )
        }
    }

    @Test
    fun tous_les_champs_d_un_habitat_survivent_a_un_redemarrage() {
        store.upsertStation("saisie-1", stationComplete())
        val relu = relireDepuisLeDisque().habitats.single()
        val attendu = habitatComplet()
        for (champ in champsDe(OccHabHabitat::class.java)) {
            champ.isAccessible = true
            assertEquals(
                "champ habitat « ${champ.name} » PERDU à la relecture du disque — l'ajouter à " +
                    "OccHabStore.normaliserHabitat()",
                champ.get(attendu), champ.get(relu),
            )
        }
    }

    @Test
    fun le_trou_d_un_polygone_survit_a_un_redemarrage() {
        // Le cas terrain : sans le correctif, la station repartait SANS son anneau intérieur
        // et l'envoi l'aurait supprimé côté GeoNature.
        store.upsertStation("saisie-1", stationComplete().copy(geometryPartielle = false))
        val relue = relireDepuisLeDisque()
        assertEquals(
            "[[[1.61,42.96908],[1.61025,42.96897],[1.61035,42.96871]]]",
            relue.geometryTrousJson,
        )
    }
}
