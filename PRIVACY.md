# WallDock — Politique de confidentialité / Privacy policy

*Dernière mise à jour / Last updated: 12 novembre 2026 / November 12, 2026*

Cette politique s'applique à **WallDock** et **WallDock Pro** (applis Android), publiées par psycho1807.
This policy applies to **WallDock** and **WallDock Pro** (Android apps), published by psycho1807.

---

## Français

**En bref : WallDock ne collecte aucune donnée personnelle. Rien n'est envoyé au développeur ni à un tiers.** L'appli échange uniquement avec les services que **vous** configurez sur **votre** réseau (votre serveur Home Assistant, et, si vous les activez, votre serveur MQTT et vos autres tablettes WallDock).

### Données traitées sur la tablette
- **Réglages** (adresse de votre Home Assistant, code administrateur, choix d'affichage…) : enregistrés uniquement sur la tablette. Une sauvegarde n'est créée que si vous la demandez, dans un fichier que vous choisissez.
- **Micro** (assistant vocal, si vous l'activez) : le son est analysé sur la tablette pour reconnaître le mot d'activation. Après ce mot, ou quand vous touchez le bouton de l'assistant, votre phrase est envoyée **à votre Home Assistant**, qui la traite selon votre configuration (par exemple avec un service de reconnaissance vocale que vous avez choisi). Aucun enregistrement n'est conservé par l'appli. Si vous « apprenez une voix » ou un mot, des empreintes numériques (pas d'enregistrement audio) sont gardées sur la tablette pour améliorer la reconnaissance ; vous pouvez les effacer à tout moment.
- **Caméra** (détection de mouvement, si vous l'activez) : les images sont analysées sur la tablette pour détecter un mouvement, puis immédiatement oubliées. Aucune image n'est enregistrée ni envoyée ; seul l'état « mouvement / pas de mouvement » est transmis à votre Home Assistant.
- **Capture d'écran** : faite uniquement quand votre Home Assistant (ou, dans WallDock Pro, la page d'administration protégée par mot de passe) la demande, et envoyée seulement à celui-ci.
- **Service d'accessibilité** (facultatif ; absent de la version Google Play de WallDock) : sert à savoir si quelqu'un utilise l'appli ouverte depuis le dock, et, dans WallDock Pro, à capturer l'écran et à effectuer les touchers que vous envoyez depuis votre Home Assistant ou la page d'administration. Il ne lit pas le contenu des autres applis.
- **Interphone** (WallDock Pro) : le son va directement d'une tablette à l'autre sur votre réseau local, chiffré avec le code que vous avez choisi ; rien n'est enregistré.
- **Capteurs envoyés à votre Home Assistant** : batterie, température de la batterie, signal Wi-Fi, appli ouverte, inactivité, état de l'assistant.

### Connexions Internet faites par l'appli
- Vers les adresses que vous configurez (votre Home Assistant, les pages et onglets que vous ajoutez).
- **Version GitHub uniquement** : vérification des mises à jour sur GitHub (github.com), sauf si vous désactivez les mises à jour automatiques.
- Téléchargement, une seule fois, de deux modèles de reconnaissance vocale publics depuis GitHub (projet openWakeWord).
Ces services peuvent, comme tout site web, voir l'adresse IP de la connexion ; WallDock ne leur transmet aucune autre information.

### Ce que WallDock ne fait pas
Pas de compte, pas de publicité, pas de statistiques d'utilisation, pas de traceur, pas de revente de données.

### Enfants
WallDock n'est pas destinée aux enfants et ne collecte pas de données les concernant.

### Contact
Questions : ouvrez un ticket sur https://github.com/psycho1807/WallDock/issues

---

## English

**In short: WallDock collects no personal data. Nothing is sent to the developer or to any third party.** The app only talks to the services **you** configure on **your** network (your Home Assistant server and, if you enable them, your MQTT broker and your other WallDock tablets).

### Data processed on the tablet
- **Settings** (your Home Assistant address, admin code, display choices…): stored only on the tablet. A backup is created only when you ask for it, in a file you choose.
- **Microphone** (voice assistant, if enabled): audio is analysed on the tablet to detect the wake word. After the wake word, or when you tap the assistant button, your sentence is sent **to your Home Assistant**, which processes it according to your own configuration. The app keeps no recordings. If you "learn a voice" or a word, numerical fingerprints (not audio recordings) are kept on the tablet to improve recognition; you can delete them at any time.
- **Camera** (motion detection, if enabled): frames are analysed on the tablet to detect motion and immediately discarded. No image is stored or sent; only the "motion / no motion" state is sent to your Home Assistant.
- **Screenshot**: taken only when your Home Assistant (or, in WallDock Pro, the password-protected admin page) asks for it, and sent only to it.
- **Accessibility service** (optional; not included in the Google Play version of WallDock): used to know whether someone is using the app opened from the dock and, in WallDock Pro, to capture the screen and perform the taps you send from your Home Assistant or the admin page. It does not read the content of other apps.
- **Intercom** (WallDock Pro): audio goes directly from one tablet to another on your local network, encrypted with the code you chose; nothing is recorded.
- **Sensors sent to your Home Assistant**: battery, battery temperature, Wi-Fi signal, open app, inactivity, assistant state.

### Internet connections made by the app
- To the addresses you configure (your Home Assistant, the pages and tabs you add).
- **GitHub version only**: update checks on GitHub (github.com), unless you turn automatic updates off.
- One-time download of two public speech models from GitHub (openWakeWord project).
Like any website, these services may see the connection's IP address; WallDock sends them no other information.

### What WallDock does not do
No account, no ads, no usage analytics, no trackers, no data selling.

### Children
WallDock is not directed at children and collects no data about them.

### Contact
Questions: open an issue at https://github.com/psycho1807/WallDock/issues
