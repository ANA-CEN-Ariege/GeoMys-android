package com.example.birdstrace.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.databinding.FragmentObservationDetailsBinding
import com.example.birdstrace.model.Taxon
import com.example.birdstrace.store.NomenclatureCache
import com.example.birdstrace.store.NomValeur
import com.example.birdstrace.store.TaxRefCache

class ObservationDetailsFragment : Fragment() {
    private var _binding: FragmentObservationDetailsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObservationDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val taxonName     = arguments?.getString("taxon") ?: Taxon.OISEAU.name
        val taxon         = runCatching { Taxon.valueOf(taxonName) }.getOrDefault(Taxon.OISEAU)
        val groupe2Inpn   = arguments?.getString("groupe2Inpn")
        val notes         = arguments?.getString("notes") ?: ""
        val sexe          = arguments?.getString("sexe") ?: ""
        val stadeVie      = arguments?.getString("stadeVie") ?: ""
        val techniqueObs  = arguments?.getString("techniqueObs") ?: ""
        val statutBio     = arguments?.getString("statutBio") ?: ""
        val etaBio        = arguments?.getString("etaBio") ?: ""
        val preuveExist   = arguments?.getString("preuveExist") ?: ""
        val objDenbr      = arguments?.getString("objDenbr") ?: ""
        val typDenbr      = arguments?.getString("typDenbr") ?: ""
        val comportement  = arguments?.getString("comportement") ?: ""
        val methDetermin  = arguments?.getString("methDetermin") ?: ""
        val determinateur = arguments?.getString("determinateur") ?: ""
        val cdNomManuel   = arguments?.getString("cdNomManuel") ?: ""
        val taxrefNonTrouve = arguments?.getBoolean("taxrefNonTrouve") ?: false
        val lat = arguments?.getDouble("latitude") ?: 0.0
        val lon = arguments?.getDouble("longitude") ?: 0.0

        binding.etNotes.setText(notes)
        binding.etDeterminateur.setText(determinateur)
        binding.tvCoordonnees.text = "%.5f, %.5f".format(lat, lon)

        // Masquer les champs non pertinents pour les Plantes
        val estPlante = taxon == Taxon.PLANTE
        binding.layoutSexe.visibility        = if (estPlante) View.GONE else View.VISIBLE
        binding.layoutStatutBio.visibility   = if (estPlante) View.GONE else View.VISIBLE
        binding.layoutComportement.visibility = if (estPlante) View.GONE else View.VISIBLE

        setupSpinners(taxon, groupe2Inpn, sexe, stadeVie, techniqueObs, statutBio, etaBio,
                      preuveExist, objDenbr, typDenbr, comportement, methDetermin)

        if (taxrefNonTrouve) {
            binding.sectionCdNom.visibility = View.VISIBLE
            binding.etCdNom.setText(cdNomManuel)
        } else {
            binding.sectionCdNom.visibility = View.GONE
        }

        binding.btnOk.setOnClickListener {
            val sv = findNavController().previousBackStackEntry?.savedStateHandle
            sv?.set("notes",         binding.etNotes.text.toString())
            sv?.set("sexe",          selectedCode(binding.spinnerSexe))
            sv?.set("stadeVie",      selectedCode(binding.spinnerStadeVie))
            sv?.set("techniqueObs",  selectedCode(binding.spinnerTechnique))
            sv?.set("statutBio",     selectedCode(binding.spinnerStatutBio))
            sv?.set("etaBio",        selectedCode(binding.spinnerEtaBio))
            sv?.set("preuveExist",   selectedCode(binding.spinnerPreuveExist))
            sv?.set("objDenbr",      selectedCode(binding.spinnerObjDenbr))
            sv?.set("typDenbr",      selectedCode(binding.spinnerTypDenbr))
            sv?.set("comportement",  selectedCode(binding.spinnerComportement))
            sv?.set("methDetermin",  selectedCode(binding.spinnerMethDetermin))
            sv?.set("determinateur", binding.etDeterminateur.text.toString())
            sv?.set("cdNomManuel",   binding.etCdNom.text.toString())
            findNavController().navigateUp()
        }
    }

    private fun setupSpinners(
        taxon: Taxon,
        groupe2Inpn: String?,
        sexe: String, stadeVie: String, techniqueObs: String,
        statutBio: String, etaBio: String, preuveExist: String,
        objDenbr: String, typDenbr: String, comportement: String, methDetermin: String
    ) {
        val useCache = NomenclatureCache.estDisponible

        if (useCache) {
            val groupes = groupesFiltrage(taxon, groupe2Inpn)
            spinnerCache(binding.spinnerSexe,         "SEXE",            groupes, sexe)
            spinnerCache(binding.spinnerStadeVie,     "STADE_VIE",       groupes, stadeVie)
            spinnerCache(binding.spinnerTechnique,    "METH_OBS",        groupes, techniqueObs)
            spinnerCache(binding.spinnerStatutBio,    "STATUT_BIO",      groupes, statutBio)
            spinnerCache(binding.spinnerEtaBio,       "ETA_BIO",         groupes, etaBio)
            spinnerCache(binding.spinnerPreuveExist,  "PREUVE_EXIST",    groupes, preuveExist)
            spinnerCache(binding.spinnerObjDenbr,     "OBJ_DENBR",       groupes, objDenbr)
            spinnerCache(binding.spinnerTypDenbr,     "TYP_DENBR",       groupes, typDenbr)
            spinnerCache(binding.spinnerComportement, "OCC_COMPORTEMENT",groupes, comportement)
            spinnerCache(binding.spinnerMethDetermin, "METH_DETERMIN",   groupes, methDetermin)
        } else {
            spinnerStatique(binding.spinnerSexe,
                listOf("Non renseigné", "Mâle", "Femelle", "Indéterminé"),
                listOf("", "1", "2", "5"), sexe)

            spinnerStatique(binding.spinnerStadeVie,
                listOf("Non renseigné", "Adulte", "Juvénile", "Immature"),
                listOf("", "2", "3", "4"), stadeVie)

            spinnerStatique(binding.spinnerTechnique,
                listOf("Non renseignée", "Vu", "Entendu", "Vu et entendu", "Chant", "Indices de présence"),
                listOf("", "0", "1", "2", "4", "5"), techniqueObs)

            spinnerStatique(binding.spinnerStatutBio,
                listOf("Non renseigné", "Reproduction", "Pas de reproduction", "Hivernation", "Estivation", "Non déterminé", "Inconnu"),
                listOf("", "1", "2", "3", "4", "5", "6"), statutBio)

            spinnerStatique(binding.spinnerEtaBio,
                listOf("Non renseigné", "Vivant", "Mort", "Signe d'activité"),
                listOf("", "1", "2", "3"), etaBio)

            spinnerStatique(binding.spinnerPreuveExist,
                listOf("Non renseignée", "Non", "Oui", "Non acquise", "Inconnu"),
                listOf("", "0", "1", "2", "3"), preuveExist)

            spinnerStatique(binding.spinnerObjDenbr,
                listOf("Non renseigné", "Individu", "Couple", "Nid", "Famille", "Groupe"),
                listOf("", "1", "2", "3", "4", "5"), objDenbr)

            spinnerStatique(binding.spinnerTypDenbr,
                listOf("Non renseigné", "Exact", "Estimé", "Minimum", "Maximum"),
                listOf("", "1", "2", "3", "4"), typDenbr)

            spinnerStatique(binding.spinnerComportement,
                listOf("Non renseigné", "Chant", "Chasse / Alimentation", "Repos", "Déplacement",
                    "Passage en vol", "Migration", "Halte migratoire", "Hivernage",
                    "Nourrissage des jeunes", "Territorial", "Accouplement",
                    "Nidification possible", "Nidification probable", "Nidification certaine", "Inconnu"),
                listOf("", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"),
                comportement)

            spinnerStatique(binding.spinnerMethDetermin,
                listOf("Non renseignée", "Visuel à distance", "Auditif direct", "Photo ou vidéo",
                    "Auditif avec transformation électronique", "Individu en main", "Autre méthode"),
                listOf("", "1", "2", "3", "4", "5", "6"), methDetermin)
        }
    }

    // Calcule l'ensemble des groupes à utiliser pour le filtrage des nomenclatures
    private fun groupesFiltrage(taxon: Taxon, groupe2Inpn: String?): Set<String> {
        return when (taxon) {
            Taxon.PLANTE -> {
                // Union de tous les groupes botaniques connus dans le cache
                val animalGroups = setOf("Oiseaux", "Mammifères", "Reptiles")
                TaxRefCache.tousLesGroupes().values
                    .filter { it !in animalGroups }
                    .toSet()
            }
            else -> setOfNotNull(groupe2Inpn)
        }
    }

    // Spinner alimenté par NomenclatureCache filtré par groupe
    private fun spinnerCache(
        spinner: android.widget.Spinner,
        type: String,
        groupes: Set<String>,
        current: String
    ) {
        val valeurs = NomenclatureCache.filtrerPourGroupes(type, groupes)
        val labels = listOf("Non renseigné") + valeurs.map { it.label }
        val codes  = listOf("") + valeurs.map { it.id.toString() }
        spinnerStatique(spinner, labels, codes, current)
    }

    private fun spinnerStatique(spinner: android.widget.Spinner, labels: List<String>, codes: List<String>, current: String) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.tag = codes
        spinner.setSelection(codes.indexOf(current).coerceAtLeast(0))
    }

    private fun selectedCode(spinner: android.widget.Spinner): String {
        val codes = spinner.tag as? List<*> ?: return ""
        return codes.getOrNull(spinner.selectedItemPosition) as? String ?: ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
