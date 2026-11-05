# Notes : spécimens en collection (issue #54)

Analyse de l'issue [#54 « Module de gestion/saisie des spécimens en collection »](https://github.com/ANA-CEN-Ariege/GeoMys-android/issues/54), faite le 2026-11-05. Elle a été révisée après une relecture critique (code GeoNature, issue, standards des collections).

La note détaillée côté serveur est `docs/collections-specimens.md` dans le fork GeoNature (cedricroy/GeoNature). Elle couvre l'architecture, les tables, les champs Darwin Core, les noms hors Taxref et les phases.

## Le besoin

Il porte sur les invertébrés conservés en collection (et les herbiers) et comporte deux volets.

1. **Saisie assistée à partir des étiquettes.**
   - On photographie une étiquette ou un lot d'étiquettes (imprimées, manuscrites ou les deux).
   - Les champs reconnus se remplissent seuls : station, commune, lieu-dit, GPS, date, observateur, taxon.
   - On corrige ou on complète ce qui n'a pas été reconnu.
   - Le gros du travail se fait **au bureau**, sur des boîtes entières, pas seulement sur le terrain.
2. **Production d'étiquettes.**
   - On choisit une session (sortie) et un type d'étiquette : station / station + taxon / station + taxon + dénombrement.
   - On exporte un tableur, que l'utilisateur relie ensuite à un modèle d'étiquettes Word (publipostage).
   - Le tableur contient l'identifiant des données, pour faire le lien entre l'étiquette et la donnée naturaliste.

Précisions apportées ensuite : la saisie doit aussi être possible **en web**, la collecte peut venir d'une observation **opportuniste comme d'un suivi**, et les herbiers peuvent porter des **noms absents de Taxref**.

## Le module GeoNature `gn_module_individuals` peut-il y répondre ? Non

Branches `master` (0.0.1) et `develop` de [PnX-SI/gn_module_individuals](https://github.com/PnX-SI/gn_module_individuals), lues en novembre 2026.

- **Objet** : le suivi d'**animaux vivants identifiés individuellement**, par capture-marquage-recapture, balises GPS/GSM/VHF ou marques couleur (bouquetins, tétras-lyre).
- **Tables** :
  - `gn_individual.t_tracking_devices` et `gn_individual.t_individual_deployments` (module) ;
  - `gn_monitoring.t_individuals` et `t_marking_events` (cœur GeoNature).
- La branche `develop` dépend de colonnes `id_individual` ajoutées dans GeoNature amont, **absentes du fork** cedricroy/GeoNature.
- Il n'y a **rien** sur les spécimens, les lots, les étiquettes, les numéros de collection, la lecture de photos ou l'export d'étiquettes.

Le détourner (un spécimen = un « individu », une étiquette = un « dispositif ») serait un contresens.

## Ce qui existe déjà et qu'on peut réutiliser

- **Champs de preuve Occtax** :
  - `id_nomenclature_exist_proof` (`PREUVE_EXIST`) : **« Oui » (code `1`)** veut dire qu'une preuve existe. La nomenclature ne dit pas si elle est numérique ou physique ;
  - `non_digital_proof` : preuve physique (institution, collection) ;
  - `sample_number_proof` : **n° du spécimen**. L'API Occtax l'accepte ; le formulaire web d'Occtax ne l'affiche pas ;
  - `digital_proof` : URL d'une photo.
- **GeoMys envoie déjà** `digital_proof` et `non_digital_proof` (`network/GeoNatureUpload.kt`, `ui/CaracterisationFragment.kt`). Il **n'envoie pas** `sample_number_proof`.
- **UUID** :
  - dans la Synthèse, `unique_id_sinp` = UUID du **dénombrement** (`unique_id_sinp_occtax`), et `unique_id_sinp_grp` = UUID du relevé ;
  - l'UUID d'occurrence (`uuidOccurrence`, `store/SortieStore.kt`) **n'arrive pas dans la Synthèse** et n'est pas unique côté GeoNature. Il ne convient pas pour une étiquette ;
  - aujourd'hui GeoMys **n'envoie pas** `unique_id_sinp_occtax` : c'est le serveur qui le crée (voir le commentaire dans `network/EnvoiSortie.kt`).
- **`FileProvider`** est déjà déclaré dans `AndroidManifest.xml`, ce qui permet de partager un fichier exporté.

## Approche recommandée côté GeoMys

### Phase 1 : identifiants et export d'étiquettes

C'est le plus simple et le plus utile tout de suite.

- **Générer `unique_id_sinp_occtax` dans l'appli** pour chaque dénombrement, à la création, et l'envoyer. L'API Occtax l'accepte (`CountingSchema`).
  - C'est indispensable pour imprimer l'étiquette **avant** l'envoi avec l'UUID qu'on retrouvera dans la Synthèse.
  - Effet de bord utile : la contrainte d'unicité côté serveur bloque enfin les doublons d'envoi signalés dans `EnvoiSortie.kt`.
- Dans le détail d'une sortie, une action **« Exporter des étiquettes »** propose trois types :
  - `STATION` : une ligne par relevé avec commune, lieu-dit, lat/lon WGS84, altitude, date, `leg.`, `uuid_releve` (= `unique_id_sinp_grp`) ;
  - `STATION_TAXON` : une ligne par dénombrement, avec en plus nom scientifique, rang, `det.`, `uuid_denombrement` (= `unique_id_sinp_occtax`) ;
  - `STATION_TAXON_DENOMBREMENT` : idem, avec en plus stade, sexe, effectif.
- Format : **CSV UTF-8 avec BOM, séparateur `;`**. Il s'ouvre directement dans Excel et sert de source au publipostage Word. Pas besoin de dépendance comme Apache POI.
- Le fichier est partagé par `ACTION_SEND` via le FileProvider.
- Une colonne `qr_payload` permet d'imprimer un QR code. Elle contient l'UUID du dénombrement, et plus tard l'URI du spécimen quand le module Collections existera.
- Mise en page entomologique à documenter avec le modèle Word : petit corps (4 à 5 pt), une information par ligne, coordonnées décimales.
- Sur le terrain, une case **« spécimen collecté »** sur l'occurrence, disponible dans **tous les modes de saisie** (opportuniste et protocoles Monitoring) :
  - met `PREUVE_EXIST` à **Oui (code 1)** ;
  - remplit `sample_number_proof` avec un code de spécimen (préfixe configurable + n° incrémental, ou UUID court) ;
  - et `non_digital_proof` avec la collection de destination.

### Phase 2 : saisie à partir d'une photo d'étiquette

- La lecture du texte se fait **sur le téléphone, sans réseau**, avec Google ML Kit Text Recognition (modèle latin intégré).
  - Limite : l'écriture manuscrite sera mal reconnue. L'utilisateur la corrige à la main.
  - Pour les gros volumes au bureau, la note serveur prévoit en complément une lecture côté serveur et une file de transcription dans le module web.
- Le texte reconnu est analysé pour repérer :
  - les dates (`12.VI.2024`, `12/06/2024`, mois en chiffres romains) ;
  - les coordonnées (degrés décimaux, DMS, Lambert) et l'altitude (`1250 m`) ;
  - la commune, cherchée dans le référentiel en cache ;
  - les mentions `leg.` et `det.` pour l'observateur et le déterminateur ;
  - le taxon, cherché dans le cache Taxref local.
- Plusieurs étiquettes par épingle : on photographie les étiquettes étalées, puis on associe l'étiquette station et l'étiquette identité. Une étiquette station déjà lue est réutilisée pour les spécimens suivants (mode multi-taxons).
- Écran « Nouvelle saisie depuis étiquette » : photo(s) → lecture → formulaire de relevé et d'occurrence pré-rempli, avec les champs douteux surlignés → validation.
- La photo de l'étiquette est jointe en média (`TYPE_MEDIA`) pour garder une trace.
- Nom lu absent de Taxref : on conserve le nom lu tel quel dans `nom_cite` et on **propose** sans l'imposer le rattachement le plus proche (synonyme ou genre).
- Si on scanne une étiquette produite en phase 1 (QR ou UUID), on retrouve directement la donnée existante.

## Résumé de l'architecture côté serveur

Voir `docs/collections-specimens.md` (cedricroy/GeoNature) pour le détail.

- Un **module Collections transversal avec sa propre saisie web**, pas un sous-module Monitoring : un spécimen peut venir d'une observation opportuniste comme d'un suivi. Occtax et Monitoring ne sont pas modifiés.
- **Rattachement** recommandé : à `synthese.unique_id_sinp`, comme `t_validations` (clé étrangère vers la Synthèse). Une colonne directe façon `id_individual` est une alternative à discuter en amont.
- Chaque spécimen a **son propre identifiant**, sous forme d'URI résolvable (modèle CETAF), imprimée en QR code. L'UUID du dénombrement identifie la donnée, pas chacun des spécimens qu'elle regroupe.
- Comment un spécimen entre dans le module :
  - **observation opportuniste ou suivi** (Occtax, Monitoring, GeoMys) : `PREUVE_EXIST` = Oui + code du spécimen. L'observation apparaît dans une **liste de travail** web, où l'on complète la fiche spécimen ;
  - **herbier ancien ou collection reçue** : saisie complète dans le module web. L'observation est créée dans un **Occtax dupliqué `COLLECTIONS`**, avec sa propre source dans la Synthèse et ses jeux de données ;
  - **rattachement après coup** à une observation existante, par recherche dans la Synthèse.
- **Déterminations** : seule la détermination retenue met à jour l'observation. Si plusieurs spécimens d'un même dénombrement divergent, on découpe le dénombrement.
- **Champs Darwin Core 2023** : `catalogNumber`, `preparations`, `typeStatus`, `verbatimLabel`, `verbatimIdentification`, `recordedBy`, `recordNumber`… et historique des déterminations sur le modèle de l'extension Identification History.
- Plus tard : lots mixtes, prêts, duplicata, sous-échantillons ADN, permis (APA/Nagoya), exports DwC-A et e-ReColNat.

## Noms absents de Taxref

- Dans GeoNature, une donnée sans `cd_nom` est **invisible** dans la liste, la carte et l'export de la Synthèse, et **refusée** par l'Import. On ne peut pas laisser le `cd_nom` vide.
- Démarche :
  1. chercher parmi les synonymes Taxref (beaucoup de noms anciens y sont) ;
  2. sinon, rattacher au rang supérieur (genre) et garder le nom exact dans `nom_cite`. La sensibilité et la validation se feront alors au niveau du genre ;
  3. demander l'ajout à Taxref (MNHN / INPN) et corriger le `cd_nom` ensuite ;
  4. spécimens étrangers, cultivars, hybrides horticoles : ils restent dans le module Collections seulement, avec un lien éventuel vers WFO ou GBIF.
- À éviter : inventer des `cd_nom` locaux dans `taxonomie.taxref` (conflits à la mise à jour Taxref, inconnus de l'INPN).
- **Côté GeoMys, sur le terrain, rien ne change** : on choisit toujours un taxon Taxref, au besoin au genre. Le problème concerne surtout la saisie de collections et d'herbiers anciens.

## À valider avec le demandeur

- Récupérer des **modèles d'étiquettes réels** (photos, plus le fichier Excel/Word de publipostage actuel). Ils serviront à figer les colonnes de l'export et à tester la lecture des photos.
- Format du code de spécimen (préfixe, numérotation) et collection de destination par défaut.
- Faut-il aussi des étiquettes de détermination (`det.`) depuis GeoMys ?

## Vérification prévue

- **Phase 1** :
  - test unitaire : un dénombrement créé dans l'appli garde le même `unique_id_sinp_occtax` après envoi (comparer avec la Synthèse) ;
  - test d'un double envoi : il est refusé par le serveur grâce à la contrainte d'unicité ;
  - tests unitaires du générateur CSV pour les 3 types (accents, `;` dans les valeurs, UUID) ;
  - ouverture du fichier dans Excel et LibreOffice ;
  - publipostage Word de test.
- **Phase 2** :
  - tests unitaires de l'analyse du texte sur un ensemble de textes d'étiquettes réelles ;
  - test manuel sur appareil, sans réseau.
