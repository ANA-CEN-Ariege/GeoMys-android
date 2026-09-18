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

import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.GeoNatureSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RATTACHEMENT DES TAXONS AUX DIX GROUPES DE SAISIE (audit 2026-09-18, constat T5).
 *
 * Mesuré sur le cache réel de l'appareil : 901 taxons appartenaient à une liste de saisie mais à
 * aucun groupe, donc introuvables en Occtax quel que soit le bouton choisi. Ils se répartissent en
 * trois règnes : Protozoa (617, dont 524 myxomycètes), Bacteria (196, cyanobactéries) et Chromista
 * (88, algues).
 *
 * Décision produit du 2026-09-18 : les **myxomycètes** — les « champignons visqueux », que TaxRef
 * classe en Protozoa — rejoignent le groupe FONGE, parce que les naturalistes les récoltent et les
 * déterminent avec les champignons. Les cyanobactéries et les algues restent hors des dix groupes.
 */
class IndexParGroupeTest {

    private val amanite = 39155       // Fungi
    private val myxo = 50359          // Protozoa / Myxomycètes — Amaurochaete atra
    private val cyano = 800001        // Bacteria / Cyanobactéries
    private val algue = 800002        // Chromista / Algues
    private val mesange = 3764        // Animalia / Oiseaux

    private fun index() = GeoNatureSync.construireIndexTaxon(
        groupe2 = mapOf(
            amanite to "Autres", myxo to "Autres", cyano to "Autres", algue to "Autres",
            mesange to "Oiseaux",
        ),
        groupe1 = mapOf(
            amanite to "Basidiomycètes", myxo to "Myxomycètes",
            cyano to "Cyanobactéries", algue to "Algues",
        ),
        regne = mapOf(
            amanite to "Fungi", myxo to "Protozoa", cyano to "Bacteria", algue to "Chromista",
            mesange to "Animalia",
        ),
    )

    @Test
    fun les_myxomycetes_sont_dans_la_fonge_avec_les_autres_champignons() {
        val fonge = index()[Taxon.FONGE].orEmpty()
        assertTrue("le myxomycète doit être dans la fonge", myxo in fonge)
        assertTrue("et le champignon y reste", amanite in fonge)
    }

    @Test
    fun les_cyanobacteries_et_les_algues_ne_sont_pas_versees_dans_la_fonge() {
        val idx = index()
        assertFalse(cyano in idx[Taxon.FONGE].orEmpty())
        assertFalse(algue in idx[Taxon.FONGE].orEmpty())
        // Elles ne rejoignent aucun autre groupe non plus : ce n'est pas l'objet de la décision.
        idx.forEach { (taxon, membres) ->
            assertFalse("$cyano ne doit pas être dans $taxon", cyano in membres)
            assertFalse("$algue ne doit pas être dans $taxon", algue in membres)
        }
    }

    @Test
    fun les_autres_groupes_ne_sont_pas_affectes() {
        val idx = index()
        assertTrue(mesange in idx[Taxon.OISEAU].orEmpty())
        assertFalse(mesange in idx[Taxon.FONGE].orEmpty())
        // Un myxomycète n'est pas un invertébré : il n'est pas Animalia.
        assertFalse(myxo in idx[Taxon.INVERTEBRES].orEmpty())
    }
}
