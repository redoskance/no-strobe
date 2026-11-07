# Release v0.2: Video & Photo Modes, On-Device Post & LAN Offload

## 🇫🇷 Nouveautés de la version 0.2

### 📱 Fonctionnalités Mobile
- **Modes Vidéo & Photo (Sélecteur au-dessus du déclencheur)** : Basculez instantanément entre la vidéo anti-scintillement et la prise de vue photo haute résolution, calée sur l'exposition et l'ISO synchronisés.
- **Post-traitement sur l'appareil (Local)** : Deux passes complètes (analyse SIMD puis réencodage AVC matériel) pour corriger le pompage de luminosité et le banding sans nécessiter d'ordinateur.
- **Galerie Avant / Après intégrée (Vidéos & Photos)** : Affiche les vidéos et les photos prises par l'application dans leur orientation exacte (support des métadonnées EXIF). Comparez immédiatement vos vidéos avec le bouton « Maintenir pour comparer », visualisez les clips en boucle et partagez facilement vos créations.
- **Déport sur serveur LAN (`no-strobe-post serve`)** : Connectez votre téléphone à votre serveur local en Wi-Fi pour déporter les traitements lourds avec détection de stockage faible (< 500 Mo) et bouton de test de connectivité.
- **Support des smartphones pliables (Pixel Fold, etc.)** : Transition fluide entre les caméras frontales externe et interne, et ergonomie adaptée en mode déplié (viseur 16:9 à gauche, commandes à droite).
- **Menu de diagnostic technique masqué** : 5 tapotements sur le badge de statut permettent d'ouvrir un rapport complet et de l'exporter en un clic vers GitHub Issues (format épuré sans émojis).
- **Thème système & compatibilité étendue** : Intégration Material 3 adaptative (thème sombre/clair du système), correction de compatibilité Android 9 et nouvelle icône de lanceur.

---

## 🇬🇧 Release Notes (English)

### 📱 Mobile Features & Improvements
- **Video & Photo Modes (Above-Shutter Selector)**: Switch seamlessly between flicker-free video recording and high-resolution still photography carrying locked exposure and ISO parameters.
- **On-Device Post-Processing (Local)**: Full dual-pass correction pipeline (SIMD luma pass + hardware AVC encoding) flattening temporal pumping and row banding directly on the phone.
- **Integrated Before / After Gallery (Videos & Photos)** : Displays both recorded videos and still photos in their correct upright orientation via EXIF rotation metadata parsing. Features instant comparison viewer with a tactile "Hold to compare" interaction, clip sharing via `FileProvider`, and background processing status.
- **LAN Server Offloading (`no-strobe-post serve`)**: Connect your phone to a desktop server over Wi-Fi for compute-heavy clips, featuring low phone storage warnings (< 500 MB) and connection testing.
- **Foldable Devices Support (Pixel Fold, etc.)**: Dynamic routing between cover and inner front cameras, and unfolded dual-pane layout (16:9 viewfinder on left, controls on right).
- **Diagnostic Report & GitHub Export**: Concealed 5-tap debug report menu for instant, emoji-free bug reports exported directly into GitHub issues.
- **System Theme & Wide Compatibility**: Full Material 3 integration following system theme, Android 9 backwards compatibility fixes, and new launcher icon.

---

### 📦 Artifacts & Downloads
- `app-debug.apk`: Android application debug package for sideloading on ARM64 / ARMv7 devices.
- `no-strobe-post`: Standalone desktop post-processing & LAN server binary (x86_64 Linux).
- `AntiStrobe-Simulator.app.zip`: Mockup iOS simulator build.
