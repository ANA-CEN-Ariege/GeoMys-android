package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentSuiviDetailBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.network.MonitoringModule
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Détail d'un protocole de suivi (gn_module_monitoring).
 *  Reçoit le `moduleCode` en argument, lit le module dans le cache de [MonitoringApi]
 *  (alimenté par l'écran liste), puis charge en parallèle :
 *   1. les enfants directs du module (`/object/<code>/module?depth=1`)
 *   2. le schéma déclaratif du protocole (`/config/<code>`)
 *  Le schéma drive l'affichage : il dit quels object_types sont "macro" (à lister) et quel
 *  champ utiliser comme nom. L'heuristique sert uniquement de fallback quand l'instance ne
 *  renvoie pas le schéma. */
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
            binding.tvLabel.text = moduleCode
            binding.tvCode.text = moduleCode
            binding.tvErreurDetail.visibility = View.VISIBLE
            binding.tvErreurDetail.text = "Recharge la liste pour voir les détails."
            return
        }
        afficherModule(module)
        chargerEtAfficher(module.moduleCode)
    }

    private fun afficherModule(m: MonitoringModule) {
        binding.tvLabel.text = m.moduleLabel
        binding.tvCode.text = m.moduleCode
        m.moduleDesc?.let {
            binding.tvDesc.text = it
            binding.tvDesc.visibility = View.VISIBLE
        }
    }

    private fun chargerEtAfficher(moduleCode: String) {
        binding.tvNbSites.visibility = View.VISIBLE
        binding.tvNbSites.text = "Sites : …"
        binding.llSites.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val config = GeoNatureConfig(requireContext())
            val enfantsDeferred = async { MonitoringApi.chargerEnfants(config, moduleCode) }
            val schemaDeferred = async { MonitoringApi.chargerSchemaProtocole(config, moduleCode) }
            val enfants = enfantsDeferred.await()
            val schema = schemaDeferred.await()
            if (!isAdded) return@launch
            if (enfants.isNullOrEmpty()) {
                binding.tvNbSites.visibility = View.GONE
                return@launch
            }

            // Re-derive le nom de chaque enfant à partir du schéma quand il déclare un nameField.
            val enfantsAffines: Map<String, List<MonitoringApi.MonitoringEnfant>> =
                enfants.mapValues { (type, liste) ->
                    val nf = schema?.get(type)?.nameField
                    if (nf == null) liste
                    else liste.map { e ->
                        val nomSchema = e.proprietes[nf]
                        if (!nomSchema.isNullOrEmpty()) e.copy(nom = nomSchema) else e
                    }
                }.mapValues { (_, liste) -> liste.sortedBy { it.nom.lowercase() } }

            val counts = enfantsAffines.mapValues { it.value.size }

            // Le résumé en haut compte tous les types présents dans la réponse, dans l'ordre
            // du schéma quand on l'a (parent avant enfant naturellement), sinon JSON.
            val typesPourResume = ordonnerTypes(enfantsAffines.keys.toList(), schema)
            binding.tvNbSites.text = typesPourResume.joinToString("  ·  ") { type ->
                "${labelPour(type, schema, counts)} : ${counts[type] ?: 0}"
            }

            // La LISTE n'affiche que les types "macro" déclarés enfants du module dans le schéma.
            // Sans schéma, fallback heuristique : si sites_group existe, on cache site.
            val typesAfficher: List<String> = typesAfficherEnListe(enfantsAffines.keys.toList(), schema)
            afficherListeSites(moduleCode, typesAfficher, enfantsAffines, counts, schema)
        }
    }

    /** Sélectionne les object_types à afficher dans la liste principale.
     *  - Si schéma dispo : on prend les enfants directs de `module` (= niveau macro)
     *  - Sinon fallback heuristique : si sites_group existe, on l'utilise seul (sans site
     *    leaf) ; sinon tous les types présents. */
    private fun typesAfficherEnListe(
        typesPresents: List<String>,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
    ): List<String> {
        val rootsSchema = schema?.get("module")?.childrenTypes.orEmpty()
        if (rootsSchema.isNotEmpty()) return rootsSchema.filter { it in typesPresents }
        if ("sites_group" in typesPresents) return typesPresents.filter { it != "site" }
        return typesPresents
    }

    /** Ordonne les types pour le résumé : si schéma dispo, parents avant enfants (DAG plat) ;
     *  sinon force sites_group avant site (heuristique). */
    private fun ordonnerTypes(
        types: List<String>,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
    ): List<String> {
        if (schema != null) {
            val racines = schema["module"]?.childrenTypes.orEmpty().filter { it in types }
            val reste = types.filter { it !in racines }
            return racines + reste
        }
        if ("sites_group" in types && "site" in types) {
            return listOf("sites_group", "site") + types.filter { it != "sites_group" && it != "site" }
        }
        return types
    }

    /** Libellé d'un object_type : schéma serveur en priorité, sinon heuristique, sinon mapping
     *  FR par défaut, sinon clé brute capitalisée. */
    private fun labelPour(
        type: String,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
        counts: Map<String, Int>,
    ): String {
        schema?.get(type)?.let { s ->
            (s.labelList ?: s.label)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Heuristique : sites_group = macro, site = leaf quand les deux coexistent
        if (counts.containsKey("sites_group") && counts.containsKey("site")) {
            if (type == "sites_group") return "Sites"
            if (type == "site") return "Points"
        }
        return labelTypeParDefaut(type)
    }

    private fun labelTypeParDefaut(type: String): String = when (type) {
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

    private fun afficherListeSites(
        moduleCode: String,
        typesOrdonnes: List<String>,
        enfants: Map<String, List<MonitoringApi.MonitoringEnfant>>,
        counts: Map<String, Int>,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val plusieursTypes = typesOrdonnes.size > 1
        val tvBg = android.util.TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        typesOrdonnes.forEach { type ->
            val items = enfants[type].orEmpty()
            if (items.isEmpty()) return@forEach
            if (plusieursTypes) {
                val header = TextView(ctx).apply {
                    text = labelPour(type, schema, counts)
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
                    setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setBackgroundResource(tvBg)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (e.id > 0) {
                            findNavController().navigate(
                                R.id.action_suivi_to_fiche,
                                bundleOf(
                                    "moduleCode" to moduleCode,
                                    "objectType" to type,
                                    "id" to e.id,
                                )
                            )
                        }
                    }
                }
                binding.llSites.addView(row)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}