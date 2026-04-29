package com.example.birdstrace.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.R
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

        val notes = arguments?.getString("notes") ?: ""
        val sexe = arguments?.getString("sexe") ?: ""
        val stadeVie = arguments?.getString("stadeVie") ?: ""
        val techniqueObs = arguments?.getString("techniqueObs") ?: ""
        val cdNomManuel = arguments?.getString("cdNomManuel") ?: ""
        val taxrefNonTrouve = arguments?.getBoolean("taxrefNonTrouve") ?: false
        val lat = arguments?.getDouble("latitude") ?: 0.0
        val lon = arguments?.getDouble("longitude") ?: 0.0

        binding.etNotes.setText(notes)
        binding.tvCoordonnees.text = "%.5f, %.5f".format(lat, lon)

        setupSpinners(sexe, stadeVie, techniqueObs)

        if (taxrefNonTrouve) {
            binding.sectionCdNom.visibility = View.VISIBLE
            binding.etCdNom.setText(cdNomManuel)
        } else {
            binding.sectionCdNom.visibility = View.GONE
        }

        binding.btnOk.setOnClickListener {
            val sv = findNavController().previousBackStackEntry?.savedStateHandle
            sv?.set("notes", binding.etNotes.text.toString())
            sv?.set("sexe", selectedSexe())
            sv?.set("stadeVie", selectedStadeVie())
            sv?.set("techniqueObs", selectedTechniqueObs())
            sv?.set("cdNomManuel", binding.etCdNom.text.toString())
            findNavController().navigateUp()
        }
    }

    private fun setupSpinners(sexe: String, stadeVie: String, techniqueObs: String) {
        val sexeOptions = listOf("Non renseigné", "Mâle", "Femelle", "Non déterminé")
        val sexeCodes = listOf("", "1", "2", "5")
        val sexeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sexeOptions)
        sexeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSexe.adapter = sexeAdapter
        binding.spinnerSexe.setSelection(sexeCodes.indexOf(sexe).coerceAtLeast(0))

        val stadeOptions = listOf("Non renseigné", "Adulte", "Juvénile", "Immature")
        val stadeCodes = listOf("", "2", "3", "4")
        val stadeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, stadeOptions)
        stadeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStadeVie.adapter = stadeAdapter
        binding.spinnerStadeVie.setSelection(stadeCodes.indexOf(stadeVie).coerceAtLeast(0))

        val techOptions = listOf("Non renseignée", "Vu", "Entendu", "Vu et entendu", "Chant", "Indices de présence")
        val techCodes = listOf("", "0", "1", "2", "4", "5")
        val techAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, techOptions)
        techAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTechnique.adapter = techAdapter
        binding.spinnerTechnique.setSelection(techCodes.indexOf(techniqueObs).coerceAtLeast(0))

        binding.spinnerSexe.tag = sexeCodes
        binding.spinnerStadeVie.tag = stadeCodes
        binding.spinnerTechnique.tag = techCodes
    }

    private fun selectedSexe(): String {
        val codes = binding.spinnerSexe.tag as? List<*> ?: return ""
        return codes.getOrNull(binding.spinnerSexe.selectedItemPosition) as? String ?: ""
    }

    private fun selectedStadeVie(): String {
        val codes = binding.spinnerStadeVie.tag as? List<*> ?: return ""
        return codes.getOrNull(binding.spinnerStadeVie.selectedItemPosition) as? String ?: ""
    }

    private fun selectedTechniqueObs(): String {
        val codes = binding.spinnerTechnique.tag as? List<*> ?: return ""
        return codes.getOrNull(binding.spinnerTechnique.selectedItemPosition) as? String ?: ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}