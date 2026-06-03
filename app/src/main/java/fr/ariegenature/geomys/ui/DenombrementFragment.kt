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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.databinding.FragmentDenombrementBinding
import fr.ariegenature.geomys.model.Denombrement
import fr.ariegenature.geomys.model.Taxon
import fr.ariegenature.geomys.network.AdditionalFieldDef
import fr.ariegenature.geomys.network.AdditionalFieldsObject
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.ui.saisie.AdditionalFieldsRenderer
import fr.ariegenature.geomys.ui.saisie.ChampsTaxon
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
    private var sexeActif: Boolean = true
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
        val locales = uris.mapIndexedNotNull { i, u -> importerMedia(u, defaultMime, i) }
        if (locales.isEmpty()) return
        items[targetIdx] = items[targetIdx].copy(mediaUris = items[targetIdx].mediaUris + locales)
        rafraichir()
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
        sexeActif = sexeActifPourTaxon(taxon)
        // Filtre les définitions OCCTAX_DENOMBREMENT depuis le cache config (peut être vide).
        // Restreint par dataset courant + listes UsersHub du cd_nom observé (info récupérée au
        // sync TaxRef) : un champ avec id_list = X ne s'affiche que si le taxon appartient à X.
        val gnConfig = GeoNatureConfig(requireContext())
        val idDataset = gnConfig.idDataset.toIntOrNull()
        val cdNom = (a?.getInt("cdNom", -1) ?: -1).takeIf { it > 0 }
        val listesDuTaxon = cdNom?.let { TaxRefCache.listesPourCdNom(it) } ?: emptyList()
        defsCounting = AdditionalFieldsRenderer.fromJson(gnConfig.additionalFieldsOcctaxJson)
            .filter { it.appliqueA(AdditionalFieldsObject.COUNTING) }
            .filter { it.visiblePour(idDataset, listesDuTaxon) }

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
            collecter()
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

            val etMin = row.findViewById<TextInputEditText>(R.id.et_nombre_min)
            val etMax = row.findViewById<TextInputEditText>(R.id.et_nombre_max)
            etMin.setText(denom.nombreMin.toString())
            etMax.setText(denom.nombreMax.toString())
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

            val sexeLayout = row.findViewById<LinearLayout>(R.id.layout_sexe)
            sexeLayout.visibility = if (sexeActif) View.VISIBLE else View.GONE
            val tvStadeVieLabel = row.findViewById<TextView>(R.id.tv_stade_vie_label)
            tvStadeVieLabel.text = if (taxon == Taxon.PLANTE) "Stade phénologique" else "Stade de vie"

            setupSpinner(row.findViewById(R.id.spinner_sexe), "SEXE", denom.sexe ?: "",
                listOf("Non renseigné","Mâle","Femelle","Indéterminé"),
                listOf("","1","2","5"))
            setupSpinner(row.findViewById(R.id.spinner_stade_vie), "STADE_VIE", denom.stadeVie ?: "",
                listOf("Non renseigné","Adulte","Juvénile","Immature"),
                listOf("","2","3","4"))
            setupSpinner(row.findViewById(R.id.spinner_obj_denbr), "OBJ_DENBR", denom.objDenbr ?: "",
                listOf("Non renseigné","Individu","Couple","Nid","Famille","Groupe"),
                listOf("","1","2","3","4","5"))
            setupSpinner(row.findViewById(R.id.spinner_typ_denbr), "TYP_DENBR", denom.typDenbr ?: "",
                listOf("Non renseigné","Exact","Estimé","Minimum","Maximum"),
                listOf("","1","2","3","4"))

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

    /** Copie le fichier sélectionné (photo ou audio) dans le storage privé pour avoir un chemin stable.
     *  Le préfixe de nom (photo_/audio_) sert juste de hint humain ; le mime réel est déduit
     *  côté upload via guessContentTypeFromName. */
    private fun importerMedia(source: Uri, defaultMime: String, index: Int = 0): String? {
        val ctx = requireContext()
        val mime = ctx.contentResolver.getType(source) ?: defaultMime
        val (prefix, ext) = when {
            mime.startsWith("image/") -> "photo" to when (mime) {
                "image/jpeg" -> "jpg"
                "image/png"  -> "png"
                "image/webp" -> "webp"
                else         -> mime.substringAfter("/").ifEmpty { "jpg" }
            }
            mime.startsWith("audio/") -> "audio" to when (mime) {
                "audio/mpeg" -> "mp3"
                "audio/mp4"  -> "m4a"
                "audio/aac"  -> "aac"
                "audio/ogg"  -> "ogg"
                "audio/wav", "audio/x-wav" -> "wav"
                else         -> mime.substringAfter("/").ifEmpty { "mp3" }
            }
            else -> "media" to mime.substringAfter("/").ifEmpty { "bin" }
        }
        val dir = File(ctx.filesDir, "medias").apply { mkdirs() }
        // `index` désambiguïse les fichiers importés dans la même milliseconde (multi-sélection).
        val dest = File(dir, "${prefix}_${System.currentTimeMillis()}_$index.$ext")
        return try {
            ctx.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            dest.toURI().toString()
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Import média échoué : ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun supprimerFichierLocal(uri: String) {
        try { File(Uri.parse(uri).path ?: return).delete() } catch (_: Exception) {}
    }

    /** Relit les valeurs des champs UI vers `items` (mutation in-place). Préserve les photos. */
    private fun collecter() {
        for (i in items.indices) {
            val row = binding.llDenombrements.getChildAt(i) ?: continue
            val min = row.findViewById<TextInputEditText>(R.id.et_nombre_min).text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val maxRaw = row.findViewById<TextInputEditText>(R.id.et_nombre_max).text?.toString()?.toIntOrNull() ?: min
            val max = maxRaw.coerceAtLeast(min)
            val sexe = selectedCode(row.findViewById(R.id.spinner_sexe)).ifEmpty { null }
            val stadeVie = selectedCode(row.findViewById(R.id.spinner_stade_vie)).ifEmpty { null }
            val objDenbr = selectedCode(row.findViewById(R.id.spinner_obj_denbr)).ifEmpty { null }
            val typDenbr = selectedCode(row.findViewById(R.id.spinner_typ_denbr)).ifEmpty { null }
            val addCounting = AdditionalFieldsRenderer.collecter(row.findViewById(R.id.ll_add_counting))
            items[i] = items[i].copy(
                nombreMin = min, nombreMax = max,
                sexe = if (sexeActif) sexe else null,
                stadeVie = stadeVie, objDenbr = objDenbr, typDenbr = typDenbr,
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

    private fun sexeActifPourTaxon(taxon: Taxon): Boolean = when (taxon) {
        Taxon.OISEAU, Taxon.MAMMIFERE, Taxon.REPTILE, Taxon.BATRACIEN,
        Taxon.POISSON, Taxon.INSECTE, Taxon.MOLLUSQUE, Taxon.INVERTEBRES -> true
        Taxon.FONGE, Taxon.PLANTE -> false
    }


    private fun setupSpinner(
        spinner: Spinner, type: String, current: String,
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
        // Fallback sur le défaut serveur (defaultNomenclatures du module) si pas de
        // valeur explicite côté dénombrement courant — alignement avec l'UI web.
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
