# GeoNat Android

Application Android de terrain pour la saisie naturaliste connectée à un serveur [GeoNature](https://github.com/PnX-SI/GeoNature) — observations libres (OccTax) **et** suivis protocolés (gn_module_monitoring), en ligne comme hors-ligne.

Développée par l'[ANA - CEN Ariège](https://ariegenature.fr/).

👉 **Mode d'emploi utilisateur** : voir [`MODE_EMPLOI.md`](MODE_EMPLOI.md) pour la prise en main, les workflows terrain et les cas particuliers.

## Fonctionnalités principales

### Saisie libre (OccTax)
- **Suivi GPS** en arrière-plan via service foreground, tracé du parcours sur carte.
- **Saisie multi-taxons** géolocalisée : un relevé peut contenir plusieurs occurrences (taxons, dénombrements, médias). Géométries point / ligne / polygone au doigt sur la carte. À la réédition d'une sortie, les lignes et polygones sont redessinés et leurs sommets restent **déplaçables** (un tap sur la forme ouvre la liste des espèces du relevé). Après validation d'un relevé, la carte repasse **directement en mode positionnement** pour enchaîner le relevé suivant.
- **Saisie rapide mono-taxon** : ouverture instantanée, photo + taxon + envoi en quelques tap. Si le serveur déclare des **champs additionnels de relevé** (OCCTAX_RELEVE), l'écran « Détails du relevé » est intercalé une fois à l'entrée de la saisie (comme en multi-taxons) : ces valeurs sont saisies une seule fois et appliquées à toutes les observations de la session (chacune devenant un relevé séparé côté serveur). Elles restent **éditables** ensuite via le bouton « Détails du relevé » du panneau de configuration (même dialog qu'en multi-taxons).
- **Bandeau de navigation** « 🏠 › Saisie mono-taxons / multi-taxons » présent sur tous les écrans de chaque flux de saisie (icône maison cliquable → retour à l'accueil), à l'image du fil d'Ariane des suivis.
- **Autocomplétion TaxRef** (noms vernaculaires et scientifiques) avec cache local.
- **Export GPX** des sorties enregistrées.
- **Envoi GeoNature** : POST des relevés OccTax avec occurrences, médias et géométries.

### Suivis protocolés (`gn_module_monitoring`)
- **100 % schema-driven** : aucun protocole en dur. L'app parse `/api/monitorings/config/<module>` et construit dynamiquement formulaires, fils d'Ariane et navigations selon l'arborescence du protocole (`sites_group → site → visit → observation`, `zone → station → point`, etc.).
- **Form renderer dynamique** : 11 widgets supportés — TEXT, TEXTAREA, NUMBER, DATE, TIME, CHECKBOX, SELECT, SELECT_MULTIPLE, RADIO, TAXON, MEDIA (photo single-file). Pré-remplissage des valeurs par défaut serveur, masquage conditionnel via `hidden_expr` Angular-like, auto-remplissage de champs dépendants via les règles `change` du schéma. Les champs **date / heure / datetime** sans défaut serveur sont initialisés à la **date du jour et l'heure actuelle** (le défaut serveur reste prioritaire ; en édition les valeurs saisies sont conservées) — vaut pour les formulaires monitoring comme pour les champs additionnels OccTax.
- **Photos rattachées aux objets monitoring** : sélection d'une photo via le picker système ; upload vers `gn_commons` après création de l'objet (uuid pré-généré côté client comme `uuid_attached_row`, id_table_location résolu depuis le `schema_dot_table` du champ).
- **Datalists fetchées à la volée** depuis les endpoints déclarés par le schéma (observateurs UsersHub, nomenclatures, datasets…).
- **Filtrage CRUVED** : la liste UI, le cache disque et la synchronisation offline ne retiennent que les modules sur lesquels l'utilisateur authentifié a au moins un droit > 0 (parité gn_mobile_monitoring) — le bloc `cruved` retourné par `/api/monitorings/modules` est appliqué dès le parsing, avant toute autre opération ou réécriture cache.
- **Carte interactive** : sur la carte d'un protocole ou d'un site, un tap sur un marker / polyline / polygone ouvre un dialog qui affiche le nom de l'objet et propose les actions disponibles selon le schéma (voir la fiche, démarrer une nouvelle saisie). Fonctionne aussi bien sur les sites macro vus depuis la liste des protocoles que sur les points d'écoute / sous-objets vus depuis la carte d'un site.
- **Restriction des taxons** au `id_list_taxonomy` du protocole (ou du dataset associé).
- **Validation** : le bouton Enregistrer est désactivé tant que les champs obligatoires visibles ne sont pas remplis OU qu'un champ numérique viole ses bornes `min`/`max` (littérales ou pointant vers un autre champ via `(value) => value.<champ>`, message d'erreur inline sous le champ).

### Mode offline complet
- **Outbox local** des saisies monitoring (visites + observations) : stockage JSON write-through, lien parent → enfant via UUID local quand le parent n'a pas encore d'id serveur.
- **Envoi à la demande** uniquement (jamais automatique) — écran « Mes visites » avec un fil d'Ariane par groupe (`Site : Forêt de Foix › Station : Point Foix-Nord`), édition / suppression cascade / envoi par groupe.
- **Cache des fonds de cartes** : écran « Maps Manager » qui télécharge une zone (jusqu'à 200 km², zoom 17) pour un usage terrain sans réseau. Plafond 1 Go avec purge LRU automatique. Cible un protocole pour cadrer + afficher les géométries de ses sites macro.
- **Cache des fiches monitoring** : modules, schémas, fiches d'objets et listes d'enfants conservés en local pour drill-down offline.

### Cartographie
Quatre fonds disponibles, basculables au tap :
- **OSM** (OpenStreetMap)
- **IGN Topo** (PLANIGNV2)
- **IGN Scan25**
- **IGN Ortho** (orthophotos)

Le dernier fond choisi est **mémorisé** et réappliqué à l'ouverture de n'importe quelle carte (préférence partagée entre tous les écrans).

**Position courante & GPS externe** : la carte « Explorer » s'abonne au GPS en direct et se recentre automatiquement sur la position dès le premier point reçu (le bouton « Centrer » suit la position live). L'app lit le `LocationManager` standard d'Android : un récepteur externe (ex. **RTK** relayé par **SW Maps** en « position fictive » / mock location) est donc utilisé de façon transparente, sans configuration côté app.

## Limitations connues

Volontairement hors périmètre à ce stade. Aucune n'est un bug : les fonctionnalités absentes dégradent proprement (un champ non porté reste éditable en texte plutôt que de disparaître), et le périmètre cible du monitoring reste **consultation + visites + saisies sur sites existants** — pas de création de sites.

- **Saisie de géométrie au formulaire monitoring (widget `geometry`)** — non portée. Les champs de type `geometry` (dessin d'un point / ligne / polygone sur carte, p. ex. pour créer un site ou tracer un transect) sont **dégradés en champ texte**. La *consultation* des géométries de sites existants, elle, est pleinement supportée (carte interactive, « Maps Manager »). Cohérent avec le périmètre « sites existants ».
- **Médias : une seule photo par objet** — l'upload vers `gn_commons` est en MVP *single-file* (OccTax comme monitoring). La pièce jointe multi-fichiers n'est pas encore implémentée.
- **Datalists TaxHub** — seules les listes alimentées par une application `GeoNature` sont chargées dynamiquement ; les datalists pointant vers **TaxHub** ne sont pas encore récupérées.
- **Dictée vocale en français uniquement** — la reconnaissance vocale (saisie OccTax) n'expose pas de choix de langue.
- **Pas de tests instrumentés UI** — la couverture automatique est solide côté logique (parsing schéma, payloads, flux réseau, offline) mais il n'y a pas de tests d'interface / fragments / services de localisation ; la validation de ces parcours reste manuelle.

## Configuration GeoNature

Écran de configuration (icône engrenage en haut à droite) :

| Paramètre | Description |
|-----------|-------------|
| URL du serveur | URL de base du serveur GeoNature (ex : `https://geonature.example.org`) |
| Identifiant / Mot de passe | Identifiants de connexion GeoNature |
| Jeu de données | Sélection dans la liste ou saisie de l'`id_dataset` |
| Liste de taxons | Liste TaxHub pour l'autocomplétion OccTax (`id_liste`) |

Le bouton **Tester** vérifie la connexion et charge automatiquement les datasets et listes disponibles. Le bouton **Recharger les données** télécharge le cache TaxRef + nomenclatures + datasets/listes/observateurs/champs additionnels + (pour le monitoring) modules / schémas / listes / fiches.

**Chargement en arrière-plan.** « Recharger les données » s'exécute dans un **service au premier plan** (`foregroundServiceType=dataSync`, notification de progression) : il **continue même si l'utilisateur quitte l'écran, met le téléphone en veille ou passe l'app en arrière-plan** — la synchro complète (souvent longue à cause de TaxRef) n'est plus interrompue. En revenant sur l'écran Config pendant l'opération, on retrouve la progression en cours ; le résultat (et un éventuel récapitulatif d'étapes en échec) s'affiche à la fin.

**Garde de cohérence config (écran d'accueil).** On ne peut démarrer une saisie que si la configuration est **complète et cohérente avec le serveur courant** : connexion renseignée **et** jeu de données, liste de taxons et observateur par défaut **réellement présents dans les caches** du serveur (et non hérités d'un autre serveur, ce qui provoquait un `HTTP 500` opaque côté GeoNature — violation de clé étrangère). Tant que ce n'est pas le cas :

- une **pastille rouge** s'affiche sur le bouton ⚙️ *Paramètres* (le point vert n'apparaît qu'une fois la config valide) ;
- les boutons **Saisie multi-taxons** et **mono-taxons** restent visibles mais **grisés/désactivés** (cache pas encore synchronisé, ou sélection devenue invalide) ;
- le bouton **Monitoring** n'apparaît que si l'utilisateur a des droits sur ≥ 1 protocole, et est lui aussi **désactivé** tant que la config n'est pas valide.

À l'envoi, si le jeu de données configuré est absent du serveur, l'erreur remontée est explicite (« jeu de données introuvable sur ce serveur ») au lieu d'une « erreur serveur ».

Un panneau **Données en cache** affiche en haut de la section le nombre de **protocoles**, **nomenclatures** et **taxons** (taxons *uniques*, c.-à-d. `cd_nom` distincts) actuellement disponibles localement. Pour les taxons, un bouton **Détails** ouvre un dialog listant **tout le cache regroupé par liste** : chaque liste avec son nombre de taxons (les listes rattachées à un protocole sont marquées « · protocole », et le titre rappelle le nombre de taxons couverts par des listes de protocoles) — un tap ouvre la liste détaillée des taxons de cette liste. Sous le champ **Liste**, une ligne d'info indique le nombre de taxons de la liste sélectionnée, ou un avertissement si elle est absente du cache **ou vide** (aucun taxon).

Le chargement récupère les taxons de **toutes les listes publiques** (`/biblistes`), des **listes imposées par les datasets**, et des **listes taxonomiques des protocoles** auxquels l'utilisateur a accès (filtré CRUVED) — y compris des listes « privées » non publiées. À la saisie, l'autocomplétion d'espèce est **strictement limitée à la liste sélectionnée** : une liste vide ne propose donc rien (plus de repli sur des suggestions embarquées).

Dans la saisie OccTax (multi et mono-taxons), le bouton **Détails** du relevé permet en outre d'**éditer le jeu de données et l'observateur** du relevé (en plus des éventuels champs additionnels) ; ces choix s'appliquent à toutes les observations de la session.

## Écran d'accueil

- **Saisie multi-taxons** — relevé OccTax complet.
- **Saisie mono-taxon** — saisie éclair.
- **Suivis** — accès aux protocoles `gn_module_monitoring`.
- Menu burger (**pastille rouge** sur le bouton dès qu'une saisie ou une visite reste à envoyer) :
  - **Mes saisies** — sorties GPX enregistrées (pastille rouge si des saisies sont en attente d'envoi). Chaque saisie en attente offre un bouton **Envoyer** (à côté d'Éditer/Supprimer) pour la transmettre individuellement au serveur.
  - **Mes visites** — saisies monitoring en attente d'envoi (pastille rouge si nécessaire).
  - **Explorer** — sorties passées sur carte.
  - **Maps Manager** — téléchargement de fonds offline.

  Chacun de ces écrans (Mes saisies, Mes visites, Explorer, Maps Manager) affiche le bandeau de navigation « 🏠 › <écran> » (icône maison cliquable → retour à l'accueil), comme les écrans de saisie.

## Architecture

```
app/src/main/java/fr/ariegenature/geonat/
├── GeoNatApplication.kt    # Init osmdroid, caches, outbox
├── TaxRefLocal.kt          # TaxRef embarqué (faune)
├── model/                  # Sortie, Observation, Taxon
├── network/                # GeoNatureUpload, MonitoringApi, OutboxEnvoi,
│                           # GeoNatureAuth, MonitoringSync, AdditionalFields
├── store/                  # GeoNatureConfig, TaxRefCache, NomenclatureCache,
│                           # MonitoringCache, OutboxMonitoring, SortieStore,
│                           # MapTileCache
├── monitoring/form/        # Form renderer dynamique (EditableField,
│                           # FormulaireRenderer, WidgetMapping, HiddenExpr,
│                           # ChangeRules, ValidationExpr)
├── location/               # LocationTracker, LocationForegroundService
├── gpx/                    # Export GPX
└── ui/                     # Fragments : Accueil, Trace, SaisieRapide,
                            # ConfigGeoNature, Suivis, SuiviDetail,
                            # FicheObjet, CarteGeometrie, NouvelleVisite,
                            # SaisiesEnAttente, CacheManager, Explorer, …
```

- **Min SDK :** 24 (Android 7.0)
- **Compile / Target SDK :** 36
- **Langage :** Kotlin (Java 11)
- **Architecture :** Fragment + Navigation Component, ViewBinding
- **Carte :** osmdroid 6.1.20 (fonds OSM + IGN via Géoportail)
- **Dépendances :** centralisées dans `gradle/libs.versions.toml`

## Endpoints serveur utilisés

| Endpoint | Usage |
|----------|-------|
| `/api/auth/login` | Authentification (Flask-JWT-Extended) |
| `/api/meta/datasets` | Liste des datasets (filtrable par module) |
| `/api/users/menu/<id>` | Membres d'une liste UsersHub |
| `/api/taxref/...` | Taxons (TaxHub) |
| `/api/occtax/releve` | Création d'un relevé OccTax |
| `/api/monitorings/modules` | Liste des protocoles |
| `/api/monitorings/config/<module>` | Schéma d'un protocole |
| `/api/monitorings/object/<module>/<type>[/<id>]` | Fiches + création d'objets |
| `/api/synthese/get_one_synthese/<id>` | Détail d'une obs synthèse |

## Build

```bash
./gradlew assembleDebug         # APK debug
./gradlew testDebugUnitTest     # tests unitaires (JVM, rapides)
./gradlew connectedAndroidTest  # tests instrumentés (device requis)
./gradlew lint                  # lint
```

Requiert Android Studio Hedgehog+ et JDK 11.

## Tests automatiques

Batterie de ~230 tests unitaires JVM (`app/src/test/`), exécutée via `./gradlew testDebugUnitTest`
(quelques secondes, sans émulateur). Couvre la logique pure, le parsing du schéma serveur,
la construction des payloads et les flux réseau (MockWebServer) :

- **Évaluateurs du form renderer monitoring** : `HiddenExprTest`, `ValidationExprTest`, `ChangeRulesTest`.
- **Mapping & parsing du schéma serveur** : `WidgetMappingTest` (`type_widget` → `ViewType`), `MonitoringApiParsingTest` (propriété /config, cruved, heuristiques nomenclature/taxref), `SchemaFusionTest` (fusion blocs `generic`+`specific`), `SubstituerVariablesModuleTest` (placeholders `__MODULE.XXX`), `AdditionalFieldsParsingTest` (cache champs additionnels), `AdditionalFieldsServerParsingTest` (JSON brut `/additional_fields`), `AdditionalFieldVisibiliteTest` (déclenchement « Détails du relevé » : niveau objet + dataset/liste), `ExtraireNomHeuristiqueTest`.
- **Payload serveur** : `ConstruireGeometrieTest` (GeoJSON Point/LineString/Polygon + fermeture d'anneau), `BuildOccurrenceTest` (occurrence OccTax : cd_nom, countings, résolution des id_nomenclature), `JsonDepuisMapTest` (typage des champs additionnels).
- **Lecture des objets serveur** : `AplatirProprietesTest` (properties → Map), `FormatGeometrieTest` (résumé lisible de géométrie), `MonitoringCacheLabelTest` (résolution offline des labels objet/type depuis le cache).
- **Import/export & réseau** : `GpxUtilsTest` (round-trip GPX), `HumaniserErreurReseauTest` (messages d'erreur), `EstTypeSaisieTest`.
- **Logique d'affichage par taxon** : `ChampsTaxonTest` (champs visibles + groupes/regno, factorisé dans `ChampsTaxon`).
- **Champs additionnels — détail** : `ExtraireDefautTest` (valeur par défaut serveur multi-formes), `PictoMonitoringTest` (picto protocole URL/image vs FontAwesome→emoji, factorisé dans `PictoMonitoring`).
- **Utilitaires** : `DateHeureDefautTest`, `FilArianeTest`, `FondCarteTest`.
- **Flux réseau (MockWebServer)** : `GeoNatureAuthTest` (auth /api/auth/login : token, id_role, cookies), `AdditionalFieldsChargerTest` (auth + GET additional_fields, 404→vide), `ChargerModulesTest` (liste protocoles + filtrage CRUVED, 403/404→vide), `GeoNatureUploadEnvoyerTest` (flux d'envoi OccTax complet : POST relevé → occurrence(s), multi-taxons, rollback sur échec, relevé orphelin).
- **Stockage / persistance (Robolectric)** : `SortieStoreTest` (sorties OccTax), `OutboxMonitoringTest` (file d'attente monitoring, compteur en attente, cascade), `FondCartePersistanceTest` (fond de carte mémorisé), `GeoNatureConfigTest` (config + état connexion), `NomenclatureCacheTest` (filtrage des valeurs par groupe/regno + défauts), `TaxRefCacheTest` (appartenance cd_nom→listes, comptes par groupe), `GroupesEtRegnoPlanteTest` (branche PLANTE dépendante du cache TaxRef).

> Le parsing serveur s'appuie sur `org.json`, seulement stubbé dans l'`android.jar` de test :
> une vraie implémentation est fournie côté test via `testImplementation("org.json:json:…")`.
> Les tests de stockage tournent sous **Robolectric** (`@Config(sdk=[34])`) pour disposer d'un
> vrai `Context`/SharedPreferences en JVM. Les flux réseau utilisent **MockWebServer** : l'URL
> de base pointe sur un serveur local, sans dépendance à une instance GeoNature réelle.

Ces tests sont lancés avant chaque release.

## Releases

Les APK debug sont publiés sur la page [Releases](https://github.com/ANA-CEN-Ariege/GeoNat-android/releases) à chaque version (`v0.X.Y`).

## Licence

Ce projet est distribué sous licence **GNU General Public License v3.0** (GPLv3).
Le texte complet de la licence est disponible dans le fichier [LICENSE](LICENSE).

© ANA - CEN Ariège.
