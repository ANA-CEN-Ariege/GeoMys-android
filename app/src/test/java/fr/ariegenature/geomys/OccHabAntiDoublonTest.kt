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

import fr.ariegenature.geomys.network.OccHabUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Anti-doublon OccHab : la station porte un `unique_id_sinp_station` stable ; après un envoi
 * INCERTAIN (réponse perdue), le ré-envoi retrouve la station déjà créée par cet UUID au lieu
 * d'en poster une seconde. On verrouille ici la fonction de matching sur les DEUX formats que
 * le serveur OccHab peut renvoyer (`format=json` = tableau plat ; repli GeoJSON = features).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabAntiDoublonTest {

    private val uuid = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    // Réponse GET /stations/?format=json : tableau d'objets, id + unique_id_sinp_station à plat.
    private val reponseJson = """
        [
          {"id_station": 11, "unique_id_sinp_station": "aaaaaaaa-0000-0000-0000-000000000000"},
          {"id_station": 42, "unique_id_sinp_station": "$uuid"}
        ]
    """.trimIndent()

    // Repli GeoJSON (FeatureCollection) : l'UUID est dans properties.
    private val reponseGeojson = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"id_station": 42, "unique_id_sinp_station": "$uuid"}}
        ]}
    """.trimIndent()

    @Test
    fun `format json — station trouvee par UUID`() {
        assertEquals(42, OccHabUpload.trouverIdParUuid(reponseJson, uuid))
    }

    @Test
    fun `repli geojson — station trouvee par UUID`() {
        assertEquals(42, OccHabUpload.trouverIdParUuid(reponseGeojson, uuid))
    }

    @Test
    fun `comparaison UUID insensible a la casse`() {
        assertEquals(42, OccHabUpload.trouverIdParUuid(reponseJson, uuid.uppercase()))
    }

    @Test
    fun `UUID absent — aucun match (on postera)`() {
        assertNull(OccHabUpload.trouverIdParUuid(reponseJson, "99999999-9999-9999-9999-999999999999"))
    }

    @Test
    fun `liste vide — aucun match`() {
        assertNull(OccHabUpload.trouverIdParUuid("[]", uuid))
    }

    @Test
    fun `reponse illisible — aucun match plutot que crash`() {
        assertNull(OccHabUpload.trouverIdParUuid("<html>502 Bad Gateway</html>", uuid))
    }
}
