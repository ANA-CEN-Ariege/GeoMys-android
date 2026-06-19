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

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentDenombrementBinding
import fr.ariegenature.geomys.model.Denombrement
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.AdditionalFieldDef
import fr.ariegenature.geomys.network.AdditionalFieldsObject
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.champFormVisible
import fr.ariegenature.geomys.store.OcctaxFieldsConfig
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer
import fr.ariegenature.geomys.ui.saisie.ChampsTaxon
import fr.ariegenature.geomys.ui.saisie.OcctaxFieldsRenderer
import java.io.File

/** Édition de la liste des dénombrements d'une PendingObs en saisie multi-taxons.
 *  Chaque dénombrement peut avoir ses propres photos attachées (uploadées au counting
 *  côté gn_commons lors de l'envoi du relevé). */
class DenombrementFragment : Fragment() {
    private var _binding: FragmentDenombrementBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()

    private val gson = Gson()
    private val items = mutableListOf<Denombrement>()
    private lateinit var taxon: Taxon
    private var groupe2Inpn: String = ""
    private lateinit var groupes: Set<String>
    private var regno: String = ""
    /** Champs de nomenclature du dénombrement à afficher (registre filtré par la config serveur). */
    private var champsCounting: List<OcctaxFieldsConfig.ChampAffichage> = emptyList()
    /** Config form_fields du serveur (visibilité des champs, comme le web). */
    private var formFieldsJson: String = ""
    /** Définitions des champs additionnels OCCTAX_DENOMBREMENT (chargées depuis le cache config). */
    private var defsCounting: List<AdditionalFieldDef> = emptyList()

    /** Index du counting pour lequel on attend le retour d'un picker (-1 si aucun).
     *  Partagé entre photo et audio puisqu'ils ne tournent jamais en parallèle. */
    private var pickMediaTargetIndex: Int = -1

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        consommerPickerResult(uris, defaultMime = "image/jpeg")
    }

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        consommerPickerResult(uris, defaultMime = "audio/mp4")
    }

    /** Importe TOUTES les URIs sélectionnées (multi-sélection) et les ajoute aux médias du
     *  counting ciblé. */
    private fun consommerPickerResult(uris: List<android.net.Uri>?, defaultMime: String) {
        val targetIdx = pickMediaTargetIndex
        pickMediaTargetIndex = -1
        if (uris.isNullOrEmpty() || targetIdx < 0 || targetIdx >= items.size) return
        collecter()
        // Import + RECOMPRESSION (decode/scale/EXIF/JPEG) hors thread UI : en multi-sélection
        // c'est N images à traiter, ce qui gèlerait l'UI (risque ANR) si fait sur le main.
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val locales = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, u -> MediaImport.importer(appCtx, u, defaultMime, i) }
            }
            if (_binding == null) return@launch
            if (locales.size < uris.size) {
                android.widget.Toast.makeText(requireContext(), "Import média échoué", android.widget.Toast.LENGTH_LONG).show()
            }
            if (locales.isNotEmpty() && targetIdx < items.size) {
                items[targetIdx] = items[targetIdx].copy(mediaUris = items[targetIdx].mediaUris + locales)
                rafraichir()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDenombrementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        appliquerBandeauNavigation(binding.bandeauSaisie.root, findNavController(), traceViewModel.typeSaisieLabel)

        val a = arguments
        taxon = runCatching { Taxon.valueOf(a?.getString("taxon") ?: "") }.getOrDefault(Taxon.OISEAU)
        groupe2Inpn = a?.getString("groupe2Inpn") ?: ""
        val espece = a?.getString("espece") ?: ""
        val denombrementsJson = a?.getString("denombrementsJson") ?: "[]"

        binding.tvEspece.text = espece

        val (g, r) = ChampsTaxon.groupesEtRegno(taxon, groupe2Inpn)
        groupes = g
        regno = r
        // Filtre les définitions OCCTAX_DENOMBREMENT depuis le cache config (peut être vide).
        // Restreint par dataset courant + listes UsersHub du cd_nom observé (info récupérée au
        // sync TaxRef) : un champ avec id_list = X ne s'affiche que si le taxon appartient à X.
        val gnConfig = GeoNatureConfig(requireContext())
        val idDataset = gnConfig.idDataset.toIntOrNull()
        val cdNom = (a?.getInt("cdNom", -1) ?: -1).takeIf { it > 0 }
        val listesDuTaxon = cdNom?.let { TaxRefCache.listesPourCdNom(it) } ?: emptyList()
        defsCounting = AdditionalFieldsRenderer.fromJson(gnConfig.additionalFieldsOcctaxJsonActif)
            .filter { it.appliqueA(AdditionalFieldsObject.COUNTING) }
            .filter { it.visiblePour(idDataset, listesDuTaxon) }
        // Champs de nomenclature du dénombrement : visibilité pilotée par form_fields du serveur
        // (comme le web Occtax), dans l'ordre du formulaire web. Un seul mécanisme, comme l'occurrence.
        formFieldsJson = gnConfig.formFieldsJson
        champsCounting = listOf(
            "OBJ_DENBR" to "obj_count", "TYP_DENBR" to "type_count",
            "STADE_VIE" to "life_stage", "SEXE" to "sex",
        ).mapNotNull { (code, ffk) ->
            if (!champFormVisible(formFieldsJson, ffk)) return@mapNotNull null
            OcctaxFieldsConfig.parCode[code]?.let {
                OcctaxFieldsConfig.ChampAffichage(it, replie = false, lectureSeule = false)
            }
        }
        val type = object : TypeToken<List<Denombrement>>() {}.type
        val initial: List<Denombrement> = try { gson.fromJson(denombrementsJson, type) ?: emptyList() } catch (_: Exception) { emptyList() }
        // Normalisation : Gson ne respecte pas les valeurs par défaut Kotlin lors de la
        // désérialisation — si un champ collection manque du JSON (cas d'un payload antérieur
        // à l'ajout du champ), il devient null à l'exécution. On le remplit ici.
        items.clear()
        items.addAll(if (initial.isEmpty()) listOf(Denombrement()) else initial.map { normaliser(it) })

        rafraichir()

        binding.btnAjouter.setOnClickListener {
            collecter()
            items.add(Denombrement())
            rafraichir()
        }

        binding.btnOk.setOnClickListener {
            // Blocage si un champ additionnel obligatoire (required) visible est vide, sur n'importe
            // quel dénombrement de la liste.
            val manquants = (0 until binding.llDenombrements.childCount).flatMap { i ->
                val container = binding.llDenombrements.getChildAt(i)
                    .findViewById<LinearLayout>(R.id.ll_add_counting)
                AdditionalFieldsRenderer.champsObligatoiresVides(container)
            }
            if (manquants.isNotEmpty()) {
                android.widget.Toast.makeText(requireContext(),
                    "Champs obligatoires à renseigner : ${manquants.distinct().joinToString(", ")}",
                    android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            collecter()
            // Aucune mémorisation des valeurs en défaut de session : pas de report d'une espèce à
            // l'autre (chaque dénombrement repart vide ou sur le défaut serveur).
            val sv = findNavController().previousBackStackEntry?.savedStateHandle ?: return@setOnClickListener
            sv.set("denombrementsJson", gson.toJson(items))
            findNavController().navigateUp()
        }
    }

    private fun rafraichir() {
        binding.llDenombrements.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        items.forEachIndexed { index, denom ->
            val row = inflater.inflate(R.layout.item_denombrement, binding.llDenombrements, false)
            row.findViewById<TextView>(R.id.tv_titre).text = "Dénombrement ${index + 1}"
            val btnSuppr = row.findViewById<ImageButton>(R.id.btn_supprimer)
            btnSuppr.visibility = if (items.size > 1) View.VISIBLE else View.GONE
            btnSuppr.setOnClickListener {
                collecter()
                if (items.size > 1) {
                    // Nettoie les photos locales du counting supprimé.
                    items[index].mediaUris.forEach { supprimerFichierLocal(it) }
                    items.removeAt(index)
                    rafraichir()
                }
            }

            val etMin = row.findViewById<android.widget.EditText>(R.id.et_nombre_min)
            val etMax = row.findViewById<android.widget.EditText>(R.id.et_nombre_max)
            etMin.setText(denom.nombreMin.toString())
            etMax.setText(denom.nombreMax.toString())
            // Visibilité min/max pilotée par form_fields (count_min/count_max), comme le web.
            row.findViewById<View>(R.id.til_nombre_min).visibility =
                if (champFormVisible(formFieldsJson, "count_min")) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.til_nombre_max).visibility =
                if (champFormVisible(formFieldsJson, "count_max")) View.VISIBLE else View.GONE
            // Saisie de Min → recopie automatique dans Max. On ne recopie que si Max
            // « suivait » Min (vide ou égal à l'ancienne valeur de Min) pour ne pas écraser
            // une borne max saisie volontairement (plage min-max). Listener posé APRÈS le
            // setText initial pour ne pas se déclencher au pré-remplissage.
            etMin.addTextChangedListener(object : android.text.TextWatcher {
                private var ancienMin = ""
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    ancienMin = s?.toString().orEmpty()
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val nouveauMin = s?.toString().orEmpty()
                    val maxActuel = etMax.text?.toString().orEmpty()
                    if ((maxActuel.isEmpty() || maxActuel == ancienMin) && maxActuel != nouveauMin) {
                        etMax.setText(nouveauMin)
                    }
                }
            })

            // Incrémenteurs −/+. Min planché à 1 ; Max ≥ valeur de Min courante. On passe par
            // setText (et non par un calcul direct) pour réutiliser la recopie Min→Max ci-dessus
            // et garder le curseur en fin de champ.
            fun ajuste(champ: android.widget.EditText, delta: Int, plancher: Int) {
                val courant = champ.text?.toString()?.toIntOrNull() ?: plancher
                val nouveau = (courant + delta).coerceAtLeast(plancher)
                champ.setText(nouveau.toString())
                champ.setSelection(champ.text.length)
            }
            row.findViewById<View>(R.id.btn_min_moins).setOnClickListener { ajuste(etMin, -1, 1) }
            row.findViewById<View>(R.id.btn_min_plus).setOnClickListener { ajuste(etMin, +1, 1) }
            row.findViewById<View>(R.id.btn_max_moins).setOnClickListener {
                ajuste(etMax, -1, etMin.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
            }
            row.findViewById<View>(R.id.btn_max_plus).setOnClickListener {
                ajuste(etMax, +1, etMin.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
            }

            // Nomenclatures du dénombrement (sexe/stade/objet/type) rendues dynamiquement, valeurs
            // dérivées du registre via svKey. Le label "Stade de vie" devient "Stade phénologique"
            // pour les plantes.
            val labelOverrides = if (taxon == Taxon.PLANTE) mapOf("STADE_VIE" to "Stade phénologique") else emptyMap()
            val valBySvKey = mapOf(
                "sexe" to (denom.sexe ?: ""), "stadeVie" to (denom.stadeVie ?: ""),
                "objDenbr" to (denom.objDenbr ?: ""), "typDenbr" to (denom.typDenbr ?: ""),
            )
            OcctaxFieldsRenderer.rendre(
                row.findViewById(R.id.ll_counting_nomenclatures), champsCounting,
                // Valeur propre au dénombrement (édition) ou vide → le renderer applique le défaut
                // serveur. Pas de report de l'espèce précédente (pas de défaut de session).
                champsCounting.associate { ca -> ca.champ.code to valBySvKey[ca.champ.svKey].orEmpty() },
                groupes, regno, labelOverrides,
            )

            // ── Photos attachées à ce counting ──
            val llPhotos = row.findViewById<LinearLayout>(R.id.ll_photos)
            llPhotos.removeAllViews()
            denom.mediaUris.forEach { uri ->
                val photoRow = inflater.inflate(R.layout.item_photo_denombrement, llPhotos, false)
                photoRow.findViewById<TextView>(R.id.tv_photo_nom).text =
                    File(Uri.parse(uri).path ?: "").name
                photoRow.findViewById<ImageButton>(R.id.btn_supprimer_photo).setOnClickListener {
                    collecter()
                    supprimerFichierLocal(uri)
                    items[index] = items[index].copy(mediaUris = items[index].mediaUris - uri)
                    rafraichir()
                }
                llPhotos.addView(photoRow)
            }
            row.findViewById<Button>(R.id.btn_ajouter_photo).setOnClickListener {
                collecter()
                pickMediaTargetIndex = index
                pickPhotoLauncher.launch("image/*")
            }
            row.findViewById<Button>(R.id.btn_ajouter_audio).setOnClickListener {
                collecter()
                pickMediaTargetIndex = index
                pickAudioLauncher.launch("audio/*")
            }

            // ── Champs additionnels OCCTAX_DENOMBREMENT pour ce counting ──
            val llAdd = row.findViewById<LinearLayout>(R.id.ll_add_counting)
            AdditionalFieldsRenderer.rendre(llAdd, defsCounting, denom.additionalFields)

            binding.llDenombrements.addView(row)
        }
    }

    private fun supprimerFichierLocal(uri: String) {
        try { File(Uri.parse(uri).path ?: return).delete() } catch (_: Exception) {}
    }

    /** Relit les valeurs des champs UI vers `items` (mutation in-place). Préserve les photos. */
    private fun collecter() {
        for (i in items.indices) {
            val row = binding.llDenombrements.getChildAt(i) ?: continue
            val min = row.findViewById<android.widget.EditText>(R.id.et_nombre_min).text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val maxRaw = row.findViewById<android.widget.EditText>(R.id.et_nombre_max).text?.toString()?.toIntOrNull() ?: min
            val max = maxRaw.coerceAtLeast(min)
            // Un champ masqué par la config serveur n'est pas rendu : on préserve alors la valeur
            // existante au lieu de l'écraser (absent de la map collectée). Valeurs ré-indexées par
            // svKey (= nom du champ Denombrement) via le registre.
            val nom = OcctaxFieldsRenderer.collecter(row.findViewById(R.id.ll_counting_nomenclatures))
            val nomBySvKey = nom.entries.associate { (OcctaxFieldsConfig.parCode[it.key]?.svKey ?: it.key) to it.value }
            fun champ(svKey: String, actuel: String?): String? =
                if (nomBySvKey.containsKey(svKey)) nomBySvKey[svKey]?.ifEmpty { null } else actuel
            val addCounting = AdditionalFieldsRenderer.collecter(row.findViewById(R.id.ll_add_counting))
            items[i] = items[i].copy(
                nombreMin = min, nombreMax = max,
                sexe = champ("sexe", items[i].sexe),
                stadeVie = champ("stadeVie", items[i].stadeVie),
                objDenbr = champ("objDenbr", items[i].objDenbr),
                typDenbr = champ("typDenbr", items[i].typDenbr),
                additionalFields = addCounting,
                // mediaUris : conservées telles quelles (déjà mises à jour par le picker / suppression).
            )
        }
    }

    /** Garantit que les collections non-nullables du Denombrement le sont vraiment.
     *  Contournement Gson : un champ absent du JSON est laissé à null malgré le default Kotlin. */
    @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
    private fun normaliser(d: Denombrement): Denombrement = Denombrement(
        id = (d.id as String?) ?: java.util.UUID.randomUUID().toString(),
        nombreMin = d.nombreMin,
        nombreMax = d.nombreMax,
        sexe = d.sexe,
        stadeVie = d.stadeVie,
        objDenbr = d.objDenbr,
        typDenbr = d.typDenbr,
        mediaUris = (d.mediaUris as List<String>?) ?: emptyList(),
        additionalFields = (d.additionalFields as Map<String, String>?) ?: emptyMap(),
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
