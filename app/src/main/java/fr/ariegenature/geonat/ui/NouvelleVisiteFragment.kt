/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

    /** Métadonnées du formulaire chargé — renseignées par [chargerSchemaEtRendre] et
     *  utilisées par [envoyerVisite] au moment du submit pour construire l'URL et le
     *  lien parent. Null tant que le schéma serveur n'a pas été chargé (fallback démo). */
    private var visitObjectType: String? = null
    /** Type d'enfant « saisie » du type créé (ex. observation pour une visite), résolu depuis
     *  le schéma. Si non-null, l'enregistrement enchaîne directement sur la création de cet
     *  enfant au lieu de revenir à la liste. */
    private var typeSaisieEnfant: String? = null
    private var parentIdFieldChamp: String? = null
    private var nomsChampsVisit: List<String> = emptyList()
    /** Nom du champ uuid du type visité (ex. `uuid_base_visit`) — lu depuis le schéma serveur.
     *  Quand un champ MEDIA est rempli, on pré-génère un uuid côté client, on l'injecte dans
     *  le payload POST à ce champ, et on le réutilise comme `uuid_attached_row` pour l'upload
     *  gn_commons (cf. OutboxEnvoi). Null si le schéma n'expose pas d'uuid_field_name. */
    private var uuidFieldNameVisit: String? = null
    /** Champs montés sur le rendu courant — sert à retrouver les EditableField MEDIA et leur
     *  `schemaDotTable` au moment du submit (la valeur récupérée par renderer.lireValeurs ne
     *  contient que l'URI, pas les métadonnées). */
    private var champsCourants: List<EditableField> = emptyList()
    private var enCoursEnvoi = false
    /** Mode édition : uuid de la SaisieEnAttente à modifier. Quand non-null, le submit fait
     *  un `mettreAJour` au lieu d'un `ajouter` ; et avant le rendu on précharge les
     *  valeurs du JSON stocké dans chaque EditableField. */
    private var editUuid: String? = null
    /** Snapshot des valeurs à pré-remplir au prochain rendu (mode édition). Consommé une
     *  seule fois par [chargerSchemaEtRendre] juste avant l'appel à `renderer.rendre`. */
    private var valeursPreremplies: Map<String, Any?>? = null
    /** Chemins médias existants à ré-injecter dans le champ MEDIA en mode édition (le valeursJson
     *  outbox ne stocke pas les URIs fichier — elles vivent dans SaisieEnAttente.mediaPathsLocal).
     *  Consommé une fois après construction du formulaire. */
    private var mediaPathsPreremplir: List<String> = emptyList()
    /** Lambda en attente du retour du picker média. Set par le callback du renderer (cf.
     *  [FormulaireRenderer.setOnChoixMedia]), invoquée dans [pickPhotoLauncher] avec la LISTE des
     *  URIs locales importées (multi-sélection ; liste vide si annulation). */
    private var attendCallbackMedia: ((List<String>) -> Unit)? = null
    private val pickPhotoLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val cb = attendCallbackMedia
        attendCallbackMedia = null
        if (uris.isNullOrEmpty() || cb == null) { cb?.invoke(emptyList()); return@registerForActivityResult }
        val locales = uris.mapIndexedNotNull { i, u -> importerMedia(u, defaultMime = "image/jpeg", index = i) }
        cb(locales)
    }

    /** Copie le contenu de l'URI fourni par le picker dans `filesDir/medias/photo_<ts>.<ext>`
     *  pour le rendre indépendant du cycle de vie de l'Uri système. Retourne l'URI String du
     *  fichier copié ou null en cas d'échec. Pattern repris de DenombrementFragment.importerMedia. */
    private fun importerMedia(source: android.net.Uri, defaultMime: String, index: Int = 0): String? {
        val ctx = requireContext()
        val mime = ctx.contentResolver.getType(source) ?: defaultMime
        val ext = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/heic", "image/heif" -> "heic"
            "image/webp" -> "webp"
            else -> mime.substringAfter("/").ifEmpty { "jpg" }
        }
        val dir = java.io.File(ctx.filesDir, "medias").apply { mkdirs() }
        // `index` désambiguïse les fichiers importés dans la même milliseconde (multi-sélection).
        val dest = java.io.File(dir, "photo_${System.currentTimeMillis()}_$index.$ext")
        return try {
            ctx.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.toURI().toString()
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx,
                "Import média échoué : ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            null
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNouvelleVisiteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        // Le contenu suit la hauteur réelle du fil (décalé plus bas si chemin multi-lignes).
        garderContenuSousFil(binding.tvFil, binding.scrollContenu)

        // Mode édition : si on a un editUuid, on récupère la saisie de l'outbox pour
        // (a) restituer le contexte (moduleCode, parentObjectType, parentId, childObjectType)
        // qui n'est pas forcément passé par l'écran appelant, (b) précharger les valeurs.
        editUuid = arguments?.getString("editUuid")?.takeIf { it.isNotEmpty() }
        val saisieEnEdition = editUuid?.let { uuid ->
            fr.ariegenature.geonat.store.OutboxMonitoring.tout().firstOrNull { it.uuid == uuid }
        }
        if (saisieEnEdition != null) {
            // On honore les meta stockées dans la saisie (= ce qui a été utilisé pour la
            // créer). Évite que l'appelant ait à re-transmettre tout ce contexte.
            arguments?.putString("moduleCode", saisieEnEdition.moduleCode)
            arguments?.putString("childObjectType", saisieEnEdition.objectType)
            saisieEnEdition.parentObjectType?.let { arguments?.putString("parentObjectType", it) }
            saisieEnEdition.parentIdServeur?.let { arguments?.putInt("parentId", it) }
            saisieEnEdition.parentUuidLocal?.let { arguments?.putString("parentUuidLocal", it) }
            valeursPreremplies = chargerValeursPourEdition(saisieEnEdition)
            mediaPathsPreremplir = saisieEnEdition.mediasLocaux()
        }

        val titreSite = arguments?.getString("titreSite").orEmpty()
        val moduleCode = arguments?.getString("moduleCode").orEmpty()
        val modeEdition = editUuid != null

        // Titre par défaut avant chargement du schéma (le type exact arrive dans
        // chargerSchemaEtRendre). En édition, on garde "Modifier la saisie" en attendant
        // de pouvoir formuler "Édition de la visite / du passage / de l'observation".
        binding.tvTitre.text = if (modeEdition) "Modifier la saisie" else "Nouvelle visite"
        // Fil d'Ariane : reçu de l'écran appelant (drill-down) ou reconstruit depuis le cache
        // (édition depuis « Saisies en attente »). Tous les segments sont des ancêtres
        // cliquables — le formulaire lui-même n'est pas un niveau du fil. Vide si aucun
        // contexte n'a pu être déterminé.
        appliquerFilAriane(
            binding.tvFil, findNavController(), moduleCode,
            decoderFil(arguments?.getString("fil")), dernierCliquable = true,
        )
        binding.tvContexte.text = buildString {
            if (titreSite.isNotEmpty()) append("Sur : $titreSite")
            if (moduleCode.isNotEmpty()) {
                if (isNotEmpty()) append(" — ")
                append("Protocole : $moduleCode")
            }
        }

        binding.btnTerminer.setOnClickListener { findNavController().navigateUp() }
        // Mode "chaîne de saisies" : le parent est lui-même un type de saisie (visite) →
        // on enchaîne plusieurs obs sur la même visite. Le bouton "Terminer" est visible
        // dans ce cas pour permettre de sortir du flow. Désactivé en édition : on modifie
        // une saisie unique, sans enchaînement.
        val parentTypeArg = arguments?.getString("parentObjectType").orEmpty()
        val modeChaine = !modeEdition && parentTypeArg.isNotEmpty() &&
            fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(parentTypeArg)
        when {
            modeEdition -> binding.btnSubmit.text = "Enregistrer les modifications"
            modeChaine -> {
                binding.btnTerminer.visibility = View.VISIBLE
                binding.btnSubmit.text = "Enregistrer + suivante"
            }
        }

        renderer = FormulaireRenderer(requireContext(), binding.llFormulaire, viewLifecycleOwner.lifecycleScope)
        binding.btnSubmit.setOnClickListener { envoyerVisite() }
        // Le bouton de submit reste inactif tant que tous les champs obligatoires visibles
        // ne sont pas remplis. Le renderer notifie via setOnChangement à chaque édition
        // (saisie texte, sélection, picker, multi-select…) — on remet le bouton à jour.
        renderer.setOnChangement { majEtatBoutonSubmit() }
        // Le renderer ne peut pas lancer le picker système (l'API ActivityResult exige un
        // enregistrement côté Fragment) — on lui fournit un callback qui stocke la lambda
        // de retour et déclenche le launcher photo. Cardinalité MVP : une seule photo.
        renderer.setOnChoixMedia { _, callback ->
            attendCallbackMedia = callback
            pickPhotoLauncher.launch("image/*")
        }

        if (moduleCode.isEmpty()) {
            renderer.rendre(creerChampsDemo())
            return
        }
        chargerSchemaEtRendre(moduleCode)
    }

    /** Active/désactive le bouton de submit selon que des champs obligatoires sont vides
     *  OU qu'un champ numérique viole ses bornes min/max. Délègue le calcul au renderer
     *  (seul à connaître la visibilité courante imposée par les expressions `hidden`). */
    private fun majEtatBoutonSubmit() {
        if (enCoursEnvoi) return
        val manquants = renderer.champsObligatoiresManquants()
        val invalides = renderer.champsInvalides()
        binding.btnSubmit.isEnabled = manquants.isEmpty() && invalides.isEmpty()
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
            // Type cible à créer : priorité au `childObjectType` reçu de l'écran appelant
            // (le bouton + d'une ligne sait quel type d'enfant créer selon le schéma —
            // visit pour un point, observation pour une visite, etc.). Fallback sur la
            // recherche heuristique pour les anciennes routes qui ne passent pas l'arg.
            val childTypeArg = arguments?.getString("childObjectType")
            val candidatesVisite = setOf("visit", "visite", "passage", "releve", "relevé", "session",
                "observation", "obs", "occurrence", "releve", "denombrement")
            val visitSchema = if (!childTypeArg.isNullOrEmpty()) {
                schema[childTypeArg]
            } else {
                schema.values.firstOrNull { it.type in candidatesVisite && it.properties.isNotEmpty() }
                    ?: schema.values.firstOrNull { it.type in candidatesVisite }
            }
            if (visitSchema == null) {
                ajouterDebug("⚠ Type '${childTypeArg ?: "visit/visite/passage/…"}' absent du schéma.")
                renderer.rendre(creerChampsDemo())
                return@launch
            }
            if (visitSchema.properties.isEmpty()) {
                ajouterDebug("⚠ Type '${visitSchema.type}' trouvé mais sans propriétés exploitables " +
                    "(le serveur ne déclare peut-être pas `properties[].type_widget`).")
                renderer.rendre(creerChampsDemo())
                return@launch
            }
            // Titre adapté au type courant (visite / observation / …) ET au mode (création vs
            // édition d'une saisie outbox). Le `genre` du schéma permet d'accorder l'article.
            val labelType = visitSchema.label ?: visitSchema.type.replaceFirstChar { it.uppercase() }
            binding.tvTitre.text = if (editUuid != null) titreEdition(labelType, visitSchema.genre)
                else "Nouvelle $labelType"
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
            // Patch des champs TAXON : on cherche l'id_list_taxonomy à appliquer en
            // cascade — niveau object_type → un autre object_type (souvent porté par
            // "module") → dataset rattaché au module monitoring (id_taxa_list).
            val viaObjet = visitSchema.idListTaxonomy
            val viaAutreObjet = schema.values.firstNotNullOfOrNull { it.idListTaxonomy }
            val viaDatasetLocal = idListeViaDataset(schema, config, moduleCode)
            val viaDatasetLive = if (viaObjet == null && viaAutreObjet == null && viaDatasetLocal == null)
                idListeViaDatasetLive(config, moduleCode) else null
            val idListePartage = viaObjet ?: viaAutreObjet ?: viaDatasetLocal ?: viaDatasetLive
            val champsAvecTaxon = if (idListePartage != null) champsAvecOptions.map { f ->
                if (f.viewType == ViewType.TAXON && f.idListeTaxonomieRestreinte == null)
                    f.copy(idListeTaxonomieRestreinte = idListePartage)
                else f
            } else champsAvecOptions
            // Mode édition : on injecte les valeurs déjà saisies AVANT le rendu, en
            // typant chaque value selon le ViewType du champ (les pre-remplissages des
            // creerChamp* attendent des types spécifiques — Int pour TAXON, String pour
            // DATE/SELECT, List<String> pour SELECT_MULTIPLE, etc.).
            val champsFinaux0 = valeursPreremplies?.let { valeurs ->
                champsAvecTaxon.map { f ->
                    if (!valeurs.containsKey(f.code)) f
                    else f.copy(value = typerPourField(f, valeurs[f.code]))
                }
            } ?: champsAvecTaxon
            // Mode édition : pré-remplit le 1er champ MEDIA avec les paths stockés sur la saisie
            // (mediaPathsLocal ne fait pas partie du valeursJson).
            val champsFinaux = mediaPathsPreremplir.takeIf { it.isNotEmpty() }?.let { paths ->
                var faitPour: String? = null
                champsFinaux0.map { f ->
                    if (faitPour == null && f.viewType == ViewType.MEDIA) {
                        faitPour = f.code
                        f.copy(value = paths)
                    } else f
                }
            } ?: champsFinaux0
            mediaPathsPreremplir = emptyList()
            renderer.rendre(champsFinaux)
            // Règles `change` du schéma : auto-remplissage de champs dépendants (ex.
            // presence == 'Non' → count_min/count_max = 0). Appliquées après le rendu.
            renderer.setReglesChange(visitSchema.changeRules)
            // Une fois posées dans les champs, les valeurs préremplies n'ont plus à être
            // ré-injectées (évite par ex. d'écraser une modification utilisateur si on
            // re-rentre dans ce code via un re-rendu).
            valeursPreremplies = null
            // Mémorise les métadonnées nécessaires à l'envoi serveur (cf. envoyerVisite).
            visitObjectType = visitSchema.type
            // Premier type d'enfant « saisie » du type créé (ex. visit → observation) : sert
            // à enchaîner sur sa création après l'enregistrement (cf. envoyerVisite).
            typeSaisieEnfant = visitSchema.childrenTypes.firstOrNull {
                fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(it)
            }
            parentIdFieldChamp = parentIdField
            // Liste COMPLÈTE des propriétés du schéma (incluant les hidden:true techniques) —
            // utilisée pour padder le payload POST avec null sur les champs non remplis.
            nomsChampsVisit = visitSchema.properties.keys.toList()
            uuidFieldNameVisit = visitSchema.uuidFieldName
            champsCourants = champsFinaux
            if (construction.ignores.isNotEmpty()) {
                val recapIgnores = construction.ignores.joinToString(", ") { "${it.first} (${it.second})" }
                ajouterDebug("⚠ ${construction.ignores.size} champ(s) non supporté(s) : $recapIgnores")
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
        val nouveaux = fields.toMutableList()
        // Stocke les options brutes par index pour la résolution des défauts (cd_nomenclature
        // / label_default → id_nomenclature). Le PropertyValue UI ne porte pas ces métadonnées.
        val optionsParIdx = mutableMapOf<Int, List<MonitoringApi.OptionDatalist>>()
        // Fetch des options des champs `datalist` (observers, dataset, nomenclatures…). Les
        // SELECT/RADIO à `values` statiques n'ont pas d'apiUrl → non fetchés ici, mais ils
        // reçoivent quand même leur défaut plus bas. ⚠ Avant, un `return` anticipé quand
        // toFetch était vide sautait toute l'application des défauts (radio Oui/Non, etc.).
        val toFetch = fields.withIndex().filter { (_, f) ->
            (f.viewType == ViewType.SELECT || f.viewType == ViewType.SELECT_MULTIPLE) &&
                f.values.isEmpty() &&
                visitSchema.properties[f.code]?.apiUrl != null
        }
        if (toFetch.isNotEmpty()) {
            val deferreds = toFetch.map { (idx, f) ->
                val prop = visitSchema.properties.getValue(f.code)
                async { idx to MonitoringApi.chargerOptionsDatalist(config, prop) }
            }
            val resultats = deferreds.awaitAll().toMap()
            val notesEchecs = mutableListOf<String>()
            for ((idx, opts) in resultats) {
                val f = nouveaux[idx]
                if (opts == null) {
                    notesEchecs.add("${f.code} (fetch options échoué)")
                    continue
                }
                optionsParIdx[idx] = opts
                val valeurs = opts.map { o -> PropertyValue(o.value, o.label) }
                // Sélecteur à choix unique = inutile : pour les champs `dataset`/`observers`
                // n'exposant qu'une seule option, on auto-sélectionne cette valeur et on masque
                // le champ (la valeur reste dans le payload via lireValeurs). Limité à ces deux
                // widgets : une nomenclature à valeur unique reste affichée (l'utilisateur doit
                // pouvoir la voir/confirmer).
                val widget = visitSchema.properties[f.code]?.typeWidget?.lowercase()
                val masquable = widget == "dataset" || widget == "observers"
                if (masquable && opts.size == 1) {
                    val uniq = opts.first().value
                    nouveaux[idx] = f.copy(
                        values = valeurs,
                        value = if (f.viewType == ViewType.SELECT_MULTIPLE) listOf(uniq) else uniq,
                        masque = true,
                    )
                } else {
                    nouveaux[idx] = f.copy(values = valeurs)
                }
            }
            if (notesEchecs.isNotEmpty()) {
                ajouterDebug("⚠ Datalists non chargées : ${notesEchecs.joinToString(", ")}")
            }
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
        // Défaut « date du jour / heure actuelle » pour les champs date/heure restés vides
        // après application des défauts serveur ci-dessus. Le serveur garde donc la priorité ;
        // on ne fait que pré-remplir ce qu'il laisse vide. En édition, ces valeurs seront
        // écrasées par les valeurs saisies (cf. valeursPreremplies côté caller).
        nouveaux.forEachIndexed { idx, f ->
            if (f.value != null) return@forEachIndexed
            val defaut = when (f.viewType) {
                ViewType.DATE -> fr.ariegenature.geonat.util.DateHeureDefaut.dateDuJour()
                ViewType.TIME -> fr.ariegenature.geonat.util.DateHeureDefaut.heureActuelle()
                ViewType.DATETIME -> fr.ariegenature.geonat.util.DateHeureDefaut.dateHeureActuelle()
                else -> return@forEachIndexed
            }
            nouveaux[idx] = f.copy(value = defaut)
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

    /** Cherche un id_taxa_list à partir du dataset rattaché au module monitoring courant.
     *  Lit le cache local des datasets (peuplé au sync via /api/meta/datasets?fields=modules)
     *  pour trouver le premier dataset dont `moduleCodes` contient le code du protocole et
     *  qui porte un `id_taxa_list` non null. Utilisé comme fallback quand le schéma de
     *  l'object_type ne déclare pas son propre id_list_taxonomy. */
    private fun idListeViaDataset(
        @Suppress("UNUSED_PARAMETER") schema: Map<String, MonitoringApi.MonitoringSchemaObjet>,
        config: GeoNatureConfig,
        moduleCode: String,
    ): Int? {
        val json = config.datasetsCacheJson.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val t = object : com.google.gson.reflect.TypeToken<List<fr.ariegenature.geonat.network.GeoNatureDataset>>() {}.type
            val datasets: List<fr.ariegenature.geonat.network.GeoNatureDataset>? =
                com.google.gson.Gson().fromJson(json, t)
            datasets?.firstOrNull { moduleCode in it.moduleCodes && it.idTaxaList != null }?.idTaxaList
        } catch (_: Exception) { null }
    }

    /** Fetch live du dataset rattaché au protocole. Notre cache OCCTAX ne contient pas
     *  les datasets monitoring (filtre `module_code=OCCTAX` au sync) — on doit interroger
     *  directement le serveur avec `module_code=<protocole>` quand on cherche l'id_taxa_list
     *  à appliquer aux champs TAXON. Appel synchrone à éviter sur l'UI thread : utilisé
     *  uniquement comme dernier fallback. */
    private suspend fun idListeViaDatasetLive(
        config: GeoNatureConfig,
        moduleCode: String,
    ): Int? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val base = config.urlServeur.trim().trimEnd('/')
            val auth = fr.ariegenature.geonat.network.GeoNatureAuth.loginAvecCookies(
                base, config.login, config.motDePasse
            ) ?: return@withContext null
            val (token, _, cookies) = auth
            val variantes = listOf(moduleCode, moduleCode.lowercase()).distinct()
            for (variant in variantes) {
                val url = java.net.URL("$base/api/meta/datasets?module_code=$variant&active=true&fields=modules")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/json")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
                if (conn.responseCode != 200) continue
                val text = conn.inputStream.bufferedReader().readText()
                val arr = try { org.json.JSONArray(text) } catch (_: Exception) {
                    org.json.JSONObject(text).optJSONArray("data") ?: org.json.JSONArray()
                }
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    if (!d.has("id_taxa_list") || d.isNull("id_taxa_list")) continue
                    val id = d.optInt("id_taxa_list", -1)
                    if (id > 0) return@withContext id
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun ajouterDebug(msg: String) {
        val current = binding.tvContexte.text.toString()
        binding.tvContexte.text = if (current.isEmpty()) msg else "$current\n\n$msg"
    }

    /** Sauvegarde la saisie localement dans [OutboxMonitoring] — l'envoi serveur est
     *  exclusivement à la demande depuis l'écran "Saisies en attente". Si le schéma n'a
     *  pas pu être chargé (mode démo), fallback sur l'ancien dialog POC. */
    private fun envoyerVisite() {
        val moduleCode = arguments?.getString("moduleCode")
        val visitType = visitObjectType
        if (moduleCode.isNullOrEmpty() || visitType.isNullOrEmpty()) {
            afficherValeursDemo()
            return
        }
        val valeurs = renderer.lireValeurs().toMutableMap()
        // Extraction des médias : on prend le premier champ MEDIA non vide parmi les champs
        // montés, on capture ses URIs + schema_dot_table, puis on RETIRE la clé du payload
        // `valeurs` — le serveur Marshmallow n'accepte pas un media:"file://…" dans le POST de
        // l'objet, l'upload se fait après création via gn_commons. Multi-fichiers.
        @Suppress("UNCHECKED_CAST")
        fun pathsDe(f: EditableField): List<String> =
            (valeurs[f.code] as? List<String>)?.filter { it.isNotEmpty() }.orEmpty()
        val champMedia = champsCourants.firstOrNull {
            it.viewType == ViewType.MEDIA && pathsDe(it).isNotEmpty()
        }
        val mediaPaths = champMedia?.let { pathsDe(it) }.orEmpty()
        val mediaSchemaDotTable = champMedia?.schemaDotTable
        // Retire toutes les clés MEDIA (rempli OU vide) du payload — elles ne doivent jamais
        // partir telles quelles ; le champ medias[] sera padder à `[]` côté envoyerVisite.
        champsCourants.filter { it.viewType == ViewType.MEDIA }.forEach { valeurs.remove(it.code) }
        // Sérialise les valeurs via JSONObject pour éviter les pertes de type Gson lors
        // de la déshydratation à l'envoi (Int → Double, etc.).
        val valeursJson = org.json.JSONObject().apply {
            for ((code, v) in valeurs) put(code, valeurToJson(v))
        }.toString()
        val parentField = parentIdFieldChamp
        val parentIdInt = arguments?.getInt("parentId", -1)?.takeIf { it > 0 }
        val parentTypeArg = arguments?.getString("parentObjectType")?.takeIf { it.isNotEmpty() }
        // Champs texte-libre : leur valeur ne doit pas être coercée en Int à l'envoi même
        // si numérique (ex. commentaire "42") — cf. audit B6, côté envoyerVisite.
        val champsTexteLibre = champsCourants
            .filter { it.viewType == ViewType.TEXT || it.viewType == ViewType.TEXTAREA }
            .map { it.code }

        // Mode édition : on remplace les valeurs ET on remet la saisie en PENDING (efface
        // un éventuel ERROR précédent). Au retour, on amène l'utilisateur sur la fiche du
        // parent serveur (= l'écran qu'il voyait en saisissant à l'origine, avec la liste
        // des visites/obs du point) — pas sur la liste "Mes visites" d'où venait le tap.
        // Si pas de parent serveur (cas rare d'une saisie orpheline), fallback navigateUp.
        val uuidEdition = editUuid
        if (uuidEdition != null) {
            fr.ariegenature.geonat.store.OutboxMonitoring.mettreAJour(uuidEdition) { ancien ->
                // Sur une saisie legacy sans uuidPayload, on en génère un MAINTENANT si on a
                // un média à uploader (sinon inutile). Le serveur acceptera l'uuid_field_name
                // si le schéma l'expose.
                val nouvelUuid = ancien.uuidPayload
                    ?: if (mediaPaths.isNotEmpty() && uuidFieldNameVisit != null) java.util.UUID.randomUUID().toString() else null
                ancien.copy(
                    valeursJson = valeursJson,
                    etat = fr.ariegenature.geonat.store.SaisieEnAttente.Etat.PENDING,
                    messageErreur = null,
                    mediaPathLocal = null,
                    mediaPathsLocal = mediaPaths,
                    mediaSchemaDotTable = mediaSchemaDotTable,
                    uuidPayload = nouvelUuid,
                    uuidFieldName = uuidFieldNameVisit ?: ancien.uuidFieldName,
                    champsTexteLibre = champsTexteLibre,
                )
            }
            android.widget.Toast.makeText(
                requireContext(), "Modifications enregistrées",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            val parentIdRetour = arguments?.getInt("parentId", -1)?.takeIf { it > 0 }
            val parentTypeRetour = arguments?.getString("parentObjectType")?.takeIf { it.isNotEmpty() }
            val moduleRetour = arguments?.getString("moduleCode")?.takeIf { it.isNotEmpty() }
            if (parentIdRetour != null && parentTypeRetour != null && moduleRetour != null) {
                findNavController().navigate(
                    fr.ariegenature.geonat.R.id.action_nouvelle_visite_to_fiche,
                    androidx.core.os.bundleOf(
                        "moduleCode" to moduleRetour,
                        "objectType" to parentTypeRetour,
                        "id" to parentIdRetour,
                        // Le fil reçu = chemin complet jusqu'au parent (la saisie y est
                        // attachée) = exactement le fil attendu par la fiche du parent.
                        "fil" to arguments?.getString("fil").orEmpty(),
                    ),
                )
            } else {
                findNavController().navigateUp()
            }
            return
        }

        // uuid pré-généré uniquement quand on a un média ET un uuid_field_name déclaré côté
        // schéma : sans uuid_field_name on ne sait pas où l'injecter dans le payload, et sans
        // média on n'a rien à rattacher après création. Évite de spammer le serveur avec un
        // uuid client sur tous les objets — laisse Postgres en générer un comme aujourd'hui.
        val uuidNouveau = if (mediaPaths.isNotEmpty() && uuidFieldNameVisit != null)
            java.util.UUID.randomUUID().toString() else null
        val saisie = fr.ariegenature.geonat.store.SaisieEnAttente(
            moduleCode = moduleCode,
            objectType = visitType,
            parentObjectType = parentTypeArg,
            parentIdServeur = parentIdInt,
            parentUuidLocal = arguments?.getString("parentUuidLocal")?.takeIf { it.isNotEmpty() },
            parentIdField = parentField,
            nomsChampsSchema = nomsChampsVisit,
            champsTexteLibre = champsTexteLibre,
            valeursJson = valeursJson,
            uuidPayload = uuidNouveau,
            uuidFieldName = uuidFieldNameVisit,
            mediaPathsLocal = mediaPaths,
            mediaSchemaDotTable = mediaSchemaDotTable,
        )
        fr.ariegenature.geonat.store.OutboxMonitoring.ajouter(saisie)

        val labelType = visitType.replaceFirstChar { it.uppercase() }
        android.widget.Toast.makeText(
            requireContext(),
            "$labelType enregistré(e) localement — envoi à la demande depuis « Saisies en attente »",
            android.widget.Toast.LENGTH_LONG,
        ).show()

        // Enchaînement : si l'objet créé a un type d'enfant « saisie » (ex. visite →
        // observation), on bascule directement sur la création de cet enfant — parent = l'objet
        // local qu'on vient de créer (parentUuidLocal) — au lieu de revenir à la liste. Le
        // formulaire courant est remplacé (popUpTo) pour que « retour » revienne au fil.
        val enfant = typeSaisieEnfant
        if (enfant != null) {
            findNavController().navigate(
                fr.ariegenature.geonat.R.id.action_nouvelle_visite_enchainer,
                androidx.core.os.bundleOf(
                    "moduleCode" to moduleCode,
                    "parentObjectType" to visitType,
                    "parentUuidLocal" to saisie.uuid,
                    "childObjectType" to enfant,
                    "titreSite" to "$labelType (saisie locale)",
                    "fil" to arguments?.getString("fil").orEmpty(),
                ),
            )
            return
        }

        // Sinon : mode "chaîne de saisies" (ex. obs sur visite) → reset du formulaire pour
        // une nouvelle saisie sur le même parent (sortie via "Terminer") ; à défaut, retour
        // à la liste.
        val modeChaine = !parentTypeArg.isNullOrEmpty() &&
            fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(parentTypeArg)
        if (modeChaine) {
            reinitialiserFormulaire()
        } else {
            findNavController().navigateUp()
        }
    }

    /** Parse le `valeursJson` d'une SaisieEnAttente en Map<code, valeur> pour pré-remplir
     *  le formulaire en édition. Les JSONArray sont aplaties en List<String>, JSONObject.NULL
     *  devient null. Les types scalaires (Int, Boolean, String) sont conservés tels quels. */
    private fun chargerValeursPourEdition(
        saisie: fr.ariegenature.geonat.store.SaisieEnAttente,
    ): Map<String, Any?> {
        return try {
            val obj = org.json.JSONObject(saisie.valeursJson)
            val out = mutableMapOf<String, Any?>()
            obj.keys().forEach { k ->
                val v = obj.get(k)
                out[k] = when (v) {
                    org.json.JSONObject.NULL -> null
                    is org.json.JSONArray -> (0 until v.length()).map { v.get(it).toString() }
                    else -> v
                }
            }
            out
        } catch (e: Exception) {
            android.util.Log.w("NouvelleVisiteFragment",
                "chargerValeursPourEdition échoué pour uuid=${saisie.uuid}", e)
            emptyMap()
        }
    }

    /** Convertit une valeur brute (telle que parsée depuis le JSON outbox) en la forme
     *  attendue par les `creerChamp*` du renderer pour le pré-remplissage initial. */
    private fun typerPourField(f: EditableField, v: Any?): Any? {
        if (v == null) return null
        return when (f.viewType) {
            ViewType.TEXT, ViewType.TEXTAREA, ViewType.DATE, ViewType.TIME, ViewType.DATETIME,
            ViewType.SELECT, ViewType.RADIO ->
                v.toString()
            ViewType.NUMBER, ViewType.TAXON -> when (v) {
                is Int -> v
                is Long -> v.toInt()
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> v.toString().toIntOrNull()
            }
            ViewType.SELECT_MULTIPLE -> when (v) {
                is List<*> -> v.map { it.toString() }
                else -> listOf(v.toString())
            }
            ViewType.CHECKBOX -> v
            // MEDIA : on attend l'URI String du fichier local (cf. SaisieEnAttente.mediaPathLocal)
            // — le valeursJson stocké ne contient PAS ce champ. Le pré-remplissage en édition se
            // fait via un copy() de l'EditableField fait par le caller, pas via cette branche.
            ViewType.MEDIA -> v.toString()
        }
    }

    /** Convertit une valeur typée du form renderer en valeur acceptable par JSONObject.put.
     *  Préserve les types numériques/booléens, sérialise List en JSONArray. Les Strings
     *  qui ressemblent à des Int (ex. id_nomenclature) sont laissées en String — la
     *  conversion finale se fait au moment de l'envoi serveur, pas ici. */
    private fun valeurToJson(v: Any?): Any {
        return when (v) {
            null -> org.json.JSONObject.NULL
            is Boolean -> v
            is Number -> v
            is List<*> -> org.json.JSONArray().apply { v.forEach { put(valeurToJson(it)) } }
            else -> v.toString()
        }
    }

    /** Réinitialise le formulaire pour enchaîner une nouvelle saisie (cas observation N+1
     *  après observation N sur la même visite). On recharge entièrement le schéma : c'est
     *  un peu plus coûteux qu'un simple reset des inputs, mais ça garantit que les valeurs
     *  par défaut, les datalists et les pré-sélections (utilisateur connecté, etc.) sont
     *  reposées de zéro sans contamination de la saisie précédente. */
    private fun reinitialiserFormulaire() {
        val moduleCode = arguments?.getString("moduleCode")
        if (moduleCode.isNullOrEmpty()) {
            renderer.rendre(creerChampsDemo())
            return
        }
        // Vide le bandeau debug pour ne pas accumuler les logs de l'envoi précédent.
        binding.tvContexte.text = ""
        chargerSchemaEtRendre(moduleCode)
    }

    /** Formule un titre « Édition de la visite » / « du passage » / « de l'observation »
     *  en accord avec le genre déclaré par le schéma et l'élision sur voyelle initiale.
     *  Fallback simple si genre absent : on choisit « du / de la / de l' » d'après la
     *  voyelle initiale uniquement (correct dans 90 % des cas — « observation » → l',
     *  « visite » → la par défaut). Le label arrive ici en casse d'origine ; on le passe
     *  en minuscules pour éviter un mix « Édition de la Visite ». */
    private fun titreEdition(label: String, genre: String?): String {
        val mot = label.lowercase()
        val commenceParVoyelle = mot.firstOrNull()?.let { c ->
            c in setOf('a', 'à', 'â', 'e', 'é', 'è', 'ê', 'i', 'î', 'o', 'ô', 'u', 'ù', 'û', 'h', 'y')
        } == true
        val article = when {
            commenceParVoyelle -> "de l'"
            genre.equals("M", ignoreCase = true) -> "du "
            else -> "de la "
        }
        return "Édition $article$mot"
    }

    /** Fallback démo quand le schéma serveur n'est pas exploitable — affiche les valeurs
     *  collectées dans un dialog sans tenter d'envoi. */
    private fun afficherValeursDemo() {
        val valeurs = renderer.lireValeurs()
        val resume = valeurs.entries.joinToString("\n") { (k, v) -> "• $k : ${v ?: "—"}" }
        AlertDialog.Builder(requireContext())
            .setTitle("Mode démo — pas d'envoi serveur")
            .setMessage(resume.ifEmpty { "Aucun champ rempli." })
            .setPositiveButton("OK", null)
            .show()
    }

    /** Démo en dur, utilisée comme fallback quand le serveur ne renvoie pas de schéma de visite
     *  exploitable. Permet de toujours voir le renderer marcher pendant le POC. */
    private fun creerChampsDemo(): List<EditableField> = listOf(
        EditableField(
            "visit_date_min", ViewType.DATE, "Date de visite", obligatoire = true,
            value = fr.ariegenature.geonat.util.DateHeureDefaut.dateDuJour(),
        ),
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
