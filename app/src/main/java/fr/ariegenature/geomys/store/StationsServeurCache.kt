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

package fr.ariegenature.geomys.store

import android.content.Context
import com.google.gson.Gson
import fr.ariegenature.geomys.model.OccHabStation
import java.io.File

/**
 * Cache local des stations OccHab DU SERVEUR appartenant à l'utilisateur (numérisateur ou
 * observateur — le filtre client de `OccHabApi.chargerStations` est appliqué AVANT écriture :
 * on ne persiste jamais les stations d'autrui), pour que la carte OccHab puisse les afficher,
 * les aimanter et les IMPORTER **hors ligne**.
 *
 * Alimenté à deux moments :
 *  - « Recharger les données » ([fr.ariegenature.geomys.network.SyncRunner], best-effort) :
 *    [remplacerTout] avec les stations de TOUS les jeux de données ;
 *  - chargement réseau réussi depuis la carte (un seul JDD) : [remplacerJdd] au fil de l'eau.
 *
 * La carte lit par JDD via [lire] (invariant carte : compte ET jeu de données de la saisie).
 * Purgé par « Vider le cache » ([viderCachesSynchronises]) et au changement d'identité serveur
 * ([fr.ariegenature.geomys.network.invaliderCachesSession] — URL/login/mdp : les id_station et
 * id_dataset sont propres à chaque instance, et les stations d'un autre compte n'ont rien à
 * faire ici). Fichier (trop gros pour SharedPreferences), écriture atomique tmp+rename comme
 * [HabitatCacheStore]. Cf. [[occhab-module]], [[synchro-cache]].
 */
object StationsServeurCache {

    private const val NOM_FICHIER = "stations_serveur_occhab_v1.json"

    private lateinit var dir: File
    private val gson = Gson()

    /** Version du FORMAT des stations écrites dans ce fichier — à incrémenter dès qu'un champ de
     *  géométrie apparaît ou change de sens dans [OccHabStation]. Un fichier d'une autre version
     *  est ignoré (cache vide) plutôt que relu : il a été écrit par un parseur qui ne savait pas
     *  lire ce que la version courante sait lire.
     *
     *  2 — v1.4.0 : anneaux intérieurs (`geometryTrousJson`) et MultiPolygon (`geometryPartielle`).
     *  Le parseur de v1.3.22 ne gardait que l'anneau extérieur et réduisait tout MultiPolygon à un
     *  Point (0,0). Relire un tel cache, c'était afficher un polygone PLEIN puis, à l'envoi de la
     *  copie modifiée, SUPPRIMER le trou côté GeoNature — la régression même que la v1.4.0
     *  corrigeait, rouverte par le chemin hors-ligne (audit 2026-09-14, R3-C1 / R2-M1). */
    private const val VERSION_FORMAT = 2

    /** Contenu du fichier : version du format, date du dernier remplacement (epoch millis, pour
     *  informer l'utilisateur de la fraîcheur en mode hors-ligne) + stations filtrées utilisateur.
     *  [stations] nullable : Gson (réflexion, sans les défauts Kotlin) peut le laisser null
     *  sur un fichier corrompu/tronqué — normalisé par [Contenu.liste]. [versionFormat] vaut 0 sur
     *  un fichier écrit avant l'introduction du champ (v1.3.22 à v1.4.1). */
    private data class Contenu(
        val dateChargement: Long = 0L,
        val stations: List<OccHabStation>? = emptyList(),
        val versionFormat: Int = 0,
    ) {
        val liste: List<OccHabStation> get() = stations ?: emptyList()
    }

    @Volatile private var mem: Contenu? = null

    fun init(context: Context) { dir = context.filesDir }

    private fun fichier() = File(dir, NOM_FICHIER)

    private fun charger(): Contenu {
        mem?.let { return it }
        if (!::dir.isInitialized) return Contenu()
        val f = fichier()
        if (!f.exists()) return Contenu()
        return try {
            val brut = gson.fromJson(f.readText(), Contenu::class.java) ?: Contenu()
            // Fichier écrit par une version dont le parseur de géométrie ne savait pas tout lire :
            // on l'ignore et on le supprime. Le prochain chargement carte (ou la prochaine synchro)
            // le réécrira au format courant ; d'ici là, l'utilisateur hors ligne n'a simplement pas
            // de station serveur — ce qui vaut mille fois mieux que d'en afficher une amputée de
            // ses trous et de la renvoyer ainsi au serveur (audit 2026-09-14, R3-C1).
            if (brut.versionFormat != VERSION_FORMAT) {
                runCatching { f.delete() }
                return Contenu().also { mem = it }
            }
            // NORMALISATION POST-GSON, comme OccHabStore : Gson construit par réflexion, sans les
            // valeurs par défaut de Kotlin — une station tronquée arrivait ici avec des champs
            // non-nullables à null. C'est la TROISIÈME porte d'entrée d'OccHabStation (avec le
            // store et le parseur réseau) et la seule qui ne normalisait pas (R2-m1).
            @Suppress("SENSELESS_COMPARISON")
            val stations = brut.liste.mapNotNull { s -> if (s == null) null else normaliserStation(s) }
            Contenu(brut.dateChargement, stations, brut.versionFormat).also { mem = it }
        } catch (_: Exception) { Contenu() }
    }

    @Synchronized
    private fun ecrire(contenu: Contenu) {
        mem = contenu
        if (!::dir.isInitialized) return
        try {
            val tmp = File(dir, "$NOM_FICHIER.tmp")
            tmp.writeText(gson.toJson(contenu))
            val cible = fichier()
            if (!tmp.renameTo(cible)) { cible.delete(); tmp.renameTo(cible) }
        } catch (_: Exception) { /* cache best-effort */ }
    }

    /** Remplace TOUT le cache (chemin de synchro, tous JDD confondus). Une liste VIDE est un
     *  résultat valable (l'utilisateur n'a plus de station) : l'appelant ne doit appeler qu'après
     *  un chargement réseau RÉUSSI — sur échec (exception), il ne touche pas au cache. */
    @Synchronized
    fun remplacerTout(stations: List<OccHabStation>) =
        ecrire(Contenu(System.currentTimeMillis(), stations, VERSION_FORMAT))

    /** Remplace les stations d'UN jeu de données (chargement réseau réussi depuis la carte),
     *  en conservant celles des autres JDD (chargées par la synchro). */
    @Synchronized
    fun remplacerJdd(idJdd: Int, stations: List<OccHabStation>) {
        val autres = charger().liste.filter { it.idDataset != idJdd }
        ecrire(Contenu(System.currentTimeMillis(), autres + stations, VERSION_FORMAT))
    }

    /** Stations en cache pour ce jeu de données (repli hors-ligne de la carte). */
    fun lire(idJdd: Int): List<OccHabStation> =
        charger().liste.filter { it.idDataset == idJdd }

    /** Date (epoch millis) du dernier remplacement, null si cache jamais écrit. */
    val dateChargement: Long?
        get() = charger().dateChargement.takeIf { it > 0L }

    /** Nombre TOTAL de stations en cache, tous JDD confondus (compteur de Paramètres). */
    val count: Int get() = charger().liste.size

    @Synchronized
    fun vider() {
        if (::dir.isInitialized) runCatching { fichier().delete() }
        mem = null
    }

    /** Réinitialise l'état mémoire (tests : chaque test repart du disque). */
    fun resetPourTests() { mem = null }
}
