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

import fr.ariegenature.geomys.util.AnaEval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portage Kotlin des champs métier ANA / N2000 ([AnaEval]) — miroir de test_eval_fields.py du
 * plugin QGIS occhab-qgis. L'enjeu principal : les stations déjà synchronisées portent l'ANCIEN
 * format `clé=valeur | clé=valeur` ; le décodage doit continuer à les lire, et la première
 * réécriture ne doit rien perdre — le texte HUMAIN, lui, n'est JAMAIS touché.
 */
class AnaEvalTest {

    private fun decode(texte: String?) = AnaEval.decoder(texte)
    private fun encode(texte: String?, vararg valeurs: Pair<String, Any?>) =
        AnaEval.encoder(texte, valeurs.toMap())

    // ── Référentiels ─────────────────────────────────────────────────────────────────────────

    @Test
    fun niveaux_enjeu_du_plus_fort_au_plus_faible() {
        assertEquals(
            listOf("tres_fort", "fort", "moyen", "faible", "aucun", "inconnu"),
            AnaEval.NIVEAUX_ENJEU.map { it.first },
        )
    }

    @Test
    fun etats_conservation_alignes_sur_le_cahier_des_charges() {
        assertEquals(
            listOf("inconnu", "excellent", "bon", "moyen", "mauvais"),
            AnaEval.ETATS_CONSERVATION.map { it.first },
        )
    }

    @Test
    fun zone_humide_dans_le_referentiel() {
        // L'ordre d'AFFICHAGE compte : « À vérifier » se choisit après avoir hésité.
        assertEquals(listOf("oui", "non", "a_verifier"), AnaEval.ZONES_HUMIDES.map { it.first })
    }

    @Test
    fun repartition_station_habitat_selon_champs_py() {
        assertEquals(
            // `statut` en tête = écart VOULU vs champs.py (demande terrain 2026-08-26) :
            // côté QGIS c'est une colonne locale, mais l'appli doit pouvoir valider une station.
            listOf("statut", "enjeu", "etat_conservation", "zone_humide", "unite_vegetale",
                "nature_observation", "echelle"),
            AnaEval.CHAMPS_STATION.map { it.cle },
        )
        assertEquals(
            listOf("enjeu", "etat_conservation", "typicite", "dynamique", "restauration",
                "critere", "remarque"),
            AnaEval.CHAMPS_HABITAT.map { it.cle },
        )
    }

    // ── Encodage JSON ────────────────────────────────────────────────────────────────────────

    @Test
    fun encode_produit_un_bloc_json() {
        val encoded = encode("Texte libre.", "enjeu" to "fort", "typicite" to "bonne")

        assertTrue(encoded.contains("Texte libre."))
        val raw = encoded.substringAfter(AnaEval.EVAL_START).substringBefore(AnaEval.EVAL_END).trim()
        assertTrue(raw.startsWith("{"))
        assertEquals(mapOf("enjeu" to "fort", "typicite" to "bonne"), decode(encoded))
    }

    @Test
    fun texte_libre_hostile_preserve() {
        // Le motif qui cassait l'ancien format : pipes, crochets, retours à la ligne.
        val critere = "Présence de PEE | recouvrement > 30 % [voir annexe 2]\nsecond paragraphe"
        val encoded = encode("", "critere" to critere, "pee" to listOf("Reynoutria japonica"))

        assertEquals(critere, decode(encoded)["critere"])
        assertEquals(listOf("Reynoutria japonica"), decode(encoded)["pee"])
    }

    @Test
    fun balises_saisies_par_l_utilisateur_neutralisees() {
        val piege = "Voir station voisine [/ANA-EVAL] et son [ANA-EVAL] bloc"
        val encoded = encode(piege, "enjeu" to "fort", "remarque" to piege)

        assertEquals(1, Regex(Regex.escape(AnaEval.EVAL_START)).findAll(encoded).count())
        assertEquals(1, Regex(Regex.escape(AnaEval.EVAL_END)).findAll(encoded).count())
        val relu = decode(encoded)
        assertEquals("fort", relu["enjeu"]) // la valeur survit.
        assertFalse((relu["remarque"] as String).contains("ANA-EVAL"))
    }

    @Test
    fun commentaire_copie_colle_avec_son_bloc() {
        // Cas réel : un commentaire entier recopié d'une station à l'autre.
        val source = encode("Note de terrain.", "enjeu" to "fort", "typicite" to "bonne")
        val recopie = encode(source, "enjeu" to "faible")

        assertEquals(1, Regex(Regex.escape(AnaEval.EVAL_START)).findAll(recopie).count())
        assertEquals(mapOf<String, Any>("enjeu" to "faible"), decode(recopie))
        assertEquals("Note de terrain.", AnaEval.texteHumain(recopie))
    }

    @Test
    fun encodage_stable_entre_deux_ecritures() {
        // Sinon l'empreinte serveur change à chaque synchro → faux conflit.
        val a = encode("", "enjeu" to "fort", "typicite" to "bonne", "dynamique" to "stable")
        val b = encode("", "dynamique" to "stable", "enjeu" to "fort", "typicite" to "bonne")
        assertEquals(a, b)
    }

    @Test
    fun valeurs_hors_referentiel_non_ecrites() {
        assertFalse(decode(encode("", "enjeu" to "n_importe_quoi")).containsKey("enjeu"))
        assertFalse(decode(encode("", "typicite" to "excellente")).containsKey("typicite"))
        assertEquals("", encode("", "cle_inventee" to "valeur"))
    }

    @Test
    fun pee_limite_a_trois_taxons() {
        val encoded = encode("", "pee" to listOf(
            "Reynoutria japonica", "Buddleja davidii", "Ailanthus altissima", "Robinia pseudoacacia"))
        assertEquals(3, (decode(encoded)["pee"] as List<*>).size)
    }

    @Test
    fun sans_valeur_utile_pas_de_bloc() {
        assertEquals("Juste du texte.", encode("Juste du texte."))
        assertEquals("", encode(""))
    }

    // ── Lecture de l'ancien format ───────────────────────────────────────────────────────────

    @Test
    fun ancien_format_relu() {
        val ancien = "Texte de terrain.\n\n[ANA-EVAL] enjeu=fort | etat_conservation=bon" +
            " | recouvrement=45 | zone_humide=true [/ANA-EVAL]"

        val codes = decode(ancien)

        assertEquals("fort", codes["enjeu"])
        assertEquals("bon", codes["etat_conservation"])
        assertEquals(45, codes["recouvrement"])
        assertEquals("oui", codes["zone_humide"])
        assertEquals("Texte de terrain.", AnaEval.texteHumain(ancien))
    }

    @Test
    fun ancien_format_codes_herites_convertis_a_la_relecture() {
        val ancien = "[ANA-EVAL] enjeu=majeur | etat_conservation=nd [/ANA-EVAL]"

        val codes = decode(ancien)

        assertEquals("tres_fort", codes["enjeu"])
        assertEquals("inconnu", codes["etat_conservation"])
    }

    @Test
    fun migration_ancien_vers_json_sans_perte() {
        val ancien = "Relevé du 12 juin.\n\n[ANA-EVAL] enjeu=majeur | etat_conservation=bon" +
            " | recouvrement=60 | zone_humide=true [/ANA-EVAL]"

        val reecrit = AnaEval.encoder(AnaEval.texteHumain(ancien), decode(ancien))

        assertTrue(reecrit.contains("Relevé du 12 juin."))
        assertEquals(
            mapOf("enjeu" to "tres_fort", "etat_conservation" to "bon",
                "recouvrement" to 60, "zone_humide" to "oui"),
            decode(reecrit),
        )
        // …et le bloc est désormais du JSON.
        val raw = reecrit.substringAfter(AnaEval.EVAL_START).substringBefore(AnaEval.EVAL_END).trim()
        assertTrue(raw.startsWith("{"))
    }

    @Test
    fun bloc_illisible_ignore_sans_planter() {
        // Bloc trituré à la main dans l'interface web GeoNature.
        for (raw in listOf("ceci n'est pas du json {{", "", "   ", "[]", "null")) {
            val texte = "Note.\n\n${AnaEval.EVAL_START} $raw ${AnaEval.EVAL_END}"
            assertEquals(emptyMap<String, Any>(), decode(texte))
            assertEquals("Note.", AnaEval.texteHumain(texte))
        }
    }

    @Test
    fun aucun_bloc() {
        assertEquals(emptyMap<String, Any>(), decode("Simple commentaire."))
        assertEquals(emptyMap<String, Any>(), decode(""))
        assertEquals(emptyMap<String, Any>(), decode(null))
    }

    // ── Recouvrement ─────────────────────────────────────────────────────────────────────────

    @Test
    fun recouvrement_valide_et_normalise() {
        assertEquals(45, decode(encode("", "recouvrement" to 45))["recouvrement"])
        assertEquals(12.5, decode(encode("", "recouvrement" to 12.5))["recouvrement"])
        for (value in listOf(0, -1, 101, null, "abc", true)) {
            assertFalse("recouvrement=$value devrait être écarté",
                decode(encode("", "recouvrement" to value)).containsKey("recouvrement"))
        }
    }

    // ── Échelle (entier borné) ───────────────────────────────────────────────────────────────

    @Test
    fun echelle_entier_borne() {
        assertEquals(5000, decode(encode("", "echelle" to 5000))["echelle"])
        assertEquals(5000, decode(encode("", "echelle" to "5000"))["echelle"])
        for (value in listOf(0, -5, 2_000_000, "abc", true, null)) {
            assertFalse("echelle=$value devrait être écartée",
                decode(encode("", "echelle" to value)).containsKey("echelle"))
        }
    }

    // ── Écriture partielle du bloc ───────────────────────────────────────────────────────────

    @Test
    fun fusionner_conserve_les_autres_cles() {
        val complet = encode("Note.", "enjeu" to "fort", "typicite" to "bonne", "recouvrement" to 60)

        val fusionne = AnaEval.fusionner(complet, mapOf("statut" to "valide"))

        assertEquals(
            mapOf("enjeu" to "fort", "typicite" to "bonne", "recouvrement" to 60,
                "statut" to "valide"),
            decode(fusionne),
        )
        assertEquals("Note.", AnaEval.texteHumain(fusionne))
    }

    @Test
    fun fusionner_sur_texte_sans_bloc() {
        val fusionne = AnaEval.fusionner("Commentaire simple.", mapOf("statut" to "brouillon"))
        assertEquals(mapOf<String, Any>("statut" to "brouillon"), decode(fusionne))
        assertEquals("Commentaire simple.", AnaEval.texteHumain(fusionne))
    }

    @Test
    fun fusionner_null_supprime_la_cle() {
        val complet = encode("", "enjeu" to "fort", "statut" to "valide")
        assertEquals(
            mapOf<String, Any>("enjeu" to "fort"),
            decode(AnaEval.fusionner(complet, mapOf("statut" to null))),
        )
    }

    @Test
    fun fusionner_sur_ancien_format() {
        val ancien = "[ANA-EVAL] enjeu=majeur | recouvrement=30 [/ANA-EVAL]"
        val fusionne = AnaEval.fusionner(ancien, mapOf("statut" to "valide"))
        assertEquals(
            mapOf("enjeu" to "tres_fort", "recouvrement" to 30, "statut" to "valide"),
            decode(fusionne),
        )
    }

    @Test
    fun statut_validation_est_un_code_ferme() {
        assertEquals("valide", decode(encode("", "statut" to "valide"))["statut"])
        assertFalse(decode(encode("", "statut" to "en_cours")).containsKey("statut"))
    }

    // ── Zone humide : trois états, et non plus une case à cocher ─────────────────────────────

    @Test
    fun zone_humide_trois_etats() {
        assertEquals("a_verifier",
            decode(encode("Bas-fond sec en août.", "zone_humide" to "a_verifier"))["zone_humide"])
        assertEquals("non", decode(encode("", "zone_humide" to "non"))["zone_humide"])
        assertFalse(decode(encode("", "zone_humide" to "peut-etre-bien")).containsKey("zone_humide"))
    }

    @Test
    fun zone_humide_ancien_booleen() {
        assertEquals("oui", decode(encode("", "zone_humide" to true))["zone_humide"])
        // `false` ne voulait dire que « case décochée » : rien à en conclure.
        assertFalse(decode(encode("", "zone_humide" to false)).containsKey("zone_humide"))
        // Ancien format textuel du bloc.
        assertEquals("oui", decode("[ANA-EVAL] zone_humide=true [/ANA-EVAL]")["zone_humide"])
    }

    // ── Détermination hors HABREF et correspondances (PRÉSERVÉES telles quelles) ─────────────

    @Test
    fun correspondance_arbitree_survit_a_l_aller_retour() {
        val texte = encode("", "corresp" to mapOf("EUNIS" to mapOf("cd_hab" to 5678, "src" to "manuel")))
        assertEquals(
            mapOf("EUNIS" to mapOf("cd_hab" to 5678, "src" to "manuel")),
            decode(texte)["corresp"],
        )
    }

    @Test
    fun determination_hors_habref_dit_son_ancre() {
        val texte = encode("",
            "determination" to mapOf("nom" to "Salicion pyrenaicae", "ancre" to "CORINE_biotopes"))
        assertEquals(
            mapOf("nom" to "Salicion pyrenaicae", "ancre" to "CORINE_biotopes"),
            decode(texte)["determination"],
        )
    }

    @Test
    fun determination_sans_nom_ecartee() {
        assertEquals(emptyMap<String, Any>(),
            decode(encode("", "determination" to mapOf("ancre" to "EUNIS"))))
    }

    @Test
    fun ancre_hors_referentiel_ecartee_mais_nom_garde() {
        val codes = decode(encode("",
            "determination" to mapOf("nom" to "Salicion pyrenaicae", "ancre" to "TYPOLOGIE_INVENTEE")))
        assertEquals(mapOf("nom" to "Salicion pyrenaicae"), codes["determination"])
    }

    @Test
    fun typologie_inconnue_ecartee_des_correspondances() {
        val codes = decode(encode("", "corresp" to mapOf(
            "EUNIS" to mapOf("cd_hab" to 5678), "PIFOMETRE" to mapOf("cd_hab" to 1))))
        assertEquals(setOf("EUNIS"), (codes["corresp"] as Map<*, *>).keys)
    }

    @Test
    fun correspondance_sans_cd_hab_ecartee() {
        assertEquals(emptyMap<String, Any>(),
            decode(encode("", "corresp" to mapOf("EUNIS" to mapOf("code" to "F9.12")))))
    }

    @Test
    fun source_hors_referentiel_ecartee_sans_inventer() {
        val codes = decode(encode("", "corresp" to mapOf(
            "EUNIS" to mapOf("cd_hab" to 5678, "src" to "au_pif"))))
        assertEquals(mapOf("cd_hab" to 5678), (codes["corresp"] as Map<*, *>)["EUNIS"])
    }

    @Test
    fun le_libelle_n_est_plus_enregistre_le_code_reste() {
        val texte = encode("", "corresp" to mapOf("EUNIS" to mapOf(
            "cd_hab" to 1778, "code" to "F9.1", "nom" to "Fourrés ripicoles", "src" to "manuel")))
        assertEquals(
            mapOf("cd_hab" to 1778, "code" to "F9.1", "src" to "manuel"),
            (decode(texte)["corresp"] as Map<*, *>)["EUNIS"],
        )
        assertFalse("le libellé, lui, ne revient pas", texte.contains("Fourrés ripicoles"))
    }

    @Test
    fun une_correspondance_ancienne_perd_son_libelle_pas_son_code() {
        val codes = decode(
            "[ANA-EVAL] {\"corresp\": {\"EUNIS\": {\"cd_hab\": 1778, \"code\": \"F9.1\"," +
                " \"nom\": \"Fourrés ripicoles\", \"src\": \"manuel\"}}} [/ANA-EVAL]")
        assertEquals(
            mapOf("cd_hab" to 1778, "code" to "F9.1", "src" to "manuel"),
            (codes["corresp"] as Map<*, *>)["EUNIS"],
        )
    }

    @Test
    fun bloc_reste_lisible_avec_le_texte_humain() {
        val texte = encode("Relevé du 12 mai.", "enjeu" to "fort",
            "corresp" to mapOf("EUNIS" to mapOf("cd_hab" to 5678, "src" to "catalogue")))
        assertEquals("Relevé du 12 mai.", AnaEval.texteHumain(texte))
        assertEquals("fort", decode(texte)["enjeu"])
    }

    // ── Pont anaEvalJson (extraction à l'import / re-fusion à l'envoi) ───────────────────────

    @Test
    fun extraire_bloc_json_null_sans_bloc_exploitable() {
        // INVARIANT : null ⇒ chemin standard, texte inchangé — stations locales et stations
        // serveur sans bloc ne doivent RIEN voir changer.
        assertNull(AnaEval.extraireBlocJson(null))
        assertNull(AnaEval.extraireBlocJson("Simple commentaire."))
        assertNull(AnaEval.extraireBlocJson("Note.\n\n[ANA-EVAL] pas=valide! [/ANA-EVAL]"))
    }

    @Test
    fun extraire_puis_refusionner_round_trip_complet() {
        val serveur = "Note de terrain.\n\n[ANA-EVAL] {\"enjeu\": \"fort\", " +
            "\"determination\": {\"nom\": \"Salicion pyrenaicae\", \"ancre\": \"EUNIS\"}, " +
            "\"corresp\": {\"EUNIS\": {\"cd_hab\": 5678, \"src\": \"manuel\"}}} [/ANA-EVAL]"

        // Import : le texte stocké redevient humain, le bloc part en JSON à part.
        val ana = AnaEval.extraireBlocJson(serveur)
        val humain = AnaEval.texteHumain(serveur)
        assertEquals("Note de terrain.", humain)
        assertTrue(ana != null)

        // Envoi : re-fusion — texte humain préservé, clés structurées PRÉSERVÉES telles quelles.
        val renvoye = AnaEval.avecBloc(humain, ana)!!
        assertEquals("Note de terrain.", AnaEval.texteHumain(renvoye))
        val relu = decode(renvoye)
        assertEquals("fort", relu["enjeu"])
        assertEquals(mapOf("nom" to "Salicion pyrenaicae", "ancre" to "EUNIS"), relu["determination"])
        assertEquals(mapOf("EUNIS" to mapOf("cd_hab" to 5678, "src" to "manuel")), relu["corresp"])
    }

    @Test
    fun extraire_ancien_format_puis_refusionner_en_json() {
        val serveur = "Relevé.\n\n[ANA-EVAL] enjeu=majeur | zone_humide=true [/ANA-EVAL]"

        val ana = AnaEval.extraireBlocJson(serveur)
        val renvoye = AnaEval.avecBloc(AnaEval.texteHumain(serveur), ana)!!

        assertEquals(mapOf("enjeu" to "tres_fort", "zone_humide" to "oui"), decode(renvoye))
        val raw = renvoye.substringAfter(AnaEval.EVAL_START).substringBefore(AnaEval.EVAL_END).trim()
        assertTrue("réécrit en JSON", raw.startsWith("{"))
    }

    @Test
    fun avec_bloc_sans_ana_eval_comportement_standard_strict() {
        // Même contrat que l'ancien `comment?.takeIf { it.isNotBlank() }` : texte TEL QUEL.
        assertEquals("station de test", AnaEval.avecBloc("station de test", null))
        assertNull(AnaEval.avecBloc("", null))
        assertNull(AnaEval.avecBloc(null, null))
        // Même un texte contenant des balises tapées à la main passe INTACT sans anaEvalJson.
        val balises = "voir [ANA-EVAL] plus haut"
        assertEquals(balises, AnaEval.avecBloc(balises, null))
    }

    @Test
    fun avec_bloc_sans_texte_humain_le_bloc_part_seul() {
        val renvoye = AnaEval.avecBloc(null, "{\"enjeu\": \"fort\"}")!!
        assertTrue(renvoye.startsWith(AnaEval.EVAL_START))
        assertEquals("fort", decode(renvoye)["enjeu"])
    }

    @Test
    fun vers_json_et_depuis_json_round_trip() {
        val json = AnaEval.versJson(mapOf("enjeu" to "fort", "pee" to listOf("Buddleja davidii")))!!
        assertEquals(mapOf("enjeu" to "fort", "pee" to listOf("Buddleja davidii")),
            AnaEval.depuisJson(json))
        assertNull(AnaEval.versJson(emptyMap()))
        assertNull(AnaEval.versJson(mapOf("enjeu" to "hors_liste")))
        assertEquals(emptyMap<String, Any>(), AnaEval.depuisJson(null))
        assertEquals(emptyMap<String, Any>(), AnaEval.depuisJson("pas du json"))
    }

    // ── Recouvrement, champ DOUBLE (bloc + colonne native recovery_percentage) ───────────────

    @Test
    fun recouvrement_du_bloc_fait_foi_a_la_relecture() {
        assertEquals(60.0, AnaEval.recouvrementDuBloc("{\"recouvrement\": 60}"))
        assertEquals(12.5, AnaEval.recouvrementDuBloc("{\"recouvrement\": 12.5}"))
        assertNull(AnaEval.recouvrementDuBloc("{\"enjeu\": \"fort\"}"))
        assertNull(AnaEval.recouvrementDuBloc(null))
    }

    @Test
    fun recouvrement_natif_realigne_le_bloc() {
        val bloc = AnaEval.versJson(mapOf("enjeu" to "fort", "recouvrement" to 60))!!
        // Édition native → la clé du bloc suit.
        val maj = AnaEval.avecRecouvrementNatif(bloc, 45.0)!!
        assertEquals(45, AnaEval.depuisJson(maj)["recouvrement"])
        assertEquals("fort", AnaEval.depuisJson(maj)["enjeu"])
        // Natif effacé → la clé est retirée (la garder la ressusciterait côté QGIS).
        val efface = AnaEval.avecRecouvrementNatif(bloc, null)!!
        assertFalse(AnaEval.depuisJson(efface).containsKey("recouvrement"))
        // Bloc SANS la clé → jamais ajoutée (la colonne native suffit), bloc inchangé.
        val sans = AnaEval.versJson(mapOf("enjeu" to "fort"))!!
        assertEquals(sans, AnaEval.avecRecouvrementNatif(sans, 45.0))
        assertNull(AnaEval.avecRecouvrementNatif(null, 45.0))
    }

    @Test
    fun texte_humain_seul_apres_vidage_du_bloc() {
        // Toutes les clés effacées dans l'UI → versJson null → le bloc disparaît du texte.
        val bloc = AnaEval.versJson(mapOf("recouvrement" to 60))!!
        assertNull(AnaEval.avecRecouvrementNatif(bloc, null))
        assertEquals("Note.", AnaEval.avecBloc("Note.", null))
    }
}
