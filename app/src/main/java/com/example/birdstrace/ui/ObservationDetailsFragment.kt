package com.example.birdstrace.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.birdstrace.databinding.FragmentObservationDetailsBinding
import com.example.birdstrace.model.Taxon
import com.example.birdstrace.store.NomenclatureCache
import com.example.birdstrace.store.NomValeur

class ObservationDetailsFragment : Fragment() {
    private var _binding: FragmentObservationDetailsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObservationDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val taxon         = runCatching { Taxon.valueOf(arguments?.getString("taxon") ?: "") }.getOrDefault(Taxon.OISEAU)
        val groupe2Inpn   = arguments?.getString("groupe2Inpn") ?: ""
        val notes         = arguments?.getString("notes") ?: ""
        val sexe          = arguments?.getString("sexe") ?: ""
        val stadeVie      = arguments?.getString("stadeVie") ?: ""
        val techniqueObs  = arguments?.getString("techniqueObs") ?: ""
        val statutBio     = arguments?.getString("statutBio") ?: ""
        val etaBio        = arguments?.getString("etaBio") ?: ""
        val preuveExist   = arguments?.getString("preuveExist") ?: ""
        val objDenbr      = arguments?.getString("objDenbr") ?: ""
        val typDenbr      = arguments?.getString("typDenbr") ?: ""
        val comportement  = arguments?.getString("comportement") ?: ""
        val methDetermin  = arguments?.getString("methDetermin") ?: ""
        val determinateur = arguments?.getString("determinateur") ?: ""
        val cdNomManuel   = arguments?.getString("cdNomManuel") ?: ""
        val taxrefNonTrouve = arguments?.getBoolean("taxrefNonTrouve") ?: false
        val lat = arguments?.getDouble("latitude") ?: 0.0
        val lon = arguments?.getDouble("longitude") ?: 0.0

        binding.etNotes.setText(notes)
        binding.etDeterminateur.setText(determinateur)
        binding.tvCoordonnees.text = "%.5f, %.5f".format(lat, lon)

        // Champs actifs selon le groupe taxonomique (identique à l'app iOS)
        val champs = champsActifs(taxon)
        binding.layoutSexe.visibility         = if ("SEXE"             in champs) View.VISIBLE else View.GONE
        binding.layoutStatutBio.visibility    = if ("STATUT_BIO"       in champs) View.VISIBLE else View.GONE
        binding.layoutEtaBio.visibility       = if ("ETA_BIO"          in champs) View.VISIBLE else View.GONE
        binding.layoutComportement.visibility = if ("OCC_COMPORTEMENT" in champs) View.VISIBLE else View.GONE

        // Stade de vie → Stade phénologique pour les Plantes
        binding.tvStadeVieLabel.text = if (taxon == Taxon.PLANTE) "Stade phénologique" else "Stade de vie"

        // Groupes de filtrage + regno selon le taxon — même logique que l'app iOS
        val (groupes, regno) = groupesEtRegno(taxon, groupe2Inpn)

        setupSpinners(champs, groupes, regno, sexe, stadeVie, techniqueObs, statutBio, etaBio,
                      preuveExist, objDenbr, typDenbr, comportement, methDetermin)

        if (taxrefNonTrouve) {
            binding.sectionCdNom.visibility = View.VISIBLE
            binding.etCdNom.setText(cdNomManuel)
        } else {
            binding.sectionCdNom.visibility = View.GONE
        }

        binding.btnOk.setOnClickListener {
            val sv = findNavController().previousBackStackEntry?.savedStateHandle
            sv?.set("notes",         binding.etNotes.text.toString())
            sv?.set("sexe",          if ("SEXE"             in champs) selectedCode(binding.spinnerSexe)         else "")
            sv?.set("stadeVie",      selectedCode(binding.spinnerStadeVie))
            sv?.set("techniqueObs",  selectedCode(binding.spinnerTechnique))
            sv?.set("statutBio",     if ("STATUT_BIO"       in champs) selectedCode(binding.spinnerStatutBio)    else "")
            sv?.set("etaBio",        if ("ETA_BIO"          in champs) selectedCode(binding.spinnerEtaBio)       else "")
            sv?.set("preuveExist",   selectedCode(binding.spinnerPreuveExist))
            sv?.set("objDenbr",      selectedCode(binding.spinnerObjDenbr))
            sv?.set("typDenbr",      selectedCode(binding.spinnerTypDenbr))
            sv?.set("comportement",  if ("OCC_COMPORTEMENT" in champs) selectedCode(binding.spinnerComportement) else "")
            sv?.set("methDetermin",  selectedCode(binding.spinnerMethDetermin))
            sv?.set("determinateur", binding.etDeterminateur.text.toString())
            sv?.set("cdNomManuel",   binding.etCdNom.text.toString())
            findNavController().navigateUp()
        }
    }

    // Champs actifs par groupe taxonomique — même logique que l'app iOS
    private fun champsActifs(taxon: Taxon): Set<String> = when (taxon) {
        Taxon.OISEAU    -> setOf("METH_OBS","SEXE","STADE_VIE","STATUT_BIO","ETA_BIO",
                                 "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","OCC_COMPORTEMENT","METH_DETERMIN")
        Taxon.MAMMIFERE -> setOf("METH_OBS","SEXE","STADE_VIE","ETA_BIO",
                                 "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","OCC_COMPORTEMENT","METH_DETERMIN")
        Taxon.REPTILE   -> setOf("METH_OBS","SEXE","STADE_VIE","ETA_BIO",
                                 "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","METH_DETERMIN")
        Taxon.BATRACIEN -> setOf("METH_OBS","SEXE","STADE_VIE","ETA_BIO",
                                 "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","METH_DETERMIN")
        Taxon.POISSON     -> setOf("METH_OBS","SEXE","STADE_VIE","ETA_BIO",
                                   "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","METH_DETERMIN")
        Taxon.INSECTE     -> setOf("METH_OBS","SEXE","STADE_VIE","ETA_BIO",
                                   "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","METH_DETERMIN")
        Taxon.FONGE       -> setOf("METH_OBS","PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","METH_DETERMIN")
        Taxon.INVERTEBRES -> setOf("METH_OBS","SEXE","STADE_VIE","ETA_BIO",
                                   "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","METH_DETERMIN")
        Taxon.PLANTE      -> setOf("METH_OBS","STADE_VIE",
                                   "PREUVE_EXIST","OBJ_DENBR","TYP_DENBR","METH_DETERMIN")
    }

    // Groupes de filtrage + regno par taxon — même logique que l'app iOS.
    // Pour Plantes : union des groupes botaniques présents dans le cache TaxRef.
    // Pour les autres : groupe exact depuis les arguments (ou fallback).
    private fun groupesEtRegno(taxon: Taxon, groupe2Inpn: String): Pair<Set<String>, String> = when (taxon) {
        Taxon.PLANTE ->
            Pair(NomenclatureCache.groupesBotaniquesConnus(), "Plantae")
        Taxon.FONGE ->
            Pair(NomenclatureCache.GROUPES_FONGE, "Fungi")
        Taxon.INVERTEBRES ->
            // Pas de group2 dédié : on cible le règne Animalia avec un groupe fictif
            // pour que filtrerPourGroupes inclue les entrées "Animalia, all"
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

    private fun setupSpinners(
        champs: Set<String>,
        groupes: Set<String>,
        regno: String,
        sexe: String, stadeVie: String, techniqueObs: String,
        statutBio: String, etaBio: String, preuveExist: String,
        objDenbr: String, typDenbr: String, comportement: String, methDetermin: String
    ) {
        val useCache = NomenclatureCache.estDisponible

        fun sc(type: String, current: String,
               fallbackLabels: List<String>, fallbackCodes: List<String>): Unit {
            if (useCache) {
                val valeurs = NomenclatureCache.filtrerPourGroupes(type, groupes, regno)
                if (valeurs.isNotEmpty()) {
                    val labels = listOf("Non renseigné") + valeurs.map { it.label }
                    val codes  = listOf("") + valeurs.map { it.id.toString() }
                    when (type) {
                        "SEXE"             -> spinnerStatique(binding.spinnerSexe,         labels, codes, current)
                        "STADE_VIE"        -> spinnerStatique(binding.spinnerStadeVie,     labels, codes, current)
                        "METH_OBS"         -> spinnerStatique(binding.spinnerTechnique,    labels, codes, current)
                        "STATUT_BIO"       -> spinnerStatique(binding.spinnerStatutBio,    labels, codes, current)
                        "ETA_BIO"          -> spinnerStatique(binding.spinnerEtaBio,       labels, codes, current)
                        "PREUVE_EXIST"     -> spinnerStatique(binding.spinnerPreuveExist,  labels, codes, current)
                        "OBJ_DENBR"        -> spinnerStatique(binding.spinnerObjDenbr,     labels, codes, current)
                        "TYP_DENBR"        -> spinnerStatique(binding.spinnerTypDenbr,     labels, codes, current)
                        "OCC_COMPORTEMENT" -> spinnerStatique(binding.spinnerComportement, labels, codes, current)
                        "METH_DETERMIN"    -> spinnerStatique(binding.spinnerMethDetermin, labels, codes, current)
                    }
                    return
                }
            }
            // Fallback valeurs statiques
            when (type) {
                "SEXE"             -> spinnerStatique(binding.spinnerSexe,         fallbackLabels, fallbackCodes, current)
                "STADE_VIE"        -> spinnerStatique(binding.spinnerStadeVie,     fallbackLabels, fallbackCodes, current)
                "METH_OBS"         -> spinnerStatique(binding.spinnerTechnique,    fallbackLabels, fallbackCodes, current)
                "STATUT_BIO"       -> spinnerStatique(binding.spinnerStatutBio,    fallbackLabels, fallbackCodes, current)
                "ETA_BIO"          -> spinnerStatique(binding.spinnerEtaBio,       fallbackLabels, fallbackCodes, current)
                "PREUVE_EXIST"     -> spinnerStatique(binding.spinnerPreuveExist,  fallbackLabels, fallbackCodes, current)
                "OBJ_DENBR"        -> spinnerStatique(binding.spinnerObjDenbr,     fallbackLabels, fallbackCodes, current)
                "TYP_DENBR"        -> spinnerStatique(binding.spinnerTypDenbr,     fallbackLabels, fallbackCodes, current)
                "OCC_COMPORTEMENT" -> spinnerStatique(binding.spinnerComportement, fallbackLabels, fallbackCodes, current)
                "METH_DETERMIN"    -> spinnerStatique(binding.spinnerMethDetermin, fallbackLabels, fallbackCodes, current)
            }
        }

        if ("SEXE" in champs)
            sc("SEXE", sexe,
                listOf("Non renseigné","Mâle","Femelle","Indéterminé"),
                listOf("","1","2","5"))

        sc("STADE_VIE", stadeVie,
            listOf("Non renseigné","Adulte","Juvénile","Immature"),
            listOf("","2","3","4"))

        sc("METH_OBS", techniqueObs,
            listOf("Non renseignée","Vu","Entendu","Vu et entendu","Chant","Indices de présence"),
            listOf("","0","1","2","4","5"))

        if ("STATUT_BIO" in champs)
            sc("STATUT_BIO", statutBio,
                listOf("Non renseigné","Reproduction","Pas de reproduction","Hivernation","Estivation","Non déterminé","Inconnu"),
                listOf("","1","2","3","4","5","6"))

        if ("ETA_BIO" in champs)
            sc("ETA_BIO", etaBio,
                listOf("Non renseigné","Vivant","Mort","Signe d'activité"),
                listOf("","1","2","3"))

        sc("PREUVE_EXIST", preuveExist,
            listOf("Non renseignée","Non","Oui","Non acquise","Inconnu"),
            listOf("","0","1","2","3"))

        sc("OBJ_DENBR", objDenbr,
            listOf("Non renseigné","Individu","Couple","Nid","Famille","Groupe"),
            listOf("","1","2","3","4","5"))

        sc("TYP_DENBR", typDenbr,
            listOf("Non renseigné","Exact","Estimé","Minimum","Maximum"),
            listOf("","1","2","3","4"))

        if ("OCC_COMPORTEMENT" in champs)
            sc("OCC_COMPORTEMENT", comportement,
                listOf("Non renseigné","Chant","Chasse / Alimentation","Repos","Déplacement",
                    "Passage en vol","Migration","Halte migratoire","Hivernage",
                    "Nourrissage des jeunes","Territorial","Accouplement",
                    "Nidification possible","Nidification probable","Nidification certaine","Inconnu"),
                listOf("","1","2","3","4","5","6","7","8","9","10","11","12","13","14","15"))

        sc("METH_DETERMIN", methDetermin,
            listOf("Non renseignée","Visuel à distance","Auditif direct","Photo ou vidéo",
                "Auditif avec transformation électronique","Individu en main","Autre méthode"),
            listOf("","1","2","3","4","5","6"))
    }

    private fun spinnerStatique(spinner: android.widget.Spinner, labels: List<String>, codes: List<String>, current: String) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.tag = codes
        spinner.setSelection(codes.indexOf(current).coerceAtLeast(0))
    }

    private fun selectedCode(spinner: android.widget.Spinner): String {
        val codes = spinner.tag as? List<*> ?: return ""
        return codes.getOrNull(spinner.selectedItemPosition) as? String ?: ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
