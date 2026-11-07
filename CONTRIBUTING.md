# Contribuer / Contributing

[🇫🇷 Français](#-guide-de-contribution-français) | [🇬🇧 English](#-contributing-guide-english)

---

## 🇫🇷 Guide de Contribution (Français)

Le projet filme des concerts : l'application mesure le scintillement des projecteurs et écrans LED et ajuste le temps d'exposition du capteur caméra sur un multiple entier de périodes de scintillement, afin que chaque image et chaque ligne du capteur reçoivent exactement la même quantité de lumière.

Le mode principal supporté est le **scintillement PWM de 100 Hz à 5 kHz** (projecteurs LED, murs d'écrans de scène). Le mode stroboscopique (1 Hz à 0,4 × fps) est expérimental et secondaire.

---

### Environnement de développement

```sh
cargo test --workspace                                   # Le cœur algorithmique Rust (agnostique)
cd platform/android
JAVA_HOME=/path/to/jdk gradle assembleDebug              # L'application Android (APK)
```

Pour compiler les bibliothèques natives Android avec `cargo-ndk` :

```sh
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
cargo ndk -t arm64-v8a -t armeabi-v7a -o platform/android/app/src/main/jniLibs \
    build -p ns-ffi --release --features android
```

Le dossier `jniLibs` est généré lors du build et n'est pas versionné dans git.
L'outil desktop de post-traitement nécessite `ffmpeg` et `ffprobe` dans votre PATH (`cargo build --release -p ns-post`).

---

### Gestion des branches

- `main` — La branche stable de référence. L'intégration continue (CI) compile l'APK et publie automatiquement les versions *Nightly*.
- `test-for-android` — `main` avec les traces de débogage et sondes matérielles. **Tout ce qui se trouve dans `crates/ns-core/examples/` appartient à cette branche et ne doit jamais aller sur `main`.**

Gardez les branches dans le même historique linéaire. Ne faites pas de rebase de l'une sur l'autre.

---

### Règles pour les commits

- **Signez chaque commit** avec votre clé GPG (`git commit -S`, `commit.gpgsign = true`). Les commits non signés sont rejetés lors de la revue.
- **Des commits propres et professionnels.** Aucun trailer automatisé ou lien externe non pertinent. Des messages clairs, soignés et utiles.
- **Format du message** :
  - *Sujet* : à l'impératif, décrivant une seule chose, moins de 70 caractères.
  - *Corps* : expliquez **pourquoi** le changement est fait, ce qui a été mesuré, et ce qui échouerait sans cela. Un diff qui indique seulement "fix bug" ne peut pas être relu efficacement.
- **Un commit par idée** : Ne mélangez pas refactorisation cosmétique et changement de comportement dans un même commit.
- **Jamais de force push (`git push -f`)** sur les branches partagées.

---

### Avant de soumettre une Pull Request

```sh
cargo test --workspace
cd platform/android && gradle :app:testDebugUnitTest
```

Les deux suites de tests doivent passer à 100 %.
Ajoutez des tests unitaires pour tout changement : le cœur mathématique est testé sur des profils synthétiques (`crates/ns-core/tests/`), et la logique Android dans `platform/android/app/src/test/`.
Conservez le moteur indépendant : `crates/ns-core` ne doit comporter aucune dépendance liée à Android ou iOS. Le code spécifique aux plateformes réside dans `platform/`.

---

### Test sur un smartphone réel

L'émulateur Android ne supporte pas l'exposition manuelle (`MANUAL_SENSOR`). Le comportement de la boucle fermée doit donc être vérifié sur du vrai matériel :

```sh
adb -s <serial> install -r -g platform/android/app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am force-stop dev.nostrobe
adb -s <serial> shell am start -n dev.nostrobe/.MainActivity --ed sim_hz 500
adb -s <serial> logcat -d -s nostrobe:V
```

Les extras se passent avec `--ei` (entier), `--ed` (décimal), `--ez` (booléen) ou `--es` (chaîne). `sim_hz` injecte un scintillement simulé (lu comme décimal : `--es` le rendrait silencieusement inactif).

Pour relever les profils de lignes d'un flicker réel à plusieurs expositions (expositions en microsecondes, séparées par des virgules ; sans `--es`, une grille par défaut est utilisée) :

```sh
adb -s <serial> shell am force-stop dev.nostrobe
adb -s <serial> shell am start -n dev.nostrobe/.MainActivity --ez dump_profiles true --es dump_exposures 8000,8500,9000,9500
adb -s <serial> logcat -d -s nostrobe:V    # attendre la ligne « dump: done »
adb -s <serial> pull /storage/emulated/0/Android/data/dev.nostrobe/cache/profiles.csv
```

---

### Sécurité et respect des lieux

- L'application réduit le scintillement **dans la vidéo enregistrée**, pas dans la salle de concert. Conservez l'avertissement d'épilepsie photosensible dans l'interface.
- `no-strobe-post serve` exécute `ffmpeg`. Ne l'exposez jamais directement sur Internet sans réseau sécurisé.
- Respectez les droits à l'image et les règles de captation des salles et des intervenants sur scène.

---

<br/>

---

### Clés de signature

- **Ne commitez jamais une clé de signature** (`*.keystore`, `*.jks`, mots de passe) : un dépôt public la donne à tout le monde, et Android accepterait un APK malveillant signé avec elle comme une mise à jour de l'application. `.gitignore` les exclut.
- Une clé de publication se configure hors du dépôt : variables d'environnement ou `~/.gradle/gradle.properties` (`NOSTROBE_KEYSTORE_PATH`, `NOSTROBE_STORE_PASSWORD`, `NOSTROBE_KEY_ALIAS`, `NOSTROBE_KEY_PASSWORD`), et, pour le CI, les *secrets* du dépôt (`NOSTROBE_KEYSTORE_B64` en base64, plus les trois mots de passe/alias). Sans clé configurée, le build est signé avec la clé de débogage de la machine.

### Appareils et données personnels

- **Pas de modèle, de numéro de série ni d'enregistrement d'un appareil personnel dans le dépôt** : ni dans les commentaires, ni dans les tests, ni dans la documentation. Écrivez « un téléphone », « un pliable ». Les tests qui dépendent d'un enregistrement (journal de luma, profils de lignes) restent chez leur propriétaire, dans `crates/ns-core/tests/private/` (ignoré par git, voir `.gitignore`) ; dans le dépôt, utilisez des scènes synthétiques (voir `blinking_light_scene` dans `crates/ns-core/tests/strobe_sweep.rs`).

### À faire prochainement

Idées et chantiers ouverts, du plus utile au moins urgent dans chaque groupe. Prenez-en un, ouvrez une issue pour le dire, et gardez les tests verts.

**Validation sur matériel** (rien de tout cela n'a pu être vérifié sans l'appareil)
- [ ] Une vraie lampe de 100 Hz devant un téléphone, avec le build courant : relever un balayage fin (`--ez dump_profiles true --es dump_exposures 8000,8500,…,32000`), le rejouer avec `replay_real` et confirmer que le verrou tombe sur un creux (la voie par lignes a changé depuis le dernier essai sur une vraie lampe).
- [ ] Un vrai strobe (ou l'outil `tools/android-strobe-flasher`) : valider la rafale photo (`BurstPick`) et le mode STROBE sur appareil, y compris la revérification toutes les 20 s.
- [ ] Un vrai appareil 32 bits (armeabi-v7a seul) et un appareil sans `MANUAL_SENSOR`.
- [ ] Un smartphone pliable, replié et déplié, avec le build courant.

**Contrôleur**
- [ ] Scintillement de 120 ou 300 Hz (multiple de la cadence d'images) devant une scène structurée : aucun verrou. Piste : comparer les profils de lignes de deux expositions (la structure statique s'annule dans le rapport) plutôt que l'amplitude du banding seul.
- [ ] Scène sombre et bruitée (bruit d'environ 2 % par ligne) : un scintillement faible n'est pas confirmé. Piste : moyenner les profils d'un essai avant de calculer le banding, et allonger la vérification quand la mesure est limitée par le bruit.
- [ ] Un verrou aveugle à k ≥ 2 annonce f/k ; `frequency_hz` préfère un PLL périmé ; le balayage recommence sans fin quand la grille a moins de 5 points (relevés par une relecture, non corrigés).
- [ ] Strobe sous 1,5 Hz : le solveur ne propose aucune exposition avec les limites d'un téléphone dont la cadence minimale est 15 i/s, donc pas de verrou ; au-delà de fps/2 la période est repliée. Un plan « fenêtre de la durée d'une image » pour les strobes lents reste à concevoir.
- [ ] Strobe large (rapport cyclique de 70 %) vers 17 Hz à 60 i/s : un creux sur deux reste peu profond, les événements viennent un cycle sur deux et la période se lit double. Piste : valider la période candidate par le repli de la luma à P et à P/2.
- [ ] Mode STROBE : le médian glissant par image (`local_residual`) coûte O(n·w·log w) ; une fenêtre triée incrémentale réduirait le coût par image.

**Android et interface**
- [ ] Vraie correction en direct dans la vidéo : le MediaRecorder encode le flux tel quel et le verrouillage n'agit qu'en boucle ouverte. Piste : un encodeur MediaCodec alimenté par les images corrigées.
- [ ] Chaînes de l'interface écrites en dur en français dans le code : les passer dans `strings.xml` (FR et EN).
- [ ] `usesCleartextTraffic="true"` : le restreindre au réseau local (network security config) pour le déport LAN.
- [ ] Un scénario `adb` automatisé (démarrage à froid, photo, vidéo, rotation) sur des émulateurs (API 30, 34, tablette), dans le CI.

**Signature et publication**
- [ ] Configurer une vraie clé de publication privée (secrets du dépôt, voir « Clés de signature »).
- [ ] Publier la v0.3 : le tag crée un brouillon de release, à relire avant de le publier.

**Dépôt et qualité**
- [ ] `crates/ns-core/examples/` : 14 fichiers sont sur `main`, contre la règle de ce guide. Les déplacer sur `test-for-android` et faire de `deflicker` (utilisé par `tools/postprocess.sh`) une sous-commande de `no-strobe-post`.
- [ ] Le code n'est pas formaté par rustfmt et clippy n'est pas dans le CI : les ajouter.
- [ ] iOS : le dossier n'est qu'une maquette qui ne traite pas le scintillement. L'implémenter (Swift, AVFoundation) ou ne plus la présenter comme une application.

## 🇬🇧 Contributing Guide (English)

The project films concerts: the app measures LED flicker and sets the sensor exposure to a whole number of flicker cycles (`exposure = k/f`), so frames and sensor rows integrate the exact same light: no horizontal banding, no brightness pumping.

The supported mode is **PWM flicker from 100 Hz to 5 kHz** (LED projectors, stage screens). The strobe mode (1 Hz to 0.4 × fps) is experimental and secondary.

---

### Getting set up

```sh
cargo test --workspace                                   # Platform-free core engine
cd platform/android
JAVA_HOME=/path/to/jdk gradle assembleDebug              # Android APK
```

Build phone native libraries using `cargo-ndk`:

```sh
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
cargo ndk -t arm64-v8a -t armeabi-v7a -o platform/android/app/src/main/jniLibs \
    build -p ns-ffi --release --features android
```

`jniLibs` is generated build output and is not tracked in git. The desktop post-processing tool requires `ffmpeg` and `ffprobe` in PATH (`cargo build --release -p ns-post`).

---

### Branches

- `main` — Production-ready code. CI builds the APK and publishes nightly releases.
- `test-for-android` — `main` plus hardware probes and debug traces. **Anything under `crates/ns-core/examples/` belongs strictly here, never on `main`.**

---

### Commits

- **Sign every commit** with your GPG key (`git commit -S`, `commit.gpgsign = true`).
- **Clean and professional commits.** No third-party automated trailers, no irrelevant external links. Clear, professional commit messages.
- **Message format**:
  - *Subject*: Imperative, single focus, under 70 characters.
  - *Body*: Explain **why** the change was made, what was measured, and what breaks without it.
- **One commit per concept.** Keep functional logic separate from styling or formatting.
- **Never force push** to shared branches.

---

### Before opening a Pull Request

```sh
cargo test --workspace
cd platform/android && gradle :app:testDebugUnitTest
```

Both test suites must pass 100%. Add unit tests for any new or modified behaviour.

---

### Real device testing

The Android emulator lacks `MANUAL_SENSOR` capabilities. Closed-loop flicker tracking must be tested on physical hardware:

```sh
adb -s <serial> install -r -g platform/android/app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am force-stop dev.nostrobe
adb -s <serial> shell am start -n dev.nostrobe/.MainActivity --ed sim_hz 500
adb -s <serial> logcat -d -s nostrobe:V
```

Extras are passed with `--ei` (integer), `--ed` (decimal), `--ez` (boolean) or `--es` (string). `sim_hz` injects a simulated flicker (read as a decimal: with `--es` it would be silently ignored).

To record the row profiles of a real flicker at several exposures (exposures in microseconds, comma-separated; without `--es` a default grid is used), the output is `profiles.csv` in the app's external cache:

```sh
adb -s <serial> shell am force-stop dev.nostrobe
adb -s <serial> shell am start -n dev.nostrobe/.MainActivity --ez dump_profiles true --es dump_exposures 8000,8500,9000,9500
adb -s <serial> logcat -d -s nostrobe:V    # wait for the line "dump: done"
adb -s <serial> pull /storage/emulated/0/Android/data/dev.nostrobe/cache/profiles.csv
```

### Signing keys

- **Never commit a signing key** (`*.keystore`, `*.jks`, passwords): a public repository gives it to everyone, and Android would accept a malicious APK signed with it as an update of the app. `.gitignore` excludes them.
- A release key is configured outside the repository: environment variables or `~/.gradle/gradle.properties` (`NOSTROBE_KEYSTORE_PATH`, `NOSTROBE_STORE_PASSWORD`, `NOSTROBE_KEY_ALIAS`, `NOSTROBE_KEY_PASSWORD`) and, for CI, the repository *secrets* (`NOSTROBE_KEYSTORE_B64` as base64, plus the passwords and alias). Without a configured key the build is signed with the machine's debug key.

### Personal devices and data

- **No model, serial number or recording of a personal device in the repository**: not in comments, tests or documentation. Write "a phone", "a foldable". Tests that depend on a recording (a luma log, row profiles) stay with their owner, in `crates/ns-core/tests/private/` (git-ignored, see `.gitignore`); in the repository use synthetic scenes (see `blinking_light_scene` in `crates/ns-core/tests/strobe_sweep.rs`).

### Near-future TODO

Open ideas and tasks, most useful first within each group. Pick one, open an issue to say so, and keep the tests green.

**Hardware validation** (none of this could be checked without the device)
- [ ] A real 100 Hz lamp in front of a phone, with the current build: record a fine sweep (`--ez dump_profiles true --es dump_exposures 8000,8500,…,32000`), replay it with `replay_real` and confirm the lock lands on a null (the row-rate path changed since the last test on a real lamp).
- [ ] A real strobe (or `tools/android-strobe-flasher`): validate the photo burst (`BurstPick`) and STROBE mode on a device, including the re-check every 20 s.
- [ ] A real 32-bit device (armeabi-v7a only) and a device without `MANUAL_SENSOR`.
- [ ] A foldable phone, folded and unfolded, with the current build.

**Controller**
- [ ] A 120 or 300 Hz flicker (a multiple of the frame rate) in front of a structured scene never locks. Idea: compare the row profiles of two exposures (static structure cancels in the ratio) instead of the banding amplitude alone.
- [ ] Dark, noisy scene (about 2 % row noise): a shallow flicker is not confirmed. Idea: average the profiles of a trial before computing the banding, and lengthen the check when the measurement is noise-limited.
- [ ] A blind lock at k ≥ 2 reports f/k; `frequency_hz` prefers a stale PLL; the scan restarts for ever when the grid has fewer than 5 points (found by a review, not fixed).
- [ ] Strobe under 1.5 Hz: the solver offers no exposure with the limits of a phone whose slowest rate is 15 fps, so no lock; above fps/2 the period aliases. A "one frame long window" plan for slow strobes is still to design.
- [ ] A wide strobe (70 % duty) near 17 Hz at 60 fps: every second dip stays shallow, the events come every other cycle and the period reads double. Idea: check the candidate period by folding the luma at P and at P/2.
- [ ] STROBE mode: the per-frame running median (`local_residual`) costs O(n·w·log w); an incremental sorted window would cut the cost per frame.

**Android and interface**
- [ ] Real live correction in the video: MediaRecorder encodes the stream as is and the lock only acts open loop. Idea: a MediaCodec encoder fed with corrected frames.
- [ ] Interface strings are hard-coded in French in the code: move them to `strings.xml` (FR and EN).
- [ ] `usesCleartextTraffic="true"`: restrict it to the local network (network security config) for the LAN offload.
- [ ] An automated `adb` scenario (cold start, photo, video, rotation) on emulators (API 30, 34, tablet) in CI.

**Signing and publishing**
- [ ] Set up a real private release key (repository secrets, see "Signing keys").
- [ ] Publish v0.3: the tag creates a draft release, to review before publishing it.

**Repository and quality**
- [ ] `crates/ns-core/examples/`: 14 files are on `main`, against the rule of this guide. Move them to `test-for-android` and turn `deflicker` (used by `tools/postprocess.sh`) into a `no-strobe-post` subcommand.
- [ ] The code is not rustfmt-formatted and clippy is not in CI: add both.
- [ ] iOS: the folder is only a mock-up that does not handle flicker. Implement it (Swift, AVFoundation) or stop presenting it as an app.
