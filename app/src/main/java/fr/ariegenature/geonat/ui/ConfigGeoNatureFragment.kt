package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentConfigGeonatureBinding
import fr.ariegenature.geonat.network.AdditionalFieldsApi
import fr.ariegenature.geonat.network.GeoNatureAuth
import fr.ariegenature.geonat.network.GeoNatureBrowse
import fr.ariegenature.geonat.network.GeoNatureDataset
import fr.ariegenature.geonat.network.GeoNatureListe
import fr.ariegenature.geonat.network.GeoNatureObservateur
import fr.ariegenature.geonat.network.GeoNatureSync
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache
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

        updateStatusIndicator()
        updateCacheInfo()

        binding.btnTesterConnexion.setOnClickListener {
            sauvegarderChamps()
            testerConnexion()
        }

        binding.btnChargerParametres.setOnClickListener {
            sauvegarderChamps()
            chargerDatasets()
            chargerListes()
            chargerObservateurs()
            chargerAdditionalFields()
        }

        binding.btnSyncTaxRef.setOnClickListener {
            sauvegarderChamps()
            syncTaxRef()
        }

        binding.btnViderCache.setOnClickListener {
            TaxRefCache.vider()
            updateCacheInfo()
        }

        binding.btnFermer.setOnClickListener {
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
        gnConfig.urlServeur = binding.etUrl.text.toString()
        gnConfig.login = binding.etLogin.text.toString()
        gnConfig.motDePasse = binding.etMotDePasse.text.toString()
        // idDataset / nomDataset sont positionnés via le spinner — pas via le champ texte.
        gnConfig.taxaListeId = binding.etTaxaListe.text.toString()
    }

    private fun testerConnexion() {
        binding.btnTesterConnexion.isEnabled = false
        binding.btnChargerParametres.isEnabled = false
        binding.progressTest.visibility = View.VISIBLE
        binding.tvResultatTest.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val (success, msg) = GeoNatureAuth.testerConnexion(gnConfig)
            binding.progressTest.visibility = View.GONE
            binding.btnTesterConnexion.isEnabled = true
            binding.btnChargerParametres.isEnabled = success
            binding.tvResultatTest.visibility = View.VISIBLE
            binding.tvResultatTest.text = msg
            binding.tvResultatTest.setTextColor(
                if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
            updateStatusIndicator()
        }
    }

    private fun chargerDatasets() {
        binding.btnChargerParametres.isEnabled = false
        binding.progressDatasets.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = GeoNatureBrowse.chargerDatasets(gnConfig)
                if (result.isNotEmpty()) {
                    peuplerSpinnerDatasets(result)
                    gnConfig.datasetsCacheJson = gson.toJson(result)
                }
                binding.tvErreurDatasets.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
                binding.tvErreurDatasets.text = if (result.isEmpty()) "Aucun jeu de données accessible" else ""
            } catch (e: Exception) {
                binding.tvErreurDatasets.visibility = View.VISIBLE
                binding.tvErreurDatasets.text = e.message
            } finally {
                binding.progressDatasets.visibility = View.GONE
                binding.btnChargerParametres.isEnabled = true
            }
        }
    }

    private fun peuplerSpinnerDatasets(result: List<GeoNatureDataset>) {
        datasets.clear()
        datasets.addAll(result)
        val noms = result.map { "${it.nom} (${it.id})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, noms)
        binding.acDatasets.setAdapter(adapter)
        binding.tilDatasets.visibility = View.VISIBLE
        binding.acDatasets.threshold = 1
        binding.acDatasets.setOnItemClickListener { _, _, position, _ ->
            // Le position passé est l'index dans la liste filtrée — on retrouve l'objet par son label.
            val labelChoisi = adapter.getItem(position) ?: return@setOnItemClickListener
            val idx = noms.indexOf(labelChoisi)
            if (idx >= 0) {
                gnConfig.idDataset = datasets[idx].id.toString()
                gnConfig.nomDataset = datasets[idx].nom
            }
        }
        // Affiche la sélection courante si elle existe (sinon vide).
        val currentId = gnConfig.idDataset.toIntOrNull()
        val idx = datasets.indexOfFirst { it.id == currentId }
        binding.acDatasets.setText(if (idx >= 0) noms[idx] else "", false)
    }

    private fun chargerListes() {
        binding.btnChargerParametres.isEnabled = false
        binding.tvErreurListes.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = GeoNatureBrowse.chargerListesTaxons(gnConfig)
                if (result.isNotEmpty()) {
                    peuplerSpinnerListes(result)
                    gnConfig.listesCacheJson = gson.toJson(result)
                } else {
                    binding.tvErreurListes.visibility = View.VISIBLE
                    binding.tvErreurListes.text = "Aucune liste de taxons trouvée sur ce serveur"
                }
            } catch (e: Exception) {
                binding.tvErreurListes.visibility = View.VISIBLE
                binding.tvErreurListes.text = e.message
            } finally {
                binding.btnChargerParametres.isEnabled = true
            }
        }
    }

    private fun peuplerSpinnerListes(result: List<GeoNatureListe>) {
        listes.clear()
        listes.addAll(result)
        val noms = result.map { "${it.nom} (${it.id})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, noms)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerListes.adapter = adapter
        binding.spinnerListes.visibility = View.VISIBLE
        binding.tilTaxaListe.visibility = View.GONE
        binding.spinnerListes.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                gnConfig.taxaListeId = listes[position].id.toString()
                binding.etTaxaListe.setText(gnConfig.taxaListeId)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        val currentId = gnConfig.taxaListeId.toIntOrNull()
        val idx = listes.indexOfFirst { it.id == currentId }
        if (idx >= 0) binding.spinnerListes.setSelection(idx)
        else if (result.size == 1) {
            binding.spinnerListes.setSelection(0)
            gnConfig.taxaListeId = result[0].id.toString()
            binding.etTaxaListe.setText(gnConfig.taxaListeId)
        }
    }

    private fun chargerObservateurs() {
        binding.btnChargerParametres.isEnabled = false
        binding.tvErreurObservateurs.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = GeoNatureBrowse.chargerObservateurs(gnConfig)
                if (result.isNotEmpty()) {
                    peuplerSpinnerObservateurs(result)
                    gnConfig.observateursCacheJson = gson.toJson(result)
                } else {
                    binding.tvErreurObservateurs.visibility = View.VISIBLE
                    binding.tvErreurObservateurs.text = "Aucun observateur retourné par /api/users/roles"
                }
            } catch (e: Exception) {
                binding.tvErreurObservateurs.visibility = View.VISIBLE
                binding.tvErreurObservateurs.text = e.message
            } finally {
                binding.btnChargerParametres.isEnabled = true
            }
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
            }
        }
        // Affiche la sélection courante si elle existe (sinon vide).
        val currentId = gnConfig.observateurDefautId.toIntOrNull()
        val idx = observateurs.indexOfFirst { it.idRole == currentId }
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

    private fun chargerAdditionalFields() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = AdditionalFieldsApi.charger(gnConfig, "OCCTAX")
                if (result.isNotEmpty()) gnConfig.additionalFieldsOcctaxJson = gson.toJson(result)
                else if (gnConfig.additionalFieldsOcctaxJson.isEmpty()) {
                    gnConfig.additionalFieldsOcctaxJson = "[]"
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Champs additionnels : ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun syncTaxRef() {
        binding.btnSyncTaxRef.isEnabled = false
        binding.progressSync.visibility = View.VISIBLE
        binding.tvSyncResultat.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val (nbTaxons, msgTaxRef) = GeoNatureSync.synchroniserTaxRef(gnConfig) { fait, _ ->
                activity?.runOnUiThread {
                    binding.tvSyncResultat.visibility = View.VISIBLE
                    binding.tvSyncResultat.text = "$fait taxons reçus…"
                }
            }
            // Sync des nomenclatures (METH_OBS, STADE_VIE, …) : on remonte le message d'erreur
            // s'il y en a un (count = 0 indique un échec côté serveur).
            val (nbNom, msgNom) = GeoNatureSync.synchroniserNomenclatures(gnConfig)
            binding.progressSync.visibility = View.GONE
            binding.btnSyncTaxRef.isEnabled = true
            binding.tvSyncResultat.visibility = View.VISIBLE
            binding.tvSyncResultat.text = buildString {
                append(msgTaxRef)
                if (nbTaxons > 0 && nbNom == 0) append("\n⚠ Nomenclatures : $msgNom")
            }
            updateCacheInfo()
        }
    }

    private fun updateStatusIndicator() {
        val configured = gnConfig.estConfiguree
        binding.tvStatutConfig.text = if (configured)
            getString(R.string.configuration_complete)
        else
            getString(R.string.configuration_incomplete)
        binding.tvStatutConfig.setTextColor(
            if (configured) 0xFF2E7D32.toInt() else 0xFFE65100.toInt()
        )
    }

    private fun updateCacheInfo() {
        val count = TaxRefCache.count
        val comptes = TaxRefCache.comptesGroupes
        val taxrefText = when {
            count == 0 -> "Cache vide"
            comptes.isNotEmpty() -> buildString {
                append("$count taxons en cache")
                (comptes["Oiseaux"] ?: 0).let { if (it > 0) append(" · $it oiseaux") }
                (comptes["Mammifères"] ?: 0).let { if (it > 0) append(" · $it mammifères") }
                (comptes["Reptiles"] ?: 0).let { if (it > 0) append(" · $it reptiles") }
                (comptes["Amphibiens"] ?: 0).let { if (it > 0) append(" · $it batraciens") }
                (comptes["Poissons"] ?: 0).let { if (it > 0) append(" · $it poissons") }
                (comptes["Insectes"] ?: 0).let { if (it > 0) append(" · $it insectes") }
                val regnes = TaxRefCache.tousLesRegnes()
                val nbFonge = regnes.values.count { it == "Fungi" }
                if (nbFonge > 0) append(" · $nbFonge fonge")
                val groupes1 = TaxRefCache.tousLesGroupes1()
                val nbMol = groupes1.values.count { it == "Mollusques" }
                if (nbMol > 0) append(" · $nbMol mollusques")
                val nbInv = maxOf(0, regnes.values.count { it == "Animalia" } -
                    (comptes["Oiseaux"] ?: 0) - (comptes["Mammifères"] ?: 0) -
                    (comptes["Reptiles"] ?: 0) - (comptes["Amphibiens"] ?: 0) -
                    (comptes["Poissons"] ?: 0) - (comptes["Insectes"] ?: 0) - nbMol)
                if (nbInv > 0) append(" · $nbInv autres invertébrés")
                // Plantes : somme des comptes par group2_inpn botanique (cohérent avec sync + iOS).
                val nbPlantes = NomenclatureCache.GROUPES_BOTANIQUES.sumOf { comptes[it] ?: 0 }
                if (nbPlantes > 0) append(" · $nbPlantes plantes")
            }
            else -> "$count taxons en cache"
        }
        binding.tvCacheInfo.text = taxrefText
        binding.btnViderCache.isEnabled = count > 0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}