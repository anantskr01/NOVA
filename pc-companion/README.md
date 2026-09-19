# NOVA PC Companion

The PC companion is intentionally isolated from the Android `:app` module.

## Contract

- Bind only to an explicitly configured local/network interface.
- Authenticate every request with a configured secret/token.
- Expose only allowlisted PC operations.
- Never expose arbitrary shell execution through the Android agent contract.
- Keep workspace access scoped to `NOVA_PC_WORKSPACE`.

The Android side should integrate through a small HTTP client/agent interface rather than depending on implementation classes from this module.
