# GeoNat Android

Application Android de terrain pour la saisie d'observations naturalistes (oiseaux, mammifères, reptiles) avec envoi vers un serveur [GeoNature](https://github.com/PnX-SI/GeoNature).

Développée par le [CEN Ariège](https://www.cen-ariege.fr/).

## Fonctionnalités

- **Suivi GPS** en arrière-plan avec tracé du parcours sur carte (IGN Scan 25)
- **Saisie d'observations** géolocalisées avec autocomplétion des espèces
  - Groupes : Oiseaux, Mammifères, Reptiles
  - Noms vernaculaires ou scientifiques
  - Champs détaillés : sexe, stade de vie, technique d'observation, statut biologique, comportement, méthode de détermination, etc.
- **TaxRef embarqué** pour fonctionnement hors connexion, complété par cache local synchronisé depuis GeoNature
- **Export GPX** des sorties
- **Envoi GeoNature** : création de relevés OccTax avec occurrences (protocole OCCTAX)
- **Explorateur** de sorties passées avec carte et liste des observations

## Configuration GeoNature

Dans l'écran de configuration (icône engrenage) :

| Paramètre | Description |
|-----------|-------------|
| URL du serveur | URL de base du serveur GeoNature (ex : `https://geonature.example.org`) |
| Identifiant / Mot de passe | Identifiants de connexion GeoNature |
| Jeu de données | Sélectionner dans la liste ou saisir l'`id_dataset` manuellement |
| Liste de taxons | Liste TaxHub utilisée pour l'autocomplétion (`id_liste`) |

Le bouton **Tester** vérifie la connexion et charge automatiquement les jeux de données et listes disponibles.

Le bouton **Synchroniser depuis GeoNature** télécharge le cache TaxRef (noms, groupes) depuis la liste de taxons configurée.

## Architecture

```
app/src/main/java/com/example/birdstrace/
├── model/          # Modèles de données (Sortie, Observation, Taxon)
├── network/        # GeoNatureService, TaxRefService (API REST)
├── store/          # Persistance : GeoNatureConfig, TaxRefCache, SortieStore, MapTileCache
├── location/       # LocationTracker, LocationForegroundService
├── gpx/            # Export GPX
├── TaxRefLocal.kt  # Base TaxRef embarquée (oiseaux/mammifères/reptiles)
└── ui/             # Fragments : Accueil, Trace, Observations, Explorer, ConfigGeoNature, …
```

- **Min SDK :** 24 (Android 7.0)
- **Langage :** Kotlin
- **Architecture :** Fragment + Navigation Component, ViewBinding
- **Carte :** OSMDroid (tuiles IGN Scan 25 via Géoportail)

## Build

```bash
./gradlew assembleDebug
```

Requiert Android Studio Hedgehog ou supérieur, JDK 11.

## Licence

© CEN Ariège — usage interne.
