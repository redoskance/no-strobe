# no-strobe

[🇫🇷 Français](#-version-française) | [🇬🇧 English](#-english-version)

---

## 🇫🇷 Version Française

Application caméra pour Android et iOS conçue pour filmer des concerts sans scintillement ni bandes parasites (*banding*) causées par les éclairages LED et écrans de scène. Elle mesure la fréquence de modulation PWM de la source lumineuse et cale l'exposition du capteur sur un multiple entier exact de la période de scintillement (`exposition = k/f`), garantissant que chaque trame et chaque ligne du capteur reçoivent la même quantité de lumière.

**Périmètre : scintillement PWM / LED (100 Hz – 5 kHz).** C'est le mode opérationnel principal.

| Mode | Source lumineuse | Fréquence | Statut |
|---|---|---|---|
| **PWM** | Projecteurs LED, panneaux de scène, écrans | 100 Hz – 5 kHz | **Cible principale**, temps réel sur Android (iOS : pas encore fonctionnel, voir *Limites connues*) |
| **Strobe** | Éclairs délibérés lents (lampes de police) | 1 Hz à 0,4 × fps (12 Hz à 30 images/s) | Expérimental (voir *Modes stroboscopiques*) |

Un outil de post-traitement sur ordinateur permet également de corriger les vidéos enregistrées sans synchronisation (voir *Post-traitement*).

Le contexte des strobes lents (lampes de police) et les applis qui s'y rapportent sont décrits dans [STROBE-POLICE.md](STROBE-POLICE.md).

Pour installer l'application sur votre smartphone sans passer par les stores, consultez le [Guide d'installation pas à pas](INSTALL.md).
Pour la liste des prérequis matériels et des modèles testés, consultez [COMPATIBILITY.md](COMPATIBILITY.md).

---

### Pourquoi la synchronisation d'exposition fonctionne

- **Le scintillement temporel** provient du fait que des trames successives intègrent un nombre variable de cycles lumineux. Si la fenêtre d'exposition est un multiple entier exact de la période $T = 1/f$, chaque trame intègre exactement la même énergie lumineuse : le pompage de luminosité disparaît, quelle que soit la phase.
- **Le banding de rolling shutter** provient du décalage de lecture temporelle entre les lignes du capteur. Chaque ligne est exposée pendant `exposition + sustain`, où `sustain` correspond au temps de lecture ligne (`SENSOR_INFO_ROLLING_SHUTTER_SUSTAIN_DURATION` sur Android). Si cette fenêtre traverse plusieurs cycles lumineux, le haut et le bas de l'image voient des phases différentes, créant des barres horizontales. Choisir `exposition = k/f` annule ces barres.
- **Convergence spatiale sub-seconde (< 500 ms)** : Au lieu d'un balayage empirique lent de 60 secondes, l'autocorrélation spatiale robuste (filtrage passe-haut laplacien, seuillage MAD et corrélation de Pearson) extrait la fréquence fondamentale $f$ du PWM en une seule trame (33 ms).
- **Filtrage prédictif (PLL logicielle)** : Dans Camera2, une commande d'exposition envoyée à la trame $N$ n'est appliquée par le capteur qu'à la trame $N+2$ ou $N+3$ (profondeur de pipeline). Une boucle à verrouillage de phase (PLL du 2nd ordre) suit la phase du scintillement et compense ce retard avec 2 à 3 trames d'avance.

---

### Modes stroboscopiques (Expérimental)

> Considérez ce mode comme expérimental et secondaire par rapport au flicker LED. La synchronisation d'exposition ne peut pas éliminer un stroboscope très lent lorsque `exposition = k·T` dépasse la durée de la trame (ex. 10 Hz à 30 fps).

| Mode | Exposition | Effet produit |
|---|---|---|
| `Integrate` | $k \cdot T$ | Énergie stroboscopique uniforme entre les trames, image lumineuse |
| `Gap` | intervalle sombre − sustain | Éclairs stroboscopiques totalement occultés |
| `Flash` | fenêtre d'un éclair | Chaque trame capture exactement un seul flash |

---

### Mode PWM (Temps Réel)

La luminance moyenne par image ne permet pas d'observer les fréquences supérieures à $f_{ps}/2$ (à 30 fps, tout ce qui dépasse 15 Hz subit un repliement de spectre). Le mode PWM analyse donc le **profil de luminance ligne par ligne** :
1. `LineProfileExtractor` extrait le profil moyen de luminance par ligne depuis le plan YUV.
2. `robust_period_rows` applique un filtre laplacien spatial $\Delta^2$, centre le signal, et calcule la corrélation de Pearson pour détecter la période $T$ en nombre de lignes en moins d'une trame.
3. Le contrôleur bascule instantanément à `k/f`, calcule la compensation ISO pour maintenir l'exposition globale, et engage la PLL pour maintenir le calage de phase.
4. Si le temps de lecture ligne est inconnu (iOS), le contrôleur effectue une descente de gradient sur l'amplitude de banding résiduelle.
5. **Rejet du bruit** : une modulation mesurée plus forte que ce qu'un scintillement de 100 Hz ou plus peut produire (scène sombre, bruit du capteur) n'est pas retenue comme verrouillage. Une fréquence lue sur les lignes n'est acceptée qu'après une vérification dans le temps (le centre de l'exposition contre des sondes plus lointaines).
6. **ISO** : pendant la recherche, l'ISO reste sous 1 600 (au-delà, l'aperçu et les mesures sont surtout du bruit). Au-delà de 3 200, une pose plus longue, toujours sur un multiple de la période, est préférée.

---

### Fonctionnalités de l'Application Mobile

- **Modes Vidéo, Photo & Strobe (sélecteur au-dessus du déclencheur : VIDEO | PHOTO | STROBE)** : VIDEO enregistre une vidéo anti-scintillement, PHOTO prend une rafale haute résolution, STROBE enregistre avec la recherche lente de strobe. Passer vers ou depuis STROBE redémarre la caméra ; le mode choisi est mémorisé.
- **Post-traitement sur l'appareil (Local)** : Deux passes complètes (analyse SIMD puis réencodage AVC matériel) pour corriger le pompage de luminosité et le banding sans nécessiter d'ordinateur.
- **Galerie Avant / Après intégrée (Vidéos & Photos)** : Affiche les vidéos et les photos dans leur orientation exacte (métadonnées EXIF). Permet de comparer immédiatement vos prises avec un bouton interactif « Maintenir pour comparer », de partager vos créations et de visualiser les résultats en boucle.
- **Déport sur serveur LAN (`no-strobe-post serve`)** : Connectez votre téléphone à votre serveur local en Wi-Fi pour déporter les traitements lourds avec détection automatique de stockage faible (< 500 Mo) et bouton de test de connectivité.
- **Support des smartphones pliables** : Transition fluide entre les caméras frontales externe et interne, et ergonomie adaptée en mode déplié (viseur 16:9 à gauche, commandes à droite).
- **Menu de diagnostic technique masqué** : 5 tapotements sur le badge de statut permettent d'ouvrir un rapport complet et de l'exporter en un clic vers GitHub Issues.
- **Photos en rafale** : une photo est une rafale de 6 trames, à exposition et ISO fixes. Comme une impulsion de strobe peut frapper certaines trames seulement, la trame qu'aucune impulsion n'a frappée est conservée : les trames sont comparées sur la luminance brute, les trames nettement plus claires (impulsion) sont écartées, et la moins marquée par le banding est gardée.
- **Mode STROBE** : recherche lente d'un strobe de 1 Hz à 0,4 × fps. La période est déduite des instants des impulsions, et l'exposition de recherche couvre presque toute la durée d'une image *telle que la caméra la livre* (un téléphone demandé à 60 images/s peut en livrer 30). Il faut un train régulier, une impulsion dans presque chaque cycle : une lumière qui clignote par moments ne verrouille pas. Le verrouillage est revérifié toutes les 20 s (la recherche reprend 2 à 4 s) et abandonné si le strobe s'est arrêté. Au-delà de fps/2 la lumière est repliée (alias) : il faut 60 images/s pour suivre un strobe au-dessus de 12 Hz.
- **Enregistrement HEVC** : quand l'appareil dispose d'un encodeur HEVC, les vidéos sont enregistrées en H.265 (plus compact à bruit égal). Elles se corrigent sur le téléphone ou avec `no-strobe-post` sur ordinateur.
- **Écran toujours allumé** : l'écran reste allumé tant que l'application est au premier plan, pour qu'une prise longue ne soit pas interrompue par la mise en veille.

---

### Limites connues (version 0.3.1)

- **Scintillement PWM sur matériel** : la voie par lignes et sa vérification temporelle n'ont pas été revalidées sur un vrai scintillement LED depuis ces modifications. Les essais récents utilisent un scintillement simulé et des profils enregistrés sur téléphone.
- **Scintillement de 120 ou 300 Hz devant une scène structurée** : un multiple de la cadence d'images ressemble, image après image, à la structure de la scène (texte, clavier) ; pas de verrouillage.
- **Scène sombre et bruitée** : avec un bruit par ligne d'environ 2 % de la luminance, un scintillement de faible profondeur n'est pas confirmé.
- **Rafales photo** : jamais observées sous un vrai strobe.
- **Strobe et repliement** : au-dessus de fps/2 (15 Hz à 30 images/s), la luminance des trames est repliée ; un strobe plus rapide peut alors être confondu avec un strobe plus lent. La recherche s'arrête à 0,4 × fps.
- **iOS** : l'application iOS est une maquette et ne traite pas le scintillement LED.
- **Appareils 32 bits** : aucun appareil 32 bits réel n'a été testé (l'APK embarque armeabi-v7a).

---

### Arborescence du projet

```
Cargo.toml             Workspace Rust : ns-core, ns-ffi, ns-post
crates/ns-core/        Cœur algorithmique pur sans dépendance externe
  src/lib.rs           Point d'entrée et API publique
  src/camera.rs        Limites du capteur (plages d'exposition, sustain, fps, iso)
  src/pll.rs           Boucle à verrouillage de phase (PLL 2e ordre) et prédiction pipeline
  src/pwm.rs           Filtrage laplacien, autocorrélation robuste, simulation de profils
  src/pwm_controller.rs Contrôleur PWM rapide (< 500 ms) + suivi PLL
  src/controller.rs    Contrôleur stroboscopique (Search → Locked)
  src/restorer.rs      Post-traitement : gain temporel par trame, gain spatial par ligne
crates/ns-ffi/         Interface C (en-tête cbindgen) et passerelle JNI pour Android
crates/ns-post/        Outil no-strobe-post : CLI, serveur HTTP offload, pipeline ffmpeg
platform/android/      Application Android (Gradle, Camera2, Material You / M3)
platform/ios/          Maquette iOS (SwiftUI, AVFoundation), pas encore fonctionnelle pour le scintillement
tools/                 Scripts de test, banc d'essai et simulation de scintillement
```

---

### Compilation et Installation

Pour les utilisateurs finaux, téléchargez directement le fichier `app-debug.apk` depuis les [Releases](https://github.com/redoskance/no-strobe/releases) et suivez le [Guide d'installation](INSTALL.md).

#### Compilation native (Android NDK)
```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
cargo ndk -t arm64-v8a -t armeabi-v7a -o platform/android/app/src/main/jniLibs \
    build -p ns-ffi --release --features android
```

#### Assemblage de l'APK Android
```sh
cd platform/android
gradle :app:assembleDebug   # Gradle 9.x ; le dépôt n'a pas de wrapper gradlew
# Génère platform/android/app/build/outputs/apk/debug/app-debug.apk
```

---

### Post-traitement sur ordinateur (`no-strobe-post`)

Pour corriger une vidéo enregistrée avec une caméra conventionnelle :

```sh
cargo build --release -p ns-post
target/release/no-strobe-post fix concert.mp4 --line-rate 40000 -o concert-corrige.mp4
```

Vous pouvez également lancer le serveur local pour décharger le calcul depuis le smartphone :
```sh
export NO_STROBE_TOKEN=mon-secret
target/release/no-strobe-post serve --listen 0.0.0.0:8765
```

---

### Contribution

Consultez [`CONTRIBUTING.md`](CONTRIBUTING.md) pour les conventions de code, la signature GPG des commits et les directives de test.

### Licence

Ce projet est publié sous la licence WTFPL (*Do What The Fuck You Want To Public License*, version 2). Voir [`LICENSE`](LICENSE).

---

<br/>

---

## 🇬🇧 English Version

Camera app (Android + iOS) that films concerts without LED flicker. It measures the flicker
of LED projectors and stage screens, then sets the sensor exposure to a whole number of
flicker cycles (`exposure = k/f`), so every frame and every sensor row integrates the same
amount of light: no banding, no brightness pumping.

**Scope: LED / PWM flicker, 100 Hz–5 kHz.** This is the supported mode.

| Mode | Light source | Frequency | Status |
|---|---|---|---|
| **PWM** | LED projectors, stage screens | 100 Hz–5 kHz | **main target**, real-time on Android (iOS: not implemented yet, see *Known limits*) |
| **Strobe** | deliberate slow flashes (police torches) | 1 Hz to 0.4 × fps (12 Hz at 30 fps) | experimental, outside the project scope (see *Strobe modes*) |

A desktop post-processing tool fixes clips recorded without sync (see *Post-processing*).

The context of slow strobes (police torches) and the related apps are described in [STROBE-POLICE.md](STROBE-POLICE.md).

For direct APK installation on your smartphone, see the step-by-step [Installation Guide](INSTALL.md).
For theoretical hardware requirements and verified devices, see [COMPATIBILITY.md](COMPATIBILITY.md).

---

### Why exposure sync works

- **Flicker** comes from frames integrating a different number of cycles. If the exposure
  window is an exact integer multiple of the period `T`, every frame integrates the same
  amount of light → no flicker, and the phase no longer matters.
- **Rolling-shutter banding** comes from rows being read out at different times. A row is
  exposed over `exposure + sustain`, where `sustain` is the sensor readout duration
  (`SENSOR_INFO_ROLLING_SHUTTER_SUSTAIN_DURATION` on Android, unpublished on iOS). If that
  window spans several cycles, top and bottom of the frame see different phases → horizontal
  bands. Exposure = `k/f` removes them.
- **Sub-second spatial convergence (< 500 ms)**: Rather than taking 60 seconds with empirical stepping, robust spatial autocorrelation (2nd-difference Laplacian pre-filter, MAD Winsorization, and mean-subtracted Pearson correlation) locks onto the PWM fundamental frequency $f$ in a single frame (33 ms).
- **Predictive pipeline filtering (Software PLL)**: In Camera2, an exposure command issued at frame $N$ is only applied by sensor hardware at frame $N+2$ or $N+3$ (pipeline depth). A 2nd-order software PLL tracks the flicker phase and compensates this latency with 2 to 3 frames of lead prediction.

---

### Strobe modes (Experimental)

> Experimental and outside the project scope (LED flicker only). Kept because the code exists
> and is tested, but it is not the target of the app. Exposure sync alone cannot flatten a
> slow strobe when `exposure = k·T` exceeds the frame period (e.g. 10 Hz at 30 fps).

| Mode | Exposure | Effect |
|---|---|---|
| `Integrate` | `k·T` | strobe energy uniform across frames, image stays bright |
| `Gap` | dark interval − sustain | strobe fully removed from the image |
| `Flash` | one flash window | every frame captures exactly one flash |

---

### PWM mode (Real-Time)

Frame-mean luma cannot resolve above `fps/2` — at 30 fps it aliases everything past 15 Hz. So
the PWM path reads the **per-row luminance profile** of a frame: the rolling shutter samples
light at the row readout rate (20–100 kHz), so a 100 Hz–5 kHz flicker shows up as a vertical
banding pattern.

1. `LineProfileExtractor` builds a per-row mean luma array from the Y plane.
2. `robust_period_rows` applies a discrete Laplacian filter $\Delta^2$, centers the profile, and computes Pearson cross-correlation to estimate $T$ in rows in a single frame.
3. The controller switches directly to `k/f`, compensates ISO to maintain overall frame exposure, and activates the PLL.
4. When the sensor's row readout rate is unknown (iOS), the controller hill-climbs on banding amplitude directly.
5. **Noise rejection**: a measured modulation stronger than a 100 Hz or faster flicker can produce (dark scene, sensor noise) is not accepted as a lock. A frequency read from the rows is accepted only after a check over time (the centre of the exposure against farther probes).
6. **ISO**: during the search, ISO stays below 1,600 (above it the preview and the measurements are mostly noise). Above 3,200, a longer exposure, still on a multiple of the period, is preferred.

---

### Mobile Application Features

- **Video, Photo & Strobe Modes (Above-Shutter Selector: VIDEO | PHOTO | STROBE)**: VIDEO records flicker-free video, PHOTO takes a high-res burst, STROBE records with the slow strobe search. Switching to or from STROBE restarts the camera; the chosen mode is remembered.
- **On-Device Post-Processing (Local)**: Full dual-pass correction pipeline (SIMD luma pass + hardware AVC encoding) flattening temporal pumping and row banding directly on the phone.
- **Integrated Before / After Gallery (Videos & Photos)**: Displays both video clips and still photos in their correct orientation via EXIF rotation metadata. Features instant comparison viewer with a tactile "Hold to compare" interaction, clip sharing via `FileProvider`, and background processing progress.
- **LAN Server Offloading (`no-strobe-post serve`)**: Easy Wi-Fi connection to a desktop server for compute-heavy clips, automatic low-storage warnings (< 500 MB), and connection testing.
- **Foldable Devices Support**: Dynamic routing between cover and inner front cameras, and unfolded dual-pane layout (16:9 viewfinder on left, controls on right).
- **Diagnostic Report & GitHub Export**: Hidden 5-tap debug report menu for instant, emoji-free bug reports exported directly into GitHub issues.
- **Burst photos**: a photo is a burst of 6 frames with fixed exposure and ISO. A strobe pulse may hit only some of them, so the frame no pulse hit is kept: frames are compared on raw mean luma, clearly brighter frames (a pulse) are discarded, and the least banded of the rest is kept.
- **STROBE mode**: slow strobe search from 1 Hz to 0.4 × fps. The period is taken from the pulse times, and the search exposure covers most of a frame *as the camera delivers it* (a phone asked for 60 fps may deliver 30). It takes a regular train, a pulse in nearly every cycle: a light that blinks now and then does not lock. The lock is checked again every 20 s (the search resumes for 2 to 4 s) and dropped when the strobe has stopped. Above fps/2 the light aliases: a strobe above 12 Hz needs 60 frames per second to be followed.
- **HEVC recording**: when the device has an HEVC encoder, videos are recorded in H.265 (smaller files at the same noise level). They are corrected on the phone or with `no-strobe-post` on a computer.
- **Screen kept on**: the screen stays on while the app is in front, so a long take is not cut by the screen timeout.

---

### Known limits (0.3.1)

- **PWM flicker on hardware**: the row-rate path and its time check have not been revalidated on a real LED flicker since these changes. Recent tests use a simulated flicker and row profiles recorded on a phone.
- **120 or 300 Hz flicker in front of a structured scene**: a multiple of the frame rate looks, frame after frame, like the structure of the scene (text, a keyboard); no lock.
- **Dark, noisy scene**: with row noise around 2 % of the luminance, a shallow flicker is not confirmed.
- **Burst photos**: never observed under a real strobe.
- **Strobe aliasing**: above fps/2 (15 Hz at 30 fps) the frame luma aliases; a faster strobe can then be mistaken for a slower one. The search stops at 0.4 × fps.
- **iOS**: the iOS app is a mock-up and does not handle LED flicker.
- **32-bit devices**: no real 32-bit device has been tested (the APK ships armeabi-v7a).

---

### Repository Layout

```
Cargo.toml             workspace: ns-core, ns-ffi, ns-post
crates/ns-core/        platform-free algorithm (no external deps)
  src/lib.rs           module list, public API
  src/camera.rs        sensor limits (exposure range, rolling-shutter sustain, fps, iso)
  src/pll.rs           software PLL (2nd order loop filter) & pipeline lead predictor
  src/pwm.rs           discrete Laplacian filter, robust autocorrelation, synthetic profiles
  src/pwm_controller.rs fast PWM controller (< 500 ms lock) & PLL integration
  src/controller.rs    strobe path: Search → Locked
  src/restorer.rs      post-processing: per-frame gain, per-row gain
crates/ns-ffi/         C API (cbindgen header) + JNI for Android
crates/ns-post/        no-strobe-post: fix.rs (streaming ffmpeg), serve.rs (HTTP server), main.rs (CLI)
platform/android/      Android app (Gradle, Camera2, Material 3 UI)
platform/ios/          iOS mock-up (SwiftUI, AVFoundation); not a working flicker app yet
tools/                 test scripts, flicker generator, test probes
```

---

### Building and Installation

Users can download the latest `app-debug.apk` directly from GitHub [Releases](https://github.com/redoskance/no-strobe/releases) and follow the [Installation Guide](INSTALL.md).

#### Build native JNI libraries
```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
cargo ndk -t arm64-v8a -t armeabi-v7a -o platform/android/app/src/main/jniLibs \
    build -p ns-ffi --release --features android
```

#### Assemble Android APK
```sh
cd platform/android
gradle :app:assembleDebug   # Gradle 9.x; the repository has no gradlew wrapper
# Produces platform/android/app/build/outputs/apk/debug/app-debug.apk
```

---

### Desktop Post-Processing (`no-strobe-post`)

To deflicker clips recorded on standard cameras:

```sh
cargo build --release -p ns-post
target/release/no-strobe-post fix clip.mp4 --line-rate 40000 -o clip-fixed.mp4
```

To offload processing from your smartphone:
```sh
export NO_STROBE_TOKEN=some-secret
target/release/no-strobe-post serve --listen 0.0.0.0:8765
```

---

### Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for branch guidelines, GPG signing requirements, and testing guidelines.

### License

This project is released under the WTFPL (*Do What The Fuck You Want To Public License*, version 2). See [`LICENSE`](LICENSE).
