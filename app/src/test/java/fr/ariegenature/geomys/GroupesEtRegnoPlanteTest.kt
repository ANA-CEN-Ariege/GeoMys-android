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
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.ui.saisie.ChampsTaxon
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Branche PLANTE de groupesEtRegno : intersection des groupes botaniques connus avec ceux
 *  réellement présents dans le cache TaxRef (cf. la discussion sur sa dépendance aux prefs —
 *  d'où Robolectric ici, contrairement aux autres taxons testés en JVM pur). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupesEtRegnoPlanteTest {

    @Before
    fun setup() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun plante_intersecte_avec_les_groupes_presents_dans_le_cache() {
        // Cache contenant un groupe botanique connu + un groupe animal hors botanique.
        TaxRefCache.comptesGroupes = mapOf("Angiospermes" to 10, "Oiseaux" to 5)
        val (groupes, regno) = ChampsTaxon.groupesEtRegno(Taxon.PLANTE, "")
        assertEquals(setOf("Angiospermes"), groupes) // "Oiseaux" écarté (non botanique)
        assertEquals("Plantae", regno)
    }

    @Test
    fun plante_fallback_sur_tous_les_groupes_si_cache_vide() {
        TaxRefCache.comptesGroupes = emptyMap()
        val (groupes, regno) = ChampsTaxon.groupesEtRegno(Taxon.PLANTE, "")
        assertEquals(NomenclatureCache.GROUPES_BOTANIQUES, groupes)
        assertEquals("Plantae", regno)
    }
}
