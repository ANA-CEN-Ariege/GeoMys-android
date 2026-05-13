package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentDenombrementBinding
import fr.ariegenature.geonat.model.Denombrement
import fr.ariegenature.geonat.model.Taxon
import fr.ariegenature.geonat.store.NomenclatureCache

/** Édition de la liste des dénombrements d'une PendingObs en saisie multi-taxons.
 *  Permet d'ajouter, supprimer, modifier chaque counting (≥ 1 requis). */
class DenombrementFragment : Fragment() {
    private var _binding: FragmentDenombrementBinding? = null
    private val binding get() = _binding!!

    private val gson = Gson()
    private val items = mutableListOf<Denombrement>()
    private lateinit var taxon: Taxon
    private var groupe2Inpn: String = ""
    private lateinit var groupes: Set<String>
    private var regno: String = ""
    private var sexeActif: Boolean = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDenombrementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)

        val a = arguments
        taxon = runCatching { Taxon.valueOf(a?.getString("taxon") ?: "") }.getOrDefault(Taxon.OISEAU)
        groupe2Inpn = a?.getString("groupe2Inpn") ?: ""
        val espece = a?.getString("espece") ?: ""
        val denombrementsJson = a?.getString("denombrementsJson") ?: "[]"

        binding.tvEspece.text = espece

        val (g, r) = groupesEtRegno(taxon, groupe2Inpn)
        groupes = g
        regno = r
        sexeActif = sexeActifPourTaxon(taxon)

        val type = object : TypeToken<List<Denombrement>>() {}.type
        val initial: List<Denombrement> = try { gson.fromJson(denombrementsJson, type) ?: emptyList() } catch (_: Exception) { emptyList() }
        items.clear()
        items.addAll(if (initial.isEmpty()) listOf(Denombrement()) else initial)

        rafraichir()

        binding.btnAjouter.setOnClickListener {
            // Sauvegarde l'état UI courant dans items avant de re-rendre
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
                    items.removeAt(index)
                    rafraichir()
                }
            }

            val etMin = row.findViewById<TextInputEditText>(R.id.et_nombre_min)
            val etMax = row.findViewById<TextInputEditText>(R.id.et_nombre_max)
            etMin.setText(denom.nombreMin.toString())
            etMax.setText(denom.nombreMax.toString())

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

            binding.llDenombrements.addView(row)
        }
    }

    /** Relit les valeurs des champs UI vers `items` (mutation in-place). */
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
            items[i] = items[i].copy(
                nombreMin = min, nombreMax = max,
                sexe = if (sexeActif) sexe else null,
                stadeVie = stadeVie, objDenbr = objDenbr, typDenbr = typDenbr,
            )
        }
    }

    private fun sexeActifPourTaxon(taxon: Taxon): Boolean = when (taxon) {
        Taxon.OISEAU, Taxon.MAMMIFERE, Taxon.REPTILE, Taxon.BATRACIEN,
        Taxon.POISSON, Taxon.INSECTE, Taxon.MOLLUSQUE, Taxon.INVERTEBRES -> true
        Taxon.FONGE, Taxon.PLANTE -> false
    }

    private fun groupesEtRegno(taxon: Taxon, groupe2Inpn: String): Pair<Set<String>, String> = when (taxon) {
        Taxon.PLANTE -> Pair(NomenclatureCache.groupesBotaniquesConnus(), "Plantae")
        Taxon.FONGE -> Pair(NomenclatureCache.GROUPES_FONGE, "Fungi")
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
