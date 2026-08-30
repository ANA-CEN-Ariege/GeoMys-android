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

import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.store.GeoNatureConfig
import kotlinx.coroutines.launch

/**
 * Bouton « Tout envoyer (N) » d'une liste de saisies en attente : visibilité/libellé, confirmation,
 * envoi séquentiel avec progression inline et récapitulatif — factorisé (ex-3 copies dans Mes
 * saisies / Mes stations / Mes visites, audit 2026-08-27). Le fragment fournit ses vues (null quand
 * son binding est mort), ses règles (droit CRUVED, connexion configurée), sa liste et sa fonction
 * d'envoi unitaire ; [envoiEnCours] est PARTAGÉ avec l'envoi d'une seule saisie (flèche de ligne)
 * pour qu'un seul envoi tourne à la fois.
 */
class EnvoiGroupeUi<T>(
    private val fragment: Fragment,
    private val vues: () -> Vues?,
    private val autorise: () -> Boolean,
    private val connexionConfiguree: () -> Boolean,
    private val chargerToutes: () -> List<T>,
    private val envoyables: (List<T>) -> List<T>,
    /** Envoi d'UN élément → (succès, message humanisé). Doit persister lui-même son résultat. */
    private val envoyerUne: suspend (T, GeoNatureConfig) -> Pair<Boolean, String>,
    private val rafraichir: () -> Unit,
) {
    class Vues(val bouton: Button, val progression: View, val message: TextView)

    /** Un envoi (groupé ou unitaire) est en cours : bouton masqué, nouveaux clics refusés. */
    var envoiEnCours = false

    fun majBouton(ongletEnvoi: Boolean, toutes: List<T>) {
        val v = vues() ?: return
        val nb = envoyables(toutes).size
        val visible = ongletEnvoi && autorise() && nb > 0 && !envoiEnCours
        v.bouton.visibility = if (visible) View.VISIBLE else View.GONE
        v.bouton.isEnabled = !envoiEnCours
        v.bouton.text = "Tout envoyer ($nb)"
    }

    fun confirmer() {
        val ctx = fragment.requireContext()
        if (envoiEnCours) {
            Toast.makeText(ctx, "Un envoi est déjà en cours…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!connexionConfiguree()) {
            AlertDialog.Builder(ctx)
                .setTitle("Configuration requise")
                .setMessage("La connexion GeoNature n'est pas configurée. Ouvrez la configuration (⚙️) avant d'envoyer.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val aEnvoyer = envoyables(chargerToutes())
        if (aEnvoyer.isEmpty()) return
        AlertDialog.Builder(ctx)
            .setTitle("Tout envoyer")
            .setMessage("Envoyer les ${aEnvoyer.size} saisie(s) en attente vers GeoNature ?")
            .setPositiveButton("Envoyer") { _, _ -> lancer(aEnvoyer, GeoNatureConfig(ctx)) }
            .setNegativeButton(R.string.annuler, null)
            .show()
    }

    private fun lancer(items: List<T>, config: GeoNatureConfig) {
        envoiEnCours = true
        vues()?.let {
            it.bouton.visibility = View.GONE
            it.progression.visibility = View.VISIBLE
            it.message.visibility = View.VISIBLE
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            var succes = 0
            var echecs = 0
            val messages = mutableListOf<String>()
            try {
                items.forEachIndexed { i, item ->
                    vues()?.message?.text = "Envoi ${i + 1}/${items.size} vers GeoNature…"
                    val (ok, msg) = envoyerUne(item, config)
                    if (ok) succes++ else { echecs++; messages.add(msg) }
                    if (fragment.isAdded && vues() != null) rafraichir()
                }
                if (!fragment.isAdded || vues() == null) return@launch
                val recap = buildString {
                    append("$succes saisie(s) envoyée(s)")
                    if (echecs > 0) append(", $echecs échec(s)")
                    if (messages.isNotEmpty()) { append("\n\n"); append(messages.joinToString("\n")) }
                }
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(if (echecs == 0) "Envoi" else "Erreur d'envoi")
                    .setMessage(recap)
                    .setPositiveButton("OK", null).show()
            } finally {
                envoiEnCours = false
                vues()?.let {
                    it.progression.visibility = View.GONE
                    it.message.visibility = View.GONE
                }
                if (fragment.isAdded && vues() != null) rafraichir()
            }
        }
    }
}
