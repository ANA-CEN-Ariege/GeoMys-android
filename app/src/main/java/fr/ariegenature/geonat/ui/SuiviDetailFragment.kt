package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.databinding.FragmentSuiviDetailBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.network.MonitoringModule
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Détail d'un protocole de suivi (gn_module_monitoring).
 *  Reçoit le `moduleCode` en argument et lit le module dans le cache de [MonitoringApi]
 *  (alimenté par l'écran liste). Affiche le résumé du protocole + la liste des sites. */
class SuiviDetailFragment : Fragment() {
    private var _binding: FragmentSuiviDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSuiviDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)

        val moduleCode = arguments?.getString("moduleCode") ?: run {
            findNavController().navigateUp()
            return
        }

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        binding.progressDetail.visibility = View.GONE
        val module = MonitoringApi.moduleParCode(moduleCode)
        if (module == null) {
            // Cache vidé (process tué, navigation directe) — fallback minimal sur le code.
            binding.tvLabel.text = moduleCode
            binding.tvCode.text = moduleCode
            binding.tvErreurDetail.visibility = View.VISIBLE
            binding.tvErreurDetail.text = "Recharge la liste pour voir les détails."
            return
        }
        afficherModule(module)
        chargerEnfants(module.moduleCode)
    }

    private fun afficherModule(m: MonitoringModule) {
        binding.tvLabel.text = m.moduleLabel
        binding.tvCode.text = m.moduleCode
        m.moduleDesc?.let {
            binding.tvDesc.text = it
            binding.tvDesc.visibility = View.VISIBLE
        }
    }

    private fun chargerEnfants(moduleCode: String) {
        binding.tvNbSites.visibility = View.VISIBLE
        binding.tvNbSites.text = "Sites : …"
        binding.llSites.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val config = GeoNatureConfig(requireContext())
            // Les deux endpoints sont indépendants — on les lance en parallèle.
            val enfantsDeferred = async { MonitoringApi.chargerEnfants(config, moduleCode) }
            val labelsDeferred = async { MonitoringApi.chargerLabelsObjets(config, moduleCode) }
            val enfants = enfantsDeferred.await()
            val labels = labelsDeferred.await()
            if (!isAdded) return@launch
            if (enfants.isNullOrEmpty()) {
                binding.tvNbSites.visibility = View.GONE
                return@launch
            }
            val counts = enfants.mapValues { it.value.size }
            // Hiérarchie 2 niveaux (sites_group → site) : sites_group en tête, site juste après.
            // L'ordre des autres types suit l'insertion JSON.
            val typesOrdonnes: List<String> = if (enfants.containsKey("sites_group") && enfants.containsKey("site")) {
                listOf("sites_group", "site") +
                    enfants.keys.filter { it != "sites_group" && it != "site" }
            } else enfants.keys.toList()

            // Le résumé en haut compte TOUS les types (utile pour savoir qu'il y a 70 points
            // d'écoute derrière les 8 sites STOM).
            binding.tvNbSites.text = typesOrdonnes.joinToString("  ·  ") { type ->
                "${resolveLabel(type, counts, labels)} : ${counts[type] ?: 0}"
            }
            // En revanche, la LISTE n'affiche que le niveau macro : quand un protocole a un
            // sites_group, le `site` n'est qu'un sous-élément (point d'écoute, station…) — on
            // le masque pour ne pas noyer les vrais sites de terrain.
            val typesAfficher: List<String> = if (enfants.containsKey("sites_group"))
                typesOrdonnes.filter { it != "site" }
            else typesOrdonnes
            afficherListeSites(typesAfficher, enfants, counts, labels)
        }
    }

    private fun afficherListeSites(
        typesOrdonnes: List<String>,
        enfants: Map<String, List<MonitoringApi.MonitoringEnfant>>,
        counts: Map<String, Int>,
        labels: Map<String, String>?,
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val plusieursTypes = typesOrdonnes.size > 1
        typesOrdonnes.forEach { type ->
            val items = enfants[type].orEmpty()
            if (items.isEmpty()) return@forEach
            if (plusieursTypes) {
                val header = TextView(ctx).apply {
                    text = resolveLabel(type, counts, labels)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                    isAllCaps = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (12 * density).toInt()
                        bottomMargin = (6 * density).toInt()
                    }
                }
                binding.llSites.addView(header)
            }
            items.forEach { e ->
                val row = TextView(ctx).apply {
                    text = e.nom
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                binding.llSites.addView(row)
            }
        }
    }

    /** Résolution du libellé d'un `object_type`, par ordre de fiabilité :
     *   1. label per-protocole renvoyé par /api/monitorings/config/<code> (vrai libellé serveur)
     *   2. heuristique hiérarchique sites_group/site → "Sites"/"Points" si les deux coexistent
     *   3. mapping FR par défaut pour les types standards (site, transect, station, …) */
    private fun resolveLabel(type: String, counts: Map<String, Int>, labels: Map<String, String>?): String {
        labels?.get(type)?.takeIf { it.isNotEmpty() }?.let { return it }
        val nested = counts.containsKey("sites_group") && counts.containsKey("site")
        if (nested && type == "sites_group") return "Sites"
        if (nested && type == "site") return "Points"
        return labelType(type)
    }

    /** Mappe les `object_type` standards de gn_module_monitoring vers un label FR.
     *  Pour les types custom (déclarés dans le config/objects.json d'un protocole), on retombe
     *  sur la clé brute avec la première lettre en majuscule. */
    private fun labelType(type: String): String = when (type) {
        "site" -> "Sites"
        "sites_group" -> "Groupes de sites"
        "transect" -> "Transects"
        "station" -> "Stations"
        "point_ecoute" -> "Points d'écoute"
        "quadrat" -> "Quadrats"
        "visit", "visite" -> "Visites"
        "observation" -> "Observations"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
