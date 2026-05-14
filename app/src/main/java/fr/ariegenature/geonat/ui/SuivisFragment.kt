package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentSuivisBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.network.MonitoringModule
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.launch

/** Liste des protocoles (modules) du gn_module_monitoring de l'instance GeoNature. */
class SuivisFragment : Fragment() {
    private var _binding: FragmentSuivisBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSuivisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        gnConfig = GeoNatureConfig(requireContext())

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        chargerModules()
    }

    private fun chargerModules() {
        binding.progressSuivis.visibility = View.VISIBLE
        binding.tvErreurSuivis.visibility = View.GONE
        binding.llModules.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val modules = MonitoringApi.chargerModules(gnConfig)
            if (!isAdded) return@launch
            binding.progressSuivis.visibility = View.GONE
            if (modules.isEmpty()) {
                binding.tvErreurSuivis.visibility = View.VISIBLE
                binding.tvErreurSuivis.text = "Aucun protocole disponible.\n" +
                    "Vérifie que gn_module_monitoring est installé sur le serveur " +
                    "et que tu as les droits CRUVED de lecture."
            } else {
                afficherModules(modules)
            }
        }
    }

    private fun afficherModules(modules: List<MonitoringModule>) {
        val inflater = LayoutInflater.from(requireContext())
        modules.forEach { m ->
            val row = inflater.inflate(R.layout.item_suivi_module, binding.llModules, false)
            row.findViewById<TextView>(R.id.tv_label).text = m.moduleLabel
            row.findViewById<TextView>(R.id.tv_code).text = m.moduleCode
            row.findViewById<TextView>(R.id.tv_desc).apply {
                m.moduleDesc?.let { text = it; visibility = View.VISIBLE }
            }
            row.setOnClickListener {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Module ${m.moduleCode} — saisie à venir.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            binding.llModules.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
