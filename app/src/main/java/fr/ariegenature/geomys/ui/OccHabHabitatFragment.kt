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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentOcchabHabitatBinding
import fr.ariegenature.geomys.model.OccHabHabitat
import fr.ariegenature.geomys.network.HabitatService
import fr.ariegenature.geomys.network.HabitatSuggestion
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.util.AnaEval
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Écran de création / édition d'UN habitat OccHab (champs pilotés par `OCCHAB.formConfig`).
 * Affiché directement après la carte (et après la saisie du jeu de données à la 1ʳᵉ station).
 * « Valider » ajoute l'habitat à la station et renvoie sur la liste des habitats.
 */
class OccHabHabitatFragment : Fragment() {
    private var _binding: FragmentOcchabHabitatBinding? = null
    private val binding get() = _binding!!
    private val occhabViewModel: OccHabViewModel by activityViewModels()
    private lateinit var gnConfig: GeoNatureConfig

    private var existant: OccHabHabitat? = null
    private var cdHabChoisi: Int? = null
    private var champHab: MaterialAutoCompleteTextView? = null
    private var champDeterminateur: EditText? = null
    private var champPrecision: EditText? = null
    private var champRecouvr: EditText? = null
    private var lireInteret: () -> Int? = { null }
    private var lireTypeDeterm: () -> Int? = { null }
    private var lireTech: () -> Int? = { null }
    private var lireAbon: () -> Int? = { null }

    /** Lecteur de la section « Évaluation ANA / Natura 2000 » (bloc ANA-EVAL du plugin QGIS) —
     *  non null SEULEMENT quand l'habitat édité porte un bloc (habitat lu du serveur). Habitat
     *  sans bloc : AUCUN changement d'UI. */
    private var lireAnaEval: (() -> String?)? = null

    /** true si le spinner « Technique de collecte » est AFFICHÉ (formConfig) : requis côté web
     *  (form-service.ts `id_nomenclature_collection_technique: Validators.required`) — on ne
     *  bloque que si l'utilisateur peut agir (champ masqué → valeur préservée, anti-impasse). */
    private var techniqueVisible = false

    /** Libellé du champ « Précision technique » — astérisque DYNAMIQUE : requis seulement quand
     *  la technique de collecte vaut cd_nomenclature "10" (« Autre, préciser »), parité avec le
     *  technicalValidator du web. */
    private var labelPrecision: TextView? = null

    /** Vrai si la technique sélectionnée est « Autre, préciser » (cd_nomenclature "10").
     *  cd absent du cache (synchro antérieure à l'ajout du champ) → false : règle inactive. */
    private fun techniqueEstAutre(): Boolean {
        val id = lireTech() ?: return false
        return NomenclatureCache.get("TECHNIQUE_COLLECT_HAB").firstOrNull { it.id == id }?.cd == "10"
    }

    private fun precisionRequise(): Boolean = champPrecision != null && techniqueEstAutre()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOcchabHabitatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.racine.applySystemBarInsets()
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), "OccHab")
        gnConfig = GeoNatureConfig(requireContext())

        // Mort du process pendant la saisie (ViewModel reconstruit VIERGE) : valider un habitat
        // créerait une station fantôme (0,0) envoyable — on renvoie à l'accueil (audit 2026-08-27).
        if (!occhabViewModel.station.geometrieDefinie()) {
            Toast.makeText(requireContext(),
                "Saisie interrompue (application relancée) — reprenez depuis « Mes stations »",
                Toast.LENGTH_LONG).show()
            findNavController().popBackStack(R.id.accueilFragment, false)
            return
        }

        val habitatId = arguments?.getString("habitatId")
        existant = habitatId?.let { id -> occhabViewModel.station.habitats.firstOrNull { it.id == id } }

        construireFormulaire()
        binding.btnValider.setOnClickListener { valider() }
        // État initial du bouton (édition d'un habitat existant → actif ; création → inactif
        // tant qu'aucun habitat HABREF n'est choisi). Même patron que « Enregistrer » de
        // Nouvelle visite (monitoring) : bouton grisé tant que l'obligatoire n'est pas rempli.
        majEtatBoutonValider()

        // NB : le formulaire des informations OBLIGATOIRES du relevé (JDD, observateurs,
        // dates, nature objet géo) s'affiche désormais DÈS LA CARTE au démarrage de la
        // session (OccHabCarteFragment) — plus rien à intercaler ici.
    }

    private val density get() = resources.displayMetrics.density

    private fun label(t: String) = TextView(requireContext()).apply {
        text = t
        setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /** « Valider l'habitat » actif SEULEMENT quand les champs REQUIS sont remplis — alignés sur
     *  les Validators.required du client web OccHab (form-service.ts) :
     *   - habitat HABREF (toujours) ;
     *   - technique de collecte (si affichée) ;
     *   - précision technique SI technique = « Autre, préciser » (cd "10") — technicalValidator.
     *  Même patron que le bouton « Enregistrer » de Nouvelle visite (grisé Material). À rappeler
     *  à chaque changement de champ requis. Met aussi à jour l'astérisque dynamique du libellé
     *  « Précision technique ». */
    private fun majEtatBoutonValider() {
        val habitatOk = (cdHabChoisi ?: 0) > 0
        val techniqueOk = !techniqueVisible || lireTech() != null
        val precisionRequise = precisionRequise()
        val precisionOk = !precisionRequise ||
            !champPrecision?.text?.toString()?.trim().isNullOrEmpty()
        _binding?.btnValider?.isEnabled = habitatOk && techniqueOk && precisionOk
        labelPrecision?.text =
            if (precisionRequise) "Précision technique *" else "Précision technique"
    }

    private fun construireFormulaire() {
        val ctx = requireContext()
        val racine = binding.fieldsContainer
        val ex = existant

        // Autocomplétion HABREF (cache dédié OccHab, sensible aux accents comme le web).
        cdHabChoisi = ex?.cdHab?.takeIf { it > 0 }
        val libelles = ArrayList<String>()
        val cdParLibelle = HashMap<String, Int>()
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line, libelles) {
            private val neutre = object : Filter() {
                override fun performFiltering(c: CharSequence?) = FilterResults().apply {
                    values = libelles; count = libelles.size
                }
                override fun publishResults(c: CharSequence?, r: FilterResults?) = notifyDataSetChanged()
            }
            override fun getFilter(): Filter = neutre
        }
        val champ = MaterialAutoCompleteTextView(ctx).apply {
            setAdapter(adapter); threshold = 0; isSingleLine = true
            hint = "Rechercher un habitat (HABREF)…"
            setText(ex?.habitatLabel.orEmpty(), false)
        }
        champHab = champ
        var majProg = false
        var job: Job? = null
        champ.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (majProg) return
                cdHabChoisi = null
                majEtatBoutonValider()
                val terme = s?.toString().orEmpty()
                job?.cancel()
                if (terme.trim().length < 2) return
                job = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    val res = chercherHabitats(terme)
                    libelles.clear(); cdParLibelle.clear()
                    res.forEach { libelles.add(it.libelle); cdParLibelle[it.libelle] = it.cdHab }
                    adapter.notifyDataSetChanged()
                    if (libelles.isNotEmpty() && champ.isFocused) champ.showDropDown()
                }
            }
        })
        champ.setOnItemClickListener { _, _, pos, _ ->
            val libelle = adapter.getItem(pos) ?: return@setOnItemClickListener
            cdHabChoisi = cdParLibelle[libelle]
            majProg = true
            champ.setText(libelle, false)
            majProg = false
            majEtatBoutonValider()
            masquerClavier(champ)
        }
        // Re-clic sur un habitat déjà sélectionné : on vide le champ (le TextWatcher annule la
        // sélection) pour pouvoir chercher une autre valeur — même geste que le champ JDD.
        champ.setOnClickListener {
            if (cdHabChoisi != null) champ.setText("", false)
        }

        val estNouveau = ex == null
        fun idInit(valExistante: Int?, mnem: String): Int? =
            valExistante ?: if (estNouveau) gnConfig.occhabDefautNomenclature(mnem) else null
        fun visible(champC: String) = gnConfig.occhabChampVisible(champC)

        // « * » = requis pour valider UN HABITAT (le bouton reste grisé sans lui). La STATION,
        // elle, est déjà enregistrée à la validation de la carte — un retour arrière la garde
        // SANS habitat (valide et envoyable telle quelle, décision terrain 2026-08-24).
        racine.addView(label("Habitat (HABREF) *")); racine.addView(champ)
        racine.addView(TextView(ctx).apply {
            text = "La station est déjà enregistrée — l'habitat est facultatif : " +
                "revenez en arrière pour la laisser sans habitat."
            textSize = 12f
            setPadding(0, (4 * density).toInt(), 0, 0)
        })

        lireInteret = if (visible("community_interest")) {
            val (sp, lire) = construireSpinner("HAB_INTERET_COM", idInit(ex?.idNomInteretCommunautaire, "HAB_INTERET_COM"))
            racine.addView(label("Habitat d'intérêt communautaire")); racine.addView(sp); lire
        } else { { ex?.idNomInteretCommunautaire } }

        champDeterminateur = if (visible("determiner")) {
            val et = EditText(ctx).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(ex?.determiner.orEmpty()); hint = "Déterminateur"
            }
            racine.addView(label("Déterminateur")); racine.addView(et); et
        } else null

        lireTypeDeterm = if (visible("determination_type")) {
            val (sp, lire) = construireSpinner("DETERMINATION_TYP_HAB", idInit(ex?.idNomTypeDetermination, "DETERMINATION_TYP_HAB"))
            racine.addView(label("Type de détermination")); racine.addView(sp); lire
        } else { { ex?.idNomTypeDetermination } }

        techniqueVisible = visible("collection_technique")
        lireTech = if (techniqueVisible) {
            // « * » : requis côté web (form-service.ts). Le changement de sélection recalcule le
            // bouton Valider ET l'obligation conditionnelle de la précision (technique cd "10").
            val (sp, lire) = construireSpinner("TECHNIQUE_COLLECT_HAB", idInit(ex?.idNomTechniqueCollecte, "TECHNIQUE_COLLECT_HAB"))
            racine.addView(label("Technique de collecte *")); racine.addView(sp)
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    majEtatBoutonValider()
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = majEtatBoutonValider()
            }
            lire
        } else { { ex?.idNomTechniqueCollecte } }

        champPrecision = if (visible("technical_precision")) {
            val et = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(ex?.precisionTechnique.orEmpty()); hint = "Précision technique"
                // Requise quand la technique = « Autre, préciser » → la frappe recalcule le bouton.
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { majEtatBoutonValider() }
                })
            }
            labelPrecision = label("Précision technique")
            racine.addView(labelPrecision); racine.addView(et); et
        } else null

        champRecouvr = if (visible("recovery_percentage")) {
            val et = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(ex?.recouvrement?.let {
                    if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
                }.orEmpty())
                hint = "Recouvrement en %"
            }
            racine.addView(label("Recouvrement (%)")); racine.addView(et); et
        } else null

        lireAbon = if (visible("abundance")) {
            val (sp, lire) = construireSpinner("ABONDANCE_HAB", idInit(ex?.idNomAbondance, "ABONDANCE_HAB"))
            racine.addView(label("Abondance")); racine.addView(sp); lire
        } else { { ex?.idNomAbondance } }

        // ── Évaluation ANA / Natura 2000 (bloc ANA-EVAL du plugin QGIS occhab-qgis) :
        //    UNIQUEMENT quand l'habitat édité porte un bloc (habitat lu du serveur). Clés
        //    HABITAT selon champs.py (+ pee) ; les autres clés du bloc (recouvrement,
        //    determination, corresp…) traversent telles quelles. ──
        lireAnaEval = ex?.anaEvalJson?.let { ana ->
            construireSectionAnaEval(ctx, racine, AnaEval.CHAMPS_HABITAT, avecPee = true, anaEvalJson = ana)
        }
    }

    private fun valider() {
        // Gardes MIROIR du bouton grisé (course clic/sélection) — parité Validators.required web.
        if (techniqueVisible && lireTech() == null) {
            toast("Choisissez une technique de collecte")
            majEtatBoutonValider()
            return
        }
        if (precisionRequise() && champPrecision?.text?.toString()?.trim().isNullOrEmpty()) {
            toast("Précisez la technique de collecte (« Autre, préciser »)")
            majEtatBoutonValider()
            return
        }
        val cd = cdHabChoisi
        if (cd != null && cd > 0) {
            // Un habitat est renseigné → on l'ajoute / le met à jour sur la station.
            val libelle = champHab?.text?.toString()?.trim().orEmpty()
            // Champ VISIBLE → sa valeur fait foi (vide = effacé) ; champ MASQUÉ par formConfig →
            // on préserve la valeur existante. L'ancien repli `?: existant?.x` empêchait d'EFFACER
            // un champ visible (la valeur précédente réapparaissait).
            val habitat = (existant ?: OccHabHabitat()).copy(
                cdHab = cd,
                habitatLabel = libelle,
                // `nom_cite` : le NOM CITÉ existant (saisi sur le web / QGIS, ≠ libellé HABREF)
                // est CONSERVÉ tant que l'habitat HABREF ne change pas — le réécrire à chaque
                // validation envoyait une donnée fausse et faisait passer un habitat intact pour
                // modifié (audit 2026-08-27). Nouvel habitat ou HABREF changé → libellé HABREF.
                nomCite = existant.let { ex ->
                    if (ex != null && ex.cdHab == cd && ex.nomCite.isNotBlank()) ex.nomCite else libelle
                },
                determiner = champDeterminateur?.let { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?: if (champDeterminateur == null) existant?.determiner else null,
                precisionTechnique = champPrecision?.let { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?: if (champPrecision == null) existant?.precisionTechnique else null,
                // Virgule décimale (claviers français) acceptée comme le point — « 12,5 » donnait
                // null en silence (recouvrement perdu, y compris dans le bloc ANA).
                recouvrement = champRecouvr?.let { it.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() }
                    ?: if (champRecouvr == null) existant?.recouvrement else null,
                idNomTechniqueCollecte = lireTech(),
                idNomAbondance = lireAbon(),
                idNomTypeDetermination = lireTypeDeterm(),
                idNomInteretCommunautaire = lireInteret(),
                // Bloc ANA-EVAL : la SECTION fait foi quand elle est affichée (null = bloc vidé,
                // il disparaîtra du texte à l'envoi) ; sinon le bloc existant traverse tel quel.
                anaEvalJson = lireAnaEval.let { if (it != null) it() else existant?.anaEvalJson },
            )
            occhabViewModel.ajouterOuMajHabitat(habitat)
        } else {
            // Garde de cohérence : le bouton est GRISÉ tant qu'aucun habitat HABREF n'est
            // choisi (champ obligatoire — une station n'existe qu'avec au moins un habitat).
            // On ne devrait jamais passer ici ; toast au cas où (course clic/effacement).
            toast("Choisissez un habitat dans la liste HABREF")
            majEtatBoutonValider()
            return
        }
        // Habitat ajouté ci-dessus → enregistrement AU FIL DE L'EAU de la station complète.
        // C'est ICI qu'une NOUVELLE station entre dans le store (1er habitat validé) — jamais
        // avant : règle métier « pas de station sans habitat ».
        val station = occhabViewModel.stationAEnregistrer()
        val ok = fr.ariegenature.geomys.store.OccHabStore(requireContext())
            .upsertStation(occhabViewModel.saisieId, station)
        if (!ok) {
            // Écriture disque échouée (disque plein ?) : rester sur l'écran — naviguer laisserait
            // croire que l'habitat est enregistré alors qu'il ne vit qu'en mémoire (audit 2026-08-23).
            alerterEchecEcritureStore(requireContext(),
                "Libérez de l'espace (photos, cache de cartes) puis revalidez cet habitat.")
            return
        }
        findNavController().naviguerSur(R.id.action_occhab_habitat_to_liste)
    }

    private fun construireSpinner(type: String, idCourant: Int?): Pair<Spinner, () -> Int?> =
        construireSpinnerNomenclature(requireContext(), type, idCourant)

    private suspend fun chercherHabitats(terme: String): List<HabitatSuggestion> {
        val base = gnConfig.urlServeur.trim().trimEnd('/')
        val idList = gnConfig.occhabIdListHabitat.takeIf { it > 0 }
        return HabitatService.rechercher(base, terme, 20, idList, occhab = true)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
