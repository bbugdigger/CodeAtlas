# Bundled embedding model — `bge-small-en-v1.5-int8.onnx`

## What lives here

This directory is the classpath location `OnnxEmbeddingProvider` checks for a
bundled ONNX model before falling back to a runtime download.

If a file named exactly `bge-small-en-v1.5-int8.onnx` is placed next to this
card (and shipped inside the plugin JAR), it will be copied out to
`<systemPath>/CodeAtlas/models/` on first use and loaded from there on every
subsequent run. No file present → the provider downloads from Hugging Face on
first use.

## Source model

- Hugging Face model: `BAAI/bge-small-en-v1.5`
- Exact ONNX export: `onnx/model_quantized.onnx` (INT8 dynamic quantization)
- Download URL:
  `https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx`
- Embedding dim: **384**
- License: **MIT** (the model card on Hugging Face publishes the model under
  the MIT license at the time of writing — verify the upstream README before
  shipping)
- Approximate file size: **~33 MB**

The chosen INT8 build trades <2% MTEB quality loss for ~4x size reduction
versus the FP32 export. We pin to the quantized build so the plugin JAR stays
under the marketplace's recommended bundle size.

## How to fetch and bundle

The model file is **not** committed to git — large binaries should not live in
the source tree. Two acceptable distribution paths:

1. **CI-side bundling (recommended)** — a release build step downloads the file
   into `src/main/resources/model/` immediately before `./gradlew buildPlugin`,
   verifies its SHA-256 against the value below, then continues. Local
   developer builds skip this step and the provider downloads on first IDE run.
2. **Manual bundling** — for one-off marketplace uploads, run the curl command
   below before `./gradlew buildPlugin`, then verify the checksum.

```bash
curl -L -o src/main/resources/model/bge-small-en-v1.5-int8.onnx \
  https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx
sha256sum src/main/resources/model/bge-small-en-v1.5-int8.onnx
```

## Expected SHA-256

`F36668DDF22403A332F978057D527CF285B01468BC3431B04094A7BAFA6ABA59`

The first published release should pin a known-good checksum here and a
build-time verification step in `build.gradle.kts` should reject the JAR if the
bundled file's hash drifts from the pinned value.

## Why bundle vs. download

Bundling keeps the "fully offline" promise on the marketplace listing intact.
The download fallback exists for two cases:

1. Local dev builds where committers don't want a 33 MB binary in their working
   tree.
2. Slim CI builds that intentionally ship without the model (e.g. for unit
   testing the download path).

End users on a release marketplace install should never hit the download path.
