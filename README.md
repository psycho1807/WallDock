# WallDock

Appli Android de kiosque pour tablette murale Home Assistant : le tableau de bord en plein écran, un dock d'applis autorisées (Spotify, YouTube, caméras…) et un retour automatique au tableau de bord après inactivité.

> Prototype (v0.1). Licence GPL v3.

## Ce que fait le prototype

- Tableau de bord Home Assistant en plein écran, écran toujours allumé, barres système masquées.
- Dock ouvert par la poignée ▲ en bas à droite : applis choisies, bouton Recharger, bouton Réglages.
- Retour automatique au tableau de bord après le délai choisi (120 s par défaut). Les applis cochées « Vidéo » n'ont pas de retour automatique.
- Réglages protégés par un code administrateur.
- Peut devenir l'écran d'accueil de la tablette : le bouton Accueil ramène alors toujours au tableau de bord.

## Installation sur la tablette

1. Sur la tablette, ouvrir https://github.com/psycho1807/WallDock/releases/latest et télécharger `WallDock-x.y.z.apk`.
2. L'installer (autoriser l'installation d'applis de sources inconnues si Android le demande).
3. Les versions suivantes s'installent ensuite toutes seules (réglage « Mises à jour » ; la toute première peut demander une confirmation à l'écran).
3. Au premier lancement, renseigner l'adresse de Home Assistant, un code administrateur et le délai, cocher les applis du dock, puis **Enregistrer**.
4. Dans **Réglages → Autorisations** :
   - **Définir WallDock comme écran d'accueil** (indispensable pour le mode kiosque) ;
   - **Autoriser l'affichage par-dessus les autres applis** (permet le retour automatique) ;
   - **Activer la détection d'activité** (facultatif : le délai repart à chaque geste au lieu d'être compté depuis l'ouverture de l'appli).
5. Sur les tablettes Honor/Huawei : désactiver l'optimisation de batterie pour WallDock, sinon Android peut couper le retour automatique.

## Compilation

Chaque push sur `main` compile l'APK signé avec GitHub Actions (`.github/workflows/build.yml`) ; quand `versionName` change, une release GitHub est publiée et les tablettes la récupèrent. La clé de signature est stockée chiffrée (`app/release.jks.enc`), son mot de passe est le secret `WALLDOCK_KEY_PASSWORD`. En local : Android Studio, ou `gradle assembleDebug` avec Gradle 8.10 et le SDK Android 35.

## Prochaines étapes

- Capteurs et commandes dans Home Assistant via l'API ESPHome (batterie, écran, appli au premier plan, retour au tableau de bord).
- Veille avec horloge, profils adulte / enfant / invité.
- Satellite vocal Assist.
