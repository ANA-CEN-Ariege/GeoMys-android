package fr.ariegenature.geonat.ui

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
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.TaxRefLocal
import fr.ariegenature.geonat.databinding.FragmentSaisieObservationBinding
import fr.ariegenature.geonat.model.Observation
import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.network.TaxRefStatut
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.TaxRefCache
import fr.ariegenature.geonat.ui.saisie.SpeechToTextHelper
import fr.ariegenature.geonat.ui.saisie.TaxRefLookupController
import fr.ariegenature.geonat.ui.saisie.TaxonSelector
import fr.ariegenature.geonat.ui.saisie.createSpeciesAutocompleteAdapter
import fr.ariegenature.geonat.ui.saisie.taxonIcon
import kotlinx.coroutines.*

class SaisieObservationFragment : Fragment() {
    private var _binding: FragmentSaisieObservationBinding? = null
    private val binding get() = _binding!!

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
        var denombrementsAdditionnels: List<fr.ariegenature.geonat.model.Denombrement> = emptyList(),
        // ── Caractérisation de l'occurrence ──
        var statutObs: String = "",
        var techniqueObs: String = "",
        var statutBio: String = "",
        var etaBio: String = "",
        var preuveExist: String = "",
        var comportement: String = "",
        var methDetermin: String = "",
        var determinateur: String = "",
        var notes: String = "",
        var cdNomManuel: String = "",
        // ── Médias par counting ──
        var mediaUrisCounting0: List<String> = emptyList(),
        val existingId: String? = null
    )

    private val pendingObs = mutableListOf<PendingObs>()
    /** Index de la PendingObs dont on édite les détails via ObservationDetailsFragment. */
    private var editingDetailsIndex: Int? = null

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
        gnConfig = GeoNatureConfig(requireContext())

        val obsId = arguments?.getString("obsId")
        val obsExistante = obsId?.let { id ->
            traceViewModel.observations.value?.find { it.id == id }
        }

        val taxonInitial: Taxon
        if (obsExistante != null) {
            latitude = obsExistante.latitude
            longitude = obsExistante.longitude
            taxonInitial = obsExistante.taxon ?: Taxon.OISEAU
            pendingObs.add(PendingObs(
                taxon = obsExistante.taxon ?: Taxon.OISEAU,
                espece = obsExistante.espece,
                cdNom = obsExistante.cdNom,
                nombre = obsExistante.nombre,
                sexe = obsExistante.sexe ?: "",
                stadeVie = obsExistante.stadeVie ?: "",
                techniqueObs = obsExistante.techniqueObs ?: "",
                statutBio = obsExistante.statutBio ?: "",
                etaBio = obsExistante.etaBio ?: "",
                preuveExist = obsExistante.preuveExist ?: "",
                objDenbr = obsExistante.objDenbr ?: "",
                typDenbr = obsExistante.typDenbr ?: "",
                comportement = obsExistante.comportement ?: "",
                methDetermin = obsExistante.methDetermin ?: "",
                determinateur = obsExistante.determinateur ?: "",
                notes = obsExistante.notes,
                cdNomManuel = obsExistante.cdNom?.toString() ?: "",
                existingId = obsExistante.id
            ))
            requireActivity().title = "Modifier l'observation"
        } else {
            latitude = arguments?.getDouble("latitude") ?: 0.0
            longitude = arguments?.getDouble("longitude") ?: 0.0
            taxonInitial = Taxon.OISEAU
        }

        taxonSelector = TaxonSelector(
            requireContext(),
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
            initial = taxonInitial,
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

        binding.btnAnnuler.setOnClickListener { findNavController().navigateUp() }
        binding.btnEnregistrer.setOnClickListener { enregistrer() }
        updateBtnEnregistrerState()
    }

    // ─── Liste des obs en attente ─────────────────────────────────────────────

    private fun rafraichirListe() {
        binding.llPendingObs.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        pendingObs.forEachIndexed { index, obs ->
            val row = inflater.inflate(R.layout.item_pending_obs, binding.llPendingObs, false)
            row.findViewById<ImageView>(R.id.iv_taxon).setImageResource(taxonIcon(obs.taxon))
            row.findViewById<TextView>(R.id.tv_espece).text = obs.espece
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
        updateBtnEnregistrerState()
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

    private fun ouvrirCaracterisation(index: Int) {
        if (index !in pendingObs.indices) return
        val obs = pendingObs[index]
        editingDetailsIndex = index
        val bundle = Bundle().apply {
            putString("taxon",               obs.taxon.name)
            putString("groupe2Inpn",         groupe2InpnPour(obs))
            putString("statutObs",           obs.statutObs)
            putString("techniqueObs",        obs.techniqueObs)
            putString("etaBio",              obs.etaBio)
            putString("comportement",        obs.comportement)
            putString("statutBio",           obs.statutBio)
            putString("methDetermin",        obs.methDetermin)
            putString("determinateur",       obs.determinateur)
            // Même chaîne de fallback que la création d'obs (helper determinateurParDefaut).
            putString("determinateurDefaut", determinateurParDefaut())
            putString("preuveExist",         obs.preuveExist)
            putString("notes",               obs.notes)
        }
        findNavController().navigate(R.id.action_saisie_to_caracterisation, bundle)
    }

    private fun ouvrirDenombrement(index: Int) {
        if (index !in pendingObs.indices) return
        val obs = pendingObs[index]
        editingDetailsIndex = index
        // Sérialise la liste complète des dénombrements (counting #0 issu des champs flat + additionnels).
        val counting0 = fr.ariegenature.geonat.model.Denombrement(
            nombreMin = obs.nombre,
            nombreMax = obs.nombreMax ?: obs.nombre,
            sexe = obs.sexe.ifEmpty { null },
            stadeVie = obs.stadeVie.ifEmpty { null },
            objDenbr = obs.objDenbr.ifEmpty { null },
            typDenbr = obs.typDenbr.ifEmpty { null },
            mediaUris = obs.mediaUrisCounting0,
        )
        val tous = listOf(counting0) + obs.denombrementsAdditionnels
        val json = com.google.gson.Gson().toJson(tous)
        val bundle = Bundle().apply {
            putString("taxon",             obs.taxon.name)
            putString("groupe2Inpn",       groupe2InpnPour(obs))
            putString("espece",            obs.espece)
            putString("denombrementsJson", json)
        }
        findNavController().navigate(R.id.action_saisie_to_denombrement, bundle)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        findNavController().currentBackStackEntry?.savedStateHandle?.apply {
            val handler: (String, (PendingObs, String) -> Unit) -> Unit = { key, setter ->
                getLiveData<String>(key).observe(viewLifecycleOwner) { value ->
                    val idx = editingDetailsIndex ?: return@observe
                    if (idx in pendingObs.indices) {
                        setter(pendingObs[idx], value)
                        rafraichirListe()
                    }
                }
            }
            // Caractérisation (renvoyée par CaracterisationFragment)
            handler("statutObs")          { o, v -> o.statutObs = v }
            handler("techniqueObs")       { o, v -> o.techniqueObs = v }
            handler("etaBio")             { o, v -> o.etaBio = v }
            handler("comportement")       { o, v -> o.comportement = v }
            handler("statutBio")          { o, v -> o.statutBio = v }
            handler("methDetermin")       { o, v -> o.methDetermin = v }
            handler("determinateur")      { o, v -> o.determinateur = v }
            handler("preuveExist")        { o, v -> o.preuveExist = v }
            handler("notes")              { o, v -> o.notes = v }
            // Legacy : ObservationDetailsFragment (saisie rapide) renvoie aussi sexe/stade/etc.
            // mais cet écran n'est plus déclenché depuis la saisie multi-taxons — on garde quand
            // même les handlers au cas où.
            handler("sexe")        { o, v -> o.sexe = v }
            handler("stadeVie")    { o, v -> o.stadeVie = v }
            handler("objDenbr")    { o, v -> o.objDenbr = v }
            handler("typDenbr")    { o, v -> o.typDenbr = v }
            handler("cdNomManuel") { o, v ->
                o.cdNomManuel = v
                v.trim().toIntOrNull()?.takeIf { it > 0 }?.let { o.cdNom = it }
            }
            // Dénombrements (renvoyés par DenombrementFragment) — JSON sérialisé d'une List<Denombrement>
            getLiveData<String>("denombrementsJson").observe(viewLifecycleOwner) { json ->
                val idx = editingDetailsIndex ?: return@observe
                if (idx !in pendingObs.indices) return@observe
                val type = object : com.google.gson.reflect.TypeToken<List<fr.ariegenature.geonat.model.Denombrement>>() {}.type
                val liste: List<fr.ariegenature.geonat.model.Denombrement> = try {
                    com.google.gson.Gson().fromJson(json, type) ?: emptyList()
                } catch (_: Exception) { emptyList() }
                if (liste.isEmpty()) return@observe
                val obs = pendingObs[idx]
                val c0 = liste[0]
                obs.nombre = c0.nombreMin
                obs.nombreMax = if (c0.nombreMax != c0.nombreMin) c0.nombreMax else null
                obs.sexe = c0.sexe ?: ""
                obs.stadeVie = c0.stadeVie ?: ""
                obs.objDenbr = c0.objDenbr ?: ""
                obs.typDenbr = c0.typDenbr ?: ""
                obs.mediaUrisCounting0 = c0.mediaUris
                obs.denombrementsAdditionnels = if (liste.size > 1) liste.drop(1) else emptyList()
                rafraichirListe()
            }
        }
    }

    // ─── Espèce / autocomplete ────────────────────────────────────────────────

    private fun onTaxonChanged() {
        binding.etEspece.setText("")
        taxrefLookup.reset()
        refreshAutocompleteAdapter()
        updateEspeceHint()
    }

    private fun refreshAutocompleteAdapter() {
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.Default) {
                TaxRefLocal.getSuggestionsAutocomplete(taxonSelector.taxon, rechercheNomSci)
            }
            if (isAdded) binding.etEspece.setAdapter(
                createSpeciesAutocompleteAdapter(requireContext(), suggestions)
            )
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
        binding.tilEspece.hint = ""
        binding.tilEspece.placeholderText = if (rechercheNomSci) "Nom scientifique ($nomGroupe)"
                                            else                "Espèce observée ($nomGroupe)"
    }

    private fun setupAutocomplete() {
        binding.etEspece.threshold = 1
        refreshAutocompleteAdapter()
        updateEspeceHint()
        binding.tilEspece.setEndIconOnClickListener { speech.lancer() }

        binding.switchNomSci.setOnCheckedChangeListener { _, isChecked ->
            rechercheNomSci = isChecked
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
    }

    /** Ajoute une nouvelle PendingObs à partir d'une suggestion d'autocomplétion. */
    private fun ajouterDepuisSuggestion(nom: String) {
        val entry = TaxRefCache.get(nom)
        val (cdNom, especeAffichee) = if (entry != null) {
            val nomAffiche = entry.nomFrOriginal ?: nom
            Pair(entry.cdNom, nomAffiche)
        } else {
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
    }

    /** Déterminateur préfilé à la création d'une nouvelle obs (et utilisé comme défaut
     *  dans l'écran caractérisation) : observateur sélectionné en config, sinon nom du user
     *  GeoNature connecté, sinon login en dernier recours. */
    private fun determinateurParDefaut(): String =
        gnConfig.observateurDefautNom.ifEmpty {
            gnConfig.nomUtilisateur.ifEmpty { gnConfig.login }
        }

    private fun updateBtnEnregistrerState() {
        binding.btnEnregistrer.isEnabled = pendingObs.isNotEmpty()
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
        if (pendingObs.isEmpty()) {
            findNavController().navigateUp()
            return
        }
        // Toutes les obs créées dans cette session de saisie multi-taxons partagent un
        // même releveId : à l'envoi, GeoNatureUpload les fusionnera en un seul relevé.
        val releveIdBatch = java.util.UUID.randomUUID().toString()
        for (obs in pendingObs) {
            val nomFinal = obs.espece.ifEmpty { "Espèce inconnue" }
            val cdNomFinal = obs.cdNom ?: obs.cdNomManuel.trim().toIntOrNull()
            if (obs.existingId != null) {
                val base = traceViewModel.observations.value?.find { it.id == obs.existingId } ?: continue
                traceViewModel.modifierObservation(base.copy(
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
                    determinateur             = obs.determinateur.ifEmpty { null },
                    mediaUrisCounting0        = obs.mediaUrisCounting0,
                ))
            } else {
                traceViewModel.ajouterObservation(Observation(
                    espece                    = nomFinal,
                    taxon                     = obs.taxon,
                    cdNom                     = cdNomFinal,
                    latitude                  = latitude,
                    longitude                 = longitude,
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
                    determinateur             = obs.determinateur.ifEmpty { null },
                    mediaUrisCounting0        = obs.mediaUrisCounting0,
                    releveId                  = releveIdBatch,
                ))
            }
        }
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        taxrefLookup.cancel()
        speech.destroy()
        _binding = null
    }
}
