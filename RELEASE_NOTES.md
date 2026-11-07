# Release v0.3: Strobe Mode, Burst Photos & Recording Reliability

## 🇫🇷 Nouveautés de la version 0.3

### 📱 Fonctionnalités Mobile
- **Sélecteur de mode VIDEO | PHOTO | STROBE** : le mode STROBE enregistre avec la recherche lente de strobe. Passer vers ou depuis STROBE redémarre la caméra ; le mode choisi est mémorisé.
- **Photos en rafale** : une photo est une rafale de 6 trames à exposition et ISO fixes. La trame qu'aucune impulsion de strobe n'a frappée est conservée (comparaison sur la luminance brute).
- **Recherche de strobe lent** : de 1 Hz à 0,4 × fps (12 Hz à 30 images/s). La période est déduite des instants des impulsions, avec une exposition de recherche qui couvre presque toute l'image *telle que la caméra la livre*. Un verrouillage demande un train régulier (une impulsion dans presque chaque cycle) : une lumière qui clignote par moments ne verrouille plus. Le verrouillage est revérifié toutes les 20 s et abandonné si le strobe s'est arrêté. Un strobe dont la période n'admet aucune exposition réalisable (moins de 1,5 Hz sur certains téléphones) faisait planter l'application par récursion infinie : corrigé.
- **Moins de faux verrouillages (PWM)** : une modulation plus forte que ce qu'un scintillement de 100 Hz ou plus peut produire (scène sombre, bruit du capteur) n'est plus retenue. Une fréquence lue sur les lignes est vérifiée dans le temps avant d'être utilisée. Les lumières de 2 kHz et plus trouvées à l'aveugle se verrouillent de nouveau, et un verrouillage obtenu sur un capteur bruité ne se déplace plus à chaque contrôle.
- **ISO borné pendant la recherche** : la recherche garde l'ISO sous 1 600 ; au-delà de 3 200, une pose plus longue est préférée.
- **Enregistrement HEVC** : H.265 quand l'appareil dispose d'un encodeur (fichiers plus légers à bruit égal), corrigeable sur le téléphone ou avec `no-strobe-post`.
- **Écran allumé** tant que l'application est au premier plan : une prise longue n'est plus coupée par la mise en veille.
- **Enregistrement plus robuste** : une boîte de dialogue système (autorisation du micro, volet de notifications) ne termine plus l'enregistrement ; une caméra perdue est rouverte une fois ; un clip commencé est sauvegardé proprement.
- **Galerie** : les photos s'ouvrent dans une visionneuse, orientées selon leurs métadonnées EXIF.
- **Stockage** : sur Android 10 et plus, la copie dans DCIM passe par MediaStore et signale son résultat.

### ⚠️ Limites connues
- La voie par lignes (scintillement PWM) n'a pas été revalidée sur un vrai scintillement LED depuis ces modifications : les essais utilisent un scintillement simulé et des profils enregistrés sur téléphone.
- Un scintillement de 120 ou 300 Hz (multiple de la cadence d'images) devant une scène très structurée (texte, clavier) ne verrouille pas : à chaque image le scintillement ressemble à la structure de la scène.
- Dans une scène sombre et bruitée (bruit par ligne d'environ 2 % de la luminance), un scintillement de faible profondeur n'est pas confirmé : pas de verrouillage, l'exposition reste en recherche.
- Mode STROBE : la période n'est pas distinguable d'un repliement au-dessus de fps/2, et pendant la revérification (2 à 4 s toutes les 20 s) l'état repasse à « search ».
- Les rafales photo n'ont jamais été observées sous un vrai strobe.
- Au-dessus de fps/2 (15 Hz à 30 images/s), la luminance des trames est repliée : un strobe plus rapide peut être confondu avec un strobe plus lent. Il faut 60 images/s pour suivre un strobe au-dessus de 12 Hz.
- L'application iOS est une maquette et ne traite pas le scintillement LED.
- Aucun appareil 32 bits réel n'a été testé. Les essais ont été faits sur des téléphones Android récents ; un autre téléphone a verrouillé une lampe de 100 Hz avant les derniers changements du contrôleur et n'a pas été retesté depuis.

---

## 🇬🇧 Release Notes (English)

### 📱 Mobile Features & Improvements
- **VIDEO | PHOTO | STROBE mode selector**: STROBE records with the slow strobe search. Switching to or from STROBE restarts the camera; the chosen mode is remembered.
- **Burst photos**: a photo is a burst of 6 frames with fixed exposure and ISO. The frame no strobe pulse hit is kept (comparison on raw mean luma).
- **Slow strobe search**: from 1 Hz to 0.4 × fps (12 Hz at 30 fps). The period is taken from the pulse times, with a search exposure that covers most of a frame *as the camera delivers it*. A lock needs a regular train (a pulse in nearly every cycle): a light that blinks now and then no longer locks. The lock is checked again every 20 s and dropped when the strobe has stopped. A strobe whose period has no workable exposure (under 1.5 Hz on some phones) crashed the app through endless recursion: fixed.
- **Fewer false locks (PWM)**: a modulation stronger than a 100 Hz or faster flicker can produce (dark scene, sensor noise) is no longer accepted. A frequency read from the rows is checked over time before it is used. Lights of 2 kHz and above found blind lock again, and a lock obtained on a noisy sensor no longer moves at every check.
- **ISO bounded during the search**: the search keeps ISO below 1,600; above 3,200, a longer exposure is preferred.
- **HEVC recording**: H.265 when the device has an encoder (smaller files at the same noise level), corrected on the phone or with `no-strobe-post`.
- **Screen kept on** while the app is in front: a long take is no longer cut by the screen timeout.
- **More robust recording**: a system dialog (microphone permission, notification shade) no longer ends a recording; a lost camera is reopened once; a clip that has started is saved cleanly.
- **Gallery**: photos open in a viewer, oriented from their EXIF metadata.
- **Storage**: on Android 10 and later, the copy to DCIM goes through MediaStore and reports its result.

### ⚠️ Known Limits
- The row-rate path (PWM flicker) has not been revalidated on a real LED flicker since these changes: the tests use a simulated flicker and row profiles recorded on a phone.
- A 120 or 300 Hz flicker (a multiple of the frame rate) in front of a very structured scene (text, a keyboard) does not lock: in every frame the flicker looks like the structure of the scene.
- In a dark, noisy scene (row noise around 2 % of the luminance) a shallow flicker is not confirmed: no lock, the exposure stays in search.
- STROBE mode: above fps/2 the period cannot be told from an alias, and during the re-check (2 to 4 s every 20 s) the state goes back to "search".
- Burst photos have never been observed under a real strobe.
- Above fps/2 (15 Hz at 30 fps) the frame luma aliases: a faster strobe can be mistaken for a slower one. Following a strobe above 12 Hz needs 60 frames per second.
- The iOS app is a mock-up and does not handle LED flicker.
- No real 32-bit device has been tested. The tests were run on recent Android phones; another phone locked a 100 Hz lamp before the latest controller changes and has not been retested since.

---

### 📦 Artifacts & Downloads
- `app-debug.apk`: debug-signed Android package for sideloading on ARM64 / ARMv7 devices.
- `no-strobe-post`: Standalone desktop post-processing & LAN server binary (x86_64 Linux).
- `AntiStrobe-Simulator.app.zip`: Mockup iOS simulator build.
