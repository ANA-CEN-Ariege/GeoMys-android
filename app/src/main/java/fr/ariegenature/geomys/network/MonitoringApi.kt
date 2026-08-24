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

package fr.ariegenature.geomys.network

import fr.ariegenature.geomys.store.GeoNatureConfig
import fr.ariegenature.geomys.store.MonitoringCache

object MonitoringApi {

    /** Vide les caches mémoire process-wide (liste de modules + LabelResolver par module).
     *  À appeler quand l'URL/login/mdp serveur changent — sinon on continue à servir des
     *  modules et des résolutions de nomenclatures du serveur précédent. Le cache disque
     *  ([MonitoringCache]) n'est pas touché : il est versionné par module_code et reste
     *  valide tant que l'utilisateur ne change pas de serveur. */
    fun invaliderCaches() {
        MonitoringModules.invaliderCacheMemoire()
        MonitoringDatalists.invaliderCacheMemoire()
    }

    /** Voir [MonitoringModules.chargerModules]. */
    suspend fun chargerModules(config: GeoNatureConfig): List<MonitoringModule> =
        MonitoringModules.chargerModules(config)

    /** Un enfant (site, sites_group, …) d'un module monitoring : id technique + nom "best-effort"
     *  extrait à la volée + map plate des propriétés strings (pour ré-extraire le nom côté UI
     *  une fois le schéma chargé) + GeoJSON brut de sa géométrie quand l'API la renvoie au
     *  niveau enfant (utile pour superposer tous les enfants sur la carte d'un site). */
    data class MonitoringEnfant(
        val id: Int,
        val nom: String,
        val proprietes: Map<String, String>,
        val geometrieGeoJson: String? = null,
    )

    /** Schéma d'une propriété d'un object_type (un champ saisissable). Vient des blocs
     *  `generic`/`specific` côté serveur. */
    data class MonitoringPropertySchema(
        val nom: String,
        /** `text`, `textarea`, `date`, `time`, `number`, `select`, `radio`, `datalist`,
         *  `nomenclature`, `observers`, `dataset`, `medias`, `taxonomy`, `bool_checkbox`, … */
        val typeWidget: String,
        val label: String,
        val obligatoire: Boolean,
        /** Discriminateur sémantique : `user`, `nomenclature`, `dataset`, `taxonomy`, `types_site`.
         *  Utilisé pour résoudre les IDs en labels via [LabelResolver]. */
        val typeUtil: String? = null,
        /** Pour widget=`nomenclature` (forme ancienne) : code mnémonique du type de nomenclature. */
        val nomenclatureType: String? = null,
        /** Valeurs prédéfinies pour `select`/`radio` (value → label). Vide pour les datalists
         *  qui sont alimentés dynamiquement par appel API. */
        val valeurs: List<Pair<String, String>> = emptyList(),
        /** Pour widget=`datalist` : champs nécessaires au fetch des options. */
        val apiUrl: String? = null,
        val application: String? = null,
        val keyLabel: String? = null,
        val keyValue: String? = null,
        val dataPath: String? = null,
        val multiple: Boolean = false,
        /** Valeur par défaut scalaire (text/number/date) — `value` ou `default` simple. */
        val defaultValue: String? = null,
        /** Valeur par défaut sous forme objet pour nomenclature : `{cd_nomenclature: "18"}` ou
         *  `{label_default: "Imago"}`. Résolu en `id_nomenclature` côté UI via les options. */
        val defaultObjet: Map<String, String> = emptyMap(),
        /** Filtres déclarés pour restreindre les options renvoyées (datalist/nomenclature).
         *  Format : Map<champ_filtre → liste de valeurs acceptables>. Ex chronoventaire stade :
         *  `{"label_default": ["Inconnu", "Chrysalide", "Imago", "Chenille", "Œuf"]}`. */
        val filtres: Map<String, List<String>> = emptyMap(),
        /** Texte d'aide / tooltip déclaré dans le schéma (`definition`). Affiché sous le label
         *  dans le formulaire de saisie pour expliquer ce qu'on attend. */
        val definition: String? = null,
        /** Sur les propriétés `dataset` : code du module pour filtrer les jeux de données
         *  proposés au seul module pertinent. Sans ce filtre, on liste TOUS les datasets du
         *  serveur. */
        val moduleCodeFiltre: String? = null,
        /** Expression d'affichage conditionnel (clé `hidden` ou `display` du schéma serveur,
         *  format string interpolée Angular type `${champ_x}` ou `${champ_x} === 'val'`).
         *  Évaluée à la volée par le renderer pour masquer/afficher dynamiquement le champ
         *  en fonction des autres valeurs. Null = toujours visible. */
        val hiddenExpr: String? = null,
        /** Expression de caractère obligatoire DYNAMIQUE (clé `required` du schéma quand
         *  c'est une lambda `({value}) => …` et non un booléen — ex. champs végétation requis
         *  seulement au passage 2). Évaluée par le renderer contre les valeurs courantes ;
         *  null = se fier au booléen [MonitoringPropertySchema] `obligatoire`. */
        val obligatoireExpr: String? = null,
        /** `hidden: true` côté schéma serveur : champ technique caché à l'UI (id_base_visit,
         *  id_module, medias, nb_observations…). Inclus dans le payload POST avec valeur
         *  null sinon le serveur Marshmallow plante. */
        val hiddenBool: Boolean = false,
        /** Liste taxonomique propre à CE champ (lue depuis `id_list` ou l'api
         *  `taxref/allnamebylist/<id>`). Prime sur l'`idListTaxonomy` du module pour
         *  restreindre l'autocomplete TaxRef d'un champ taxonomie. */
        val idListTaxonomie: Int? = null,
        /** Borne minimale pour un champ numérique. Brut serveur car peut être un littéral
         *  (`"min": 0`) ou une expression `(value) => value.<autre_champ>` qui pointe vers
         *  un autre champ du formulaire. Résolu à la volée par [fr.ariegenature.geomys
         *  .monitoring.form.ValidationExpr] au moment de la validation. */
        val minValue: String? = null,
        /** Borne maximale (cf. [minValue]). Couple typique côté monitoring :
         *  `count_min` avec `max: "(value) => value.count_max"` pour forcer min ≤ max. */
        val maxValue: String? = null,
        /** Vrai si le champ a été déclaré dans le bloc `specific` de l'object_type côté
         *  schéma serveur (par opposition au bloc `generic` du modèle de base). Parité avec
         *  `isInSpecific` de gn_mobile_monitoring : un champ specific est **toujours inclus**
         *  dans le formulaire de saisie, même s'il n'est pas listé dans `display_form` /
         *  `display_properties`. C'est typiquement comme ça que les protocoles ajoutent leurs
         *  champs custom sans avoir à redéclarer tout le display_form. */
        val enSpecific: Boolean = false,
        /** Pour les widgets `medias` : table Postgres à laquelle le média est rattaché côté
         *  gn_commons (champ `schema_dot_table` du schéma serveur, ex.
         *  `gn_monitoring.t_base_visits`). Sert à résoudre l'id_table_location lors de
         *  l'upload du fichier via /api/gn_commons/media. */
        val schemaDotTable: String? = null,
        /** Pour le widget `dataset` du champ id_dataset : valeur `creatable_in_module` du
         *  schéma, format `"<module_code>.<code_object>"` (ex. `apollons.MONITORINGS_VISITES`
         *  pour le picker dataset d'une visite). Passée au backend via `?create=<value>`
         *  pour ne lister que les jeux où l'utilisateur connecté a le droit CRUVED `C` sur
         *  cet object — parité formulaire web monitoring. */
        val creatableInModule: String? = null,
    )

    /** Schéma d'un object_type déclaré par un protocole dans son `config/objects.json` serveur,
     *  tel que renvoyé par /api/monitorings/config/<module_code>. Tous les champs sont nullables :
     *  les vieux protocoles ou les protocoles minimalistes n'exposent pas forcément tout. */
    data class MonitoringSchemaObjet(
        val type: String,
        val label: String?,
        val labelList: String?,
        /** Nom du champ de `properties` à utiliser comme libellé d'une instance (par ex.
         *  `base_site_name` pour site, `sites_group_name` pour sites_group). Si présent, prime
         *  sur l'extraction heuristique. */
        val nameField: String?,
        /** object_type parent dans la hiérarchie. "module" pour les types directement attachés
         *  au protocole, ou null si non déclaré. */
        val parentType: String?,
        /** object_types enfants directs déclarés. Permet de savoir, pour `module`, quels types
         *  sont au niveau "macro" (= ce que l'utilisateur appelle "site"). */
        val childrenTypes: List<String>,
        /** Schéma des propriétés saisissables, indexé par nom. Vide si le protocole ne déclare
         *  pas de schéma de saisie (vieux protocole, ou type qui n'est qu'un container). */
        val properties: Map<String, MonitoringPropertySchema> = emptyMap(),
        /** Ordre d'affichage des propriétés dans une fiche ou un formulaire. Si vide, on affiche
         *  selon l'ordre d'insertion JSON de `properties`. */
        val displayProperties: List<String> = emptyList(),
        /** `display_form` : sous-ensemble + ordre des champs spécifiquement pour le FORMULAIRE
         *  de saisie. Prime sur `displayProperties` quand non vide (parité version web /
         *  gn_mobile_monitoring : display_form > display_properties). */
        val displayForm: List<String> = emptyList(),
        /** Règles `change` (tableau de lignes JS `({objForm, meta}) => { … patchValue({…}) }`)
         *  déclarées au niveau de l'object_type pour auto-remplir des champs dépendants.
         *  Brut — l'évaluation est faite par [fr.ariegenature.geomys.monitoring.form.ChangeRules]. */
        val changeRules: List<String> = emptyList(),
        /** Liste ordonnée des propriétés à afficher dans la vue LISTE de ce type (sous le nom).
         *  Ex chronoventaire site : `["base_site_name", "first_use_date", "last_visit", "nb_visits"]`.
         *  Séparé de `displayProperties` (qui est pour la fiche). */
        val displayList: List<String> = emptyList(),
        /** Critères de tri par défaut pour la liste de ce type. List<(prop, "asc"|"desc")>. */
        val sorts: List<Pair<String, String>> = emptyList(),
        /** Nom du champ qui sert d'identifiant technique pour ce type (ex: `id_base_site`,
         *  `id_dalle`, `id_sites_group`). Utilisé côté UI pour masquer le champ de sélection
         *  du parent dans le formulaire de création d'un enfant (le parent est connu par
         *  contexte de navigation). */
        val idFieldName: String? = null,
        /** Pour le type "module" uniquement : id de la liste d'observateurs déclarée. Sert à
         *  fetcher /api/users/menu/<id> pour résoudre les `id_role` en noms. */
        val idListObserver: Int? = null,
        /** Pour le type "module" uniquement : id de la liste taxonomique. */
        val idListTaxonomy: Int? = null,
        /** Type de géométrie déclaré par le schéma (`Point`, `Polygon`, `LineString`,
         *  `MultiPolygon`, …). Null si le type n'a pas de géométrie associée — dans ce cas
         *  le bouton "voir sur carte" est inutile et masqué. */
        val geometryType: String? = null,
        /** Nom du champ uuid de cet object_type (ex. `uuid_base_visit` pour les visites).
         *  Sert à pré-générer un uuid côté client et l'injecter dans le payload POST de
         *  création, pour pouvoir ensuite rattacher les médias gn_commons à cet uuid via
         *  `uuid_attached_row` sans avoir à reparser la réponse serveur. */
        val uuidFieldName: String? = null,
        /** Genre grammatical du label (`M` / `F`), tel que déclaré par le protocole.
         *  Utilisé côté UI pour formuler proprement « Édition de la visite » vs « Édition
         *  du passage » vs « Édition de l'observation ». Null = inconnu (heuristique
         *  voyelle initiale uniquement). */
        val genre: String? = null,
    )

    /** Cache des labels résolus depuis le serveur — permet de remplacer les IDs (id_role,
     *  id_nomenclature, id_dataset) par leurs labels au moment de l'affichage. */
    data class LabelResolver(
        /** code_nomenclature_type → (id_nomenclature → label_fr). */
        val nomenclatures: Map<String, Map<String, String>> = emptyMap(),
        /** id_role → nom_complet. */
        val users: Map<String, String> = emptyMap(),
        /** id_dataset → dataset_name. */
        val datasets: Map<String, String> = emptyMap(),
    ) {
        /** Résout l'ID d'une propriété en label si une correspondance existe. Retourne null si
         *  le type_util n'est pas géré ou si l'ID n'est pas trouvé. */
        fun resoudre(prop: MonitoringPropertySchema, valeur: String): String? {
            if (valeur.isEmpty() || valeur == "null") return null
            return when (prop.typeUtil) {
                "user" -> users[valeur]
                "nomenclature" -> prop.nomenclatureType?.let { nomenclatures[it]?.get(valeur) }
                "dataset" -> datasets[valeur]
                // Champ taxon (ex. `cd_nom`, type_util "taxonomy") : la valeur est un cd_nom.
                // On le résout en nom scientifique via le cache TaxRef (avec le vernaculaire
                // entre parenthèses s'il est connu), à l'image du web qui affiche `nom_vern,lb_nom`.
                // runCatching : si le cache TaxRef n'est pas initialisé/chargé, on retombe sur le
                // formatage brut (= numéro) côté appelant plutôt que de planter.
                "taxonomy" -> runCatching {
                    val cd = valeur.toIntOrNull() ?: return null
                    val sci = fr.ariegenature.geomys.store.TaxRefCache.entreesParCdNom()[cd]?.sciNom
                        ?: return null
                    val vern = fr.ariegenature.geomys.store.TaxRefCache.getVernaculaireParCdNom(cd)
                    if (vern != null) "$sci ($vern)" else sci
                }.getOrNull()
                else -> null
            }
        }
    }

    /** Voir [MonitoringObjets.chargerEnfants]. */
    suspend fun chargerEnfants(config: GeoNatureConfig, moduleCode: String): Map<String, List<MonitoringEnfant>> =
        MonitoringObjets.chargerEnfants(config, moduleCode)

    /** Un objet monitoring complet : type + id + propriétés plates + enfants directs (1 niveau).
     *  Sert pour les fiches site/visite/observation, toutes pilotées par le même renderer
     *  générique côté UI.
     *  - [geometrie] : libellé court formaté pour affichage (ex. "44.123°N, 1.456°E")
     *  - [geometrieGeoJson] : GeoJSON brut sérialisé pour rendu sur carte (osmdroid) */
    data class MonitoringObjet(
        val type: String,
        val id: Int,
        val moduleCode: String,
        val proprietes: Map<String, String>,
        val enfants: Map<String, List<MonitoringEnfant>>,
        val geometrie: String?,
        val geometrieGeoJson: String?,
    )

    /** Voir [MonitoringObjets.chargerObjet]. */
    suspend fun chargerObjet(
        config: GeoNatureConfig,
        moduleCode: String,
        objectType: String,
        id: Int,
    ): MonitoringObjet = MonitoringObjets.chargerObjet(config, moduleCode, objectType, id)

    /** Voir [MonitoringSchemas.chargerSchemaProtocole]. */
    suspend fun chargerSchemaProtocole(config: GeoNatureConfig, moduleCode: String): Map<String, MonitoringSchemaObjet>? =
        MonitoringSchemas.chargerSchemaProtocole(config, moduleCode)

    /** Une option de datalist fetchée depuis l'API serveur. `cdNomenclature`/`labelDefaut`
     *  permettent de résoudre les valeurs par défaut déclarées dans le schéma
     *  (`default: {cd_nomenclature: "18"}` ou `default: {label_default: "Imago"}`). */
    data class OptionDatalist(
        val value: String,
        val label: String,
        val cdNomenclature: String? = null,
        val labelDefaut: String? = null,
    )

    /** Voir [MonitoringDatalists.chargerOptionsDatalist]. */
    suspend fun chargerOptionsDatalist(
        config: GeoNatureConfig,
        prop: MonitoringPropertySchema,
    ): List<OptionDatalist>? = MonitoringDatalists.chargerOptionsDatalist(config, prop)

}
