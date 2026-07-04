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
import com.google.gson.Gson
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentDetailsReleveBinding
import fr.ariegenature.geomys.network.AdditionalFieldDef
import fr.ariegenature.geomys.network.AdditionalFieldsObject
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer

/** Écran intercalé entre la sélection GPS (TraceFragment) et la saisie des espèces
 *  (SaisieObservationFragment). Affiche les champs additionnels niveau OCCTAX_RELEVE
 *  déclarés par le serveur et empêche la progression tant que les champs `required`
 *  ne sont pas remplis. Reste accessible ensuite via le bouton "Détails du relevé"
 *  de l'écran de saisie pour modification ultérieure. */
class DetailsReleveFragment : Fragment() {
    private var _binding: FragmentDetailsReleveBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()

    private var defs: List<AdditionalFieldDef> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailsReleveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)

        val args = arguments
        val gnConfig = GeoNatureConfig(requireContext())
        defs = AdditionalFieldsRenderer.fromJson(gnConfig.additionalFieldsOcctaxJsonActif)
            .filter { it.appliqueA(AdditionalFieldsObject.RELEVE) }
            .filter { it.visiblePour(gnConfig.idDataset.toIntOrNull(), emptyList()) }

        val valeursInitiales: Map<String, String> = try {
            val json = args?.getString("addReleveJson") ?: "{}"
            @Suppress("UNCHECKED_CAST")
            (Gson().fromJson(json, Map::class.java) as? Map<String, String>) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }

        AdditionalFieldsRenderer.rendre(binding.llChamps, defs, valeursInitiales)

        binding.btnAnnuler.setOnClickListener { findNavController().navigateUp() }

        binding.btnContinuer.setOnClickListener {
            val valeurs = AdditionalFieldsRenderer.collecter(binding.llChamps)
            val manquants = defs.filter { it.required && (valeurs[it.fieldName].isNullOrBlank()) }
                .map { it.fieldLabel }
            if (manquants.isNotEmpty()) {
                binding.tvErreur.visibility = View.VISIBLE
                binding.tvErreur.text = "Champ(s) obligatoire(s) manquant(s) : ${manquants.joinToString(", ")}"
                return@setOnClickListener
            }
            // popUpTo + inclusive : on retire cet écran de la backstack pour que le retour
            // depuis la saisie ramène directement à l'écran précédent (carte/accueil) et non
            // aux détails.
            val navOpts = androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.detailsReleveFragment, true)
                .build()
            // Mode mono-taxons : pas d'étape de positionnement préalable. Les valeurs saisies
            // deviennent le défaut de session de SaisieRapideFragment, commun à toutes les obs.
            if (args?.getBoolean("mono", false) == true) {
                val bundleMono = Bundle().apply { putString("addReleveJson", Gson().toJson(valeurs)) }
                findNavController().naviguerSur(R.id.action_details_releve_to_saisie_rapide, bundleMono, navOpts)
                return@setOnClickListener
            }
            // Multi-taxons : transmet les valeurs + GPS + géométrie reçus à SaisieObservationFragment.
            val bundleSuite = Bundle().apply {
                putDouble("latitude",  args?.getDouble("latitude", 0.0) ?: 0.0)
                putDouble("longitude", args?.getDouble("longitude", 0.0) ?: 0.0)
                putString("addReleveJson", Gson().toJson(valeurs))
                args?.getString("geometryType")?.let { putString("geometryType", it) }
                args?.getString("geometryCoordsJson")?.let { putString("geometryCoordsJson", it) }
            }
            findNavController().naviguerSur(R.id.action_details_releve_to_saisie, bundleSuite, navOpts)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
