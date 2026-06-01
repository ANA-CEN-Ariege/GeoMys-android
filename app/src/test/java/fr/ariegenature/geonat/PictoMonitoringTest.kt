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

import fr.ariegenature.geonat.ui.PictoMonitoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interprétation du `module_picto` d'un protocole (URL/image vs code FontAwesome → emoji). */
class PictoMonitoringTest {

    @Test
    fun urls_et_chemins_reconnus_comme_images() {
        assertTrue(PictoMonitoring.estImagePicto("https://srv/x.png"))
        assertTrue(PictoMonitoring.estImagePicto("http://srv/y"))
        assertTrue(PictoMonitoring.estImagePicto("//cdn/z.svg"))
        assertTrue(PictoMonitoring.estImagePicto("/media/pictos/a.jpg"))
        assertTrue(PictoMonitoring.estImagePicto("logo.WEBP")) // extension insensible à la casse
    }

    @Test
    fun codes_fontawesome_ne_sont_pas_des_images() {
        assertFalse(PictoMonitoring.estImagePicto("fa-bird"))
        assertFalse(PictoMonitoring.estImagePicto("leaf"))
    }

    @Test
    fun mapping_emoji_avec_prefixes_fa() {
        assertEquals("🐦", PictoMonitoring.faEnEmoji("fa-bird"))
        assertEquals("🐦", PictoMonitoring.faEnEmoji("fas-dove"))
        assertEquals("🌿", PictoMonitoring.faEnEmoji("leaf"))
        assertEquals("📍", PictoMonitoring.faEnEmoji("map-marker"))
    }

    @Test
    fun insensible_a_la_casse() {
        assertEquals("🐟", PictoMonitoring.faEnEmoji("FA-FISH"))
    }

    @Test
    fun code_inconnu_retombe_sur_le_presse_papier() {
        assertEquals("📋", PictoMonitoring.faEnEmoji("fa-licorne-cosmique"))
    }

    @Test
    fun chaine_vide_renvoie_null() {
        assertNull(PictoMonitoring.faEnEmoji(""))
    }
}
