package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.NomValeur
import fr.ariegenature.geonat.store.TaxRefCache
import fr.ariegenature.geonat.store.TaxRefEntry
import fr.ariegenature.geonat.store.TaxrefRestriction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

object GeoNatureSync {

    suspend fun verifierVersionTaxRef(config: GeoNatureConfig): String? =
        withContext(Dispatchers.IO) {
            try {
                val base = config.urlServeur.trim().trimEnd('/')
                val url = URL("$base/api/taxhub/api/taxref/version")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) return@withContext null
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                obj.opt("version")?.toString()
                    ?: obj.opt("taxref_version")?.toString()
                    ?: obj.opt("num_version")?.toString()
            } catch (e: Exception) { null }
        }

    suspend fun synchroniserTaxRef(
        config: GeoNatureConfig,
        progression: (Int, Int) -> Unit
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val listeId = config.taxaListeId.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: return@withContext Pair(0, "Aucune liste de taxons configurée — saisissez un id_liste dans les paramètres GeoNature.")

        val base = config.urlServeur.trim().trimEnd('/')
        val pageSize = 1000
        val entrees = mutableMapOf<String, TaxRefEntry>()
        val groupeMap = mutableMapOf<Int, String>()
        val groupe1Map = mutableMapOf<Int, String>()
        val regneMap = mutableMapOf<Int, String>()
        val cdNomsOiseaux    = mutableSetOf<Int>()
        val cdNomsMammiferes = mutableSetOf<Int>()
        val cdNomsReptiles   = mutableSetOf<Int>()
        val cdNomsBatraciens = mutableSetOf<Int>()
        val cdNomsPoissons   = mutableSetOf<Int>()
        val cdNomsInsectes   = mutableSetOf<Int>()
        val comptesTousGroupes = mutableMapOf<String, Int>()
        var totalRecu = 0
        var pagesRecues = 0

        progression(0, 1)

        var page = 1
        while (true) {
            try {
                val url = URL("$base/api/taxhub/api/taxref?orderby=cd_nom&fields=listes&id_liste=$listeId&limit=$pageSize&page=$page")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 60000
                conn.readTimeout = 60000
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != 200) {
                    if (pagesRecues == 0) {
                        val preview = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null } ?: ""
                        return@withContext Pair(0, "Erreur HTTP $code — $preview")
                    }
                    break
                }
                val text = conn.inputStream.bufferedReader().readText()
                val array: JSONArray = try {
                    val obj = JSONObject(text)
                    obj.optJSONArray("items") ?: obj.optJSONArray("data") ?: obj.optJSONArray("results") ?: JSONArray(text)
                } catch (_: Exception) {
                    try { JSONArray(text) } catch (_: Exception) {
                        if (pagesRecues == 0) return@withContext Pair(0, "Réponse non-JSON : ${text.take(200)}")
                        break
                    }
                }

                if (array.length() == 0) break
                pagesRecues++
                totalRecu += array.length()
                progression(entrees.size, 0)

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val cdNom = item.optInt("cd_nom", -1).takeIf { it > 0 } ?: continue
                    val lbNom = item.optString("lb_nom", "").takeIf { it.isNotEmpty() } ?: continue
                    // JSONObject.optString retourne la chaîne littérale "null" quand la valeur
                    // JSON est null — il faut tester isNull() au préalable, sinon ça pollue
                    // les compteurs (un cd_nom avec group1=null finirait dans le groupe "null").
                    val nomVernRaw = if (item.isNull("nom_vern")) "" else item.optString("nom_vern", "")
                    val groupe  = if (item.isNull("group2_inpn")) "" else item.optString("group2_inpn", "")
                    val groupe1 = if (item.isNull("group1_inpn")) "" else item.optString("group1_inpn", "")
                    val regne   = if (item.isNull("regne"))       "" else item.optString("regne", "")

                    // Une entrée par clé — alignement iOS. La clé pour un nom vernaculaire
                    // pointe sur une entrée avec nomFrOriginal = ce nom français spécifique ;
                    // la clé pour le nom scientifique pointe sur une entrée avec nomFrOriginal = null.
                    // Évite la duplication massive (liste partagée sérialisée N fois en JSON).
                    for (partie in nomVernRaw.split(",")) {
                        val vernNettoye = TaxRefCache.nettoyerSuffixeArticle(partie.trim())
                        if (vernNettoye.isEmpty()) continue
                        val cle = TaxRefCache.normaliser(vernNettoye)
                        if (cle.isNotEmpty()) entrees[cle] = TaxRefEntry(cdNom, lbNom, vernNettoye)
                    }
                    val cleSci = TaxRefCache.normaliser(lbNom)
                    if (cleSci.isNotEmpty()) entrees[cleSci] = TaxRefEntry(cdNom, lbNom, null)

                    if (groupe.isNotEmpty()) {
                        groupeMap[cdNom] = groupe
                        comptesTousGroupes[groupe] = (comptesTousGroupes[groupe] ?: 0) + 1
                    }
                    if (groupe1.isNotEmpty()) groupe1Map[cdNom] = groupe1
                    if (regne.isNotEmpty()) regneMap[cdNom] = regne
                    when (groupe) {
                        "Oiseaux"    -> cdNomsOiseaux.add(cdNom)
                        "Mammifères" -> cdNomsMammiferes.add(cdNom)
                        "Reptiles"   -> cdNomsReptiles.add(cdNom)
                        "Amphibiens" -> cdNomsBatraciens.add(cdNom)
                        "Poissons"   -> cdNomsPoissons.add(cdNom)
                        "Insectes"   -> cdNomsInsectes.add(cdNom)
                        else         -> Unit
                    }
                }

                if (array.length() < pageSize) break
                page++
            } catch (e: Exception) {
                if (pagesRecues == 0) return@withContext Pair(0, "Erreur réseau : ${e.message}")
                break
            }
        }

        if (pagesRecues == 0) return@withContext Pair(0, "Aucun résultat — la liste $listeId est peut-être vide ou inexistante.")
        if (entrees.isNotEmpty()) TaxRefCache.ajouter(entrees)
        if (groupeMap.isNotEmpty()) TaxRefCache.ajouterGroupes(groupeMap)
        if (groupe1Map.isNotEmpty() || regneMap.isNotEmpty()) TaxRefCache.ajouterGroupes1etRegnes(groupe1Map, regneMap)

        // Index pré-calculé Taxon → cdNoms : permet à l'autocomplétion de servir
        // les suggestions sans rescanner l'ensemble du cache à chaque switch de taxon.
        val indexTaxon = construireIndexTaxon(groupeMap, groupe1Map, regneMap)
        TaxRefCache.setIndexParTaxon(indexTaxon)
        val nbO = cdNomsOiseaux.size
        val nbM = cdNomsMammiferes.size
        val nbR = cdNomsReptiles.size
        val nbB = cdNomsBatraciens.size
        val nbPo = cdNomsPoissons.size
        val nbI  = cdNomsInsectes.size
        // Stocker les comptes par groupe — clé = group2_inpn exact
        val comptes = TaxRefCache.comptesGroupes.toMutableMap()
        for ((k, v) in comptesTousGroupes) if (v > 0) comptes[k] = v
        TaxRefCache.comptesGroupes = comptes
        verifierVersionTaxRef(config)?.let { TaxRefCache.versionSauvegardee = it }
        // Fonge : règne = 'Fungi' ; Invertébrés : règne = 'Animalia' hors vertébrés+insectes+poissons
        val nbCh  = regneMap.values.count { it == "Fungi" }
        val nbInv = maxOf(0, regneMap.values.count { it == "Animalia" } - nbO - nbM - nbR - nbB - nbPo - nbI)
        // Flore : group2_inpn dans la liste des groupes botaniques INPN — même critère qu'iOS.
        // group1_inpn (Phanérogames/Ptéridophytes/Bryophytes) est souvent NULL sur GeoNature,
        // alors que group2_inpn (Angiospermes, Trachéophytes, Mousses, Lichens…) est mieux peuplé.
        val nbP = NomenclatureCache.GROUPES_BOTANIQUES.sumOf { comptesTousGroupes[it] ?: 0 }
        val msg = buildString {
            append("${entrees.size} taxons indexés — $nbO oiseaux")
            if (nbM > 0) append(", $nbM mammifères")
            if (nbR > 0) append(", $nbR reptiles")
            if (nbB > 0) append(", $nbB batraciens")
            if (nbPo > 0) append(", $nbPo poissons")
            if (nbI  > 0) append(", $nbI insectes")
            if (nbCh > 0) append(", $nbCh fonge")
            if (nbInv > 0) append(", $nbInv invertébrés")
            if (nbP > 0) append(", $nbP plantes")
        }
        Pair(entrees.size, msg)
    }

    suspend fun synchroniserNomenclatures(config: GeoNatureConfig): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val base = config.urlServeur.trim().trimEnd('/')
            val (token, _, cookies) = GeoNatureAuth.loginAvecCookies(base, config.login, config.motDePasse)
                ?: return@withContext Pair(0, "Authentification échouée")

            try {
                // On teste les mêmes endpoints que sur iOS
                val urls = listOf(
                    "$base/api/nomenclatures/nomenclatures/taxonomy",
                    "$base/api/nomenclatures/nomenclature/taxonomy",
                    "$base/api/nomenclatures/taxonomy"
                )

                var text: String? = null
                var lastCode = 0
                for (u in urls) {
                    val conn = URL(u).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("Accept", "application/json")
                    if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                    if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                    lastCode = conn.responseCode
                    if (lastCode == 200) {
                        text = conn.inputStream.bufferedReader().readText()
                        break
                    }
                }

                if (text == null) return@withContext Pair(0, "Endpoint taxonomy inaccessible (HTTP $lastCode)")

                val array: JSONArray = try { JSONArray(text) } catch (_: Exception) {
                    try {
                        val obj = JSONObject(text)
                        obj.optJSONArray("items") ?: obj.optJSONArray("data")
                            ?: obj.optJSONArray("nomenclatures") ?: obj.optJSONArray("results")
                            ?: return@withContext Pair(0, "Format inattendu")
                    } catch (_: Exception) {
                        return@withContext Pair(0, "Format inattendu")
                    }
                }

                val typesVoulus = setOf("METH_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
                                        "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN")
                val result = mutableMapOf<String, List<NomValeur>>()

                for (i in 0 until array.length()) {
                    val typeObj = array.getJSONObject(i)
                    // GeoNature peut utiliser "mnemonique" ou "mnemonic"
                    val mnem = typeObj.optString("mnemonique", "").ifEmpty { typeObj.optString("mnemonic", "") }
                    if (mnem !in typesVoulus) continue

                    // GeoNature retourne "nomenclatures" (pas "values")
                    val valuesArray = typeObj.optJSONArray("nomenclatures")
                        ?: typeObj.optJSONArray("values")
                        ?: continue
                    val valeurs = mutableListOf<NomValeur>()

                    for (j in 0 until valuesArray.length()) {
                        val v = valuesArray.getJSONObject(j)
                        val id = v.optInt("id_nomenclature", -1).takeIf { it > 0 } ?: continue
                        val label = v.optString("label_default", "").ifEmpty { v.optString("label_fr", "") }
                        if (label.isEmpty()) continue

                        val taxrefArray = v.optJSONArray("taxref")
                        val restrictions = mutableListOf<TaxrefRestriction>()
                        if (taxrefArray != null) {
                            for (k in 0 until taxrefArray.length()) {
                                val r = taxrefArray.getJSONObject(k)
                                val regne = r.optString("regne", "")
                                val group2 = r.optString("group2_inpn", "")
                                // Ignorer les entrées vides (comme iOS : guard !regno.isEmpty || !group.isEmpty)
                                if (regne.isEmpty() && group2.isEmpty()) continue
                                restrictions.add(TaxrefRestriction(regne, group2))
                            }
                        }
                        valeurs.add(NomValeur(id, label, restrictions))
                    }

                    if (valeurs.isNotEmpty()) result[mnem] = valeurs
                }

                if (result.isNotEmpty()) NomenclatureCache.setAll(result)
                val total = result.values.sumOf { it.size }
                val resume = result.entries.joinToString(" | ") { (t, vals) ->
                    val nbTr = vals.count { it.taxref.isNotEmpty() }
                    "$t:${vals.size}val/${nbTr}taxref"
                }
                Pair(total, resume)
            } catch (e: Exception) {
                Pair(0, "Erreur : ${e.message}")
            }
        }

    private fun construireIndexTaxon(
        groupe2: Map<Int, String>,
        groupe1: Map<Int, String>,
        regne: Map<Int, String>
    ): Map<Taxon, List<Int>> {
        val parG2 = groupe2.entries.groupBy({ it.value }, { it.key })
        val oiseaux    = parG2["Oiseaux"].orEmpty().toSet()
        val mammiferes = parG2["Mammifères"].orEmpty().toSet()
        val reptiles   = parG2["Reptiles"].orEmpty().toSet()
        val batraciens = parG2["Amphibiens"].orEmpty().toSet()
        val poissons   = (parG2["Poissons"].orEmpty() +
            groupe1.filterValues { it == "Poissons" }.keys +
            groupe2.filter { it.value in NomenclatureCache.GROUP2_POISSONS }.keys).toSet()
        val insectes   = parG2["Insectes"].orEmpty().toSet()
        val fonge      = regne.filterValues { it == "Fungi" }.keys
        // Plantes : group2_inpn botanique (Angiospermes, Trachéophytes, Mousses, Lichens…)
        // — critère principal, identique iOS. Complété par group1 et regne pour les instances
        // où ces champs sont mieux peuplés que group2.
        val plantes    = (groupe2.filterValues { it in NomenclatureCache.GROUPES_BOTANIQUES }.keys +
            groupe1.filterValues { it in NomenclatureCache.GROUPES1_FLORE }.keys +
            regne.filterValues { it == "Plantae" }.keys).toSet()

        val tousAnimalia = regne.filterValues { it == "Animalia" }.keys
        val invertebres = (tousAnimalia - oiseaux - mammiferes - reptiles - batraciens - poissons - insectes).toSet()

        return mapOf(
            Taxon.OISEAU      to oiseaux.sorted(),
            Taxon.MAMMIFERE   to mammiferes.sorted(),
            Taxon.REPTILE     to reptiles.sorted(),
            Taxon.BATRACIEN   to batraciens.sorted(),
            Taxon.POISSON     to poissons.sorted(),
            Taxon.INSECTE     to insectes.sorted(),
            Taxon.FONGE       to fonge.sorted(),
            Taxon.INVERTEBRES to invertebres.sorted(),
            Taxon.PLANTE      to plantes.sorted(),
        )
    }
}