package fr.ariegenature.geonat.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.databinding.FragmentCaracterisationBinding
import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.store.NomenclatureCache
import java.io.File

/** Édition des champs de caractérisation de l'occurrence pour une PendingObs en saisie multi-taxons.
 *  Le dénombrement (sexe, stade de vie, effectifs, OBJ/TYP_DENBR) est édité ailleurs via
 *  [DenombrementFragment]. */
class CaracterisationFragment : Fragment() {
    private var _binding: FragmentCaracterisationBinding? = null
    private val binding get() = _binding!!

    /** URI courante du média copié dans le storage privé (file:///…), ou null. */
    private var mediaUri: String? = null
    private var mediaMimeType: String? = null

    /** Picker images. Le contrat GetContent retourne une content:// URI temporaire — on copie
     *  immédiatement le fichier dans le storage privé pour avoir un chemin stable. */
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importerMedia(it, "image/*") }
    }
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importerMedia(it, "audio/*") }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCaracterisationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)

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
        mediaUri      = a?.getString("mediaUri")?.takeIf { it.isNotEmpty() }
        mediaMimeType = a?.getString("mediaMimeType")?.takeIf { it.isNotEmpty() }

        // Préremplissage : si pas de déterminateur saisi, on prend le login GeoNature.
        binding.etDeterminateur.setText(determinateurArg.ifEmpty { determinateurDefaut })
        binding.etNotes.setText(notes)
        updateMediaPreview()

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

        binding.btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnPickAudio.setOnClickListener { pickAudioLauncher.launch("audio/*") }
        binding.btnRemoveMedia.setOnClickListener {
            supprimerMediaLocal()
            mediaUri = null
            mediaMimeType = null
            updateMediaPreview()
        }

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
            sv.set("mediaUri",       mediaUri ?: "")
            sv.set("mediaMimeType",  mediaMimeType ?: "")
            findNavController().navigateUp()
        }
    }

    /** Copie le fichier sélectionné dans le storage privé de l'app pour avoir un chemin stable
     *  qui survit à la révocation de la permission URI du picker. */
    private fun importerMedia(source: Uri, mimeHint: String) {
        val ctx = requireContext()
        val mime = ctx.contentResolver.getType(source) ?: mimeHint
        val ext = ext(mime)
        // Supprime l'éventuel média précédent pour ne pas accumuler de fichiers orphelins.
        supprimerMediaLocal()
        val dir = File(ctx.filesDir, "medias").apply { mkdirs() }
        val dest = File(dir, "media_${System.currentTimeMillis()}.$ext")
        try {
            ctx.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            mediaUri = dest.toURI().toString()
            mediaMimeType = mime
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Import média échoué : ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
        updateMediaPreview()
    }

    private fun supprimerMediaLocal() {
        mediaUri?.let { uri ->
            try { File(Uri.parse(uri).path ?: return).delete() } catch (_: Exception) {}
        }
    }

    private fun ext(mime: String): String = when {
        mime == "image/jpeg" -> "jpg"
        mime == "image/png"  -> "png"
        mime == "image/webp" -> "webp"
        mime == "audio/mpeg" -> "mp3"
        mime == "audio/mp4"  -> "m4a"
        mime == "audio/ogg"  -> "ogg"
        mime == "audio/wav"  -> "wav"
        else                 -> mime.substringAfter("/").ifEmpty { "bin" }
    }

    private fun updateMediaPreview() {
        val uri = mediaUri
        if (uri.isNullOrEmpty()) {
            binding.layoutMediaPreview.visibility = View.GONE
        } else {
            binding.layoutMediaPreview.visibility = View.VISIBLE
            binding.tvMediaNom.text = File(Uri.parse(uri).path ?: "").name
        }
    }

    /** Sous-ensemble de champsActifs limité à la caractérisation (sans les champs de counting). */
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
        Taxon.PLANTE ->
            Pair(NomenclatureCache.groupesBotaniquesConnus(), "Plantae")
        Taxon.FONGE ->
            Pair(NomenclatureCache.GROUPES_FONGE, "Fungi")
        Taxon.MOLLUSQUE, Taxon.INVERTEBRES ->
            Pair(setOf("Animalia"), "Animalia")
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
        spinner.setSelection(codes.indexOf(current).coerceAtLeast(0))
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
