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
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Sortie
import fr.ariegenature.geomys.store.SortieStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Durcissement SortieStore (audit 2026-07) : normalisation post-Gson des vieux brouillons
 *  (champs non-nullables ajoutés au fil des versions → null via Unsafe → NPE différée à
 *  l'envoi/édition), quarantaine d'un JSON illisible (sinon écrasé par la sauvegarde
 *  suivante = perte définitive), et marquage partiel des obs envoyées. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SortieStoreNormalisationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = ctx.getSharedPreferences("sorties_store", Context.MODE_PRIVATE)

    @Before
    fun setup() {
        SortieStore.reinitialiserCacheMemoire()
        prefs.edit().clear().commit()
        File(ctx.filesDir, "sorties_store.corrupt.json").delete()
    }

    @Test
    fun vieux_brouillon_sans_les_champs_recents_est_normalise_et_copiable() {
        // JSON d'une version ancienne : Observation SANS les listes/maps introduites depuis
        // (denombrementsAdditionnels, mediaUrisCounting0, observateursReleveIds, champs
        // additionnels…). Gson les laisse à null malgré la non-nullabilité Kotlin.
        val json = """[{"id":"s1","date":1,"pointsParcours":[],
            "observations":[{"id":"o1","espece":"Merle noir","latitude":42.9,"longitude":1.4,
                             "date":1,"notes":"","nombre":2}],
            "distanceTotale":0.0,"envoyeGeoNature":false,"estImportee":false}]"""
        prefs.edit().putString("sorties_sauvegardees", json).commit()

        val o = SortieStore(ctx).charger().single().observations.single()
        // Avant le correctif : NPE ici (champ null) — c'est le chemin de GeoNatureUpload.
        assertTrue(o.denombrementsAdditionnels.isEmpty())
        assertTrue(o.mediaUrisCounting0.isEmpty())
        assertTrue(o.observateursReleveIds.isEmpty())
        assertTrue(o.additionalFieldsReleve.isEmpty())
        assertTrue(o.champsReleveExtra.isEmpty())
        assertFalse(o.envoyeeServeur)
        // Avant le correctif : copy() → checkNotNullParameter (chemin TraceViewModel, reprise
        // d'édition d'un vieux brouillon).
        assertEquals(3, o.copy(nombre = 3).nombre)
    }

    @Test
    fun json_illisible_mis_en_quarantaine_avant_d_etre_ecrase() {
        prefs.edit().putString("sorties_sauvegardees", "{pas du json[[[").commit()

        val store = SortieStore(ctx)
        assertTrue("un store illisible charge une liste vide", store.charger().isEmpty())
        val quarantaine = File(ctx.filesDir, "sorties_store.corrupt.json")
        assertTrue("le JSON d'origine doit être préservé en quarantaine", quarantaine.exists())
        assertEquals("{pas du json[[[", quarantaine.readText())

        // La sauvegarde suivante écrase la clé prefs (comportement attendu)… mais la
        // quarantaine, elle, garde le contenu d'ORIGINE (pas re-écrasée).
        store.ajouter(Sortie(id = "s2"))
        assertEquals("{pas du json[[[", quarantaine.readText())
        assertEquals("s2", SortieStore(ctx).charger().single().id)
    }

    @Test
    fun marquer_observations_envoyees_ne_touche_que_les_ids_cibles() {
        val store = SortieStore(ctx)
        val sortie = Sortie(id = "s1", observations = listOf(
            Observation(id = "a", espece = "Merle", latitude = 0.0, longitude = 0.0),
            Observation(id = "b", espece = "Grive", latitude = 0.0, longitude = 0.0),
        ))
        store.ajouter(sortie)

        store.marquerObservationsEnvoyees("s1", listOf("a"))

        // Relecture depuis le DISQUE (cache mémoire réinitialisé) : le marquage doit être durable.
        SortieStore.reinitialiserCacheMemoire()
        val relue = SortieStore(ctx).charger().single()
        assertTrue(relue.observations.first { it.id == "a" }.envoyeeServeur)
        assertFalse(relue.observations.first { it.id == "b" }.envoyeeServeur)
        assertFalse("la sortie ne doit PAS être marquée envoyée", relue.envoyeGeoNature)
    }
}
