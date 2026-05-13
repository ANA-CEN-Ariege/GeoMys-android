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
import fr.ariegenature.geonat.network.GeoNatureAuth
import fr.ariegenature.geonat.network.GeoNatureBrowse
import fr.ariegenature.geonat.network.GeoNatureDataset
import fr.ariegenature.geonat.network.GeoNatureListe
import fr.ariegenature.geonat.network.GeoNatureSync
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache
import kotlinx.coroutines.launch

class ConfigGeoNatureFragment : Fragment() {
    private var _binding: FragmentConfigGeonatureBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig
    private val datasets = mutableListOf<GeoNatureDataset>()
    private val listes = mutableListOf<GeoNatureListe>()

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
        binding.etDataset.setText(gnConfig.idDataset)
        binding.etTaxaListe.setText(gnConfig.taxaListeId)

        updateStatusIndicator()
        updateCacheInfo()

        binding.btnTesterConnexion.setOnClickListener {
            sauvegarderChamps()
            testerConnexion()
        }

        binding.btnChargerDatasets.setOnClickListener {
            sauvegarderChamps()
            chargerDatasets()
        }

        binding.btnChargerListes.setOnClickListener {
            sauvegarderChamps()
            chargerListes()
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
        gnConfig.idDataset = binding.etDataset.text.toString()
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
            if (success) { chargerDatasets(); chargerListes() }
        }
    }

    private fun chargerDatasets() {
        binding.btnChargerDatasets.isEnabled = false
        binding.progressDatasets.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = GeoNatureBrowse.chargerDatasets(gnConfig)
                datasets.clear()
                datasets.addAll(result)
                if (result.isNotEmpty()) {
                    val noms = result.map { "${it.nom} (${it.id})" }
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, noms)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerDatasets.adapter = adapter
                    binding.spinnerDatasets.visibility = View.VISIBLE
                    binding.spinnerDatasets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            gnConfig.idDataset = datasets[position].id.toString()
                            binding.etDataset.setText(gnConfig.idDataset)
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                    val currentId = gnConfig.idDataset.toIntOrNull()
                    val idx = datasets.indexOfFirst { it.id == currentId }
                    if (idx >= 0) binding.spinnerDatasets.setSelection(idx)
                }
                binding.tvErreurDatasets.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
                binding.tvErreurDatasets.text = if (result.isEmpty()) "Aucun jeu de données accessible" else ""
            } catch (e: Exception) {
                binding.tvErreurDatasets.visibility = View.VISIBLE
                binding.tvErreurDatasets.text = e.message
            } finally {
                binding.progressDatasets.visibility = View.GONE
                binding.btnChargerDatasets.isEnabled = true
            }
        }
    }

    private fun chargerListes() {
        binding.btnChargerListes.isEnabled = false
        binding.tvErreurListes.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = GeoNatureBrowse.chargerListesTaxons(gnConfig)
                listes.clear()
                listes.addAll(result)
                if (result.isNotEmpty()) {
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
                } else {
                    binding.tvErreurListes.visibility = View.VISIBLE
                    binding.tvErreurListes.text = "Aucune liste de taxons trouvée sur ce serveur"
                }
            } catch (e: Exception) {
                binding.tvErreurListes.visibility = View.VISIBLE
                binding.tvErreurListes.text = e.message
            } finally {
                binding.btnChargerListes.isEnabled = true
            }
        }
    }

    private fun syncTaxRef() {
        binding.btnSyncTaxRef.isEnabled = false
        binding.progressSync.visibility = View.VISIBLE
        binding.tvSyncResultat.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val (_, msgTaxRef) = GeoNatureSync.synchroniserTaxRef(gnConfig) { fait, _ ->
                activity?.runOnUiThread {
                    binding.tvSyncResultat.visibility = View.VISIBLE
                    binding.tvSyncResultat.text = "$fait taxons reçus…"
                }
            }
            // Sync silencieuse des nomenclatures — pas d'affichage du résumé technique
            // (METH_OBS:5val/3taxref…), seulement le compte de taxons indexés.
            GeoNatureSync.synchroniserNomenclatures(gnConfig)
            binding.progressSync.visibility = View.GONE
            binding.btnSyncTaxRef.isEnabled = true
            binding.tvSyncResultat.visibility = View.VISIBLE
            binding.tvSyncResultat.text = msgTaxRef
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
                val nbInv = maxOf(0, regnes.values.count { it == "Animalia" } -
                    (comptes["Oiseaux"] ?: 0) - (comptes["Mammifères"] ?: 0) -
                    (comptes["Reptiles"] ?: 0) - (comptes["Amphibiens"] ?: 0) -
                    (comptes["Poissons"] ?: 0) - (comptes["Insectes"] ?: 0))
                if (nbInv > 0) append(" · $nbInv invertébrés")
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