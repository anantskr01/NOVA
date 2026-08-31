# NOVA + Jarvis OS integration

This branch is the safe foundation for merging the strongest parts of both projects.

## Ownership

### NOVA keeps
- Camera2 + MediaPipe hand perception
- Continuous finger-motion engine
- Existing AccessibilityService implementation
- NovaAssistant orchestration
- NovaAgentPlanner
- NovaActionEngine
- NovaMemory
- NovaSkillRegistry
- NovaVoiceService
- NOVA HUD/wake experience

### Jarvis OS concepts being adopted
- Central gateway/device-node model
- WebSocket daemon transport and reconnect protocol
- Explicit operation envelopes and result acknowledgements
- Device pairing and server-issued reconnect credentials
- Separation of AI core from device capabilities
- Cross-device event routing

## Android flow

```text
Voice / Text / Gesture / Remote Agent
                 |
                 v
          NovaAssistant
                 |
        +--------+--------+
        |                 |
   local action       device gateway
        |                 |
        v                 v
 NovaActionEngine   central NOVA server
        |                 |
        v                 v
 Accessibility       other devices
        ^
        |
 MediaPipe gestures
```

## Important implementation rule

Do not replace NOVA's existing gesture pipeline with Jarvis's Android daemon. NOVA already owns a working Camera2/MediaPipe/Accessibility control path. The Jarvis daemon architecture is being used as the transport and distributed-device pattern.

The gateway currently speaks the Jarvis-compatible `/api/daemon/ws` pairing/reconnect envelope and deliberately exposes an operation-handler interface. Unsupported remote operations return an explicit failure instead of pretending they succeeded.

## Next integration stages

1. Wire `NovaDeviceGateway` into the Android lifecycle without breaking existing services.
2. Persist device credentials through the existing Android Keystore-backed `NovaSecureStore`.
3. Add the central server/gateway layer to the NOVA repository.
4. Route agent plans through a unified operation schema.
5. Add the desktop node.
6. Add shared memory and device registry.
7. Add automated build/test checks before enabling autonomous device actions.
