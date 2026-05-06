package com.example.birdstrace.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.databinding.FragmentObservationDetailsBinding

class ObservationDetailsFragment : Fragment() {
    private var _binding: FragmentObservationDetailsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObservationDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        setupSpinners(sexe, stadeVie, techniqueObs, statutBio, etaBio, preuveExist, objDenbr, typDenbr, comportement, methDetermin)

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
        sexe: String, stadeVie: String, techniqueObs: String,
        statutBio: String, etaBio: String, preuveExist: String,
        objDenbr: String, typDenbr: String, comportement: String, methDetermin: String
    ) {
        spinner(binding.spinnerSexe,
            listOf("Non renseigné", "Mâle", "Femelle", "Indéterminé"),
            listOf("", "1", "2", "5"), sexe)

        spinner(binding.spinnerStadeVie,
            listOf("Non renseigné", "Adulte", "Juvénile", "Immature"),
            listOf("", "2", "3", "4"), stadeVie)

        spinner(binding.spinnerTechnique,
            listOf("Non renseignée", "Vu", "Entendu", "Vu et entendu", "Chant", "Indices de présence"),
            listOf("", "0", "1", "2", "4", "5"), techniqueObs)

        spinner(binding.spinnerStatutBio,
            listOf("Non renseigné", "Reproduction", "Pas de reproduction", "Hivernation", "Estivation", "Non déterminé", "Inconnu"),
            listOf("", "1", "2", "3", "4", "5", "6"), statutBio)

        spinner(binding.spinnerEtaBio,
            listOf("Non renseigné", "Vivant", "Mort", "Signe d'activité"),
            listOf("", "1", "2", "3"), etaBio)

        spinner(binding.spinnerPreuveExist,
            listOf("Non renseignée", "Non", "Oui", "Non acquise", "Inconnu"),
            listOf("", "0", "1", "2", "3"), preuveExist)

        spinner(binding.spinnerObjDenbr,
            listOf("Non renseigné", "Individu", "Couple", "Nid", "Famille", "Groupe"),
            listOf("", "1", "2", "3", "4", "5"), objDenbr)

        spinner(binding.spinnerTypDenbr,
            listOf("Non renseigné", "Exact", "Estimé", "Minimum", "Maximum"),
            listOf("", "1", "2", "3", "4"), typDenbr)

        spinner(binding.spinnerComportement,
            listOf("Non renseigné", "Chant", "Chasse / Alimentation", "Repos", "Déplacement",
                "Passage en vol", "Migration", "Halte migratoire", "Hivernage",
                "Nourrissage des jeunes", "Territorial", "Accouplement",
                "Nidification possible", "Nidification probable", "Nidification certaine", "Inconnu"),
            listOf("", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"),
            comportement)

        spinner(binding.spinnerMethDetermin,
            listOf("Non renseignée", "Visuel à distance", "Auditif direct", "Photo ou vidéo",
                "Auditif avec transformation électronique", "Individu en main", "Autre méthode"),
            listOf("", "1", "2", "3", "4", "5", "6"), methDetermin)
    }

    private fun spinner(spinner: android.widget.Spinner, labels: List<String>, codes: List<String>, current: String) {
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
