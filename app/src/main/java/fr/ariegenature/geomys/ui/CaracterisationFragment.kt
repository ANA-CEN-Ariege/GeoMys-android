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
        val notes = a?.getString("notes") ?: ""
        // Ouverture AUTO (nidification oiseaux) : on n'affiche QUE le champ Comportement (indice de
        // nidification), pour une saisie rapide. Le bouton « Détails » (comportementSeul=false) ouvre
        // au contraire tout le formulaire. En mode comportement seul, on ne touche QUE le comportement
        // (les autres champs / champs additionnels ne sont ni rendus ni réécrits).
        val comportementSeul = a?.getBoolean("comportementSeul", false) == true

        // ── Champs additionnels niveau OCCURRENCE uniquement (le niveau RELEVE est édité via
        // « Détails du relevé », partagé entre toutes les espèces). ──
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
        binding.tvLabelAddOccurrence.visibility =
            if (!comportementSeul && defsOcc.isNotEmpty()) View.VISIBLE else View.GONE
        if (!comportementSeul) AdditionalFieldsRenderer.rendre(binding.llAddOccurrence, defsOcc, valeursOcc)

        // ── Formulaire occurrence 100 % piloté par form_fields du serveur (comme le web Occtax),
        // un SEUL pipeline. Champs « basiques » (technique, état bio) toujours visibles ; tout le
        // reste dans UNE section « Avancé » repliée derrière UN toggle « Plus de champs », à l'image
        // de la gn-advanced-section du web. groupesEtRegno reste par-taxon (filtre des VALEURS). ──
        val (groupes, regno) = ChampsTaxon.groupesEtRegno(taxon, groupe2Inpn)
        val formFieldsJson = gnConfig.formFieldsJson
        val sauverDefauts = OcctaxFieldsConfig.sauvegarderValeursDefaut(gnConfig.settingsOcctaxJson)
        val densite = resources.displayMetrics.density
        val occExtraInit: Map<String, String> = try {
            gson.fromJson(a?.getString("occExtraJson") ?: "{}", mapType) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }

        // (code nomenclature, clé form_fields) dans l'ordre du web. STATUT_SOURCE/DEE_FLOU sont portés
        // dans champsOccExtra ; les autres nomenclatures via savedStateHandle (svKey → champ d'obs).
        // Mode comportement seul : un seul champ basique (le comportement), aucune section avancée.
        val occBasique = if (comportementSeul) listOf("OCC_COMPORTEMENT" to "behaviour")
            else listOf("METH_OBS" to "obs_tech", "ETA_BIO" to "bio_condition")
        val occAvanceNom = if (comportementSeul) emptyList() else listOf(
            "METH_DETERMIN" to "determination_method",
            "STATUT_OBS" to "observation_status",
            "NATURALITE" to "naturalness",
            "STATUT_BIO" to "bio_status",
            "OCC_COMPORTEMENT" to "behaviour",
            "STATUT_SOURCE" to "source_status",
            "DEE_FLOU" to "blurring",
            "PREUVE_EXIST" to "exist_proof",
        )
        val extraCodes = setOf("STATUT_SOURCE", "DEE_FLOU") // portés dans champsOccExtra

        fun construire(liste: List<Pair<String, String>>): Pair<List<OcctaxFieldsConfig.ChampAffichage>, Map<String, String>> {
            val champs = liste.mapNotNull { (code, ffk) ->
                if (!champFormVisible(formFieldsJson, ffk)) return@mapNotNull null
                OcctaxFieldsConfig.parCode[code]?.let {
                    OcctaxFieldsConfig.ChampAffichage(it, replie = false, lectureSeule = false)
                }
            }
            val valeurs = champs.associate { ca ->
                val f = ca.champ
                val init = if (f.code in extraCodes) occExtraInit[f.formFieldKey] ?: ""
                    else (a?.getString(f.svKey) ?: "").ifEmpty { if (sauverDefauts) OcctaxDefautsSession.valeur(f.code) else "" }
                f.code to init
            }
            return champs to valeurs
        }

        // Champs basiques (toujours visibles).
        val (basiqueChamps, basiqueVal) = construire(occBasique)
        OcctaxFieldsRenderer.rendre(binding.llCaracterisation, basiqueChamps, basiqueVal, groupes, regno)

        // ── Section AVANCÉE (repliée) ──
        val containerAvance = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fun titreAvance(txt: String) = android.widget.TextView(requireContext()).apply {
            text = txt; textSize = 13f
            setTextColor(requireContext().getColor(android.R.color.darker_gray))
            setPadding(0, (12 * densite).toInt(), 0, 0)
        }
        // Déterminateur (form_fields.determiner)
        val edDeterminateur = if (!comportementSeul && champFormVisible(formFieldsJson, "determiner")) {
            containerAvance.addView(titreAvance("Déterminateur"))
            android.widget.EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                isSingleLine = true
                setText(determinateurArg.ifEmpty { determinateurDefaut })
            }.also { containerAvance.addView(it) }
        } else null
        // Nomenclatures avancées
        val (avanceChamps, avanceVal) = construire(occAvanceNom)
        val containerAvanceNom = android.widget.LinearLayout(requireContext())
            .apply { orientation = android.widget.LinearLayout.VERTICAL }
        containerAvance.addView(containerAvanceNom)
        if (avanceChamps.isNotEmpty()) {
            OcctaxFieldsRenderer.rendre(containerAvanceNom, avanceChamps, avanceVal, groupes, regno)
        }
        // Preuves numérique (URL) / non numérique (texte)
        val editsProof = LinkedHashMap<String, android.widget.EditText>()
        if (!comportementSeul) listOf(
            Triple("digital_proof", "Preuve numérique (URL)", true),
            Triple("non_digital_proof", "Preuve non numérique", false),
        ).forEach { (key, label, estUrl) ->
            if (!champFormVisible(formFieldsJson, key)) return@forEach
            containerAvance.addView(titreAvance(label))
            android.widget.EditText(requireContext()).apply {
                inputType = if (estUrl) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                    else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setText(occExtraInit[key].orEmpty())
                isSingleLine = estUrl
            }.also { containerAvance.addView(it); editsProof[key] = it }
        }
        // Commentaire occurrence (form_fields.comment_occ)
        val edNotes = if (!comportementSeul && champFormVisible(formFieldsJson, "comment_occ")) {
            containerAvance.addView(titreAvance("Commentaire"))
            android.widget.EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                setText(notes)
            }.also { containerAvance.addView(it) }
        } else null

        // UN seul toggle « Plus de champs » pour toute la section avancée.
        val aDesChampsAvances = edDeterminateur != null || avanceChamps.isNotEmpty() ||
            editsProof.isNotEmpty() || edNotes != null
        if (aDesChampsAvances) {
            val toggleAvance = android.widget.TextView(requireContext()).apply {
                text = "▾ Plus de champs"
                textSize = 13f
                setTextColor(requireContext().getColor(android.R.color.holo_blue_dark))
                setPadding(0, (12 * densite).toInt(), 0, 0)
                isClickable = true
                isFocusable = true
            }
            binding.llCaracterisationFormFields.addView(toggleAvance)
            binding.llCaracterisationFormFields.addView(containerAvance)
            var ouvert = false
            toggleAvance.setOnClickListener {
                ouvert = !ouvert
                containerAvance.visibility = if (ouvert) View.VISIBLE else View.GONE
                toggleAvance.text = if (ouvert) "▴ Moins de champs" else "▾ Plus de champs"
            }
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
            // Nomenclatures (basiques + avancées) : routage par code — STATUT_SOURCE/DEE_FLOU →
            // champsOccExtra ; les autres → svKey (champ d'obs). Un champ masqué n'est pas collecté,
            // donc n'écrase pas la valeur déjà portée par l'obs.
            val occExtra = HashMap(occExtraInit)
            val pourDefaut = mutableMapOf<String, String>()
            (OcctaxFieldsRenderer.collecter(binding.llCaracterisation) +
                OcctaxFieldsRenderer.collecter(containerAvanceNom)).forEach { (code, valeur) ->
                val f = OcctaxFieldsConfig.parCode[code] ?: return@forEach
                if (f.code in extraCodes) {
                    if (valeur.isEmpty()) occExtra.remove(f.formFieldKey) else occExtra[f.formFieldKey] = valeur
                } else {
                    sv.set(f.svKey, valeur)
                    pourDefaut[code] = valeur
                }
            }
            if (sauverDefauts) OcctaxDefautsSession.memoriser(pourDefaut)
            edDeterminateur?.let { sv.set("determinateur", it.text.toString()) }
            edNotes?.let { sv.set("notes", it.text.toString()) }
            editsProof.forEach { (key, ed) ->
                val v = ed.text?.toString()?.trim().orEmpty()
                if (v.isEmpty()) occExtra.remove(key) else occExtra[key] = v
            }
            // En mode comportement seul, les champs additionnels et occExtra ne sont PAS rendus :
            // on ne les réécrit pas (sinon on écraserait les valeurs déjà portées par l'obs).
            if (!comportementSeul) {
                val gsonOut = Gson()
                sv.set("addOccJson", gsonOut.toJson(AdditionalFieldsRenderer.collecter(binding.llAddOccurrence)))
                sv.set("occExtraJson", gsonOut.toJson(occExtra))
            }
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
