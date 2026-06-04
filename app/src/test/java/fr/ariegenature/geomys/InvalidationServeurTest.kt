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
import fr.ariegenature.geomys.network.invaliderCachesSession
import fr.ariegenature.geomys.store.NomValeur
import fr.ariegenature.geomys.store.NomenclatureCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Changement d'identité serveur ([invaliderCachesSession], appelée par l'écran de config
 *  quand URL/login/mdp changent) : les id_nomenclature sont propres à chaque instance
 *  GeoNature et [fr.ariegenature.geomys.network.GeoNatureUpload.envoyer] préfère le cache
 *  disque au réseau — un cache survivant enverrait les FK de l'ancienne instance (500
 *  opaque côté serveur). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InvalidationServeurTest {

    @Before
    fun setup() {
        NomenclatureCache.init(ApplicationProvider.getApplicationContext())
        NomenclatureCache.vider()
    }

    @Test
    fun changement_de_serveur_purge_les_nomenclatures_valeurs_et_defauts() {
        // Cache peuplé depuis le serveur A (valeurs + défauts par module).
        NomenclatureCache.setAll(mapOf("SEXE" to listOf(NomValeur(123, "mâle"))))
        NomenclatureCache.setDefauts(mapOf("STATUT_OBS" to "456"))
        assertTrue(NomenclatureCache.estDisponible)

        // L'utilisateur pointe l'app vers le serveur B.
        invaliderCachesSession()

        // Plus aucun id de l'instance A ne doit pouvoir partir dans un envoi : c'est le
        // cache vide qui force GeoNatureUpload à resynchroniser depuis l'instance B.
        assertFalse(NomenclatureCache.estDisponible)
        assertTrue(NomenclatureCache.get("SEXE").isEmpty())
        assertNull(NomenclatureCache.defautPour("STATUT_OBS"))
        assertTrue(NomenclatureCache.tousLesDefauts().isEmpty())
    }

    @Test
    fun la_purge_survit_au_redemarrage_pas_seulement_en_memoire() {
        NomenclatureCache.setAll(mapOf("SEXE" to listOf(NomValeur(123, "mâle"))))
        invaliderCachesSession()
        // Démarrage à froid simulé : ré-init depuis le disque (le memo mémoire ne doit pas
        // être le seul à avoir été purgé).
        NomenclatureCache.init(ApplicationProvider.getApplicationContext())
        assertFalse(NomenclatureCache.estDisponible)
        assertEquals(0, NomenclatureCache.count)
    }

    @Test
    fun invalidation_sans_init_prealable_ne_crashe_pas() {
        // invaliderCachesSession() peut être appelée tôt ; la purge nomenclatures doit être
        // un no-op silencieux si le cache n'est pas encore initialisé. (Robolectric ne permet
        // pas de désinitialiser l'objet : on vérifie au moins l'absence d'exception.)
        invaliderCachesSession()
    }
}