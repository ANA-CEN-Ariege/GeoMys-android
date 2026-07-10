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
import fr.ariegenature.geomys.ui.FondChoisi
import fr.ariegenature.geomys.ui.chargerFondChoisi
import fr.ariegenature.geomys.ui.enregistrerFondChoisi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mémorisation du fond de carte choisi (SharedPreferences, partagé entre toutes les cartes) —
 *  fonds EN LIGNE et repli. Le modèle est [FondChoisi] depuis v1.2.14 (support MBTiles). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FondCartePersistanceTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sans_valeur_enregistree_renvoie_osm() {
        assertEquals(FondChoisi.EnLigne(FondCarte.OSM), chargerFondChoisi(ctx))
    }

    @Test
    fun enregistrer_puis_charger_roundtrip_en_ligne() {
        enregistrerFondChoisi(ctx, FondChoisi.EnLigne(FondCarte.ORTHO))
        assertEquals(FondChoisi.EnLigne(FondCarte.ORTHO), chargerFondChoisi(ctx))
        enregistrerFondChoisi(ctx, FondChoisi.EnLigne(FondCarte.SCAN25))
        assertEquals(FondChoisi.EnLigne(FondCarte.SCAN25), chargerFondChoisi(ctx))
    }

    @Test
    fun valeur_corrompue_retombe_sur_osm() {
        ctx.getSharedPreferences("GeoMys_prefs", Context.MODE_PRIVATE)
            .edit().putString("fond_carte", "PAS_UN_FOND").commit()
        assertEquals(FondChoisi.EnLigne(FondCarte.OSM), chargerFondChoisi(ctx))
    }

    @Test
    fun mbtiles_dont_le_fichier_a_disparu_retombe_sur_osm() {
        // Un fond MBTiles mémorisé mais dont le fichier n'existe plus → repli OSM (pas de crash).
        ctx.getSharedPreferences("GeoMys_prefs", Context.MODE_PRIVATE)
            .edit().putString("fond_carte", "mbtiles:inexistant.mbtiles").commit()
        assertEquals(FondChoisi.EnLigne(FondCarte.OSM), chargerFondChoisi(ctx))
    }
}
