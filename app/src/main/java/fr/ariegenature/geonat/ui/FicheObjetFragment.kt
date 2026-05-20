package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentFicheObjetBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Fiche générique d'un objet monitoring (site, sites_group, visite, observation, …).
 *  Réutilisable à tous les niveaux de la hiérarchie : un clic sur un enfant ouvre la même
 *  fragment avec un nouveau `objectType`/`id`, ce qui permet le drill-down arbitraire sans
 *  un fragment par object_type. Affichage piloté par le schéma du protocole. */
class FicheObjetFragment : Fragment() {
    private var _binding: FragmentFicheObjetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFicheObjetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)

        val moduleCode = arguments?.getString("moduleCode") ?: return navUp()
        val objectType = arguments?.getString("objectType") ?: return navUp()
        val id = arguments?.getInt("id", -1)?.takeIf { it > 0 } ?: return navUp()

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        chargerEtAfficher(moduleCode, objectType, id)
    }

    private fun navUp() { findNavController().navigateUp() }

    private fun chargerEtAfficher(moduleCode: String, objectType: String, id: Int) {
        binding.progressFiche.visibility = View.VISIBLE
        binding.tvErreur.visibility = View.GONE
        binding.tvTitre.text = ""
        binding.tvType.text = ""
        binding.llProprietes.removeAllViews()
        binding.llEnfants.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val config = GeoNatureConfig(requireContext())
            // runCatching dans chaque async : sinon une exception (ex. 403) propage au scope
            // parent (structured concurrency) et plante le launch avant le catch sur await().
            val objetDeferred = async { runCatching { MonitoringApi.chargerObjet(config, moduleCode, objectType, id) } }
            val schemaDeferred = async { runCatching { MonitoringApi.chargerSchemaProtocole(config, moduleCode) } }
            val objetRes = objetDeferred.await()
            val schemaRes = schemaDeferred.await()
            if (!isAdded) return@launch
            val objet = objetRes.getOrElse { e ->
                binding.progressFiche.visibility = View.GONE
                binding.tvErreur.visibility = View.VISIBLE
                binding.tvErreur.text = fr.ariegenature.geonat.network.humaniserErreurReseau(e)
                return@launch
            }
            val schema = schemaRes.getOrNull()
            val resolver = if (schema != null) {
                runCatching { MonitoringApi.chargerResolveurLabels(config, moduleCode, schema) }
                    .getOrNull() ?: MonitoringApi.LabelResolver()
            } else MonitoringApi.LabelResolver()
            if (!isAdded) return@launch
            binding.progressFiche.visibility = View.GONE
            afficher(objet, schema, resolver)
        }
    }

    private fun afficher(
        objet: MonitoringApi.MonitoringObjet,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
        resolver: MonitoringApi.LabelResolver,
    ) {
        val schemaCe = schema?.get(objet.type)
        // Titre = valeur du nameField déclaré par le schéma, sinon heuristique.
        val nom = schemaCe?.nameField?.let { objet.proprietes[it] }?.takeIf { it.isNotEmpty() }
            ?: MonitoringApi.extraireNomHeuristique(objet.proprietes, objet.type, objet.id)
        binding.tvTitre.text = nom
        binding.tvType.text = schemaCe?.label
            ?: schemaCe?.labelList
            ?: labelTypeParDefaut(objet.type)

        // Position / Geometry retirée de la fiche : reste accessible via le bouton 👁 carte.
        afficherProprietes(objet, schemaCe, resolver)
        afficherEnfants(objet, schema, resolver)
    }

    /** Affiche les propriétés de l'objet sous forme "Label : valeur".
     *  Priorité au schéma serveur : `display_properties` dicte l'ORDRE et le SOUS-ENSEMBLE
     *  des champs à afficher (= ce que montre l'interface web), `attribut_label` dicte le
     *  libellé. Sans schéma exploitable, fallback sur l'ancien filtre par blacklist. */
    private fun afficherProprietes(
        objet: MonitoringApi.MonitoringObjet,
        schemaCe: MonitoringApi.MonitoringSchemaObjet?,
        resolver: MonitoringApi.LabelResolver,
    ) {
        val nameField = schemaCe?.nameField
        val displayList = schemaCe?.displayProperties.orEmpty()

        val cles: List<String> = if (displayList.isNotEmpty()) {
            displayList.filter { it != nameField && objet.proprietes.containsKey(it) }
        } else {
            // Fallback (vieux schéma sans display_properties) : tous les scalaires moins
            // une blacklist de champs techniques.
            objet.proprietes.keys.filter { k ->
                k != nameField &&
                    !k.startsWith("id_") &&
                    !k.startsWith("uuid_") &&
                    !k.startsWith("meta_") &&
                    k != "additional_data_keys" &&
                    k != "pk" &&
                    k != "altitude_min" && k != "altitude_max" &&
                    !k.endsWith("_code") &&
                    k != "geom" && k != "geometry" && !k.startsWith("geom_")
            }
        }
        if (cles.isEmpty()) return

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        cles.forEach { k ->
            val v = objet.proprietes[k] ?: return@forEach
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val label = TextView(ctx).apply {
                // Schéma serveur en priorité (attribut_label), sinon mapping FR par défaut.
                text = schemaCe?.properties?.get(k)?.label ?: labelChamp(k)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(android.graphics.Color.parseColor("#888888"))
            }
            val value = TextView(ctx).apply {
                // Résolution ID → label via le resolver si type_util est géré, sinon formatage.
                val prop = schemaCe?.properties?.get(k)
                val resolu = if (prop != null) resolver.resoudre(prop, v) else null
                text = resolu ?: formatValeur(k, v)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            row.addView(label)
            row.addView(value)
            binding.llProprietes.addView(row)
        }
    }

    /** Liste les enfants directs par type (visites, observations, etc.), chacun cliquable
     *  pour ouvrir sa propre fiche (drill-down récursif). */
    private fun afficherEnfants(
        objet: MonitoringApi.MonitoringObjet,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
        resolver: MonitoringApi.LabelResolver,
    ) {
        if (objet.enfants.isEmpty()) return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        objet.enfants.forEach { (type, itemsBruts) ->
            if (itemsBruts.isEmpty()) return@forEach
            val schemaType = schema?.get(type)
            // Re-derive nom via schema.nameField puis trie selon schema.sorts.
            val nf = schemaType?.nameField
            val itemsAffines = if (nf != null) itemsBruts.map { e ->
                val nomSchema = e.proprietes[nf]
                if (!nomSchema.isNullOrEmpty()) e.copy(nom = nomSchema) else e
            } else itemsBruts
            val items = MonitoringApi.trierEnfants(itemsAffines, schemaType?.sorts.orEmpty())
            val typeLabel = schema?.get(type)?.let { it.labelList ?: it.label }
                ?: labelTypeParDefaut(type)
            // Type d'enfant qui sera créé via le bouton "+" : on regarde si le type de
            // l'enfant courant déclare lui-même un childrenType de saisie (visite, releve,
            // observation…). Si oui, chaque ligne d'enfant porte un "+" qui crée cette
            // saisie. Sinon, pas de bouton "+". (cf. respect strict du schéma serveur.)
            val typeSaisieEnfant = schemaType?.childrenTypes?.firstOrNull {
                fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(it)
            }
            val header = TextView(ctx).apply {
                text = "$typeLabel (${items.size})"
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
            binding.llEnfants.addView(header)

            val borderless = android.util.TypedValue().also {
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            }.resourceId
            items.forEach { e ->
                val nom = e.nom
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
                    text = nom
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
                bloc.addView(nameTv)
                val sousTitre = sousTitrePourEnfant(e, schemaType, resolver)
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
                                R.id.action_fiche_to_fiche,
                                bundleOf(
                                    "moduleCode" to objet.moduleCode,
                                    "objectType" to type,
                                    "id" to e.id,
                                )
                            )
                        }
                    }
                }
                val aGeometrie = schemaType?.geometryType != null
                val btnCarte = if (aGeometrie) ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_eye)
                    setBackgroundResource(borderless)
                    contentDescription = "Voir sur carte"
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setOnClickListener {
                        if (e.id > 0) {
                            findNavController().navigate(
                                R.id.action_fiche_to_carte,
                                bundleOf(
                                    "moduleCode" to objet.moduleCode,
                                    "objectType" to type,
                                    "id" to e.id,
                                    "titre" to nom,
                                )
                            )
                        }
                    }
                } else null
                // Bouton "+" : nouvelle saisie attachée à cet enfant, visible uniquement
                // si le schéma de l'enfant déclare un type de saisie comme childrenType.
                val btnPlus = if (typeSaisieEnfant != null) ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_add)
                    setBackgroundResource(borderless)
                    contentDescription = "Nouvelle saisie"
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setOnClickListener {
                        if (e.id > 0) {
                            findNavController().navigate(
                                R.id.action_fiche_to_nouvelle_visite,
                                bundleOf(
                                    "moduleCode" to objet.moduleCode,
                                    "parentObjectType" to type,
                                    "parentId" to e.id,
                                    "titreSite" to nom,
                                    "childObjectType" to typeSaisieEnfant,
                                )
                            )
                        }
                    }
                } else null
                row.addView(bloc)
                row.addView(btnInfo)
                if (btnCarte != null) row.addView(btnCarte)
                if (btnPlus != null) row.addView(btnPlus)
                binding.llEnfants.addView(row)
            }
        }
    }

    /** Label FR pour un nom de champ de gn_module_monitoring (best-effort), sinon clé nettoyée. */
    private fun labelChamp(key: String): String = when (key) {
        "base_site_name", "sites_group_name" -> "Nom"
        "base_site_code", "sites_group_code" -> "Code"
        "base_site_description", "sites_group_description" -> "Description"
        "comment_site", "comment" -> "Commentaire"
        "desc_habitat" -> "Habitat"
        "prospect_surf" -> "Surface prospectée (m²)"
        "nb_visits" -> "Nombre de visites"
        "last_visit" -> "Dernière visite"
        "first_use_date" -> "Mise en service"
        "visit_date_min", "date_min" -> "Date de visite"
        "visit_date_max", "date_max" -> "Date de fin"
        "comments" -> "Commentaires"
        "altitude_min" -> "Altitude min (m)"
        "altitude_max" -> "Altitude max (m)"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Format léger : booleans en oui/non, dates ISO raccourcies, sinon raw. */
    private fun formatValeur(key: String, v: String): String {
        if (v == "true") return "oui"
        if (v == "false") return "non"
        // Date ISO "YYYY-MM-DD..." → "YYYY-MM-DD" simple
        if (v.length >= 10 && v[4] == '-' && v[7] == '-') return v.substring(0, 10)
        return v
    }

    /** Sous-titre d'un row enfant : valeurs des champs de `schema.display_list` séparées par
     *  " · ", dates ISO formatées, IDs résolus en labels via le resolver, sans le nameField. */
    private fun sousTitrePourEnfant(
        e: MonitoringApi.MonitoringEnfant,
        schemaType: MonitoringApi.MonitoringSchemaObjet?,
        resolver: MonitoringApi.LabelResolver,
    ): String {
        val displayList = schemaType?.displayList.orEmpty()
        if (displayList.isEmpty()) return ""
        val nameField = schemaType?.nameField
        return displayList
            .filter { it != nameField }
            .mapNotNull { k ->
                val v = e.proprietes[k]?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                val prop = schemaType?.properties?.get(k)
                if (prop != null) resolver.resoudre(prop, v)?.let { return@mapNotNull it }
                if (v.length >= 10 && v[4] == '-' && v[7] == '-')
                    "${v.substring(8, 10)}/${v.substring(5, 7)}/${v.substring(0, 4)}"
                else v
            }
            .joinToString(" · ")
    }

    private fun labelTypeParDefaut(type: String): String = when (type) {
        "site" -> "Site"
        "sites_group" -> "Site (groupe)"
        "transect" -> "Transect"
        "station" -> "Station"
        "point_ecoute" -> "Point d'écoute"
        "quadrat" -> "Quadrat"
        "visit", "visite" -> "Visite"
        "observation" -> "Observation"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}