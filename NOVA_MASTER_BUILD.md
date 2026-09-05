# NOVA Master Build

## Architecture baseline

NOVA is an agent platform, not a fixed command interpreter and not a foundation model. `NovaBrain` owns goal orchestration while AI providers are selected behind `NovaAiClient` and `NovaAiProviderManager`.

Current provider adapters:

- `ollama` — native Ollama `/api/chat`
- `openai-compatible` — OpenAI-compatible `/v1/chat/completions`

New providers must implement `NovaAiProvider` rather than adding provider-specific branches to `NovaBrain`.

## Natural-language rule

Open-ended questions must reach the universal Brain pipeline. Wording-specific command dictionaries must not be used to distinguish contractions, capitalization, punctuation, or equivalent phrasing.

Deterministic capabilities such as arithmetic may exist as tools, but they must be exposed through the agent tool boundary rather than by intercepting one English sentence pattern.

## Agent safety

AI output is parsed and validated against `NovaActionSchema` before execution. UI mutations and informational operations remain distinct. Important UI actions are followed by observation/verification and bounded recovery.

## CI gate

The Android build workflow runs for the master-build branch and main. Device-level verification still requires an attached Android device because GitHub Actions cannot reproduce the user's local accessibility, camera, microphone, LAN Ollama, or MediaPipe environment.
