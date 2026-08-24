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
import android.content.ContextWrapper
import android.content.SharedPreferences
import fr.ariegenature.geomys.network.GNErreur
import fr.ariegenature.geomys.network.GeoNatureAuth
import fr.ariegenature.geomys.network.GeoNatureBrowse
import fr.ariegenature.geomys.network.GeoNatureDataset
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Navigation GeoNature ([GeoNatureBrowse]) via MockWebServer : parsing des jeux de données,
 * des listes de taxons et des observateurs + construction des requêtes (chemins, méthode,
 * en-tête Bearer, corps POST) et comportement sur erreur (HTTP 500, JSON malformé).
 *
 * Tests JVM PURS (pas de Robolectric) : [GeoNatureConfig] exige un Context uniquement pour ses
 * SharedPreferences — on lui fournit un [FauxContexte] (ContextWrapper sur préférences en
 * mémoire), possible grâce à `unitTests.isReturnDefaultValues = true` (constructeur stub muet).
 * Le mot de passe suit alors le repli « mémoire seule » de GeoNatureConfig (Keystore absent en
 * JVM) — exactement le chemin de production quand le Keystore est KO.
 */
class GeoNatureBrowseTest {

    private lateinit var server: MockWebServer
    private lateinit var config: GeoNatureConfig

    /** Chemins des requêtes reçues par le serveur mock (ordre d'arrivée). */
    private val chemins: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Réponse du POST /api/auth/login (surchargée par les tests d'échec d'auth). */
    private var reponseLogin: () -> MockResponse = {
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"access_token":"t","user":{"id_role":1}}""")
    }

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        // Le cache d'auth (TTL 5 min) est statique process-wide : on repart propre à chaque test.
        GeoNatureAuth.invaliderCache()
        config = GeoNatureConfig(FauxContexte()).apply {
            urlServeur = server.url("/").toString().trimEnd('/')
            login = "alice"
            motDePasse = "pwd"
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        GeoNatureAuth.invaliderCache()
    }

    /** Routage par préfixe de chemin ; le login est servi d'office via [reponseLogin]. */
    private fun router(vararg routes: Pair<String, () -> MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val chemin = request.path.orEmpty()
                chemins.add(chemin)
                if (chemin.startsWith("/api/auth/login")) return reponseLogin()
                routes.firstOrNull { chemin.startsWith(it.first) }?.let { return it.second() }
                return MockResponse().setResponseCode(404)
            }
        }
    }

    private fun json(corps: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(corps)

    // ------------------------------------------------------------------ chargerDatasets

    @Test
    fun `datasets — parsing complet, entrees invalides ignorees, tri francais`() {
        router(
            "/api/meta/datasets" to {
                json(
                    """
                    [
                      {"id_dataset": 2, "dataset_name": "Zoo", "active": false, "id_taxa_list": 0,
                       "modules": [{"module_code": "OCCTAX"}, {"module_code": "MONITORINGS"}]},
                      {"id_dataset": 1, "dataset_name": "Épervier", "active": true, "id_taxa_list": 100,
                       "modules": ["OCCTAX"]},
                      {"id_dataset": 3, "dataset_name": "abeille"},
                      {"dataset_name": "sans id"},
                      {"id_dataset": 9}
                    ]
                    """.trimIndent(),
                )
            },
        )

        val datasets = runBlocking { GeoNatureBrowse.chargerDatasets(config) }

        // Entrées sans id ou sans nom écartées ; tri Collator France (accents + casse ignorés).
        assertEquals(listOf("abeille", "Épervier", "Zoo"), datasets.map { it.nom })

        val epervier = datasets.first { it.nom == "Épervier" }
        assertEquals(1, epervier.id)
        assertEquals(100, epervier.idTaxaList)
        assertTrue(epervier.actif)
        assertEquals(listOf("OCCTAX"), epervier.moduleCodes) // format tableau de chaînes

        val zoo = datasets.first { it.nom == "Zoo" }
        assertFalse("active=false conservé (filtrage à l'affichage)", zoo.actif)
        assertNull("id_taxa_list=0 → pas de liste imposée", zoo.idTaxaList)
        assertEquals(listOf("OCCTAX", "MONITORINGS"), zoo.moduleCodes) // format objets module_code

        val abeille = datasets.first { it.nom == "abeille" }
        assertTrue("active absent → actif par défaut", abeille.actif)
        assertTrue(abeille.moduleCodes.isEmpty())

        // Construction des requêtes : login PUIS GET filtré + fields=modules, avec le Bearer.
        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/api/auth/login", login.path)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/meta/datasets?active=true&module_code=OCCTAX&fields=modules", get.path)
        assertEquals("Bearer t", get.getHeader("Authorization"))
    }

    @Test
    fun `datasets — reponse enveloppee sous data acceptee`() {
        router("/api/meta/datasets" to { json("""{"data":[{"id_dataset":3,"dataset_name":"Alpha"}]}""") })
        val datasets = runBlocking { GeoNatureBrowse.chargerDatasets(config) }
        assertEquals(listOf(GeoNatureDataset(3, "Alpha")), datasets)
    }

    @Test
    fun `datasets — module_code propage dans l'URL`() {
        router("/api/meta/datasets" to { json("[]") })
        runBlocking { GeoNatureBrowse.chargerDatasets(config, moduleCode = "OCCHAB") }
        assertTrue(chemins.any { it == "/api/meta/datasets?active=true&module_code=OCCHAB&fields=modules" })
    }

    @Test
    fun `datasets — HTTP 500 leve EnvoiEchoue`() {
        router("/api/meta/datasets" to { MockResponse().setResponseCode(500) })
        try {
            runBlocking { GeoNatureBrowse.chargerDatasets(config) }
            fail("EnvoiEchoue attendue")
        } catch (e: GNErreur.EnvoiEchoue) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `datasets — login refuse leve AuthEchouee`() {
        reponseLogin = { MockResponse().setResponseCode(401) }
        router("/api/meta/datasets" to { json("[]") })
        try {
            runBlocking { GeoNatureBrowse.chargerDatasets(config) }
            fail("AuthEchouee attendue")
        } catch (e: GNErreur.AuthEchouee) {
            assertEquals(401, e.code)
        }
        assertTrue("le endpoint datasets ne doit pas être appelé sans auth", chemins.none { it.startsWith("/api/meta") })
    }

    // ------------------------------------------------------------------ chargerIdsDatasetsCreables

    @Test
    fun `datasets creables — POST create=module et ids extraits`() {
        router("/api/meta/datasets" to { json("""[{"id_dataset":5,"dataset_name":"A"},{"id_dataset":7,"dataset_name":"B"}]""") })
        val ids = runBlocking { GeoNatureBrowse.chargerIdsDatasetsCreables(config) }
        assertEquals(setOf(5, 7), ids)

        server.takeRequest() // login
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/meta/datasets?active=true", post.path)
        // Même filtre CRUVED « C » que le formulaire web (cf. gn_mobile_core).
        assertEquals("""{"create":"OCCTAX"}""", post.body.readUtf8())
    }

    @Test
    fun `datasets creables — HTTP 500 renvoie un set vide (filet, pas de restriction)`() {
        router("/api/meta/datasets" to { MockResponse().setResponseCode(500) })
        assertTrue(runBlocking { GeoNatureBrowse.chargerIdsDatasetsCreables(config) }.isEmpty())
    }

    @Test
    fun `datasets creables — login refuse renvoie un set vide sans appel au endpoint`() {
        reponseLogin = { MockResponse().setResponseCode(401) }
        router("/api/meta/datasets" to { json("[]") })
        assertTrue(runBlocking { GeoNatureBrowse.chargerIdsDatasetsCreables(config) }.isEmpty())
        assertTrue(chemins.none { it.startsWith("/api/meta") })
    }

    // ------------------------------------------------------------------ chargerListesTaxons

    @Test
    fun `listes taxons — parsing, tri par nom et URL TaxHub deduite`() {
        router(
            "/api/taxhub/api/biblistes" to {
                json(
                    """
                    {"data":[
                      {"id_liste": 2, "nom_liste": "Oiseaux"},
                      {"id_liste": 1, "nom_liste": "Flore"},
                      {"id_liste": -1, "nom_liste": "invalide"},
                      {"nom_liste": "sans id"}
                    ]}
                    """.trimIndent(),
                )
            },
        )
        val listes = runBlocking { GeoNatureBrowse.chargerListesTaxons(config) }
        assertEquals(listOf(1 to "Flore", 2 to "Oiseaux"), listes.map { it.id to it.nom })
        // taxhubUrlCache vide → base déduite `<serveur>/api/taxhub` (endpoint public, sans auth).
        assertEquals(listOf("/api/taxhub/api/biblistes"), chemins)
    }

    @Test
    fun `listes taxons — HTTP 500 leve EnvoiEchoue`() {
        router("/api/taxhub/api/biblistes" to { MockResponse().setResponseCode(500) })
        try {
            runBlocking { GeoNatureBrowse.chargerListesTaxons(config) }
            fail("EnvoiEchoue attendue")
        } catch (e: GNErreur.EnvoiEchoue) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `listes taxons — 200 mais HTML (JSON malforme) leve EnvoiEchoue`() {
        // Un portail captif/proxy qui répond 200 en HTML ne doit pas produire une liste vide
        // silencieuse : l'appelant doit voir l'erreur (« format JSON inattendu »).
        router("/api/taxhub/api/biblistes" to {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                .setBody("<html>portail captif</html>")
        })
        try {
            runBlocking { GeoNatureBrowse.chargerListesTaxons(config) }
            fail("EnvoiEchoue attendue")
        } catch (e: GNErreur.EnvoiEchoue) {
            assertTrue(e.msg.contains("format JSON inattendu"))
        }
    }

    // ------------------------------------------------------------------ chargerObservateurs

    @Test
    fun `observateurs — liste configuree du module servie par users menu`() {
        router(
            "/api/gn_commons/config" to { json("""{"OCCTAX":{"id_observers_list":9}}""") },
            "/api/users/menu/9" to {
                json(
                    """
                    [
                      {"id_role": 3, "nom_complet": "DUPONT jean"},
                      {"id_role": 4, "nom_complet": "AUBRY anna"},
                      {"id_role": 0, "nom_complet": "invalide"},
                      {"id_role": 5, "nom_role": "MARTIN", "prenom_role": "paul"}
                    ]
                    """.trimIndent(),
                )
            },
        )
        val obs = runBlocking { GeoNatureBrowse.chargerObservateurs(config) }
        // Tri insensible à la casse ; repli nom_role+prenom_role quand nom_complet manque.
        assertEquals(
            listOf(4 to "AUBRY anna", 3 to "DUPONT jean", 5 to "MARTIN paul"),
            obs.map { it.idRole to it.nomComplet },
        )
        assertTrue("la liste curée dispense d'appeler /users/roles", chemins.none { it.startsWith("/api/users/roles") })
    }

    @Test
    fun `observateurs — sans liste configuree, repli sur users roles (groupes exclus)`() {
        // /api/gn_commons/config absent (404) → repli historique sur tous les rôles.
        router(
            "/api/users/roles" to {
                json(
                    """
                    [
                      {"id_role": 1, "prenom_role": "Jean", "nom_role": "Dupont"},
                      {"id_role": 2, "groupe": true, "nom_role": "Grp Ornithos"},
                      {"id_role": -5, "nom_role": "invalide"},
                      {"id_role": 3, "nom_complet": "MARTIN paul"}
                    ]
                    """.trimIndent(),
                )
            },
        )
        val obs = runBlocking { GeoNatureBrowse.chargerObservateurs(config) }
        assertEquals(
            listOf(1 to "Jean Dupont", 3 to "MARTIN paul"),
            obs.map { it.idRole to it.nomComplet },
        )
    }

    @Test
    fun `observateurs — menu vide, repli sur users roles`() {
        router(
            "/api/gn_commons/config" to { json("""{"OCCTAX":{"id_observers_list":9}}""") },
            "/api/users/menu/9" to { json("[]") },
            "/api/users/roles" to { json("""[{"id_role":1,"nom_complet":"SEULE alice"}]""") },
        )
        val obs = runBlocking { GeoNatureBrowse.chargerObservateurs(config) }
        assertEquals(listOf(1 to "SEULE alice"), obs.map { it.idRole to it.nomComplet })
    }

    @Test
    fun `observateurs — repli en erreur HTTP 500 leve EnvoiEchoue`() {
        router("/api/users/roles" to { MockResponse().setResponseCode(500) })
        try {
            runBlocking { GeoNatureBrowse.chargerObservateurs(config) }
            fail("EnvoiEchoue attendue")
        } catch (e: GNErreur.EnvoiEchoue) {
            assertEquals(500, e.code)
        }
    }

    // ------------------------------------------------------------------ chargerTousHabitats

    @Test
    fun `habitats — liste du module honoree et parsing avec repli lb_code`() {
        router(
            "/api/gn_commons/config" to { json("""{"OCCTAX":{"ID_LIST_HABITAT":55}}""") },
            "/api/habref/habitats/autocomplete" to {
                json(
                    """
                    [
                      {"cd_hab": 616, "search_name": "Prairies humides", "lb_code": "37.2", "cd_typo": 22},
                      {"cd_hab": 0, "search_name": "invalide"},
                      {"cd_hab": 17, "lb_code": "62.1"}
                    ]
                    """.trimIndent(),
                )
            },
        )
        val habitats = runBlocking { GeoNatureBrowse.chargerTousHabitats(config) }
        assertEquals(
            listOf(
                Triple(616, "Prairies humides", 22),
                Triple(17, "62.1", null), // search_name absent → repli lb_code
            ),
            habitats.map { Triple(it.cdHab, it.libelle, it.cdTypo) },
        )
        assertTrue(
            "la liste configurée doit filtrer l'autocomplete",
            chemins.any { it == "/api/habref/habitats/autocomplete?limit=200000&id_list=55" },
        )
    }

    @Test
    fun `habitats — HTTP 500 renvoie une liste vide (best-effort)`() {
        router("/api/habref/habitats/autocomplete" to { MockResponse().setResponseCode(500) })
        assertTrue(runBlocking { GeoNatureBrowse.chargerTousHabitats(config) }.isEmpty())
    }
}

/**
 * Context minimal pour les tests JVM purs : seules les SharedPreferences (en mémoire) et
 * l'applicationContext sont fonctionnels — tout ce que consomme [GeoNatureConfig]. Repose sur
 * `unitTests.isReturnDefaultValues = true` (le constructeur stub de ContextWrapper est muet).
 */
private class FauxContexte : ContextWrapper(null) {
    private val prefsParNom = mutableMapOf<String, FauxPrefs>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        prefsParNom.getOrPut(name.orEmpty()) { FauxPrefs() }
    // Utilisé par la migration de mot de passe (fichier legacy absent → migration sautée).
    override fun getDataDir(): File = File(System.getProperty("java.io.tmpdir"), "geomys-faux-data")
}

/** SharedPreferences en mémoire (écriture immédiate — suffisant pour les tests). */
private class FauxPrefs : SharedPreferences {
    private val valeurs = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(valeurs)
    override fun getString(key: String?, defValue: String?): String? = valeurs[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        valeurs[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = valeurs[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = valeurs[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = valeurs[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = valeurs[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key in valeurs
    override fun edit(): SharedPreferences.Editor = Editeur()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editeur : SharedPreferences.Editor {
        private fun poser(key: String?, value: Any?): SharedPreferences.Editor {
            valeurs[key.orEmpty()] = value; return this
        }
        override fun putString(key: String?, value: String?) = poser(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = poser(key, values)
        override fun putInt(key: String?, value: Int) = poser(key, value)
        override fun putLong(key: String?, value: Long) = poser(key, value)
        override fun putFloat(key: String?, value: Float) = poser(key, value)
        override fun putBoolean(key: String?, value: Boolean) = poser(key, value)
        override fun remove(key: String?): SharedPreferences.Editor { valeurs.remove(key.orEmpty()); return this }
        override fun clear(): SharedPreferences.Editor { valeurs.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
