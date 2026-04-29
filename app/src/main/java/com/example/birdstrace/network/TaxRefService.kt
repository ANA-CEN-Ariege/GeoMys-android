package com.example.birdstrace.network

import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.TaxRefCache
import com.example.birdstrace.store.TaxRefEntry
import com.example.birdstrace.TaxRefLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

sealed class TaxRefStatut {
    data class Trouve(val cdNom: Int, val nomScientifique: String, val nomFrancais: String? = null) : TaxRefStatut()
    object NonTrouve : TaxRefStatut()
    object Indisponible : TaxRefStatut()
}

object TaxRefService {

    suspend fun rechercher(nom: String, gnConfig: GeoNatureConfig? = null): Pair<TaxRefStatut, Boolean> =
        withContext(Dispatchers.IO) {
            // 1. Table locale TaxRef v18 (Priorité haute pour conserver les accents)
            val local = TaxRefLocal.rechercher(nom)
            if (local is TaxRefStatut.Trouve) return@withContext Pair(local, false)

            // 2. Cache persistant (normalisé sans accents)
            TaxRefCache.get(nom)?.let { entry ->
                return@withContext Pair(TaxRefStatut.Trouve(entry.cdNom, entry.sciNom, entry.vernNom), false)
            }

            // 3. API TaxRef GeoNature (si configuré)
            if (gnConfig != null && gnConfig.connexionConfiguree) {
                rechercherViaGeoNature(nom, gnConfig)?.let { statut ->
                    if (statut is TaxRefStatut.Trouve) {
                        TaxRefCache.set(nom, statut.cdNom, statut.nomScientifique, statut.nomFrancais)
                    }
                    return@withContext Pair(statut, true)
                }
            }

            // 4. API MNHN en ligne
            try {
                val encoded = nom.trim().replace(" ", "%20")
                val url = URL("https://taxref.mnhn.fr/api/taxa/search?frenchVernacularNames=$encoded&territory=fr&page=0&size=1")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val embedded = json.optJSONObject("_embedded")
                    val taxa = embedded?.optJSONArray("taxa")
                    val first = taxa?.optJSONObject(0)
                    if (first != null) {
                        val id = first.optInt("id", -1)
                        val sciName = first.optString("scientificName", "")
                        val frName = if (first.has("frenchVernacularName") && !first.isNull("frenchVernacularName")) 
                            first.getString("frenchVernacularName") else null
                        if (id > 0 && sciName.isNotEmpty()) {
                            return@withContext Pair(TaxRefStatut.Trouve(id, sciName, frName), false)
                        }
                    }
                    return@withContext Pair(TaxRefStatut.NonTrouve, false)
                }
            } catch (e: Exception) {
                return@withContext Pair(TaxRefStatut.Indisponible, false)
            }

            Pair(local, false)
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