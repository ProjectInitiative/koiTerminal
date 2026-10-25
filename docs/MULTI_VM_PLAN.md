# Multi-VM Support — Plan & Contracts

Goal: koiTerminal becomes a VM **manager** — install/keep multiple VM images,
run one (or later several) at a time, with per-VM settings (ports, memory,
network, config). The app already survives the VM lifecycle (idle screen);
this plan builds the rest. Work is split into agent-sized phases with fixed
contracts so they can proceed mostly in parallel.

## Platform notes (AVF)

- `VirtualizationService` supports **multiple concurrent VMs** — each
  `VirtualMachine` is its own crosvm process. The practical limit is memory
  (`memory_mib` per VM config) and the app's own bookkeeping. Design for
  N installed VMs, 1 running at a time initially (multi-concurrent later —
  the state model must not assume exclusivity).

## Current single-VM coupling (inventory)

| Component | Problem |
|---|---|
| `InstalledImage` | paths derived from one dir (`files/linux/`); `getDefault(context)` |
| `VmController` | singleton `object`; one `_vmState`, one `GuestAgentController`, one `vmConfigJson` |
| `Installer` | singleton; one `installState`; `ImageArchive.getDefault()` = one `/sdcard/linux/images.tar.gz` |
| `TerminalSessionRepository` | sessions not bound to a VM |
| Settings prefs | memory/network/socks5/ports are global |
| `PortNotifier`/`PortsStateManager` | no VM scoping |

## Target architecture

```
files/
  vm_registry.json                  # list of installed VMs (see VmEntry)
  vms/<vmId>/                       # per-VM install slot (old files/linux content)
  vms/<vmId>/vm_config.json         # per-VM editable config
  vms/<vmId>.prefs                  # optional: per-VM settings overlay
```

### Contract: VmEntry + registry (Phase 1 defines, everyone consumes)

```kotlin
data class VmEntry(
    val id: String,            // stable slug/uuid, used for paths & prefs keys
    val name: String,          // user-visible (launcher list); default = target from build_id
    val createdAt: Long,
    val archiveSha256: String?,// provenance of the imported images.tar.gz
)
object VmRegistry {
    fun list(context: Context): List<VmEntry>             // reads vm_registry.json
    fun add(context: Context, entry: VmEntry)             // + creates vms/<id> dir
    fun remove(context: Context, id: String)              // deletes slot + entry (with backup flow)
    fun rename(context: Context, id: String, name: String)
    fun installDir(context: Context, id: String): Path    // files/vms/<id>
    fun migrateLegacyInstall(context: Context)            // files/linux -> first entry (id "default")
}
```

- `InstalledImage` gains a constructor param for the base dir (or a static
  `forSlot(context, vmId)`); `getDefault()` becomes `getForSlot()`/deprecated.
- `ConfigJson.replaceKeywords` already substitutes `$PAYLOAD_DIR`/
  `$APP_DATA_DIR` at read time — no change needed there.
- Migration: on app start, if `files/linux/vm_config.json` exists and the
  registry is empty, move it into `vms/<migrated-id>/` and register as
  "koiTerminal" (keep the old dir until first successful boot of the
  migrated VM, then delete).

### Contract: VmManager (Phase 2)

```kotlin
class VmInstance(context, val vmId: String)   // wraps current VmController guts
object VmManager {
    val states: StateFlow<Map<String, VmState>>        // vmId -> state
    fun state(vmId: String): StateFlow<VmState>
    fun start(vmId: String); fun stop(vmId: String); fun reset(vmId: String)
    // policy: refuse start if another VM is Running (Phase 1) unless
    // Settings allows concurrent (Phase 3+, default off)
}
```

- `VmController` object becomes a thin facade delegating to
  `VmManager.activeVmId` so call sites migrate incrementally.
- `VmService`: one foreground service, notification shows active VM name(s).
  `startForegroundService` already used per start — keep idempotent.
- `GuestAgentController` is already created per `start()` — attach to
  instance, keyed by vmId. Port forwarding calls gain a vmId.

### Contract: import & installer (Phase 3)

- New `ImportDialog`: SAF `ACTION_OPEN_DOCUMENT` (mime `application/gzip`,
  `application/x-tar`, fallback `*/*`) + optional name field → copies the
  stream to `files/imports/<uuid>.tar.gz` (no permission needed, app-private),
  creates `VmEntry`, enqueues install.
- `ImageArchive`: add `fromPath(Path)` (PathSource already exists internally);
  `getDefault()` stays for the legacy flow only.
- `Installer.installState` → `StateFlow<Map<String, InstallState>>` keyed by
  vmId; UI shows one progress card per active install. Queue: serialize
  installs (extract is I/O bound), allow queuing N.
- Keep "place images.tar.gz in /sdcard/linux" as a **legacy import** path:
  offer it in the import dialog for users following the README.

### Contract: UI (Phase 4)

- Idle screen (current Ready state) becomes the **VM list**: one card per
  `VmEntry` — name, state chip (Stopped/Booting/Running/Error), Start/Stop
  buttons, overflow: Edit config / Ports / Rename / Uninstall.
- "+ Add VM" opens the import dialog (Phase 3).
- Terminal tabs: `TerminalSession` gains `vmId`; tab bar groups or prefixes
  by VM name; `TerminalScreen` binds streams of the tab's VM.
- `VmConfigEditorPage` gains a `vmId: String` param; path from
  `VmRegistry.installDir(context, vmId)/vm_config.json`.
- Auto-boot: `auto_boot` stays global; add `auto_boot_vm_id` (default:
  first VM) — respected by the Ready-state auto-start collector.

### Contract: settings split (Phase 5)

- Global (unchanged): display resolution, keep-awake, share-time, auto-boot.
- Per-VM (move): memory_mib, network connection, socks5, ports, graphics
  accel. Store as a per-VM JSON (`vms/<id>.prefs.json`) via a small
  `VmSettings` repo; `SettingsViewModel` exposes per-VM flows used by the
  VM card's detail screen. Global Settings screen keeps only global items.

## Phase order & agent split

| Agent | Scope | Depends on |
|---|---|---|
| A: registry+storage | VmRegistry, InstalledImage param, migration, VmEntry | — |
| B: VmManager | VmInstance/VmManager, VmService, guest agent/port vmId | A (contract above is enough to start) |
| C: import/installer | ImportDialog, ImageArchive.fromPath, Installer map | A |
| D: UI | VM list idle screen, per-VM tabs/editor/ports | A+B (compile against contracts) |
| E: settings split | VmSettings repo + settings screens | A |

Sequencing: A first (sets the real contracts in code). B and C can go in
parallel after A. D after B. E any time after A. Every agent:
`nix develop .# -c bash -c 'build-android'` must pass before hand-off;
keep diffs scoped to the phase's files; do not rename existing user-facing
strings except where the phase specifies.

## Testing checklist (each phase)

- Cold start with legacy install → migrated, boots, serial keyboard works.
- Import second image → both listed; start/stop each; ports per-VM survive
  stop/start; config edits per-VM survive reinstall of the *other* VM.
- Reboot-in-guest still auto-restarts; auto-boot setting still honored.
- Uninstall VM → slot removed, registry updated, other VM untouched.
