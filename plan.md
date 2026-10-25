# Current plan: multi-VM support

Full architecture + contracts: **docs/MULTI_VM_PLAN.md** (read it first).

## Phase status

- [ ] Phase A — registry + storage (`VmRegistry`, `InstalledImage` param, legacy migration)
- [ ] Phase B — VmManager (`VmInstance`, per-vmId state flows, VmService scoping)
- [ ] Phase C — import & installer (SAF import dialog, `ImageArchive.fromPath`, install queue)
- [ ] Phase D — UI (VM list idle screen, per-VM tabs, per-VM config editor)
- [ ] Phase E — settings split (per-VM JSON prefs, global trim)

Rules: implement only your assigned phase; the build gate
(`nix develop .# -c bash -c "build-android"`) must pass before committing;
commit `phase-X: <summary>`; never push. Update this checklist by ticking
your phase when its commit lands.
