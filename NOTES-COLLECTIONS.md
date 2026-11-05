# Notes : spécimens en collection (issue #54)

Analyse de l'issue [#54 « Module de gestion/saisie des spécimens en collection »](https://github.com/ANA-CEN-Ariege/GeoMys-android/issues/54), faite le 2026-11-05.

## Le besoin

Il porte sur les invertébrés conservés en collection et comporte deux volets.

1. **Saisie assistée à partir des étiquettes.**
   - On photographie une étiquette ou un lot d'étiquettes (imprimées, manuscrites ou les deux).
   - Les champs reconnus se remplissent seuls : station, commune, lieu-dit, GPS, date, observateur, taxon.
   - On corrige ou on complète ce qui n'a pas été reconnu.
2. **Production d'étiquettes.**
   - On choisit une session (sortie) et un type d'étiquette : station / station + taxon / station + taxon + dénombrement.
   - On exporte un tableur, que l'utilisateur relie ensuite à un modèle d'étiquettes Word (publipostage).
   - Le tableur contient les UUID des données, pour faire le lien entre l'étiquette et la donnée naturaliste.

## Le module GeoNature `gn_module_individuals` peut-il y répondre ? Non

Branches `master` (0.0.1) et `develop` de [PnX-SI/gn_module_individuals](https://github.com/PnX-SI/gn_module_individuals), lues en novembre 2026.

- **Objet** : le suivi d'**animaux vivants identifiés individuellement**, par capture-marquage-recapture, balises GPS/GSM/VHF ou marques couleur (bouquetins, tétras-lyre).
- **Tables** :
  - `gn_monitoring.t_individuals` : nom, cd_nom, sexe, actif, données additionnelles ;
  - `gn_individual.t_tracking_devices` : type de dispositif, fournisseur, code ;
  - `gn_individual.t_individual_deployments` : pose d'un dispositif ou d'une marque sur un individu, dates de pose et de retrait.
- Il est relié à Occtax et Monitoring pour les observations et recaptures.
- Il n'y a **rien** sur les spécimens, les lots, les étiquettes, les numéros de collection, la lecture de photos ou l'export d'étiquettes.
- Le module est jeune et demande GeoNature ≥ 2.16.

Le détourner (un spécimen = un « individu », une étiquette = un « dispositif ») serait un contresens.

## Ce qui existe déjà et qu'on peut réutiliser

- **Occtax (standard SINP)** :
  - `id_nomenclature_exist_proof` (`PREUVE_EXIST`) : la valeur « Non numérique » désigne un spécimen conservé ;
  - `non_digital_proof` : texte libre où mettre le **n° ou le code de spécimen et de collection** ;
  - `digital_proof` et les champs additionnels.
- **GeoMys envoie déjà ces champs** : `network/GeoNatureUpload.kt` (`digital_proof`, `non_digital_proof`) et `ui/CaracterisationFragment.kt`.
- **UUID stables créés dans l'appli** : on les imprimera sur les étiquettes.
  - `uuidOccurrence` (`store/SortieStore.kt`) ;
  - UUID de relevé groupé `uuidReleveGroupe` (`network/GeoNatureUpload.kt`, envoyé dans `unique_id_sinp_grp`).
- **`FileProvider`** est déjà déclaré dans `AndroidManifest.xml`, ce qui permet de partager un fichier exporté.

## Approche recommandée

### Phase 1 : export d'étiquettes depuis une sortie

C'est le plus simple et le plus utile tout de suite.

- Dans le détail d'une sortie, une action **« Exporter des étiquettes »** propose trois types :
  - `STATION` : une ligne par relevé avec commune, lieu-dit, lat/lon, altitude, date, observateurs, `uuid_releve` ;
  - `STATION_TAXON` : une ligne par occurrence, avec en plus nom scientifique, rang, déterminateur, `uuid_occurrence` ;
  - `STATION_TAXON_DENOMBREMENT` : une ligne par dénombrement, avec en plus stade, sexe, effectif, `uuid_denombrement`.
- Format : **CSV UTF-8 avec BOM, séparateur `;`**. Il s'ouvre directement dans Excel et sert de source au publipostage Word. Pas besoin de dépendance comme Apache POI.
- Le fichier est partagé par `ACTION_SEND` via le FileProvider.
- Option : une colonne `qr_payload` (UUID) pour imprimer un QR code sur l'étiquette.
- Sur le terrain, une case **« spécimen collecté »** sur l'occurrence :
  - met `PREUVE_EXIST` à « non numérique » ;
  - pré-remplit `non_digital_proof` avec un code de spécimen (préfixe configurable + n° incrémental, ou UUID court).

### Phase 2 : saisie à partir d'une photo d'étiquette

- La lecture du texte se fait **sur le téléphone, sans réseau**, avec Google ML Kit Text Recognition (modèle latin intégré).
  - Limite : l'écriture manuscrite sera mal reconnue. L'utilisateur la corrige à la main.
- Le texte reconnu est analysé pour repérer :
  - les dates (`12.VI.2024`, `12/06/2024`, mois en chiffres romains) ;
  - les coordonnées (degrés décimaux, DMS, Lambert) et l'altitude (`1250 m`) ;
  - la commune, cherchée dans le référentiel en cache ;
  - les mentions `leg.` et `det.` pour l'observateur et le déterminateur ;
  - le taxon, cherché dans le cache Taxref local.
- Écran « Nouvelle saisie depuis étiquette » : photo(s) → lecture → formulaire de relevé et d'occurrence pré-rempli, avec les champs douteux surlignés → validation.
- La photo de l'étiquette est jointe en média (`TYPE_MEDIA`) pour garder une trace.
- Saisie en lot : une étiquette « station » est réutilisée pour plusieurs étiquettes « taxon », sur la base du mode multi-taxons.
- Si on scanne une étiquette produite en phase 1 (QR ou UUID), on retrouve directement la donnée existante.

### Phase 3 (optionnelle, côté serveur) : gestion de collection

Seulement si le besoin dépasse « une preuve non numérique par occurrence » : prêts, rangement en boîte ou en tiroir, collections reçues d'autres structures, plusieurs spécimens par occurrence. Deux pistes :

- un module GeoNature `gn_module_collections`, sur le modèle de `gn_module_individuals`, avec une table `t_specimens` liée à `unique_id_occurence_occtax` (type de conservation, lieu de stockage, déterminations successives) ;
- ou proposer à PnX-SI d'étendre `individuals` à un objet « spécimen ».

Voir aussi la note côté serveur : `docs/collections-specimens.md` dans le fork GeoNature (cedricroy/GeoNature). Elle détaille l'architecture, les tables et les champs.

## Résumé de l'architecture côté serveur

- Le module Collections ne se greffe pas sur chaque module de saisie. Il s'accroche à l'**UUID SINP de l'observation** (`uuid_attached_row`), comme `t_medias` et `t_validations`. Il marche donc pour Occtax, Monitoring, Import et GeoMys.
- Il ajoute les lots/contenants, plusieurs spécimens par observation, l'historique des déterminations, le rangement et les prêts.
- Champs repris du **Darwin Core** :
  - collection : `catalog_number`, `preparation`, `type_status`, `disposition` ;
  - texte exact de l'étiquette : `verbatim_locality`, `verbatim_date`, `recorded_by`, `record_number`, `label_text`.
- Ce qui est écrit sur l'étiquette est toujours conservé tel quel, à côté de l'interprétation normalisée.
- C'est un **module transversal avec sa propre saisie web**, pas un sous-module Monitoring : un spécimen peut venir d'une observation opportuniste comme d'un suivi. Occtax et Monitoring ne sont pas modifiés.
- Comment un spécimen entre dans le module :
  - **observation opportuniste ou suivi** (Occtax, Monitoring, GeoMys) : on met « preuve non numérique » + code du spécimen. L'observation apparaît dans une **liste de travail** web, et on y complète la fiche spécimen ;
  - **herbier ancien ou collection reçue** : saisie complète dans le module web. Le module crée ensuite l'observation via l'API Occtax, dans un jeu de données propre à la collection ;
  - **rattachement après coup** à une observation existante, par recherche dans la Synthèse.
- Écrans web : liste et carte des spécimens, liste de travail, fiche spécimen (étiquette d'un côté, interprétation de l'autre, déterminations), saisie en lot, export d'étiquettes.
- **Pour GeoMys** : la case « spécimen collecté » doit être disponible dans **tous les modes de saisie**, opportuniste comme protocoles Monitoring. Elle suffit pour que la donnée remonte dans la liste de travail du module.

## Noms absents de Taxref

- Dans GeoNature, une donnée sans `cd_nom` est **invisible** dans la Synthèse (vues en `JOIN taxref`) et **refusée** par l'Import. On ne peut pas laisser le `cd_nom` vide.
- Démarche :
  1. chercher parmi les synonymes Taxref (beaucoup de noms anciens y sont) ;
  2. sinon, rattacher au rang supérieur (genre) et garder le nom exact dans `nom_cite` ;
  3. demander l'ajout à Taxref (MNHN / INPN) et corriger le `cd_nom` ensuite ;
  4. spécimens étrangers, cultivars, hybrides horticoles : ils restent dans le module Collections seulement, avec un lien éventuel vers WFO ou GBIF.
- À éviter : inventer des `cd_nom` locaux dans `taxonomie.taxref` (conflits à la mise à jour Taxref, inconnus de l'INPN).
- **Côté GeoMys, rien ne change** : sur le terrain on choisit toujours un taxon Taxref, au besoin au genre. Le problème concerne surtout la saisie de collections et d'herbiers anciens. Si la saisie depuis une photo d'étiquette (phase 2) est faite, il faudra :
  - conserver le nom lu tel quel dans `nom_cite` ;
  - proposer le rattachement Taxref le plus proche (synonyme ou genre) sans l'imposer.

## À valider avec le demandeur

- Récupérer des **modèles d'étiquettes réels** (photos, plus le fichier Excel/Word de publipostage actuel). Ils serviront à figer les colonnes de l'export et à tester la lecture des photos.
- `non_digital_proof` suffit-il pour le n° de spécimen, ou faut-il un champ additionnel Occtax dédié ?

## Vérification prévue

- **Phase 1** :
  - tests unitaires du générateur CSV pour les 3 types (accents, `;` dans les valeurs, UUID) ;
  - ouverture du fichier dans Excel et LibreOffice ;
  - publipostage Word de test.
- **Phase 2** :
  - tests unitaires de l'analyse du texte sur un ensemble de textes d'étiquettes réelles ;
  - test manuel sur appareil, sans réseau.
