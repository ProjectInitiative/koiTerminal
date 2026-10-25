# koiTerminal — agent context

Soft fork of GrapheneOS's Linux Terminal (AVF VmTerminalApp) that installs as a
normal app on non-rooted devices and boots user-provided Linux VM images via
Android Virtualization Framework. Fork origin: outlawsanzhang/koiTerminal
(fork of GrapheneOS), our remote: ProjectInitiative/koiTerminal.

## Build (Nix)

```bash
nix develop .# -c bash -c 'build-android'   # assembleDebug; MUST pass before any hand-off
nix develop .# -c bash -c 'build-rust'      # rust components (rarely needed)
nix develop .# -c bash -c 'install-app'     # adb install to connected device
```

- `AndroidStudioProject/` is a **symlink view** into `android/TerminalApp/` —
  edit real files under `android/TerminalApp/`; `git add` the real paths.
- `AndroidManifest.xml` and `res/` are shared with the Soong/OS build
  (`android/TerminalApp/Android.bp`) — do not add gradle-only resources there.
- App id for gradle builds: `com.android.virtualization.koiterminal.githubaction`.

## Key code paths

- `android/TerminalApp/java/com/android/virtualization/koiterminal/` — app code
  - `new2/core/` — VmController, Installer, InstalledImage, ConfigJson, VmState
  - `new2/ui/` — Compose UI (MainScreen, SettingsScreen, TerminalScreen, …)
- `docs/MULTI_VM_PLAN.md` — the multi-VM architecture + phase contracts

## Conventions

- One commit per work phase, message prefixed `phase-X:`.
- Never push; the parent session reviews and pushes.
- Do not edit `views/` (keel) or files outside your assigned phase scope.
