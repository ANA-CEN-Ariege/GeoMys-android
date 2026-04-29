package com.example.birdstrace.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.R
import com.example.birdstrace.databinding.FragmentConfigGeonatureBinding
import com.example.birdstrace.network.GeoNatureDataset
import com.example.birdstrace.network.GeoNatureService
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.TaxRefCache
import kotlinx.coroutines.launch

class ConfigGeoNatureFragment : Fragment() {
    private var _binding: FragmentConfigGeonatureBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig
    private val datasets = mutableListOf<GeoNatureDataset>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConfigGeonatureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gnConfig = GeoNatureConfig(requireContext())

        binding.etUrl.setText(gnConfig.urlServeur)
        binding.etLogin.setText(gnConfig.login)
        binding.etMotDePasse.setText(gnConfig.motDePasse)
        binding.etDataset.setText(gnConfig.idDataset)

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
                val version = GeoNatureService.verifierVersionTaxRef(gnConfig)
                if (version != null) {
                    val cached = TaxRefCache.versionSauvegardee
                    binding.tvTaxRefVersion.visibility = View.VISIBLE
                    binding.tvTaxRefVersion.text = if (cached != null && cached != version)
                        "TaxRef : mise à jour v$version (cache v$cached)"
                    else if (cached == version)
                        "TaxRef v$version — à jour"
                    else
                        "TaxRef v$version disponible"
                }
            }
        }
    }

    private fun sauvegarderChamps() {
        gnConfig.urlServeur = binding.etUrl.text.toString()
        gnConfig.login = binding.etLogin.text.toString()
        gnConfig.motDePasse = binding.etMotDePasse.text.toString()
        gnConfig.idDataset = binding.etDataset.text.toString()
    }

    private fun testerConnexion() {
        binding.btnTesterConnexion.isEnabled = false
        binding.progressTest.visibility = View.VISIBLE
        binding.tvResultatTest.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val (success, msg) = GeoNatureService.testerConnexion(gnConfig)
            binding.progressTest.visibility = View.GONE
            binding.btnTesterConnexion.isEnabled = true
            binding.tvResultatTest.visibility = View.VISIBLE
            binding.tvResultatTest.text = msg
            binding.tvResultatTest.setTextColor(
                if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
            updateStatusIndicator()
            if (success) chargerDatasets()
        }
    }

    private fun chargerDatasets() {
        binding.btnChargerDatasets.isEnabled = false
        binding.progressDatasets.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = GeoNatureService.chargerDatasets(gnConfig)
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

    private fun syncTaxRef() {
        binding.btnSyncTaxRef.isEnabled = false
        binding.progressSync.visibility = View.VISIBLE
        binding.tvSyncResultat.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val (_, msg) = GeoNatureService.synchroniserTaxRef(gnConfig) { fait, _ ->
                activity?.runOnUiThread {
                    binding.tvSyncResultat.visibility = View.VISIBLE
                    binding.tvSyncResultat.text = "$fait taxons reçus…"
                }
            }
            binding.progressSync.visibility = View.GONE
            binding.btnSyncTaxRef.isEnabled = true
            binding.tvSyncResultat.visibility = View.VISIBLE
            binding.tvSyncResultat.text = msg
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
        binding.tvCacheInfo.text = when {
            count == 0 -> "Cache vide"
            comptes.isNotEmpty() -> {
                val nbO = comptes["Oiseaux"] ?: 0
                val nbM = comptes["Mammifères"] ?: 0
                val nbR = comptes["Reptiles"] ?: 0
                buildString {
                    append("$count taxons en cache")
                    if (nbO > 0) append(" · $nbO oiseaux")
                    if (nbM > 0) append(" · $nbM mammifères")
                    if (nbR > 0) append(" · $nbR reptiles")
                }
            }
            else -> "$count taxons en cache"
        }
        binding.btnViderCache.isEnabled = count > 0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}