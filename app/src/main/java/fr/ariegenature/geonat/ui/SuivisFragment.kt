package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.os.bundleOf
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
            val modules = try {
                MonitoringApi.chargerModules(gnConfig)
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.progressSuivis.visibility = View.GONE
                binding.tvErreurSuivis.visibility = View.VISIBLE
                binding.tvErreurSuivis.text = "Erreur de chargement : ${e.message}"
                return@launch
            }
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
            row.findViewById<TextView>(R.id.tv_picto).apply {
                val emoji = m.modulePicto?.let { pictoFaEnEmoji(it) }
                if (emoji != null) { text = emoji; visibility = View.VISIBLE }
            }
            row.findViewById<ImageButton>(R.id.btn_info).setOnClickListener {
                findNavController().navigate(
                    R.id.action_suivis_to_detail,
                    bundleOf("moduleCode" to m.moduleCode)
                )
            }
            row.findViewById<ImageButton>(R.id.btn_carte).setOnClickListener {
                findNavController().navigate(
                    R.id.action_suivis_to_carte,
                    bundleOf(
                        "moduleCode" to m.moduleCode,
                        "objectType" to "module",
                        "id" to m.idModule,
                        "titre" to m.moduleLabel,
                    )
                )
            }
            binding.llModules.addView(row)
        }
    }

    /** Mappe un code FontAwesome déclaré dans `module_picto` vers un emoji unicode équivalent.
     *  Couvre les codes les plus utilisés en gn_module_monitoring (faune/flore/géo). Fallback :
     *  emoji "presse-papier" générique pour les codes non listés. Retourne null si pas de code. */
    private fun pictoFaEnEmoji(picto: String): String? {
        if (picto.isEmpty()) return null
        return when (picto.lowercase().removePrefix("fa-").removePrefix("fas-").removePrefix("far-")) {
            "puzzle-piece", "puzzle" -> "🧩"
            "bird", "dove", "crow", "feather" -> "🐦"
            "leaf", "seedling" -> "🌿"
            "tree" -> "🌳"
            "fish" -> "🐟"
            "bug" -> "🐛"
            "spider" -> "🕷️"
            "paw" -> "🐾"
            "frog" -> "🐸"
            "snake" -> "🐍"
            "horse" -> "🐎"
            "cow" -> "🐄"
            "dog" -> "🐕"
            "cat" -> "🐈"
            "deer" -> "🦌"
            "rabbit" -> "🐇"
            "mouse" -> "🐁"
            "flower", "fan" -> "🌸"
            "mountain" -> "⛰️"
            "water", "tint", "droplet" -> "💧"
            "map", "map-marker", "map-pin", "location-dot" -> "📍"
            "binoculars" -> "🔭"
            "camera" -> "📷"
            "ear-listen", "headphones" -> "🎧"
            "compass" -> "🧭"
            "ruler", "ruler-combined" -> "📏"
            "clipboard", "clipboard-list", "list", "list-ul" -> "📋"
            "book" -> "📖"
            "calendar", "calendar-days" -> "📅"
            "globe", "earth", "globe-europe" -> "🌍"
            "sun" -> "☀️"
            "cloud" -> "☁️"
            "user", "users" -> "👤"
            else -> "📋"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
