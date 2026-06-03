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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.ui.FondCarte
import fr.ariegenature.geomys.ui.chargerFondCarte
import fr.ariegenature.geomys.ui.enregistrerFondCarte
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mémorisation du fond de carte choisi (SharedPreferences, partagé entre toutes les cartes). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FondCartePersistanceTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sans_valeur_enregistree_renvoie_le_defaut() {
        assertEquals(FondCarte.OSM, chargerFondCarte(ctx, FondCarte.OSM))
        assertEquals(FondCarte.TOPO, chargerFondCarte(ctx, FondCarte.TOPO))
    }

    @Test
    fun enregistrer_puis_charger_roundtrip() {
        enregistrerFondCarte(ctx, FondCarte.ORTHO)
        assertEquals(FondCarte.ORTHO, chargerFondCarte(ctx, FondCarte.OSM))
        // Le défaut passé est ignoré dès qu'une valeur est mémorisée.
        enregistrerFondCarte(ctx, FondCarte.SCAN25)
        assertEquals(FondCarte.SCAN25, chargerFondCarte(ctx, FondCarte.OSM))
    }

    @Test
    fun valeur_corrompue_retombe_sur_le_defaut() {
        ctx.getSharedPreferences("GeoMys_prefs", Context.MODE_PRIVATE)
            .edit().putString("fond_carte", "PAS_UN_FOND").commit()
        assertEquals(FondCarte.TOPO, chargerFondCarte(ctx, FondCarte.TOPO))
    }
}
