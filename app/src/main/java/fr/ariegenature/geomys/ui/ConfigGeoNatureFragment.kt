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

package fr.ariegenature.geomys.ui

import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.appcompat.app.AlertDialog
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentConfigGeonatureBinding
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.GeoNatureAuth
import fr.ariegenature.geomys.network.GeoNatureDataset
import fr.ariegenature.geomys.network.GeoNatureListe
import fr.ariegenature.geomys.network.GeoNatureObservateur
import fr.ariegenature.geomys.network.GeoNatureSync
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.network.SyncRunner
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.sync.SyncForegroundService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

class ConfigGeoNatureFragment : Fragment() {
    private var _binding: FragmentConfigGeonatureBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig
    private val datasets = mutableListOf<GeoNatureDataset>()
    private val listes = mutableListOf<GeoNatureListe>()
    private val observateurs = mutableListOf<GeoNatureObservateur>()
    private val gson = Gson()

    /** Vrai après un « Vider le cache » et jusqu'au prochain rechargement : on repart d'une
     *  ardoise vierge, donc le rechargement ne doit PAS ré-appliquer d'auto-défaut (liste unique,
     *  observateur = utilisateur connecté). L'utilisateur re-choisit explicitement ses 3 sélections. */
    private var selectionsReinitialisees = false

    /** Debounce du recalcul déclenché par la saisie manuelle de l'id de liste (évite le jank).
     *  Sur viewLifecycleOwner.lifecycleScope → annulé automatiquement à la destruction de la vue. */
    private var avertissementListeJob: kotlinx.coroutines.Job? = null

    /** Demande de permission POST_NOTIFICATIONS (Android 13+) pour la notification du service de
     *  synchro. Enregistré à la construction du fragment (avant STARTED), comme l'exige l'API. */
    private val demandeNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

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

        // Reflète une éventuelle synchro en cours/terminée tournant dans le service de fond
        // (si l'utilisateur revient sur l'écran pendant ou après un « Recharger les données »).
        observerSyncEnArrierePlan()

        // État initial des sections selon la présence de données en cache.
        // - Cache TaxRef non vide → l'utilisateur a déjà chargé : on montre tout
        //   (section "Charger" affiche "Recharger…", section "Données" visible).
        // - Sinon → seul le bloc connexion est visible, l'utilisateur doit se connecter
        //   puis cliquer sur "Charger les données".
        val donneesPresentes = TaxRefCache.count > 0
        // La boîte « Chargement des données » n'apparaît QUE lorsque la connexion a réussi — pas
        // seulement parce que des données sont en cache. Révélée par le test de connexion (manuel)
        // OU par la vérification de version au démarrage si le serveur répond (cf. plus bas).
        binding.llSectionCharger.visibility = View.GONE
        // Le résumé du cache et les sélecteurs restent liés à la présence de données (utilisables
        // hors-ligne), mais le résumé étant DANS la boîte 2, il ne s'affiche qu'avec elle.
        binding.llCacheResume.visibility = if (donneesPresentes) View.VISIBLE else View.GONE
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
                // Recalcul DEBOUNCÉ : updateAvertissementListe lit cdNomsDansListe (parcours du
                // cache) et updateCacheInfo relit les caches — le faire à chaque frappe sur le
                // thread UI provoquait du jank lors de la saisie manuelle de l'id de liste.
                avertissementListeJob?.cancel()
                avertissementListeJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(300)
                    updateAvertissementListe()
                    updateCacheInfo()
                    updateStatusIndicator()
                }
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
        // Appui long = rechargement COMPLET forcé (re-télécharge TaxRef même si la version est
        // inchangée). Le clic normal saute TaxRef quand il est déjà à jour.
        binding.btnChargerDonnees.setOnLongClickListener {
            sauvegarderChamps()
            android.widget.Toast.makeText(requireContext(),
                "Rechargement complet forcé (taxons)", android.widget.Toast.LENGTH_SHORT).show()
            chargerToutesLesDonnees(forcerTaxRef = true)
            true
        }

        binding.btnViderCache.setOnClickListener {
            viderTousLesCaches()
            // « Vider le cache » est un reset explicite : on efface aussi les 3 sélections OCCTAX
            // (jeu de données / liste / observateur) et leurs caches, pour repartir avec des
            // champs select vierges. Sans ça, le rechargement ré-appliquait les anciennes valeurs.
            reinitialiserSelectionsOcctax()
            updateCacheInfo()
            updateAvertissementListe()
            updateStatusIndicator()
            // Sans cache, on repasse en état "Charger les données" — le bouton change
            // de libellé mais la section reste visible si la connexion est OK.
            binding.btnChargerDonnees.text = "Charger les données"
            binding.llCacheResume.visibility = View.GONE
            binding.llSectionDonnees.visibility = View.GONE
        }

        binding.btnTaxonsParGroupe.setOnClickListener { montrerTaxonsParListe() }

        binding.fabValider.setOnClickListener {
            sauvegarderChamps()
            findNavController().navigateUp()
        }

        // Version de l'instance GeoNature relevée au dernier test de connexion réussi —
        // rafraîchie par testerConnexion(), vidée au changement d'identité serveur.
        afficherVersionGeoNature()

        // Vérifier version TaxRef serveur
        if (gnConfig.connexionConfiguree) {
            viewLifecycleOwner.lifecycleScope.launch {
                val version = GeoNatureSync.verifierVersionTaxRef(gnConfig)
                if (version != null) {
                    // Le serveur a répondu → la connexion fonctionne → on révèle la boîte 2.
                    binding.llSectionCharger.visibility = View.VISIBLE
                    // On affiche simplement la version de TaxRef CHARGÉE (= cache local). Si le
                    // serveur expose une version plus récente, on l'indique discrètement.
                    val cached = TaxRefCache.versionSauvegardee
                    if (cached != null) {
                        binding.tvTaxRefVersion.visibility = View.VISIBLE
                        if (cached != version) {
                            binding.tvTaxRefVersion.text = "TaxRef v$cached — maj v$version disponible"
                            binding.tvTaxRefVersion.setTextColor(
                                androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
                            )
                        } else {
                            binding.tvTaxRefVersion.text = "TaxRef v$cached"
                        }
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
            fr.ariegenature.geomys.network.invaliderCachesSession()
            // La version affichée appartient à l'ancienne instance — re-renseignée au
            // prochain test de connexion réussi. Le flag de compatibilité repart au bénéfice
            // du doute : le nouveau serveur sera re-jugé à son premier test de connexion.
            gnConfig.versionGeoNatureServeur = ""
            gnConfig.serveurCompatible = true
            // Changement d'IDENTITÉ serveur : les ids de sélection (dataset/liste/observateur) du
            // serveur précédent n'ont plus aucun sens — ils peuvent même coïncider avec d'AUTRES
            // entités sur le nouveau serveur (ex. liste id 100 ou observateur id 3 qui existent
            // sur les deux mais désignent autre chose). La réconciliation par présence ne suffit
            // donc pas ici : on réinitialise franchement les 3 sélections. L'observateur se
            // re-défaut sur l'utilisateur connecté au prochain peuplement (cf. peuplerSpinnerObservateurs).
            reinitialiserSelectionsOcctax()
            return // les champs ont été vidés ; rien d'autre à sauvegarder pour cette passe.
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
                afficherVersionGeoNature()  // version serveur relevée pendant le test
            }
        }
    }

    /** Affiche la version de l'instance GeoNature mémorisée au dernier test de connexion
     *  réussi (masqué si inconnue — vieux serveur sans /api/gn_commons/config). */
    private fun afficherVersionGeoNature() {
        val version = gnConfig.versionGeoNatureServeur
        binding.tvVersionGeonature.visibility = if (version.isEmpty()) View.GONE else View.VISIBLE
        if (version.isNotEmpty()) binding.tvVersionGeonature.text = "Serveur GeoNature v$version"
    }

    /** Retourne null si OK, sinon un message d'erreur — exploité par chargerToutesLesDonnees
     *  pour agréger les étapes en échec dans l'avertissement final. */
    private fun peuplerSpinnerDatasets(result: List<GeoNatureDataset>) {
        // Mémorise TOUS les datasets (utilisés par les écrans monitoring pour résoudre
        // un id_dataset précis selon le protocole). Mais ne propose à l'écran de config
        // OCCTAX que ceux **actifs** ET **rattachés au module OCCTAX**.
        datasets.clear()
        datasets.addAll(result)
        // Restriction aux datasets CRÉABLES (CRUVED C, comme le web). Set vide ⇒ pas de restriction.
        val creables = gnConfig.datasetsCreablesOcctax
        val proposes = result.filter {
            it.actif && (it.moduleCodes.isEmpty() || "OCCTAX" in it.moduleCodes) &&
                (creables.isEmpty() || it.id in creables)
        }
        // Note : moduleCodes vide = on garde le dataset (fallback safe pour les instances qui
        // n'exposent pas le tableau modules — sinon on n'aurait rien à proposer).
        val noms = proposes.map { "${it.nom} (${it.id})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, noms)
        binding.acDatasets.setAdapter(adapter)
        binding.tilDatasets.visibility = View.VISIBLE
        binding.acDatasets.threshold = 1
        binding.acDatasets.setOnItemClickListener { _, _, position, _ ->
            val labelChoisi = adapter.getItem(position) ?: return@setOnItemClickListener
            masquerClavier(binding.acDatasets)
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
        if (idx >= 0) {
            binding.acDatasets.setText(noms[idx], false)
            // Rafraîchit le nom stocké depuis le cache (corrige une dérive id↔nom après re-sync).
            gnConfig.nomDataset = proposes[idx].nom
            // Restaure la restriction au démarrage si le dataset courant a une liste imposée.
            appliquerRestrictionListeDataset(proposes[idx])
        } else {
            // Sélection stockée ABSENTE de la liste valide (dataset devenu inactif, supprimé, ou
            // hérité d'un autre serveur / d'un état antérieur après re-sync) : on PURGE le fantôme
            // pour que les prefs == l'affichage (sinon un id stale validait la config à tort).
            if (gnConfig.idDataset.isNotEmpty() || gnConfig.nomDataset.isNotEmpty()) {
                gnConfig.idDataset = ""
                gnConfig.nomDataset = ""
            }
            binding.acDatasets.setText("", false)
        }
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
            masquerClavier(binding.acListes)
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
        } else if (result.size == 1 && !selectionsReinitialisees) {
            // Auto-sélection de l'unique liste — sauf juste après un reset, où l'on laisse vide.
            binding.acListes.setText(noms[0], false)
            gnConfig.taxaListeId = result[0].id.toString()
            binding.etTaxaListe.setText(gnConfig.taxaListeId)
        } else {
            // Sélection stockée absente du cache courant (après re-sync) → purge du fantôme.
            if (gnConfig.taxaListeId.isNotEmpty()) {
                gnConfig.taxaListeId = ""
                binding.etTaxaListe.setText("")
            }
            binding.acListes.setText("", false)
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
            masquerClavier(binding.acObservateurs)
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
            // Présélection de l'utilisateur connecté comme observateur par défaut — y compris
            // après un « Vider le cache » : contrairement au dataset et à la liste (laissés
            // vierges), l'observateur retombe toujours sur celui qui saisit.
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
        if (idx >= 0) {
            // Rafraîchit le nom stocké (corrige une dérive id↔nom après re-sync).
            gnConfig.observateurDefautNom = observateurs[idx].nomComplet
            binding.acObservateurs.setText(noms[idx], false)
        } else {
            // Sélection stockée absente du cache courant (et utilisateur connecté introuvable) →
            // purge du fantôme.
            if (gnConfig.observateurDefautId.isNotEmpty() || gnConfig.observateurDefautNom.isNotEmpty()) {
                gnConfig.observateurDefautId = ""
                gnConfig.observateurDefautNom = ""
            }
            binding.acObservateurs.setText("", false)
        }
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

    /** Réinitialise les 3 sélections OCCTAX (jeu de données, liste, observateur) : valeurs
     *  persistées, caches JSON et champs affichés. Arme [selectionsReinitialisees] pour que le
     *  prochain rechargement n'auto-sélectionne aucun défaut. Appelé par « Vider le cache ». */
    private fun reinitialiserSelectionsOcctax() {
        gnConfig.idDataset = ""
        gnConfig.nomDataset = ""
        gnConfig.taxaListeId = ""
        gnConfig.observateurDefautId = ""
        gnConfig.observateurDefautNom = ""
        gnConfig.datasetsCacheJson = ""
        gnConfig.listesCacheJson = ""
        gnConfig.observateursCacheJson = ""
        gnConfig.additionalFieldsOcctaxJson = ""
        datasets.clear()
        listes.clear()
        observateurs.clear()
        // Vide les champs affichés et lève une éventuelle restriction de liste imposée par un
        // ancien dataset (réactive le contrôle des listes).
        binding.acDatasets.setText("", false)
        binding.acListes.setText("", false)
        binding.acObservateurs.setText("", false)
        binding.etTaxaListe.setText("")
        binding.acListes.isEnabled = true
        binding.tvErreurListes.visibility = View.GONE
        selectionsReinitialisees = true
    }

    /** Purge les trois caches locaux (TaxRef, nomenclatures, monitoring) en une opération.
     *  Utilisé par le bouton « Vider le cache ». Le rechargement, lui, purge via [SyncRunner].
     *  Ne touche pas aux JSON SharedPreferences (datasets / listes / observateurs). */
    private fun viderTousLesCaches() {
        TaxRefCache.vider()
        NomenclatureCache.vider()
        fr.ariegenature.geomys.store.HabitatCache.vider()
        MonitoringCache.vider()
        // MonitoringCache.vider() n'efface que le DISQUE. La liste des modules est aussi
        // gardée en mémoire par MonitoringApi (dernierChargement), et countModulesEnCache()
        // la renvoie en priorité — sans cette invalidation, le compteur de protocoles reste
        // figé (ex. 12) après un « Vider le cache » / « Recharger les données ».
        MonitoringApi.invaliderCaches()
    }

    /** Lance le chargement complet des données dans un **service au premier plan**
     *  ([SyncForegroundService]) : il CONTINUE même si l'utilisateur quitte l'écran, met le
     *  téléphone en veille ou passe l'app en arrière-plan. L'écran se contente de démarrer le
     *  service ; la progression et le résultat sont reflétés par l'observateur de
     *  [SyncRunner.etat] (cf. [observerSyncEnArrierePlan]). Les caches sont purgés puis réécrits
     *  par [SyncRunner] ; le re-peuplement des spinners se fait à la fin via [restaurerCaches]. */
    private fun chargerToutesLesDonnees(forcerTaxRef: Boolean = false) {
        demanderPermissionNotifSiBesoin()
        // Purge un éventuel état terminal résiduel pour ne pas le rejouer en bandeau.
        SyncRunner.accuserReception()
        binding.btnChargerDonnees.isEnabled = false
        binding.btnTesterConnexion.isEnabled = false
        binding.progressSync.visibility = View.VISIBLE
        binding.tvSyncResultat.visibility = View.VISIBLE
        binding.tvSyncResultat.text =
            if (forcerTaxRef) "Rechargement complet (taxons forcés)…" else "Démarrage de la synchronisation…"
        binding.tvSyncResultat.setTextColor(couleurSurOnSurface(requireContext()))
        SyncForegroundService.start(requireContext(), forcerTaxRef)
    }

    /** Reflète l'état de la synchro de fond ([SyncRunner.etat]) dans l'UI de l'écran de config :
     *  bandeau de progression pendant, résumé + re-peuplement des spinners à la fin. Lifecycle-
     *  aware : si l'utilisateur quitte puis revient sur l'écran pendant une synchro, il retrouve
     *  la progression en cours ; un état terminal manqué est rejoué à son retour. */
    private fun observerSyncEnArrierePlan() {
        SyncRunner.etat.observe(viewLifecycleOwner) { etat ->
            if (etat == null) return@observe
            when {
                etat.enCours -> {
                    binding.btnChargerDonnees.isEnabled = false
                    binding.btnTesterConnexion.isEnabled = false
                    binding.progressSync.visibility = View.VISIBLE
                    binding.tvSyncResultat.visibility = View.VISIBLE
                    binding.tvSyncResultat.text = etat.texte
                    binding.tvSyncResultat.setTextColor(couleurSurOnSurface(requireContext()))
                    updateCacheInfo()
                }
                etat.termine -> {
                    // Re-peuple les spinners depuis les caches fraîchement écrits AVANT de
                    // désarmer le mode reset (sinon la liste unique se ré-auto-sélectionnerait).
                    restaurerCaches()
                    selectionsReinitialisees = false
                    binding.progressSync.visibility = View.GONE
                    binding.btnChargerDonnees.isEnabled = true
                    binding.btnTesterConnexion.isEnabled = true
                    binding.tvSyncResultat.visibility = View.VISIBLE
                    // Le détail (compteurs) est dans la boîte « Chargement des données » : ici on
                    // n'affiche que les avertissements éventuels, sinon un simple « Chargement terminé ».
                    binding.tvSyncResultat.text =
                        (etat.resume ?: etat.texte).trim().ifBlank { "Chargement terminé" }
                    binding.tvSyncResultat.setTextColor(
                        if (!etat.succes)
                            com.google.android.material.color.MaterialColors.getColor(
                                binding.tvSyncResultat, com.google.android.material.R.attr.colorSecondary, 0xFFE65100.toInt(),
                            )
                        else couleurSurOnSurface(requireContext())
                    )
                    updateCacheInfo()
                    updateAvertissementListe()
                    updateStatusIndicator()
                    if (TaxRefCache.count > 0) {
                        binding.llCacheResume.visibility = View.VISIBLE
                        binding.llSectionDonnees.visibility = View.VISIBLE
                        binding.btnChargerDonnees.text = "Recharger les données"
                    }
                    // Consomme l'état terminal pour ne pas le rejouer à chaque réouverture.
                    SyncRunner.accuserReception()
                }
            }
        }
    }

    /** Demande POST_NOTIFICATIONS (Android 13+) au moment de lancer une synchro : la notification
     *  du service au premier plan ne s'affiche pas sans elle. Best-effort — le refus n'empêche
     *  pas la synchro de tourner, seul l'affichage de la notification est perdu. */
    private fun demanderPermissionNotifSiBesoin() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            demandeNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Depuis le sync exhaustif, le cache contient toutes les listes serveur — l'utilisateur
     *  peut donc switcher de liste hors-réseau sans re-sync. On affiche un avertissement sous
     *  le champ si la liste sélectionnée :
     *   - n'est pas couverte par le cache (ajoutée côté serveur depuis la dernière synchro), OU
     *   - est bien synchronisée mais ne contient **aucun taxon** (la saisie n'aura rien à proposer). */
    private fun updateAvertissementListe() {
        val configuree = binding.etTaxaListe.text?.toString()?.trim()?.toIntOrNull()
        val listesCache = TaxRefCache.listesSynchronisees
        val cacheNonVide = TaxRefCache.count > 0
        // (texte, estAvertissement) ; estAvertissement=false → simple info (nb de taxons).
        val (message, avertissement) = when {
            configuree == null || !cacheNonVide || listesCache.isEmpty() -> null to false
            configuree !in listesCache ->
                "⚠ La liste $configuree n'est pas dans le cache (${listesCache.size} listes synchronisées). Resynchroniser pour l'ajouter." to true
            else -> {
                val n = TaxRefCache.cdNomsDansListe(configuree).size
                if (n == 0)
                    "⚠ La liste $configuree ne contient aucun taxon — la saisie n'aura rien à proposer. Vérifier la liste côté serveur." to true
                else
                    "Liste $configuree : $n taxon${if (n > 1) "s" else ""} en cache." to false
            }
        }
        binding.tvAvertissementListe.visibility = if (message != null) View.VISIBLE else View.GONE
        if (message != null) {
            binding.tvAvertissementListe.text = message
            binding.tvAvertissementListe.setTextColor(
                if (avertissement) 0xFFE65100.toInt() else couleurSurOnSurface(requireContext())
            )
        }
    }

    private fun updateStatusIndicator() {
        // Validation STRICTE (durcie) : il ne suffit PLUS que les champs soient non vides — on
        // exige que le jeu de données, la liste ET l'observateur sélectionnés soient réellement
        // PRÉSENTS dans les caches du serveur courant (cf. saisieOcctaxValide). Sans ça, un id
        // fantôme (ex. dataset devenu inactif, donc absent du cache filtré active=true) passait
        // pour « complet » alors qu'une saisie partait dans le vide / invisible côté serveur.
        // On exige en plus que les taxons aient été chargés.
        val configured = gnConfig.saisieOcctaxValide && TaxRefCache.count > 0
        binding.tvStatutConfig.text = if (configured)
            getString(R.string.configuration_complete)
        else
            getString(R.string.configuration_incomplete)
        binding.tvStatutConfig.setTextColor(
            if (configured) 0xFF2E7D32.toInt() else 0xFFE65100.toInt()
        )
        // Coche du bouton « Valider » : verte si config valide, orange tant qu'elle ne l'est pas.
        binding.fabValider.setImageResource(
            if (configured) R.drawable.coche else R.drawable.coche_orange
        )
    }

    private fun updateCacheInfo() {
        // Taxons UNIQUES (cd_nom distincts) — et non le nombre de clés de noms (un taxon a
        // plusieurs clés : scientifique + vernaculaires), sinon le total affiché (ex. 230) ne
        // correspond pas à la somme du détail par liste.
        val nbTaxons = TaxRefCache.nbTaxonsUniques
        val nbNomenclatures = NomenclatureCache.count
        val nbProtocoles = MonitoringApi.countModulesEnCache()
        // Listes de taxons et observateurs en cache (comptés depuis les caches JSON du serveur).
        val nbListes = try {
            val t = object : TypeToken<List<GeoNatureListe>>() {}.type
            (gson.fromJson<List<GeoNatureListe>>(gnConfig.listesCacheJson, t) ?: emptyList()).size
        } catch (_: Exception) { 0 }
        val nbObservateurs = try {
            val t = object : TypeToken<List<GeoNatureObservateur>>() {}.type
            (gson.fromJson<List<GeoNatureObservateur>>(gnConfig.observateursCacheJson, t) ?: emptyList()).size
        } catch (_: Exception) { 0 }

        binding.tvCountProtocoles.text = nbProtocoles.toString()
        binding.tvCountNomenclatures.text = nbNomenclatures.toString()
        binding.tvCountTaxons.text = nbTaxons.toString()
        binding.tvCountListes.text = nbListes.toString()
        binding.tvCountObservateurs.text = nbObservateurs.toString()

        binding.btnTaxonsParGroupe.isEnabled = nbTaxons > 0
        binding.btnViderCache.isEnabled = nbTaxons > 0 || nbNomenclatures > 0 || nbProtocoles > 0
    }

    /** Ouvre un AlertDialog listant les groupes taxonomiques avec leur effectif (filtré
     *  sur la liste sélectionnée le cas échéant). Un tap sur un groupe ouvre la liste
     *  détaillée de ses taxons via [TaxonsListeFragment]. Groupes vides masqués pour ne
     *  pas afficher des entrées inutiles. */
    /** « Détails » du panneau cache : présente TOUT le cache de taxons regroupé **par liste**
     *  (indépendamment de la liste sélectionnée). Chaque ligne = une liste présente dans le cache
     *  avec son nombre de taxons ; un tap ouvre la liste détaillée de ses taxons. */
    private fun montrerTaxonsParListe() {
        val comptes = TaxRefCache.comptesParListe() // id_liste -> nb taxons
        if (comptes.isEmpty()) {
            android.widget.Toast.makeText(
                requireContext(),
                "Aucun taxon en cache (ou appartenance aux listes non synchronisée).",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        // Noms des listes depuis le cache JSON (fallback « Liste <id> »).
        val nomsListes: Map<Int, String> = try {
            val t = object : TypeToken<List<GeoNatureListe>>() {}.type
            (gson.fromJson<List<GeoNatureListe>>(gnConfig.listesCacheJson, t) ?: emptyList())
                .associate { it.id to it.nom }
        } catch (_: Exception) { emptyMap() }

        // Listes rattachées à des protocoles monitoring (id_list_taxonomy, filtré CRUVED) +
        // nombre de taxons UNIQUES couverts par ces listes (union, pas de double comptage).
        val listesProtocoles = MonitoringApi.listesTaxonomieProtocolesEnCache()
        val cdNomsProtocoles = listesProtocoles.flatMap { TaxRefCache.cdNomsDansListe(it) }.toSet()

        val lignes = comptes.entries.sortedByDescending { it.value }
        val items = lignes.map { (id, n) ->
            val nom = nomsListes[id] ?: "Liste $id"
            val marqueProto = if (id in listesProtocoles) " · protocole" else ""
            "$nom ($id) — $n taxon${if (n > 1) "s" else ""}$marqueProto"
        }.toTypedArray()
        val titre = buildString {
            append("Taxons par liste")
            if (cdNomsProtocoles.isNotEmpty())
                append(" — ${cdNomsProtocoles.size} via protocoles")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(titre)
            .setItems(items) { _, which ->
                val id = lignes[which].key
                findNavController().naviguerSur(
                    R.id.action_config_to_taxons,
                    Bundle().apply {
                        putInt("idListe", id)
                        putString("nomListe", nomsListes[id] ?: "Liste $id")
                    },
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