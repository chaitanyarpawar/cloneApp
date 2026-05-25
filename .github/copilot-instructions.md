# CloneApp - AI Coding Instructions

## Project Overview

CloneApp is a **Flutter + Kotlin** multi-account app cloning solution that uses **virtualization** (NOT package renaming) to run multiple isolated app instances. It's designed to be **Play Store compliant**—no root, no APK modification, no system privileges.

## Architecture

### Two-Layer Communication
```
Flutter (Dart)  ←→  MethodChannel  ←→  Android (Kotlin)
```

**Critical entry points:**
- **Dart side:** CloneVirtualizationEngine (in lib → core → virtualization) - orchestrates launches via `MethodChannel('com.cloneapp.multiaccount/virtualization')`
- **Kotlin side:** VirtualizationFacade (in android → app → src → main → kotlin → com.cloneapp.multiaccount → virtualization) - coordinates all native components

### Virtualization Component Stack (Kotlin)
Located in android/app/src/main/kotlin/com.cloneapp.multiaccount/virtualization/:
| Component | Responsibility |
|-----------|----------------|
| ContainerManager | Container lifecycle, per-clone isolated paths |
| VirtualProcessManager | DexClassLoader caching, process isolation |
| ActivityProxyManager | Intent interception, launch orchestration |
| FileRedirector | SharedPrefs/file path redirection |
| BinderProxyManager | AccountManager/PackageManager proxying |
| PermissionMediator | Per-clone permission state |

### Flutter Layer Structure
- **lib/services/** - Business logic (CloneAppService, CloneLaunchService)
- **lib/core/virtualization/** - Dart virtualization engine
- **lib/screens/** - UI screens
- **lib/blocs/** - BLoC state management (theme only currently)
- **lib/models/** - Data models (ClonedApp, LaunchResult, VirtualizationResult)

## Critical Performance Constraints

**These are NON-NEGOTIABLE for Play Store compliance:**

1. **Startup timeout:** Max 5 seconds for clone launch
2. **Ad SDK init:** MUST be async, NEVER block `onCreate()`/`onResume()`. Delay ads 2+ seconds after UI renders.
3. **Heavy operations:** Run off main thread via `backgroundExecutor.execute {}`

```kotlin
// CORRECT: Ads delayed after launch
mainHandler.postDelayed({ initializeAds() }, 2000)

// WRONG: Blocking ad init
MobileAds.instance.initialize().get() // Never do this
```

## Launch Strategy Pattern

Clone launches use a **fallback chain** (see CloneLaunchService in lib/services):
1. Direct multi-instance (fastest)
2. Virtual container
3. Clone activity proxy
4. Force restart (most compatible)

Always preserve this pattern when modifying launch logic.

## Storage Isolation

Each clone gets unique directories under `/clones/{cloneId}/`:
- `data/`, `cache/`, `databases/`, `files/`, `shared_prefs/`

Never share static state between clones—use IsolatedContextWrapper for context operations.

## MethodChannel Contract

```dart
// Dart → Kotlin channels
'com.cloneapp.multiaccount/app_launcher'      // Legacy launch methods
'com.cloneapp.multiaccount/virtualization'    // Advanced virtualization
```

When adding new native functionality:
1. Add method handler in MainActivity.kt (android/app/.../com.cloneapp.multiaccount/)
2. Create corresponding Dart wrapper in appropriate service

## Blacklisted Apps

Apps using SafetyNet/Play Integrity **cannot** be virtualized. Check `blacklistedApps` in the virtualization engine before attempting. Includes: banking apps (com.phonepe.app, net.one97.paytm), Google core services.

## Build & Test

```bash
# Build release APK
flutter build apk --release

# Debug logs during clone launch
adb logcat -s CloneApp VirtualizationFacade

# Test multi-instance via ADB
adb shell am start -n <package>/<activity> --activity-new-task --activity-multiple-task
```

## Key Conventions

- **Logging:** Use CloneLogger (Dart) or `Log.d(TAG, ...)` (Kotlin) with clone ID context
- **Timeouts:** Always wrap async operations; use `Future.any()` for race conditions
- **Error handling:** Return result objects (LaunchResult, VirtualizationResult) with diagnostics, never throw uncaught
- **Callbacks:** Native uses VirtualizationCallback interface; Dart uses `Future<Result>`

## Files to Check First

**For virtualization changes:**
- VirtualizationFacade.kt (android/app/.../virtualization/)
- clone_virtualization_engine.dart (lib/core/virtualization/)

**For launch behavior:**
- CloneLauncher.kt (android/app/.../com.cloneapp.multiaccount/)
- clone_launch_service.dart (lib/services/)

**For UI/state:**
- clone_app_service.dart (lib/services/)
- home_screen.dart (lib/screens/)
