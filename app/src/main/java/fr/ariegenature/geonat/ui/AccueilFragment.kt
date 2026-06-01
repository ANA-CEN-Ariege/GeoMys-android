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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentAccueilBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.store.GeoNatureConfig
import fr.ariegenature.geonat.store.OutboxMonitoring
import fr.ariegenature.geonat.store.SaisieEnAttente
import fr.ariegenature.geonat.store.SortieStore

class AccueilFragment : Fragment() {
    private var _binding: FragmentAccueilBinding? = null
    private val binding get() = _binding!!
    private val traceViewModel: TraceViewModel by activityViewModels()
    private lateinit var sortieStore: SortieStore
    private lateinit var gnConfig: GeoNatureConfig

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAccueilBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sortieStore = SortieStore(requireContext())
        gnConfig = GeoNatureConfig(requireContext())

        // Insets : fond plein écran, boutons à l'écart des barres système.
        binding.menuContainer.applyStatusBarInset()
        binding.topRightContainer.applyStatusBarInset()
        binding.accueilContent.applySystemBarInsets()

        binding.tvVersion.text = "v${versionName()}"
        binding.tvLicence.setOnClickListener { afficherLicence() }

        val prefs = requireContext().getSharedPreferences("GeoNat_prefs", android.content.Context.MODE_PRIVATE)
        binding.switchTrace.isChecked = prefs.getBoolean("enregistrer_trace", true)
        binding.switchTrace.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enregistrer_trace", isChecked).apply()
        }

        binding.btnNouveauSortie.setOnClickListener {
            findNavController().navigate(R.id.action_accueil_to_trace)
        }

        binding.btnSaisieRapide.setOnClickListener {
            findNavController().navigate(R.id.action_accueil_to_saisie_rapide)
        }

        binding.btnSuivis.setOnClickListener {
            findNavController().navigate(R.id.action_accueil_to_suivis)
        }

        binding.btnMenu.setOnClickListener { view ->
            PopupMenu(requireContext(), view).apply {
                menuInflater.inflate(R.menu.menu_accueil, menu)
                // "Mes visites" ne concerne que les saisies monitoring : on masque l'entrée
                // si l'utilisateur n'a accès à aucun protocole (CRUVED nul, ou cache vide
                // avant la première synchro). Cohérent avec la visibilité du bouton "Suivis".
                menu.findItem(R.id.menu_mes_visites)?.isVisible =
                    MonitoringApi.countModulesEnCache() > 0
                // Pastille rouge à côté des entrées dont des éléments restent à envoyer.
                menu.findItem(R.id.menu_mes_sorties)?.let {
                    it.title = titreAvecPastille(it.title, nbSaisiesEnAttente() > 0)
                }
                menu.findItem(R.id.menu_mes_visites)?.let {
                    it.title = titreAvecPastille(it.title, nbVisitesEnAttente() > 0)
                }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.menu_mes_sorties -> {
                            findNavController().navigate(R.id.action_accueil_to_sorties)
                            true
                        }
                        R.id.menu_mes_visites -> {
                            findNavController().navigate(R.id.action_accueil_to_attente)
                            true
                        }
                        R.id.menu_explorer -> {
                            findNavController().navigate(R.id.action_accueil_to_explorer)
                            true
                        }
                        R.id.menu_cache_manager -> {
                            findNavController().navigate(R.id.action_accueil_to_cache_manager)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }

        binding.btnConfig.setOnClickListener {
            findNavController().navigate(R.id.action_accueil_to_config)
        }

        traceViewModel.locationTracker.estEnCours.observe(viewLifecycleOwner) { updateButtonState() }
    }

    override fun onResume() {
        super.onResume()
        updateSortiesCount()
        updateGnIndicator()
        updateButtonState()
        updateSuivisVisibility()
        updatePastilleMenu()
    }

    /** Pastille rouge sur le bouton menu dès qu'au moins une saisie OU une visite reste à
     *  envoyer (le détail par entrée est affiché dans le menu déplié). */
    private fun updatePastilleMenu() {
        val enAttente = nbSaisiesEnAttente() > 0 || nbVisitesEnAttente() > 0
        binding.pastilleMenu.visibility = if (enAttente) View.VISIBLE else View.GONE
    }

    /** Nombre de saisies OccTax (sorties) non encore envoyées — hors sorties importées. */
    private fun nbSaisiesEnAttente(): Int =
        sortieStore.charger().count { !it.envoyeGeoNature && !it.estImportee }

    /** Nombre de saisies monitoring (visites + observations) en attente ou en erreur. */
    private fun nbVisitesEnAttente(): Int =
        OutboxMonitoring.tout().count {
            it.etat == SaisieEnAttente.Etat.PENDING || it.etat == SaisieEnAttente.Etat.ERROR
        }

    /** Renvoie [titre] suffixé d'une pastille ronde rouge "●" quand [afficher] est vrai, pour
     *  signaler des éléments en attente d'envoi sur une entrée du menu déplié. */
    private fun titreAvecPastille(titre: CharSequence?, afficher: Boolean): CharSequence {
        val base = titre ?: ""
        if (!afficher) return base
        val sb = SpannableStringBuilder(base).append("   ●")
        sb.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.rouge_pastille)),
            sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return sb
    }

    /** Affiche le bouton "Suivis" uniquement si l'utilisateur a au moins un protocole
     *  accessible (cache filtré par CRUVED, cf [MonitoringApi.countModulesEnCache]). Quand
     *  il est masqué, le spacer de poids 1 au-dessus de la zone boutons décale naturellement
     *  les 2 boutons de saisie vers le bas — le cluster bouton reste collé au bas de l'écran. */
    private fun updateSuivisVisibility() {
        binding.btnSuivis.visibility =
            if (MonitoringApi.countModulesEnCache() > 0) View.VISIBLE else View.GONE
    }

    private fun updateButtonState() {
        val enCours = traceViewModel.locationTracker.estEnCours.value == true
        binding.indicateurEnregistrement.visibility = if (enCours) View.VISIBLE else View.GONE
    }

    private fun updateSortiesCount() {
        // badge supprimé avec le bouton Mes sorties
    }

    private fun updateGnIndicator() {
        binding.indicateurGn.visibility = if (gnConfig.estConfiguree) View.VISIBLE else View.GONE
    }

    private fun versionName(): String = try {
        val pkg = requireContext().packageName
        val pm = requireContext().packageManager
        pm.getPackageInfo(pkg, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    /** Notice de licence GPLv3 (mention de garantie nulle + lien vers le texte complet),
     *  affichée au tap sur le libellé « Logiciel libre — GNU GPL v3 ». Le bouton renvoie
     *  vers le texte officiel de la licence sur gnu.org. */
    private fun afficherLicence() {
        AlertDialog.Builder(requireContext())
            .setTitle("Licence")
            .setMessage(
                "GeoNat-Android v${versionName()}\n" +
                "© 2026 ANA - CEN Ariège\n\n" +
                "Ce programme est un logiciel libre : vous pouvez le redistribuer et/ou le " +
                "modifier selon les termes de la GNU General Public License telle que publiée " +
                "par la Free Software Foundation, en version 3 ou (à votre choix) toute version " +
                "ultérieure.\n\n" +
                "Ce programme est distribué dans l'espoir qu'il sera utile, mais SANS AUCUNE " +
                "GARANTIE, sans même la garantie implicite de COMMERCIALISATION ou " +
                "d'ADÉQUATION À UN USAGE PARTICULIER. Voir la GNU General Public License pour " +
                "plus de détails."
            )
            .setPositiveButton("Texte complet") { _, _ ->
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"),
                    )
                )
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}