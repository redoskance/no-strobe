# Compatibilité Matérielle et Appareils Vérifiés / Device Compatibility

[🇫🇷 Français](#-version-française) | [🇬🇧 English](#-english-version)

---

## 🇫🇷 Version Française

Ce document détaille les **exigences techniques théoriques** requises pour exécuter l'application **no-strobe** et tirer parti de la synchronisation d'exposition en temps réel, ainsi que la **liste des appareils physiques et environnements vérifiés** avec leur version validée.

---

### 1. Exigences Techniques Théoriques

Pour éliminer le scintillement (*flicker*) PWM et les bandes horizontales de balayage (*rolling-shutter banding*), l'appareil doit permettre un contrôle millimétré de son capteur optique via l'API Camera2 d'Android.

#### A. Système d'exploitation & Versions Android
- **Version minimale** : **Android 9.0 (Pie) — API 28**.
  - L'application démarre et fonctionne sur API 28.
  - Les appels aux API plus récentes (comme `CameraCharacteristics.Key` dynamique sur API 29+) sont isolés avec gestion d'exception rétrocompatible.
- **Version recommandée** : **Android 12 (S) — API 31 ou supérieure**.
  - À partir de l'API 31, le système fournit la clé officielle `SENSOR_INFO_ROLLING_SHUTTER_SUSTAIN_DURATION` (durée de lecture d'une ligne du capteur).
  - Sur les versions antérieures (API 28 à 30), le temps de lecture ligne est déduit dynamiquement ou estimé par balayage de profil.
- **Version cible (Target SDK)** : **Android 14 — API 34** (testé et validé jusqu'à Android 17 / API 37).

#### B. Capacités Camera2 Obligatoires & Recommandées
| Capacité / Clé Camera2 | Rôle pour no-strobe | Niveau de criticité |
|---|---|---|
| `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR` | Permet de débrayer l'exposition automatique (AE) et d'appliquer manuellement le temps de pose (`SENSOR_EXPOSURE_TIME`), la sensibilité (`SENSOR_SENSITIVITY`) et la durée de trame (`SENSOR_FRAME_DURATION`). | **Indispensable**. Sans cette capacité, l'application bascule en mode automatique `autoOnly` (aucun dé-banding possible). |
| `INFO_SUPPORTED_HARDWARE_LEVEL` | Niveau matériel du pilote de caméra. | **`FULL` ou `LEVEL_3` recommandé**. `LIMITED` est toléré si `MANUAL_SENSOR` est présent. `LEGACY` non supporté. |
| `SENSOR_INFO_EXPOSURE_TIME_RANGE` | Plage d'exposition du capteur. | **Obligatoire**. Le capteur doit pouvoir descendre à des poses très brèves ($\le 100\,\mu\text{s}$, idéalement $20\,\mu\text{s}$) pour mesurer et caler des fréquences PWM élevées (> 1 kHz). |
| `SENSOR_INFO_SENSITIVITY_RANGE` | Plage de sensibilité ISO. | **Obligatoire**. Utilisée pour compenser le niveau lumineux lorsque le temps d'exposition est contraint sur $k/f$. |
| `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` | Plages de cadence d'image disponibles (ex: `[30, 30]`, `[60, 60]`). | **Recommandé**. Permet de verrouiller la cadence vidéo sans pompage de framerate. |
| `SENSOR_INFO_ROLLING_SHUTTER_SUSTAIN_DURATION` | Durée de balayage ligne (*rolling shutter sustain*). | **Fortement recommandé** (API 31+). Permet la convergence instantanée sub-seconde (< 500 ms) sans balayage empirique. |
| Flux concurrents simultanés | Sortie simultanée : Viseur (SurfaceView 16:9) + Analyse (`ImageReader` YUV_420_888 $\le 720\text{p}$) + Enregistreur (`MediaRecorder` ou `MediaCodec`). | **Obligatoire**. Requiert au moins 3 flux de sortie concurrents. |

#### C. Architectures Processeur & NDK
- **`arm64-v8a` (ARM 64-bit)** : Architecture de référence. Vectorisation SIMD NEON active pour l'extraction de profils de lignes et l'autocorrélation rapide.
- **`armeabi-v7a` (ARM 32-bit)** : Supporté. *(Remarque : sur certaines ROMs 64-bit exécutant du 32-bit, un bug framework dans `Image.getPlanes()` désactive l'analyse en temps réel et bascule en enregistrement auto).*
- **`x86_64`** : Supporté pour les émulateurs de développement et de CI.

#### D. Encodage Vidéo & Accélération Graphique
- **Codecs vidéo** : Encodage matériel H.264 / AVC ou H.265 / HEVC. HEVC est sélectionné par défaut si l'appareil dispose de l'encodeur matériel approprié.
- **Vulkan 1.1+** : Requis pour les pipelines de post-traitement de restauration directe sur l'appareil (`OnDevicePost`).

---

### 2. Liste des Appareils et Environnements Vérifiés

Le tableau ci-dessous recense les appareils sur lesquels l'application a été déployée, testée et validée en conditions réelles ou simulées.

| Appareil / Environnement | Type | SoC / Processeur | OS & API | Version no-strobe | Statut & Validation |
|---|---|---|---|---|---|
| **Google Pixel 11 Pro Fold** / **Pixel 10 Pro XL** (`mustang`) | Physique (Pliable) | Google Tensor | Android 17 (API 37 Preview) | **v0.3.1** | **Validé à 100%**. Synchronisation PWM en direct, modes stroboscopiques (17 Hz duty 70% et < 1,5 Hz), rafale photo anti-strobe (`BurstPick`), transitions plié/déplié (sw600dp/sw700dp), encodage HEVC, permissions et signature release v2. |
| **Sony Xperia 1 III** (`pdx215` / `XQ-BC52`) | Physique | Qualcomm Snapdragon 888 | Android 11 à 13 (API 30–33) | **v0.1 / v0.2** | **Validé**. Verrouillage PWM confirmé sur vraie lampe 100 Hz (~10,3 ms, 99,5 Hz) et simulation 500 Hz. Mode High Speed 240 fps. Gestion du repli de flux en cas de restriction de caméra physique. |
| **Émulateur Android API 34** (Tablette & Pliable) | Virtuel (AVD) | x86_64 | Android 14 (API 34) | **v0.3.1** | **Validé**. Interface adaptative grand écran (`res/layout-sw600dp`), internationalisation FR/EN, configuration de sécurité réseau (`network_security_config`). |
| **Émulateur Android API 30** (Téléphone) | Virtuel (AVD) | x86_64 | Android 11 (API 30) | **v0.2 / v0.3.1** | **Validé**. Rétrocompatibilité API 30 (absence de sustain natif, repli sur cadence apprise). |
| **Émulateur Android API 28** | Virtuel (AVD) | x86_64 | Android 9.0 (API 28) | **v0.3.1** | **Validé**. Non-régression NewApi, initialisation sans crash sur Android Pie. |
| **Apple iPhone / iOS** | Maquette | Apple Silicon | iOS 16+ | Maquette v0.1 | **Non fonctionnel**. Maquette SwiftUI/AVFoundation existante dans `platform/ios`, mais l'absence d'exposition publique du temps de lecture ligne (*sustain*) sur iOS empêche pour l'instant le verrouillage temps réel. |

---

### 3. Matrice des Fonctionnalités par Profil d'Appareil

| Fonctionnalité | Appareil avec `MANUAL_SENSOR` + API 31+ | Appareil avec `MANUAL_SENSOR` (API 28–30) | Appareil `LIMITED` sans `MANUAL_SENSOR` |
|---|:---:|:---:|:---:|
| **Verrouillage PWM en direct (100 Hz – 5 kHz)** | **Optimal** (< 500 ms) | **Fonctionnel** (après calibrage/balayage) | Non supporté (Auto-exposition seule) |
| **Verrouillage Stroboscopique (1 Hz – 20 Hz)** | **Optimal** | **Optimal** | Non supporté |
| **Rafale Photo Anti-Strobe (`BurstPick`)** | **Supporté** (AE Lock) | **Supporté** (AE Lock) | Non supporté |
| **Enregistrement HEVC / AVC 1080p** | **Supporté** | **Supporté** | **Supporté** |
| **Déport et streaming LAN** | **Supporté** (Cleartext local sécurisé) | **Supporté** | **Supporté** |
| **Restauration Post-traitement embarquée** | **Supporté** (GPU Vulkan) | **Supporté** (si Vulkan 1.1) | **Supporté** (si Vulkan 1.1) |

---

<br/>

---

## 🇬🇧 English Version

This document outlines the **theoretical hardware and software requirements** needed to run **no-strobe** and achieve real-time exposure synchronization, along with the **verified list of physical devices and test environments** with their verified app versions.

---

### 1. Theoretical Technical Requirements

To eliminate LED PWM flicker and rolling-shutter banding, a smartphone camera must expose granular sensor controls via Android's Camera2 API.

#### A. Operating System & Android Versions
- **Minimum Version**: **Android 9.0 (Pie) — API 28**.
  - App installs and runs properly.
  - API level guards prevent runtime crashes on newer keys (such as dynamic `CameraCharacteristics.Key` on API 29+).
- **Recommended Version**: **Android 12 (S) — API 31 or higher**.
  - Starting with API 31, the platform exposes `SENSOR_INFO_ROLLING_SHUTTER_SUSTAIN_DURATION` (the readout duration of a single sensor line).
  - On earlier releases (API 28–30), the line rate is deduced dynamically or hill-climbed.
- **Target SDK**: **Android 14 — API 34** (tested and verified up to Android 17 / API 37).

#### B. Required & Recommended Camera2 Capabilities
| Camera2 Key / Capability | Purpose in no-strobe | Criticality |
|---|---|---|
| `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR` | Allows disabling 3A auto-exposure and applying per-frame manual shutter parameters (`SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `SENSOR_FRAME_DURATION`). | **Mandatory**. Without this, the app falls back to `autoOnly` mode (no flicker sync possible). |
| `INFO_SUPPORTED_HARDWARE_LEVEL` | Hardware level of the camera HAL. | **`FULL` or `LEVEL_3` recommended**. `LIMITED` is accepted if `MANUAL_SENSOR` is reported. `LEGACY` is unsupported. |
| `SENSOR_INFO_EXPOSURE_TIME_RANGE` | Exposure time range. | **Mandatory**. Sensor must support very short exposures ($\le 100\,\mu\text{s}$, down to $20\,\mu\text{s}$) to measure and lock onto high PWM frequencies (> 1 kHz). |
| `SENSOR_INFO_SENSITIVITY_RANGE` | ISO sensitivity range. | **Mandatory**. Used to compensate scene brightness when exposure time is locked to $k/f$. |
| `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` | Available AE target frame rate ranges (e.g. `[30, 30]`, `[60, 60]`). | **Recommended**. Locks target video frame rate cleanly without pacing jitter. |
| `SENSOR_INFO_ROLLING_SHUTTER_SUSTAIN_DURATION` | Row readout skew (*rolling shutter sustain*). | **Highly recommended** (API 31+). Enables sub-second instant spatial convergence (< 500 ms). |
| Concurrent Output Streams | Simultaneous output: Preview (SurfaceView 16:9) + Analysis (`ImageReader` YUV_420_888 $\le 720\text{p}$) + Video Encoder (`MediaRecorder` or `MediaCodec`). | **Mandatory**. Requires at least 3 concurrent active streams. |

#### C. Processor Architectures (ABIs)
- **`arm64-v8a` (ARM 64-bit)**: Primary target architecture. NEON SIMD optimizations enabled for row-profile extraction and robust autocorrelation.
- **`armeabi-v7a` (ARM 32-bit)**: Supported. *(Note: on certain 64-bit ROMs running 32-bit userlands, framework crashes in `Image.getPlanes()` trigger a fallback to autoOnly mode).*
- **`x86_64`**: Supported for developer emulators and CI runners.

#### D. Video Encoding & Graphics Acceleration
- **Video Encoders**: Hardware H.264 / AVC or H.265 / HEVC. HEVC is preferred when supported by device hardware to reduce storage bandwidth by 40%.
- **Vulkan 1.1+**: Required for on-device post-processing deflickering filters (`OnDevicePost`).

---

### 2. Verified Devices & Test Environments

| Device / Environment | Type | SoC / Processor | OS & API | no-strobe Version | Verification Status |
|---|---|---|---|---|---|
| **Google Pixel 11 Pro Fold** / **Pixel 10 Pro XL** (`mustang`) | Physical (Foldable) | Google Tensor | Android 17 (API 37 Preview) | **v0.3.1** | **100% Verified**. Live PWM sync, strobe modes (17 Hz at 70% duty and < 1.5 Hz), anti-strobe photo burst (`BurstPick`), folded/unfolded layout switching (sw600dp/sw700dp), HEVC recording, permissions and signed release v2 APK. |
| **Sony Xperia 1 III** (`pdx215` / `XQ-BC52`) | Physical | Qualcomm Snapdragon 888 | Android 11 to 13 (API 30–33) | **v0.1 / v0.2** | **Verified**. PWM lock validated under real 100 Hz lamp (~10.3 ms, 99.5 Hz) and synthetic 500 Hz flicker. High Speed 240 fps video mode. Logical camera fallback when physical pinned stream is rejected. |
| **Android Emulator API 34** (Tablet & Foldable) | Virtual (AVD) | x86_64 | Android 14 (API 34) | **v0.3.1** | **Verified**. Wide screen adaptive layout (`res/layout-sw600dp`), EN/FR localization, network security config. |
| **Android Emulator API 30** (Phone) | Virtual (AVD) | x86_64 | Android 11 (API 30) | **v0.2 / v0.3.1** | **Verified**. API 30 backward compatibility (no native sustain key, learned row rate fallback). |
| **Android Emulator API 28** | Virtual (AVD) | x86_64 | Android 9.0 (API 28) | **v0.3.1** | **Verified**. NewApi backward compatibility check, crash-free launch on Android Pie. |
| **Apple iPhone / iOS** | Mock-up | Apple Silicon | iOS 16+ | Mock-up v0.1 | **Not operational**. Prototype SwiftUI/AVFoundation app in `platform/ios`, but lack of exposed line readout duration (*sustain*) on iOS prevents real-time lock. |

---

### 3. Feature Support Matrix by Hardware Profile

| Feature | Device with `MANUAL_SENSOR` + API 31+ | Device with `MANUAL_SENSOR` (API 28–30) | `LIMITED` device without `MANUAL_SENSOR` |
|---|:---:|:---:|:---:|
| **Live PWM Deflicker (100 Hz – 5 kHz)** | **Optimal** (< 500 ms) | **Supported** (with hill-climbing/calibration) | Unsupported (Auto-exposure only) |
| **Strobe Flash Lock (1 Hz – 20 Hz)** | **Optimal** | **Optimal** | Unsupported |
| **Anti-Strobe Photo Burst (`BurstPick`)** | **Supported** (AE Lock) | **Supported** (AE Lock) | Unsupported |
| **HEVC / AVC 1080p Video Recording** | **Supported** | **Supported** | **Supported** |
| **LAN Server Offload & Streaming** | **Supported** (Secure local cleartext) | **Supported** | **Supported** |
| **On-Device Vulkan Deflicker** | **Supported** (Vulkan GPU) | **Supported** (if Vulkan 1.1+) | **Supported** (if Vulkan 1.1+) |
