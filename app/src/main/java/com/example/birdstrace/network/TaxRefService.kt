package com.example.birdstrace.network

import com.example.birdstrace.TaxRefLocal
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.TaxRefCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

sealed class TaxRefStatut {
    data class Trouve(val cdNom: Int, val nomScientifique: String, val nomFrancais: String? = null) : TaxRefStatut()
    object NonTrouve : TaxRefStatut()
    object Indisponible : TaxRefStatut()
}

object TaxRefService {

    suspend fun rechercher(nom: String, gnConfig: GeoNatureConfig? = null): Pair<TaxRefStatut, Boolean> =
        withContext(Dispatchers.IO) {
            // 1. Cache synchronisé depuis le serveur GeoNature (cd_nom autoritatif du serveur)
            TaxRefCache.get(nom)?.let { entry ->
                return@withContext Pair(TaxRefStatut.Trouve(entry.cdNom, entry.sciNom, entry.vernNom), false)
            }

            // 2. Base embarquée TaxRefLocal
            val statutLocal = TaxRefLocal.rechercher(nom)
            if (statutLocal is TaxRefStatut.Trouve) {
                return@withContext Pair(statutLocal, false)
            }

            // 3. API TaxRef GeoNature en direct (si configuré)
            if (gnConfig != null && gnConfig.connexionConfiguree) {
                rechercherViaGeoNature(nom, gnConfig)?.let { statut ->
                    if (statut is TaxRefStatut.Trouve) {
                        TaxRefCache.set(nom, statut.cdNom, statut.nomScientifique, statut.nomFrancais)
                    }
                    return@withContext Pair(statut, true)
                }
            }

            Pair(TaxRefStatut.NonTrouve, false)
        }

    private suspend fun rechercherViaGeoNature(nom: String, config: GeoNatureConfig): TaxRefStatut? =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val encoded = nom.trim().replace(" ", "%20")
                val url = URL("$base/api/taxhub/api/taxref/?nom_cite=$encoded&limit=10")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) return@withContext null
                val array = JSONArray(conn.inputStream.bufferedReader().readText())
                val nomNorm = TaxRefCache.normaliser(nom)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val vernRaw = item.optString("nom_vern", "")
                    val vern = TaxRefCache.normaliser(vernRaw)
                    if (vern == nomNorm) {
                        val cdNom = item.optInt("cd_nom", -1)
                        val lbNom = item.optString("lb_nom", "")
                        if (cdNom > 0) return@withContext TaxRefStatut.Trouve(cdNom, lbNom, if (vernRaw.isNotEmpty()) vernRaw else null)
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
}