package fr.ariegenature.geonat.network

import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Pré-chargement de l'**arborescence structurelle** du module Suivis (gn_module_monitoring)
 *  pour usage hors-réseau.
 *
 *  Stratégie : BFS qui descend tant qu'on est dans des types "structurels" (sites_group,
 *  site, point, dalle, secteur…) et s'arrête à l'entrée d'un type "saisie" (visite,
 *  observation, releve…). Rationale : pour saisir une nouvelle visite offline il faut
 *  pouvoir naviguer jusqu'au parent de la saisie (le site/point) ; les visites et
 *  observations passées sont consultables uniquement en ligne (fetch réseau + cache
 *  write-through automatique côté [MonitoringApi]). Sans ce filtre, un protocole avec
 *  ~10 ans d'historique fait facilement 30000+ fiches inutiles à la saisie.
 *
 *  Concurrence bornée à [CHUNK_SIZE] requêtes parallèles pour ne pas saturer l'instance
 *  GeoNature. La profondeur max [PROFONDEUR_MAX] sert de garde-fou contre les cycles. */
object MonitoringSync {

    /** Types reconnus comme "saisie" → la BFS ne descend pas dedans. Insensible à la casse.
     *  Couvre les conventions OCCTAX (releve/occurrence/denombrement) et gn_module_monitoring
     *  (visite/observation). Au pluriel pour matcher les variantes serveur ("observations"). */
    private val TYPES_SAISIE = setOf(
        "visite", "visites", "visit", "visits",
        "observation", "observations", "obs",
        "occurrence", "occurrences",
        "releve", "releves", "relevé", "relevés",
        "denombrement", "denombrements", "dénombrement", "dénombrements",
    )

    /** Garde-fou contre les schémas mal formés (cycle parent_type / children). En pratique
     *  les arborescences réelles structurelles dépassent rarement 4 niveaux. */
    private const val PROFONDEUR_MAX = 8
    /** Concurrence par niveau — empirique : 5 RTT en parallèle, c'est confortable sans
     *  faire monter la charge serveur. */
    private const val CHUNK_SIZE = 5

    /** Vrai si [type] est un type de saisie (ne pas pré-charger l'historique). */
    /** Vrai si [type] est un type de "saisie" (visite, observation, occurrence, …).
     *  Exposé pour que les écrans qui décident d'afficher un bouton "+ Nouveau X" puissent
     *  s'aligner sur la même heuristique de nommage. */
    fun estTypeSaisie(type: String): Boolean = type.trim().lowercase() in TYPES_SAISIE

    /** Charge tout le module Suivis pour usage offline.
     *  Callback `progression(moduleIdx, modulesTotaux, objetsCharges)` — `moduleIdx==0` avant le
     *  démarrage (pendant le fetch de la liste des modules), puis 1..N pendant l'itération. */
    suspend fun synchroniserSuivis(
        config: GeoNatureConfig,
        progression: (Int, Int, Int) -> Unit,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        progression(0, 0, 0)

        val modules = try { MonitoringApi.chargerModules(config) }
            catch (e: Exception) { return@withContext Pair(0, "Modules monitoring : ${e.message}") }

        if (modules.isEmpty()) return@withContext Pair(0, "Aucun module monitoring exposé par le serveur.")

        var objetsCharges = 0
        var modulesOk = 0
        val modulesEnEchec = mutableListOf<String>()

        for ((idx, module) in modules.withIndex()) {
            progression(idx + 1, modules.size, objetsCharges)
            try {
                // Le schéma + les enfants directs sont indépendants → fetch en parallèle pour
                // gagner ~1 RTT par module.
                val (_, enfantsParType) = coroutineScope {
                    val schemaDef = async { runCatching { MonitoringApi.chargerSchemaProtocole(config, module.moduleCode) }.getOrNull() }
                    val enfantsDef = async { runCatching { MonitoringApi.chargerEnfants(config, module.moduleCode) }.getOrDefault(emptyMap()) }
                    awaitAll(schemaDef, enfantsDef)
                    schemaDef.await() to enfantsDef.await()
                }

                // BFS sur l'arborescence STRUCTURELLE — on filtre les types de saisie pour
                // ne pas précharger l'historique des visites/obs. Chaque "tâche" = (type, id)
                // à charger via chargerObjet.
                val visitees = HashSet<Pair<String, Int>>() // dédoublonne (type, id) si réfs croisés
                var niveau: List<Pair<String, Int>> = enfantsParType
                    .filterKeys { !estTypeSaisie(it) }
                    .flatMap { (type, l) -> l.map { type to it.id } }
                    .filter { visitees.add(it) }

                var profondeur = 0
                while (niveau.isNotEmpty() && profondeur < PROFONDEUR_MAX) {
                    profondeur++
                    val prochain = mutableListOf<Pair<String, Int>>()
                    niveau.chunked(CHUNK_SIZE).forEach { chunk ->
                        val fiches = coroutineScope {
                            chunk.map { (type, id) ->
                                async {
                                    runCatching {
                                        MonitoringApi.chargerObjet(config, module.moduleCode, type, id)
                                    }.getOrNull()
                                }
                            }.awaitAll()
                        }
                        for (fiche in fiches) {
                            if (fiche == null) continue
                            objetsCharges++
                            // Sous-enfants depth=1 — déjà inclus dans la fiche. On skippe
                            // les types de saisie : ils seront fetchés à la volée en ligne
                            // si l'utilisateur les consulte (cache write-through prend le
                            // relais ensuite pour l'offline ultérieur).
                            for ((sousType, liste) in fiche.enfants) {
                                if (estTypeSaisie(sousType)) continue
                                for (enfant in liste) {
                                    val cle = sousType to enfant.id
                                    if (visitees.add(cle)) prochain.add(cle)
                                }
                            }
                        }
                        // Progression mise à jour à chaque chunk pour rester réactif sur les
                        // gros modules (la BFS d'un protocole STOM peut faire 500+ objets).
                        progression(idx + 1, modules.size, objetsCharges)
                    }
                    niveau = prochain
                }
                modulesOk++
            } catch (e: Exception) {
                modulesEnEchec.add(module.moduleCode)
            }
        }

        val msg = buildString {
            append("$modulesOk/${modules.size} protocoles préchargés, $objetsCharges objets en cache")
            if (modulesEnEchec.isNotEmpty()) append("\n⚠ Modules en échec : ${modulesEnEchec.joinToString(",")}")
        }
        Pair(modulesOk, msg)
    }
}
