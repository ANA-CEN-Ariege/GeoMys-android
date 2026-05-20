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

    /** Sync exhaustif : itère sur **toutes** les biblistes du serveur pour permettre à
     *  l'utilisateur de changer la liste/dataset sélectionnés hors-réseau sans perdre les
     *  taxons associés. Le cache `listesParCdNom` se construit naturellement par agrégation
     *  (chaque cd_nom porte son tableau d'appartenance via `fields=listes`).
     *
     *  Callback `progression(taxonsAccumules, listeIdx, listesTotales)` — listeIdx==0 avant
     *  le démarrage (pendant le fetch des biblistes), 1..N pendant l'itération. */
    suspend fun synchroniserTaxRef(
        config: GeoNatureConfig,
        progression: (Int, Int, Int) -> Unit
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val base = config.urlServeur.trim().trimEnd('/')
        val pageSize = 1000

        progression(0, 0, 0)

        // 1) Récupère la liste de toutes les biblistes — délégué à GeoNatureBrowse.
        val biblistes: List<GeoNatureListe> = try {
            GeoNatureBrowse.chargerListesTaxons(config)
        } catch (e: Exception) {
            return@withContext Pair(0, "Impossible de récupérer les listes de taxons : ${e.message}")
        }
        // Récupère aussi les `id_taxa_list` référencés par les datasets : certains serveurs
        // les attachent à des listes "privées" non listées dans /biblistes. Sans cette
        // fusion, la liste imposée par le dataset déclenche l'avertissement "non dans le
        // cache" et l'utilisateur ne peut pas la débloquer en rechargeant.
        val idsViaDatasets: Set<Int> = try {
            GeoNatureBrowse.chargerDatasets(config).mapNotNull { it.idTaxaList }.toSet()
        } catch (_: Exception) { emptySet() }
        val idsBiblistes = biblistes.map { it.id }.toSet()
        val listesAFetcher = biblistes.toMutableList()
        idsViaDatasets.forEach { id ->
            if (id !in idsBiblistes) listesAFetcher.add(GeoNatureListe(id, "Liste $id (via dataset)"))
        }
        if (listesAFetcher.isEmpty()) {
            return@withContext Pair(0, "Aucune liste de taxons trouvée sur le serveur (/api/taxhub/api/biblistes).")
        }

        // Accumulateurs globaux — on fusionne au fil des listes. Map<key, entry> garantit
        // qu'un cd_nom présent dans plusieurs listes n'apparaît qu'une fois dans le cache.
        val entrees = mutableMapOf<String, TaxRefEntry>()
        val groupeMap = mutableMapOf<Int, String>()
        val groupe1Map = mutableMapOf<Int, String>()
        val regneMap = mutableMapOf<Int, String>()
        // Set<Int> pour fusionner les appartenances de listes (un cd_nom peut être dans
        // plusieurs listes → union via Set, sérialisé en List à la fin).
        val listesParCdNom = mutableMapOf<Int, MutableSet<Int>>()
        val comptesTousGroupes = mutableMapOf<String, Int>()
        val cdNomsDejaComptes = mutableSetOf<Int>()
        val listesSynchronisees = mutableListOf<Int>()
        val listesEnEchec = mutableListOf<Int>()

        for ((listeIdx, liste) in listesAFetcher.withIndex()) {
            progression(entrees.size, listeIdx + 1, listesAFetcher.size)
            var pagesRecues = 0
            // Devient true dès qu'un appel HTTP 200 a réussi — même si la liste est vide
            // côté serveur. Sans ça, une liste valide mais sans taxons ne serait pas
            // marquée comme synchronisée et l'avertissement "pas dans le cache" persisterait.
            var httpOkAuMoinsUneFois = false
            var page = 1
            while (true) {
                try {
                    val url = URL("$base/api/taxhub/api/taxref?orderby=cd_nom&fields=listes&id_liste=${liste.id}&limit=$pageSize&page=$page")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 60000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("Accept", "application/json")
                    val code = conn.responseCode
                    if (code != 200) {
                        if (!httpOkAuMoinsUneFois) listesEnEchec.add(liste.id)
                        break
                    }
                    httpOkAuMoinsUneFois = true
                    val text = conn.inputStream.bufferedReader().readText()
                    val array: JSONArray = try {
                        val obj = JSONObject(text)
                        obj.optJSONArray("items") ?: obj.optJSONArray("data") ?: obj.optJSONArray("results") ?: JSONArray(text)
                    } catch (_: Exception) {
                        try { JSONArray(text) } catch (_: Exception) { break }
                    }

                    if (array.length() == 0) break
                    pagesRecues++

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
                            // Évite de compter un cd_nom deux fois s'il apparaît dans plusieurs listes.
                            if (cdNomsDejaComptes.add(cdNom)) {
                                comptesTousGroupes[groupe] = (comptesTousGroupes[groupe] ?: 0) + 1
                            }
                        }
                        if (groupe1.isNotEmpty()) groupe1Map[cdNom] = groupe1
                        if (regne.isNotEmpty()) regneMap[cdNom] = regne
                        // Appartenance aux listes UsersHub. Le champ `listes` retourné est
                        // censé contenir TOUTES les listes du cd_nom (pas seulement celle filtrée),
                        // mais on a observé des incohérences serveur → on ajoute systématiquement
                        // l'id de la liste courante en filet de sécurité.
                        val setListes = listesParCdNom.getOrPut(cdNom) { mutableSetOf() }
                        setListes.add(liste.id)
                        item.optJSONArray("listes")?.let { arr ->
                            for (j in 0 until arr.length()) {
                                arr.optJSONObject(j)?.optInt("id_liste", -1)
                                    ?.takeIf { it > 0 }?.let(setListes::add)
                            }
                        }
                    }

                    if (array.length() < pageSize) break
                    page++
                } catch (e: Exception) {
                    if (!httpOkAuMoinsUneFois) listesEnEchec.add(liste.id)
                    break
                }
            }
            // Liste considérée synchronisée si on a obtenu au moins un HTTP 200, peu importe
            // le nombre de taxons ramenés (une liste vide est valide côté serveur).
            if (httpOkAuMoinsUneFois) listesSynchronisees.add(liste.id)
        }

        if (entrees.isEmpty()) {
            return@withContext Pair(0, "Aucun taxon récupéré sur ${listesAFetcher.size} liste(s).")
        }

        TaxRefCache.ajouter(entrees)
        if (groupeMap.isNotEmpty()) TaxRefCache.ajouterGroupes(groupeMap)
        if (groupe1Map.isNotEmpty() || regneMap.isNotEmpty()) TaxRefCache.ajouterGroupes1etRegnes(groupe1Map, regneMap)
        // Sérialise Set<Int> → List<Int> pour stockage. L'ordre n'est pas signifiant.
        if (listesParCdNom.isNotEmpty()) {
            TaxRefCache.ajouterListesParCdNom(listesParCdNom.mapValues { (_, v) -> v.toList() })
        }

        // Index pré-calculé Taxon → cdNoms : permet à l'autocomplétion de servir
        // les suggestions sans rescanner l'ensemble du cache à chaque switch de taxon.
        val indexTaxon = construireIndexTaxon(groupeMap, groupe1Map, regneMap)
        TaxRefCache.setIndexParTaxon(indexTaxon)
        // Marque le cache comme exhaustif (toutes les listes serveur ont été tentées).
        TaxRefCache.listesSynchronisees = listesSynchronisees
        // Champ legacy conservé pour compatibilité ascendante du SharedPreferences.
        TaxRefCache.listeSynchroniseeId = config.taxaListeId.trim().ifEmpty { listesSynchronisees.firstOrNull()?.toString() ?: "" }

        // Stocker les comptes par groupe — clé = group2_inpn exact
        val comptes = TaxRefCache.comptesGroupes.toMutableMap()
        for ((k, v) in comptesTousGroupes) if (v > 0) comptes[k] = v
        TaxRefCache.comptesGroupes = comptes
        verifierVersionTaxRef(config)?.let { TaxRefCache.versionSauvegardee = it }

        val nbO  = comptesTousGroupes["Oiseaux"] ?: 0
        val nbM  = comptesTousGroupes["Mammifères"] ?: 0
        val nbR  = comptesTousGroupes["Reptiles"] ?: 0
        val nbB  = comptesTousGroupes["Amphibiens"] ?: 0
        val nbPo = comptesTousGroupes["Poissons"] ?: 0
        val nbI  = comptesTousGroupes["Insectes"] ?: 0
        val nbCh  = regneMap.values.count { it == "Fungi" }
        val nbMol = groupe1Map.values.count { it == "Mollusques" }
        val nbInv = maxOf(0, regneMap.values.count { it == "Animalia" } - nbO - nbM - nbR - nbB - nbPo - nbI - nbMol)
        val nbP = NomenclatureCache.GROUPES_BOTANIQUES.sumOf { comptesTousGroupes[it] ?: 0 }
        val msg = buildString {
            append("${entrees.size} taxons indexés sur ${listesSynchronisees.size}/${listesAFetcher.size} listes — $nbO oiseaux")
            if (nbM > 0) append(", $nbM mammifères")
            if (nbR > 0) append(", $nbR reptiles")
            if (nbB > 0) append(", $nbB batraciens")
            if (nbPo > 0) append(", $nbPo poissons")
            if (nbI  > 0) append(", $nbI insectes")
            if (nbCh > 0) append(", $nbCh fonge")
            if (nbMol > 0) append(", $nbMol mollusques")
            if (nbInv > 0) append(", $nbInv autres invertébrés")
            if (nbP > 0) append(", $nbP plantes")
            if (listesEnEchec.isNotEmpty()) append("\n⚠ ${listesEnEchec.size} liste(s) non chargée(s) : ${listesEnEchec.joinToString(",")}")
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

                val typesVoulus = setOf("METH_OBS", "STATUT_OBS", "SEXE", "STADE_VIE", "STATUT_BIO", "ETA_BIO",
                                        "PREUVE_EXIST", "OBJ_DENBR", "TYP_DENBR", "OCC_COMPORTEMENT", "METH_DETERMIN", "TYPE_MEDIA")
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

                // Récupère aussi les valeurs par défaut des nomenclatures pour le module
                // OCCTAX — endpoint /api/occtax/defaultNomenclatures (Map<mnemonique, id>).
                // C'est ce que l'UI web utilise pour pré-remplir les spinners nomenclature
                // (STATUT_OBS, SEXE…) au démarrage d'une nouvelle saisie.
                val defauts = fetchDefaultsNomenclatures(base, token, cookies, "occtax")
                NomenclatureCache.setDefauts(defauts)

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

    /** Fetch des défauts de nomenclatures pour un module (`/api/<module>/defaultNomenclatures`).
     *  Retourne une Map<mnemonique, id_nomenclature_stringifié>. Tente plusieurs casses du
     *  module (Flask est case-sensitive sur les routes). Best-effort, vide en cas d'échec. */
    fun fetchDefaultsNomenclatures(base: String, token: String?, cookies: String, moduleCode: String): Map<String, String> {
        val variantes = listOf(moduleCode, moduleCode.lowercase()).distinct()
        for (variant in variantes) {
            val urlStr = "$base/api/$variant/defaultNomenclatures"
            try {
                val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                val code = conn.responseCode
                if (code != 200) {
                    android.util.Log.w("GeoNatureSync", "defaultNomenclatures HTTP $code pour $urlStr")
                    continue
                }
                val text = conn.inputStream.bufferedReader().readText()
                val obj = JSONObject(text)
                val out = mutableMapOf<String, String>()
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.opt(k)?.toString()?.takeIf { s -> s.isNotEmpty() && s != "null" }
                    if (v != null) out[k] = v
                }
                android.util.Log.i("GeoNatureSync", "defaultNomenclatures OK $urlStr → ${out.size} entrées : $out")
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                android.util.Log.w("GeoNatureSync", "defaultNomenclatures exception pour $urlStr : ${e.message}")
            }
        }
        return emptyMap()
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
        // Mollusques : group1_inpn = 'Mollusques'. Sont retirés du sac invertébrés "autres".
        val mollusques = groupe1.filterValues { it == "Mollusques" }.keys.toSet()
        val invertebres = (tousAnimalia - oiseaux - mammiferes - reptiles - batraciens - poissons - insectes - mollusques).toSet()

        return mapOf(
            Taxon.OISEAU      to oiseaux.sorted(),
            Taxon.MAMMIFERE   to mammiferes.sorted(),
            Taxon.REPTILE     to reptiles.sorted(),
            Taxon.BATRACIEN   to batraciens.sorted(),
            Taxon.POISSON     to poissons.sorted(),
            Taxon.INSECTE     to insectes.sorted(),
            Taxon.FONGE       to fonge.sorted(),
            Taxon.MOLLUSQUE   to mollusques.sorted(),
            Taxon.INVERTEBRES to invertebres.sorted(),
            Taxon.PLANTE      to plantes.sorted(),
        )
    }
}