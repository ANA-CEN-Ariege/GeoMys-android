package com.example.birdstrace.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.R
import com.example.birdstrace.databinding.FragmentAccueilBinding
import com.example.birdstrace.store.GeoNatureConfig
import com.example.birdstrace.store.SortieStore

class AccueilFragment : Fragment() {
    private var _binding: FragmentAccueilBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var sortieStore: SortieStore
    private lateinit var gnConfig: GeoNatureConfig

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAccueilBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sortieStore = SortieStore(requireContext())
        gnConfig = GeoNatureConfig(requireContext())

        // Insets : fond plein écran, boutons à l'écart des barres système.
        binding.btnMenu.applyStatusBarMargin()
        binding.topRightContainer.applyStatusBarInset()
        binding.accueilContent.applySystemBarInsets()

        val prefs = requireContext().getSharedPreferences("GeoNat_prefs", android.content.Context.MODE_PRIVATE)
        binding.switchTrace.isChecked = prefs.getBoolean("enregistrer_trace", true)
        binding.switchTrace.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enregistrer_trace", isChecked).apply()
        }

        binding.btnNouveauSortie.setOnClickListener {
            findNavController().navigate(R.id.action_accueil_to_trace)
        }

        binding.btnSaisieRapide.setOnClickListener {
            findNavController().navigate(R.id.action_accueil_to_saisie_rapide)
        }

        binding.btnMenu.setOnClickListener { view ->
            PopupMenu(requireContext(), view).apply {
                menuInflater.inflate(R.menu.menu_accueil, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menu_mes_sorties -> {
                            findNavController().navigate(R.id.action_accueil_to_sorties)
                            true
                        }
                        R.id.menu_explorer -> {
                            findNavController().navigate(R.id.action_accueil_to_explorer)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }

        binding.btnConfig.setOnClickListener {
            findNavController().navigate(R.id.action_accueil_to_config)
        }

        traceViewModel.locationTracker.estEnCours.observe(viewLifecycleOwner) { updateButtonState() }
    }

    override fun onResume() {
        super.onResume()
        updateSortiesCount()
        updateGnIndicator()
        updateButtonState()
    }

    private fun updateButtonState() {
        val enCours = traceViewModel.locationTracker.estEnCours.value == true
        binding.indicateurEnregistrement.visibility = if (enCours) View.VISIBLE else View.GONE
    }

    private fun updateSortiesCount() {
        // badge supprimé avec le bouton Mes sorties
    }

    private fun updateGnIndicator() {
        binding.indicateurGn.visibility = if (gnConfig.estConfiguree) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}