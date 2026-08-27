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

import android.app.DatePickerDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import fr.ariegenature.geomys.R
import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.NomenclatureCache
import fr.ariegenature.geomys.util.AnaEval
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dialogue « Détails de la station » OccHab, partagé par l'écran de création d'habitat
 * ([OccHabHabitatFragment]) et l'écran liste ([OccHabStationFragment]). Édite les détails
 * COMMUNS à la session ([OccHabViewModel.details]) : jeu de données, observateurs, dates,
 * altitudes, surface + méthode de calcul, nature de l'objet géographique, commentaire.
 * [onValide] est appelé après validation (rafraîchir résumé / état du bouton Enregistrer).
 */
fun ouvrirDialogDetailsOccHab(
    context: Context,
    vm: OccHabViewModel,
    config: GeoNatureConfig,
    /** true à la 1ʳᵉ station : le jeu de données est obligatoire (« Valider » bloqué sans lui,
     *  dialogue non annulable, bouton « Retour » → [onAnnule]). */
    jddObligatoire: Boolean = false,
    /** true = formulaire de DÉMARRAGE de session : uniquement les champs OBLIGATOIRES de la
     *  station (JDD, observateurs, dates, nature objet géo — pré-remplis par les défauts) ;
     *  false = dialogue « Détails » complet (+ altitudes/surface/méthode/commentaire). */
    jddSeul: Boolean = false,
    onAnnule: () -> Unit = {},
    onValide: () -> Unit,
) {
    val density = context.resources.displayMetrics.density
    // Dates SANS heure : le serveur OccHab attend des dates yyyy-MM-dd (parité web NgbDate).
    val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
    val d = vm.details
    val racine = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * density).toInt(); setPadding(p, p, p, 0)
    }

    fun label(t: String) = TextView(context).apply {
        text = t
        setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun champClic(labelTxt: String, valeurInit: String, onClic: (TextView) -> Unit): TextView {
        racine.addView(label(labelTxt))
        val tv = TextView(context).apply {
            text = valeurInit
            textSize = 15f
            setPadding((4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt())
            val bg = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
            setBackgroundResource(bg.resourceId)
            isClickable = true
        }
        tv.setOnClickListener { onClic(tv) }
        racine.addView(tv)
        return tv
    }

    fun champNombre(labelTxt: String, valeurInit: String?): EditText {
        racine.addView(label(labelTxt))
        val et = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(valeurInit.orEmpty())
        }
        racine.addView(et)
        return et
    }

    fun spinner(labelTxt: String, type: String, idCourant: Int?): () -> Int? {
        racine.addView(label(labelTxt))
        val (sp, lecteur) = construireSpinnerNomenclature(context, type, idCourant)
        racine.addView(sp)
        return lecteur
    }

    // Sélecteur de DATE seule (pas d'heure) : les dates de station OccHab sont des jours
    // (yyyy-MM-dd côté serveur, NgbDate côté web). L'heure est mise à zéro pour que deux
    // sélections du même jour donnent la même valeur.
    fun choisirDate(cal: Calendar, onSet: () -> Unit) {
        DatePickerDialog(context, { _, y, m, day ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            onSet()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ── État LOCAL des sélections : rien n'est écrit dans le ViewModel avant « Valider »
    //    (sinon « Annuler » laissait passer les modifications de JDD / observateurs / dates). ──
    var pendingIdDataset: Int? = d.idDataset
    var pendingNomDataset: String? = d.nomDataset
    var pendingDateMin: Long? = d.dateMin
    var pendingDateMax: Long? = d.dateMax
    // Getter des observateurs choisis (widget partagé, cf. plus bas). Par défaut : sélection
    // actuelle inchangée (mode « JDD seul » ne montre pas le champ).
    var lireObservateurs: () -> List<Pair<Int, String>> = {
        d.observateursIds.mapIndexed { i, id -> id to (d.observateursNoms.getOrNull(i) ?: id.toString()) }
    }

    // Jeu de données (autocomplétion).
    racine.addView(label("Jeu de données *"))
    val datasets = datasetsPourOccHab(config)
    if (datasets.isEmpty()) {
        racine.addView(TextView(context).apply {
            text = "Aucun jeu de données OccHab en cache — lancez « Recharger les données »."
            setTextColor(couleurErreur(context)); textSize = 13f
            setPadding(0, (6 * density).toInt(), 0, 0)
        })
    } else {
        val til = LayoutInflater.from(context)
            .inflate(R.layout.champ_dropdown_releve, racine, false) as TextInputLayout
        til.hint = "Rechercher un jeu de données"
        val champ = til.findViewById<MaterialAutoCompleteTextView>(R.id.ac_champ_releve)
        val labels = datasets.map { it.second }
        champ.setAdapter(AdaptateurAutocomplete(context, labels))
        champ.threshold = 1
        // Hauteur bornée (5 items, défilable) pour que la liste reste entièrement visible
        // au-dessus du clavier.
        val hauteurDropdown = (minOf(datasets.size, 5) * 48 * density).toInt()
        champ.dropDownHeight = hauteurDropdown
        if (jddSeul) {
            // Dialogue JDD seul (petit, centré) : le clavier masque le bas → dropdown AU-DESSUS.
            champ.post { champ.dropDownVerticalOffset = -(champ.height + hauteurDropdown) }
        }
        // Détails (champ en haut du formulaire) : ancrage par défaut = liste juste sous le bas
        // du champ (un offset supplémentaire la faisait descendre trop bas).
        var nomChoisi: String? = pendingNomDataset ?: datasets.firstOrNull { it.first == pendingIdDataset }?.second
        nomChoisi?.let { champ.setText(it, false) }
        champ.setOnClickListener { champ.setText("", false); champ.showDropDown() }
        champ.setOnItemClickListener { _, _, pos, _ ->
            val txt = (champ.adapter.getItem(pos) as? String).orEmpty()
            datasets.firstOrNull { it.second == txt }?.let { sel ->
                pendingIdDataset = sel.first
                pendingNomDataset = sel.second
                nomChoisi = sel.second
            }
            champ.setText(nomChoisi.orEmpty(), false)
            masquerClavier(champ)
        }
        champ.setOnDismissListener { champ.setText(nomChoisi.orEmpty(), false) }
        racine.addView(til)
    }

    // Autres champs (masqués en mode « JDD seul » du démarrage de session).
    var etAltMin: EditText? = null
    var etAltMax: EditText? = null
    var etSurface: EditText? = null
    var etComment: EditText? = null
    var lireMethode: () -> Int? = { null }
    var lireNature: () -> Int? = { null }
    // Visibilité des spinners station pilotée par OCCHAB.formConfig (les EditText s'auto-testent
    // via `et != null`). Les flags sont relus à « Valider » pour PRÉSERVER la valeur d'un champ
    // masqué plutôt que de l'effacer.
    var methodeVisible = false
    var natureVisible = false
    // ── Groupe des champs REQUIS (Validators.required du web) : affiché AUSSI dans le
    //    formulaire intercalé du démarrage de session ([jddSeul]) — on capture d'un coup
    //    TOUT l'obligatoire de la station (JDD, observateurs, dates, nature objet géo),
    //    pré-rempli par les défauts (observateur connecté, date du jour, défaut serveur). ──
    // Visibilité pilotée serveur (parité web occhab-form) — défaut « visible » si la clé est
    // absente (occhabChampVisible). Clés = celles du formulaire web du module OccHab.
    val vDateMin = config.occhabChampVisible("date_min")
    val vDateMax = config.occhabChampVisible("date_max")
    // Observateurs : toujours proposés (visibilité serveur via OBSERVER_AS_TXT, pas formConfig).
    // « * » : requis côté web (form-service.ts `observers: Validators.required` en mode liste).
    racine.addView(label("Observateurs *"))
    lireObservateurs = construireSelecteurMultiObservateurs(
        context, racine, observateursPourDetailsReleve(config),
        d.observateursIds, d.observateursNoms,
    )

    // Dates début / fin — DATE seule, sans heure (yyyy-MM-dd côté serveur) ; défaut de session
    // = date du jour pour les DEUX. « * » : requises côté web (form-service.ts).
    val calDebut = Calendar.getInstance().apply { timeInMillis = pendingDateMin ?: System.currentTimeMillis() }
    val calFin = Calendar.getInstance().apply { timeInMillis = pendingDateMax ?: calDebut.timeInMillis }
    // La date affichée fait foi : figée dans pending si rien n'était encore choisi, pour que
    // la valeur montrée soit réellement celle enregistrée.
    if (vDateMin && pendingDateMin == null) pendingDateMin = calDebut.timeInMillis
    if (vDateMax && pendingDateMax == null) pendingDateMax = calFin.timeInMillis
    if (vDateMin) champClic("Date de début *", dateFmt.format(calDebut.time)) { tv ->
        choisirDate(calDebut) {
            pendingDateMin = calDebut.timeInMillis
            tv.text = dateFmt.format(calDebut.time)
        }
    }
    if (vDateMax) champClic("Date de fin *", pendingDateMax?.let { dateFmt.format(Date(it)) } ?: "—") { tv ->
        choisirDate(calFin) {
            pendingDateMax = calFin.timeInMillis
            tv.text = dateFmt.format(calFin.time)
        }
    }
    if (config.occhabChampVisible("geographic_object")) {
        // « * » : requis côté web (form-service.ts `id_nomenclature_geographic_object`).
        lireNature = spinner("Nature de l'objet géographique *", "NAT_OBJ_GEO", d.idNomObjetGeographique)
        natureVisible = true
    }

    // Switch « stations du serveur » : UNIQUEMENT au formulaire de démarrage du relevé.
    var lireChargerServeur: () -> Boolean = { d.chargerStationsServeur }
    if (jddSeul) {
        val sw = androidx.appcompat.widget.SwitchCompat(context).apply {
            text = "Afficher mes stations déjà sur GeoNature (ce jeu de données)"
            isChecked = d.chargerStationsServeur
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        racine.addView(sw)
        lireChargerServeur = { sw.isChecked }
    }

    // ── Groupe FACULTATIF : seulement dans le dialogue « Détails » complet. Altitudes et
    //    surface sont PAR STATION (auto-remplies : surface géodésique locale à la validation
    //    de la carte, altitudes MNT serveur si réseau) — lues/écrites sur vm.station, PAS sur
    //    les détails de session. ──
    // Lecteur de la section « Évaluation ANA / Natura 2000 » (bloc ANA-EVAL du plugin QGIS) —
    // null tant que la section n'est pas construite (station sans bloc = AUCUN changement d'UI).
    var lireAnaEval: (() -> String?)? = null
    // Contrôles bloquants de la section ANA-EVAL (entiers bornés) — message ou null.
    val validateursAnaEval = mutableListOf<() -> String?>()
    if (!jddSeul) {
        val st = vm.station
        if (config.occhabChampVisible("altitude_min")) etAltMin = champNombre("Altitude min (m)", st.altitudeMin?.toString())
        if (config.occhabChampVisible("altitude_max")) etAltMax = champNombre("Altitude max (m)", st.altitudeMax?.toString())
        // `area` = m² entiers côté serveur (BigInteger), pas de décimale. Auto-calculée à la
        // validation de la géométrie ; éditable ici jusqu'au prochain redessin (parité web).
        if (config.occhabChampVisible("area")) etSurface = champNombre("Surface (m²)", st.surface?.toString())
        if (config.occhabChampVisible("area_surface_calculation")) {
            lireMethode = spinner("Méthode de calcul de la surface", "METHOD_CALCUL_SURFACE", d.idNomCalculSurface)
            methodeVisible = true
        }
        if (config.occhabChampVisible("comment")) {
            racine.addView(label("Commentaire"))
            etComment = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                setText(d.comment.orEmpty())
            }
            racine.addView(etComment)
        }
        // ── Évaluation ANA / Natura 2000 (bloc ANA-EVAL du plugin QGIS occhab-qgis), sous le
        //    commentaire : UNIQUEMENT quand la STATION courante (lue du serveur) porte un bloc.
        //    Clés STATION selon champs.py ; lue/écrite sur vm.station.anaEvalJson (via AnaEval),
        //    les autres clés du bloc (statut, etc.) traversent telles quelles. ──
        st.anaEvalJson?.let { ana ->
            lireAnaEval = construireSectionAnaEval(
                context, racine, AnaEval.CHAMPS_STATION, avecPee = false, anaEvalJson = ana,
                validateurs = validateursAnaEval)
        }
    }

    val dlg = AlertDialog.Builder(context)
        .setTitle(if (jddSeul) "Informations obligatoires" else "Détails de la station")
        .setView(ScrollView(context).apply { addView(racine) })
        .setCancelable(!jddObligatoire)
        .setPositiveButton("Valider", null)
        // « Annuler » dans les deux modes (ex-« Retour ») : au démarrage obligatoire du relevé,
        // il déclenche [onAnnule] (retour à l'accueil — demande terrain 2026-08-26).
        .setNegativeButton("Annuler") { _, _ ->
            if (jddObligatoire) onAnnule()
        }
        .create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Champs REQUIS — alignés sur les Validators.required du client web OccHab
            // (form-service.ts) : id_dataset, observers, date_min, date_max,
            // id_nomenclature_geographic_object (la géométrie, requise elle aussi, est
            // garantie par la carte). Un champ MASQUÉ par formConfig n'est pas bloquant
            // (sa valeur est préservée) — anti-impasse, seule entorse assumée au web.
            fun bloquer(msg: String) =
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            if (pendingIdDataset == null) {
                bloquer("Choisissez un jeu de données")
                return@setOnClickListener
            }
            // Groupe REQUIS : présent dans les DEUX modes (formulaire de démarrage inclus).
            if (lireObservateurs().isEmpty()) {
                bloquer("Choisissez au moins un observateur")
                return@setOnClickListener
            }
            if (config.occhabChampVisible("date_min") && pendingDateMin == null) {
                bloquer("Choisissez une date de début")
                return@setOnClickListener
            }
            if (config.occhabChampVisible("date_max") && pendingDateMax == null) {
                bloquer("Choisissez une date de fin")
                return@setOnClickListener
            }
            if (natureVisible && lireNature() == null) {
                bloquer("Choisissez la nature de l'objet géographique")
                return@setOnClickListener
            }
            if (!jddSeul) {
                // Parité minMaxValidator du web : altitudes incohérentes = blocage explicite.
                val altMin = etAltMin?.text?.toString()?.trim()?.toIntOrNull()
                val altMax = etAltMax?.text?.toString()?.trim()?.toIntOrNull()
                if (altMin != null && altMax != null && altMin > altMax) {
                    bloquer("Altitude min supérieure à l'altitude max")
                    return@setOnClickListener
                }
                // ANA-EVAL : une échelle hors bornes était effacée en silence par AnaEval.nettoyer.
                validateursAnaEval.firstNotNullOfOrNull { it() }?.let { msg ->
                    bloquer(msg)
                    return@setOnClickListener
                }
            }
            // Application ATOMIQUE des sélections : rien avant, tout ici (Annuler = sans effet).
            // Garde serveur : date_max ≥ date_min (sinon payload invalide côté GeoNature).
            val fin = pendingDateMax?.let { f -> maxOf(f, pendingDateMin ?: f) }
            val obs = lireObservateurs()
            // Formulaire de démarrage → défauts des nouvelles stations ; « i » / « Détails » →
            // la station sélectionnée seulement (détails par station, décision 2026-08-27).
            vm.majDetails(demarrage = jddSeul) {
                it.idDataset = pendingIdDataset
                it.nomDataset = pendingNomDataset
                it.observateursIds = obs.map { o -> o.first }
                it.observateursNoms = obs.map { o -> o.second }
                it.dateMin = pendingDateMin
                it.dateMax = fin
                // Champ MASQUÉ par formConfig (widget non créé) → on PRÉSERVE la valeur
                // existante (jamais effacée), comme Occtax/monitoring.
                it.idNomObjetGeographique = if (natureVisible) lireNature() else d.idNomObjetGeographique
                it.chargerStationsServeur = lireChargerServeur()
            }
            if (!jddSeul) {
                vm.majDetails {
                    it.idNomCalculSurface = if (methodeVisible) lireMethode() else d.idNomCalculSurface
                    it.comment = if (etComment != null) etComment?.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } else d.comment
                }
                // Altitudes et surface : PAR STATION (auto-remplies depuis la géométrie ; une
                // édition manuelle ici tient jusqu'au prochain redessin de la géométrie).
                val st = vm.station
                vm.definirAltitudes(
                    if (etAltMin != null) etAltMin?.text?.toString()?.trim()?.toIntOrNull() else st.altitudeMin,
                    if (etAltMax != null) etAltMax?.text?.toString()?.trim()?.toIntOrNull() else st.altitudeMax,
                )
                vm.definirSurface(
                    if (etSurface != null) etSurface?.text?.toString()?.trim()?.toLongOrNull() else st.surface,
                )
                // Bloc ANA-EVAL : réécrit sur la STATION courante (champ par station, comme les
                // altitudes) — il repartira fusionné dans `comment` à l'envoi. Section absente
                // (station sans bloc) → rien n'est touché.
                lireAnaEval?.let { vm.station.anaEvalJson = it() }
            }
            // Mémorise les infos obligatoires validées AU DÉMARRAGE : le formulaire du RELEVÉ
            // SUIVANT repartira de ces valeurs (dates exclues au rechargement — cf.
            // detailsSessionParDefaut). Pas depuis le « i » d'une station (détails PAR station :
            // les observateurs d'une station importée du serveur ne sont pas un défaut de session)
            // ni le commentaire (propre à un relevé) — audit 2026-08-27.
            if (jddSeul) runCatching {
                config.occhabDetailsPrecedentsJson = com.google.gson.Gson().toJson(vm.details.copy(comment = null))
            }
            onValide()
            dlg.dismiss()
        }
    }
    dlg.show()
}

/** Spinner de nomenclature « — Non renseigné — » + valeurs du cache [NomenclatureCache].
 *  Renvoie le spinner et un lecteur de l'id_nomenclature choisi (null si non renseigné).
 *  Partagé par le dialogue Détails et l'écran de création d'habitat.
 *  Rendu ALIGNÉ sur les spinners de nomenclature Occtax (OcctaxFieldsRenderer) : item fermé
 *  `simple_spinner_item` + liste `simple_spinner_dropdown_item`, valeurs triées alphabétiquement
 *  (français, insensible casse/accents), placeholder en tête. */
internal fun construireSpinnerNomenclature(
    context: Context, type: String, idCourant: Int?,
): Pair<Spinner, () -> Int?> {
    val collator = java.text.Collator.getInstance(Locale.FRENCH).apply {
        strength = java.text.Collator.PRIMARY
    }
    val valeurs = NomenclatureCache.get(type).sortedWith(compareBy(collator) { it.label })
    val labels = mutableListOf("— Non renseigné —").apply { valeurs.forEach { add(it.label) } }
    val spinner = Spinner(context)
    val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter
    val idx = valeurs.indexOfFirst { it.id == idCourant }
    if (idx >= 0) spinner.setSelection(idx + 1)
    return spinner to {
        val pos = spinner.selectedItemPosition
        if (pos <= 0) null else valeurs.getOrNull(pos - 1)?.id
    }
}

/**
 * Section « Évaluation ANA / Natura 2000 » — édition du bloc ANA-EVAL du plugin QGIS
 * `occhab-qgis`, affichée UNIQUEMENT quand l'objet édité porte un bloc (anaEvalJson non null :
 * stations/habitats lus du serveur). Partagée par le dialogue Détails de la station
 * ([ouvrirDialogDetailsOccHab], clés STATION) et l'écran habitat ([OccHabHabitatFragment],
 * clés HABITAT + pee) — répartition des clés = champs.py du plugin ([AnaEval.CHAMPS_STATION] /
 * [AnaEval.CHAMPS_HABITAT]).
 *
 * Les clés hors périmètre du niveau (statut, recouvrement, clés de l'autre niveau…) sont
 * PRÉSERVÉES telles quelles ; `determination` et `corresp` (arbitrages botaniste) sont montrées
 * en LECTURE SEULE compacte, jamais éditées ici.
 *
 * Renvoie un LECTEUR du bloc mis à jour : JSON canonique validé par [AnaEval], ou null quand
 * plus rien n'est renseigné (le bloc disparaîtra du texte à l'envoi).
 */
internal fun construireSectionAnaEval(
    context: Context,
    racine: LinearLayout,
    champs: List<AnaEval.ChampAnaEval>,
    avecPee: Boolean,
    anaEvalJson: String,
    /** Reçoit un contrôle bloquant par entier borné (message d'erreur ou null) — l'appelant les
     *  évalue AVANT de valider ; null = pas de contrôle (valeur hors bornes effacée par nettoyer). */
    validateurs: MutableList<() -> String?>? = null,
): () -> String? {
    val density = context.resources.displayMetrics.density
    val valeurs = AnaEval.depuisJson(anaEvalJson)

    fun label(t: String) = TextView(context).apply {
        text = t
        setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    // Titre de section — même famille visuelle que les libellés, en plus marqué.
    racine.addView(TextView(context).apply {
        text = "Évaluation ANA / Natura 2000"
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (20 * density).toInt(), 0, (2 * density).toInt())
    })

    // Widgets d'édition : (clé → lecteur de la valeur saisie, null = clé effacée).
    val lecteurs = mutableListOf<Pair<String, () -> Any?>>()
    for (champ in champs) {
        racine.addView(label(champ.libelle))
        val ref = champ.referentiel
        if (ref != null) {
            // Code fermé → spinner « — Non renseigné — » + libellés du référentiel (le CODE est
            // stocké, le libellé affiché — même patron que fill_eval_combo du plugin).
            val labels = mutableListOf("— Non renseigné —").apply { ref.forEach { add(it.second) } }
            val spinner = Spinner(context)
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            val idx = ref.indexOfFirst { it.first == valeurs[champ.cle] }
            if (idx >= 0) spinner.setSelection(idx + 1)
            racine.addView(spinner)
            lecteurs.add(champ.cle to {
                val pos = spinner.selectedItemPosition
                if (pos <= 0) null else ref.getOrNull(pos - 1)?.first
            })
        } else {
            val et = EditText(context).apply {
                if (champ.entier) {
                    inputType = InputType.TYPE_CLASS_NUMBER
                } else {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                setText(valeurs[champ.cle]?.toString().orEmpty())
            }
            racine.addView(et)
            lecteurs.add(champ.cle to {
                val t = et.text?.toString()?.trim().orEmpty()
                when {
                    t.isEmpty() -> null
                    champ.entier -> t.toIntOrNull()
                    else -> t
                }
            })
            if (champ.entier) validateurs?.add {
                val t = et.text?.toString()?.trim().orEmpty()
                val n = t.toIntOrNull()
                val borne = AnaEval.borneEntier(champ.cle)
                when {
                    t.isEmpty() -> null
                    n == null -> "${champ.libelle} : nombre entier attendu"
                    borne != null && n !in borne.first..borne.second ->
                        "${champ.libelle} : entre ${borne.first} et ${borne.second}"
                    else -> null
                }
            }
        }
    }

    // PEE (plantes exotiques envahissantes) : 3 taxons au plus — clé HABITAT (champs.py).
    val champsPee = mutableListOf<EditText>()
    if (avecPee) {
        racine.addView(label("PEE — plantes exotiques envahissantes (3 taxons max)"))
        val peeActuel = (valeurs["pee"] as? List<*>).orEmpty()
        repeat(3) { i ->
            val et = EditText(context).apply {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "Taxon ${i + 1}"
                setText(peeActuel.getOrNull(i)?.toString().orEmpty())
            }
            racine.addView(et)
            champsPee.add(et)
        }
    }

    // determination / corresp : arbitrages du botaniste — AFFICHÉS mais jamais éditables
    // (préservés tels quels dans le bloc).
    fun texteLectureSeule(t: String) = TextView(context).apply {
        text = t
        textSize = 13f
        setPadding((4 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
    }
    (valeurs["determination"] as? Map<*, *>)?.let { det ->
        val ancre = (det["ancre"] as? String)?.let { cle ->
            AnaEval.TYPOLOGIES_CORRESPONDANCE.firstOrNull { it.first == cle }?.second ?: cle
        }
        racine.addView(label("Détermination hors HABREF (non modifiable)"))
        racine.addView(texteLectureSeule(
            det["nom"].toString() + (ancre?.let { " — ancre $it" } ?: "")))
    }
    (valeurs["corresp"] as? Map<*, *>)?.let { corr ->
        val lignes = corr.entries.mapNotNull { (typologie, detail) ->
            val d = detail as? Map<*, *> ?: return@mapNotNull null
            val libelle = AnaEval.TYPOLOGIES_CORRESPONDANCE
                .firstOrNull { it.first == typologie }?.second ?: typologie.toString()
            val code = d["code"]?.toString() ?: "cd_hab ${d["cd_hab"]}"
            val src = (d["src"] as? String)?.let { " ($it)" } ?: ""
            "$libelle : $code$src"
        }
        if (lignes.isNotEmpty()) {
            racine.addView(label("Correspondances arbitrées (non modifiables)"))
            racine.addView(texteLectureSeule(lignes.joinToString("\n")))
        }
    }

    return {
        // Fusion NON destructive : on repart des valeurs du bloc (clés préservées comprises) et
        // on n'y remplace que les clés éditées ici — valeur vidée = clé retirée.
        val maj = valeurs.toMutableMap()
        for ((cle, lire) in lecteurs) {
            val v = lire()
            if (v == null) maj.remove(cle) else maj[cle] = v
        }
        if (avecPee) {
            val pee = champsPee.mapNotNull { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            if (pee.isEmpty()) maj.remove("pee") else maj["pee"] = pee
        }
        AnaEval.versJson(maj)
    }
}
