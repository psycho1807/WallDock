# WallDock

**Appli Android de kiosque pour tablette murale Home Assistant, avec l'assistant vocal Jarvis intégré.**
*Android wall-panel kiosk for Home Assistant, with a built-in voice assistant — [English below](#english).*

Licence GPL v3 · [Dernière version](https://github.com/psycho1807/WallDock/releases/latest) · [Soutenir le projet](#soutenir-le-projet)

---

## Ce que fait WallDock

**Kiosque**
- Tableau de bord Home Assistant en plein écran, barres système masquées, écran toujours allumé.
- Dock d'applis autorisées (Spotify, YouTube, caméras…) ouvert par une poignée (position réglable ou cachée).
- Retour automatique au tableau de bord après inactivité ; les applis « Vidéo » en sont exclues.
- Réglages protégés par un code ; WallDock peut devenir l'écran d'accueil de la tablette (conseillé sur une tablette murale).
- Redémarrage automatique : WallDock se relance quand la tablette redémarre (coupure de courant), même s'il n'est pas l'écran d'accueil (il faut l'autorisation « afficher par-dessus les autres applis »).
- Écran de veille : horloge, ou une page de votre choix (une vue du tableau de bord, par exemple `/lovelace/veille`).
- Luminosité de la fenêtre WallDock, ou du système entier en option (autorisation « Modifier les paramètres système »).
- Écran de secours quand Home Assistant ne répond pas.
- Détection de mouvement avec la caméra frontale : l'écran s'allume quand quelqu'un passe, capteur « Mouvement » dans HA (les images restent sur la tablette).
- Mises à jour automatiques depuis les releases GitHub (désactivables).
- Sauvegarde et restauration des réglages dans un fichier.

**Intégration Home Assistant** (API native ESPHome, découverte automatique, **connexion chiffrée**)
- Chiffrement ESPHome (Noise) : à la première connexion, Home Assistant crée une clé et l'envoie à la tablette ; ensuite, seule la connexion chiffrée avec cette clé est acceptée. Rien à saisir. « Réinitialiser le chiffrement » dans les réglages retire la clé.
- Capteurs : batterie, en charge, température de la batterie, signal Wi-Fi, appli ouverte, alerte affichée, mise à jour, état de Jarvis.
- Inactivité : capteur « Inactivité » (secondes depuis le dernier toucher, par pas de 10 s) et binary_sensor « Activité sur l'écran » (délai réglable), pour tout piloter par automatisation.
- Commandes : écran, luminosité, volume, ouvrir une appli ou une page, revenir au tableau de bord, recharger.
- Actions : `parler`, `jouer_son`, `ouvrir_page`, `ouvrir_appli`, `afficher_alerte` (popup plein écran avec compte à rebours), `afficher_camera` (une caméra en plein écran : `camera: camera.sonnette` pour son image, ou une page du tableau de bord comme `/sonnette/live` pour la vidéo en direct), `fermer_alerte`.
- Caméra « Capture d'écran » : ce qu'affiche la tablette, visible dans HA.
- Réglages modifiables depuis HA (catégorie Configuration de l'appareil) : écoute et sensibilité de Jarvis, écran de veille et sa page, délai de retour, poignée du dock, adresse du tableau de bord, luminosité du système, démarrage automatique, mises à jour automatiques.

**Jarvis, assistant vocal** (satellite Assist)
- « Hey Jarvis » reconnu sur la tablette (modèles openWakeWord) ou par Home Assistant.
- Visage animé plein écran avec la question et la réponse, conversation qui s'enchaîne.
- Annonces (`assist_satellite.announce`), minuteurs vocaux avec compte à rebours et sonnerie.
- Dites « Stop » pour couper Jarvis ou la sonnerie d'un minuteur (modèle microWakeWord, sur la tablette).

**Lecteur multimédia**
- La tablette apparaît comme `media_player` dans HA : musique, radio, Music Assistant, annonces, pause, volume, muet. La musique se met en pause quand Jarvis écoute.

## Installation

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.walldock%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpsycho1807%2FWallDock%22%2C%22author%22%3A%22psycho1807%22%2C%22name%22%3A%22WallDock%22%7D"><img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="54"></a>

Avec [Obtainium](https://github.com/ImranR98/Obtainium), WallDock s'installe et se met à jour directement depuis GitHub (bouton ci-dessus, depuis la tablette). Sinon :

1. Sur la tablette, ouvrir la [dernière version](https://github.com/psycho1807/WallDock/releases/latest) et installer `WallDock-x.y.z.apk`.
2. Au premier lancement : adresse du tableau de bord (ex. `http://192.168.1.10:8123/lovelace/0?kiosk`), code administrateur, applis du dock, puis **Enregistrer**.
3. Dans les réglages, donner les autorisations : écran d'accueil, affichage par-dessus les autres applis, micro (pour Jarvis), installation des mises à jour.
4. Home Assistant propose ensuite « WallDock … » dans **Paramètres → Appareils et services** : l'accepter.
5. Pour Jarvis : un pipeline Assist avec STT/TTS ; pour la détection par HA, l'appli openWakeWord.

Tablettes Honor/Huawei : désactiver l'optimisation de batterie pour WallDock.

## Soutenir le projet

WallDock est gratuit et libre. Si l'appli vous rend service, un petit don aide à la faire avancer :

- ☕ **Ko-fi** : https://ko-fi.com/psycho1807

## Compilation

Chaque push sur `main` compile l'APK signé avec GitHub Actions ; quand `versionName` change, une release est publiée. En local : Android Studio, ou `gradle assembleRelease` avec Gradle 8.10 et le SDK Android 35 (sans la clé officielle, l'APK n'est pas signé).

Le serveur ESPHome (`app/src/main/java/app/walldock/esphome`) est en Java pur et se teste sur une JVM avec le client Python `aioesphomeapi`.

## Crédits

- Mot « Stop » : modèle [microWakeWord](https://github.com/kahrendt/microWakeWord) de Kevin Ahrendt (Apache 2.0), inclus dans l'appli ; extraction des caractéristiques portée du micro frontend de TensorFlow Lite Micro (Apache 2.0).
- Détection du mot d'activation : modèles [openWakeWord](https://github.com/dscripka/openWakeWord) (téléchargés au premier usage, licence CC BY-NC-SA 4.0), exécutés avec TensorFlow Lite.
- Protocole : [API native ESPHome](https://esphome.io/components/api.html).

---

## English

**WallDock** turns an Android tablet into a Home Assistant wall panel:

- Motion detection with the front camera (screen wakes up, "Motion" sensor in HA; images never leave the tablet).
- Full-screen dashboard kiosk, app dock, automatic return to the dashboard, PIN-protected settings, start on boot (power cut), screensaver (clock or any dashboard page), optional system brightness, offline fallback screen, automatic updates from GitHub releases.
- Monitoring sensors: battery level and temperature, Wi-Fi signal, inactivity time and a screen-activity binary sensor.
- Native ESPHome API integration (auto-discovered, **encrypted**: Home Assistant creates the Noise key on its own at the first connection): battery, screen, brightness, volume, current app, open app/page, full-screen alerts with countdown, text-to-speech, sounds.
- **Jarvis voice assistant**: Assist satellite with on-device "Hey Jarvis" wake word (openWakeWord models) or Home Assistant wake word detection, animated full-screen face, announcements, voice timers, and "Stop" to silence it.
- **Media player** entity: music, radio, Music Assistant, announcements, pause and volume.

Install the latest APK from [Releases](https://github.com/psycho1807/WallDock/releases/latest), or with [Obtainium](https://github.com/ImranR98/Obtainium) to get updates straight from GitHub:

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.walldock%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpsycho1807%2FWallDock%22%2C%22author%22%3A%22psycho1807%22%2C%22name%22%3A%22WallDock%22%7D"><img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="54"></a>

 The app UI is available in English and French (it follows the tablet language).

Support the project: [Ko-fi](https://ko-fi.com/psycho1807)

License: GPL-3.0.
