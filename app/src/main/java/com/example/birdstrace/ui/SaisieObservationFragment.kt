package com.example.birdstrace.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.R
import com.example.birdstrace.TaxRefLocal
import com.example.birdstrace.databinding.FragmentSaisieObservationBinding
import com.example.birdstrace.model.Observation
import com.example.birdstrace.model.Taxon
import com.example.birdstrace.network.TaxRefService
import com.example.birdstrace.network.TaxRefStatut
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.TaxRefCache
import kotlinx.coroutines.*

class SaisieObservationFragment : Fragment() {
    private var _binding: FragmentSaisieObservationBinding? = null
    private val binding get() = _binding!!

    private var speechRecognizer: SpeechRecognizer? = null

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) demarrerEcoute() else Toast.makeText(requireContext(), "Permission micro refusée", Toast.LENGTH_SHORT).show()
    }
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var gnConfig: GeoNatureConfig

    /** Une obs en attente d'enregistrement. Contient tous les champs ; `existingId` est non-null
     *  uniquement quand on édite une obs déjà enregistrée (flow depuis la liste Observations). */
    private data class PendingObs(
        var taxon: Taxon,
        var espece: String,
        var cdNom: Int?,
        var nombre: Int = 1,
        var sexe: String = "",
        var stadeVie: String = "",
        var techniqueObs: String = "",
        var statutBio: String = "",
        var etaBio: String = "",
        var preuveExist: String = "",
        var objDenbr: String = "",
        var typDenbr: String = "",
        var comportement: String = "",
        var methDetermin: String = "",
        var determinateur: String = "",
        var notes: String = "",
        var cdNomManuel: String = "",
        val existingId: String? = null
    )

    private val pendingObs = mutableListOf<PendingObs>()
    /** Index de la PendingObs dont on édite les détails via ObservationDetailsFragment. */
    private var editingDetailsIndex: Int? = null

    // État courant du formulaire — utilisé au moment d'ajouter une nouvelle ligne.
    private var latitude = 0.0
    private var longitude = 0.0
    private var taxon: Taxon = Taxon.OISEAU
    private var rechercheNomSci = false
    private var taxRefStatut: TaxRefStatut? = null
    private var taxRefJob: Job? = null

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

        if (obsExistante != null) {
            // Édition d'une obs existante — pré-remplir la liste avec une seule entrée.
            latitude = obsExistante.latitude
            longitude = obsExistante.longitude
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
        }

        setupTaxonSelector()
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
            row.findViewById<ImageView>(R.id.iv_taxon).setImageResource(iconeTaxon(obs.taxon))
            row.findViewById<TextView>(R.id.tv_espece).apply {
                text = obs.espece
                setOnClickListener { dupliquer(index) }
            }
            row.findViewById<TextView>(R.id.tv_nombre).apply {
                text = "× ${obs.nombre}"
                setOnClickListener {
                    showNombrePickerDialog(obs.nombre) { nouveauNombre ->
                        pendingObs[index] = obs.copy(nombre = nouveauNombre)
                        rafraichirListe()
                    }
                }
            }
            row.findViewById<ImageButton>(R.id.btn_edit).setOnClickListener { reediter(index) }
            row.findViewById<ImageButton>(R.id.btn_info).setOnClickListener { ouvrirDetails(index) }
            row.findViewById<ImageButton>(R.id.btn_delete).setOnClickListener { supprimer(index) }
            binding.llPendingObs.addView(row)
        }
        updateBtnEnregistrerState()
    }

    private fun iconeTaxon(t: Taxon): Int = when (t) {
        Taxon.OISEAU      -> R.drawable.oiseaux
        Taxon.MAMMIFERE   -> R.drawable.mammiferes2
        Taxon.REPTILE     -> R.drawable.reptiles2
        Taxon.BATRACIEN   -> R.drawable.amphibiens
        Taxon.POISSON     -> R.drawable.poissons
        Taxon.INSECTE     -> R.drawable.insectes
        Taxon.FONGE       -> R.drawable.champignons2
        Taxon.INVERTEBRES -> R.drawable.mollusques
        Taxon.PLANTE      -> R.drawable.fleurs
    }

    private fun supprimer(index: Int) {
        if (index !in pendingObs.indices) return
        pendingObs.removeAt(index)
        rafraichirListe()
    }

    /** Clic sur le nom d'une ligne : ajoute une nouvelle PendingObs avec le même
     *  taxon / espèce / cd_nom — le nombre repart à 1 (l'utilisateur ajuste via le chip
     *  de la nouvelle ligne si besoin), les détails repartent à vide pour ne pas répliquer
     *  un sexe ou un stade qui appartiennent à l'autre individu observé. */
    private fun dupliquer(index: Int) {
        if (index !in pendingObs.indices) return
        val source = pendingObs[index]
        pendingObs.add(PendingObs(
            taxon = source.taxon,
            espece = source.espece,
            cdNom = source.cdNom,
            nombre = 1
        ))
        rafraichirListe()
    }

    /** Bouton ✏️ : retire la ligne et remet ses valeurs (taxon, nombre, nom) dans le formulaire
     *  pour que l'utilisateur puisse corriger puis re-sélectionner via l'autocomplétion. */
    private fun reediter(index: Int) {
        if (index !in pendingObs.indices) return
        val obs = pendingObs.removeAt(index)
        pendingNombreForNextAdd = obs.nombre
        taxon = obs.taxon
        updateTaxonUI()
        refreshAutocompleteAdapter()
        binding.etEspece.setText(obs.espece)
        binding.etEspece.setSelection(obs.espece.length)
        binding.etEspece.requestFocus()
        rafraichirListe()
    }

    /** Nombre à appliquer à la prochaine PendingObs ajoutée via l'autocomplétion. Mis à jour
     *  par reediter() pour préserver le compteur de la ligne réouverte. Toujours remis à 1
     *  après chaque ajout — les ajouts normaux et la duplication partent toujours de 1. */
    private var pendingNombreForNextAdd = 1

    private fun ouvrirDetails(index: Int) {
        if (index !in pendingObs.indices) return
        val obs = pendingObs[index]
        editingDetailsIndex = index
        val groupe2Inpn = obs.cdNom?.let { TaxRefCache.tousLesGroupes()[it.toString()] }
            ?: when (obs.taxon) {
                Taxon.MAMMIFERE   -> "Mammifères"
                Taxon.REPTILE     -> "Reptiles"
                Taxon.BATRACIEN   -> "Amphibiens"
                Taxon.POISSON     -> "Poissons"
                Taxon.INSECTE     -> "Insectes"
                Taxon.PLANTE,
                Taxon.FONGE,
                Taxon.INVERTEBRES -> null
                else              -> "Oiseaux"
            }
        val bundle = Bundle().apply {
            putString("taxon",         obs.taxon.name)
            putString("groupe2Inpn",   groupe2Inpn)
            putString("notes",         obs.notes)
            putString("sexe",          obs.sexe)
            putString("stadeVie",      obs.stadeVie)
            putString("techniqueObs",  obs.techniqueObs)
            putString("statutBio",     obs.statutBio)
            putString("etaBio",        obs.etaBio)
            putString("preuveExist",   obs.preuveExist)
            putString("objDenbr",      obs.objDenbr)
            putString("typDenbr",      obs.typDenbr)
            putString("comportement",  obs.comportement)
            putString("methDetermin",  obs.methDetermin)
            putString("determinateur", obs.determinateur)
            putString("cdNomManuel",   obs.cdNomManuel)
            putBoolean("taxrefNonTrouve", obs.cdNom == null)
            putDouble("latitude", latitude)
            putDouble("longitude", longitude)
        }
        findNavController().navigate(R.id.action_saisie_to_details, bundle)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Retour de ObservationDetailsFragment : met à jour la PendingObs en cours d'édition.
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
            handler("notes")         { o, v -> o.notes = v }
            handler("sexe")          { o, v -> o.sexe = v }
            handler("stadeVie")      { o, v -> o.stadeVie = v }
            handler("techniqueObs")  { o, v -> o.techniqueObs = v }
            handler("statutBio")     { o, v -> o.statutBio = v }
            handler("etaBio")        { o, v -> o.etaBio = v }
            handler("preuveExist")   { o, v -> o.preuveExist = v }
            handler("objDenbr")      { o, v -> o.objDenbr = v }
            handler("typDenbr")      { o, v -> o.typDenbr = v }
            handler("comportement")  { o, v -> o.comportement = v }
            handler("methDetermin")  { o, v -> o.methDetermin = v }
            handler("determinateur") { o, v -> o.determinateur = v }
            handler("cdNomManuel")   { o, v ->
                o.cdNomManuel = v
                v.trim().toIntOrNull()?.takeIf { it > 0 }?.let { o.cdNom = it }
            }
        }
    }

    // ─── Sélection taxon ──────────────────────────────────────────────────────

    private fun setupTaxonSelector() {
        updateTaxonUI()
        listOf(
            binding.btnTaxonOiseau     to Taxon.OISEAU,
            binding.btnTaxonMammifere  to Taxon.MAMMIFERE,
            binding.btnTaxonReptile    to Taxon.REPTILE,
            binding.btnTaxonBatracien  to Taxon.BATRACIEN,
            binding.btnTaxonPoisson    to Taxon.POISSON,
            binding.btnTaxonInsecte    to Taxon.INSECTE,
            binding.btnTaxonFonge      to Taxon.FONGE,
            binding.btnTaxonInvertebres to Taxon.INVERTEBRES,
            binding.btnTaxonPlante     to Taxon.PLANTE,
        ).forEach { (btn, t) ->
            btn.setOnClickListener { if (taxon != t) { taxon = t; onTaxonChanged() } }
        }
    }

    private fun onTaxonChanged() {
        binding.etEspece.setText("")
        taxRefStatut = null
        updateTaxRefUI()
        updateTaxonUI()
        refreshAutocompleteAdapter()
        updateEspeceHint()
    }

    private fun updateTaxonUI() {
        val transparent = ColorStateList.valueOf(Color.TRANSPARENT)
        val white = ColorStateList.valueOf(Color.WHITE)
        val gray = ContextCompat.getColorStateList(requireContext(), android.R.color.darker_gray)!!

        val couleurs = mapOf(
            Taxon.OISEAU      to ContextCompat.getColor(requireContext(), R.color.orange),
            Taxon.MAMMIFERE   to ContextCompat.getColor(requireContext(), R.color.brown),
            Taxon.REPTILE     to ContextCompat.getColor(requireContext(), R.color.colorSecondary),
            Taxon.BATRACIEN   to ContextCompat.getColor(requireContext(), R.color.blue_batracien),
            Taxon.POISSON     to ContextCompat.getColor(requireContext(), R.color.blue_poisson),
            Taxon.INSECTE     to ContextCompat.getColor(requireContext(), R.color.amber_insecte),
            Taxon.FONGE       to ContextCompat.getColor(requireContext(), R.color.brown_fonge),
            Taxon.INVERTEBRES to ContextCompat.getColor(requireContext(), R.color.purple_invertebres),
            Taxon.PLANTE      to ContextCompat.getColor(requireContext(), R.color.teal),
        )
        val boutons = mapOf(
            Taxon.OISEAU      to binding.btnTaxonOiseau,
            Taxon.MAMMIFERE   to binding.btnTaxonMammifere,
            Taxon.REPTILE     to binding.btnTaxonReptile,
            Taxon.BATRACIEN   to binding.btnTaxonBatracien,
            Taxon.POISSON     to binding.btnTaxonPoisson,
            Taxon.INSECTE     to binding.btnTaxonInsecte,
            Taxon.FONGE       to binding.btnTaxonFonge,
            Taxon.INVERTEBRES to binding.btnTaxonInvertebres,
            Taxon.PLANTE      to binding.btnTaxonPlante,
        )
        boutons.values.forEach { btn ->
            btn.backgroundTintList = transparent
            btn.setTextColor(gray)
            btn.iconTint = gray
        }
        boutons[taxon]?.let { btn ->
            val color = couleurs[taxon] ?: return
            btn.backgroundTintList = ColorStateList.valueOf(color)
            btn.setTextColor(Color.WHITE)
            btn.iconTint = white
        }
    }

    // ─── Espèce / autocomplete ────────────────────────────────────────────────

    private fun refreshAutocompleteAdapter() {
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.Default) {
                TaxRefLocal.getSuggestionsAutocomplete(taxon, rechercheNomSci)
            }
            if (isAdded) binding.etEspece.setAdapter(accentInsensitiveAdapter(suggestions))
        }
    }

    private fun updateEspeceHint() {
        binding.tilEspece.hint = ""
        binding.tilEspece.placeholderText = if (rechercheNomSci) when (taxon) {
            Taxon.MAMMIFERE   -> "Nom scientifique (ex: Vulpes vulpes, Meles meles…)"
            Taxon.REPTILE     -> "Nom scientifique (ex: Podarcis muralis, Vipera aspis…)"
            Taxon.BATRACIEN   -> "Nom scientifique (ex: Rana temporaria, Salamandra salamandra…)"
            Taxon.POISSON     -> "Nom scientifique (ex: Esox lucius, Salmo trutta…)"
            Taxon.INSECTE     -> "Nom scientifique (ex: Papilio machaon, Apis mellifera…)"
            Taxon.FONGE       -> "Nom scientifique (ex: Boletus edulis, Cantharellus cibarius…)"
            Taxon.INVERTEBRES -> "Nom scientifique (ex: Helix pomatia, Homarus gammarus…)"
            Taxon.PLANTE      -> "Nom scientifique (ex: Quercus robur, Rosa canina…)"
            else              -> "Nom scientifique (ex: Turdus merula, Erithacus rubecula…)"
        } else when (taxon) {
            Taxon.MAMMIFERE   -> "Espèce observée (ex: Renard roux, Blaireau…)"
            Taxon.REPTILE     -> "Espèce observée (ex: Lézard des murailles, Vipère aspic…)"
            Taxon.BATRACIEN   -> "Espèce observée (ex: Grenouille verte, Salamandre tachetée…)"
            Taxon.POISSON     -> "Espèce observée (ex: Brochet, Truite fario…)"
            Taxon.INSECTE     -> "Espèce observée (ex: Criquet mélodieux, Machaon…)"
            Taxon.FONGE       -> "Espèce observée (ex: Cèpe de Bordeaux, Chanterelle…)"
            Taxon.INVERTEBRES -> "Espèce observée (ex: Escargot de Bourgogne, Homard…)"
            Taxon.PLANTE      -> "Espèce observée (ex: Chêne pédonculé, Genêt purgatif…)"
            else              -> "Espèce observée (ex: Merle noir, Rouge-gorge…)"
        }
    }

    private fun accentInsensitiveAdapter(suggestions: List<String>): ArrayAdapter<String> {
        val normalized: List<Pair<String, String>> = suggestions.map { TaxRefCache.normaliser(it) to it }
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_dropdown_item_1line, suggestions.toMutableList()) {
            override fun getFilter() = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    val filtered: List<String> = if (constraint.isNullOrEmpty()) suggestions
                    else {
                        val query = TaxRefCache.normaliser(constraint.toString())
                        val starts = ArrayList<String>()
                        val contains = ArrayList<String>()
                        for ((key, display) in normalized) {
                            when {
                                key.startsWith(query) -> starts.add(display)
                                key.contains(query)   -> contains.add(display)
                            }
                        }
                        starts + contains
                    }
                    results.values = filtered
                    results.count = filtered.size
                    return results
                }
                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                    clear()
                    if (results.count > 0) addAll(results.values as List<String>)
                    notifyDataSetChanged()
                }
            }
        }
    }

    private fun setupAutocomplete() {
        binding.etEspece.threshold = 1
        refreshAutocompleteAdapter()
        binding.tilEspece.setEndIconOnClickListener { lancerDictee() }

        binding.switchNomSci.setOnCheckedChangeListener { _, isChecked ->
            rechercheNomSci = isChecked
            binding.etEspece.setText("")
            taxRefStatut = null
            updateTaxRefUI()
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
                // Recherche TaxRef à des fins d'affichage du statut (pas d'ajout automatique).
                lancerRechercheTaxRef(s?.toString() ?: "")
            }
        })
    }

    /** Ajoute une nouvelle PendingObs à partir d'une suggestion d'autocomplétion. */
    private fun ajouterDepuisSuggestion(nom: String) {
        val entry = TaxRefCache.get(nom)
        val (cdNom, especeAffichee) = if (entry != null) {
            val nomAffiche = entry.nomFrOriginal ?: nom
            Pair(entry.cdNom, nomAffiche)
        } else {
            // Cas rare : la suggestion vient des listes embarquées (TaxRefLocal). On accepte
            // la sélection sans cd_nom — l'utilisateur peut compléter via les détails.
            Pair(null, nom)
        }
        pendingObs.add(PendingObs(
            taxon = taxon,
            espece = especeAffichee,
            cdNom = cdNom,
            nombre = pendingNombreForNextAdd
        ))
        pendingNombreForNextAdd = 1
        binding.etEspece.setText("")
        taxRefStatut = null
        updateTaxRefUI()
        rafraichirListe()
    }

    private fun lancerRechercheTaxRef(nom: String) {
        taxRefJob?.cancel()
        if (nom.length < 2) {
            taxRefStatut = null
            updateTaxRefUI()
            return
        }
        taxRefJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            if (!isActive) return@launch
            binding.taxrefProgress.visibility = View.VISIBLE
            binding.tvTaxrefStatut.visibility = View.GONE
            val (statut, _) = TaxRefService.rechercher(nom, taxon, gnConfig)
            taxRefStatut = statut
            binding.taxrefProgress.visibility = View.GONE
            updateTaxRefUI()
        }
    }

    private fun updateTaxRefUI() {
        when (val s = taxRefStatut) {
            is TaxRefStatut.Trouve -> {
                binding.tvTaxrefStatut.visibility = View.VISIBLE
                binding.tvTaxrefStatut.text = "✓ ${s.nomScientifique}  •  cd_nom ${s.cdNom}"
                binding.tvTaxrefStatut.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
            }
            TaxRefStatut.NonTrouve -> {
                binding.tvTaxrefStatut.visibility = View.VISIBLE
                binding.tvTaxrefStatut.text = getString(R.string.taxref_non_trouve)
                binding.tvTaxrefStatut.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark))
            }
            TaxRefStatut.Indisponible -> {
                binding.tvTaxrefStatut.visibility = View.VISIBLE
                binding.tvTaxrefStatut.text = getString(R.string.taxref_indisponible)
                binding.tvTaxrefStatut.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }
            null -> {
                binding.tvTaxrefStatut.visibility = View.GONE
                binding.taxrefProgress.visibility = View.GONE
            }
        }
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
        for (obs in pendingObs) {
            val nomFinal = obs.espece.ifEmpty { "Espèce inconnue" }
            val cdNomFinal = obs.cdNom ?: obs.cdNomManuel.trim().toIntOrNull()
            if (obs.existingId != null) {
                val base = traceViewModel.observations.value?.find { it.id == obs.existingId } ?: continue
                traceViewModel.modifierObservation(base.copy(
                    espece        = nomFinal,
                    taxon         = obs.taxon,
                    cdNom         = cdNomFinal,
                    notes         = obs.notes,
                    nombre        = obs.nombre,
                    sexe          = obs.sexe.ifEmpty { null },
                    stadeVie      = obs.stadeVie.ifEmpty { null },
                    techniqueObs  = obs.techniqueObs.ifEmpty { null },
                    statutBio     = obs.statutBio.ifEmpty { null },
                    etaBio        = obs.etaBio.ifEmpty { null },
                    preuveExist   = obs.preuveExist.ifEmpty { null },
                    objDenbr      = obs.objDenbr.ifEmpty { null },
                    typDenbr      = obs.typDenbr.ifEmpty { null },
                    comportement  = obs.comportement.ifEmpty { null },
                    methDetermin  = obs.methDetermin.ifEmpty { null },
                    determinateur = obs.determinateur.ifEmpty { null }
                ))
            } else {
                traceViewModel.ajouterObservation(Observation(
                    espece        = nomFinal,
                    taxon         = obs.taxon,
                    cdNom         = cdNomFinal,
                    latitude      = latitude,
                    longitude     = longitude,
                    notes         = obs.notes,
                    nombre        = obs.nombre,
                    sexe          = obs.sexe.ifEmpty { null },
                    stadeVie      = obs.stadeVie.ifEmpty { null },
                    techniqueObs  = obs.techniqueObs.ifEmpty { null },
                    statutBio     = obs.statutBio.ifEmpty { null },
                    etaBio        = obs.etaBio.ifEmpty { null },
                    preuveExist   = obs.preuveExist.ifEmpty { null },
                    objDenbr      = obs.objDenbr.ifEmpty { null },
                    typDenbr      = obs.typDenbr.ifEmpty { null },
                    comportement  = obs.comportement.ifEmpty { null },
                    methDetermin  = obs.methDetermin.ifEmpty { null },
                    determinateur = obs.determinateur.ifEmpty { null }
                ))
            }
        }
        findNavController().navigateUp()
    }

    // ─── Dictée vocale ────────────────────────────────────────────────────────

    private fun lancerDictee() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Reconnaissance vocale non disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        demarrerEcoute()
    }

    private fun demarrerEcoute() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic_active)
                }
                override fun onResults(results: Bundle?) {
                    binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic)
                    val texte = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    binding.etEspece.setText(texte)
                    binding.etEspece.setSelection(texte.length)
                }
                override fun onError(error: Int) {
                    binding.tilEspece.setEndIconDrawable(R.drawable.ic_mic)
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH      -> "Aucune correspondance — réessayez"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune voix détectée"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erreur réseau — connexion requise"
                        SpeechRecognizer.ERROR_AUDIO         -> "Erreur microphone"
                        else -> "Erreur reconnaissance ($error)"
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val partiel = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    binding.etEspece.setText(partiel)
                    binding.etEspece.setSelection(partiel.length)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        taxRefJob?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _binding = null
    }
}
