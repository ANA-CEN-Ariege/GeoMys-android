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
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.model.OccHabStation
import fr.ariegenature.geomys.store.OccHabStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Store des SAISIES OccHab : sauvegarde au fil de l'eau d'une station dans sa saisie
 * ([OccHabStore.upsertStation]), état DÉRIVÉ de la saisie (envoyée ssi toutes ses stations le
 * sont), suppression cascade / par station.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OccHabSaisieStoreTest {

    private lateinit var store: OccHabStore

    @Before
    fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        OccHabStore.reinitialiserCacheMemoire()
        ctx.getSharedPreferences("occhab_store", Context.MODE_PRIVATE).edit().clear().commit()
        store = OccHabStore(ctx)
    }

    private fun station(id: String) = OccHabStation(
        id = id, habitats = listOf(OccHabHabitat(cdHab = 1, habitatLabel = "Habitat $id")),
    )

    @Test
    fun upsert_cree_la_saisie_puis_ajoute_les_stations() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        val saisies = store.charger()
        assertEquals(1, saisies.size)
        assertEquals("sa", saisies.single().id)
        assertEquals(listOf("A", "B"), store.stationsDeSaisie("sa").map { it.id })
    }

    @Test
    fun upsert_remplace_une_station_existante_par_son_id() {
        store.upsertStation("sa", station("A").copy(stationName = "v1"))
        store.upsertStation("sa", station("A").copy(stationName = "v2"))
        assertEquals(1, store.stationsDeSaisie("sa").size)
        assertEquals("v2", store.stationsDeSaisie("sa").single().stationName)
    }

    @Test
    fun saisie_envoyee_seulement_quand_toutes_les_stations_le_sont() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        store.marquerStationEnvoyee("sa", "A", 100)
        assertFalse("1/2 envoyée → saisie pas envoyée", store.charger().single().envoyeGeoNature)
        store.marquerStationEnvoyee("sa", "B", 101)
        assertTrue("2/2 envoyées → saisie envoyée", store.charger().single().envoyeGeoNature)
        assertNull(store.charger().single().derniereErreurEnvoi)
        assertTrue(store.stationsDeSaisie("sa").all { it.envoyeGeoNature })
        assertEquals(100, store.stationsDeSaisie("sa").first { it.id == "A" }.idStationServeur)
    }

    // Invariant 2026-08-26 : une station serveur n'a jamais plus d'UNE copie locale à envoyer —
    // la carte consulte cet index avant tout import / remise en édition.
    @Test
    fun copie_locale_non_envoyee_indexee_par_id_serveur_toutes_saisies() {
        store.upsertStation("sa", station("A").copy(idStationServeur = 42))
        store.upsertStation("sb", station("B").copy(idStationServeur = 43, envoyeGeoNature = true))
        store.upsertStation("sb", station("C")) // locale pure (sans id serveur) : hors index
        store.upsertStation("sb", station("D").copy(idStationServeur = 44, envoiIncertain = true))
        val copie = store.copieLocaleNonEnvoyee(42)
        assertEquals("sa", copie?.first?.id)
        assertEquals("A", copie?.second?.id)
        assertNull("copie déjà envoyée → plus en attente", store.copieLocaleNonEnvoyee(43))
        assertEquals("envoi incertain = toujours en attente", "D", store.copieLocaleNonEnvoyee(44)?.second?.id)
        assertNull(store.copieLocaleNonEnvoyee(99))
        assertEquals(setOf(42, 44), store.copiesLocalesNonEnvoyees().keys)
    }

    @Test
    fun copie_locale_disparait_une_fois_envoyee_et_revient_a_la_remise_en_edition() {
        store.upsertStation("sa", station("A").copy(idStationServeur = 42))
        store.marquerStationEnvoyee("sa", "A", 42)
        assertNull(store.copieLocaleNonEnvoyee(42))
        // Remise « à envoyer » (Modifier une station déjà envoyée) : redevient LA copie en attente.
        store.upsertStation("sa", store.stationsDeSaisie("sa").single().copy(envoyeGeoNature = false))
        assertEquals("A", store.copieLocaleNonEnvoyee(42)?.second?.id)
        // Doublon hérité d'avant l'invariant : la saisie la plus récente (en tête) fait foi.
        store.upsertStation("sb", station("A2").copy(idStationServeur = 42))
        assertEquals("sb", store.copieLocaleNonEnvoyee(42)?.first?.id)
    }

    // Demande terrain 2026-08-27 : une station importée du serveur (ou remise en édition) n'entre
    // dans « Mes stations » qu'à la PREMIÈRE modification réelle — garde dans upsertStation.
    @Test
    fun station_importee_intacte_n_entre_pas_dans_mes_stations_avant_la_premiere_modif() {
        val importee = station("A").copy(idStationServeur = 42, stationName = "Tourbière",
            geometryType = "Polygon", geometryCoordsJson = "[[1.4,42.9],[1.5,42.9],[1.5,43.0]]")
        val origine = importee.copy(empreinteOrigine = importee.empreinteContenu())
        assertTrue("ignorée = succès", store.upsertStation("sa", origine))
        assertTrue(store.stationsDeSaisie("sa").isEmpty())
        // Champs DÉRIVÉS recalculés à « Valider » (surface, altitudes, centroïde) : toujours intacte.
        assertTrue(store.upsertStation("sa", origine.copy(
            surface = 999L, altitudeMin = 1, altitudeMax = 2, latitude = 42.95, longitude = 1.45)))
        assertTrue(store.stationsDeSaisie("sa").isEmpty())
        assertNull(store.copieLocaleNonEnvoyee(42))
        // 1ʳᵉ modification réelle → persistée, à envoyer.
        assertTrue(store.upsertStation("sa", origine.copy(stationName = "Tourbière du col")))
        assertEquals(listOf("A"), store.stationsDeSaisie("sa").map { it.id })
        assertFalse(store.stationsDeSaisie("sa").single().envoyeGeoNature)
        assertNotNull(store.copieLocaleNonEnvoyee(42))
        // Retour au contenu d'origine (Annuler…) : plus rien à envoyer → la copie d'import QUITTE
        // « Mes stations » (demande terrain 2026-08-30) et la station redevient importable.
        assertTrue(store.upsertStation("sa", origine))
        assertTrue(store.stationsDeSaisie("sa").isEmpty())
        assertNull(store.copieLocaleNonEnvoyee(42))
        // …et une nouvelle modification la fait rentrer à nouveau.
        assertTrue(store.upsertStation("sa", origine.copy(comment = "2e passage")))
        assertEquals(listOf("A"), store.stationsDeSaisie("sa").map { it.id })
    }

    @Test
    fun copie_envoyee_remise_en_edition_puis_revenue_a_l_origine_redevient_envoyee() {
        store.upsertStation("sa", station("A").copy(idStationServeur = 42))
        store.marquerStationEnvoyee("sa", "A", 42)
        val envoyee = store.stationsDeSaisie("sa").single()
        // Remise en édition (carte) : copie « à envoyer » dès la 1ʳᵉ modification…
        val modifiable = envoyee.copy(envoyeGeoNature = false, origineEnvoyee = true,
            empreinteOrigine = envoyee.empreinteContenu())
        assertTrue(store.upsertStation("sa", modifiable.copy(comment = "x")))
        assertFalse(store.stationsDeSaisie("sa").single().envoyeGeoNature)
        // …revenue à l'identique : elle REDEVIENT envoyée (pas retirée, pas « à envoyer »).
        assertTrue(store.upsertStation("sa", modifiable))
        val apres = store.stationsDeSaisie("sa").single()
        assertTrue(apres.envoyeGeoNature)
        assertNull(store.copieLocaleNonEnvoyee(42))
        assertTrue("l'origine reste connue pour la suite de l'édition", store.estIntacte("sa", modifiable))
    }

    @Test
    fun copie_revenue_a_l_origine_apres_un_envoi_tente_reste_a_envoyer() {
        val importee = station("A").copy(idStationServeur = 42)
        val origine = importee.copy(empreinteOrigine = importee.empreinteContenu())
        store.upsertStation("sa", origine.copy(comment = "x"))
        store.marquerStationIncertain("sa", "A", "réseau coupé") // le serveur a peut-être reçu « x »
        assertFalse(store.estIntacte("sa", origine))
        assertTrue(store.upsertStation("sa", origine))
        val apres = store.stationsDeSaisie("sa").single()
        assertFalse("reste à envoyer : renverra le contenu d'origine en mise à jour", apres.envoyeGeoNature)
        assertEquals("A", store.copieLocaleNonEnvoyee(42)?.second?.id)
    }

    @Test
    fun est_intacte_suit_exactement_la_garde_d_upsert() {
        val importee = station("A").copy(idStationServeur = 42)
        val origine = importee.copy(empreinteOrigine = importee.empreinteContenu())
        assertTrue(store.estIntacte("sa", origine))
        assertFalse("modifiée", store.estIntacte("sa", origine.copy(comment = "x")))
        assertFalse("locale (sans empreinte)", store.estIntacte("sa", importee))
        store.upsertStation("sa", origine.copy(comment = "x")) // désormais « à envoyer »
        assertTrue("revenue à l'identique sans envoi tenté → plus rien à envoyer", store.estIntacte("sa", origine))
    }

    @Test
    fun station_envoyee_remise_en_edition_reste_envoyee_tant_qu_intacte() {
        store.upsertStation("sa", station("A").copy(idStationServeur = 42))
        store.marquerStationEnvoyee("sa", "A", 42)
        val envoyee = store.stationsDeSaisie("sa").single()
        assertNull("effacée à l'envoi confirmé", envoyee.empreinteOrigine)
        val modifiable = envoyee.copy(envoyeGeoNature = false, empreinteOrigine = envoyee.empreinteContenu())
        assertTrue(store.upsertStation("sa", modifiable))
        assertTrue("intacte → la copie envoyée reste envoyée", store.stationsDeSaisie("sa").single().envoyeGeoNature)
        assertNull(store.copieLocaleNonEnvoyee(42))
        assertTrue(store.upsertStation("sa", modifiable.copy(comment = "revu sur le terrain")))
        assertFalse(store.stationsDeSaisie("sa").single().envoyeGeoNature)
        assertEquals("A", store.copieLocaleNonEnvoyee(42)?.second?.id)
    }

    @Test
    fun empreinte_contenu_ignore_les_champs_derives_et_le_formatage_des_coordonnees() {
        val poly = station("P").copy(geometryType = "Polygon",
            geometryCoordsJson = "[[1.40,42.90],[1.5,42.9],[1.5,43.0]]", latitude = 42.9, longitude = 1.4)
        val e = poly.empreinteContenu()
        assertEquals(e, poly.copy(
            geometryCoordsJson = "[[1.4,42.9],[1.5,42.9],[1.5,43]]", latitude = 0.0, longitude = 0.0,
            surface = 5L, altitudeMin = 3, altitudeMax = 9,
            envoyeGeoNature = true, envoiIncertain = true, derniereErreurEnvoi = "x",
            empreinteOrigine = "autre",
        ).empreinteContenu())
        assertNotEquals(e, poly.copy(geometryCoordsJson = "[[1.4,42.9],[1.5,42.9],[1.5,43.1]]").empreinteContenu())
        assertNotEquals(e, poly.copy(habitats = poly.habitats + OccHabHabitat(cdHab = 2)).empreinteContenu())
        assertNotEquals(e, poly.copy(habitats = listOf(poly.habitats.single().copy(recouvrement = 50.0))).empreinteContenu())
        assertNotEquals(e, poly.copy(dateMin = 1L).empreinteContenu())
        assertNotEquals(e, poly.copy(observateursIds = listOf(3)).empreinteContenu())
        assertNotEquals(e, poly.copy(anaEvalJson = "{\"enjeu\":\"fort\"}").empreinteContenu())
        // Point : la position compte.
        val pt = station("Q").copy(latitude = 42.9, longitude = 1.4)
        assertNotEquals(pt.empreinteContenu(), pt.copy(latitude = 42.91).empreinteContenu())
    }

    @Test
    fun erreur_station_remonte_au_niveau_saisie() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        store.marquerStationEnvoyee("sa", "A", 100)
        store.marquerStationErreur("sa", "B", "Réseau interrompu")
        val saisie = store.charger().single()
        assertFalse(saisie.envoyeGeoNature)
        assertEquals("Réseau interrompu", saisie.derniereErreurEnvoi)
    }

    @Test
    fun supprimer_station_retire_puis_supprime_la_saisie_vide() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sa", station("B"))
        store.supprimerStation("sa", "A")
        assertEquals(listOf("B"), store.stationsDeSaisie("sa").map { it.id })
        store.supprimerStation("sa", "B")
        assertTrue("saisie vide supprimée", store.charger().isEmpty())
    }

    @Test
    fun supprimer_saisie_cascade() {
        store.upsertStation("sa", station("A"))
        store.upsertStation("sb", station("C"))
        store.supprimer("sa")
        assertEquals(listOf("sb"), store.charger().map { it.id })
    }

    @Test
    fun persistance_relecture_a_froid() {
        store.upsertStation("sa", station("A"))
        store.marquerStationEnvoyee("sa", "A", 100)
        OccHabStore.reinitialiserCacheMemoire() // force la relecture disque
        val relu = OccHabStore(ApplicationProvider.getApplicationContext())
        assertEquals(1, relu.charger().size)
        assertTrue(relu.charger().single().envoyeGeoNature)
        assertEquals("A", relu.stationsDeSaisie("sa").single().id)
    }
}
