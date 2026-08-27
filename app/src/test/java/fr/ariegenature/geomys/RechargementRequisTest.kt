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
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.HabitatCache
import fr.ariegenature.geomys.store.HabitatCacheOccHab
import fr.ariegenature.geomys.store.MonitoringCache
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.PictoCache
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.armerRechargementSiRequis
import fr.ariegenature.geomys.store.viderCachesSynchronises
import fr.ariegenature.geomys.ui.configurationComplete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mécanisme « rechargement des données requis après mise à jour » (2026-08-27) : au lancement,
 * une installation dont les données sont en retard sur [GeoNatureConfig.VERSION_DONNEES_REQUISE]
 * voit le drapeau ARMÉ (Paramètres forcé, bandeau) — SANS purge, qui n'a lieu qu'au début de la
 * synchro (SyncRunner) ; une installation vierge ou à jour n'est pas touchée ; idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RechargementRequisTest {

    private lateinit var ctx: Context
    private lateinit var cfg: GeoNatureConfig

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        TaxRefCache.init(ctx)
        NomenclatureCache.init(ctx)
        HabitatCache.init(ctx)
        HabitatCacheOccHab.init(ctx)
        MonitoringCache.init(ctx)
        PictoCache.init(ctx)
        TaxRefCache.vider()
        cfg = GeoNatureConfig(ctx)
        cfg.versionDonneesChargees = 0
        cfg.rechargementRequisApresMaj = false
    }

    @Test
    fun installation_vierge_ni_armement_ni_bandeau() {
        assertFalse(armerRechargementSiRequis(ctx))
        assertFalse(cfg.rechargementRequisApresMaj)
    }

    @Test
    fun donnees_en_retard_arme_le_drapeau_sans_purger_et_une_seule_fois() {
        TaxRefCache.versionSauvegardee = "taxref-v17"
        assertTrue(armerRechargementSiRequis(ctx))
        assertTrue(cfg.rechargementRequisApresMaj)
        assertEquals("données conservées jusqu'à la synchro (purge dans SyncRunner)",
            "taxref-v17", TaxRefCache.versionSauvegardee)
        assertFalse("drapeau armé ⇒ configuration incomplète ⇒ Paramètres forcé", configurationComplete(cfg))
        assertFalse("déjà armé → idempotent (relance, synchro en cours…)", armerRechargementSiRequis(ctx))
        assertTrue("le drapeau reste armé jusqu'à la synchro", cfg.rechargementRequisApresMaj)
    }

    @Test
    fun donnees_a_jour_rien_ne_se_passe() {
        TaxRefCache.versionSauvegardee = "taxref-v17"
        cfg.versionDonneesChargees = GeoNatureConfig.VERSION_DONNEES_REQUISE
        assertFalse(armerRechargementSiRequis(ctx))
        assertEquals("taxref-v17", TaxRefCache.versionSauvegardee)
        assertFalse(cfg.rechargementRequisApresMaj)
    }

    @Test
    fun purge_des_caches_synchronises_vide_taxref() {
        TaxRefCache.versionSauvegardee = "taxref-v17"
        viderCachesSynchronises()
        assertNull(TaxRefCache.versionSauvegardee)
    }
}
