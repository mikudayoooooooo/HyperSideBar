# Repository Guidelines

AI-assistant-oriented guide to `hyperSidebar`. Read this before editing anything.

## Project Overview

LSPosed/Xposed module that replaces the HyperOS/MIUI native sidebar with a **dual-ring semicircle fan joystick menu**: edge-hold / bottom-corner swipe gesture → fan overlay → app launched in freeform (小窗). Kotlin + Jetpack Compose on the **miuix KMP** UI kit (`top.yukonga.miuix.kmp:*:0.9.4`). Hooks three Xiaomi system hosts — it does not run as a normal app except for its own settings process.

- Requires root + LSPosed (API 102). Scopes: `com.miui.securitycenter`, `com.android.systemui`, `com.miui.home` (`app/src/main/resources/META-INF/xposed/scope.list`). Supports HyperOS 2/3/4 (README is the user-facing promise and wins on conflict); device-verified on marble (HyperOS 3.300 / OS4 8602 builds).
- **Identity split (easy to break):** `namespace = com.lsp.hypersidebar` (source tree, class paths, resources) vs `applicationId = io.github.mikudayoooooooo.hypersidebar` (APK/process identity, LSPosed-repo requirement). When addressing components cross-process: package part → applicationId, class part → namespace. Never "fix" the mismatch.
- OS4's launcher is pure Rust/C++ (no dex, no ART) — reached via the native Xposed entry `libhypersidebar_native.so`; native gets no Java-side data, only properties/files (`persist.hsb.*`, `Android/media/<applicationId>/hsb.conf`).
- Product invariant (README): the module shares the system back-gesture stream without conflict — it only ever calls out after the system's own back-gesture chain has claimed the touch. Do not implement self-judging call-outs.

## Architecture & Data Flow

Single entry `XposedInit` (XposedModule) routes by **process name only** (`hostFor(procName)` — API 102 hot-reload params carry no package name, so package-based routing is unusable; `XposedInit.kt:99-104`):

| Host | Hooks | Output path |
|---|---|---|
| `com.miui.home` (launcher) | `EdgeGestureHook` (portrait edge/dwell + corner swipe via `GestureStubView`/`NavStubView`); OS4: native inline hooks on `handle_back_gesture` | `FanMenuController` → `BroadcastLaunchStrategy` (ordered broadcast) → freeform launch |
| `com.miui.securitycenter:ui` | `TurboLayout` (landscape B-route trigger, strip state machine, layer hiding; arms `RelayGestureSink`), `FreeformRelayHook`, `SidebarOverlayProbe` | `FanMenuController` + `DirectLaunchStrategy` (`FreeformLauncher`, `ShortcutLauncher`, `AllAppsActivity` panel) |
| `com.android.systemui` | `SystemUiHook` (QS tile data-layer click; arms `SystemUiInputSpy` global input monitor, `FreeformBlankTapHook`, `InsetProbe`), `BackGestureSignalProbe` | OS4 stream: spy → `SPY_STREAM_ACTION` broadcast → `RelayGestureSink` in `:ui` |
| module process | `MainActivity` (settings), `ShortcutRelayReceiver` (legacy broadcast fallback), `ConfigRestoreReceiver`, `UnfreezeRelayService`, `ConfigPullService` | root relay for non-exported targets: Binder `ShortcutRelayBridge` + short `specialUse` FGS |

Anything outside this map (e.g. `com.android.systemui:screenshot`) is skipped on purpose — duplicate ordered-broadcast answers corrupt relay results.

Data flow: hook detects gesture → `FanMenuController` assembles `FanConfig` (custom apps + `DataLoader` ranked list) → pooled `ComposeFanHost` WindowManager Compose overlay (pure geometry in `FanGeometry`; it is a plain class with a hand-rolled `LifecycleOwner`, not a ViewModel) → dwell-selection → launch strategy → `FreeformLauncher`/`ShortcutLauncher` (launcher side = ordered broadcast; `:ui` = direct). The OS4 strip route may only evoke after the system back-gesture chain claimed the stream (native publishes `seq=0`; `TurboLayout` waits ≤1500 ms, fails closed). All cross-process glue is broadcast/Messenger IPC; keys/actions/extras live **only** in `prefs/PrefContract.kt`.

Hard-won architectural constraints — do not "fix" these:

- **`RemotePreferences` in hook processes is a dead snapshot** (no AIDL push; listeners are in-process/weak). Config reachability uses own channels: `ConfigSync` (full `getAll()` push, `relayToken` stripped at `sanitizeSyncMap`) + `SyncedPrefs` read-through + `ConfigPullBridge` bind-pull. Native config is **file-first**: `Android/media/<applicationId>/hsb.conf` → `persist.hsb.*` prop → compiled default, written atomically by `ConfigFileBridge` from the module process only (C paths hardcode the applicationId — `ConfigPathMirrorTest`). Root-editing `shared_prefs/*.xml` does **not** reach hooks — settings must go through the app's write path.
- **Hook targets are never hard-coded.** `anchor/` resolves obfuscated names structurally at runtime (L0 disk hint → L1 reflection fingerprint → L2 DexKit), fail-closed per role, disk-cached per host version. Ambiguous prologue scans must yield exactly one candidate — 0 or >1 ⇒ refuse to install, never "take the first" (wrong address crashes the launcher). Version bump without regeneration = silently no hook.
- **`pilferPointers()` is irreversible: if you pilfer, you must consume or forward.** Forwarding must start the moment the gesture stops being a tap candidate — buffering the whole stream to UP replays a drag as one tap (2026-10-17 incident). To swallow clicks use the SystemUI-uid `InputFilter` consume/forward channel, not monitor pilfer; pilfer only on the OS4 spy route (`b2Enabled()`), since OS2's corner fan is launched by the launcher itself. Never pilfer inside the 24 dp system-gesture band (checked at DOWN) — it breaks the app's own back gesture.
- **Never touch ROM singletons at hook-install time** (e.g. `MultiTaskingControllerStub.sInstance` is `static final`): premature resolution pins a bare stub → SystemUI `ClassCastException` crash loop → **LSPosed SafeMode** (reboot-only recovery). Resolve lazily (`ClassLoader.loadClass`, `runCatching`); Dagger component lookup via `SystemUIAppComponentFactoryBase.systemUIInitializer` is safe early — prefer it.
- **ROM thread affinities:** WMShell transition APIs (`MiuiFreeformModeAnimation.*`, `Transitions.startTransition`) must run on the WMShell `ShellExecutor` (`mMainExecutor`); `MiuiFreeformModeController.*` freeform actions on the main Handler. Wrong thread = assertion/NPE.
- **IME position on this ROM: every insets API lies** (measured 2026-10-17). Read via the WMShell `DisplayImeController$PerDisplay.insetsChanged`/`updateImeVisibility` hook (aux: SystemUI nav-bar window insets); class missing (ROM drift) ⇒ keep "unknown" and let the safety valve work. Diagnostics there must use `android.util.Log` (HLog may not be ready that early). Blank-tap judgments must subtract the IME band.
- **LSPosed module dex can be stale across `install -r`** — judge by new-code-only log lines, never by "the process restarted". After any install, verify injection first: `grep hypersidebar /data/adb/lspd/log/modules_*.log`. A changed APK path silently disables injection while the app and LSPosed DB look healthy — repair with `tools/hsb_lsp_fix_path.ps1` / `tools/hsb_deploy.py`.
- **`:ui` silently fake-succeeds** `startActivityAsUser` on unlaunchable targets (no exception) → non-exported targets go through the module-process root relay (`ShortcutRelayBridge`/`UnfreezeRelayService`; `ShortcutRelayReceiver` is legacy broadcast fallback). OS2/3 launcher tries local `startShortcut` first. Component class names must be absolute (`ComponentName` two-arg ctor does not expand a leading dot).
- **Hot reload (API 102):** `onHotReloading` (old generation) must release everything — receivers via each hook's `releaseForReload`, `removeCallbacksAndMessages(null)` for Handlers, idempotent release for HandlerThreads/pools/overlay windows (ledger in `XposedInit.onHotReloading`); `onHotReloaded` (new generation) reinstalls all Java hooks. **Native is never reinstalled** (bound to host spawner generation; G3 patrol self-heals). Any new registration/thread/window must be added to that release list.

## Key Directories

|Path|Purpose|
|---|---|
|`app/src/main/java/com/lsp/hypersidebar/`|All app source (namespace `com.lsp.hypersidebar`)|
|`hook/`|Host hooks + gesture state (`TurboLayout`, `EdgeGestureHook`, `SystemUiHook`, `SystemUiInputSpy`, `CornerTrigger`, `CircuitBreaker`)|
|`prefs/`|⭐ `PrefContract.kt` wire contract; `SettingsRepository`|
|`ui/fan/`|Compose fan overlay: `ComposeFanHost`, `FanMenuController`, pure geometry in `FanGeometry`|
|`ui/settings/`|~25 miuix settings screens (navigation3 `NavDisplay`/`NavKey`)|
|`ui/allapps/`|Exported freeform "all apps" panel activity|
|`util/`|`HLog`, `ConfigSync`, launch/shortcut/relay/native bridges, caches, `MisclickCaliber`|
|`anchor/`|Structured hook-target resolution (pure rule engine + DexKit)|
|`theme/`|`HyperSidebarTheme` over `MiuixTheme`|
|`app/src/main/cpp/`|OS4 native entry: `hypersidebar_native.c` + ELF/GOT/A64 "base" (`hsb_config.c`, `hsb_elf_view.c`, `hsb_import_got.c`, `hsb_a64_decode.c`), `launcher_profiles.h` (generated), CMake + export map|
|`app/src/main/resources/META-INF/xposed/`|`module.prop` (minApiVersion 102, staticScope=false), `scope.list`, `java_init.list`, `native_init.list`|
|`hidden-api/`|Compile-only `@hide` stubs (`InputChannel`, `InputEventReceiver`) — **compileOnly, no keep rules**|
|`docs/`, `tools/`, `PROJECT_NOTES.md`, `.workbuddy/`, `.zcode/`, `.mimosa/`, `.ota-extract/`, `_apk/`, `_repos/`|**Git-ignored local research workspace.** Editing them never shows in `git status`|

## Development Commands

Windows host (`gradlew.bat`), Gradle 9.3.1, daemon JVM 21.

```powershell
# Build
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain   # canonical full check

# Native syntax only (fast; invalidates nothing). Windows GBK-pitfall: set UTF-8 first
$env:PYTHONUTF8=1; python tools\check_native_syntax.py

# Install + restart hooks — restart BOTH hosts or you get "new native + old :ui"
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell "su -c 'am force-stop com.miui.securitycenter'"   # :ui = Kotlin half
adb shell "su -c 'am force-stop com.miui.home'"             # launcher = native half
# Open the module app once after install: it is the process that pushes persist.hsb.* props

# Forensics
adb logcat -d -s HSBNative:* TurboLayout:* SpySink:* SysInputSpy:* | Select-String 'gate:|hooks ready|hook HB|chanpublish|SUPPRESS|AUTH|band geom|arrow '

# OS4 "native went silent" four-way diagnosis (auto-verdict: not-injected / never-installed / WIPED / no-traffic)
pwsh -File tools\hsb_native_silence_probe.ps1 -Install -Inject -Watch 900
pwsh -File tools\hsb_native_silence_probe.ps1 -FromFile <saved-log>    # offline verdict only, no device

# On-device acceptance harness (real regression suite)
pwsh -File tools\hsb_device_test.ps1 -Stage A|B|C|L|M|N|O [-Install] [-Inject] [-Wait N]
```

- `tools/hsb_device_test.ps1` stages: **A** install surface, **B** observe-only (`WOULD-SUPPRESS`), **C** production (`suppress=2` + `arrowfix`), **N** arrow-fix closing, **O** de-faulted gate (only stage clearing `debug.hsb.native.hook`), **L** landscape stand-down, **M** out-of-bounds fallback. Cross-stage ⇒ re-run the script (props carry over). Where a checklist's stage names conflict with the script, trust the script (it is newer).
- `tools/check_native_syntax.py` needs one prior Gradle build (`compile_commands.json`); its `shell=True` is deliberate (Windows backslash paths break `shlex.split`).
- LSPosed path trap: `adb install -r` can invalidate the APK path in `/data/adb/lspd/config/modules_config.db` ⇒ module silently not injected. `python tools\hsb_deploy.py` (rewrites path, reboots, bounces hosts — SystemUI needs `kill -9`, verify injection) or `pwsh tools\hsb_lsp_fix_path.ps1` (path-only).
- Anchor/profile regen after a launcher OTA: `pwsh -File tools\gen_profile_from_device.ps1 [-Apk <MiuiHome.apk>] [-Build] [-Install]` (pull → `gen_launcher_profiles.py --from-so … --write` → `sync_probe_mirror.py --write` → `verify_profile_imports.py`, must report 0 broken imports).

### Launcher OTA: what to do (since 2026-11-21 the default is "nothing")

`hsb_locate` resolves an anchor in **three** passes: offset table (fastest, per-build) → **descriptor** (shape enumeration) → sigscan. The descriptor pass is why an unknown launcher build usually needs no work at all.

| Situation | Action |
|---|---|
| New launcher build, gesture still works | **None.** Watch one log line per anchor: `descriptor: <anchor> shape=unique @…` or `shape=N-hit ⇒ size→unique`. Record the build tag in `launcher_profiles.h` only when you next touch that file anyway |
| Gesture silently dead after OTA (`descriptor` logs absent or all `弃权`) | 1) `adb logcat -d -s HSBNative:* \| Select-String 'descriptor:\|hook refused\|anchor '`; 2) if the FDE line is missing (`本库没有 PT_GNU_EH_FRAME` / `解码失败`) the device's `.so` shape differs — pull the APK and run the regen command above; 3) if `形状歧义` appears for one anchor, that anchor's save-set changed: add **one template** via the regen command, not a hand edit |
| You pulled a new `MiuiHome.apk` for any reason | `python tools\gen_launcher_profiles.py --from-so <apk> <existing ones…> --write` → `python tools\sync_probe_mirror.py --write` → `python tools\gen_anchor_descriptors.py` → `python tools\verify_profile_imports.py <so>` → `:app:testDebugUnitTest` (the corpus test fails if you forget step 3) |

Cost of the descriptor pass on device: one FDE sweep per `load bias` (≈23k entries, 17 anchors × 8-word compare — single-digit ms), cached in a process-local static, re-swept automatically when launcher remap changes the base. It does **not** participate in hot-reload release bookkeeping (statics, no threads/windows).

To check the descriptor corpus is in sync without running Gradle: `python tools\gen_anchor_descriptors.py --check` (non-zero exit on drift; same predicate as `NativeDescriptorCorpusTest`).

## Code Conventions & Common Patterns

- **Cross-process contract:** every key/action/extra/probe code goes in `prefs/PrefContract.kt` (`PrefKeys`, `HostPackages`, `PrefsFiles`, `LayoutDefaults`). Never scatter literals; add new keys there first.
- **No inline magic numbers in UI** for ranges/steps/defaults — same-source constants in `LayoutDefaults`, and every adjustable setting must survive `restoreAllDefaults()`.
- **Hooks:** extend `BaseHook` (`init()`/`name`/`isInit`), resolve targets through `anchor/`, register with EzXHelper `MethodFinder`/`HookFactory`. Wrapper hooks guard on per-role fail-closed. `CircuitBreaker` stops intruding after 5 mechanism failures (no self-heal); `DataDeadState` latches ROM incompatibility. Degrade (S1 auto-degrade back to the native sidebar) rather than crash a system host.
- **Error handling:** `runCatching { … }.onFailure { HLog.w/e(…, t) }` everywhere; per-incident `Trace` ids. **Logging:** `HLog` (ring buffer, `verboseEnabled`, redaction); **never log `RelayToken`** or put it in self-check exports.
- **Exported IPC ACL:** every exported component verifies uid and/or `RelayToken`. Messenger services must read `msg.sendingUid`, not `Binder.getCallingUid()` (returns self — real 0907 misfire). `allowBackup=false` on purpose (token + circuit state must not migrate).
- **Naming:** classes `PascalCase`, members `camelCase`, constants `SCREAMING_SNAKE` in `object`s; hooks `*Hook`; strategies `*Strategy`/`*Bridge`; `*Probe` = log-only, `*Gate` = switch, `*State` = process-local flag; pure-logic helpers as Kotlin `object` singletons (`CornerTrigger`, `MisclickCaliber`, `AnchorSpec`); tests `*Test`.
- **Prefer pure-JVM testable cores** for logic (state machines, geometry, contracts) and keep the Xposed glue thin — the established split (`CornerTrigger`, `FanGeometry`, `QsAdapterContract`, `AnchorSpec` are pure; their glue is device-verified).
- **Kotlin↔C mirrors are enforced by source-text invariant tests** (`NativePrologueMirrorTest`, `NativeArrowfixInvariantTest`, `ConfigPathMirrorTest`) — update both sides and their guard tests together. `launcher_profiles.h` is generated by `tools/gen_launcher_profiles.py`, never hand-edited.
- **Comments are evidence-carrying:** non-obvious lines carry date + symptom + root cause + evidence ("jadx: …", "真机日志 …", source refs); `【待验证】` marks speculation in `PROJECT_NOTES.md`/docs. **Ownership comments are mandatory:** any receiver/thread/overlay must state who releases it and when.
- **Every settings write funnels through `SharedPreferences.savePref()` or explicit `ConfigSync.notifyConfigChanged(…)`** — batch edits (`commitDraft`, `restoreAllDefaults`) bypass the change listener.
- **Don't remove overwrite guards** born from real incidents (e.g. `ShortcutStore.saveUserShortcuts`'s `SharedPreferencesImpl` refusal — a 6-shortcuts-vs-1 data-loss case).
- **Kotlin 2.4 + Java 21 trap:** `MutableList.removeLast()` resolves to Java 21 `SequencedCollection.removeLast()` → `NoSuchMethodError` on API 33/34; use the local `popLast()` helper.
- **Git:** Conventional-commit style with Chinese subjects `type(scope): 中文描述——理由/实锤`, multi-line body (症状/根因/改法/验证) via `git commit -F -` (worked example: `docs/hsb-commit-plan.md`). Stage files **explicitly**, never `git add -A`; `version.properties` gets its own `chore` commit; verify with `git diff --cached --stat` (line-ending pollution: no `.gitattributes`/autocrlf).

### Native red lines (`app/src/main/cpp/`) — half-deleting these compiles fine and fails silently

1. **arrowfix has two halves** — `hsb_arrow_state_release()` and `if (fixed) g_arrow_due = 1;`. Both must stay (`NativeArrowfixInvariantTest` guards it).
2. **Only hook `handle_back_gesture`** (side-only, per-event boundary). Never hook shared outer entries (`on_input_event`) — that ANR'd/killed bottom Home+recents on device.
3. **No global `dlopen`/`dlsym` interception.** Hooks are inline prologue hooks installed through LSPosed `hook_func`; PLT/GOT analysis (`hsb_import_got.c`, `hsb_a64_decode.c`) is structural proof only — never turn it into interception. Default (and only production) anchor is `handle_back_gesture` (`HSB_ONLY_ANCHOR`, backed by a debug-prop whitelist: `anchor2` = `pilfer_pointers`); `anchor3` probe stubs are read-only pass-through and must never alter return values.
4. **Never overwrite a live-mapped `.so`**, never rewrite `.text` another thread may be executing. Heal only via the framework (`unhook_func` → verify original prologue → `hook_func`) — that is what the G3 patch-integrity patrol does.
5. **The module never restarts a host process**; only prompt the user (tooling scripts may force-stop; module code must not).
6. **16 KB page contract:** `PT_LOAD.p_align ≥ 16384` (CMake `-Wl,-z,max-page-size=16384`) **and** aligned ZIP entry (`jniLibs.useLegacyPackaging = false`). A violation aborts the whole module at `native_init` — Java hooks die too.
7. **Prologue/prologue-mask matching is fail-closed**: a scan with ≠1 hit must refuse to install rather than guess.
8. Even "log-only" native edits need `tools\check_native_syntax.py` **and** an on-device run before being called done.
9. `HSB_NATIVE_SPIKE` is a plain CMake variable (deliberately **not** `option()`/CACHE — cached values stick in `app/.cxx`); it is currently `ON` in the tree while the in-file comment still says spike-branch-only. Flipping it is a release-shape decision — don't convert it to a cache variable, and verify before shipping OS2/OS3 builds.
10. **`anchor_descriptors.h` is generated, never hand-edited** — `python tools/gen_anchor_descriptors.py`. It is derived from `launcher_profiles.h` (17 anchors / 30 masked templates / per-anchor length sets); `NativeDescriptorCorpusTest` enforces sync **both ways** and `--check` is the same predicate. It must never contain `action_probe`: that stub rewrites system gesture judgement (`hsb_stub_actionprobe`) and its prologue has 85–88 hits in real launchers, so shape enumeration is necessarily ambiguous there — `action_probe` stays offset-table-only, and a shape miss means "refuse to install", never "guess".
11. **The descriptor path must never guess.** In `hsb_descriptor_locate` a 0-hit or ≥2-hit-after-size anchor returns 0 (abstain → fall through to sigscan → refuse). Do not add a "take the first hit" fallback and do not widen it to `action_probe`; measured 0 wrong picks across 10 launcher builds depend on this exact rule.

## Important Files

|Path|Why it matters|
|---|---|
|`app/src/main/java/com/lsp/hypersidebar/XposedInit.kt`|Module entry, host/process routing, hot-reload release ledger|
|`app/src/main/java/com/lsp/hypersidebar/prefs/PrefContract.kt`|All cross-process keys, host packages, defaults|
|`app/src/main/java/com/lsp/hypersidebar/hook/EdgeGestureHook.kt`|Launcher portrait edge/corner gesture|
|`app/src/main/java/com/lsp/hypersidebar/hook/TurboLayout.kt`|`:ui` landscape B-route trigger + layer hiding + strip machine|
|`app/src/main/java/com/lsp/hypersidebar/hook/CornerTrigger.kt`|Pure corner-swipe state machine (heavily unit-tested)|
|`app/src/main/java/com/lsp/hypersidebar/ui/fan/ComposeFanHost.kt`|WindowManager Compose host, hysteresis, idle watchdog|
|`app/src/main/java/com/lsp/hypersidebar/ui/fan/FanGeometry.kt`|Pure fan geometry|
|`app/src/main/java/com/lsp/hypersidebar/anchor/AnchorResolver.kt` / `AnchorSpec.kt`|Structured anchor resolution (rule engine is pure)|
|`app/src/main/java/com/lsp/hypersidebar/util/ShortcutLauncher.kt`|Launch strategies (Default/System/ROOT) + `FailureReason`|
|`app/src/main/java/com/lsp/hypersidebar/util/ConfigSync.kt`|Prefs replication into hook processes (sanitize point)|
|`app/src/main/java/com/lsp/hypersidebar/hook/SystemUiInputSpy.kt`|OS4 global input monitor: zone classification, pilfer/takeover + replay, echo filter|
|`app/src/main/java/com/lsp/hypersidebar/hook/FreeformBlankTapHook.kt`|ROM freeform blank-tap actions; lazy singleton resolution + ROM thread affinity|
|`app/src/main/java/com/lsp/hypersidebar/hook/BaseHook.kt`|Hot-reload contract (`releaseForReload` / `rearmHostSide`) every hook follows|
|`app/src/main/java/com/lsp/hypersidebar/util/RelayToken.kt`|IPC auth model (`verifyFan` lenient / `verifyRelay` strict)|
|`app/src/main/java/com/lsp/hypersidebar/util/ConfigFileBridge.kt` / `CornerGeomBridge.kt` / `NativeGateBridge.kt`|Java→native channels: file-first config, geometry push, fail-open master gate|
|`app/src/main/cpp/hsb_config.c`|Native config reader (file → prop → default); paths mirror `applicationId`|
|`app/build.gradle.kts`, `gradle/libs.versions.toml`|SDK levels, versionCode logic, NDK/packaging, deps|
|`app/src/main/cpp/CMakeLists.txt`, `exports-lsposed.map`|Native build: version script, `max-page-size=16384`, `-fvisibility=hidden`|
|`app/src/main/cpp/launcher_profiles.h`|Generated 16-anchor prologue table (OTA snapshot — regenerate, never edit)|
|`app/proguard-rules.pro`|R8: keep `XposedInit`, `-keepnames com.lsp.hypersidebar.**`; never keep `@hide` stubs|
|`PROJECT_NOTES.md`|Architecture/hard-conclusion ledger (**stale numbers** in §2/§8 — re-derive from source)|
|`docs/adaptation/os4-native-handoff.md`|OS4 state + red lines §9 + system-gesture gate §10|
|`docs/adaptation/freeform-blank-tap-scheme.md`|Hook-safety rules (singleton trap, pilfer, threading, IME)|
|`docs/adaptation/os4-native-hold-route-test-checklist.md`|Device checklist — read **§0.5 validity table first** (stages D/E/R/G, O/P/Q obsolete; deleted switches `judge/evoke/cancel/delegate/recover` must not be resurrected)|

Reading order for a new agent: `PROJECT_NOTES.md` (ledger; trust its discipline rules, not its numbers) → `docs/adaptation/os4-native-handoff.md` → `docs/adaptation/adaptation-checklist.md`. When a number matters, re-derive it from `app/build.gradle.kts` / `gradle/libs.versions.toml` / `wc -l` / `git rev-list`.

## Runtime/Tooling Preferences

- **JDK 21** everywhere (daemon toolchain via foojay, `gradle/gradle-daemon-jvm.properties`; source/target 21 — required by miuix 0.9.4 inline `entry` DSL). `.idea` uses temurin-21.
- **AGP 9.1.1 / Kotlin 2.4.0 / Gradle 9.3.1.** All deps via `gradle/libs.versions.toml`. **There is no `kotlin-android` plugin** — AGP 9's built-in Kotlin compiles sources; only `kotlin.plugin.compose` is declared. Don't add one.
- `compileSdk 37 / minSdk 33 (hard floor: miuix-blur AGSL RuntimeShader) / targetSdk 36`; `versionName` is manual (`2.3.1`); no product flavors (debug + release only).
- **Pinned deps — upgrading any of these is an independent decision with full device regression:** `composeBom 2024.02.00` (miuix 0.9.4 validated only against it), `xposed-api 102.0.0` (compileOnly) + `xposed-service 102.0.0`, EzXHelper 3.2.0-preview1 with its `xposed-api-101` bridge (**no `-102` artifact exists**), `dexkit 2.3.0`, `activity 1.13.0` (miuix dialog back handling requires it).
- **NDK 27.2.12479018 / CMake 4.1.2 / arm64-v8a only** (`NativeLibLoader.ABI` mirrors `abiFilters` — change both). Pure C, links only `log`.
- Release builds: R8 + resource shrinking; signing via git-ignored `keystore.properties` (+ `keystore/`) — absent ⇒ `app-release-unsigned.apk`. Any build **rewrites tracked `version.properties`** (versionCode = `yyyyMMdd*100 + dailySeq`, Locale.US-pinned; runs even for `testDebugUnitTest` via `preBuild`) — commit it as its own `chore`, or pass `-x :app:preBuild`.
- **No CI** (no `.github/`), **no ktlint/detekt/spotless/`.editorconfig`/lint config** — do not add lint tooling or expect CI gates. No emulator; device-only verification.
- Host scripts: Python 3 + PowerShell 7; **always `$env:PYTHONUTF8=1` before `tools\*.py`** (GBK console mangling). `tools/` is research tooling — never wire it into Gradle.

## Testing & QA

- **Frameworks:** JUnit 4.13.2 only, plus real `org.json:json:20240303` as `testImplementation` (Android's stub throws "not mocked"). No JUnit 5, Robolectric, MockK, Turbine, Compose-UI-Test; **no coverage tooling or thresholds**. Do not add.
- **Unit tests:** `app/src/test/java/com/lsp/hypersidebar/` — **34 test classes, 236 `@Test` methods** (count with grep; stale `app/build` artifacts and old docs disagree — never quote a count you didn't re-derive).
- **What is unit-tested:** pure-JVM cores — gesture state machines (`CornerTrigger`, `BlankTapDetector`, `GestureSamples`, `GestureTakeover`), geometry (`FanGeometry*`, `FreeformBlankTest`, `ImeRectMath`, `GroupPopupLayout`), models/parsing (`Shortcut*`, `RomSignals`, `ContextLaunchLog`, `ShortcutRootCatalog`), contracts (`QsAdapterContract`, `AnchorEngine`, `ConfigSync.sanitizeSyncMap`), plus **source-text mirror/invariant tests**: `NativePrologueMirrorTest` (`launcher_profiles.h` ↔ `NativeRootProbe.ORIGINAL_PROLOGUE`, 16 B/32 hex), `NativeArrowfixInvariantTest`, `NativeSigscanDisambiguationTest`, `ConfigPathMirrorTest` (C↔Kotlin path/prefix), `BackgroundAndSwipeContractTest` (dwell 150/350/250 ↔ native `hsb_strip_dwell_ms()` clamp).
- **Untested by design** (device-verified only): hook glue, `ui/settings/**`, interactive `ui/fan/**`, native C runtime, `prefs` repositories. The `androidTest` source set was deliberately gutted (2026-10-26); the leftover `ExampleInstrumentedTest.kt` cannot compile — don't resurrect it.
- **Rules:** never weaken or delete an assertion to make a suite pass; environment gates use `Assume` (see `ProcCaptureTest`). Kotlin↔C mirror pairs move together with their guard tests. Mirror tests resolve fixtures from the `app/` module CWD (`src/main/…` then `app/src/main/…`) and fail loudly when the fixture is missing — never silently skip a source-text invariant. Test names: `*Test` in the production package; `org.junit.Assert.*` with Chinese failure messages for invariants.
- **Verification loop:** `.\gradlew.bat :app:testDebugUnitTest` → `.\gradlew.bat :app:assembleDebug` → install + restart both hosts → on-device acceptance via `tools/hsb_device_test.ps1` (and `docs/adaptation/*checklist*`, reading their validity tables first). For "OS4 native went silent", `tools/hsb_native_silence_probe.ps1` verdict first — it decides G2/G3 vs a config fix; do not guess.
- **Do-done criteria:** pure logic + new unit tests + full `testDebugUnitTest` pass count recorded; visual/behavioral changes require on-device acceptance before being called done.
