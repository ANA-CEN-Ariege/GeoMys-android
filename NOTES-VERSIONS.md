# GeoMys — nouveautés de la v1.3.8 à la v1.4.0

*16 versions publiées du 20 au 31 août 2026. Ce document regroupe les changements par thème ; le détail version par version est dans les [releases GitHub](https://github.com/ANA-CEN-Ariege/GeoMys-android/releases).*

---

## ⚠ À savoir avant de mettre à jour

- **Depuis la v1.3.17, une mise à jour peut exiger un rechargement des données.** Au premier lancement, l'application ouvre alors **Paramètres** et demande « Recharger les données » (réseau nécessaire) avant de laisser continuer. **Vos saisies en attente sont conservées** (Mes saisies, Mes visites, Mes stations) et pourront être envoyées ensuite.
- Même quand ce n'est pas obligatoire, un « Recharger les données » après mise à jour est conseillé : c'est lui qui remplit les caches hors-ligne (listes de formulaires, pictogrammes de protocoles, habitats HABREF, et depuis la v1.3.22 vos stations OccHab).
- **Envoyez vos saisies en attente avant de changer de canal d'installation** (APK GitHub ↔ Play Store) : les deux applications sont distinctes et une désinstallation efface les saisies locales.

---

## Module OccHab — l'essentiel du travail de cette série

### Saisie d'une station

- **Le formulaire des informations obligatoires s'ouvre dès la carte**, à chaque nouveau relevé, **pré-rempli avec les valeurs du relevé précédent** (les dates repartent du jour). Un bouton **« i »** rouvre à tout moment le formulaire complet de la station.
- **Chaque station est indépendante** : « i » et « Détails » ne modifient que la station sélectionnée (jeu de données, observateurs, dates, commentaire, nomenclatures). Une nouvelle station hérite des valeurs du formulaire de démarrage, puis vit sa vie.
- **Champs obligatoires alignés sur GeoNature web** ; **surface calculée automatiquement** (calcul local, fonctionne hors-ligne) et **altitudes min/max remplies depuis le MNT du serveur** quand il y a du réseau ; dates sans heure.
- Une géométrie validée est **enregistrée et envoyable telle quelle, avec ou sans habitat**.

### Dessin et modification des polygones

- **Poignées « + »** au milieu de chaque arête : toucher une poignée insère un sommet à cet endroit (mécanisme QField) — fini les contours croisés. Appui long sur un sommet pour le déplacer.
- **Topologie partagée** : sur une arête commune à deux polygones (aimantage), un sommet inséré ou déplacé est répercuté sur le polygone voisin — y compris pendant le dessin d'un nouveau polygone. Un voisin déjà envoyé repasse alors « à envoyer ».
- **Annuler = la dernière opération** (sommet ajouté, déplacé, inséré, point déplacé), effet sur les voisins compris. Plusieurs Annuler remontent les opérations une à une.
- **Aimantage élargi** sur les sommets des stations voisines, pour raccorder deux polygones plus facilement.
- **Stations à trou** (v1.4.0) : un polygone dessiné sous QGIS avec un **anneau intérieur** est désormais lu, affiché en creux et **conservé à l'identique lors d'une mise à jour** — auparavant un simple renvoi supprimait le trou sur GeoNature. Les **sommets d'un trou se modifient** comme ceux du contour, et la **surface déduit les trous**. Une géométrie multi-parties est affichée mais non modifiable dans l'appli (à retoucher sous QGIS).

### Stations déjà présentes sur GeoNature

- **« Afficher mes stations déjà sur GeoNature »** : vos stations du serveur (celles de votre compte, sur le jeu de données de la saisie) s'affichent en **violet**, cadrent la carte et aimantent la saisie. Depuis la v1.3.22, l'option est **cochée par défaut** et le décochage est mémorisé.
- **Elles sont modifiables** : touchez-en une pour l'importer, modifiez géométrie et habitats — l'envoi part en **mise à jour** de la station existante, jamais en doublon.
- **Jamais plus d'une copie locale à envoyer** par station serveur. Une station déjà reprise dans une autre saisie apparaît en **orange pointillé** : la toucher propose d'**ouvrir cette saisie**. Depuis la v1.3.22, elle s'affiche **avec ses modifications** en cours.
- **Elle n'entre dans « Mes stations » qu'à la première modification réelle** : la consulter ou la valider sans rien changer ne crée aucune copie à envoyer. Et si l'on **annule toutes ses modifications**, elle en ressort et redevient importable.
- **Disponibles hors-ligne** (v1.3.22) : « Recharger les données » les met en cache ; sans réseau, la carte les affiche depuis ce cache (avec la date du dernier chargement) et elles restent importables et modifiables.

### Évaluations ANA / Natura 2000

- Les stations portant un bloc **[ANA-EVAL]** (plugin QGIS maison) affichent une section **éditable** : statut de validation, enjeu, état de conservation, zone humide, unité végétale, échelle… côté station ; enjeu, état, typicité, dynamique, restauration, plantes exotiques envahissantes… côté habitat.
- Le **texte libre du commentaire n'est jamais touché** : le bloc est extrait à la lecture et re-fusionné à l'envoi — parfaitement interopérable avec le plugin QGIS.

### Corrections de saisie

- Le **nom cité** d'un habitat existant (saisi sur le web ou QGIS) est conservé tant que le code HABREF ne change pas.
- Le **recouvrement accepte la virgule** décimale ; le commentaire d'une saisie n'est plus proposé dans la suivante.
- Si le téléphone a relancé l'application en pleine saisie, les écrans « habitats » renvoient à l'accueil au lieu d'enregistrer une station vide à (0, 0).
- L'édition d'un habitat passe par une **icône crayon** ; la suppression d'une station se fait depuis « Mes stations ».

---

## Fiabilité des envois

- **Plus de doublon si l'on quitte l'écran pendant un envoi** (Occtax et OccHab) : le résultat d'un envoi abouti est toujours enregistré, et chaque observation est marquée dès sa création côté serveur.
- **Anti-doublon Occtax** : chaque observation part avec un identifiant unique ; si la réponse du serveur se perd (coupure réseau), le prochain envoi vérifie d'abord son existence avant de la renvoyer.
- **Stockage plein** : un envoi transmis mais impossible à enregistrer localement est clairement signalé, au lieu de passer pour « à envoyer » et d'être renvoyé en double. Plus généralement, tout échec d'écriture sur l'appareil déclenche une alerte au lieu d'une perte silencieuse.
- **Messages réseau justes** : hors couverture, l'appli dit « Pas de réseau, ou serveur introuvable » au lieu de « Identifiants expirés (HTTP 401) » — inutile de ressaisir vos identifiants dans ce cas. Une session réellement expirée est détectée sur tous les appels, avec reconnexion automatique.

---

## Suivis (monitoring)

- **Vrais pictogrammes de protocole** (images du serveur, en cache hors-ligne) et liste épurée.
- **Saisie 100 % hors-ligne** : les listes déroulantes des formulaires (nomenclatures, jeux de données, observateurs) sont en cache après « Recharger les données ».
- Formulaires plus fluides sur les gros protocoles ; un champ « entier » ne part plus avec une décimale ; un protocole sans image ne déclenche plus d'appel réseau à chaque ouverture.

---

## Occtax

- Un champ additionnel de type **texte** part tel quel (« 42 » reste du texte), comme depuis le site GeoNature ; les champs nombre, case à cocher et nomenclature gardent leur type.
- Clavier avec séparateur décimal sur les champs numériques ; autocomplétion des espèces sans à-coups ; tracé GPS plus économe sur les longues sorties.

---

## Application et Paramètres

- **Première configuration guidée** : tant que la configuration n'est pas complète (connexion, données, sélections), l'application ouvre Paramètres et empêche d'en sortir.
- Modifier serveur, identifiant ou mot de passe « oublie » la connexion précédente et invite à re-tester.
- **Paramètres utilisables hors-ligne** (v1.4.0) : la boîte « Chargement des données » (bouton Recharger + compteurs) est visible dès que des données sont en cache, même sans réseau.
- **Compteurs adaptés aux droits** : « Protocoles » n'apparaît que si un protocole de suivi est accessible, « Stations OccHab » que si le module est disponible.
- La liste des **jeux de données** s'ouvre sous le champ au lieu de le recouvrir.

---

## Sous le capot

- **Application deux fois plus légère** : APK 7,4 → 3,4 Mo grâce à la minification R8 — mises à jour bien plus rapides sur le terrain.
- **Mot de passe** chiffré via l'Android Keystore (migration automatique et transparente).
- **Mise à jour intégrée** en HTTPS exclusivement, fichier d'installation purgé après usage.
- Purge des **photos orphelines** ; sauvegarde Android bornée aux données utiles (référentiels, cartes hors-ligne et médias exclus).
- **549 tests automatisés** (373 à la v1.3.8), dont un test d'exhaustivité qui garantit qu'aucun champ OccHab envoyé au serveur ne peut être oublié lors d'une modification.
- Trois audits de code menés en juillet et août 2026, appliqués par lots (A à D).

---

## Repères de versions

| Version | Date | En une ligne |
|---|---|---|
| **1.4.0** | 31/08 | Polygones à trou (lus, éditables, conservés à l'envoi) ; Paramètres hors-ligne |
| 1.3.22 | 31/08 | Stations serveur en cache hors-ligne ; option cochée par défaut |
| 1.3.21 | 30/08 | Annuler = dernière opération ; retour à l'origine = plus rien à envoyer |
| 1.3.20 | 27/08 | Détails par station ; champs additionnels texte |
| 1.3.19 | 27/08 | Corrections de saisie OccHab (nom cité, virgule, station fantôme) |
| 1.3.18 | 27/08 | Fiabilité des envois : anti-doublon, stockage plein, messages réseau |
| 1.3.17 | 27/08 | Rechargement des données requis ; entrée dans Mes stations à la 1ʳᵉ modification |
| 1.3.16 | 26/08 | Une seule copie locale par station serveur (orange pointillé) |
| 1.3.15 | 26/08 | Évaluations ANA / Natura 2000 éditables |
| 1.3.14 | 26/08 | Poignées « + », topologie partagée, stations serveur modifiables |
| 1.3.13 | 25/08 | Refonte du flux de saisie OccHab |
| 1.3.12 | 25/08 | Robustesse (mise à jour HTTPS, connexion, cartes) |
| 1.3.11 | 24/08 | Station sans habitat envoyable ; mot de passe Keystore |
| 1.3.10 | 24/08 | Minification R8 : application deux fois plus légère |
| 1.3.9 | 24/08 | OccHab aligné sur le web ; suivis hors-ligne ; alertes de stockage |
| 1.3.8 | 20/08 | Première configuration guidée ; écran Paramètres plus sûr |
