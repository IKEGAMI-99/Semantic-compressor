# Semantic Compressor

Android proof-of-concept for **AI-semantic image compression**.

A photo is analyzed locally with Gemma 4 through LiteRT-LM, then packed into a tiny `.simg` container containing:

- a very low-resolution WebP preview,
- compact semantic metadata,
- minimal decoder instructions for a multimodal AI.

The target is **1–3 KB per image**. This is lossy semantic compression: the original pixels are not recoverable. A decoder AI reconstructs a semantically similar image from the preserved structure, colors and object descriptions.

## Model

The app does not bundle model weights. Select a Gemma 4 LiteRT-LM model (`.litertlm`) from device storage. Gemma 4 E2B is the recommended starting point; E4B is supported on devices with enough memory.

## MVP flow

1. Select a `.litertlm` Gemma 4 model.
2. Load the model on-device.
3. Select a photo.
4. Choose a 1 KB / 2 KB / 3 KB target.
5. Gemma 4 analyzes the photo locally.
6. The app creates a `.simg` file.
7. Share the `.simg` file to ChatGPT or another multimodal AI.

## `.simg` v1

`.simg` is currently a ZIP-compatible container with three entries:

- `p.webp` — tiny visual reference
- `m.json` — minified semantic metadata
- `d.txt` — decoder instructions

Keeping the format ZIP-compatible makes the prototype inspectable while still allowing aggressive compression.

## Status

Initial Android MVP. The exact 1–3 KB target is best-effort and depends on image complexity and model output.
