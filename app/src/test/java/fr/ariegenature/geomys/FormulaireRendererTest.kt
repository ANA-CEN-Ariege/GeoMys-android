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

package fr.ariegenature.geomys

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import fr.ariegenature.geomys.monitoring.form.EditableField
import fr.ariegenature.geomys.monitoring.form.FormulaireRenderer
import fr.ariegenature.geomys.monitoring.form.ViewType
import fr.ariegenature.geomys.store.TaxRefCache
import fr.ariegenature.geomys.store.TaxRefEntry
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ASSEMBLAGE du moteur de formulaire monitoring ([FormulaireRenderer]) — trou de couverture de
 * l'audit 2026-08-27 : rendu → lecture typée des valeurs (texte, décimal avec virgule, entier
 * strict), champs obligatoires manquants (mis à jour par la saisie dans la vue), règle `change`
 * appliquée via le flush synchrone, sans boucle ni écrasement d'un champ modifié par l'utilisateur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormulaireRendererTest {

    private lateinit var ctx: Context
    private lateinit var parent: LinearLayout
    private lateinit var renderer: FormulaireRenderer

    @Before
    fun setup() {
        ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_GeoMys)
        parent = LinearLayout(ctx)
        renderer = FormulaireRenderer(ctx, parent, MainScope())
    }

    /** Tous les EditText du formulaire, dans l'ordre de rendu. */
    private fun editTexts(v: View): List<EditText> = when (v) {
        is EditText -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { editTexts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun lecture_typee_des_valeurs_rendues() {
        renderer.rendre(listOf(
            EditableField("nom", ViewType.TEXT, "Nom", value = "Merle"),
            EditableField("taux", ViewType.NUMBER, "Taux", value = "12,5", decimal = true),
            EditableField("nb", ViewType.NUMBER, "Nombre", value = "7"),
            EditableField("vide", ViewType.NUMBER, "Vide"),
        ))
        val v = renderer.lireValeurs()
        assertEquals("Merle", v["nom"])
        assertEquals("virgule décimale acceptée", 12.5, v["taux"])
        assertEquals("entier reste entier", 7, v["nb"])
        assertNull(v["vide"])
    }

    @Test
    fun widget_entier_ne_renvoie_jamais_de_decimal() {
        renderer.rendre(listOf(EditableField("nb", ViewType.NUMBER, "Nombre", value = "3")))
        editTexts(parent).single().setText("4.7") // collé / posé par une règle
        renderer.flushChangementsEnAttente()
        assertEquals("tronqué en entier (audit lot C)", 4, renderer.lireValeurs()["nb"])
    }

    @Test
    fun champs_obligatoires_manquants_suivent_la_saisie() {
        renderer.rendre(listOf(
            EditableField("nom", ViewType.TEXT, "Nom", obligatoire = true),
            EditableField("nb", ViewType.NUMBER, "Nombre", obligatoire = true, value = "1"),
        ))
        val manquants = renderer.champsObligatoiresManquants()
        assertEquals(1, manquants.size)
        assertTrue("le champ vide est signalé : $manquants", manquants.single().contains("Nom", ignoreCase = true) || manquants.single() == "nom")
        editTexts(parent).first().setText("Grive")
        renderer.flushChangementsEnAttente()
        assertTrue(renderer.champsObligatoiresManquants().isEmpty())
    }

    @Test
    fun regle_change_appliquee_au_flush_sans_ecraser_un_champ_modifie_par_l_utilisateur() {
        renderer.rendre(listOf(
            EditableField("presence", ViewType.TEXT, "Présence", value = "Oui"),
            EditableField("count_min", ViewType.NUMBER, "Min", value = "5"),
            EditableField("count_max", ViewType.NUMBER, "Max", value = "9"),
        ))
        // Garde `!dirty` des scripts gn_module_monitoring : le patch ne s'applique que tant que
        // l'utilisateur n'a pas touché count_min lui-même.
        renderer.setReglesChange(listOf(
            "({objForm, meta}) => {",
            "if (objForm.value.presence === 'Non' && !objForm.controls.count_min.dirty) {",
            "objForm.patchValue({count_min: 0, count_max: 0})",
            "}",
            "}",
        ))
        val (presence, min, _) = editTexts(parent)
        presence.setText("Non")
        renderer.flushChangementsEnAttente()
        shadowOf(Looper.getMainLooper()).idle()
        val v = renderer.lireValeurs()
        assertEquals("patch appliqué (règle change)", 0, v["count_min"])
        assertEquals(0, v["count_max"])
        // L'utilisateur reprend la main sur count_min → dirty : un nouveau déclenchement de la
        // règle ne doit pas re-écraser sa valeur (garde !dirty), et ne doit pas boucler.
        min.setText("3")
        renderer.flushChangementsEnAttente()
        presence.setText("Non ")
        renderer.flushChangementsEnAttente()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("champ modifié par l'utilisateur conservé", 3, renderer.lireValeurs()["count_min"])
    }

    // ── Mode complétion : barre verticale rouge sur les obligatoires VIDES ─────────────
    // Demande terrain 2026-09-03 : quand on ouvre un formulaire pour finir les champs
    // manquants, ils doivent se repérer d'un coup d'œil.

    /** Le bloc (libellé + champ) du n-ième champ rendu. */
    private fun blocChamp(index: Int): View = parent.getChildAt(index)

    /** Rouge franc attendu pour la barre — PAS la couleur d'erreur du thème (rose pâle en
     *  thème sombre). Le test verrouille donc aussi la teinte. */
    private val rougeAttendu = 0xFFD32F2F.toInt()

    /** Retrouve la barre du champ dans [bloc] : la seule View dont le fond est [rougeAttendu].
     *  Recherche par la COULEUR plutôt que par la position, pour ne pas casser si la structure
     *  du bloc évolue. */
    private fun barreDu(bloc: View): View? {
        val fond = bloc.background
        if (bloc !is ViewGroup && fond is android.graphics.drawable.ColorDrawable &&
            fond.color == rougeAttendu
        ) return bloc
        if (bloc is ViewGroup) {
            for (i in 0 until bloc.childCount) barreDu(bloc.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** La barre existe ET est visible. */
    private fun aUneBarre(bloc: View): Boolean = barreDu(bloc)?.visibility == View.VISIBLE

    @Test
    fun barre_rouge_seulement_sur_les_obligatoires_vides() {
        renderer.rendre(listOf(
            EditableField("rempli", ViewType.TEXT, "Rempli", value = "x", obligatoire = true),
            EditableField("vide", ViewType.TEXT, "Vide", obligatoire = true),
            EditableField("libre", ViewType.TEXT, "Facultatif"),
        ))
        renderer.marquerObligatoiresManquants(true)
        assertTrue("obligatoire VIDE → barre", aUneBarre(blocChamp(1)))
        assertFalse("obligatoire déjà rempli → pas de barre", aUneBarre(blocChamp(0)))
        assertFalse("champ facultatif → pas de barre", aUneBarre(blocChamp(2)))
    }

    @Test
    fun la_barre_disparait_quand_le_champ_est_rempli() {
        renderer.rendre(listOf(EditableField("vide", ViewType.TEXT, "Vide", obligatoire = true)))
        renderer.marquerObligatoiresManquants(true)
        assertTrue(aUneBarre(blocChamp(0)))
        editTexts(parent).single().setText("enfin saisi")
        renderer.flushChangementsEnAttente()
        renderer.marquerObligatoiresManquants(true)
        assertFalse("champ rempli → barre retirée", aUneBarre(blocChamp(0)))
    }

    @Test
    fun hors_mode_completion_aucune_barre() {
        renderer.rendre(listOf(EditableField("vide", ViewType.TEXT, "Vide", obligatoire = true)))
        renderer.marquerObligatoiresManquants(false)
        assertFalse(aUneBarre(blocChamp(0)))
        // La barre existe dans la hiérarchie mais reste GONE : elle ne prend aucune place.
        assertEquals(View.GONE, barreDu(blocChamp(0))?.visibility)
    }

    @Test
    fun la_barre_est_accolee_au_champ_pas_au_libelle() {
        // Demande terrain : la barre appartient au CHAMP (elle en a la hauteur), le libellé
        // n'est pas souligné. Elle doit donc être SŒUR de l'éditable, pas parente du bloc.
        renderer.rendre(listOf(EditableField("vide", ViewType.TEXT, "Vide", obligatoire = true)))
        renderer.marquerObligatoiresManquants(true)
        val barre = barreDu(blocChamp(0))
        assertTrue("barre absente", barre != null)
        val rangee = barre!!.parent as ViewGroup
        val editable = editTexts(parent).single()
        // La rangée ne contient QUE la barre et le champ : le libellé est ailleurs (dans le
        // bloc, au-dessus) — c'est ce qui garantit qu'il n'est pas souligné par la barre.
        assertEquals("la rangée ne doit contenir que la barre et le champ", 2, rangee.childCount)
        assertTrue("barre en premier", rangee.getChildAt(0) === barre)
        assertTrue("champ juste après la barre", rangee.getChildAt(1) === editable)
    }

    // ── Champs masqués et envoi (audit 2026-09-14, R4-M4 / R4-M3) ──

    @Test
    fun un_champ_masque_conserve_sa_valeur_mais_ne_part_PAS_au_serveur() {
        // Un protocole qui masque « nombre de nids » quand « espèce observée = non » transmettait
        // quand même le nombre saisi avant la bascule : donnée fausse en base, sans aucun signal.
        // Le client mobile officiel PnX-SI, lui, n'envoie pas les champs masqués.
        renderer.rendre(listOf(
            EditableField("vue", ViewType.TEXT, "Espèce observée", value = "non"),
            EditableField("nids", ViewType.NUMBER, "Nombre de nids", value = "3",
                hiddenExpr = "\${vue} === 'non'"),
        ))

        assertEquals(
            "la valeur reste lisible : masquer n'est pas effacer, l'utilisateur la retrouve si la " +
                "condition redevient vraie",
            3, renderer.lireValeurs()["nids"],
        )
        assertTrue(
            "mais elle ne doit pas partir au serveur",
            !renderer.lireValeursPourEnvoi().containsKey("nids"),
        )
        assertEquals("les champs visibles partent normalement", "non", renderer.lireValeursPourEnvoi()["vue"])
    }

    @Test
    fun un_champ_masque_mais_REQUIS_part_quand_meme() {
        // Exception voulue, alignée sur le client officiel : le serveur refuserait la saisie
        // autrement, et une incohérence du schéma ne doit pas faire perdre une visite de terrain.
        renderer.rendre(listOf(
            EditableField("vue", ViewType.TEXT, "Espèce observée", value = "non"),
            EditableField("nids", ViewType.NUMBER, "Nombre de nids", value = "3",
                obligatoire = true, hiddenExpr = "\${vue} === 'non'"),
        ))
        assertEquals(3, renderer.lireValeursPourEnvoi()["nids"])
    }

    @Test
    fun setReglesChange_recalcule_l_etat_du_formulaire() {
        // appliquerChangeRules ne fait que POSER des valeurs. Sans recalcul derrière, la visibilité
        // conditionnelle et l'état du bouton restaient ceux d'AVANT la règle — et le rattrapage par
        // listener est neutralisé pendant l'application (garde appliquantChange).
        var notifie = false
        renderer.setOnChangement { notifie = true }
        renderer.rendre(listOf(
            EditableField("mode", ViewType.TEXT, "Mode", value = "auto"),
            EditableField("calcule", ViewType.TEXT, "Calculé"),
        ))
        notifie = false

        renderer.setReglesChange(listOf("calcule = 'rempli par la règle'"))

        assertTrue("le formulaire doit être notifié du recalcul", notifie)
    }

    /**
     * SITE D'APPEL du correctif C1 (audit 2026-09-17) : le champ espèce d'un protocole à liste
     * taxonomique ne doit plus poser le cd_nom d'un taxon HORS liste. Cas réel : sur STERF
     * (liste 109), « Souci » est *Colias crocea* (641941) — la clé du cache appartient pourtant à
     * *Calendula* (190178), la plante, qui partait à GeoNature comme observation de papillon.
     * Testé ICI, sur la vue rendue, et pas seulement sur TaxRefCache : c'est le renderer qui
     * décide ce que `lireValeurs` renverra à l'envoi.
     */
    @Test
    fun champ_taxon_d_un_protocole_ne_resout_pas_hors_de_sa_liste() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        TaxRefCache.remplacerTout(mapOf(
            TaxRefCache.normaliser("Souci") to TaxRefEntry(190178, "Calendula", "Souci"),
            TaxRefCache.normaliser("Colias crocea") to TaxRefEntry(641941, "Colias crocea", null),
        ))
        TaxRefCache.ajouterVerns(mapOf(190178 to listOf("Souci"), 641941 to listOf("Souci")))
        TaxRefCache.ajouterListesParCdNom(mapOf(641941 to listOf(100, 109), 190178 to listOf(100)))

        renderer.rendre(listOf(
            EditableField("esp", ViewType.TAXON, "Espèce", idListeTaxonomieRestreinte = 109),
            EditableField("esp_libre", ViewType.TAXON, "Espèce (sans liste)"),
        ))
        val champs = editTexts(parent)
        champs[0].setText("Souci")
        champs[1].setText("Souci")

        val v = renderer.lireValeurs()
        assertEquals("le protocole impose sa liste : c'est le papillon", 641941, v["esp"])
        assertEquals("sans liste, la résolution globale est inchangée", 190178, v["esp_libre"])
    }

    /** Un nom étranger au protocole n'est pas silencieusement remplacé par un taxon d'ailleurs :
     *  aucune valeur n'est posée (le champ signale « non reconnue » au blur). */
    @Test
    fun champ_taxon_refuse_un_nom_etranger_au_protocole() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        TaxRefCache.remplacerTout(mapOf(
            TaxRefCache.normaliser("Genette") to TaxRefEntry(60831, "Genetta genetta", "Genette"),
            TaxRefCache.normaliser("Colias crocea") to TaxRefEntry(641941, "Colias crocea", null),
        ))
        TaxRefCache.ajouterVerns(mapOf(60831 to listOf("Genette"), 641941 to listOf("Souci")))
        TaxRefCache.ajouterListesParCdNom(mapOf(641941 to listOf(100, 109), 60831 to listOf(100)))

        renderer.rendre(listOf(
            EditableField("esp", ViewType.TAXON, "Espèce", idListeTaxonomieRestreinte = 109),
        ))
        editTexts(parent)[0].setText("Genette")
        assertNull(renderer.lireValeurs()["esp"])
    }

    /** Un texte d'espèce non reconnu n'est plus ignoré en silence : il rend le formulaire
     *  INVALIDE, ce qui grise le bouton d'enregistrement (NouvelleVisiteFragment le lit via
     *  [FormulaireRenderer.champsInvalides]). Règle produit 2026-09-17. */
    @Test
    fun un_nom_d_espece_non_reconnu_rend_le_formulaire_invalide() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        TaxRefCache.remplacerTout(mapOf(
            TaxRefCache.normaliser("Colias crocea") to TaxRefEntry(641941, "Colias crocea", null),
        ))
        TaxRefCache.ajouterVerns(mapOf(641941 to listOf("Souci")))
        TaxRefCache.ajouterListesParCdNom(mapOf(641941 to listOf(100, 109)))

        renderer.rendre(listOf(
            EditableField("esp", ViewType.TAXON, "Espèce", idListeTaxonomieRestreinte = 109),
        ))
        val champ = editTexts(parent)[0]

        champ.setText("Bidule inexistant")
        assertTrue("un nom inconnu doit bloquer", renderer.champsInvalides().contains("esp"))

        champ.setText("Souci")
        assertFalse("un nom du protocole ne bloque pas", renderer.champsInvalides().contains("esp"))

        champ.setText("")
        assertFalse("un champ vide ne bloque pas (c'est `obligatoire` qui s'en charge)",
            renderer.champsInvalides().contains("esp"))
    }

    /** Liste du protocole absente du cache : aucune proposition, et le moindre texte saisi rend
     *  le formulaire invalide (le champ affiche « Pas de données — rechargez les données »).
     *  Avant, l'écran repliait sur TOUTES les espèces du cache et acceptait n'importe quel
     *  taxon, hors protocole compris. */
    @Test
    fun liste_du_protocole_absente_du_cache_rien_n_est_propose_ni_accepte() {
        TaxRefCache.init(ApplicationProvider.getApplicationContext())
        TaxRefCache.vider()
        TaxRefCache.remplacerTout(mapOf(
            TaxRefCache.normaliser("Colias crocea") to TaxRefEntry(641941, "Colias crocea", null),
            // La clé « souci » appartient à la plante : sans garde, le repli global la poserait.
            TaxRefCache.normaliser("Souci") to TaxRefEntry(190178, "Calendula", "Souci"),
        ))
        TaxRefCache.ajouterVerns(mapOf(641941 to listOf("Souci"), 190178 to listOf("Souci")))
        // Les taxons sont en cache, mais AUCUNE appartenance à la liste 109 n'a été synchronisée.
        TaxRefCache.ajouterListesParCdNom(mapOf(641941 to listOf(100), 190178 to listOf(100)))

        renderer.rendre(listOf(
            EditableField("esp", ViewType.TAXON, "Espèce", idListeTaxonomieRestreinte = 109),
        ))
        val champ = editTexts(parent)[0]
        champ.setText("Souci")
        assertNull("aucun cd_nom ne doit être posé", renderer.lireValeurs()["esp"])
        assertTrue("et le formulaire est invalide", renderer.champsInvalides().contains("esp"))
    }
}
