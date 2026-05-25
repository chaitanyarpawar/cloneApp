# Quick Reference - Virtualization-Based Cloning

## 🚀 Quick Start

### Launch a Clone (Dart)
```dart
// Using the service
final service = CloneAppService();
final result = await service.launchClonedAppWithResult(clonedApp);

if (result.success) {
  print('Clone launched successfully');
} else {
  print('Error: ${result.error}');
  if (result.canRetry) {
    // Show retry button
  }
}
```

### Launch Workflow
```
1. Pre-validation ────┐
2. Storage setup  ────┤─── <5 seconds total
3. Launch         ────┤
4. Ads (delayed)  ────┘
```

## 🔑 Key Classes

### Dart Layer
```dart
// Core engine
CloneVirtualizationEngine()
  .launchVirtualizedClone(
    packageName: 'com.example.app',
    cloneId: 'clone_123',
    config: {'delayAds': true}
  )

// Storage management
IsolatedStorageManager()
  .createIsolatedStorage(
    cloneId: 'clone_123',
    packageName: 'com.example.app'
  )

// Process tracking
CloneProcessManager()
  .registerProcess(cloneId, processInfo)
```

### Kotlin Layer
```kotlin
// Virtualization engine
CloneVirtualizationEngine(context)
  .launchVirtualized(
    packageName = "com.example.app",
    cloneId = "clone_123",
    storagePath = "/data/.../clones/clone_123",
    config = mapOf("delayAds" to true),
    callback = object : VirtualizationCallback {
      override fun onSuccess(cloneId: String, elapsedMs: Long) {}
      override fun onFailure(cloneId: String, error: String, stage: String) {}
    }
  )

// Isolated context
IsolatedContextWrapper(
  base = context,
  cloneId = "clone_123",
  packageName = "com.example.app",
  storagePath = "/path/to/storage",
  classLoader = dexClassLoader
)
```

## ⚡ Performance Targets

| Metric | Target | Typical | Maximum |
|--------|--------|---------|---------|
| Total launch | <5s | 1-3s | 5s |
| Validation | <200ms | 50ms | 200ms |
| Environment | <500ms | 200ms | 500ms |
| Launch | <4s | 1-2s | 4s |
| Ad delay | 2s+ | 2s | - |

## 🛠️ Common Patterns

### Error Handling
```dart
try {
  final result = await engine.launchVirtualizedClone(...);
  
  if (!result.success) {
    switch (result.stage) {
      case 'validation':
        // Handle validation errors
        if (result.error.contains('banking')) {
          showBankingAppWarning();
        }
        break;
      case 'launch':
        // Handle launch errors
        if (result.canRetry) {
          showRetryButton();
        }
        break;
    }
  }
} on TimeoutException {
  showTimeoutError();
} catch (e) {
  showGenericError(e);
}
```

### Retry Logic
```dart
Future<LaunchResult> launchWithRetry(ClonedApp app) async {
  for (int attempt = 0; attempt < 3; attempt++) {
    final result = await service.launchClonedAppWithResult(app);
    if (result.success) return result;
    
    await Future.delayed(Duration(milliseconds: 500));
  }
  return LaunchResult(success: false, error: 'Max retries exceeded');
}
```

### Storage Management
```dart
// Create storage
final storage = await storageManager.createIsolatedStorage(
  cloneId: cloneId,
  packageName: packageName,
);

// Check size
final size = await storageManager.getStorageSize(cloneId);

// Clear cache
await storageManager.clearCache(cloneId);

// Cleanup
await storageManager.cleanupCloneStorage(cloneId);
```

## 🎯 Critical Rules

### ✅ DO:
- Launch on background thread
- Set strict timeouts (5s)
- Delay ads (2s minimum)
- Validate before launch
- Handle errors gracefully
- Log all operations
- Clear user communication

### ❌ DON'T:
- Block main thread
- Initialize ads in onCreate
- Modify APK packages
- Require root access
- Bypass security checks
- Make assumptions about success
- Ignore timeouts

## 🔍 Debugging

### Enable Verbose Logging
```dart
// Dart
CloneLogger.setLogLevel(LogLevel.debug);

// Kotlin
Log.d(TAG, "[$cloneId] Debug message")
```

### Export Logs
```dart
final logs = await service.exportLogsForFeedback();
// Share logs with support
```

### Check Diagnostics
```dart
final diagnostics = await service.collectDiagnostics(packageName);
print('Device: ${diagnostics['device']}');
print('App installed: ${diagnostics['isAppInstalled']}');
print('App running: ${diagnostics['isAppRunning']}');
```

## 📱 Platform Channels

### Main Channel
```dart
static const platform = MethodChannel(
  'com.cloneapp.multiaccount/app_launcher'
);
```

### Virtualization Channel
```dart
static const virtualization = MethodChannel(
  'com.cloneapp.multiaccount/virtualization'
);

// Available methods:
// - launchVirtualized
// - terminateVirtualized
// - getActiveInstances
// - initializeEnvironment
// - getVirtualizationDiagnostics
```

## 🏗️ Architecture Layers

```
┌────────────────────────────────────┐
│ UI Layer (Flutter Widgets)        │
├────────────────────────────────────┤
│ Service Layer (CloneAppService)   │
├────────────────────────────────────┤
│ Core Layer (VirtualizationEngine) │
├────────────────────────────────────┤
│ Platform Channel                   │
├────────────────────────────────────┤
│ Native Layer (Kotlin)              │
│ - CloneVirtualizationEngine        │
│ - IsolatedContextWrapper           │
│ - CloneLauncher                    │
└────────────────────────────────────┘
```

## 🎨 User Experience Flow

```
1. User taps "Launch Clone"
   ↓
2. Show loading indicator
   ↓
3. Launch virtualized instance
   ↓
4. Target app UI appears (2-4s)
   ↓
5. Ads initialize (delayed)
   ↓
6. User interacts with clone

If error:
   ↓
Show clear error message
   ↓
Offer retry if applicable
   ↓
Provide log export option
```

## 🔒 Security Checklist

```
□ No package modification
□ No signature spoofing
□ No root requirement
□ No system calls
□ Respect app permissions
□ Follow Play Store policies
□ Clear user consent
□ Data isolation verified
```

## 📊 Monitoring

### Key Metrics to Track:
```dart
// Launch success rate
final successRate = successfulLaunches / totalAttempts;

// Average launch time
final avgLaunch = totalLaunchTime / successfulLaunches;

// Error distribution
final errorsByType = {
  'validation': validationErrors,
  'environment': environmentErrors,
  'launch': launchErrors,
  'timeout': timeoutErrors,
};

// Clone statistics
final cloneStats = {
  'totalClones': activeClones.length,
  'storageUsed': totalStorageBytes,
  'mostClonedApps': topPackages,
};
```

## 🐛 Common Issues & Fixes

### Issue: Launch Timeout
```dart
// Increase timeout (carefully)
static const Duration _launchTimeout = Duration(seconds: 7);

// Or optimize environment setup
```

### Issue: Storage Errors
```dart
// Check available space
final freeSpace = await storageManager.getAvailableSpace();
if (freeSpace < 100 * 1024 * 1024) {
  showStorageWarning();
}
```

### Issue: ANR on Startup
```kotlin
// Ensure background threading
backgroundExecutor.execute {
  // Heavy operations here
}
```

## 📚 Related Files

- `lib/core/virtualization/clone_virtualization_engine.dart`
- `lib/core/virtualization/isolated_storage_manager.dart`
- `lib/services/clone_app_service.dart`
- `android/.../CloneVirtualizationEngine.kt`
- `android/.../IsolatedContextWrapper.kt`

## 🎓 Best Practices

1. **Always validate first**
   ```dart
   final validation = await engine.performValidation(packageName);
   if (!validation.isValid) return;
   ```

2. **Use timeouts everywhere**
   ```dart
   await operation().timeout(Duration(seconds: 5));
   ```

3. **Handle all error cases**
   ```dart
   try {
     // attempt operation
   } on TimeoutException {
     // handle timeout
   } on PlatformException {
     // handle platform error
   } catch (e) {
     // handle generic error
   }
   ```

4. **Log important events**
   ```dart
   _logger.log('operation', 'description', cloneId: id);
   ```

5. **Clean up resources**
   ```dart
   await storageManager.cleanupCloneStorage(cloneId);
   ```

---

**Quick Reference Version:** 1.0  
**Last Updated:** December 2025
