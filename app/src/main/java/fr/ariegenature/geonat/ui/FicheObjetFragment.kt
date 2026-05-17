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
            val objetDeferred = async { MonitoringApi.chargerObjet(config, moduleCode, objectType, id) }
            val schemaDeferred = async { MonitoringApi.chargerSchemaProtocole(config, moduleCode) }
            val objet = objetDeferred.await()
            val schema = schemaDeferred.await()
            if (!isAdded) return@launch
            binding.progressFiche.visibility = View.GONE
            if (objet == null) {
                binding.tvErreur.visibility = View.VISIBLE
                binding.tvErreur.text = "Impossible de charger la fiche (HTTP ou parse erreur)."
                return@launch
            }
            afficher(objet, schema)
        }
    }

    private fun afficher(
        objet: MonitoringApi.MonitoringObjet,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
    ) {
        val schemaCe = schema?.get(objet.type)
        // Titre = valeur du nameField déclaré par le schéma, sinon heuristique.
        val nom = schemaCe?.nameField?.let { objet.proprietes[it] }?.takeIf { it.isNotEmpty() }
            ?: MonitoringApi.extraireNomHeuristique(objet.proprietes, objet.type, objet.id)
        binding.tvTitre.text = nom
        binding.tvType.text = schemaCe?.label
            ?: schemaCe?.labelList
            ?: labelTypeParDefaut(objet.type)

        afficherGeometrie(objet)
        afficherProprietes(objet, schemaCe)
        afficherEnfants(objet, schema)
    }

    private fun afficherGeometrie(objet: MonitoringApi.MonitoringObjet) {
        val geo = objet.geometrie ?: return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
        val label = TextView(ctx).apply {
            text = "Position"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(android.graphics.Color.parseColor("#888888"))
        }
        val value = TextView(ctx).apply {
            text = geo
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        row.addView(label)
        row.addView(value)
        binding.llProprietes.addView(row)
    }

    /** Liste les propriétés scalaires de l'objet sous forme "Label : valeur". Masque les
     *  champs techniques (id_*, additional_data_keys, …) et le nameField (déjà en titre). */
    private fun afficherProprietes(
        objet: MonitoringApi.MonitoringObjet,
        schemaCe: MonitoringApi.MonitoringSchemaObjet?,
    ) {
        val nameField = schemaCe?.nameField
        val proprietesAffichables = objet.proprietes.filterKeys { k ->
            k != nameField &&
                !k.startsWith("id_") &&
                k != "uuid_base_site" &&
                k != "additional_data_keys"
        }
        if (proprietesAffichables.isEmpty()) return

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        proprietesAffichables.forEach { (k, v) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val label = TextView(ctx).apply {
                text = labelChamp(k)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(android.graphics.Color.parseColor("#888888"))
            }
            val value = TextView(ctx).apply {
                text = formatValeur(k, v)
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
    ) {
        if (objet.enfants.isEmpty()) return
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        objet.enfants.forEach { (type, items) ->
            if (items.isEmpty()) return@forEach
            val typeLabel = schema?.get(type)?.let { it.labelList ?: it.label }
                ?: labelTypeParDefaut(type)
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

            // Re-extrait le nom des enfants via le schéma de leur type.
            val nf = schema?.get(type)?.nameField
            items.forEach { e ->
                val nom = nf?.let { e.proprietes[it] }?.takeIf { it.isNotEmpty() } ?: e.nom
                val row = TextView(ctx).apply {
                    text = nom
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setBackgroundResource(android.R.attr.selectableItemBackground.let {
                        val tv = android.util.TypedValue()
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                        tv.resourceId
                    })
                    isClickable = true
                    isFocusable = true
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