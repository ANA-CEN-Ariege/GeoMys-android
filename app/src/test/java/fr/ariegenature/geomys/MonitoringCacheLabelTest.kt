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
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.store.MonitoringCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Résolution des labels d'objets/types monitoring depuis le cache local (combine lecture
 *  fichier + aplatissement des properties + heuristique de nom). Offline, pas de réseau. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringCacheLabelTest {

    @Before
    fun setup() {
        MonitoringCache.init(ApplicationProvider.getApplicationContext())
        MonitoringCache.vider()
    }

    @Test
    fun label_objet_resolu_depuis_les_properties_en_cache() {
        MonitoringCache.setJson(
            MonitoringCache.keyObjet("stom", "site", 5),
            """{"properties":{"base_site_name":"Forêt de Foix"}}""",
        )
        assertEquals("Forêt de Foix", MonitoringApi.labelObjetEnCache("stom", "site", 5))
    }

    @Test
    fun label_objet_absent_du_cache_renvoie_null() {
        assertNull(MonitoringApi.labelObjetEnCache("stom", "site", 999))
    }

    @Test
    fun label_objet_sans_nom_exploitable_renvoie_null() {
        // Pas de champ nom → l'heuristique tombe sur "#id", filtré → null.
        MonitoringCache.setJson(
            MonitoringCache.keyObjet("stom", "site", 6),
            """{"properties":{"altitude":1200}}""",
        )
        assertNull(MonitoringApi.labelObjetEnCache("stom", "site", 6))
    }

    @Test
    fun id_invalide_renvoie_null() {
        assertNull(MonitoringApi.labelObjetEnCache("stom", "site", 0))
    }

    @Test
    fun label_type_depuis_le_schema_en_cache() {
        MonitoringCache.setJson(
            MonitoringCache.keySchema("stom"),
            """{"site":{"label":"Site"},"visit":{"label_list":"Visites"}}""",
        )
        assertEquals("Site", MonitoringApi.labelTypeEnCache("stom", "site"))
        // Repli sur label_list quand label est absent.
        assertEquals("Visites", MonitoringApi.labelTypeEnCache("stom", "visit"))
    }

    @Test
    fun label_type_inconnu_ou_schema_absent_renvoie_null() {
        assertNull(MonitoringApi.labelTypeEnCache("stom", "inconnu"))
        assertNull(MonitoringApi.labelTypeEnCache("module_sans_schema", "site"))
    }
}
