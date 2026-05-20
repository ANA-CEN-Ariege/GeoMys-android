package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentDetailsReleveBinding
import fr.ariegenature.geonat.network.AdditionalFieldDef
import fr.ariegenature.geonat.network.AdditionalFieldsObject
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.ui.saisie.AdditionalFieldsRenderer

/** Écran intercalé entre la sélection GPS (TraceFragment) et la saisie des espèces
 *  (SaisieObservationFragment). Affiche les champs additionnels niveau OCCTAX_RELEVE
 *  déclarés par le serveur et empêche la progression tant que les champs `required`
 *  ne sont pas remplis. Reste accessible ensuite via le bouton "Détails du relevé"
 *  de l'écran de saisie pour modification ultérieure. */
class DetailsReleveFragment : Fragment() {
    private var _binding: FragmentDetailsReleveBinding? = null
    private val binding get() = _binding!!

    private var defs: List<AdditionalFieldDef> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailsReleveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)

        val args = arguments
        val gnConfig = GeoNatureConfig(requireContext())
        defs = AdditionalFieldsRenderer.fromJson(gnConfig.additionalFieldsOcctaxJson)
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
            // Transmet les valeurs + GPS + géométrie reçus à SaisieObservationFragment via le bundle.
            val bundleSuite = Bundle().apply {
                putDouble("latitude",  args?.getDouble("latitude", 0.0) ?: 0.0)
                putDouble("longitude", args?.getDouble("longitude", 0.0) ?: 0.0)
                putString("addReleveJson", Gson().toJson(valeurs))
                args?.getString("geometryType")?.let { putString("geometryType", it) }
                args?.getString("geometryCoordsJson")?.let { putString("geometryCoordsJson", it) }
            }
            // popUpTo + inclusive : on retire cet écran de la backstack pour que le retour
            // depuis la saisie d'espèces ramène directement à la carte (et non aux détails).
            val navOpts = androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.detailsReleveFragment, true)
                .build()
            findNavController().navigate(R.id.action_details_releve_to_saisie, bundleSuite, navOpts)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
