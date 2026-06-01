/*
 * GeoNat-Android — application Android de saisie naturaliste pour GeoNature.
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

package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.databinding.FragmentCaracterisationBinding
import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.network.AdditionalFieldsObject
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.NomenclatureCache
import fr.ariegenature.geonat.store.TaxRefCache
import fr.ariegenature.geonat.ui.saisie.AdditionalFieldsRenderer
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
        val statutObs        = a?.getString("statutObs") ?: ""
        val techniqueObs     = a?.getString("techniqueObs") ?: ""
        val etaBio           = a?.getString("etaBio") ?: ""
        val comportement     = a?.getString("comportement") ?: ""
        val statutBio        = a?.getString("statutBio") ?: ""
        val methDetermin     = a?.getString("methDetermin") ?: ""
        val determinateurArg = a?.getString("determinateur") ?: ""
        val determinateurDefaut = a?.getString("determinateurDefaut") ?: ""
        val preuveExist      = a?.getString("preuveExist") ?: ""
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
        val defsOcc = AdditionalFieldsRenderer.fromJson(gnConfig.additionalFieldsOcctaxJson)
            .filter { it.visiblePour(idDataset, listesDuTaxon) }
            .filter { it.appliqueA(AdditionalFieldsObject.OCCURRENCE) }
        val gson = Gson()
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        val valeursOcc: Map<String, String> = try {
            gson.fromJson(a?.getString("addOccJson") ?: "{}", mapType) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }

        binding.tvLabelAddOccurrence.visibility = if (defsOcc.isNotEmpty()) View.VISIBLE else View.GONE
        AdditionalFieldsRenderer.rendre(binding.llAddOccurrence, defsOcc, valeursOcc)

        val champs = champsActifsCaracterisation(taxon)
        binding.layoutEtaBio.visibility       = if ("ETA_BIO"          in champs) View.VISIBLE else View.GONE
        binding.layoutComportement.visibility = if ("OCC_COMPORTEMENT" in champs) View.VISIBLE else View.GONE
        binding.layoutStatutBio.visibility    = if ("STATUT_BIO"       in champs) View.VISIBLE else View.GONE

        val (groupes, regno) = groupesEtRegno(taxon, groupe2Inpn)

        setupSpinner(binding.spinnerStatutObs,    "STATUT_OBS", statutObs,    groupes, regno,
            listOf("Non renseigné","Présent","Non observé","Présence probable","Non recherché"),
            listOf("","1","2","3","4"))
        setupSpinner(binding.spinnerTechnique,    "METH_OBS", techniqueObs, groupes, regno,
            listOf("Non renseignée","Vu","Entendu","Vu et entendu","Chant","Indices de présence"),
            listOf("","0","1","2","4","5"))
        if ("ETA_BIO" in champs)
            setupSpinner(binding.spinnerEtaBio,   "ETA_BIO", etaBio,         groupes, regno,
                listOf("Non renseigné","Vivant","Mort","Signe d'activité"),
                listOf("","1","2","3"))
        if ("OCC_COMPORTEMENT" in champs)
            setupSpinner(binding.spinnerComportement, "OCC_COMPORTEMENT", comportement, groupes, regno,
                listOf("Non renseigné","Chant","Chasse / Alimentation","Repos","Déplacement",
                    "Passage en vol","Migration","Halte migratoire","Hivernage",
                    "Nourrissage des jeunes","Territorial","Accouplement",
                    "Nidification possible","Nidification probable","Nidification certaine","Inconnu"),
                listOf("","1","2","3","4","5","6","7","8","9","10","11","12","13","14","15"))
        if ("STATUT_BIO" in champs)
            setupSpinner(binding.spinnerStatutBio, "STATUT_BIO", statutBio,   groupes, regno,
                listOf("Non renseigné","Reproduction","Pas de reproduction","Hivernation","Estivation","Non déterminé","Inconnu"),
                listOf("","1","2","3","4","5","6"))
        setupSpinner(binding.spinnerMethDetermin, "METH_DETERMIN", methDetermin, groupes, regno,
            listOf("Non renseignée","Visuel à distance","Auditif direct","Photo ou vidéo",
                "Auditif avec transformation électronique","Individu en main","Autre méthode"),
            listOf("","1","2","3","4","5","6"))
        setupSpinner(binding.spinnerPreuveExist,  "PREUVE_EXIST", preuveExist, groupes, regno,
            listOf("Non renseignée","Non","Oui","Non acquise","Inconnu"),
            listOf("","0","1","2","3"))

        binding.btnOk.setOnClickListener {
            val sv = findNavController().previousBackStackEntry?.savedStateHandle ?: return@setOnClickListener
            sv.set("statutObs",      selectedCode(binding.spinnerStatutObs))
            sv.set("techniqueObs",   selectedCode(binding.spinnerTechnique))
            sv.set("etaBio",         if ("ETA_BIO"          in champs) selectedCode(binding.spinnerEtaBio)       else "")
            sv.set("comportement",   if ("OCC_COMPORTEMENT" in champs) selectedCode(binding.spinnerComportement) else "")
            sv.set("statutBio",      if ("STATUT_BIO"       in champs) selectedCode(binding.spinnerStatutBio)    else "")
            sv.set("methDetermin",   selectedCode(binding.spinnerMethDetermin))
            sv.set("preuveExist",    selectedCode(binding.spinnerPreuveExist))
            sv.set("determinateur",  binding.etDeterminateur.text.toString())
            sv.set("notes",          binding.etNotes.text.toString())
            // Seul le niveau OCCURRENCE est édité ici ; le niveau RELEVE est géré
            // dans SaisieObservationFragment via le bouton "Détails du relevé".
            val gsonOut = Gson()
            sv.set("addOccJson", gsonOut.toJson(AdditionalFieldsRenderer.collecter(binding.llAddOccurrence)))
            findNavController().navigateUp()
        }
    }

    private fun champsActifsCaracterisation(taxon: Taxon): Set<String> = when (taxon) {
        Taxon.OISEAU    -> setOf("METH_OBS","STATUT_BIO","ETA_BIO","PREUVE_EXIST","OCC_COMPORTEMENT","METH_DETERMIN")
        Taxon.MAMMIFERE -> setOf("METH_OBS","ETA_BIO","PREUVE_EXIST","OCC_COMPORTEMENT","METH_DETERMIN")
        Taxon.REPTILE,
        Taxon.BATRACIEN,
        Taxon.POISSON,
        Taxon.INSECTE,
        Taxon.MOLLUSQUE,
        Taxon.INVERTEBRES -> setOf("METH_OBS","ETA_BIO","PREUVE_EXIST","METH_DETERMIN")
        Taxon.FONGE       -> setOf("METH_OBS","PREUVE_EXIST","METH_DETERMIN")
        Taxon.PLANTE      -> setOf("METH_OBS","PREUVE_EXIST","METH_DETERMIN")
    }

    private fun groupesEtRegno(taxon: Taxon, groupe2Inpn: String): Pair<Set<String>, String> = when (taxon) {
        Taxon.PLANTE -> Pair(NomenclatureCache.groupesBotaniquesConnus(), "Plantae")
        Taxon.FONGE  -> Pair(NomenclatureCache.GROUPES_FONGE, "Fungi")
        Taxon.MOLLUSQUE, Taxon.INVERTEBRES -> Pair(setOf("Animalia"), "Animalia")
        else -> {
            val g = groupe2Inpn.ifEmpty {
                when (taxon) {
                    Taxon.OISEAU    -> "Oiseaux"
                    Taxon.MAMMIFERE -> "Mammifères"
                    Taxon.REPTILE   -> "Reptiles"
                    Taxon.BATRACIEN -> "Amphibiens"
                    Taxon.POISSON   -> "Poissons"
                    Taxon.INSECTE   -> "Insectes"
                    else            -> ""
                }
            }
            Pair(setOf(g), NomenclatureCache.regno(pourGroupe = g))
        }
    }

    private fun setupSpinner(
        spinner: Spinner, type: String, current: String,
        groupes: Set<String>, regno: String,
        fallbackLabels: List<String>, fallbackCodes: List<String>,
    ) {
        val useCache = NomenclatureCache.estDisponible
        val (labels, codes) = if (useCache) {
            val valeurs = NomenclatureCache.filtrerPourGroupes(type, groupes, regno)
            if (valeurs.isNotEmpty())
                Pair(listOf("Non renseigné") + valeurs.map { it.label },
                     listOf("") + valeurs.map { it.id.toString() })
            else Pair(fallbackLabels, fallbackCodes)
        } else Pair(fallbackLabels, fallbackCodes)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.tag = codes
        // Si la PendingObs ne porte pas de valeur explicite pour ce champ, on applique
        // le défaut configuré côté serveur (table <module>.defaults_nomenclatures_value,
        // exposée via /api/<module>/defaultNomenclatures et cachée dans NomenclatureCache).
        val codeEffectif = current.ifEmpty { NomenclatureCache.defautPour(type) ?: "" }
        spinner.setSelection(codes.indexOf(codeEffectif).coerceAtLeast(0))
    }

    private fun selectedCode(spinner: Spinner): String {
        val codes = spinner.tag as? List<*> ?: return ""
        return codes.getOrNull(spinner.selectedItemPosition) as? String ?: ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
