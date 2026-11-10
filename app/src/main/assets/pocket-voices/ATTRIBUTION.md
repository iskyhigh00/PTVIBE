# Pocket story voice references

grave.wav and alegre.wav are synthetic speech generated for GlideLive during
the Pocket Spanish evaluation. They are not recordings of a real narrator.
They were generated with the Spanish Pocket TTS FP32 model (Kyutai, CC BY 4.0)
using synthetic Spanish voice-design references from the MIT-licensed
hangry-labs/faster-qwen3-tts-studio examples (deep_male and cheerful_female).

Sources:
https://github.com/kyutai-labs/pocket-tts
https://huggingface.co/kyutai/pocket-tts
https://github.com/hangry-labs/faster-qwen3-tts-studio/tree/main/examples/assets/references/spanish
https://github.com/hangry-labs/faster-qwen3-tts-studio/blob/main/LICENSE

The upstream model and Lola recording attribution are delivered with the model
package as MODEL_LICENSE.txt and VOICE_ATTRIBUTION.md. The runtime's MIT,
Apache-2.0 and MIT-0 notices are preserved under app/src/main/cpp/pocket.

The bundled speech is used only as a conditioning reference for new generation.
Its presence does not guarantee a particular accent, emotional performance,
pronunciation, or that every word of a new utterance will be generated correctly.
