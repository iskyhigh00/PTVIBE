# Embedded Pocket native code

PocketTTS.cpp and the model integration are based on the MIT-licensed
The-unknown-Shadowman/PocketTTS-Android-Engine snapshot
40c904924724a738bf2696f9956b84eed4794cf6 (vendor/PocketTTS.cpp), originating
from VolgaGerm/PocketTTS.cpp. LICENSE.PocketTTS preserves its license.

GlideLive changes: JNI bridge with standard UTF-8 input, no speech effects,
serialized ownership and callback cancellation; generation thread exceptions
are joined/reported instead of aborting Android or saving partial audio as
success. Only ARM64 is enabled. Decoder and background producer exceptions
propagate through the streaming C API. Voice/text/model input semantics remain
upstream's; successful audio generation does not guarantee every word is spoken.

SentencePiece v0.2.1 (31646a467d2051eb904e0b45de3a73e91fe1c1e3), Google: Apache-2.0, license in sentencepiece/LICENSE.
Essential src, third_party and build configuration are vendored; data/training
corpora, tests' external fixtures and the Git checkout are excluded. GCC14
cstdint include fix in sentencepiece_processor.h comes from the upstream host
build recipe.

dr_libs snapshot b6a91899fd5ee9508aab6a41b9f51ff8b4987317, David Reid:
choice of public domain or MIT-0, see dr_libs/LICENSE and individual headers.

ONNX Runtime v1.20.0, Microsoft: MIT, see ort/LICENSE. Only its public headers
are vendored. Native code links against the same Android AAR 1.20.0 shared
library already shipped by GlideLive; no second ORT library is packaged.

Model weights and reference recordings are downloaded separately in the app.
Pocket weights are CC BY 4.0 and require their own attribution, independently
of these source-code licenses.
