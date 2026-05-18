package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
            val enfants: Map<String, List<MonitoringApi.MonitoringEnfant>>
            val schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?
            try {
                enfants = enfantsDeferred.await()
                schema = schemaDeferred.await()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.tvNbSites.visibility = View.GONE
                binding.tvErreurDetail.visibility = View.VISIBLE
                binding.tvErreurDetail.text = fr.ariegenature.geonat.network.humaniserErreurReseau(e)
                return@launch
            }
            if (!isAdded) return@launch
            if (enfants.isEmpty()) {
                binding.tvNbSites.visibility = View.GONE
                return@launch
            }

            // Re-derive le nom de chaque enfant via le nameField du schéma, puis applique le tri
            // déclaré par le protocole (schema.sorts) ou tri alphabétique par défaut.
            val enfantsAffines: Map<String, List<MonitoringApi.MonitoringEnfant>> =
                enfants.mapValues { (type, liste) ->
                    val nf = schema?.get(type)?.nameField
                    if (nf == null) liste
                    else liste.map { e ->
                        val nomSchema = e.proprietes[nf]
                        if (!nomSchema.isNullOrEmpty()) e.copy(nom = nomSchema) else e
                    }
                }.mapValues { (type, liste) ->
                    MonitoringApi.trierEnfants(liste, schema?.get(type)?.sorts.orEmpty())
                }

            val counts = enfantsAffines.mapValues { it.value.size }

            // Sur un protocole avec hiérarchie (sites_group → site type STOM), on n'affiche que
            // le niveau macro — ni dans le résumé ni dans la liste. Les points d'écoute restent
            // visibles dans la fiche d'un site individuel.
            val typesAfficher: List<String> = typesAfficherEnListe(enfantsAffines.keys.toList(), schema)
            binding.tvNbSites.text = typesAfficher.joinToString("  ·  ") { type ->
                "${labelPour(type, schema, counts)} : ${counts[type] ?: 0}"
            }
            afficherListeSites(moduleCode, typesAfficher, enfantsAffines, counts, schema)
        }
    }

    /** Sélectionne les object_types à afficher dans la liste principale et dans le résumé.
     *  Filtre robuste par `parent_type` : un type est macro s'il déclare le module comme parent.
     *  Ça reste cohérent même si l'API renvoie en `children` à la fois sites_group ET site (cas
     *  STOM observé) — seul sites_group est gardé car site a `parent_types: ["sites_group"]`.
     *  Sans schéma, fallback heuristique. */
    private fun typesAfficherEnListe(
        typesPresents: List<String>,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
    ): List<String> {
        if (schema != null) {
            val typesMacro = typesPresents.filter { type ->
                schema[type]?.parentType == "module"
            }
            if (typesMacro.isNotEmpty()) return typesMacro
        }
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

    /** Construit le sous-titre d'un row enfant depuis `schema.display_list` (sans le nameField,
     *  déjà en titre). Formate les dates ISO, ignore les valeurs vides ou null. */
    private fun sousTitrePourEnfant(
        e: MonitoringApi.MonitoringEnfant,
        schemaType: MonitoringApi.MonitoringSchemaObjet?,
    ): String {
        val displayList = schemaType?.displayList.orEmpty()
        if (displayList.isEmpty()) return ""
        val nameField = schemaType?.nameField
        return displayList
            .filter { it != nameField }
            .mapNotNull { k ->
                val v = e.proprietes[k]?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                formatValeurAffichee(v)
            }
            .joinToString(" · ")
    }

    /** Date ISO YYYY-MM-DD → JJ/MM/AAAA pour affichage. Autres valeurs : telles quelles. */
    private fun formatValeurAffichee(v: String): String {
        if (v.length >= 10 && v[4] == '-' && v[7] == '-') {
            return "${v.substring(8, 10)}/${v.substring(5, 7)}/${v.substring(0, 4)}"
        }
        return v
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
        val borderless = android.util.TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
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
            val schemaType = schema?.get(type)
            items.forEach { e ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val bloc = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameTv = TextView(ctx).apply {
                    text = e.nom
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
                bloc.addView(nameTv)
                // Sous-titre depuis schema.display_list (sans le nameField, déjà en titre).
                val sousTitre = sousTitrePourEnfant(e, schemaType)
                if (sousTitre.isNotEmpty()) {
                    val sub = TextView(ctx).apply {
                        text = sousTitre
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(android.graphics.Color.parseColor("#666666"))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    bloc.addView(sub)
                }
                val btnInfo = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_info)
                    setBackgroundResource(borderless)
                    contentDescription = "Détails"
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
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
                val btnCarte = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_eye)
                    setBackgroundResource(borderless)
                    contentDescription = "Voir sur carte"
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setOnClickListener {
                        if (e.id > 0) {
                            findNavController().navigate(
                                R.id.action_suivi_to_carte,
                                bundleOf(
                                    "moduleCode" to moduleCode,
                                    "objectType" to type,
                                    "id" to e.id,
                                    "titre" to e.nom,
                                )
                            )
                        }
                    }
                }
                row.addView(bloc)
                row.addView(btnInfo)
                row.addView(btnCarte)
                binding.llSites.addView(row)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}