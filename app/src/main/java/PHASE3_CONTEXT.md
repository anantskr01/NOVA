# NOVA Phase 3 — Context & Intelligence

Phase 3 adds bounded context assembly and deterministic intent classification without bypassing the existing agent safety boundaries.

## Components
- `NovaContextEngine`: bounded recent conversation + relevant local memory.
- `NovaIntentResolver`: local classification into conversation, device action, multi-step, memory, and screen query.
- `NovaBrain`: contextual conversation input is assembled before direct AI chat.

## Boundaries
- No unbounded history is sent to the model.
- Memory remains local and user-controlled.
- Device actions continue through `NovaActionEngine` / `NovaToolExecutor`.
- Autonomous execution remains bounded by `NovaAgentLoop` and `NovaAgentPlanner`.
- Consequential communication remains confirmation-gated.
