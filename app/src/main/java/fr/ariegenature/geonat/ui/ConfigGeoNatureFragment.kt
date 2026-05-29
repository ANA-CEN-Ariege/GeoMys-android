package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.appcompat.app.AlertDialog
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentConfigGeonatureBinding
import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.network.AdditionalFieldsApi
import fr.ariegenature.geonat.network.GeoNatureAuth
import fr.ariegenature.geonat.network.GeoNatureBrowse
import fr.ariegenature.geonat.network.GeoNatureDataset
import fr.ariegenature.geonat.network.GeoNatureListe
import fr.ariegenature.geonat.network.GeoNatureObservateur
import fr.ariegenature.geonat.network.GeoNatureSync
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.network.MonitoringSync
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.MonitoringCache
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ConfigGeoNatureFragment : Fragment() {
    private var _binding: FragmentConfigGeonatureBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig
    private val datasets = mutableListOf<GeoNatureDataset>()
    private val listes = mutableListOf<GeoNatureListe>()
    private val observateurs = mutableListOf<GeoNatureObservateur>()
    private val gson = Gson()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConfigGeonatureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        gnConfig = GeoNatureConfig(requireContext())

        binding.etUrl.setText(gnConfig.urlServeur)
        binding.etLogin.setText(gnConfig.login)
        binding.etMotDePasse.setText(gnConfig.motDePasse)
        binding.etTaxaListe.setText(gnConfig.taxaListeId)

        // Restaure les spinners (datasets/listes/observateurs) depuis le cache local
        // si déjà chargés lors d'une session précédente.
        restaurerCaches()

        // État initial des sections selon la présence de données en cache.
        // - Cache TaxRef non vide → l'utilisateur a déjà chargé : on montre tout
        //   (section "Charger" affiche "Recharger…", section "Données" visible).
        // - Sinon → seul le bloc connexion est visible, l'utilisateur doit se connecter
        //   puis cliquer sur "Charger les données".
        val donneesPresentes = TaxRefCache.count > 0
        binding.llSectionCharger.visibility = if (donneesPresentes) View.VISIBLE else View.GONE
        binding.llSectionDonnees.visibility = if (donneesPresentes) View.VISIBLE else View.GONE
        binding.btnChargerDonnees.text = if (donneesPresentes) "Recharger les données" else "Charger les données"

        updateStatusIndicator()
        updateCacheInfo()
        updateAvertissementListe()

        binding.etTaxaListe.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                gnConfig.taxaListeId = s?.toString() ?: ""
                updateAvertissementListe()
                // Re-render les compteurs par groupe : intersection avec la nouvelle liste.
                updateCacheInfo()
                updateStatusIndicator()
            }
        })

        binding.btnTesterConnexion.setOnClickListener {
            sauvegarderChamps()
            testerConnexion()
        }

        // Clic sur Jeu de données / Observateur : on vide le texte affiché pour
        // permettre à l'utilisateur de choisir une autre valeur sans devoir effacer
        // manuellement. `setText("", false)` ne déclenche pas le filtrage, donc la
        // dropdown affiche toute la liste. La valeur stockée dans gnConfig n'est
        // remplacée que sur sélection effective dans la dropdown.
        binding.acDatasets.setOnClickListener {
            binding.acDatasets.setText("", false)
            binding.acDatasets.showDropDown()
        }
        binding.acObservateurs.setOnClickListener {
            binding.acObservateurs.setText("", false)
            binding.acObservateurs.showDropDown()
        }

        binding.btnChargerDonnees.setOnClickListener {
            sauvegarderChamps()
            chargerToutesLesDonnees()
        }

        binding.btnViderCache.setOnClickListener {
            viderTousLesCaches()
            updateCacheInfo()
            updateAvertissementListe()
            updateStatusIndicator()
            // Sans cache, on repasse en état "Charger les données" — le bouton change
            // de libellé mais la section reste visible si la connexion est OK.
            binding.btnChargerDonnees.text = "Charger les données"
            binding.llSectionDonnees.visibility = View.GONE
        }

        binding.btnTaxonsParGroupe.setOnClickListener { montrerTaxonsParGroupe() }

        binding.fabValider.setOnClickListener {
            sauvegarderChamps()
            findNavController().navigateUp()
        }

        // Vérifier version TaxRef serveur
        if (gnConfig.connexionConfiguree) {
            viewLifecycleOwner.lifecycleScope.launch {
                val version = GeoNatureSync.verifierVersionTaxRef(gnConfig)
                if (version != null) {
                    val cached = TaxRefCache.versionSauvegardee
                    binding.tvTaxRefVersion.visibility = View.VISIBLE
                    when {
                        cached != null && cached != version -> {
                            binding.tvTaxRefVersion.text = "⚠ TaxRef serveur v$version — cache v$cached. Resynchroniser recommandé."
                            binding.tvTaxRefVersion.setTextColor(
                                androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
                            )
                        }
                        cached == version -> binding.tvTaxRefVersion.text = "TaxRef v$version — à jour"
                        else -> binding.tvTaxRefVersion.text = "TaxRef v$version disponible — synchroniser pour utiliser"
                    }
                }
            }
        }
    }

    private fun sauvegarderChamps() {
        // Snapshot des credentials AVANT modif pour détecter un changement d'identité serveur.
        // Sans cette comparaison, les caches mémoire process-wide (auth token, id_nomenclature,
        // id_table_location, id_role, LabelResolver…) continueraient à servir les valeurs de
        // l'instance précédente — les envois partiraient ensuite avec des FK invalides.
        val ancien = Triple(gnConfig.urlServeur, gnConfig.login, gnConfig.motDePasse)
        gnConfig.urlServeur = binding.etUrl.text.toString()
        gnConfig.login = binding.etLogin.text.toString()
        gnConfig.motDePasse = binding.etMotDePasse.text.toString()
        val nouveau = Triple(gnConfig.urlServeur, gnConfig.login, gnConfig.motDePasse)
        if (ancien != nouveau) {
            fr.ariegenature.geonat.network.invaliderCachesSession()
        }
        // idDataset / nomDataset sont positionnés via le spinner — pas via le champ texte.
        gnConfig.taxaListeId = binding.etTaxaListe.text.toString()
    }

    private fun testerConnexion() {
        binding.btnTesterConnexion.isEnabled = false
        binding.progressTest.visibility = View.VISIBLE
        binding.tvResultatTest.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val (success, msg) = GeoNatureAuth.testerConnexion(gnConfig)
            binding.progressTest.visibility = View.GONE
            binding.btnTesterConnexion.isEnabled = true
            binding.tvResultatTest.visibility = View.VISIBLE
            binding.tvResultatTest.text = msg
            binding.tvResultatTest.setTextColor(
                if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
            updateStatusIndicator()
            // Connexion OK → on révèle uniquement le bouton "Charger les données".
            // Aucun chargement automatique : l'utilisateur déclenche explicitement
            // (le sync exhaustif peut être long, on ne le lance pas par surprise).
            if (success) {
                binding.llSectionCharger.visibility = View.VISIBLE
            }
        }
    }

    /** Retourne null si OK, sinon un message d'erreur — exploité par chargerToutesLesDonnees
     *  pour agréger les étapes en échec dans l'avertissement final. */
    private suspend fun chargerDatasets(): String? {
        binding.progressDatasets.visibility = View.VISIBLE
        return try {
            val result = GeoNatureBrowse.chargerDatasets(gnConfig)
            if (result.isNotEmpty()) {
                peuplerSpinnerDatasets(result)
                gnConfig.datasetsCacheJson = gson.toJson(result)
                binding.tvErreurDatasets.visibility = View.GONE
                null
            } else {
                binding.tvErreurDatasets.visibility = View.VISIBLE
                binding.tvErreurDatasets.text = "Aucun jeu de données accessible"
                "Aucun jeu de données"
            }
        } catch (e: Exception) {
            binding.tvErreurDatasets.visibility = View.VISIBLE
            binding.tvErreurDatasets.text = e.message
            e.message ?: "Erreur datasets"
        } finally {
            binding.progressDatasets.visibility = View.GONE
        }
    }

    private fun peuplerSpinnerDatasets(result: List<GeoNatureDataset>) {
        // Mémorise TOUS les datasets (utilisés par les écrans monitoring pour résoudre
        // un id_dataset précis selon le protocole). Mais ne propose à l'écran de config
        // OCCTAX que ceux **actifs** ET **rattachés au module OCCTAX**.
        datasets.clear()
        datasets.addAll(result)
        val proposes = result.filter { it.actif && (it.moduleCodes.isEmpty() || "OCCTAX" in it.moduleCodes) }
        // Note : moduleCodes vide = on garde le dataset (fallback safe pour les instances qui
        // n'exposent pas le tableau modules — sinon on n'aurait rien à proposer).
        val noms = proposes.map { "${it.nom} (${it.id})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, noms)
        binding.acDatasets.setAdapter(adapter)
        binding.tilDatasets.visibility = View.VISIBLE
        binding.acDatasets.threshold = 1
        binding.acDatasets.setOnItemClickListener { _, _, position, _ ->
            val labelChoisi = adapter.getItem(position) ?: return@setOnItemClickListener
            val idx = noms.indexOf(labelChoisi)
            if (idx >= 0) {
                gnConfig.idDataset = proposes[idx].id.toString()
                gnConfig.nomDataset = proposes[idx].nom
                appliquerRestrictionListeDataset(proposes[idx])
                updateStatusIndicator()
            }
        }
        // Affiche la sélection courante si elle est dans les datasets proposés.
        val currentId = gnConfig.idDataset.toIntOrNull()
        val idx = proposes.indexOfFirst { it.id == currentId }
        binding.acDatasets.setText(if (idx >= 0) noms[idx] else "", false)
        // Restaure la restriction au démarrage si le dataset courant a une liste imposée.
        if (idx >= 0) appliquerRestrictionListeDataset(proposes[idx])
    }

    /** Si le dataset porte un `id_taxa_list`, restreint la dropdown des listes à cette
     *  seule liste et force la sélection — l'utilisateur ne peut plus choisir une autre
     *  liste pour ce dataset. Sinon, restaure la liste complète des biblistes serveur. */
    private fun appliquerRestrictionListeDataset(dataset: GeoNatureDataset) {
        val idImpose = dataset.idTaxaList
        if (idImpose != null) {
            // Cherche la liste imposée dans le cache existant ; fallback à un GeoNatureListe
            // synthétique avec juste son id (label dégradé) si le serveur n'a pas exposé
            // cette liste via /biblistes.
            val toutes = listes.toList()
            val liste = toutes.firstOrNull { it.id == idImpose }
                ?: GeoNatureListe(idImpose, "Liste $idImpose")
            peuplerSpinnerListes(listOf(liste))
            // Force la sélection sur l'unique entrée — déclenche aussi le rafraîchissement
            // des compteurs par groupe (via le TextWatcher de etTaxaListe).
            gnConfig.taxaListeId = idImpose.toString()
            binding.etTaxaListe.setText(idImpose.toString())
            binding.acListes.setText("${liste.nom} (${liste.id})", false)
            // L'utilisateur ne peut pas dévier — désactive l'AutoCompleteTextView et le
            // champ manuel ; un sous-titre explique pourquoi.
            binding.acListes.isEnabled = false
            binding.tilTaxaListe.visibility = View.GONE
            binding.tvErreurListes.visibility = View.VISIBLE
            binding.tvErreurListes.text = "Liste imposée par le jeu de données"
            binding.tvErreurListes.setTextColor(couleurSecondaire(requireContext()))
        } else {
            // Dataset sans contrainte : restaure la liste complète + réactive le contrôle.
            binding.acListes.isEnabled = true
            binding.tvErreurListes.visibility = View.GONE
            // Repeuple avec la liste complète depuis le cache JSON local.
            gnConfig.listesCacheJson.takeIf { it.isNotEmpty() }?.let { json ->
                try {
                    val t = object : TypeToken<List<GeoNatureListe>>() {}.type
                    val l: List<GeoNatureListe>? = gson.fromJson(json, t)
                    if (!l.isNullOrEmpty()) peuplerSpinnerListes(l)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun chargerListes(): String? {
        binding.tvErreurListes.visibility = View.GONE
        return try {
            val result = GeoNatureBrowse.chargerListesTaxons(gnConfig)
            if (result.isNotEmpty()) {
                peuplerSpinnerListes(result)
                gnConfig.listesCacheJson = gson.toJson(result)
                null
            } else {
                binding.tvErreurListes.visibility = View.VISIBLE
                binding.tvErreurListes.text = "Aucune liste de taxons trouvée sur ce serveur"
                "Aucune liste de taxons"
            }
        } catch (e: Exception) {
            binding.tvErreurListes.visibility = View.VISIBLE
            binding.tvErreurListes.text = e.message
            e.message ?: "Erreur listes"
        }
    }

    private fun peuplerSpinnerListes(result: List<GeoNatureListe>) {
        listes.clear()
        listes.addAll(result)
        val noms = result.map { "${it.nom} (${it.id})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, noms)
        binding.acListes.setAdapter(adapter)
        binding.tilListes.visibility = View.VISIBLE
        binding.tilTaxaListe.visibility = View.GONE
        binding.acListes.threshold = 1
        binding.acListes.setOnItemClickListener { _, _, position, _ ->
            val labelChoisi = adapter.getItem(position) ?: return@setOnItemClickListener
            val idx = noms.indexOf(labelChoisi)
            if (idx >= 0) {
                gnConfig.taxaListeId = listes[idx].id.toString()
                binding.etTaxaListe.setText(gnConfig.taxaListeId)
                updateAvertissementListe()
                updateStatusIndicator()
            }
        }
        
        val currentId = gnConfig.taxaListeId.toIntOrNull()
        val idx = listes.indexOfFirst { it.id == currentId }
        if (idx >= 0) {
            binding.acListes.setText(noms[idx], false)
        } else if (result.size == 1) {
            binding.acListes.setText(noms[0], false)
            gnConfig.taxaListeId = result[0].id.toString()
            binding.etTaxaListe.setText(gnConfig.taxaListeId)
        }
    }

    private suspend fun chargerObservateurs(): String? {
        binding.tvErreurObservateurs.visibility = View.GONE
        return try {
            val result = GeoNatureBrowse.chargerObservateurs(gnConfig)
            if (result.isNotEmpty()) {
                peuplerSpinnerObservateurs(result)
                gnConfig.observateursCacheJson = gson.toJson(result)
                null
            } else {
                binding.tvErreurObservateurs.visibility = View.VISIBLE
                binding.tvErreurObservateurs.text = "Aucun observateur retourné par /api/users/roles"
                "Aucun observateur"
            }
        } catch (e: Exception) {
            binding.tvErreurObservateurs.visibility = View.VISIBLE
            binding.tvErreurObservateurs.text = e.message
            e.message ?: "Erreur observateurs"
        }
    }

    private fun peuplerSpinnerObservateurs(result: List<GeoNatureObservateur>) {
        observateurs.clear()
        observateurs.addAll(result)
        val noms = result.map { it.nomComplet }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, noms)
        binding.acObservateurs.setAdapter(adapter)
        binding.tilObservateurs.visibility = View.VISIBLE
        binding.acObservateurs.threshold = 1
        binding.acObservateurs.setOnItemClickListener { _, _, position, _ ->
            val labelChoisi = adapter.getItem(position) ?: return@setOnItemClickListener
            val idx = noms.indexOf(labelChoisi)
            if (idx >= 0) {
                gnConfig.observateurDefautId = observateurs[idx].idRole.toString()
                gnConfig.observateurDefautNom = observateurs[idx].nomComplet
                updateStatusIndicator()
            }
        }
        // Affiche la sélection courante si elle existe. Sinon, on présélectionne
        // automatiquement l'utilisateur connecté (id_role récupéré lors du login) —
        // pratique pour le cas par défaut où l'observateur = celui qui saisit.
        val currentId = gnConfig.observateurDefautId.toIntOrNull()
        var idx = observateurs.indexOfFirst { it.idRole == currentId }
        if (idx < 0) {
            val idRoleConnecte = gnConfig.idRoleUtilisateur.takeIf { it > 0 }
            if (idRoleConnecte != null) {
                idx = observateurs.indexOfFirst { it.idRole == idRoleConnecte }
                if (idx >= 0) {
                    gnConfig.observateurDefautId = observateurs[idx].idRole.toString()
                    gnConfig.observateurDefautNom = observateurs[idx].nomComplet
                    updateStatusIndicator()
                }
            }
        }
        binding.acObservateurs.setText(if (idx >= 0) noms[idx] else "", false)
    }

    /** Restaure les 3 spinners depuis le cache SharedPreferences si présent. */
    private fun restaurerCaches() {
        gnConfig.datasetsCacheJson.takeIf { it.isNotEmpty() }?.let { json ->
            try {
                val t = object : TypeToken<List<GeoNatureDataset>>() {}.type
                val l: List<GeoNatureDataset>? = gson.fromJson(json, t)
                if (!l.isNullOrEmpty()) peuplerSpinnerDatasets(l)
            } catch (_: Exception) {}
        }
        gnConfig.listesCacheJson.takeIf { it.isNotEmpty() }?.let { json ->
            try {
                val t = object : TypeToken<List<GeoNatureListe>>() {}.type
                val l: List<GeoNatureListe>? = gson.fromJson(json, t)
                if (!l.isNullOrEmpty()) peuplerSpinnerListes(l)
            } catch (_: Exception) {}
        }
        gnConfig.observateursCacheJson.takeIf { it.isNotEmpty() }?.let { json ->
            try {
                val t = object : TypeToken<List<GeoNatureObservateur>>() {}.type
                val l: List<GeoNatureObservateur>? = gson.fromJson(json, t)
                if (!l.isNullOrEmpty()) peuplerSpinnerObservateurs(l)
            } catch (_: Exception) {}
        }
    }

    private suspend fun chargerAdditionalFields(): String? {
        return try {
            val result = AdditionalFieldsApi.charger(gnConfig, "OCCTAX")
            if (result.isNotEmpty()) {
                gnConfig.additionalFieldsOcctaxJson = gson.toJson(result)
            } else if (gnConfig.additionalFieldsOcctaxJson.isEmpty()) {
                gnConfig.additionalFieldsOcctaxJson = "[]"
            }
            null
        } catch (e: Exception) {
            e.message ?: "Erreur champs additionnels"
        }
    }

    /** Charge tout depuis le serveur en une action unique :
     *  - datasets, listes, observateurs, additional_fields (parallèle, sucès/échec tracké)
     *  - sync TaxRef exhaustif (toutes les biblistes)
     *  - sync nomenclatures (+ defaults par module)
     *  - sync Suivis (modules + schémas + arborescence structurelle)
     *
     *  Si une ou plusieurs étapes échouent (même partiellement), un bandeau d'avertissement
     *  liste les étapes en échec à la fin pour que l'utilisateur sache que le chargement
     *  n'a pas été complet et qu'il peut relancer. */
    /** Purge les trois caches locaux (TaxRef, nomenclatures, monitoring) en une opération.
     *  Centralisé pour rester cohérent entre le bouton "Vider le cache" et le démarrage d'un
     *  "Recharger les données". Ne touche pas aux JSON SharedPreferences (datasets / listes /
     *  observateurs / additional_fields) : ils sont réécrits naturellement par les fonctions
     *  de chargement à la prochaine sync, et préservés en cas d'échec d'une étape. */
    private fun viderTousLesCaches() {
        TaxRefCache.vider()
        NomenclatureCache.vider()
        MonitoringCache.vider()
    }

    private fun chargerToutesLesDonnees() {
        binding.btnChargerDonnees.isEnabled = false
        binding.btnTesterConnexion.isEnabled = false
        binding.progressSync.visibility = View.VISIBLE
        binding.tvSyncResultat.visibility = View.GONE

        // Purge des caches AVANT chargement : "Recharger" doit repartir d'une ardoise propre
        // côté local, sinon on garde des entrées orphelines (taxons d'une ancienne liste, nomenc-
        // latures d'un module retiré côté serveur, etc.) qui faussent les compteurs et la saisie.
        viderTousLesCaches()
        updateCacheInfo()

        viewLifecycleOwner.lifecycleScope.launch {
            // Étapes 1-4 en parallèle (indépendantes, rapides). Chacune retourne null si OK,
            // sinon un message d'erreur synthétique. On agrège les échecs pour l'avertissement.
            val etapesEnEchec = mutableListOf<String>()
            kotlinx.coroutines.coroutineScope {
                val ds = async { chargerDatasets() }
                val li = async { chargerListes() }
                val obs = async { chargerObservateurs() }
                val add = async { chargerAdditionalFields() }
                listOf(
                    "Jeux de données" to ds.await(),
                    "Listes de taxons" to li.await(),
                    "Observateurs" to obs.await(),
                    "Champs additionnels" to add.await(),
                ).forEach { (nom, err) ->
                    if (err != null) etapesEnEchec += "$nom ($err)"
                }
            }

            // Étape 5 : TaxRef exhaustif.
            val (nbTaxons, msgTaxRef) = GeoNatureSync.synchroniserTaxRef(gnConfig) { fait, listeIdx, listesTotales ->
                activity?.runOnUiThread {
                    binding.tvSyncResultat.visibility = View.VISIBLE
                    binding.tvSyncResultat.text = when {
                        listesTotales == 0 -> "Récupération des listes de taxons…"
                        else -> "Liste $listeIdx/$listesTotales — $fait taxons cumulés…"
                    }
                }
            }
            if (nbTaxons == 0) etapesEnEchec += "TaxRef (${msgTaxRef.take(80)})"

            // Étape 6 : nomenclatures + defaults par module.
            val (nbNom, msgNom) = GeoNatureSync.synchroniserNomenclatures(gnConfig)
            if (nbNom == 0) etapesEnEchec += "Nomenclatures ($msgNom)"

            // Étape 7 : pré-chargement module Suivis (best-effort, ne plante pas l'app).
            val (nbModulesOk, msgSuivis) = MonitoringSync.synchroniserSuivis(gnConfig) { moduleIdx, modulesTotaux, objets ->
                activity?.runOnUiThread {
                    binding.tvSyncResultat.text = when {
                        modulesTotaux == 0 -> "Récupération des protocoles Suivis…"
                        else -> "Suivis : protocole $moduleIdx/$modulesTotaux — $objets objets en cache…"
                    }
                }
            }
            // Le sync Suivis renvoie "Aucun module monitoring exposé" si l'instance n'a pas
            // gn_module_monitoring — ce n'est pas une erreur, on ne le compte pas en échec.
            if (nbModulesOk == 0 && !msgSuivis.startsWith("Aucun")) {
                etapesEnEchec += "Suivis (${msgSuivis.take(80)})"
            }

            binding.progressSync.visibility = View.GONE
            binding.btnChargerDonnees.isEnabled = true
            binding.btnTesterConnexion.isEnabled = true
            binding.tvSyncResultat.visibility = View.VISIBLE
            binding.tvSyncResultat.text = buildString {
                if (etapesEnEchec.isNotEmpty()) {
                    append("⚠ Chargement incomplet — étape(s) en échec :\n")
                    etapesEnEchec.forEach { append("  • $it\n") }
                    append("Vous pouvez relancer « Recharger les données ».\n\n")
                }
                append(msgTaxRef)
                if (nbTaxons > 0 && nbNom == 0) append("\n⚠ Nomenclatures : $msgNom")
                if (nbModulesOk > 0 || msgSuivis.startsWith("Aucun")) append("\nSuivis : $msgSuivis")
            }
            binding.tvSyncResultat.setTextColor(
                // Étape en échec → orange Material (colorSecondary du thème night), sinon
                // texte standard sur surface. Évite #333333 invisible sur le fond accueil.
                if (etapesEnEchec.isNotEmpty())
                    com.google.android.material.color.MaterialColors.getColor(
                        binding.tvSyncResultat, com.google.android.material.R.attr.colorSecondary, 0xFFE65100.toInt(),
                    )
                else couleurSurOnSurface(requireContext())
            )
            updateCacheInfo()
            updateAvertissementListe()
            updateStatusIndicator()

            // Chargement effectif → on bascule en état "Données". Idempotent si on est
            // déjà dans cet état (rechargement utilisateur).
            if (nbTaxons > 0) {
                binding.llSectionDonnees.visibility = View.VISIBLE
                binding.btnChargerDonnees.text = "Recharger les données"
            }
        }
    }

    /** Depuis le sync exhaustif, le cache contient toutes les listes serveur — l'utilisateur
     *  peut donc switcher de liste hors-réseau sans re-sync. On affiche tout de même un
     *  avertissement si la liste actuellement sélectionnée n'est pas couverte par le cache
     *  (cas où le serveur a ajouté une liste depuis la dernière synchro). */
    private fun updateAvertissementListe() {
        val configuree = binding.etTaxaListe.text?.toString()?.trim()?.toIntOrNull()
        val listesCache = TaxRefCache.listesSynchronisees
        val cacheNonVide = TaxRefCache.count > 0
        val absente = configuree != null && cacheNonVide && listesCache.isNotEmpty() && configuree !in listesCache
        binding.tvAvertissementListe.visibility = if (absente) View.VISIBLE else View.GONE
        if (absente) {
            binding.tvAvertissementListe.text =
                "⚠ La liste $configuree n'est pas dans le cache (${listesCache.size} listes synchronisées). Resynchroniser pour l'ajouter."
        }
    }

    private fun updateStatusIndicator() {
        // Pour cet écran on exige les 3 sélections (jeu de données, liste, observateur)
        // ET que les données aient été effectivement chargées (= TaxRef en cache).
        // `estConfiguree` côté store reste plus permissif car il est consommé par les
        // écrans d'envoi (où seul idDataset est requis côté payload OCCTAX).
        val configured = gnConfig.connexionConfiguree
            && gnConfig.idDataset.trim().isNotEmpty()
            && gnConfig.taxaListeId.trim().isNotEmpty()
            && gnConfig.observateurDefautId.trim().isNotEmpty()
            && TaxRefCache.count > 0
        binding.tvStatutConfig.text = if (configured)
            getString(R.string.configuration_complete)
        else
            getString(R.string.configuration_incomplete)
        binding.tvStatutConfig.setTextColor(
            if (configured) 0xFF2E7D32.toInt() else 0xFFE65100.toInt()
        )
    }

    private fun updateCacheInfo() {
        val nbTaxons = TaxRefCache.count
        val nbNomenclatures = NomenclatureCache.count
        val nbProtocoles = MonitoringApi.countModulesEnCache()

        binding.tvCountProtocoles.text = nbProtocoles.toString()
        binding.tvCountNomenclatures.text = nbNomenclatures.toString()
        // Compte brut du cache TaxRef (toutes listes serveur confondues, tous groupes inclus).
        // Le filtre par liste sélectionnée n'est pas appliqué ici : il vaut au moment de la
        // saisie, pas pour mesurer la taille du cache. Il est en revanche reflété dans le
        // dialog "Par groupe" qui montre les effectifs filtrés sur la liste courante.
        binding.tvCountTaxons.text = nbTaxons.toString()

        binding.btnTaxonsParGroupe.isEnabled = nbTaxons > 0
        binding.btnViderCache.isEnabled = nbTaxons > 0 || nbNomenclatures > 0 || nbProtocoles > 0
    }

    /** Ouvre un AlertDialog listant les groupes taxonomiques avec leur effectif (filtré
     *  sur la liste sélectionnée le cas échéant). Un tap sur un groupe ouvre la liste
     *  détaillée de ses taxons via [TaxonsListeFragment]. Groupes vides masqués pour ne
     *  pas afficher des entrées inutiles. */
    private fun montrerTaxonsParGroupe() {
        val idListeFiltre = gnConfig.taxaListeId.trim().toIntOrNull()
        val groupes = listOf(
            "Oiseaux" to Taxon.OISEAU,
            "Mammifères" to Taxon.MAMMIFERE,
            "Reptiles" to Taxon.REPTILE,
            "Amphibiens" to Taxon.BATRACIEN,
            "Poissons" to Taxon.POISSON,
            "Insectes" to Taxon.INSECTE,
            "Fonge" to Taxon.FONGE,
            "Mollusques" to Taxon.MOLLUSQUE,
            "Invertébrés" to Taxon.INVERTEBRES,
            "Plantes" to Taxon.PLANTE,
        ).map { (label, t) ->
            Triple(label, t, TaxRefCache.indexParTaxon(t, idListeFiltre)?.size ?: 0)
        }.filter { it.third > 0 }

        if (groupes.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "Aucun taxon en cache", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val items = groupes.map { "${it.first} (${it.third})" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(if (idListeFiltre != null) "Taxons par groupe (liste $idListeFiltre)" else "Taxons par groupe")
            .setItems(items) { _, which ->
                val (_, taxon, _) = groupes[which]
                findNavController().navigate(
                    R.id.action_config_to_taxons,
                    Bundle().apply { putString("taxonName", taxon.name) },
                )
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}