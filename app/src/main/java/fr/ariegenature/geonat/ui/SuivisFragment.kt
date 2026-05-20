package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentSuivisBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.network.MonitoringModule
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.launch

/** Liste des protocoles (modules) du gn_module_monitoring de l'instance GeoNature. */
class SuivisFragment : Fragment() {
    private var _binding: FragmentSuivisBinding? = null
    private val binding get() = _binding!!
    private lateinit var gnConfig: GeoNatureConfig

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSuivisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)
        gnConfig = GeoNatureConfig(requireContext())

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        chargerModules()
    }

    private fun chargerModules() {
        binding.progressSuivis.visibility = View.VISIBLE
        binding.tvErreurSuivis.visibility = View.GONE
        binding.llModules.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val modules = try {
                MonitoringApi.chargerModules(gnConfig)
            } catch (e: Exception) {
                if (!isAdded) return@launch
                binding.progressSuivis.visibility = View.GONE
                binding.tvErreurSuivis.visibility = View.VISIBLE
                binding.tvErreurSuivis.text = "Erreur de chargement : ${e.message}"
                return@launch
            }
            if (!isAdded) return@launch
            binding.progressSuivis.visibility = View.GONE
            if (modules.isEmpty()) {
                binding.tvErreurSuivis.visibility = View.VISIBLE
                binding.tvErreurSuivis.text = "Aucun protocole disponible.\n" +
                    "Vérifie que gn_module_monitoring est installé sur le serveur " +
                    "et que tu as les droits CRUVED de lecture."
            } else {
                afficherModules(modules)
            }
        }
    }

    private fun afficherModules(modules: List<MonitoringModule>) {
        val inflater = LayoutInflater.from(requireContext())
        modules.forEach { m ->
            val row = inflater.inflate(R.layout.item_suivi_module, binding.llModules, false)
            row.findViewById<TextView>(R.id.tv_label).text = m.moduleLabel
            row.findViewById<TextView>(R.id.tv_code).text = m.moduleCode
            row.findViewById<TextView>(R.id.tv_desc).apply {
                m.moduleDesc?.let { text = it; visibility = View.VISIBLE }
            }
            // Picto : si module_picto ressemble à une URL/chemin d'image → ImageView,
            // sinon fallback emoji depuis la nomenclature FontAwesome.
            val ivPicto = row.findViewById<ImageView>(R.id.iv_picto)
            val tvPicto = row.findViewById<TextView>(R.id.tv_picto)
            val picto = m.modulePicto
            if (picto != null && ressembleAUneImage(picto)) {
                ivPicto.visibility = View.VISIBLE
                chargerImagePicto(ivPicto, urlAbsoluePicto(picto))
            } else {
                val emoji = picto?.let { pictoFaEnEmoji(it) }
                if (emoji != null) { tvPicto.text = emoji; tvPicto.visibility = View.VISIBLE }
            }
            row.findViewById<ImageButton>(R.id.btn_info).setOnClickListener {
                findNavController().navigate(
                    R.id.action_suivis_to_detail,
                    bundleOf("moduleCode" to m.moduleCode)
                )
            }
            row.findViewById<ImageButton>(R.id.btn_carte).setOnClickListener {
                findNavController().navigate(
                    R.id.action_suivis_to_carte,
                    bundleOf(
                        "moduleCode" to m.moduleCode,
                        "objectType" to "module",
                        "id" to m.idModule,
                        "titre" to m.moduleLabel,
                    )
                )
            }
            binding.llModules.addView(row)
        }
    }

    /** Vrai si la chaîne ressemble à une URL ou un chemin vers une image plutôt qu'à un
     *  identifiant FontAwesome. Heuristique simple : préfixe http(s)/protocole ou
     *  chemin commençant par `/`, ou extension d'image connue. */
    private fun ressembleAUneImage(picto: String): Boolean {
        val s = picto.trim()
        if (s.startsWith("http://", true) || s.startsWith("https://", true) ||
            s.startsWith("//") || s.startsWith("/")) return true
        val low = s.lowercase()
        return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg")
            || low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".svg")
    }

    /** Construit l'URL absolue du picto à partir de la base serveur. Si la chaîne est
     *  déjà absolue (http/https), elle est retournée telle quelle. Sinon on préfixe par
     *  l'URL du serveur GeoNature et un éventuel `/` manquant. */
    private fun urlAbsoluePicto(picto: String): String {
        val p = picto.trim()
        if (p.startsWith("http://", true) || p.startsWith("https://", true)) return p
        val base = gnConfig.urlServeur.trim().trimEnd('/')
        val rel = if (p.startsWith("/")) p else "/$p"
        return "$base$rel"
    }

    /** Charge l'image en arrière-plan dans un Bitmap et la pose sur l'ImageView. Best-effort
     *  (cache OS HttpURLConnection en place). En cas d'échec, masque l'ImageView pour ne
     *  pas laisser un trou. */
    private fun chargerImagePicto(target: ImageView, url: String) {
        target.tag = url  // détecte les recyclages de View (cas peu probable ici, on n'est
                          // pas dans un RecyclerView mais ça ne coûte rien).
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    if (conn.responseCode != 200) return@runCatching null
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (target.tag == url) {
                if (bmp != null) target.setImageBitmap(bmp)
                else target.visibility = View.GONE
            }
        }
    }

    /** Mappe un code FontAwesome déclaré dans `module_picto` vers un emoji unicode équivalent.
     *  Couvre les codes les plus utilisés en gn_module_monitoring (faune/flore/géo). Fallback :
     *  emoji "presse-papier" générique pour les codes non listés. Retourne null si pas de code. */
    private fun pictoFaEnEmoji(picto: String): String? {
        if (picto.isEmpty()) return null
        return when (picto.lowercase().removePrefix("fa-").removePrefix("fas-").removePrefix("far-")) {
            "puzzle-piece", "puzzle" -> "🧩"
            "bird", "dove", "crow", "feather" -> "🐦"
            "leaf", "seedling" -> "🌿"
            "tree" -> "🌳"
            "fish" -> "🐟"
            "bug" -> "🐛"
            "spider" -> "🕷️"
            "paw" -> "🐾"
            "frog" -> "🐸"
            "snake" -> "🐍"
            "horse" -> "🐎"
            "cow" -> "🐄"
            "dog" -> "🐕"
            "cat" -> "🐈"
            "deer" -> "🦌"
            "rabbit" -> "🐇"
            "mouse" -> "🐁"
            "flower", "fan" -> "🌸"
            "mountain" -> "⛰️"
            "water", "tint", "droplet" -> "💧"
            "map", "map-marker", "map-pin", "location-dot" -> "📍"
            "binoculars" -> "🔭"
            "camera" -> "📷"
            "ear-listen", "headphones" -> "🎧"
            "compass" -> "🧭"
            "ruler", "ruler-combined" -> "📏"
            "clipboard", "clipboard-list", "list", "list-ul" -> "📋"
            "book" -> "📖"
            "calendar", "calendar-days" -> "📅"
            "globe", "earth", "globe-europe" -> "🌍"
            "sun" -> "☀️"
            "cloud" -> "☁️"
            "user", "users" -> "👤"
            else -> "📋"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
