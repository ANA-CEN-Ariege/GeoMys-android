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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geomys.databinding.FragmentObservationDetailsBinding
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OcctaxFieldsConfig
import fr.ariegenature.geomys.ui.saisie.ChampsTaxon
import fr.ariegenature.geomys.ui.saisie.OcctaxFieldsRenderer

class ObservationDetailsFragment : Fragment() {
    private var _binding: FragmentObservationDetailsBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObservationDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)

        val taxon         = runCatching { Taxon.valueOf(arguments?.getString("taxon") ?: "") }.getOrDefault(Taxon.OISEAU)
        val groupe2Inpn   = arguments?.getString("groupe2Inpn") ?: ""
        val notes         = arguments?.getString("notes") ?: ""
        val determinateur = arguments?.getString("determinateur") ?: ""
        val cdNomManuel   = arguments?.getString("cdNomManuel") ?: ""
        val taxrefNonTrouve = arguments?.getBoolean("taxrefNonTrouve") ?: false
        val lat = arguments?.getDouble("latitude") ?: 0.0
        val lon = arguments?.getDouble("longitude") ?: 0.0

        binding.etNotes.setText(notes)
        binding.etDeterminateur.setText(determinateur)
        binding.tvCoordonnees.text = "%.5f, %.5f".format(lat, lon)

        // Groupes de filtrage + regno selon le taxon — filtrage des VALEURS de nomenclature.
        val (groupes, regno) = ChampsTaxon.groupesEtRegno(taxon, groupe2Inpn)
        val settingsJson = GeoNatureConfig(requireContext()).settingsOcctaxJson
        val labelOverrides = if (taxon == Taxon.PLANTE) mapOf("STADE_VIE" to "Stade phénologique") else emptyMap()

        // Visibilité/ordre pilotés par la config serveur ; valeurs courantes dérivées du registre
        // via svKey (= clé d'argument). Aucun mnémonique listé ici.
        fun rendreNiveau(container: android.widget.LinearLayout, niveau: OcctaxFieldsConfig.Niveau) {
            val champs = OcctaxFieldsConfig.champsVisibles(settingsJson, niveau)
            OcctaxFieldsRenderer.rendre(
                container, champs,
                champs.associate { it.code to (arguments?.getString(it.svKey) ?: "") },
                groupes, regno, labelOverrides,
            )
        }
        rendreNiveau(binding.llInformation, OcctaxFieldsConfig.Niveau.INFORMATION)
        rendreNiveau(binding.llCounting, OcctaxFieldsConfig.Niveau.COUNTING)

        if (taxrefNonTrouve) {
            binding.sectionCdNom.visibility = View.VISIBLE
            binding.etCdNom.setText(cdNomManuel)
        } else {
            binding.sectionCdNom.visibility = View.GONE
        }

        binding.btnOk.setOnClickListener {
            val sv = findNavController().previousBackStackEntry?.savedStateHandle
            sv?.set("notes", binding.etNotes.text.toString())
            (OcctaxFieldsRenderer.collecter(binding.llInformation) +
                OcctaxFieldsRenderer.collecter(binding.llCounting)).forEach { (code, valeur) ->
                OcctaxFieldsConfig.parCode[code]?.svKey?.let { sv?.set(it, valeur) }
            }
            sv?.set("determinateur", binding.etDeterminateur.text.toString())
            sv?.set("cdNomManuel",   binding.etCdNom.text.toString())
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
