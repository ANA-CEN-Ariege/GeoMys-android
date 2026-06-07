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

package fr.ariegenature.geomys.ui.saisie

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.store.OcctaxFieldsConfig.ChampAffichage
import fr.ariegenature.geomys.store.OcctaxFieldsConfig.OcctaxField

/**
 * Rend dynamiquement, dans un container vertical, les spinners des champs de nomenclature
 * standards Occtax (cf. [fr.ariegenature.geomys.store.OcctaxFieldsConfig]). Remplace les spinners
 * codés en dur des écrans de Caractérisation / Dénombrement : la liste des champs et leur ordre
 * viennent désormais du registre filtré par la config serveur.
 *
 * Chaque spinner est rempli via [NomenclatureCache.filtrerPourGroupes] (valeurs serveur filtrées
 * par groupe/règne), avec repli sur les valeurs du registre si la nomenclature n'est pas
 * synchronisée, et défaut serveur [NomenclatureCache.defautPour]. Logique reprise telle quelle de
 * l'ancien `setupSpinner`, centralisée une seule fois. Pattern de tag identique à
 * [AdditionalFieldsRenderer] (`R.id.tag_field_codes` pour stocker les codes sans écraser le tag
 * "input" cherché par [collecter]).
 */
object OcctaxFieldsRenderer {

    private data class FieldTag(val code: String)

    /**
     * Vide [container] et y rend un label + spinner par champ de [champs].
     * @param valeurs        valeurs courantes par mnémonique (code), pour pré-sélection.
     * @param groupes/regno  contexte taxonomique pour le filtrage des nomenclatures.
     * @param labelOverrides libellés alternatifs par code (ex. STADE_VIE → "Stade phénologique" pour les plantes).
     */
    fun rendre(
        container: LinearLayout,
        champs: List<ChampAffichage>,
        valeurs: Map<String, String>,
        groupes: Set<String>,
        regno: String,
        labelOverrides: Map<String, String> = emptyMap(),
    ) {
        container.removeAllViews()
        val ctx = container.context
        fun build(c: ChampAffichage) = buildField(
            ctx, c.champ, labelOverrides[c.champ.code] ?: c.champ.label,
            valeurs[c.champ.code] ?: "", groupes, regno, c.lectureSeule,
        )
        // Champs principaux (default=true) affichés directement ; champs « avancés » (default=false)
        // repliés derrière un toggle « Plus de champs ». Tous restent enfants du container (donc
        // collectés), seuls les avancés démarrent masqués.
        champs.filter { !it.replie }.forEach { container.addView(build(it)) }
        val avances = champs.filter { it.replie }.map { build(it).apply { visibility = View.GONE } }
        if (avances.isNotEmpty()) {
            val toggle = TextView(ctx).apply {
                text = "▾ Plus de champs"
                textSize = 13f
                setTextColor(ctx.getColor(android.R.color.holo_blue_dark))
                setPadding(0, dp(ctx, 12), 0, 0)
                background = null
                isClickable = true
                isFocusable = true
            }
            container.addView(toggle)
            avances.forEach { container.addView(it) }
            var ouvert = false
            toggle.setOnClickListener {
                ouvert = !ouvert
                avances.forEach { it.visibility = if (ouvert) View.VISIBLE else View.GONE }
                toggle.text = if (ouvert) "▴ Moins de champs" else "▾ Plus de champs"
            }
        }
    }

    /** Relit les codes sélectionnés, indexés par mnémonique. */
    fun collecter(container: LinearLayout): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i)
            val tag = view.tag as? FieldTag ?: continue
            val spinner = view.findViewWithTag<Spinner>("input") ?: continue
            val codes = spinner.getTag(R.id.tag_field_codes) as? List<*>
            result[tag.code] = codes?.getOrNull(spinner.selectedItemPosition) as? String ?: ""
        }
        return result
    }

    private fun buildField(
        ctx: Context, champ: OcctaxField, label: String, current: String,
        groupes: Set<String>, regno: String, lectureSeule: Boolean = false,
    ): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(ctx, 12) }
            tag = FieldTag(champ.code)
        }
        wrapper.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.getColor(android.R.color.darker_gray))
        })

        // Valeurs : cache serveur filtré par groupe/règne, sinon repli du registre.
        val (labelsBruts, codesBruts) = if (NomenclatureCache.estDisponible) {
            val valeurs = NomenclatureCache.filtrerPourGroupes(champ.code, groupes, regno)
            if (valeurs.isNotEmpty())
                Pair(listOf("Non renseigné") + valeurs.map { it.label },
                     listOf("") + valeurs.map { it.id.toString() })
            else Pair(champ.fallbackLabels, champ.fallbackCodes)
        } else Pair(champ.fallbackLabels, champ.fallbackCodes)
        // Déduplication par libellé : certains types GeoNature renvoient déjà une valeur
        // « Non renseigné », qui ferait doublon avec le placeholder à code vide qu'on préfixe.
        // On garde la PREMIÈRE occurrence (donc le placeholder « ne pas envoyer »).
        val (labelsDedup, codesDedup) = dedupParLabel(labelsBruts, codesBruts)
        // Tri alphabétique (français, insensible casse/accents), le placeholder à code vide
        // restant en tête.
        val (labels, codes) = trierAlphabetique(labelsDedup, codesDedup)

        val spinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            tag = "input"
        }
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setTag(R.id.tag_field_codes, codes)
        // Défaut serveur (defaults_nomenclatures_value) si pas de valeur explicite portée par l'obs.
        val codeEffectif = current.ifEmpty { NomenclatureCache.defautPour(champ.code) ?: "" }
        spinner.setSelection(codes.indexOf(codeEffectif).coerceAtLeast(0))
        // Lecture seule (locked) : non éditable, garde la valeur (défaut serveur) qui sera quand
        // même collectée et envoyée.
        spinner.isEnabled = !lectureSeule
        wrapper.addView(spinner)
        return wrapper
    }

    /** Retire les entrées dont le libellé (normalisé) est déjà présent, en gardant la première
     *  et en préservant l'alignement labels/codes. */
    private fun dedupParLabel(labels: List<String>, codes: List<String>): Pair<List<String>, List<String>> {
        val vus = HashSet<String>()
        val l = ArrayList<String>(labels.size)
        val c = ArrayList<String>(codes.size)
        for (i in labels.indices) {
            if (vus.add(labels[i].trim().lowercase())) {
                l.add(labels[i])
                c.add(codes.getOrElse(i) { "" })
            }
        }
        return Pair(l, c)
    }

    // Tri français insensible à la casse et aux accents (PRIMARY).
    private val collator = java.text.Collator.getInstance(java.util.Locale.FRENCH)
        .apply { strength = java.text.Collator.PRIMARY }

    /** Trie les entrées par libellé (alphabétique français), en gardant en tête celles à code
     *  vide (placeholder « Non renseigné »). Préserve l'alignement labels/codes. */
    private fun trierAlphabetique(labels: List<String>, codes: List<String>): Pair<List<String>, List<String>> {
        val paires = labels.indices.map { labels[it] to codes.getOrElse(it) { "" } }
        val (placeholders, autres) = paires.partition { it.second.isEmpty() }
        val triees = placeholders + autres.sortedWith(compareBy(collator) { it.first })
        return Pair(triees.map { it.first }, triees.map { it.second })
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
