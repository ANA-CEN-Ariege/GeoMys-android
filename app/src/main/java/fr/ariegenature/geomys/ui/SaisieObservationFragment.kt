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

import android.Manifest
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.TaxRefLocal
import fr.ariegenature.geomys.databinding.FragmentSaisieObservationBinding
import fr.ariegenature.geomys.model.Observation
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.TaxRefStatut
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NidificationOiseaux
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer
import fr.ariegenature.geomys.ui.saisie.SpeechToTextHelper
import fr.ariegenature.geomys.ui.saisie.TaxRefLookupController
import fr.ariegenature.geomys.ui.saisie.TaxonSelector
import fr.ariegenature.geomys.ui.saisie.createSpeciesAutocompleteAdapter
import fr.ariegenature.geomys.ui.saisie.filtrerBoutonsGroupesNonVides
import fr.ariegenature.geomys.ui.saisie.taxonIcon
import kotlinx.coroutines.*

class SaisieObservationFragment : Fragment() {
    private var _binding: FragmentSaisieObservationBinding? = null
    private val binding get() = _binding!!

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // La popup système peut revenir après destruction de la vue → garde isAdded.
        if (!isAdded || !::speech.isInitialized) return@registerForActivityResult
        if (granted) speech.demarrerEcoute()
        else Toast.makeText(requireContext(), "Permission micro refusée", Toast.LENGTH_SHORT).show()
    }
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var gnConfig: GeoNatureConfig
    private lateinit var speech: SpeechToTextHelper
    private lateinit var taxonSelector: TaxonSelector
    private lateinit var taxrefLookup: TaxRefLookupController

    /** Une obs en attente d'enregistrement. Contient tous les champs ; `existingId` est non-null
     *  uniquement quand on édite une obs déjà enregistrée (flow depuis la liste Observations). */
    private data class PendingObs(
        var taxon: Taxon,
        var espece: String,
        var cdNom: Int?,
        // ── Counting #0 ──
        var nombre: Int = 1,
        var nombreMax: Int? = null,
        var sexe: String = "",
        var stadeVie: String = "",
        var objDenbr: String = "",
        var typDenbr: String = "",
        // ── Dénombrements supplémentaires ──
        var denombrementsAdditionnels: List<fr.ariegenature.geomys.model.Denombrement> = emptyList(),
        // ── Caractérisation de l'occurrence ──
        var statutObs: String = "",
        var techniqueObs: String = "",
        var statutBio: String = "",
        var etaBio: String = "",
        var preuveExist: String = "",
        var comportement: String = "",
        var methDetermin: String = "",
        var naturalite: String = "",
        var determinateur: String = "",
        var notes: String = "",
        var cdNomManuel: String = "",
        // ── Médias par counting ──
        var mediaUrisCounting0: List<String> = emptyList(),
        // ── Champs additionnels gn_commons ──
        var additionalFieldsReleve: Map<String, String> = emptyMap(),
        var additionalFieldsOccurrence: Map<String, String> = emptyMap(),
        var additionalFieldsCounting0: Map<String, String> = emptyMap(),
        val existingId: String? = null,
        /** Id VM stable de cette espèce — identité pour l'upsert « au fil de l'eau » dans le
         *  TraceViewModel (cf. [synchroniserBatch]). En édition d'une obs existante, c'est
         *  [existingId] qui fait foi. */
        val vmId: String = java.util.UUID.randomUUID().toString(),
    )

    private val pendingObs = mutableListOf<PendingObs>()
    /** Index de la PendingObs dont on édite les détails (Caractérisation / Dénombrement). */
    private var editingDetailsIndex: Int? = null

    /** Champs additionnels niveau OCCTAX_RELEVE — partagés par toutes les espèces du
     *  relevé en cours. Le serveur déclare ces champs au niveau du relevé (la session
     *  de saisie globale), pas par-espèce. Édités via le bouton "Détails du relevé"
     *  et propagés sur chaque PendingObs au moment de l'enregistrement. */
    private var additionalFieldsReleveSession: Map<String, String> = emptyMap()

    /** Override du relevé édités via « Détails du relevé » (jeu de données + observateur),
     *  communs à toutes les obs de la session. null = valeur par défaut (config / login). */
    private var idDatasetReleveSession: Int? = null
    private var idObservateurReleveSession: Int? = null
    private var nomObservateurReleveSession: String? = null
    /** Type de regroupement (TYP_GRP) du relevé, commun à toutes les obs. "" = non renseigné. */
    private var typGrpReleveSession: String = ""

    /** Géométrie du relevé courant : "Point" (défaut), "LineString", "Polygon". Null = Point.
     *  Partagée par toutes les obs (un relevé = une géométrie + N occurrences). */
    private var geometryTypeSession: String? = null
    /** Coordonnées JSON de la géométrie (vide pour Point, sinon liste de [lon,lat]). */
    private var geometryCoordsJsonSession: String? = null

    /** Quand on édite un relevé existant (arg `releveId`), on garde l'UUID pour le réutiliser
     *  lors de l'enregistrement (les nouvelles obs ajoutées en cours d'édition partagent ce
     *  releveId au lieu d'en avoir un nouveau). Null en saisie initiale. */
    private var releveIdEdite: String? = null

    /** IDs des obs présentes au début de l'édition d'un relevé — sert à détecter celles que
     *  l'utilisateur a retirées de la liste pendant l'édition pour les supprimer au save. */
    private val obsInitialesIds = mutableSetOf<String>()

    /** UUID du relevé en cours, stable pour toute la session de saisie : reprend l'UUID
     *  édité ([releveIdEdite]) ou en génère un nouveau. Sert d'ancrage pour l'auto-save « au
     *  fil de l'eau » ([synchroniserBatch]) — toutes les espèces du lot partagent ce releveId,
     *  et on réécrit le même relevé à chaque modification (pas de doublon). */
    private val releveIdSession: String by lazy { releveIdEdite ?: java.util.UUID.randomUUID().toString() }

    /** Vrai après le premier onViewCreated de cette instance de fragment. Quand la View est
     *  recréée au retour d'un sous-écran (Caractérisation / Dénombrement / Détails), évite
     *  de re-peupler `pendingObs` depuis le store — sinon chaque aller-retour duplique la
     *  liste des espèces. */
    private var donneesChargees = false

    private var latitude = 0.0
    private var longitude = 0.0
    private var rechercheNomSci = false
    /** Vrai entre le résultat final de la dictée vocale et l'arrivée du statut TaxRef
     *  correspondant : à ce moment-là, un match auto-ajoute une obs sans intervention. */
    private var attendreRetourVoix = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSaisieObservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)
        gnConfig = GeoNatureConfig(requireContext())

        val releveIdArg = arguments?.getString("releveId")
        val obsId = arguments?.getString("obsId")

        // On charge depuis le store UNIQUEMENT la première fois — au retour d'un sous-écran
        // la View est recréée mais `pendingObs` survit (champ de fragment, pas de view) :
        // ré-ajouter dupliquerait les espèces à chaque aller-retour.
        val taxonInitial: Taxon
        if (!donneesChargees) {
            donneesChargees = true
            val obsRelevExistants: List<Observation> = when {
                !releveIdArg.isNullOrEmpty() ->
                    traceViewModel.observations.value?.filter { it.releveId == releveIdArg } ?: emptyList()
                obsId != null ->
                    traceViewModel.observations.value?.find { it.id == obsId }?.let { listOf(it) } ?: emptyList()
                else -> emptyList()
            }
            if (obsRelevExistants.isNotEmpty()) {
                val premier = obsRelevExistants.first()
                latitude = premier.latitude
                longitude = premier.longitude
                taxonInitial = premier.taxon ?: Taxon.OISEAU
                releveIdEdite = premier.releveId
                obsInitialesIds.clear()
                obsInitialesIds.addAll(obsRelevExistants.map { it.id })
                // Toutes les obs d'un même relevé partagent les mêmes champs additionnels
                // niveau RELEVE et la même géométrie — on prend la première comme source.
                additionalFieldsReleveSession = premier.additionalFieldsReleve
                idDatasetReleveSession = premier.idDatasetReleve
                idObservateurReleveSession = premier.observateurReleveId
                nomObservateurReleveSession = premier.observateurReleveNom
                typGrpReleveSession = premier.typGrpReleve ?: ""
                geometryTypeSession = premier.geometryType
                geometryCoordsJsonSession = premier.geometryCoordsJson
                obsRelevExistants.forEach { obsExistante ->
                    pendingObs.add(PendingObs(
                        taxon = obsExistante.taxon ?: Taxon.OISEAU,
                        espece = obsExistante.espece,
                        cdNom = obsExistante.cdNom,
                        nombre = obsExistante.nombre,
                        nombreMax = obsExistante.nombreMax,
                        sexe = obsExistante.sexe ?: "",
                        stadeVie = obsExistante.stadeVie ?: "",
                        objDenbr = obsExistante.objDenbr ?: "",
                        typDenbr = obsExistante.typDenbr ?: "",
                        denombrementsAdditionnels = obsExistante.denombrementsAdditionnels,
                        statutObs = obsExistante.statutObs ?: "",
                        techniqueObs = obsExistante.techniqueObs ?: "",
                        statutBio = obsExistante.statutBio ?: "",
                        etaBio = obsExistante.etaBio ?: "",
                        preuveExist = obsExistante.preuveExist ?: "",
                        comportement = obsExistante.comportement ?: "",
                        methDetermin = obsExistante.methDetermin ?: "",
                        naturalite = obsExistante.naturalite ?: "",
                        determinateur = obsExistante.determinateur ?: "",
                        notes = obsExistante.notes,
                        cdNomManuel = obsExistante.cdNom?.toString() ?: "",
                        mediaUrisCounting0 = obsExistante.mediaUrisCounting0,
                        additionalFieldsReleve = obsExistante.additionalFieldsReleve,
                        additionalFieldsOccurrence = obsExistante.additionalFieldsOccurrence,
                        additionalFieldsCounting0 = obsExistante.additionalFieldsCounting0,
                        existingId = obsExistante.id
                    ))
                }
                requireActivity().title = if (obsRelevExistants.size > 1)
                    "Modifier le relevé (${obsRelevExistants.size} esp.)"
                else "Modifier le relevé"
            } else {
                latitude = arguments?.getDouble("latitude") ?: 0.0
                longitude = arguments?.getDouble("longitude") ?: 0.0
                // Restaure le dernier groupe choisi par l'utilisateur. Fallback Oiseaux si
                // jamais mémorisé. La validité par rapport aux boutons disponibles est
                // vérifiée plus bas (taxonDepart).
                taxonInitial = fr.ariegenature.geomys.ui.saisie.PreferencesSaisie
                    .dernierTaxon(requireContext()) ?: Taxon.OISEAU
                // Nouveau relevé venant de DetailsReleveFragment : on reçoit les valeurs
                // OCCTAX_RELEVE déjà validées (required vérifiés en amont).
                arguments?.getString("addReleveJson")?.takeIf { it.isNotEmpty() }?.let { json ->
                    additionalFieldsReleveSession = try {
                        val mapType = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                        com.google.gson.Gson().fromJson<Map<String, String>>(json, mapType) ?: emptyMap()
                    } catch (_: Exception) { emptyMap() }
                }
                // Géométrie reçue de TraceFragment (via DetailsReleveFragment ou direct).
                geometryTypeSession = arguments?.getString("geometryType")
                geometryCoordsJsonSession = arguments?.getString("geometryCoordsJson")
            }
        } else {
            // View recréée — `pendingObs` est déjà à jour. Taxon = celui de la 1re espèce en cours.
            taxonInitial = pendingObs.firstOrNull()?.taxon ?: Taxon.OISEAU
        }

        val boutonsTaxons = filtrerBoutonsGroupesNonVides(
            mapOf(
                Taxon.OISEAU      to binding.btnTaxonOiseau,
                Taxon.MAMMIFERE   to binding.btnTaxonMammifere,
                Taxon.REPTILE     to binding.btnTaxonReptile,
                Taxon.BATRACIEN   to binding.btnTaxonBatracien,
                Taxon.POISSON     to binding.btnTaxonPoisson,
                Taxon.INSECTE     to binding.btnTaxonInsecte,
                Taxon.FONGE       to binding.btnTaxonFonge,
                Taxon.MOLLUSQUE   to binding.btnTaxonMollusque,
                Taxon.INVERTEBRES to binding.btnTaxonInvertebres,
                Taxon.PLANTE      to binding.btnTaxonPlante,
            ),
            idListeFiltre = gnConfig.taxaListeId.trim().toIntOrNull(),
        )
        // Si le taxon initial n'a aucun cd_nom chargé (groupe masqué), on bascule sur
        // le premier groupe disponible pour éviter une sélection sur un bouton GONE.
        val taxonDepart = if (taxonInitial in boutonsTaxons.keys) taxonInitial
            else boutonsTaxons.keys.firstOrNull() ?: taxonInitial
        taxonSelector = TaxonSelector(
            requireContext(),
            boutonsTaxons,
            initial = taxonDepart,
        ) { onTaxonChanged() }
        taxonSelector.init()

        speech = SpeechToTextHelper(
            this, binding.tilEspece, binding.etEspece, micPermissionLauncher,
            onFinalText = { attendreRetourVoix = true }
        )
        taxrefLookup = TaxRefLookupController(
            scope = viewLifecycleOwner.lifecycleScope,
            progress = binding.taxrefProgress,
            tvStatut = binding.tvTaxrefStatut,
            taxonProvider = { taxonSelector.taxon },
            configProvider = { gnConfig },
            onChange = { s -> consommerRetourVoix(s) },
        )

        setupAutocomplete()
        rafraichirListe()

        // Bouton coche (haut-droite) : la saisie est sauvée au fil de l'eau, ce bouton sert
        // juste à terminer le relevé et sortir (synchro finale + navigateUp).
        binding.btnOk.setOnClickListener { enregistrer() }
        // Bouton "Détails" : TOUJOURS visible. Le dialog affiche les infos de base du
        // relevé (dataset, observateur, position) + les éventuels champs additionnels
        // OCCTAX_RELEVE déclarés par le serveur pour le dataset courant.
        binding.btnDetailsReleve.setOnClickListener {
            val defsReleveSession = AdditionalFieldsRenderer
                .fromJson(gnConfig.additionalFieldsOcctaxJsonActif)
                .filter { it.appliqueA(fr.ariegenature.geomys.network.AdditionalFieldsObject.RELEVE) }
                .filter { it.visiblePour(gnConfig.idDataset.toIntOrNull(), emptyList()) }
            ouvrirDetailsReleve(defsReleveSession)
        }
    }

    // ─── Liste des obs en attente ─────────────────────────────────────────────

    private fun rafraichirListe() {
        binding.llPendingObs.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        // Map cdNom → entrée TaxRef construite une seule fois (pas par ligne) pour résoudre
        // le nom scientifique à afficher sous le nom français.
        val parCdNom = TaxRefCache.entreesParCdNom()
        pendingObs.forEachIndexed { index, obs ->
            val row = inflater.inflate(R.layout.item_pending_obs, binding.llPendingObs, false)
            val tvEspece = row.findViewById<TextView>(R.id.tv_espece)
            tvEspece.text = obs.espece
            // Icône teintée dans la même couleur que le nom de l'espèce (suit le thème
            // clair/sombre) au lieu de sa couleur native presque noire.
            row.findViewById<ImageView>(R.id.iv_taxon).apply {
                setImageResource(taxonIcon(obs.taxon))
                setColorFilter(tvEspece.currentTextColor)
            }
            // Nom scientifique sous le nom français (en italique via le layout). Affiché
            // seulement si l'espèce affichée n'est pas déjà le nom sci (= un nom FR a été choisi).
            row.findViewById<TextView>(R.id.tv_sci_nom).apply {
                val sci = obs.cdNom?.let { parCdNom[it]?.sciNom }
                if (sci != null && !sci.equals(obs.espece, ignoreCase = true)) {
                    text = sci
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
            row.findViewById<TextView>(R.id.tv_nombre).apply {
                // Total = somme des count_min sur tous les dénombrements (counting #0 + additionnels).
                val total = obs.nombre + obs.denombrementsAdditionnels.sumOf { it.nombreMin }
                text = "× $total"
                setOnClickListener { ouvrirDenombrement(index) }
            }
            row.findViewById<ImageButton>(R.id.btn_info).setOnClickListener { ouvrirCaracterisation(index) }
            row.findViewById<ImageButton>(R.id.btn_delete).setOnClickListener { supprimer(index) }
            binding.llPendingObs.addView(row)
        }
        // Auto-save « au fil de l'eau » : tout changement de la liste (ajout, suppression,
        // retour d'un sous-écran de détail) est reflété dans le store via le TraceViewModel.
        synchroniserBatch()
    }

    private fun supprimer(index: Int) {
        if (index !in pendingObs.indices) return
        pendingObs.removeAt(index)
        rafraichirListe()
    }

    private fun groupe2InpnPour(obs: PendingObs): String? =
        obs.cdNom?.let { TaxRefCache.tousLesGroupes()[it.toString()] }
            ?: when (obs.taxon) {
                Taxon.MAMMIFERE   -> "Mammifères"
                Taxon.REPTILE     -> "Reptiles"
                Taxon.BATRACIEN   -> "Amphibiens"
                Taxon.POISSON     -> "Poissons"
                Taxon.INSECTE     -> "Insectes"
                Taxon.PLANTE,
                Taxon.FONGE,
                Taxon.MOLLUSQUE,
                Taxon.INVERTEBRES -> null
                else              -> "Oiseaux"
            }

    /** Ouvre un dialog avec les informations du relevé. Toujours présent :
     *   - en-tête : jeu de données, observateur, position GPS, type de géométrie
     *   - puis, si le serveur déclare des champs additionnels OCCTAX_RELEVE pour le
     *     dataset courant, ces champs éditables. */
    private fun ouvrirDetailsReleve(
        defs: List<fr.ariegenature.geomys.network.AdditionalFieldDef>,
    ) {
        val infos = buildList {
            add("Position" to "%.5f, %.5f".format(latitude, longitude))
            geometryTypeSession?.takeIf { it.isNotEmpty() && it != "Point" }?.let {
                add("Géométrie" to it)
            }
        }
        val datasets = datasetsPourDetailsReleve(gnConfig)
        val observateurs = observateursPourDetailsReleve(gnConfig)
        val idDsInitial = idDatasetReleveSession ?: gnConfig.idDataset.toIntOrNull()
        val idObsInitial = idObservateurReleveSession
            ?: gnConfig.observateurDefautId.toIntOrNull()
            ?: gnConfig.idRoleUtilisateur.takeIf { it > 0 }
        val nomDsInitial = gnConfig.nomDataset.takeIf { it.isNotEmpty() }
        val nomObsInitial = nomObservateurReleveSession
            ?: gnConfig.observateurDefautNom.ifEmpty { gnConfig.nomUtilisateur.ifEmpty { gnConfig.login } }
        ouvrirDialogDetailsReleve(
            requireContext(), infos, datasets, idDsInitial, nomDsInitial,
            observateurs, idObsInitial, nomObsInitial, defs, additionalFieldsReleveSession,
            gnConfig.settingsOcctaxJson, typGrpReleveSession,
        ) { res ->
            idDatasetReleveSession = res.idDataset
            idObservateurReleveSession = res.idObservateur
            nomObservateurReleveSession = res.nomObservateur
            additionalFieldsReleveSession = res.additionnels
            typGrpReleveSession = res.typGrp
        }
    }

    private fun ouvrirCaracterisation(index: Int) {
        if (index !in pendingObs.indices) return
        val obs = pendingObs[index]
        editingDetailsIndex = index
        val bundle = Bundle().apply {
            putString("taxon",               obs.taxon.name)
            putString("groupe2Inpn",         groupe2InpnPour(obs))
            putInt("cdNom",                  obs.cdNom ?: -1)
            putString("statutObs",           obs.statutObs)
            putString("techniqueObs",        obs.techniqueObs)
            putString("etaBio",              obs.etaBio)
            putString("comportement",        obs.comportement)
            putString("statutBio",           obs.statutBio)
            putString("methDetermin",        obs.methDetermin)
            putString("naturalite",          obs.naturalite)
            putString("determinateur",       obs.determinateur)
            // Même chaîne de fallback que la création d'obs (helper determinateurParDefaut).
            putString("determinateurDefaut", determinateurParDefaut())
            putString("preuveExist",         obs.preuveExist)
            putString("notes",               obs.notes)
            // Niveau OCCURRENCE uniquement — les champs niveau RELEVE sont édités au
            // niveau de la session via le bouton "Détails du relevé" du présent écran.
            putString("addOccJson", com.google.gson.Gson().toJson(obs.additionalFieldsOccurrence))
        }
        findNavController().navigate(R.id.action_saisie_to_caracterisation, bundle)
    }

    private fun ouvrirDenombrement(index: Int) {
        if (index !in pendingObs.indices) return
        val obs = pendingObs[index]
        editingDetailsIndex = index
        // Sérialise la liste complète des dénombrements (counting #0 issu des champs flat + additionnels).
        val counting0 = fr.ariegenature.geomys.model.Denombrement(
            nombreMin = obs.nombre,
            nombreMax = obs.nombreMax ?: obs.nombre,
            sexe = obs.sexe.ifEmpty { null },
            stadeVie = obs.stadeVie.ifEmpty { null },
            objDenbr = obs.objDenbr.ifEmpty { null },
            typDenbr = obs.typDenbr.ifEmpty { null },
            mediaUris = obs.mediaUrisCounting0,
            additionalFields = obs.additionalFieldsCounting0,
        )
        val tous = listOf(counting0) + obs.denombrementsAdditionnels
        val json = com.google.gson.Gson().toJson(tous)
        val bundle = Bundle().apply {
            putString("taxon",             obs.taxon.name)
            putString("groupe2Inpn",       groupe2InpnPour(obs))
            putInt("cdNom",                obs.cdNom ?: -1)
            putString("espece",            obs.espece)
            putString("denombrementsJson", json)
        }
        findNavController().navigate(R.id.action_saisie_to_denombrement, bundle)
    }

    override fun onResume() {
        super.onResume()
        // Consomme les valeurs renvoyées par les sous-écrans (Caractérisation / Dénombrement)
        // via le SavedStateHandle. Approche eager (pas LiveData) pour éviter les surprises de
        // timing autour de viewLifecycleOwner et des re-attachements multiples.
        consommerResultatsSousEcrans()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Restaure les overrides « Détails du relevé » après rotation / process-death.
        savedInstanceState?.let { st ->
            if (st.containsKey("rs_ds")) idDatasetReleveSession = st.getInt("rs_ds")
            if (st.containsKey("rs_obs")) idObservateurReleveSession = st.getInt("rs_obs")
            st.getString("rs_obsnom")?.let { nomObservateurReleveSession = it }
            st.getString("rs_add")?.let { json ->
                additionalFieldsReleveSession = try {
                    com.google.gson.Gson().fromJson(
                        json, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type,
                    ) ?: additionalFieldsReleveSession
                } catch (_: Exception) { additionalFieldsReleveSession }
            }
        }
        // Filet de sécurité : on consomme aussi ici au cas où onResume serait trop tardif
        // pour certains cas de figure du cycle de vie Navigation. consommerString supprime
        // la clé après l'avoir appliquée, donc pas de double-application.
        consommerResultatsSousEcrans()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        idDatasetReleveSession?.let { outState.putInt("rs_ds", it) }
        idObservateurReleveSession?.let { outState.putInt("rs_obs", it) }
        nomObservateurReleveSession?.let { outState.putString("rs_obsnom", it) }
        if (additionalFieldsReleveSession.isNotEmpty())
            outState.putString("rs_add", com.google.gson.Gson().toJson(additionalFieldsReleveSession))
    }

    private fun consommerResultatsSousEcrans() {
        val sv = findNavController().currentBackStackEntry?.savedStateHandle ?: return
        val idx = editingDetailsIndex ?: return
        if (idx !in pendingObs.indices) return
        val obs = pendingObs[idx]
        var modifie = false

        fun consommerString(key: String, setter: (String) -> Unit) {
            if (!sv.contains(key)) return
            val value: String = sv.remove<String>(key) ?: ""
            setter(value)
            modifie = true
        }

        // Caractérisation (CaracterisationFragment)
        consommerString("statutObs")     { obs.statutObs = it }
        consommerString("techniqueObs")  { obs.techniqueObs = it }
        consommerString("etaBio")        { obs.etaBio = it }
        consommerString("comportement")  { obs.comportement = it }
        consommerString("statutBio")     { obs.statutBio = it }
        consommerString("methDetermin")  { obs.methDetermin = it }
        consommerString("determinateur") { obs.determinateur = it }
        consommerString("preuveExist")   { obs.preuveExist = it }
        consommerString("naturalite")    { obs.naturalite = it }
        consommerString("notes")         { obs.notes = it }

        val mapType = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
        consommerString("addOccJson") { v ->
            obs.additionalFieldsOccurrence = try {
                com.google.gson.Gson().fromJson<Map<String, String>>(v, mapType) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        }

        // Legacy ObservationDetailsFragment (saisie rapide) — non utilisé en multi-taxons
        // mais on consomme par sécurité.
        consommerString("sexe")        { obs.sexe = it }
        consommerString("stadeVie")    { obs.stadeVie = it }
        consommerString("objDenbr")    { obs.objDenbr = it }
        consommerString("typDenbr")    { obs.typDenbr = it }
        consommerString("cdNomManuel") { v ->
            obs.cdNomManuel = v
            v.trim().toIntOrNull()?.takeIf { it > 0 }?.let { obs.cdNom = it }
        }

        // Dénombrements (DenombrementFragment) — JSON sérialisé d'une List<Denombrement>.
        consommerString("denombrementsJson") { json ->
            val type = object : com.google.gson.reflect.TypeToken<List<fr.ariegenature.geomys.model.Denombrement>>() {}.type
            val liste: List<fr.ariegenature.geomys.model.Denombrement> = try {
                com.google.gson.Gson().fromJson(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
            if (liste.isEmpty()) return@consommerString
            val c0 = liste[0]
            obs.nombre = c0.nombreMin
            obs.nombreMax = if (c0.nombreMax != c0.nombreMin) c0.nombreMax else null
            obs.sexe = c0.sexe ?: ""
            obs.stadeVie = c0.stadeVie ?: ""
            obs.objDenbr = c0.objDenbr ?: ""
            obs.typDenbr = c0.typDenbr ?: ""
            @Suppress("USELESS_ELVIS")
            run {
                obs.mediaUrisCounting0 = (c0.mediaUris as List<String>?) ?: emptyList()
                obs.additionalFieldsCounting0 = (c0.additionalFields as Map<String, String>?) ?: emptyMap()
            }
            obs.denombrementsAdditionnels = if (liste.size > 1) liste.drop(1) else emptyList()
        }

        if (modifie) rafraichirListe()
    }

    // ─── Espèce / autocomplete ────────────────────────────────────────────────

    private fun onTaxonChanged() {
        // Mémorise le nouveau groupe pour qu'il soit ré-appliqué à la prochaine ouverture
        // d'un écran de saisie (mono ou multi-taxons).
        fr.ariegenature.geomys.ui.saisie.PreferencesSaisie
            .memoiserTaxon(requireContext(), taxonSelector.taxon)
        binding.etEspece.setText("")
        taxrefLookup.reset()
        refreshAutocompleteAdapter()
        updateEspeceHint()
    }

    private fun refreshAutocompleteAdapter() {
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.Default) {
                TaxRefLocal.getSuggestionsAutocomplete(
                    taxonSelector.taxon,
                    rechercheNomSci,
                    idListeFiltre = gnConfig.taxaListeId.trim().toIntOrNull(),
                )
            }
            if (!isAdded || _binding == null) return@launch
            val adapter = createSpeciesAutocompleteAdapter(requireContext(), suggestions)
            binding.etEspece.setAdapter(adapter)
            // Race possible : si l'utilisateur a tapé avant la fin du scan asynchrone,
            // AutoCompleteTextView a déclenché le filtre sur un adapter encore vide et
            // ne le rejoue pas tout seul après setAdapter — on relance manuellement.
            val current = binding.etEspece.text?.toString().orEmpty()
            if (current.length >= binding.etEspece.threshold && binding.etEspece.hasFocus()) {
                adapter.filter.filter(current) { count ->
                    if (count > 0 && _binding != null && binding.etEspece.hasFocus()) {
                        binding.etEspece.showDropDown()
                    }
                }
            }
        }
    }

    private fun updateEspeceHint() {
        val nomGroupe = when (taxonSelector.taxon) {
            Taxon.OISEAU      -> "Oiseaux"
            Taxon.MAMMIFERE   -> "Mammifères"
            Taxon.REPTILE     -> "Reptiles"
            Taxon.BATRACIEN   -> "Amphibiens"
            Taxon.POISSON     -> "Poissons"
            Taxon.INSECTE     -> "Insectes"
            Taxon.FONGE       -> "Champignons"
            Taxon.MOLLUSQUE   -> "Mollusques"
            Taxon.INVERTEBRES -> "Autres invertébrés"
            Taxon.PLANTE      -> "Plantes"
        }
        binding.tilEspece.hint = if (rechercheNomSci) "Nom scientifique ($nomGroupe)"
                                 else                "Espèce observée ($nomGroupe)"
    }

    private fun setupAutocomplete() {
        binding.etEspece.threshold = 1
        refreshAutocompleteAdapter()
        updateEspeceHint()
        binding.tilEspece.setEndIconOnClickListener { speech.lancer() }

        // Restaure l'état du switch depuis la dernière session (partagé avec la saisie rapide).
        rechercheNomSci = fr.ariegenature.geomys.ui.saisie.PreferencesSaisie
            .rechercheNomSci(requireContext())
        binding.switchNomSci.isChecked = rechercheNomSci
        binding.switchNomSci.setOnCheckedChangeListener { _, isChecked ->
            rechercheNomSci = isChecked
            fr.ariegenature.geomys.ui.saisie.PreferencesSaisie
                .memoiserNomSci(requireContext(), isChecked)
            binding.etEspece.setText("")
            taxrefLookup.reset()
            refreshAutocompleteAdapter()
            updateEspeceHint()
        }

        binding.etEspece.setOnItemClickListener { _, _, position, _ ->
            val nomSelectionne = binding.etEspece.adapter.getItem(position) as? String ?: return@setOnItemClickListener
            ajouterDepuisSuggestion(nomSelectionne)
        }

        binding.etEspece.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                taxrefLookup.rechercher(s?.toString() ?: "")
            }
        })
    }

    /** Consomme le flag dicté : sur un match TaxRef, ajoute automatiquement l'obs.
     *  Le flag est consommé dans tous les cas pour éviter qu'une frappe clavier
     *  ultérieure ne déclenche un ajout fantôme. */
    private fun consommerRetourVoix(statut: TaxRefStatut?) {
        if (!attendreRetourVoix || statut == null) return
        attendreRetourVoix = false
        if (statut is TaxRefStatut.Trouve) ajouterDepuisVoix(statut)
    }

    /** Ajoute une PendingObs à partir d'un match TaxRef issu de la dictée vocale.
     *  Privilégie le premier nom français du statut (TaxRefStatut.nomFrancais peut être
     *  une liste séparée par virgules) ; à défaut, retombe sur le nom scientifique. */
    private fun ajouterDepuisVoix(statut: TaxRefStatut.Trouve) {
        val premierNomFr = statut.nomFrancais
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val espece = premierNomFr ?: statut.nomScientifique
        pendingObs.add(PendingObs(
            taxon = taxonSelector.taxon,
            espece = espece,
            cdNom = statut.cdNom,
            nombre = 1,
            determinateur = determinateurParDefaut(),
        ))
        binding.etEspece.setText("")
        taxrefLookup.reset()
        rafraichirListe()
        declencherCaracterisationSiNidification(pendingObs.lastIndex)
    }

    /** Ajoute une nouvelle PendingObs à partir d'une suggestion d'autocomplétion. */
    private fun ajouterDepuisSuggestion(nom: String) {
        val entry = TaxRefCache.get(nom)
        val (cdNom, especeAffichee) = if (entry != null) {
            val nomAffiche = entry.nomFrOriginal ?: nom
            Pair(entry.cdNom, nomAffiche)
        } else {
            // Log diagnostique : si l'autocomplete propose un nom mais TaxRefCache.get
            // retourne null, c'est un cas à investiguer (TaxRef partiel, données embarquées,
            // caractères invisibles, etc.). Le code-points de chaque char aide à repérer un
            // espace insécable ou autre caractère parasite. À filtrer via `adb logcat | grep
            // TaxRef-miss`.
            val codepoints = nom.map { "%04X".format(it.code) }.joinToString(" ")
            android.util.Log.w("TaxRef-miss",
                "Suggestion sans cd_nom : '$nom' (codepoints: $codepoints, normalisé: '${fr.ariegenature.geomys.store.TaxRefCache.normaliser(nom)}')"
            )
            Pair(null, nom)
        }
        pendingObs.add(PendingObs(
            taxon = taxonSelector.taxon,
            espece = especeAffichee,
            cdNom = cdNom,
            nombre = 1,
            determinateur = determinateurParDefaut(),
        ))
        binding.etEspece.setText("")
        taxrefLookup.reset()
        rafraichirListe()
        declencherCaracterisationSiNidification(pendingObs.lastIndex)
    }

    /** Déterminateur préfilé à la création d'une nouvelle obs (et utilisé comme défaut
     *  dans l'écran caractérisation) : observateur sélectionné en config, sinon nom du user
     *  GeoNature connecté, sinon login en dernier recours. */
    private fun determinateurParDefaut(): String =
        gnConfig.observateurDefautNom.ifEmpty {
            gnConfig.nomUtilisateur.ifEmpty { gnConfig.login }
        }

    /** Mois (1..12) pilotant l'ouverture auto de la caractérisation. Pour l'instant le mois
     *  courant ; point UNIQUE à rebrancher sur la date de saisie le jour où la saisie d'une
     *  date antérieure sera implémentée dans le multi-taxon. */
    private fun moisPertinent(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1

    /** Oiseaux uniquement : si l'espèce qu'on vient d'ajouter est en période de nidification
     *  pour le mois pertinent, ouvre automatiquement sa caractérisation (non bloquant : l'écran
     *  reste fermable). Espèce hors fichier, mois hors période ou cd_nom non résolu => aucun
     *  effet, comportement de saisie inchangé. */
    private fun declencherCaracterisationSiNidification(index: Int) {
        val obs = pendingObs.getOrNull(index) ?: return
        if (obs.taxon != Taxon.OISEAU) return
        val cdNom = obs.cdNom ?: return
        if (NidificationOiseaux.estEnPeriode(requireContext(), cdNom, moisPertinent())) {
            ouvrirCaracterisation(index)
        }
    }


    // ─── Nombre ───────────────────────────────────────────────────────────────

    private fun showNombrePickerDialog(initial: Int, onResult: (Int) -> Unit) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_nombre_picker, null)
        val et = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_nombre)
        val btnMoins = view.findViewById<ImageButton>(R.id.btn_moins)
        val btnPlus = view.findViewById<ImageButton>(R.id.btn_plus)
        et.setText(initial.toString())
        et.setSelection(et.text?.length ?: 0)
        fun current(): Int = et.text?.toString()?.toIntOrNull()?.coerceIn(1, 999) ?: 1
        btnMoins.setOnClickListener {
            val v = (current() - 1).coerceAtLeast(1)
            et.setText(v.toString()); et.setSelection(et.text?.length ?: 0)
        }
        btnPlus.setOnClickListener {
            val v = (current() + 1).coerceAtMost(999)
            et.setText(v.toString()); et.setSelection(et.text?.length ?: 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Nombre d'individus")
            .setView(view)
            .setPositiveButton("OK") { _, _ -> onResult(current()) }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }

    // ─── Enregistrement ───────────────────────────────────────────────────────

    private fun enregistrer() {
        // Le lot est déjà sauvé au fil de l'eau (cf. synchroniserBatch via rafraichirListe) ;
        // on resynchronise par sécurité puis on revient.
        synchroniserBatch()
        // Enchaînement : si le relevé contient au moins une espèce, on demande à la carte de
        // repasser directement en mode positionnement pour placer le relevé suivant, au lieu
        // de retomber dans l'état neutre. Le flag est posé sur l'entrée TraceFragment (= écran
        // précédent) et consommé dans son onViewCreated au retour.
        if (pendingObs.isNotEmpty()) {
            findNavController().previousBackStackEntry?.savedStateHandle
                ?.set("demarrerSaisieSuivante", true)
        }
        findNavController().navigateUp()
    }

    /** Reflète l'état courant de [pendingObs] dans le TraceViewModel (qui le persiste aussitôt
     *  dans le store, cf. son auto-save). Idempotent : chaque espèce porte une id VM stable
     *  ([PendingObs.existingId] en édition, sinon [PendingObs.vmId]) → upsert ; les espèces
     *  retirées de la liste sont supprimées du relevé. Toutes partagent [releveIdSession].
     *  Appelé à chaque changement de la liste — il n'y a plus de bouton « Annuler », donc le
     *  relevé en cours est toujours conservé (au fil de l'eau). */
    private fun synchroniserBatch() {
        val releveId = releveIdSession
        val nouvelles = pendingObs.map { obs ->
            val id = obs.existingId ?: obs.vmId
            val nomFinal = obs.espece.ifEmpty { "Espèce inconnue" }
            val cdNomFinal = obs.cdNom ?: obs.cdNomManuel.trim().toIntOrNull()
            // Repart de l'obs existante (préserve date / position d'origine en édition),
            // sinon crée une nouvelle obs à la position courante.
            val base = traceViewModel.observations.value?.find { it.id == id }
                ?: Observation(id = id, espece = nomFinal, latitude = latitude, longitude = longitude)
            base.copy(
                espece                    = nomFinal,
                taxon                     = obs.taxon,
                cdNom                     = cdNomFinal,
                notes                     = obs.notes,
                nombre                    = obs.nombre,
                nombreMax                 = obs.nombreMax,
                sexe                      = obs.sexe.ifEmpty { null },
                stadeVie                  = obs.stadeVie.ifEmpty { null },
                objDenbr                  = obs.objDenbr.ifEmpty { null },
                typDenbr                  = obs.typDenbr.ifEmpty { null },
                denombrementsAdditionnels = obs.denombrementsAdditionnels,
                techniqueObs              = obs.techniqueObs.ifEmpty { null },
                statutObs                 = obs.statutObs.ifEmpty { null },
                statutBio                 = obs.statutBio.ifEmpty { null },
                etaBio                    = obs.etaBio.ifEmpty { null },
                preuveExist               = obs.preuveExist.ifEmpty { null },
                comportement              = obs.comportement.ifEmpty { null },
                methDetermin              = obs.methDetermin.ifEmpty { null },
                naturalite                = obs.naturalite.ifEmpty { null },
                determinateur             = obs.determinateur.ifEmpty { null },
                mediaUrisCounting0        = obs.mediaUrisCounting0,
                additionalFieldsReleve    = additionalFieldsReleveSession,
                idDatasetReleve           = idDatasetReleveSession,
                observateurReleveId       = idObservateurReleveSession,
                observateurReleveNom      = nomObservateurReleveSession,
                typGrpReleve              = typGrpReleveSession.ifEmpty { null },
                geometryType              = geometryTypeSession,
                geometryCoordsJson        = geometryCoordsJsonSession,
                additionalFieldsOccurrence = obs.additionalFieldsOccurrence,
                additionalFieldsCounting0 = obs.additionalFieldsCounting0,
                releveId                  = releveId,
            )
        }
        traceViewModel.remplacerObservationsDuReleve(releveId, nouvelles)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        taxrefLookup.cancel()
        speech.destroy()
        _binding = null
    }
}
