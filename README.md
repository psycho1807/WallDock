# WallDock

**Appli Android de kiosque pour tablette murale Home Assistant, avec l'assistant vocal Jarvis intégré.**
*Android wall-panel kiosk for Home Assistant, with a built-in voice assistant — [English below](#english).*

Licence GPL v3 · [Dernière version](https://github.com/psycho1807/WallDock/releases/latest) · [Soutenir le projet](#soutenir-le-projet) · ⭐ [WallDock Pro](#walldock-pro)

---

## Ce que fait WallDock

**Kiosque**
- Tableau de bord Home Assistant en plein écran, barres système masquées, écran toujours allumé.
- Dock d'applis autorisées (Spotify, YouTube, caméras…) ouvert par une poignée (position réglable ou cachée).
- Retour automatique au tableau de bord après inactivité ; les applis « Vidéo » en sont exclues.
- Réglages protégés par un code ; WallDock peut devenir l'écran d'accueil de la tablette (conseillé sur une tablette murale).
- Redémarrage automatique : WallDock se relance quand la tablette redémarre (coupure de courant), même s'il n'est pas l'écran d'accueil (il faut l'autorisation « afficher par-dessus les autres applis »).
- Zoom du tableau de bord (50 à 200 %) : plus d'informations à l'écran d'un coup, ou plus gros.
- Écran de veille : horloge, une page de votre choix (une vue du tableau de bord, par exemple `/lovelace/veille`), ou un **diaporama photos** (album [Immich](https://immich.app/) ou dossier de la tablette).
- **Calendrier** plein écran (jour, semaine, mois, agenda) avec les calendriers de Home Assistant, dans le dock ou via l'action `afficher_calendrier`.
- Luminosité de la fenêtre WallDock, ou du système entier en option (autorisation « Modifier les paramètres système »).
- Planification : écran éteint chaque nuit (heures d'extinction et d'allumage réglables, aussi depuis HA).
- Liste blanche d'adresses : seules les adresses autorisées s'ouvrent dans le tableau de bord (Home Assistant l'est toujours).
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
- **Écoute écran éteint** : Jarvis continue d'entendre « Hey Jarvis » quand l'écran est éteint ou qu'une autre appli est ouverte, et rallume l'écran (désactivable).
- **Réveils** : réglés sur la tablette ou depuis HA (`ajouter_reveil`, `supprimer_reveil`), jours de la semaine, lever de soleil progressif (luminosité qui monte avant la sonnerie), répétition ; capteur « Prochain réveil » dans HA.

**Lecteur multimédia**
- La tablette apparaît comme `media_player` dans HA : musique, radio, Music Assistant, annonces, pause, volume, muet. La musique se met en pause quand Jarvis écoute.
- **Lecteur Music Assistant synchronisé (Sendspin)** : cochez-le dans les réglages, activez le fournisseur Sendspin dans Music Assistant et acceptez la tablette une fois. Elle joue alors en parfaite synchronisation avec vos autres enceintes Sendspin (multiroom), son chiffré, volume réglable depuis MA.

## Installation

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.walldock%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpsycho1807%2FWallDock%22%2C%22author%22%3A%22psycho1807%22%2C%22name%22%3A%22WallDock%22%7D"><img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="54"></a>

Avec [Obtainium](https://github.com/ImranR98/Obtainium), WallDock s'installe et se met à jour directement depuis GitHub (bouton ci-dessus, depuis la tablette). Sinon :

1. Sur la tablette, ouvrir la [dernière version](https://github.com/psycho1807/WallDock/releases/latest) et installer `WallDock-x.y.z.apk`.
2. Au premier lancement : adresse du tableau de bord (ex. `http://192.168.1.10:8123/lovelace/0?kiosk`), code administrateur, applis du dock, puis **Enregistrer**.
3. Dans les réglages, donner les autorisations : écran d'accueil, affichage par-dessus les autres applis, micro (pour Jarvis), installation des mises à jour.
4. Home Assistant propose ensuite « WallDock … » dans **Paramètres → Appareils et services** : l'accepter.
5. Pour Jarvis : un pipeline Assist avec STT/TTS ; pour la détection par HA, l'appli openWakeWord.

### Tablette murale : à faire absolument

- **Définir WallDock comme écran d'accueil** (réglages → « Définir WallDock comme écran d'accueil »). Depuis la 0.15.0, WallDock se relance aussi tout seul au démarrage de la tablette (coupure de courant) grâce à l'autorisation « afficher par-dessus les autres applis » ; mais certaines surcouches Android bloquent ce lancement : l'écran d'accueil reste la solution la plus sûre.
- **Désactiver l'optimisation de batterie** pour WallDock, sinon Android peut couper l'appli (et donc HA, Jarvis, le retour au tableau de bord) :
  - **Samsung (One UI)** : Paramètres → Applications → WallDock → Batterie → **Non restreinte** ; et Paramètres → Batterie → Limites d'utilisation en arrière-plan → retirer WallDock des applis « en veille ».
  - **Xiaomi / Redmi / POCO (MIUI, HyperOS)** : Paramètres → Applications → WallDock → **Démarrage automatique** activé, Économiseur de batterie → **Aucune restriction** ; dans les applis récentes, verrouiller WallDock (cadenas).
  - **Honor / Huawei** : Paramètres → Batterie → Lancement d'applis → WallDock → **Gérer manuellement**, tout cocher.
  - **Lenovo, Oppo, Realme, OnePlus…** : même principe (démarrage auto + batterie sans restriction). Le site [dontkillmyapp.com](https://dontkillmyapp.com) détaille chaque marque.

### Autorisations : à quoi elles servent

| Autorisation | Pourquoi |
|---|---|
| Internet, état du réseau, état du Wi-Fi | Afficher le tableau de bord, parler à Home Assistant, mesurer le signal Wi-Fi. |
| Affichage par-dessus les autres applis | Retour automatique au tableau de bord, popups (alertes, caméra, Jarvis), relance au démarrage. |
| Démarrage avec la tablette (`RECEIVE_BOOT_COMPLETED`) | Relancer WallDock après une coupure de courant. Désactivable dans les réglages ou depuis HA. |
| Micro | Jarvis (« Hey Jarvis », « Stop »). Le son n'est envoyé qu'à **votre** Home Assistant, et seulement après le mot d'activation quand il est reconnu sur la tablette. |
| Caméra | Uniquement si vous activez la détection de mouvement. Les images sont analysées sur la tablette et ne sont **jamais** envoyées ni enregistrées. |
| Service de premier plan, verrou de veille, notifications | Garder la connexion à HA active en permanence (notification permanente obligatoire sous Android). |
| Service d'accessibilité (facultatif) | Savoir si vous touchez l'écran dans une autre appli, pour ne pas revenir au tableau de bord pendant que vous l'utilisez. Il ne lit ni ne transmet aucun contenu. |
| Modifier les paramètres système (facultatif) | Seulement si vous cochez « Régler la luminosité du système ». |
| Alarmes exactes (`SCHEDULE_EXACT_ALARM`) | Faire sonner les réveils à l'heure pile, même tablette en veille. |
| `REQUEST_INSTALL_PACKAGES` | Installer les mises à jour de WallDock. L'appli ne télécharge **que** l'APK de la dernière release publique de ce dépôt GitHub, et Android refuse toute mise à jour qui n'est pas signée avec la même clé que l'appli installée. |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | Android 12+ : permet d'installer ces mises à jour sans confirmation (seulement pour WallDock lui-même, jamais pour une autre appli). |

**Désactiver les mises à jour automatiques** : réglages → décocher « Mises à jour automatiques », ou l'interrupteur « Mises à jour automatiques » dans HA. Vous pouvez aussi refuser l'autorisation d'installer des applis à WallDock (Android → Applis spéciales → Installer des applis inconnues), et mettre à jour à la main ou avec Obtainium.

### WallDock ou Fully Kiosk ?

Comparaison honnête, d'après la documentation publique de [Fully Kiosk Browser](https://www.fully-kiosk.com/) (novembre 2026). Fully est un produit mûr et très complet ; WallDock est fait pour Home Assistant avant tout.

| | WallDock (gratuit) | [WallDock Pro](#walldock-pro) | Fully Kiosk (licence PLUS) |
|---|---|---|---|
| Prix | Gratuit, libre (GPL-3.0) | 4,99 € (achat unique, 3 tablettes, 3 jours d'essai) | 8,90 € par tablette (paiement unique) |
| Tableau de bord plein écran, écran toujours allumé | ✅ | ✅ | ✅ |
| Relance au démarrage | ✅ | ✅ | ✅ |
| Intégration HA | ✅ API native ESPHome, découverte auto, chiffrée | ✅ + MQTT | ✅ intégration officielle « Fully Kiosk Browser » |
| Réglages modifiables depuis HA | ✅ | ✅ | ✅ |
| Détection de mouvement (caméra) | ✅ | ✅ | ✅ (+ détection de visage, de son) |
| Écran de veille | ✅ horloge ou page au choix | ✅ | ✅ plus d'options (playlist, luminosité…) |
| Lecteur multimédia, synthèse vocale, alertes plein écran | ✅ | ✅ | ✅ (sons, TTS ; pas de popup d'alerte HA dédiée) |
| **Assistant vocal (satellite Assist), mot d'activation, minuteurs** | ✅ | ✅ + choix du mot, mot personnalisé | ❌ |
| Dock d'applis + retour auto au tableau de bord | ✅ | ✅ | ✅ (lanceur d'applis, réglable) |
| Verrouillage kiosque complet (« device owner », blocage des boutons, de la barre d'état) | ❌ (barres masquées seulement) | ✅ | ✅ |
| Administration à distance (page web, Fully Cloud), MQTT, API REST | ❌ (tout passe par HA) | ✅ page web, API REST, MQTT (pas de cloud) | ✅ |
| Planification veille/réveil intégrée | ✅ | ✅ | ✅ |
| Liste blanche d'adresses | ✅ | ✅ | ✅ |
| Navigateur complet (onglets, PDF…) | ❌ | ✅ onglets, lecteur PDF | ✅ |
| Interphone entre tablettes | ❌ | ✅ | ❌ |
| Réveils, calendrier HA, diaporama Immich | ✅ | ✅ | ❌ (diaporama de dossier/URL seulement) |
| Lecteur Music Assistant synchronisé (multiroom Sendspin) | ✅ | ✅ | ❌ |
| Écran de la tablette visible et commandable depuis HA | capture seulement | ✅ en direct, clics et gestes | ✅ capture ; commande à distance via Fully Remote Admin |
| Distribution | GitHub / Obtainium (Play Store prévu) | GitHub / Obtainium (Play Store prévu) | Play Store et site officiel |

En résumé : si vous voulez un écran mural Home Assistant avec un assistant vocal, gratuit, WallDock est fait pour ça. Si vous avez besoin d'un verrouillage strict (tablette en accès public), d'administration à distance ou d'onglets, **[WallDock Pro](#walldock-pro)** les apporte (avec en plus l'interphone entre tablettes et l'écran de la tablette commandable depuis HA) ; d'ici là, Fully reste la référence.

## WallDock Pro

Une version **Pro** ajoute : mots de réveil au choix et mot personnalisé, interphone entre tablettes, écran de la tablette en direct dans Home Assistant (voir et toucher), kiosque verrouillé, administration à distance (page web + API REST), MQTT, onglets et lecteur PDF.

- 📦 **Télécharger** : [WallDock-Pro-Releases](https://github.com/psycho1807/WallDock-Pro-Releases/releases/latest) — **3 jours d'essai gratuit**, sans rien payer.
- 🔑 **Licence** (achat unique, 4,99 €, 1 clé = 3 tablettes) : [becasseau.gumroad.com/l/walldock-pro](https://becasseau.gumroad.com/l/walldock-pro)
- La version gratuite reste gratuite, complète et libre ; acheter la Pro est aussi une façon de soutenir le projet.

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

- Detailed setup for wall tablets (home screen, battery optimization on Samsung, Xiaomi, Honor/Huawei…), what each permission is for, and an honest comparison with Fully Kiosk: see the French sections above.
- Motion detection with the front camera (screen wakes up, "Motion" sensor in HA; images never leave the tablet).
- Full-screen dashboard kiosk, app dock, automatic return to the dashboard, PIN-protected settings, start on boot (power cut), nightly screen schedule, URL whitelist, dashboard zoom (50–200 %), screensaver (clock or any dashboard page), optional system brightness, offline fallback screen, automatic updates from GitHub releases.
- Monitoring sensors: battery level and temperature, Wi-Fi signal, inactivity time and a screen-activity binary sensor.
- Native ESPHome API integration (auto-discovered, **encrypted**: Home Assistant creates the Noise key on its own at the first connection): battery, screen, brightness, volume, current app, open app/page, full-screen alerts with countdown, text-to-speech, sounds.
- **Jarvis voice assistant**: Assist satellite with on-device "Hey Jarvis" wake word (openWakeWord models) or Home Assistant wake word detection, animated full-screen face, announcements, voice timers, and "Stop" to silence it.
- **Media player** entity: music, radio, Music Assistant, announcements, pause and volume.
- **Synchronised Music Assistant player (Sendspin)**: multi-room playback in sync with your other Sendspin speakers, encrypted.
- Alarms (with sunrise light ramp, settable from HA), full-screen Home Assistant calendar, Immich / local-folder photo screensaver, and wake word listening with the screen off.

Install the latest APK from [Releases](https://github.com/psycho1807/WallDock/releases/latest), or with [Obtainium](https://github.com/ImranR98/Obtainium) to get updates straight from GitHub:

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.walldock%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpsycho1807%2FWallDock%22%2C%22author%22%3A%22psycho1807%22%2C%22name%22%3A%22WallDock%22%7D"><img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="54"></a>

 The app UI is available in English and French (it follows the tablet language).

**WallDock Pro** adds wake word choice and a custom wake word, intercom between tablets, the live tablet screen in Home Assistant (view and touch), locked kiosk, remote admin page + REST API, MQTT, tabs and PDF viewer. [Download](https://github.com/psycho1807/WallDock-Pro-Releases/releases/latest) (3-day free trial) · [Licence key](https://becasseau.gumroad.com/l/walldock-pro) (one-time €4.99, 3 tablets).

Support the project: [Ko-fi](https://ko-fi.com/psycho1807)

License: GPL-3.0.
