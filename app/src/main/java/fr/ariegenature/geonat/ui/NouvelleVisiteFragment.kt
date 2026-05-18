package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.databinding.FragmentNouvelleVisiteBinding
import fr.ariegenature.geonat.monitoring.form.EditableField
import fr.ariegenature.geonat.monitoring.form.FormulaireConstruction
import fr.ariegenature.geonat.monitoring.form.FormulaireRenderer
import fr.ariegenature.geonat.monitoring.form.PropertyValue
import fr.ariegenature.geonat.monitoring.form.ViewType
import fr.ariegenature.geonat.monitoring.form.construireFormulaire
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** POC du form renderer dynamique. Charge le schéma du protocole, identifie le type `visit`
 *  (ou `visite`), construit la liste des champs via [construireFormulaire] et les rend via
 *  [FormulaireRenderer]. Fallback sur des champs hardcodés si le serveur n'expose pas de
 *  schéma de visite exploitable. Submit affiche les valeurs collectées dans un dialog — pas
 *  encore de POST réel ni de persistance offline (Phase 4 et 5 de la roadmap). */
class NouvelleVisiteFragment : Fragment() {
    private var _binding: FragmentNouvelleVisiteBinding? = null
    private val binding get() = _binding!!
    private lateinit var renderer: FormulaireRenderer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNouvelleVisiteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)

        val titreSite = arguments?.getString("titreSite").orEmpty()
        val moduleCode = arguments?.getString("moduleCode").orEmpty()

        binding.tvTitre.text = "Nouvelle visite"
        binding.tvContexte.text = buildString {
            if (titreSite.isNotEmpty()) append("Sur : $titreSite")
            if (moduleCode.isNotEmpty()) {
                if (isNotEmpty()) append(" — ")
                append("Protocole : $moduleCode")
            }
        }

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        renderer = FormulaireRenderer(requireContext(), binding.llFormulaire)
        binding.btnSubmit.setOnClickListener { afficherValeurs() }

        if (moduleCode.isEmpty()) {
            renderer.rendre(creerChampsDemo())
            return
        }
        chargerSchemaEtRendre(moduleCode)
    }

    private fun chargerSchemaEtRendre(moduleCode: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = GeoNatureConfig(requireContext())
            val schema = MonitoringApi.chargerSchemaProtocole(config, moduleCode)
            if (!isAdded) return@launch
            if (schema == null) {
                val url = config.urlServeur.trim().trimEnd('/') + "/api/monitorings/config/" + moduleCode
                ajouterDebug("⚠ /api/monitorings/config/$moduleCode a renvoyé null.\n" +
                    "Teste dans ton navigateur :\n$url")
                renderer.rendre(creerChampsDemo())
                return@launch
            }
            // Diagnostic général sur le schéma reçu — toujours utile en POC.
            val recap = schema.entries.joinToString("\n") { (t, s) ->
                "• $t : ${s.properties.size} prop, parent=${s.parentType ?: "-"}, enfants=${s.childrenTypes.ifEmpty { listOf("-") }}"
            }
            ajouterDebug("Schéma /config/$moduleCode reçu (${schema.size} type(s)) :\n$recap")

            // Cherche tout type "saisissable" qui ressemble à une visite.
            val candidatesVisite = setOf("visit", "visite", "passage", "releve", "relevé", "session")
            val visitSchema = schema.values.firstOrNull { it.type in candidatesVisite && it.properties.isNotEmpty() }
                ?: schema.values.firstOrNull { it.type in candidatesVisite }
            if (visitSchema == null) {
                ajouterDebug("⚠ Pas de type visit/visite/passage/releve/session dans ce schéma.")
                renderer.rendre(creerChampsDemo())
                return@launch
            }
            if (visitSchema.properties.isEmpty()) {
                ajouterDebug("⚠ Type '${visitSchema.type}' trouvé mais sans propriétés exploitables " +
                    "(le serveur ne déclare peut-être pas `properties[].type_widget`).")
                renderer.rendre(creerChampsDemo())
                return@launch
            }
            val constructionBrute: FormulaireConstruction = construireFormulaire(visitSchema)
            // Cache le champ de sélection du parent (id_base_site / id_dalle / id_circuit…) :
            // l'utilisateur a déjà choisi son parent via le drill-down qui amène ici, l'interface
            // web le cache pareillement. Le nom du champ vient de schema[parentType].id_field_name.
            val parentObjectType = arguments?.getString("parentObjectType").orEmpty()
            val parentIdField = schema[parentObjectType]?.idFieldName
            val construction = if (parentIdField != null)
                constructionBrute.copy(fields = constructionBrute.fields.filter { it.code != parentIdField })
            else constructionBrute
            if (construction.fields.isEmpty()) {
                val widgets = visitSchema.properties.values.joinToString(", ") { "${it.nom}=${it.typeWidget}" }
                ajouterDebug("⚠ Aucun widget supporté.\nWidgets reçus : $widgets")
                renderer.rendre(creerChampsDemo())
                return@launch
            }
            // Pré-fetch des options pour les champs datalist en parallèle (observers, dataset,
            // nomenclatures…). Auth cachée 5min côté GeoNatureAuth → 1 login pour N fetches.
            val champsAvecOptions = enrichirAvecOptions(config, construction.fields, visitSchema)
            renderer.rendre(champsAvecOptions)
            if (construction.ignores.isNotEmpty()) {
                val recapIgnores = construction.ignores.joinToString(", ") { "${it.first} (${it.second})" }
                ajouterDebug("${construction.ignores.size} champ(s) non supporté(s) : $recapIgnores")
            }
        }
    }

    /** Pour chaque champ SELECT/SELECT_MULTIPLE issu d'un widget `datalist`/`nomenclature`,
     *  fetche les options via [MonitoringApi.chargerOptionsDatalist] et remplace les `values`
     *  vides de l'EditableField par le résultat. Les champs SELECT classiques (à `values` déjà
     *  dans le schéma) sont laissés tels quels. */
    private suspend fun enrichirAvecOptions(
        config: GeoNatureConfig,
        fields: List<EditableField>,
        visitSchema: MonitoringApi.MonitoringSchemaObjet,
    ): List<EditableField> = coroutineScope {
        val toFetch = fields.withIndex().filter { (_, f) ->
            (f.viewType == ViewType.SELECT || f.viewType == ViewType.SELECT_MULTIPLE) &&
                f.values.isEmpty() &&
                visitSchema.properties[f.code]?.apiUrl != null
        }
        if (toFetch.isEmpty()) return@coroutineScope fields
        val deferreds = toFetch.map { (idx, f) ->
            val prop = visitSchema.properties.getValue(f.code)
            async { idx to MonitoringApi.chargerOptionsDatalist(config, prop) }
        }
        val resultats = deferreds.awaitAll().toMap()
        val notesEchecs = mutableListOf<String>()
        val nouveaux = fields.toMutableList()
        // Stocke les options brutes par index pour la résolution des défauts (cd_nomenclature
        // / label_default → id_nomenclature). Le PropertyValue UI ne porte pas ces métadonnées.
        val optionsParIdx = mutableMapOf<Int, List<MonitoringApi.OptionDatalist>>()
        for ((idx, opts) in resultats) {
            val f = nouveaux[idx]
            if (opts == null) {
                notesEchecs.add("${f.code} (fetch options échoué)")
                continue
            }
            optionsParIdx[idx] = opts
            nouveaux[idx] = f.copy(values = opts.map { o -> PropertyValue(o.value, o.label) })
        }
        if (notesEchecs.isNotEmpty()) {
            ajouterDebug("⚠ Datalists non chargées : ${notesEchecs.joinToString(", ")}")
        }
        // Application des valeurs par défaut déclarées dans le schéma : pour les widgets
        // nomenclature, on résout `default.cd_nomenclature` ou `default.label_default` en
        // matchant dans les options chargées. Pour les widgets scalaires, on prend directement.
        nouveaux.forEachIndexed { idx, f ->
            val prop = visitSchema.properties[f.code] ?: return@forEachIndexed
            if (f.value != null) return@forEachIndexed // déjà rempli (ex: par pré-sélection user)
            // Cas 1 : default scalaire (text/number/date) ou widget non-datalist
            if (prop.defaultValue != null && prop.defaultObjet.isEmpty()) {
                nouveaux[idx] = f.copy(value = prop.defaultValue)
                return@forEachIndexed
            }
            // Cas 2 : default objet pour datalist/nomenclature → match dans les options
            if (prop.defaultObjet.isNotEmpty()) {
                val opts = optionsParIdx[idx] ?: return@forEachIndexed
                val cdRecherche = prop.defaultObjet["cd_nomenclature"]
                val lblRecherche = prop.defaultObjet["label_default"]
                val match = when {
                    cdRecherche != null -> opts.firstOrNull { it.cdNomenclature == cdRecherche }
                    lblRecherche != null -> opts.firstOrNull { it.labelDefaut == lblRecherche }
                    else -> null
                } ?: return@forEachIndexed
                nouveaux[idx] = f.copy(
                    value = if (f.viewType == ViewType.SELECT_MULTIPLE) listOf(match.value) else match.value,
                )
            }
        }
        // Pré-sélection de l'utilisateur connecté dans les champs observers (typeWidget ∈
        // {observers, datalist} avec type_util=user, OU nom de propriété "observers" / "observer").
        val idRole = config.idRoleUtilisateur.takeIf { it > 0 }
        if (idRole != null) {
            val idStr = idRole.toString()
            nouveaux.forEachIndexed { idx, f ->
                val prop = visitSchema.properties[f.code] ?: return@forEachIndexed
                val estObservateur = prop.typeWidget.lowercase() == "observers" ||
                    (prop.typeWidget.lowercase() == "datalist" && f.code.contains("observ"))
                if (!estObservateur) return@forEachIndexed
                // Ne pré-sélectionne que si l'id_role est dans les options chargées.
                if (f.values.none { it.value == idStr }) return@forEachIndexed
                nouveaux[idx] = f.copy(
                    value = if (f.viewType == ViewType.SELECT_MULTIPLE) listOf(idStr) else idStr,
                )
            }
        }
        nouveaux
    }

    private fun ajouterDebug(msg: String) {
        val current = binding.tvContexte.text.toString()
        binding.tvContexte.text = if (current.isEmpty()) msg else "$current\n\n$msg"
    }

    private fun afficherValeurs() {
        val valeurs = renderer.lireValeurs()
        val resume = valeurs.entries.joinToString("\n") { (k, v) -> "• $k : ${v ?: "—"}" }
        AlertDialog.Builder(requireContext())
            .setTitle("Valeurs du formulaire (POC)")
            .setMessage(resume.ifEmpty { "Aucun champ rempli." })
            .setPositiveButton("OK", null)
            .show()
    }

    /** Démo en dur, utilisée comme fallback quand le serveur ne renvoie pas de schéma de visite
     *  exploitable. Permet de toujours voir le renderer marcher pendant le POC. */
    private fun creerChampsDemo(): List<EditableField> = listOf(
        EditableField("visit_date_min", ViewType.DATE, "Date de visite", obligatoire = true),
        EditableField("nb_observateurs", ViewType.NUMBER, "Nombre d'observateurs"),
        EditableField(
            "conditions_meteo", ViewType.SELECT, "Conditions météo",
            values = listOf(
                PropertyValue("ensoleille", "Ensoleillé"),
                PropertyValue("nuageux", "Nuageux"),
                PropertyValue("pluie", "Pluie"),
                PropertyValue("vent", "Venteux"),
            ),
        ),
        EditableField("responsable", ViewType.TEXT, "Nom du responsable"),
        EditableField("comments", ViewType.TEXTAREA, "Commentaires"),
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
