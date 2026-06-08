/*
 * GeoMys-Android — application Android de saisie naturaliste pour GeoNature.
 * Copyright (C) 2026 ANA - CEN Ariège
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package fr.ariegenature.geomys.ui

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
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentSuiviDetailBinding
import fr.ariegenature.geomys.network.MonitoringApi
import fr.ariegenature.geomys.network.MonitoringModule
import fr.ariegenature.geomys.store.GeoNatureConfig
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

    /** Racine du fil d'Ariane (= label du protocole) propagée aux écrans enfants via
     *  l'argument de navigation `fil`. Renseignée par [afficherModule]. */
    private var filRacine: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSuiviDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        // Le contenu suit la hauteur réelle du fil (décalé plus bas si le chemin tient sur
        // plusieurs lignes).
        garderContenuSousFil(binding.tvFil, binding.scrollContenu)

        val moduleCode = arguments?.getString("moduleCode") ?: run {
            findNavController().navigateUp()
            return
        }


        binding.progressDetail.visibility = View.GONE
        val module = MonitoringApi.moduleParCode(moduleCode)
        if (module == null) {
            binding.tvLabel.text = moduleCode
            appliquerFilAriane(
                binding.tvFil, findNavController(), moduleCode,
                filRacineSuivis(moduleCode), dernierCliquable = false,
            )
            binding.tvErreurDetail.visibility = View.VISIBLE
            binding.tvErreurDetail.text = "Recharge la liste pour voir les détails."
            return
        }
        afficherModule(module)
        chargerEtAfficher(module.moduleCode)
    }

    private fun afficherModule(m: MonitoringModule) {
        filRacine = m.moduleLabel.ifEmpty { m.moduleCode }
        // Bandeau "Suivis › Protocole" — le protocole est l'écran courant donc non cliquable ;
        // "Suivis" ramène à la liste des protocoles.
        appliquerFilAriane(
            binding.tvFil, findNavController(), m.moduleCode,
            filRacineSuivis(filRacine), dernierCliquable = false,
        )
        binding.tvLabel.text = m.moduleLabel
        m.moduleDesc?.let {
            binding.tvDesc.text = it
            binding.tvDesc.visibility = View.VISIBLE
        }
        afficherDatasetsAssocies(m.moduleCode)
    }

    /** Affiche le ou les jeux de données dont `moduleCodes` contient le code du protocole.
     *  Lu depuis le cache local `gnConfig.datasetsCacheJson` (peuplé au sync) → fonctionne
     *  hors-réseau. Masqué si aucun dataset cache ne correspond. */
    private fun afficherDatasetsAssocies(moduleCode: String) {
        val cache = fr.ariegenature.geomys.store.GeoNatureConfig(requireContext()).datasetsCacheJson
        if (cache.isEmpty()) return
        val datasets: List<fr.ariegenature.geomys.network.GeoNatureDataset> = try {
            val t = object : com.google.gson.reflect.TypeToken<List<fr.ariegenature.geomys.network.GeoNatureDataset>>() {}.type
            com.google.gson.Gson().fromJson(cache, t) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val associes = datasets.filter { moduleCode in it.moduleCodes }
        if (associes.isEmpty()) return
        binding.tvDatasets.visibility = View.VISIBLE
        binding.tvDatasets.text = if (associes.size == 1)
            "Jeu de données : ${associes[0].nom} (${associes[0].id})"
        else
            "Jeux de données : " + associes.joinToString(", ") { "${it.nom} (${it.id})" }
    }

    private fun chargerEtAfficher(moduleCode: String) {
        binding.tvNbSites.visibility = View.VISIBLE
        binding.tvNbSites.text = "Sites : …"
        binding.llSites.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val config = GeoNatureConfig(requireContext())
            // runCatching à l'intérieur de chaque async : sinon une exception (ex. 403) propage
            // au scope parent en structured concurrency et plante le launch avant le try/catch
            // sur await(). Avec Result, on lit l'erreur tranquillement après l'await.
            val enfantsDeferred = async { runCatching { MonitoringApi.chargerEnfants(config, moduleCode) } }
            val schemaDeferred = async { runCatching { MonitoringApi.chargerSchemaProtocole(config, moduleCode) } }
            val enfantsRes = enfantsDeferred.await()
            val schemaRes = schemaDeferred.await()
            // La vue peut avoir été détruite pendant le chargement réseau (navigation vers
            // une fiche enfant → onDestroyView, mais le fragment reste `isAdded` sur la pile).
            // On teste donc _binding, pas isAdded, avant tout accès UI.
            if (_binding == null) return@launch
            val enfants = enfantsRes.getOrElse { e ->
                binding.tvNbSites.visibility = View.GONE
                binding.tvErreurDetail.visibility = View.VISIBLE
                binding.tvErreurDetail.text = fr.ariegenature.geomys.network.humaniserErreurReseau(e)
                return@launch
            }
            val schema = schemaRes.getOrNull()
            if (enfants.isEmpty()) {
                binding.tvNbSites.visibility = View.GONE
                return@launch
            }
            // Resolver des labels (id_role/id_nomenclature/id_dataset → nom lisible).
            val resolver = if (schema != null) {
                runCatching { MonitoringApi.chargerResolveurLabels(config, moduleCode, schema) }
                    .getOrNull() ?: MonitoringApi.LabelResolver()
            } else MonitoringApi.LabelResolver()
            // chargerResolveurLabels est un point de suspension : re-vérifier la vue après.
            if (_binding == null) return@launch

            // Re-derive le nom de chaque enfant via le nameField du schéma, puis tri
            // alphabétique sur ce nom. On ignore volontairement `schema.sorts` ici : les
            // protocoles l'utilisent surtout pour ordonner par id (peu lisible côté
            // utilisateur). Le tri alpha simplifie la navigation dans des listes longues.
            val enfantsAffines: Map<String, List<MonitoringApi.MonitoringEnfant>> =
                enfants.mapValues { (type, liste) ->
                    val nf = schema?.get(type)?.nameField
                    if (nf == null) liste
                    else liste.map { e ->
                        val nomSchema = e.proprietes[nf]
                        if (!nomSchema.isNullOrEmpty()) e.copy(nom = nomSchema) else e
                    }
                }.mapValues { (_, liste) ->
                    MonitoringApi.trierEnfants(liste, emptyList())
                }

            val counts = enfantsAffines.mapValues { it.value.size }

            // Sur un protocole avec hiérarchie (sites_group → site type STOM), on n'affiche que
            // le niveau macro — ni dans le résumé ni dans la liste. Les points d'écoute restent
            // visibles dans la fiche d'un site individuel.
            val typesAfficher: List<String> = typesAfficherEnListe(enfantsAffines.keys.toList(), schema)
            binding.tvNbSites.text = typesAfficher.joinToString("  ·  ") { type ->
                "${labelPour(type, schema, counts)} : ${counts[type] ?: 0}"
            }
            afficherListeSites(moduleCode, typesAfficher, enfantsAffines, counts, schema, resolver)
        }
    }

    /** Sélectionne les object_types à afficher dans la liste principale et dans le résumé.
     *  Filtre par `parent_type` : un type est macro s'il déclare le module comme parent.
     *  Si l'API renvoie à plat des types avec parent commun (cas observé : sites_group + site
     *  d'un protocole STOM-like), on garde uniquement les "racines" et on masque les enfants
     *  qui apparaîtront via drill-down sur leur parent. */
    private fun typesAfficherEnListe(
        typesPresents: List<String>,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
    ): List<String> {
        if (schema == null) return typesPresents
        // Préférence : tout type qui déclare le module comme parent.
        val typesMacro = typesPresents.filter { schema[it]?.parentType == "module" }
        if (typesMacro.isNotEmpty()) return typesMacro
        // Fallback purement structurel : retire les types dont le parent est lui-même présent
        // dans la liste — ils ressortiront via drill-down. Plus de hardcoding sites_group/site.
        val typesEnfants = typesPresents.filter { type ->
            val pt = schema[type]?.parentType
            pt != null && pt != "module" && pt in typesPresents
        }
        return if (typesEnfants.isEmpty()) typesPresents else typesPresents - typesEnfants.toSet()
    }


    /** Libellé d'un object_type : schéma serveur en priorité (label_list pour les listes),
     *  sinon fallback générique. Plus de mapping FR figé ni de logique sites_group/site. */
    private fun labelPour(
        type: String,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
        @Suppress("UNUSED_PARAMETER") counts: Map<String, Int>,
    ): String {
        schema?.get(type)?.let { s ->
            (s.labelList ?: s.label)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return labelTypeParDefaut(type)
    }

    /** Construit le sous-titre d'un row enfant depuis `schema.display_list` (sans le nameField,
     *  déjà en titre). Formate les dates ISO, résout les IDs en labels via le resolver,
     *  ignore les valeurs vides ou null. */
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
                // Résolution ID → label si la propriété a un type_util géré.
                val prop = schemaType?.properties?.get(k)
                if (prop != null) resolver.resoudre(prop, v)?.let { return@mapNotNull it }
                formatValeurAffichee(v)
            }
            .joinToString(" · ")
    }

    /** Date ISO YYYY-MM-DD → JJ/MM/AAAA pour affichage. Autres valeurs : telles quelles. */
    private fun formatValeurAffichee(v: String): String {
        return fr.ariegenature.geomys.util.DateAffichage.isoVersFr(v)
    }

    /** Fallback générique quand le schéma serveur ne fournit pas de `label_list`. Capitalise
     *  le code technique et remplace les `_` par des espaces — pas de mapping FR figé qui
     *  dépendrait des conventions d'un protocole. Pour un rendu propre, c'est à l'admin
     *  GeoNature de poser un `label_list` côté schéma. */
    private fun labelTypeParDefaut(type: String): String =
        type.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun afficherListeSites(
        moduleCode: String,
        typesOrdonnes: List<String>,
        enfants: Map<String, List<MonitoringApi.MonitoringEnfant>>,
        counts: Map<String, Int>,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
        resolver: MonitoringApi.LabelResolver,
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
                    setTextColor(couleurSecondaire(ctx))
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
            // Type d'enfant à créer via "+" sur chaque ligne : on cherche dans les
            // childrenTypes de ce type un type qui se comporte comme une saisie. Pour un
            // protocole `Site → Observation`, le "+" d'un site lance une observation ;
            // pour un protocole `Site → Visite`, il lance une visite.
            val typeSaisieEnfant = schemaType?.childrenTypes?.firstOrNull {
                fr.ariegenature.geomys.network.MonitoringSync.estTypeSaisie(it)
            }
                // Safety : si jamais le niveau macro liste lui-même une saisie (cas rare,
                // schéma plat où visites/passages remontent en racine), on n'ajoute pas de
                // "+" — adding sub-saisies à une saisie historique n'a pas de sens. Aligné
                // sur la même règle que FicheObjetFragment côté enfants serveur.
                ?.takeIf { !fr.ariegenature.geomys.network.MonitoringSync.estTypeSaisie(type) }
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
                val sousTitre = sousTitrePourEnfant(e, schemaType, resolver)
                if (sousTitre.isNotEmpty()) {
                    val sub = TextView(ctx).apply {
                        text = sousTitre
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(couleurSecondaire(ctx))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    bloc.addView(sub)
                }
                val btnInfo = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
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
                                    "fil" to encoderFil(filRacineSuivis(filRacine) + FilSegment(type, e.id, e.nom)),
                                )
                            )
                        }
                    }
                }
                // Bouton "voir sur carte" : affiché uniquement si l'object_type a une géométrie
                // déclarée dans le schéma (sinon le tap mène à "Pas de géométrie pour cet objet").
                val aGeometrie = schemaType?.geometryType != null
                val btnCarte = if (aGeometrie) ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_eye)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
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
                                    // Fil de la carte = "Suivis › Protocole › Site" — au tap
                                    // d'un point enfant, le segment du point sera ajouté pour
                                    // donner un fil "Suivis › Protocole › Site › Point".
                                    "fil" to encoderFil(filRacineSuivis(filRacine) + FilSegment(type, e.id, e.nom)),
                                )
                            )
                        }
                    }
                } else null
                row.addView(bloc)
                row.addView(btnInfo)
                if (btnCarte != null) row.addView(btnCarte)
                // Bouton "+" pour créer directement une saisie (visite ou obs selon le
                // protocole) sans avoir à drill-down dans la fiche du site. Affiché
                // uniquement si l'object_type a un childrenType de type saisie.
                if (typeSaisieEnfant != null) {
                    val btnPlus = ImageButton(ctx).apply {
                        setImageResource(R.drawable.ic_add)
                        // Teinte jaune clair pour ressortir sur le fond accueil sombre
                        // (Amber 200) — cohérent avec les liens du fil d'Ariane et l'icône
                        // maison ; signale visuellement l'action "saisie".
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
                        setBackgroundResource(borderless)
                        contentDescription = "Nouvelle saisie"
                        layoutParams = LinearLayout.LayoutParams(
                            (40 * density).toInt(), (40 * density).toInt(),
                        )
                        setOnClickListener {
                            if (e.id > 0) {
                                findNavController().navigate(
                                    R.id.action_suivi_to_nouvelle_visite,
                                    bundleOf(
                                        "moduleCode" to moduleCode,
                                        "parentObjectType" to type,
                                        "parentId" to e.id,
                                        "titreSite" to e.nom,
                                        "childObjectType" to typeSaisieEnfant,
                                        "fil" to encoderFil(filRacineSuivis(filRacine) + FilSegment(type, e.id, e.nom)),
                                    )
                                )
                            }
                        }
                    }
                    row.addView(btnPlus)
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