# Semantic Compressor

Android proof-of-concept for **1–3 KB-class AI-semantic image compression**.

Instead of trying to preserve every original pixel, the app keeps the minimum information a multimodal AI needs to reconstruct the scene:

- a tiny low-resolution WebP visual reference,
- a compact Gemma 4 description of the scene,
- important object positions,
- dominant colors and lighting,
- a small decoder instruction file.

This is deliberately **lossy semantic compression**. The reconstructed image can preserve scene meaning and composition, but it is not a bit-exact recovery of the original photograph.

## Model

The app runs Gemma 4 locally with **LiteRT-LM**. Model weights are not bundled in the APK.

Recommended starting model:

- Gemma 4 E2B LiteRT-LM (`.litertlm`)

E4B can also be tried on devices with enough memory. The app imports the selected `.litertlm` file into its private storage once, tries GPU inference first, and falls back to CPU if GPU initialization fails.

## App flow

1. Select/import a Gemma 4 `.litertlm` model once.
2. Select a photo in the app, or use **Gallery → Share → Semantic Compressor**.
3. Choose a target: **1 KB / 2 KB / 3 KB**.
4. Gemma 4 analyzes the photo completely on-device.
5. The app budgets semantic metadata according to the target size.
6. It searches multiple tiny preview resolutions and WebP qualities.
7. A `.simg` file is produced and can be shared to ChatGPT or another multimodal AI.

## `.simg` v1

`.simg` is ZIP-compatible so the prototype remains easy for AI/file tools to inspect. It contains:

- `p.webp` — tiny visual reference, typically 12–32 px on the longest side
- `m.json` — minified semantic metadata
- `d.txt` — decoder instructions

Example semantic payload:

```json
{"s":"red car on a city street at dusk","o":[["car",28,54,43,24],["person",76,31,11,49]],"c":["#a51f28","#3b3b3b","#d78a64"],"l":"warm dusk light"}
```

The 1 KB target is intentionally aggressive. If the archive cannot fit, the app returns the smallest result it could produce and reports the actual byte size.

## Logs

The app keeps a rotating local text log (maximum ~2 MB) containing:

- app/version startup,
- model import and initialization,
- GPU → CPU fallback,
- Gemma 4 analysis completion,
- requested and achieved compression size,
- update check/download/install events,
- errors.

Use **Export log** in the app to save the log as a normal text file.

## App updates

The app can check this repository's **GitHub Releases** for a newer version, download the first APK asset, and open Android's package installer.

There is no custom release-signing setup in this repository. Android itself still requires every APK to carry a signature, and an in-place update requires the **same signing certificate** as the currently installed app. For personal sideloading, the simplest workflow is:

1. build/install using the normal Android debug signing,
2. keep the same local `~/.android/debug.keystore`,
3. increment `versionCode` / `versionName` for future builds,
4. build the update APK with that same keystore,
5. attach the APK to a GitHub Release tagged like `v0.2.0`.

GitHub Actions debug artifacts are for testing/build verification. Fresh CI runners may not share the same debug key, so do not rely on unrelated CI debug APKs for in-place updates.

## Build

Current project baseline:

- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- compile/target SDK 36
- Java 17
- Compose BOM 2025.12.00
- LiteRT-LM Android 0.16.0

The project is continuously compiled by GitHub Actions. The Android 36 build, including LiteRT-LM integration and the Android share-sheet entry point, passes `:app:assembleDebug`.

## Privacy

The original photo is analyzed locally by Gemma 4. The `.simg` output contains only the tiny preview and semantic description selected for preservation. Sharing the `.simg` does **not** share the original photo file.
