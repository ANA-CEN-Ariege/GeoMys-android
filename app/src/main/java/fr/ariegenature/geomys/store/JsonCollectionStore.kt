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

import com.google.gson.Gson
import java.lang.reflect.Type

/**
 * Base commune aux stores JSON « collection » (une liste d'objets sérialisée en un blob JSON) :
 * [SortieStore] (Occtax, SharedPreferences), [OccHabStore] (SharedPreferences), [OutboxMonitoring]
 * (fichier). Mutualise la logique DÉLICATE qui était copiée-divergée dans les trois :
 *
 *  - **cache mémoire process-wide** (évite de re-désérialiser tout le store à chaque action de
 *    saisie) — délégué au store concret via [cache] (companion `@Volatile` ou `object`) ;
 *  - **normalisation post-Gson** (Gson instancie par Unsafe sans constructeur → champs/listes
 *    non-nullables absents d'un JSON ancien restent `null` → NPE différée) + **quarantaine** d'un
 *    JSON illisible ou partiellement écarté (copie `.corrupt` du PREMIER incident, jamais écrasée) ;
 *  - **lire-modifier-écrire ATOMIQUE sous verrou** ([muter]) : les mutations viennent du thread UI
 *    (saisie au fil de l'eau) ET de `Dispatchers.IO` (chemin d'envoi) — sans verrou, deux écrivains
 *    croisés se perdent mutuellement des entrées (lost update). C'est le correctif que seul
 *    OutboxMonitoring appliquait ; il est désormais commun aux trois.
 *
 * Le BACKEND (prefs vs fichier) reste PROPRE à chaque store, injecté par [lireBrut]/[ecrireBrut]/
 * [quarantaine] → aucune migration de données. [cache] et [verrou] doivent être process-wide
 * (portés par le companion d'une classe multi-instanciée, ou par l'`object` singleton).
 */
abstract class JsonCollectionStore<T : Any> {
    protected val gson = Gson()

    /** Verrou PROCESS-WIDE (companion/objet) — sérialise tous les lire-modifier-écrire. */
    protected abstract val verrou: Any
    /** Cache mémoire PROCESS-WIDE (companion/objet). null = à relire du disque. */
    protected abstract var cache: List<T>?
    /** Étiquette de logs. */
    protected abstract val nom: String
    /** Type de `MutableList<T?>` pour Gson (T est effacé à l'exécution). */
    protected abstract val typeListe: Type

    /** Lit le blob JSON brut du backend (null = store vide / absent). */
    protected abstract fun lireBrut(): String?
    /** Persiste le blob JSON (true = persistance DURABLE confirmée). */
    protected abstract fun ecrireBrut(json: String): Boolean
    /** Met le blob fautif de côté (`.corrupt`, premier incident) avant qu'il soit écrasé. */
    protected abstract fun quarantaine(json: String)
    /** Reconstruit une entrée SÛRE après désérialisation ; renvoie null pour l'ÉCARTER. */
    protected abstract fun normaliser(item: T): T?

    /** Snapshot défensif : les appelants peuvent muter la copie sans affecter le cache. */
    fun charger(): MutableList<T> = synchronized(verrou) { ArrayList(chargerInterne()) }

    private fun chargerInterne(): List<T> {
        cache?.let { return it }
        val json = lireBrut()
        val parsed: List<T> = if (json == null) emptyList() else try {
            val brutes = (gson.fromJson<MutableList<T?>>(json, typeListe) ?: mutableListOf())
                .filterNotNull()
            val valides = brutes.mapNotNull { normaliser(it) }
            // Des entrées écartées = JSON corrompu-mais-parsable → on met le blob d'origine de côté
            // AVANT que la prochaine écriture ne l'écrase (sinon perte définitive silencieuse).
            if (valides.size < brutes.size) {
                android.util.Log.w(nom,
                    "charger : ${brutes.size - valides.size} entrée(s) illisible(s) écartée(s)")
                quarantaine(json)
            }
            valides
        } catch (e: Exception) {
            android.util.Log.e(nom, "charger : JSON illisible, mise en quarantaine", e)
            quarantaine(json)
            emptyList()
        }
        cache = parsed
        return parsed
    }

    /** Écrit [items] via le backend et ne met le cache à jour QU'APRÈS une persistance confirmée
     *  (sinon l'app afficherait comme sauvées des données qui disparaîtraient au redémarrage). */
    protected fun sauvegarder(items: List<T>): Boolean = synchronized(verrou) {
        val ok = ecrireBrut(gson.toJson(items))
        if (ok) cache = ArrayList(items)
        else android.util.Log.e(nom,
            "sauvegarder ECHEC — cache mémoire NON mis à jour (disque conservé)")
        ok
    }

    /** Lire-modifier-écrire ATOMIQUE : le verrou est tenu du chargement à la sauvegarde, donc deux
     *  écrivains croisés (UI + IO) ne peuvent plus se perdre une entrée. Renvoie le succès de la
     *  persistance. */
    protected fun muter(bloc: (MutableList<T>) -> Unit): Boolean = synchronized(verrou) {
        val liste = ArrayList(chargerInterne())
        bloc(liste)
        sauvegarder(liste)
    }

    /** Comme [muter] mais renvoie la valeur calculée par [bloc] (le succès de persistance est
     *  ignoré — parité avec l'ancien `supprimerCascade` qui renvoyait un compteur). */
    protected fun <R> muterCalcul(bloc: (MutableList<T>) -> R): R = synchronized(verrou) {
        val liste = ArrayList(chargerInterne())
        val r = bloc(liste)
        sauvegarder(liste)
        r
    }
}
