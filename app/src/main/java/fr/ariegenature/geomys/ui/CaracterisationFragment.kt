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
import fr.ariegenature.geomys.databinding.FragmentCaracterisationBinding
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.AdditionalFieldsObject
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.OcctaxDefautsSession
import fr.ariegenature.geomys.store.OcctaxFieldsConfig
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.champFormVisible
import fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer
import fr.ariegenature.geomys.ui.saisie.ChampsTaxon
import fr.ariegenature.geomys.ui.saisie.OcctaxFieldsRenderer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Édition des champs de caractérisation de l'occurrence pour une PendingObs en saisie multi-taxons.
 *  Le dénombrement (sexe, stade de vie, effectifs, OBJ/TYP_DENBR, photos) est édité ailleurs via
 *  [DenombrementFragment]. */
class CaracterisationFragment : Fragment() {
    private var _binding: FragmentCaracterisationBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCaracterisationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)

        val a = arguments
        val taxon = runCatching { Taxon.valueOf(a?.getString("taxon") ?: "") }.getOrDefault(Taxon.OISEAU)
        val groupe2Inpn = a?.getString("groupe2Inpn") ?: ""
        val determinateurArg = a?.getString("determinateur") ?: ""
        val determinateurDefaut = a?.getString("determinateurDefaut") ?: ""
        val notes            = a?.getString("notes") ?: ""

        binding.etDeterminateur.setText(determinateurArg.ifEmpty { determinateurDefaut })
        binding.etNotes.setText(notes)

        // ── Champs additionnels niveau OCCURRENCE uniquement ──
        // Les champs niveau RELEVE sont édités au niveau de la session via le bouton
        // "Détails du relevé" de SaisieObservationFragment et partagés entre toutes les
        // espèces du relevé — ils ne sont donc plus dupliqués dans cet écran par-espèce.
        val gnConfig = GeoNatureConfig(requireContext())
        val idDataset = gnConfig.idDataset.toIntOrNull()
        val cdNom = (a?.getInt("cdNom", -1) ?: -1).takeIf { it > 0 }
        val listesDuTaxon = cdNom?.let { TaxRefCache.listesPourCdNom(it) } ?: emptyList()
        val defsOcc = AdditionalFieldsRenderer.fromJson(gnConfig.additionalFieldsOcctaxJsonActif)
            .filter { it.visiblePour(idDataset, listesDuTaxon) }
            .filter { it.appliqueA(AdditionalFieldsObject.OCCURRENCE) }
        val gson = Gson()
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        val valeursOcc: Map<String, String> = try {
            gson.fromJson(a?.getString("addOccJson") ?: "{}", mapType) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }

        binding.tvLabelAddOccurrence.visibility = if (defsOcc.isNotEmpty()) View.VISIBLE else View.GONE
        AdditionalFieldsRenderer.rendre(binding.llAddOccurrence, defsOcc, valeursOcc)

        // Champs de nomenclature de l'occurrence : visibilité/ordre pilotés par la config serveur
        // (settings.json → information[]), rendus dynamiquement. groupesEtRegno reste par-taxon pour
        // filtrer les VALEURS de chaque nomenclature.
        // Champs et valeurs courantes dérivés du registre unique : chaque champ porte son svKey
        // (= clé d'argument et champ d'obs), donc plus aucun mnémonique listé ici.
        val (groupes, regno) = ChampsTaxon.groupesEtRegno(taxon, groupe2Inpn)
        val settingsJson = gnConfig.settingsOcctaxJson
        val champs = OcctaxFieldsConfig.champsAffichage(settingsJson, OcctaxFieldsConfig.Niveau.INFORMATION)
        // save_default_values : un champ vide d'une nouvelle obs reprend la dernière valeur de session.
        val sauverDefauts = OcctaxFieldsConfig.sauvegarderValeursDefaut(settingsJson)
        val valeursNom = champs.associate { ca ->
            val depuisArg = a?.getString(ca.champ.svKey) ?: ""
            ca.champ.code to depuisArg.ifEmpty { if (sauverDefauts) OcctaxDefautsSession.valeur(ca.champ.code) else "" }
        }
        OcctaxFieldsRenderer.rendre(binding.llCaracterisation, champs, valeursNom, groupes, regno)

        // ── Champs occurrence pilotés DIRECTEMENT par form_fields du serveur (hors curation
        // settings.json) : nomenclatures statut de la source / floutage + preuves numérique /
        // non numérique (texte). Portés de bout en bout dans la map unique champsOccExtra (clé =
        // clé form_fields), comme les champs supplémentaires du relevé. Un champ masqué garde sa
        // valeur initiale. ──
        val formFieldsJson = gnConfig.formFieldsJson
        val occExtraInit: Map<String, String> = try {
            gson.fromJson(a?.getString("occExtraJson") ?: "{}", mapType) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
        val densite = resources.displayMetrics.density
        // (a) nomenclatures (STATUT_SOURCE → source_status, DEE_FLOU → blurring)
        val nomenclOccFF = buildList {
            listOf("STATUT_SOURCE" to "source_status", "DEE_FLOU" to "blurring").forEach { (code, ffk) ->
                if (champFormVisible(formFieldsJson, ffk)) OcctaxFieldsConfig.parCode[code]
                    ?.let { add(OcctaxFieldsConfig.ChampAffichage(it, replie = false, lectureSeule = false)) }
            }
        }
        val containerNomFF = android.widget.LinearLayout(requireContext())
            .apply { orientation = android.widget.LinearLayout.VERTICAL }
        binding.llCaracterisationFormFields.addView(containerNomFF)
        if (nomenclOccFF.isNotEmpty()) {
            val valeursFF = nomenclOccFF.associate { it.champ.code to (occExtraInit[it.champ.formFieldKey] ?: "") }
            OcctaxFieldsRenderer.rendre(containerNomFF, nomenclOccFF, valeursFF, groupes, regno)
        }
        // (b) textes : preuve numérique (URL) / preuve non numérique
        val editsProof = LinkedHashMap<String, android.widget.EditText>()
        listOf(
            Triple("digital_proof", "Preuve numérique (URL)", true),
            Triple("non_digital_proof", "Preuve non numérique", false),
        ).forEach { (key, label, estUrl) ->
            if (!champFormVisible(formFieldsJson, key)) return@forEach
            binding.llCaracterisationFormFields.addView(android.widget.TextView(requireContext()).apply {
                text = label; textSize = 13f
                setTextColor(requireContext().getColor(android.R.color.darker_gray))
                setPadding(0, (12 * densite).toInt(), 0, 0)
            })
            val ed = android.widget.EditText(requireContext()).apply {
                inputType = if (estUrl) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                    else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setText(occExtraInit[key].orEmpty())
                isSingleLine = estUrl
            }
            binding.llCaracterisationFormFields.addView(ed)
            editsProof[key] = ed
        }

        binding.btnOk.setOnClickListener {
            // Blocage si un champ additionnel obligatoire (required) visible est vide.
            val manquants = AdditionalFieldsRenderer.champsObligatoiresVides(binding.llAddOccurrence)
            if (manquants.isNotEmpty()) {
                android.widget.Toast.makeText(requireContext(),
                    "Champs obligatoires à renseigner : ${manquants.joinToString(", ")}",
                    android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val sv = findNavController().previousBackStackEntry?.savedStateHandle ?: return@setOnClickListener
            // Seuls les champs effectivement rendus sont reportés : un champ masqué par la config
            // serveur n'écrase pas la valeur éventuellement déjà portée par l'obs.
            val choix = OcctaxFieldsRenderer.collecter(binding.llCaracterisation)
            choix.forEach { (code, valeur) ->
                OcctaxFieldsConfig.parCode[code]?.svKey?.let { sv.set(it, valeur) }
            }
            if (sauverDefauts) OcctaxDefautsSession.memoriser(choix)
            sv.set("determinateur",  binding.etDeterminateur.text.toString())
            sv.set("notes",          binding.etNotes.text.toString())
            // Seul le niveau OCCURRENCE est édité ici ; le niveau RELEVE est géré
            // dans SaisieObservationFragment via le bouton "Détails du relevé".
            val gsonOut = Gson()
            sv.set("addOccJson", gsonOut.toJson(AdditionalFieldsRenderer.collecter(binding.llAddOccurrence)))
            // Champs occurrence pilotés form_fields : on repart de l'initial (préserve les champs
            // masqués) et on surcharge ceux visibles (vide ⇒ retrait de la clé).
            val occExtra = HashMap(occExtraInit)
            if (nomenclOccFF.isNotEmpty()) {
                val collectes = OcctaxFieldsRenderer.collecter(containerNomFF)
                nomenclOccFF.forEach { ca ->
                    val v = collectes[ca.champ.code].orEmpty()
                    if (v.isEmpty()) occExtra.remove(ca.champ.formFieldKey) else occExtra[ca.champ.formFieldKey] = v
                }
            }
            editsProof.forEach { (key, ed) ->
                val v = ed.text?.toString()?.trim().orEmpty()
                if (v.isEmpty()) occExtra.remove(key) else occExtra[key] = v
            }
            sv.set("occExtraJson", gsonOut.toJson(occExtra))
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
