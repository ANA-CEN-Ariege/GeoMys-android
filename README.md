# GeoNat Android

Application Android de terrain pour la saisie naturaliste connectée à un serveur [GeoNature](https://github.com/PnX-SI/GeoNature) — observations libres (OccTax) **et** suivis protocolés (gn_module_monitoring), en ligne comme hors-ligne.

Développée par l'[ANA - CEN Ariège](https://ariegenature.fr/).

## Fonctionnalités principales

### Saisie libre (OccTax)
- **Suivi GPS** en arrière-plan via service foreground, tracé du parcours sur carte.
- **Saisie multi-taxons** géolocalisée : un relevé peut contenir plusieurs occurrences (taxons, dénombrements, médias). Géométries point / ligne / polygone au doigt sur la carte.
- **Saisie rapide mono-taxon** : ouverture instantanée, photo + taxon + envoi en quelques tap.
- **Autocomplétion TaxRef** (noms vernaculaires et scientifiques) avec cache local.
- **Export GPX** des sorties enregistrées.
- **Envoi GeoNature** : POST des relevés OccTax avec occurrences, médias et géométries.

### Suivis protocolés (`gn_module_monitoring`)
- **100 % schema-driven** : aucun protocole en dur. L'app parse `/api/monitorings/config/<module>` et construit dynamiquement formulaires, fils d'Ariane et navigations selon l'arborescence du protocole (`sites_group → site → visit → observation`, `zone → station → point`, etc.).
- **Form renderer dynamique** : 10 widgets supportés — TEXT, TEXTAREA, NUMBER, DATE, TIME, CHECKBOX, SELECT, SELECT_MULTIPLE, RADIO, TAXON. Pré-remplissage des valeurs par défaut serveur, masquage conditionnel via `hidden_expr` Angular-like, auto-remplissage de champs dépendants via les règles `change` du schéma.
- **Datalists fetchées à la volée** depuis les endpoints déclarés par le schéma (observateurs UsersHub, nomenclatures, datasets…).
- **Restriction des taxons** au `id_list_taxonomy` du protocole (ou du dataset associé).
- **Validation** : le bouton Enregistrer est désactivé tant que les champs obligatoires visibles ne sont pas remplis OU qu'un champ numérique viole ses bornes `min`/`max` (littérales ou pointant vers un autre champ via `(value) => value.<champ>`, message d'erreur inline sous le champ).

### Mode offline complet
- **Outbox local** des saisies monitoring (visites + observations) : stockage JSON write-through, lien parent → enfant via UUID local quand le parent n'a pas encore d'id serveur.
- **Envoi à la demande** uniquement (jamais automatique) — écran « Mes visites » avec un fil d'Ariane par groupe (`Site : Forêt de Foix › Station : Point Foix-Nord`), édition / suppression cascade / envoi par groupe.
- **Cache des fonds de cartes** : écran « Cache Manager » qui télécharge une zone (jusqu'à 200 km², zoom 17) pour un usage terrain sans réseau. Plafond 1 Go avec purge LRU automatique. Cible un protocole pour cadrer + afficher les géométries de ses sites macro.
- **Cache des fiches monitoring** : modules, schémas, fiches d'objets et listes d'enfants conservés en local pour drill-down offline.

### Cartographie
Quatre fonds disponibles, basculables au tap :
- **OSM** (OpenStreetMap)
- **IGN Topo** (PLANIGNV2)
- **IGN Scan25**
- **IGN Ortho** (orthophotos)

## Configuration GeoNature

Écran de configuration (icône engrenage en haut à droite) :

| Paramètre | Description |
|-----------|-------------|
| URL du serveur | URL de base du serveur GeoNature (ex : `https://geonature.example.org`) |
| Identifiant / Mot de passe | Identifiants de connexion GeoNature |
| Jeu de données | Sélection dans la liste ou saisie de l'`id_dataset` |
| Liste de taxons | Liste TaxHub pour l'autocomplétion OccTax (`id_liste`) |

Le bouton **Tester** vérifie la connexion et charge automatiquement les datasets et listes disponibles. Le bouton **Synchroniser** télécharge le cache TaxRef + nomenclatures + (pour le monitoring) modules / schémas / listes / fiches.

## Écran d'accueil

- **Saisie multi-taxons** — relevé OccTax complet.
- **Saisie mono-taxon** — saisie éclair.
- **Suivis** — accès aux protocoles `gn_module_monitoring`.
- Menu burger :
  - **Mes saisies** — sorties GPX enregistrées.
  - **Mes visites** — saisies monitoring en attente d'envoi.
  - **Explorer** — sorties passées sur carte.
  - **Cache Manager** — téléchargement de fonds offline.

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
./gradlew test                  # tests unitaires
./gradlew connectedAndroidTest  # tests instrumentés (device requis)
./gradlew lint                  # lint
```

Requiert Android Studio Hedgehog+ et JDK 11.

## Releases

Les APK debug sont publiés sur la page [Releases](https://github.com/ANA-CEN-Ariege/GeoNat-android/releases) à chaque version (`v0.X.Y`).

## Licence

© ANA - CEN Ariège — usage interne.
