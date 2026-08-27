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
import fr.ariegenature.geomys.network.MonitoringApi

/**
 * Purge les caches SYNCHRONISÉS (TaxRef, nomenclatures, habitats, monitoring, pictos) — ce que
 * « Recharger les données » réécrit. Ne touche NI aux saisies locales (Mes saisies / Mes stations /
 * Mes visites), NI aux JSON de config (datasets / listes / observateurs), NI aux sélections.
 * Utilisé par le bouton « Vider le cache » de Paramètres et par SyncRunner au début d'une synchro
 * de « rechargement requis » (cf. [armerRechargementSiRequis]).
 */
fun viderCachesSynchronises() {
    TaxRefCache.vider()
    NomenclatureCache.vider()
    HabitatCache.vider()
    HabitatCacheOccHab.vider()
    MonitoringCache.vider()
    PictoCache.vider()  // pictos de protocole (cache disque)
    // MonitoringCache.vider() n'efface que le DISQUE : la liste des modules est aussi gardée en
    // mémoire par MonitoringApi (dernierChargement) et countModulesEnCache() la renvoie en
    // priorité — sans cette invalidation, le compteur de protocoles reste figé après une purge.
    MonitoringApi.invaliderCaches()
}

/**
 * Au lancement de l'application (MainActivity) : si la release installée EXIGE un rechargement des
 * données ([GeoNatureConfig.VERSION_DONNEES_REQUISE] > [GeoNatureConfig.versionDonneesChargees])
 * sur une installation qui a DÉJÀ des données, ARME le drapeau
 * [GeoNatureConfig.rechargementRequisApresMaj] : `configurationComplete` devient faux ⇒ Paramètres
 * s'ouvre, bloque la sortie et affiche le bandeau (mécanisme de 1ʳᵉ configuration réutilisé).
 *
 * RIEN N'EST PURGÉ ICI (audit 2026-08-27) : la purge se fait dans SyncRunner, une fois le serveur
 * joignable et juste avant la réécriture des caches — pas de travail disque sur le thread UI, pas
 * de course avec une synchro déjà en cours, et rien n'est perdu si l'appli est lancée hors-ligne
 * (les données restent en place, seulement inaccessibles jusqu'à la synchro). SyncRunner
 * enregistre la version chargée et désarme le drapeau en fin de synchro.
 *
 * Idempotent : drapeau déjà armé ⇒ false (la synchro suivante fera le travail). Une installation
 * VIERGE (rien de chargé) suit le flux normal de 1ʳᵉ configuration, sans bandeau. Renvoie true si
 * le drapeau vient d'être armé. Demande terrain 2026-08-27.
 */
fun armerRechargementSiRequis(context: Context): Boolean {
    val cfg = GeoNatureConfig(context)
    if (cfg.rechargementRequisApresMaj) return false
    if (cfg.versionDonneesChargees >= GeoNatureConfig.VERSION_DONNEES_REQUISE) return false
    if (TaxRefCache.versionSauvegardee == null) return false
    cfg.rechargementRequisApresMaj = true
    return true
}
