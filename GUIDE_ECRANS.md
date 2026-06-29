# GeoMys — Guide écran par écran

Ce guide présente **chaque écran de l'application** et **ce que vous pouvez y faire**, en langage simple. Pour une prise en main pas à pas, voir aussi le [mode d'emploi](MODE_EMPLOI.md).

GeoMys sert à **noter des observations naturalistes sur le terrain** et à les envoyer ensuite à votre serveur **GeoNature** — qu'il s'agisse de saisies libres (**OccTax**) ou de suivis protocolés (**monitoring**, `gn_module_monitoring`). L'application fonctionne **sans réseau** une fois les données chargées : vos observations sont gardées dans le téléphone et envoyées quand vous le décidez.

---

## Écran d'accueil

C'est le point de départ. Tout part d'ici.

<p align="center"><img src="Images/accueil.jpg" alt="Écran d'accueil" width="240"></p>

**Vous pouvez :**
- **Démarrer une saisie « multi-taxons » (OccTax)** — pour noter plusieurs espèces le long d'un parcours, sur une carte.
- **Démarrer une saisie « mono-taxon » (OccTax, « rapide »)** — pour noter une seule espèce, sur plusieurs points, rapidement.
- **Ouvrir « Monitoring »** (suivis de protocoles, `gn_module_monitoring`) — uniquement si votre compte a accès à au moins un protocole (sinon le bouton n'apparaît pas).
- **Activer/désactiver « Enregistrer la trace »** — quand c'est activé, l'application peut enregistrer le tracé GPS de votre déplacement pendant une saisie multi-taxons.
- **Ouvrir le menu** <img class="ico" src="Images/icones/ic_menu.png" alt="menu" height="20"> (en haut à gauche) : *Mes saisies*, *Mes visites*, *Explorer*, *Gestion des cartes*. Une **pastille rouge** signale qu'il vous reste des observations à envoyer.
- **Ouvrir les Paramètres** <img class="ico" src="Images/icones/ic_parametres.png" alt="paramètres" height="20"> (en haut à droite). Une pastille **verte** = tout est prêt pour saisir ; **rouge** = la configuration est incomplète.
- **Voir le numéro de version** (en bas). En le touchant, vous ouvrez l'écran de mise à jour (une pastille apparaît quand une nouvelle version est disponible).

---

## Paramètres (engrenage)

À faire à la première utilisation, et à chaque changement de serveur ou de compte.

<p align="center">
  <img src="Images/parametres_1.jpg" alt="Paramètres : écran initial" width="220">
  <img src="Images/parametres_2.jpg" alt="Paramètres : connexion réussie, bouton Charger les données" width="220">
  <img src="Images/parametres_3.jpg" alt="Paramètres : données chargées et sélections par défaut" width="220">
</p>

*De gauche à droite : écran initial · connexion réussie (bouton « Charger les données ») · données chargées + sélections par défaut.*

**Vous pouvez :**
- **Saisir l'adresse du serveur, votre identifiant et votre mot de passe**, puis **tester la connexion** (une coche verte confirme que le serveur répond et vous reconnaît).
- **Charger les données** du serveur en une fois : jeux de données, listes d'espèces, observateurs, dictionnaire des noms d'espèces, protocoles de suivi. *(Quelques minutes selon le serveur. À refaire de temps en temps pour récupérer les nouveautés.)*
- **Choisir vos valeurs par défaut** : le **jeu de données**, la **liste d'espèces** autorisées, votre **nom d'observateur** (pré-rempli dans vos saisies).
- **Voir ce qui est enregistré dans le téléphone** (nombre de protocoles, d'espèces…) et, via **Détails**, parcourir les espèces par groupe.
- **Vider le cache** (à n'utiliser qu'en cas de problème : il faudra recharger les données ensuite).
- **Valider** avec <img class="ico" src="Images/icones/ic_valider.png" alt="valider" height="20"> (coche verte, en haut à droite).

---

## Saisie multi-taxons (OccTax) — la carte

L'écran carte sur lequel vous vous déplacez et placez vos observations.

<p align="center">
  <img src="Images/saisie_multitaxons_1.jpg" alt="La carte de saisie" width="240">
  <img src="Images/saisie_multitaxons_2.jpg" alt="Positionner puis valider un point" width="240">
</p>

**Vous pouvez :**
- **Voir votre position** sur la carte (le point bleu <img class="ico" src="Images/icones/ic_position.png" alt="point de position" height="20">) et **recentrer dessus** avec <img class="ico" src="Images/icones/ic_centrer.png" alt="bouton centrer" height="20">.
- **Zoomer** avec <img class="ico" src="Images/icones/ic_zoom_plus.png" alt="zoom avant" height="20"> et <img class="ico" src="Images/icones/ic_zoom_moins.png" alt="zoom arrière" height="20">, ou avec deux doigts.
- **Changer le fond de carte** avec <img class="ico" src="Images/icones/ic_fond.png" alt="bouton fond de carte" height="20"> : plusieurs fonds disponibles (carte générale, fonds IGN, photo aérienne…).
- **Orienter la carte** avec la boussole <img class="ico" src="Images/icones/ic_boussole.png" alt="boussole" height="20">.
- **Démarrer / arrêter l'enregistrement du tracé** de votre déplacement (si « Enregistrer la trace » est activé sur l'accueil). Une barre indique la distance et le nombre d'observations.
- **Placer le point d'une observation** : soit sur votre position GPS, soit en **touchant la carte** à l'endroit voulu, puis **Valider ce point**.
- **Ajouter un relevé** avec <img class="ico" src="Images/icones/ic_releve_plus.png" alt="bouton ajouter" height="20">.
- **Terminer la sortie** avec <img class="ico" src="Images/icones/ic_valider.png" alt="valider" height="20"> (en haut à droite) : vous choisissez d'enregistrer, de continuer, ou de supprimer. *(La sortie est de toute façon enregistrée au fur et à mesure ; rien n'est encore envoyé.)*

<p align="center"><img src="Images/saisie_multitaxons_8.jpg" alt="Menu Terminer la sortie" width="240"></p>

---

## Nouveau relevé (choix des espèces)

L'écran où vous indiquez quelles espèces vous avez observées à ce point.

<p align="center">
  <img src="Images/saisie_multitaxons_3.jpg" alt="Nouveau relevé : choix du groupe et du nom" width="240">
  <img src="Images/saisie_multitaxons_4.jpg" alt="Relevé avec plusieurs espèces" width="240">
</p>

**Vous pouvez :**
- **Choisir le groupe d'espèces** dans le bandeau d'icônes (oiseaux, mammifères, reptiles, amphibiens, mollusques, poissons, insectes, autres invertébrés, flore, champignons) — cela filtre les propositions.
- **Saisir le nom** d'une espèce (en français ou en scientifique) : des propositions apparaissent au fil de la frappe.
- **Dicter le nom à la voix** <img class="ico" src="Images/icones/ic_micro.png" alt="micro" height="20"> — fonctionne aussi **sans réseau** si le modèle vocal français est installé sur le téléphone.
- **Ajouter plusieurs espèces** au même relevé.
- Pour chaque espèce : **Dénombrement** <img class="ico" src="Images/icones/ic_compteur.png" alt="dénombrement" height="20">, **Caractérisation** <img class="ico" src="Images/icones/ic_caracterisation.png" alt="caractérisation" height="20">, ou **supprimer** <img class="ico" src="Images/icones/ic_supprimer.png" alt="supprimer" height="20">.
- Ouvrir les **Détails du relevé** (date, observateurs, habitat, commentaire…).
- **Valider** <img class="ico" src="Images/icones/ic_valider.png" alt="valider" height="20"> pour revenir à la carte.

---

## Dénombrement

Pour préciser combien d'individus et ajouter des médias.

<p align="center"><img src="Images/saisie_multitaxons_5.jpg" alt="Formulaire de dénombrement" width="240"></p>

**Vous pouvez :**
- **Indiquer le nombre d'individus** (minimum / maximum) avec <img class="ico" src="Images/icones/ic_moins.png" alt="moins" height="20"> et <img class="ico" src="Images/icones/ic_plus.png" alt="plus" height="20">.
- **Préciser** l'objet du dénombrement, le type de dénombrement, le stade de vie, le sexe…
- **Ajouter des photos** <img class="ico" src="Images/icones/ic_photo.png" alt="ajouter une photo" height="20"> : **prendre une photo** avec l'appareil, ou **en choisir une dans la galerie**.
- **Ajouter des sons** <img class="ico" src="Images/icones/ic_audio.png" alt="ajouter un son" height="20"> (enregistrements audio existants).

---

## Caractérisation

Pour décrire l'observation plus finement.

<p align="center"><img src="Images/saisie_multitaxons_6.jpg" alt="Formulaire de caractérisation de l'occurrence" width="240"></p>

**Vous pouvez :**
- Préciser la **technique d'observation**, l'**état biologique**, le **comportement** (par ex. indices de nidification pour les oiseaux), et les autres informations proposées.

---

## Détails du relevé

Les informations communes à toutes les espèces d'un même relevé.

<p align="center"><img src="Images/saisie_multitaxons_7.jpg" alt="Détails du relevé" width="240"></p>

**Vous pouvez :**
- **Modifier la date et l'heure** du relevé.
- **Changer le jeu de données**.
- **Ajouter ou retirer des observateurs**.
- **Renseigner l'habitat**, un **commentaire**, et les autres champs proposés (altitude, précision…).

---

## Observations (relevés de la sortie en cours)

La liste de tout ce que vous avez noté pendant la sortie.

**Vous pouvez :**
- **Voir tous les relevés** de la sortie en cours.
- **Modifier** <img class="ico" src="Images/icones/ic_editer.png" alt="modifier" height="20"> ou **supprimer** <img class="ico" src="Images/icones/ic_supprimer.png" alt="supprimer" height="20"> un relevé.
- **Tout effacer**.

---

## Saisie mono-taxon (OccTax, « rapide »)

Pour noter rapidement une seule espèce sur plusieurs points.

<p align="center">
  <img src="Images/saisie_mono_1.jpg" alt="Saisie mono-taxon : choix de l'espèce" width="240">
  <img src="Images/saisie_mono_2.jpg" alt="Saisie mono-taxon : placer des points sur la carte" width="240">
</p>

**Vous pouvez :**
- **Choisir l'espèce** (une seule) puis **Démarrer la saisie**.
- **Placer des points** d'observation : position GPS ou tap sur la carte, puis <img class="ico" src="Images/icones/ic_releve_plus.png" alt="ajouter le point" height="20"> pour valider.
- **Enchaîner** autant de points que nécessaire pour la même espèce.
- **Zoomer** <img class="ico" src="Images/icones/ic_zoom_plus.png" alt="zoom avant" height="20"> <img class="ico" src="Images/icones/ic_zoom_moins.png" alt="zoom arrière" height="20">, **recentrer** <img class="ico" src="Images/icones/ic_centrer.png" alt="centrer" height="20">, **changer le fond de carte** <img class="ico" src="Images/icones/ic_fond.png" alt="fond de carte" height="20">.
- **Terminer** <img class="ico" src="Images/icones/ic_valider.png" alt="valider" height="20">. *(Tout est enregistré au fur et à mesure ; vous retrouvez la saisie dans « Mes saisies ».)*

---

## Monitoring (liste des protocoles)

L'entrée vers les suivis protocolés. Visible seulement si vous avez accès à au moins un protocole.

<p align="center"><img src="Images/monitoring_1.jpg" alt="Monitoring : liste des protocoles" width="240"></p>

**Vous pouvez :**
- **Voir la liste des protocoles** auxquels votre compte a droit.
- Pour chaque protocole, ouvrir sa **fiche** <img class="ico" src="Images/icones/ic_fiche.png" alt="fiche" height="20"> ou sa **carte** <img class="ico" src="Images/icones/ic_voir_carte.png" alt="voir sur la carte" height="20"> (tous les sites du protocole).
- Accéder aux **données en attente d'envoi** (bandeau en haut quand il y en a).

---

## Fiche d'un protocole / d'un site

Le même écran sert à tous les niveaux (protocole, site, point d'écoute…). On y descend de proche en proche.

<p align="center">
  <img src="Images/monitoring_2.jpg" alt="Fiche d'un protocole et liste de ses sites" width="240">
  <img src="Images/monitoring_3.jpg" alt="Fiche d'un site et liste de ses points" width="240">
</p>

**Vous pouvez :**
- **Voir les informations** de l'objet courant et la **liste de ses sous-objets** (regroupés par type).
- **Descendre** dans un sous-objet (**Détails**).
- **Voir sur la carte** (si l'objet a une géométrie).
- **Créer une nouvelle visite / un passage / une observation** <img class="ico" src="Images/icones/ic_ajouter.png" alt="créer" height="20">, selon ce que le protocole autorise.
- **Remonter** à n'importe quel niveau via le **fil d'Ariane** (le chemin cliquable en haut de l'écran : `Monitoring › Protocole › Site › …`).

---

## Carte d'un site

La carte qui montre la géométrie d'un site et de ses points.

<p align="center">
  <img src="Images/monitoring_6.jpg" alt="Carte d'un site : géométrie et points" width="240">
  <img src="Images/monitoring_7.jpg" alt="Menu sur un point : voir la fiche ou nouvelle visite" width="240">
</p>

**Vous pouvez :**
- **Voir le site et ses points** (points d'écoute, sous-objets…).
- **Toucher un point** : un menu propose de **voir sa fiche** ou de **créer une nouvelle visite / un passage** sur ce point.
- **Zoomer** <img class="ico" src="Images/icones/ic_zoom_plus.png" alt="zoom avant" height="20"> <img class="ico" src="Images/icones/ic_zoom_moins.png" alt="zoom arrière" height="20">, **changer le fond de carte** <img class="ico" src="Images/icones/ic_fond.png" alt="fond de carte" height="20">, **recentrer** <img class="ico" src="Images/icones/ic_centrer.png" alt="centrer" height="20">.

---

## Nouvelle visite (formulaire)

Le formulaire de saisie d'un suivi. Son contenu dépend du protocole.

<p align="center">
  <img src="Images/monitoring_4.jpg" alt="Nouvelle visite : formulaire du protocole" width="240">
  <img src="Images/monitoring_5.jpg" alt="Nouvelle observation : espèce et effectifs" width="240">
</p>

**Vous pouvez :**
- **Remplir les champs** définis par le protocole : texte, nombre, date, heure, cases à cocher, listes, et **espèce** (avec propositions et dictée vocale).
- Les **champs obligatoires** sont marqués d'une étoile **\*** ; le bouton **Enregistrer** reste grisé tant qu'ils ne sont pas remplis.
- **Ajouter une photo** <img class="ico" src="Images/icones/ic_photo.png" alt="ajouter une photo" height="20">.
- **Enregistrer** (dans le téléphone). L'application propose ensuite d'**enchaîner** directement l'observation suivante.

---

<p align="center"><img src="Images/accueil_menu.jpg" alt="Menu de navigation" width="240"></p>

*Les écrans suivants — Mes saisies, Mes visites, Explorer, Gestion des cartes — s'ouvrent depuis le menu (icône en haut à gauche de l'accueil).*

---

## Mes saisies

Vos sorties OccTax enregistrées dans le téléphone (saisies multi et mono-taxons).

<p align="center"><img src="Images/mes_saisies_1.jpg" alt="Mes saisies" width="240"></p>

**Vous pouvez :**
- **Voir vos sorties**, classées : *à envoyer*, *envoyées*, *importées*.
- **Continuer une sortie** <img class="ico" src="Images/icones/ic_editer.png" alt="continuer" height="20"> (la rouvrir pour la compléter).
- **Envoyer une sortie** au serveur <img class="ico" src="Images/icones/ic_envoyer.png" alt="envoyer" height="20">. Si la connexion coupe en cours d'envoi, la sortie reste en local : vous relancez plus tard.
- **Supprimer une sortie** <img class="ico" src="Images/icones/ic_supprimer.png" alt="supprimer" height="20">.
- **Ouvrir le détail** d'une sortie.

---

## Détail d'une sortie

Le récapitulatif d'une sortie avant envoi.

**Vous pouvez :**
- **Voir la carte** (tracé + points) et la **liste des espèces**.
- **Envoyer** la sortie au serveur <img class="ico" src="Images/icones/ic_envoyer.png" alt="envoyer" height="20">.
- **Exporter en GPX** un fichier partageable (mail, Drive…).

---

## Mes visites

Vos saisies de suivi (visites, passages, observations) en attente d'envoi.

<p align="center"><img src="Images/mes_visites_1.jpg" alt="Mes visites : saisies de suivi en attente" width="240"></p>

**Vous pouvez :**
- **Voir les saisies** en attente, regroupées par protocole, avec leur état (en attente, envoyée, en erreur).
- **Envoyer un groupe** <img class="ico" src="Images/icones/ic_envoyer.png" alt="envoyer" height="20"> (une visite et ses observations) — l'envoi est toujours **manuel**, vous décidez quand.
- **Modifier** <img class="ico" src="Images/icones/ic_editer.png" alt="modifier" height="20"> ou **supprimer** <img class="ico" src="Images/icones/ic_supprimer.png" alt="supprimer" height="20"> une saisie.
- **Réessayer** une saisie en erreur.

---

## Explorer

Pour visualiser les observations déjà présentes sur le serveur.

<p align="center"><img src="Images/explorer_1.jpg" alt="Explorer : observations de la synthèse" width="240"></p>

**Vous pouvez :**
- **Voir sur la carte** les observations de la **synthèse** GeoNature (de la dernière année).
- **Filtrer par groupe d'espèces** (bandeau d'icônes en haut).
- **Zoomer** <img class="ico" src="Images/icones/ic_zoom_plus.png" alt="zoom avant" height="20"> <img class="ico" src="Images/icones/ic_zoom_moins.png" alt="zoom arrière" height="20">, **changer le fond de carte** <img class="ico" src="Images/icones/ic_fond.png" alt="fond de carte" height="20">, **vous recentrer** <img class="ico" src="Images/icones/ic_centrer.png" alt="centrer" height="20">.
- Voir le **nombre d'observations** affichées (en bas).

---

## Gestion des cartes hors-ligne

Pour préparer le terrain quand il n'y aura pas de réseau.

<p align="center"><img src="Images/maps_manager_1.jpg" alt="Gestion des cartes hors-ligne" width="240"></p>

**Vous pouvez :**
- **Choisir une zone** sur la carte, ou **cadrer automatiquement l'emprise sur un protocole** via le bouton <img class="ico" src="Images/icones/ic_emprise.png" alt="emprise par protocole" height="20"> (en bas à droite) : la carte se cale sur l'étendue des sites de ce protocole.
- **Choisir le fond de carte** <img class="ico" src="Images/icones/ic_fond.png" alt="fond de carte" height="20"> et le **niveau de zoom** à télécharger.
- **Télécharger** les cartes pour les consulter ensuite sans réseau.
- **Voir l'espace utilisé** et **vider** le cache des cartes.

*(Limites : environ 200 km² par téléchargement ; au-delà d'1 Go par fond, les cartes les moins consultées sont supprimées automatiquement.)*

---

## Liste des taxons

Ouverte depuis les Paramètres (bouton « Détails »).

**Vous pouvez :**
- **Parcourir les espèces** d'un groupe ou d'une liste (nom français, nom scientifique).

---

## Mise à jour

Ouvert en touchant le numéro de version sur l'accueil.

**Vous pouvez :**
- **Vérifier** si une nouvelle version existe.
- **Télécharger et installer** la mise à jour.

---

© ANA - CEN Ariège
