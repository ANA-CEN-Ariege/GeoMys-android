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
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Le champ de formulaire serveur [key] (clé de `OCCTAX.form_fields`, ex. `group_type`) est-il
 *  visible d'après [formFieldsJson] ? **true par défaut** si la clé est absente ou la config non
 *  publiée — on ne masque jamais un champ faute d'information. Partagé entre [GeoNatureConfig] et
 *  l'UI (DetailsReleveDialog). */
fun champFormVisible(formFieldsJson: String, key: String): Boolean = try {
    if (formFieldsJson.isBlank()) true
    else org.json.JSONObject(formFieldsJson).optBoolean(key, true)
} catch (_: Exception) { true }

class GeoNatureConfig(context: Context) {
    private val prefs = context.getSharedPreferences("gn_config", Context.MODE_PRIVATE)

    // Prefs chiffrées pour le mot de passe (clé maître dans l'Android Keystore). null si le
    // Keystore est indisponible → on NE persiste PAS le mot de passe en clair (cf. [motDePasse]).
    // Mises en cache au niveau process : la création (MasterKey + EncryptedSharedPreferences,
    // opération Keystore de plusieurs dizaines de ms) ne se fait qu'UNE fois, alors que
    // GeoNatureConfig est instancié très fréquemment (souvent sur le thread UI).
    private val securePrefs: SharedPreferences? = securePrefs(context.applicationContext)

    init {
        // Hygiène : si le chiffrement est (re)disponible, on purge un éventuel mot de passe en
        // clair laissé dans gn_config par une ancienne version en fallback.
        if (securePrefs != null && prefs.contains("gn_mdp")) {
            prefs.edit().remove("gn_mdp").apply()
        }
    }

    var urlServeur: String
        get() = prefs.getString("gn_url", "") ?: ""
        set(v) = prefs.edit().putString("gn_url", v).apply()

    var login: String
        get() = prefs.getString("gn_login", "") ?: ""
        set(v) = prefs.edit().putString("gn_login", v).apply()

    /** Mot de passe. Chiffré sur disque si le Keystore est dispo ; sinon gardé EN MÉMOIRE
     *  seulement (jamais en clair sur disque) — perdu au redémarrage du process, l'utilisateur
     *  le re-saisit. [motDePasseMemoire] est process-wide car [GeoNatureConfig] est ré-instancié. */
    var motDePasse: String
        get() = securePrefs?.getString("gn_mdp", "")?.takeIf { it.isNotEmpty() }
            ?: motDePasseMemoire ?: ""
        set(v) {
            if (securePrefs != null) securePrefs.edit().putString("gn_mdp", v).apply()
            else motDePasseMemoire = v.takeIf { it.isNotEmpty() }
        }

    var idDataset: String
        get() = prefs.getString("gn_dataset", "") ?: ""
        set(v) = prefs.edit().putString("gn_dataset", v).apply()

    /** Nom du jeu de données sélectionné (affiché dans Config GeoNature à la place de l'id). */
    var nomDataset: String
        get() = prefs.getString("gn_dataset_nom", "") ?: ""
        set(v) = prefs.edit().putString("gn_dataset_nom", v).apply()

    var taxaListeId: String
        get() = prefs.getString("gn_taxa_liste", "") ?: ""
        set(v) = prefs.edit().putString("gn_taxa_liste", v).apply()

    /** Nom complet (prénom + nom) renvoyé par GeoNature lors de la dernière connexion réussie.
     *  Mis à jour par GeoNatureAuth.testerConnexion. Fallback pour le déterminateur si
     *  aucun observateur par défaut n'est sélectionné. */
    var nomUtilisateur: String
        get() = prefs.getString("gn_nom_utilisateur", "") ?: ""
        set(v) = prefs.edit().putString("gn_nom_utilisateur", v).apply()

    /** id_role de l'utilisateur actuellement connecté (renvoyé par GeoNature au login).
     *  Sert à pré-sélectionner cet utilisateur dans les champs observers/datalist de saisie.
     *  -1 si pas encore connecté ou non détecté. */
    var idRoleUtilisateur: Int
        get() = prefs.getInt("gn_id_role_utilisateur", -1)
        set(v) = prefs.edit().putInt("gn_id_role_utilisateur", v).apply()

    /** Version de l'instance GeoNature relevée au dernier test de connexion réussi
     *  (`/api/gn_commons/config`, best-effort). Vide si inconnue. Affichée dans Paramètres
     *  pour diagnostiquer les comportements propres à une version serveur. */
    var versionGeoNatureServeur: String
        get() = prefs.getString("gn_version_serveur", "") ?: ""
        set(v) = prefs.edit().putString("gn_version_serveur", v).apply()

    /** Nom complet de l'observateur sélectionné comme déterminateur par défaut. */
    var observateurDefautNom: String
        get() = prefs.getString("gn_obs_defaut_nom", "") ?: ""
        set(v) = prefs.edit().putString("gn_obs_defaut_nom", v).apply()

    /** id_role de l'observateur sélectionné comme déterminateur par défaut. */
    var observateurDefautId: String
        get() = prefs.getString("gn_obs_defaut_id", "") ?: ""
        set(v) = prefs.edit().putString("gn_obs_defaut_id", v).apply()

    /** Cache JSON des datasets chargés depuis le serveur (List<GeoNatureDataset>). */
    var datasetsCacheJson: String
        get() = prefs.getString("gn_cache_datasets", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_datasets", v).apply()

    /** Cache JSON des listes de taxons chargées (List<GeoNatureListe>). */
    var listesCacheJson: String
        get() = prefs.getString("gn_cache_listes", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_listes", v).apply()

    /** IDs des jeux de données CRÉABLES en Occtax (CRUVED C) — pour ne proposer en saisie que
     *  ceux-là, comme le web. Vide ⇒ pas de restriction (filet : offline / endpoint indisponible). */
    var datasetsCreablesOcctax: Set<Int>
        get() = (prefs.getString("gn_cache_ds_creables", "") ?: "")
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        set(v) = prefs.edit().putString("gn_cache_ds_creables", v.joinToString(",")).apply()

    /** Cache JSON des observateurs chargés (List<GeoNatureObservateur>). */
    var observateursCacheJson: String
        get() = prefs.getString("gn_cache_obs", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_obs", v).apply()

    /** Cache JSON des champs additionnels Occtax (List<AdditionalFieldDef>) — tous niveaux confondus. */
    var additionalFieldsOcctaxJson: String
        get() = prefs.getString("gn_cache_add_fields_occtax", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_add_fields_occtax", v).apply()

    /** Cache JSON de l'objet `nomenclature` du settings.json Occtax du serveur (sections
     *  information[]/counting[]), récupéré via /api/gn_commons/t_mobile_apps. Pilote la visibilité
     *  des champs standards de saisie (cf. [OcctaxFieldsConfig]). Vide ⇒ registre par défaut. */
    var settingsOcctaxJson: String
        get() = prefs.getString("gn_cache_settings_occtax", "") ?: ""
        set(v) = prefs.edit().putString("gn_cache_settings_occtax", v).apply()

    /** URL de base de TaxHub publiée par le serveur (`t_mobile_apps` OCCTAX → `settings.sync.taxhub_url`),
     *  rafraîchie à chaque synchro des settings. Vide ⇒ on déduit `<URL_GeoNature>/api/taxhub`. */
    var taxhubUrlCache: String
        get() = prefs.getString("gn_taxhub_url", "") ?: ""
        set(v) = prefs.edit().putString("gn_taxhub_url", v).apply()

    /** Base TaxHub RÉSOLUE pour tous les appels TaxRef/biblistes : l'URL publiée par le serveur si
     *  présente (gère les instances où TaxHub est hébergé ailleurs), sinon déduite de l'URL GeoNature.
     *  Sans slash final ; les appelants ajoutent `/api/taxref…` ou `/api/biblistes`. */
    val urlTaxhub: String
        get() = taxhubUrlCache.trim().trimEnd('/')
            .ifEmpty { urlServeur.trim().trimEnd('/') + "/api/taxhub" }

    /** Profondeur d'historique de l'Explorer, en jours (`settings.area_observation_duration` du
     *  serveur). Défaut 365 si non publié. */
    var dureeObservationJours: Int
        get() = prefs.getInt("gn_obs_duration_days", 365)
        set(v) = prefs.edit().putInt("gn_obs_duration_days", v).apply()

    /** Visibilité des champs du formulaire Occtax DU SERVEUR (`OCCTAX.form_fields`), en JSON brut.
     *  Vide = non publié ⇒ tout visible. Permet d'aligner la visibilité d'un champ sur le serveur
     *  (par instance), ex. masquer le type de regroupement si le serveur le masque. */
    var formFieldsJson: String
        get() = prefs.getString("gn_form_fields", "") ?: ""
        set(v) = prefs.edit().putString("gn_form_fields", v).apply()

    /** Le champ de formulaire serveur [key] (clé de `form_fields`, ex. `group_type`) est-il visible ?
     *  **true par défaut** si la clé est absente ou la config non publiée — on ne masque jamais un
     *  champ faute d'information (pas de régression sur un serveur qui n'expose pas `form_fields`). */
    fun champFormVisible(key: String): Boolean = champFormVisible(formFieldsJson, key)

    /** Taille de page pour la pagination TaxRef (`settings.sync.page_size` du serveur). Défaut 1000. */
    var taxrefPageSize: Int
        get() = prefs.getInt("gn_taxref_page_size", 1000)
        set(v) = prefs.edit().putInt("gn_taxref_page_size", v).apply()

    /** Saisie de l'HEURE du relevé activée (`settings.input.date.enable_hours`). Défaut false. */
    var dateAvecHeures: Boolean
        get() = prefs.getBoolean("gn_date_hours", false)
        set(v) = prefs.edit().putBoolean("gn_date_hours", v).apply()

    /** Saisie d'une DATE DE FIN du relevé activée (`settings.input.date.enable_end_date`). Défaut false. */
    var dateAvecFin: Boolean
        get() = prefs.getBoolean("gn_date_end", false)
        set(v) = prefs.edit().putBoolean("gn_date_end", v).apply()

    /** Heures du relevé proposées — pilotées par `form_fields.hour_min` (comme le web Occtax), avec
     *  repli sur le réglage mobile `settings.input.date.enable_hours` si le serveur n'expose pas la clé. */
    val heuresVisibles: Boolean get() = champFormVisible("hour_min") && (formFieldsJson.isNotBlank() || dateAvecHeures)

    /** Date de FIN du relevé proposée (le « + » du web) — pilotée par `form_fields.date_max`, avec
     *  repli sur `settings.input.date.enable_end_date` si le serveur n'expose pas form_fields. */
    val dateFinVisible: Boolean get() = champFormVisible("date_max") && (formFieldsJson.isNotBlank() || dateAvecFin)

    /** Cache des champs additionnels Occtax filtré par le flag serveur `additional_fields` du
     *  settings : renvoie "" (donc aucun champ additionnel rendu) si le serveur les désactive. À
     *  utiliser dans tous les contextes d'AFFICHAGE des champs additionnels. */
    val additionalFieldsOcctaxJsonActif: String
        get() = if (OcctaxFieldsConfig.afficherChampsAdditionnels(settingsOcctaxJson)) additionalFieldsOcctaxJson else ""

    /** false quand le dernier test de connexion a détecté une version GeoNature inférieure
     *  à la minimale supportée ([fr.ariegenature.geomys.network.VERSION_GEONATURE_MINIMALE]).
     *  Invalide [connexionConfiguree] — donc toutes les opérations réseau (envois, Explorer,
     *  recherche TaxRef live…) — jusqu'à un nouveau test réussi contre un serveur à jour.
     *  true par défaut : bénéfice du doute quand la version n'est pas détectable. */
    var serveurCompatible: Boolean
        get() = prefs.getBoolean("gn_serveur_compatible", true)
        set(v) = prefs.edit().putBoolean("gn_serveur_compatible", v).apply()

    val connexionConfiguree: Boolean
        get() = urlServeur.trim().isNotEmpty() && login.trim().isNotEmpty() && motDePasse.isNotEmpty() &&
            serveurCompatible

    val estConfiguree: Boolean
        get() = connexionConfiguree && idDataset.trim().isNotEmpty()

    /** Le jeu de données configuré existe-t-il dans le cache des datasets du serveur courant ?
     *  Un id_dataset hérité d'un autre serveur (ex. après changement d'URL) part en clé
     *  étrangère invalide → 500 opaque côté GeoNature au lieu d'une vraie erreur de validation.
     *  Si le cache est vide (datasets jamais synchronisés), on ne peut pas trancher → on
     *  considère valide pour ne pas bloquer à tort un utilisateur en cours de configuration. */
    val datasetValide: Boolean
        get() {
            val id = idDataset.trim().toIntOrNull()?.takeIf { it > 0 } ?: return false
            val json = datasetsCacheJson.takeIf { it.isNotEmpty() } ?: return true
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).any { arr.getJSONObject(it).optInt("id", -1) == id }
            } catch (_: Exception) {
                true
            }
        }

    /** Config complète ET cohérente avec le serveur courant : pré-requis pour démarrer ou
     *  envoyer une saisie (connexion + jeu de données réellement présent côté serveur). */
    val saisiePossible: Boolean
        get() = estConfiguree && datasetValide

    /** Un id est-il présent dans un cache JSON (liste d'objets) sous la clé [champ] ?
     *  Renvoie false si le cache est vide/illisible OU si l'id est absent — interprétation
     *  STRICTE (à l'inverse de [datasetValide], permissif) : sert à n'AUTORISER une saisie que
     *  lorsqu'on a la preuve que la sélection existe sur le serveur courant. */
    private fun idPresentDansCache(json: String, id: Int?, champ: String): Boolean {
        if (id == null || id <= 0) return false
        val j = json.takeIf { it.isNotEmpty() } ?: return false
        return try {
            val arr = org.json.JSONArray(j)
            (0 until arr.length()).any { arr.getJSONObject(it).optInt(champ, -1) == id }
        } catch (_: Exception) {
            false
        }
    }


    /** Le jeu de données sélectionné est présent dans le cache ET marqué ACTIF (`actif=true`).
     *  Un dataset archivé/inactif sur le serveur (cf. `actif:false`) reste présent dans le cache
     *  mais NE doit PAS valider la config : une saisie y partirait dans le vide (invisible dans
     *  l'interface web, masquée par défaut). `actif` absent du cache → permissif (true). */
    val datasetActifDansCache: Boolean
        get() {
            val id = idDataset.trim().toIntOrNull()?.takeIf { it > 0 } ?: return false
            val j = datasetsCacheJson.takeIf { it.isNotEmpty() } ?: return false
            return try {
                val arr = org.json.JSONArray(j)
                (0 until arr.length()).any {
                    val o = arr.getJSONObject(it)
                    o.optInt("id", -1) == id && o.optBoolean("actif", true)
                }
            } catch (_: Exception) {
                false
            }
        }

    /** Le jeu de données [id] est-il acceptable pour un envoi ? Permissif si le cache datasets est
     *  vide (on ne peut pas trancher → on n'empêche pas l'envoi), strict sinon. Sert à valider un
     *  override de relevé (« Détails du relevé ») AVANT le POST, pour éviter le 500 opaque sur FK
     *  invalide — même garde que [datasetValide], mais pour un id arbitraire. */
    fun datasetAcceptablePourEnvoi(id: Int): Boolean {
        if (datasetsCacheJson.isEmpty()) return true
        return idPresentDansCache(datasetsCacheJson, id, "id")
    }

    /** La liste de taxons sélectionnée est présente dans le cache des listes. */
    val listePresenteDansCache: Boolean
        get() = idPresentDansCache(listesCacheJson, taxaListeId.trim().toIntOrNull(), "id")

    /** L'observateur (déterminateur) par défaut est présent dans le cache des observateurs. */
    val observateurPresentDansCache: Boolean
        get() = idPresentDansCache(observateursCacheJson, observateurDefautId.trim().toIntOrNull(), "idRole")

    /** Saisie OCCTAX (multi/mono) réellement réalisable : connexion configurée ET les trois
     *  sélections (jeu de données, liste de taxons, observateur par défaut) sont chargées dans
     *  les caches du serveur courant. Faux tant que les caches sont vides (rien synchronisé) ou
     *  qu'une sélection est fantôme — pilote l'activation des boutons de saisie sur l'accueil. */
    val saisieOcctaxValide: Boolean
        get() = connexionConfiguree && datasetActifDansCache &&
            listePresenteDansCache && observateurPresentDansCache

    companion object {
        // Mot de passe conservé EN MÉMOIRE uniquement quand le chiffrement (Keystore) est
        // indisponible — jamais persisté en clair sur disque. Process-wide car GeoNatureConfig
        // est ré-instancié à chaque usage ; perdu au redémarrage du process (re-saisie).
        @Volatile private var motDePasseMemoire: String? = null

        @Volatile private var securePrefsCache: SharedPreferences? = null
        @Volatile private var secureInitTente = false

        /** EncryptedSharedPreferences partagé (créé une seule fois au niveau process). Renvoie null
         *  si le Keystore est indisponible — sans retenter à chaque appel. Évite de refaire la
         *  création coûteuse (MasterKey + Keystore AES) à chaque `GeoNatureConfig(context)`. */
        private fun securePrefs(appContext: Context): SharedPreferences? {
            securePrefsCache?.let { return it }
            synchronized(this) {
                securePrefsCache?.let { return it }
                if (secureInitTente) return null
                secureInitTente = true
                return try {
                    val masterKey = MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        appContext, "gn_secure", masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    ).also { securePrefsCache = it }
                } catch (e: Exception) {
                    Log.w("GeoNatureConfig", "EncryptedSharedPreferences indisponible — mot de passe gardé en mémoire seulement", e)
                    null
                }
            }
        }
    }
}