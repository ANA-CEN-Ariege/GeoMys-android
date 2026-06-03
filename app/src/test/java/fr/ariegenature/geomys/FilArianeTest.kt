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

import fr.ariegenature.geomys.ui.FilSegment
import fr.ariegenature.geomys.ui.decoderFil
import fr.ariegenature.geomys.ui.encoderFil
import fr.ariegenature.geomys.ui.filRacineSuivis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sérialisation du fil d'Ariane des suivis dans l'argument de navigation `fil`. */
class FilArianeTest {

    @Test
    fun encode_decode_roundtrip_preserve_les_segments() {
        val segments = listOf(
            FilSegment("__accueil__", -1, "Accueil"),
            FilSegment("__suivis__", -1, "Monitoring"),
            FilSegment("site", 42, "Forêt de Foix"),
        )
        assertEquals(segments, decoderFil(encoderFil(segments)))
    }

    @Test
    fun decode_null_ou_vide_renvoie_liste_vide() {
        assertTrue(decoderFil(null).isEmpty())
        assertTrue(decoderFil("").isEmpty())
    }

    @Test
    fun label_contenant_des_caracteres_speciaux_est_preserve() {
        // Le label peut contenir espaces, chevrons, ponctuation : ne doit pas casser
        // l'encodage (les séparateurs sont des caractères de contrôle RS/US).
        val segments = listOf(FilSegment("site", 1, "Point d'écoute n°3 › zone A"))
        assertEquals(segments, decoderFil(encoderFil(segments)))
    }

    @Test
    fun racine_suivis_a_trois_niveaux_accueil_monitoring_module() {
        val racine = filRacineSuivis("STOM Ariège")
        assertEquals(3, racine.size)
        assertEquals("__accueil__", racine[0].type)
        assertEquals("__suivis__", racine[1].type)
        assertEquals("__module__", racine[2].type)
        assertEquals("STOM Ariège", racine[2].label)
    }

    @Test
    fun id_non_numerique_retombe_sur_moins_un() {
        // decoderFil tolère un id corrompu → -1 (pas de crash).
        val brut = encoderFil(listOf(FilSegment("module", 7, "X")))
        val corrompu = brut.replace("7", "abc")
        assertEquals(-1, decoderFil(corrompu).single().id)
    }
}
