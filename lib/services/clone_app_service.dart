import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/cloned_app.dart';
import '../models/launch_result.dart';
import '../core/virtualization/clone_virtualization_engine.dart';
import 'settings_service.dart';
import 'clone_logger.dart';

// Platform-specific imports
import 'package:installed_apps/app_info.dart';
import 'package:installed_apps/installed_apps.dart';
import 'package:external_app_launcher/external_app_launcher.dart';

/// Service for managing cloned apps with virtualization-based approach
/// Play Store compliant: NO package renaming, NO APK re-signing
/// Uses sandbox runtime and classloader for isolation
class CloneAppService {
  static const String _clonedAppsKey = 'cloned_apps';
  static const MethodChannel _platform = MethodChannel(
    'com.cloneapp.multiaccount/app_launcher',
  );

  final CloneLogger _logger = CloneLogger();
  final CloneVirtualizationEngine _virtualizationEngine =
      CloneVirtualizationEngine();

  // Launch configuration
  static const Duration _launchTimeout = Duration(seconds: 5);

  // Get all installed apps (optimized for faster loading)
  Future<List<AppInfo>> getInstalledApps({bool includeIcons = false}) async {
    if (kIsWeb) {
      return _getMockApps();
    }

    try {
      List<AppInfo> apps = await InstalledApps.getInstalledApps(
        false, // includeSystemApps
        includeIcons, // includeAppIcons
      );

      apps = apps
          .where((app) => app.packageName.isNotEmpty && app.name.isNotEmpty)
          .toList();

      apps.sort((a, b) => a.name.compareTo(b.name));
      return apps;
    } catch (e) {
      _logger.logError('getInstalledApps', 'Failed to load apps', error: e);
      return kIsWeb ? _getMockApps() : [];
    }
  }

  // Get app icon separately for better performance
  Future<List<int>?> getAppIcon(String packageName) async {
    if (kIsWeb) return null;

    try {
      List<AppInfo> apps = await InstalledApps.getInstalledApps(false, true);

      final app = apps.firstWhere(
        (app) => app.packageName == packageName,
        orElse: () => AppInfo(
          name: '',
          packageName: '',
          icon: null,
          versionName: '',
          versionCode: 0,
          builtWith: BuiltWith.flutter,
          installedTimestamp: 0,
        ),
      );

      return app.icon;
    } catch (e) {
      _logger.logError(
        'getAppIcon',
        'Failed to get icon',
        error: e,
        data: {'packageName': packageName},
      );
      return null;
    }
  }

  // Mock apps for web platform
  List<AppInfo> _getMockApps() {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    return [
      AppInfo(
        name: "WhatsApp",
        packageName: "com.whatsapp",
        icon: null,
        versionName: "2.23.24.12",
        versionCode: 1234567,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Instagram",
        packageName: "com.instagram.android",
        icon: null,
        versionName: "304.0.0.34.76",
        versionCode: 2345678,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Facebook",
        packageName: "com.facebook.katana",
        icon: null,
        versionName: "440.0.0.33.69",
        versionCode: 3456789,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Telegram",
        packageName: "org.telegram.messenger",
        icon: null,
        versionName: "10.2.5",
        versionCode: 6789012,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
    ];
  }

  // Get all cloned apps
  Future<List<ClonedApp>> getClonedApps() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final clonedAppsJson = prefs.getStringList(_clonedAppsKey) ?? [];

      return clonedAppsJson
          .map((jsonString) => ClonedApp.fromJson(json.decode(jsonString)))
          .toList();
    } catch (e) {
      _logger.logError('getClonedApps', 'Failed to load cloned apps', error: e);
      return [];
    }
  }

  // Add a new cloned app with proper icon handling
  Future<bool> addClonedApp(AppInfo app) async {
    final cloneId = DateTime.now().millisecondsSinceEpoch.toString();
    _logger.log(
      'addClonedApp',
      'Adding clone',
      cloneId: cloneId,
      data: {'appName': app.name, 'packageName': app.packageName},
    );

    try {
      final prefs = await SharedPreferences.getInstance();
      final existingApps = await getClonedApps();

      // Check clone limit per app
      final existingClones = existingApps
          .where((clonedApp) => clonedApp.packageName == app.packageName)
          .length;

      if (existingClones >= 5) {
        _logger.logWarning(
          'addClonedApp',
          'Too many clones for this app',
          cloneId: cloneId,
        );
        return false;
      }

      // Handle icon
      String? iconBase64;
      if (app.icon != null) {
        try {
          iconBase64 = base64Encode(app.icon!);
        } catch (e) {
          iconBase64 = null;
        }
      } else {
        final iconBytes = await getAppIcon(app.packageName);
        if (iconBytes != null) {
          try {
            iconBase64 = base64Encode(iconBytes);
          } catch (e) {
            iconBase64 = null;
          }
        }
      }

      final clonedApp = ClonedApp(
        id: cloneId,
        appName: app.name,
        packageName: app.packageName,
        appIcon: iconBase64,
        createdAt: DateTime.now(),
      );

      existingApps.add(clonedApp);

      final clonedAppsJson = existingApps
          .map((app) => json.encode(app.toJson()))
          .toList();

      await prefs.setStringList(_clonedAppsKey, clonedAppsJson);

      _logger.log('addClonedApp', 'Clone added successfully', cloneId: cloneId);
      return true;
    } catch (e) {
      _logger.logError(
        'addClonedApp',
        'Failed to add clone',
        error: e,
        cloneId: cloneId,
      );
      return false;
    }
  }

  /// Launch a cloned app with virtualization-based approach
  /// This is the PREFERRED method - uses Play Store-compliant virtualization
  /// - Launches in <5 seconds
  /// - UI appears before ads
  /// - Proper error handling with retry support
  /// - Clear user communication
  Future<LaunchResult> launchClonedAppWithResult(ClonedApp clonedApp) async {
    final cloneId = '${clonedApp.id}_${DateTime.now().millisecondsSinceEpoch}';
    final startTime = DateTime.now();

    _logger.logLaunchStart(clonedApp.packageName, cloneId);

    if (kIsWeb) {
      return LaunchResult(
        success: true,
        cloneId: cloneId,
        launchMethod: 'web_mock',
      );
    }

    // Check biometric authentication if required
    try {
      final settingsService = SettingsService();
      final requireAuth = await settingsService.shouldRequireBiometricAuth();

      if (requireAuth) {
        final authenticated = await settingsService.authenticateWithBiometrics(
          reason: 'Authenticate to launch ${clonedApp.appName}',
        );

        if (!authenticated) {
          _logger.log(
            'launchClonedApp',
            'Authentication failed',
            cloneId: cloneId,
          );
          return LaunchResult(
            success: false,
            error: 'Authentication failed',
            cloneId: cloneId,
            launchMethod: 'auth_failed',
          );
        }
      }
    } catch (e) {
      _logger.logWarning(
        'launchClonedApp',
        'Auth check failed, proceeding anyway',
        cloneId: cloneId,
      );
    }

    // Strategy 1: Virtualization-based launch (PREFERRED)
    final virtResult = await _tryVirtualizationLaunch(
      clonedApp.packageName,
      cloneId,
    );
    if (virtResult.success) {
      _logLaunchComplete(cloneId, virtResult, startTime);
      return virtResult;
    }

    // Fallback strategies if virtualization not available
    LaunchResult? result;

    // Strategy 2: Enhanced launcher
    result = await _tryEnhancedLaunch(clonedApp.packageName, cloneId);
    if (result.success) {
      _logLaunchComplete(cloneId, result, startTime);
      return result;
    }

    // Strategy 3: Direct multi-instance
    result = await _tryDirectMultiInstance(clonedApp.packageName, cloneId);
    if (result.success) {
      _logLaunchComplete(cloneId, result, startTime);
      return result;
    }

    // Strategy 4: Clone activity proxy
    result = await _tryCloneActivityProxy(clonedApp.packageName, cloneId);
    if (result.success) {
      _logLaunchComplete(cloneId, result, startTime);
      return result;
    }

    // Strategy 5: Virtual container
    result = await _tryVirtualContainer(clonedApp.packageName, cloneId);
    if (result.success) {
      _logLaunchComplete(cloneId, result, startTime);
      return result;
    }

    // Strategy 6: Basic launch fallback
    result = await _tryBasicLaunch(clonedApp.packageName, cloneId);
    _logLaunchComplete(cloneId, result, startTime);

    return result;
  }

  /// Try virtualization-based launch (Play Store compliant)
  Future<LaunchResult> _tryVirtualizationLaunch(
    String packageName,
    String cloneId,
  ) async {
    try {
      _logger.log(
        'launchStrategy',
        'Trying virtualization launch',
        cloneId: cloneId,
      );

      // Launch with virtualization engine
      final result = await _virtualizationEngine
          .launchVirtualizedClone(
            packageName: packageName,
            cloneId: cloneId,
            config: {
              'delayAds': true,
              'adDelaySeconds': 2,
              'offloadHeavyOps': true,
              'useBackgroundThread': true,
              'timeoutSeconds': 5,
            },
          )
          .timeout(_launchTimeout);

      if (result.success) {
        _logger.log(
          'launchStrategy',
          'Virtualization launch succeeded',
          cloneId: cloneId,
          data: {'duration': result.launchDuration?.inMilliseconds},
        );
      }

      return LaunchResult(
        success: result.success,
        error: result.error,
        cloneId: cloneId,
        launchMethod: 'virtualized',
        diagnostics: result.diagnostics,
        launchDuration: result.launchDuration,
      );
    } catch (e) {
      _logger.logDebug(
        'launchStrategy',
        'Virtualization launch failed: $e',
        cloneId: cloneId,
      );
      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'virtualized',
      );
    }
  }

  /// Legacy method for backwards compatibility
  Future<bool> launchClonedApp(ClonedApp clonedApp) async {
    final result = await launchClonedAppWithResult(clonedApp);
    return result.success;
  }

  void _logLaunchComplete(
    String cloneId,
    LaunchResult result,
    DateTime startTime,
  ) {
    final duration = DateTime.now().difference(startTime);
    _logger.logLaunchComplete(
      cloneId,
      result.success,
      result.launchMethod,
      duration,
    );
  }

  Future<LaunchResult> _tryEnhancedLaunch(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform
          .invokeMethod('launchDirectMultiInstance', {
            'packageName': packageName,
            'cloneId': cloneId,
            'timestamp': DateTime.now().millisecondsSinceEpoch,
          })
          .timeout(_launchTimeout);

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'enhanced_launcher',
      );
    } catch (e) {
      _logger.logDebug(
        'launchStrategy',
        'Enhanced launch failed: $e',
        cloneId: cloneId,
      );
      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'enhanced_launcher',
      );
    }
  }

  Future<LaunchResult> _tryDirectMultiInstance(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform
          .invokeMethod('launchMultipleInstancePro', {
            'packageName': packageName,
            'cloneId': cloneId,
          })
          .timeout(_launchTimeout);

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'direct_multi_instance',
      );
    } catch (e) {
      _logger.logDebug(
        'launchStrategy',
        'Direct multi-instance failed: $e',
        cloneId: cloneId,
      );
      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'direct_multi_instance',
      );
    }
  }

  Future<LaunchResult> _tryCloneActivityProxy(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform
          .invokeMethod('launchViaCloneActivity', {
            'packageName': packageName,
            'cloneId': cloneId,
          })
          .timeout(_launchTimeout);

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'clone_activity_proxy',
      );
    } catch (e) {
      _logger.logDebug(
        'launchStrategy',
        'Clone activity proxy failed: $e',
        cloneId: cloneId,
      );
      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'clone_activity_proxy',
      );
    }
  }

  Future<LaunchResult> _tryVirtualContainer(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform
          .invokeMethod('launchAppWithVirtualContainer', {
            'packageName': packageName,
            'cloneId': cloneId,
          })
          .timeout(_launchTimeout);

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'virtual_container',
      );
    } catch (e) {
      _logger.logDebug(
        'launchStrategy',
        'Virtual container failed: $e',
        cloneId: cloneId,
      );
      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'virtual_container',
      );
    }
  }

  Future<LaunchResult> _tryBasicLaunch(
    String packageName,
    String cloneId,
  ) async {
    try {
      await LaunchApp.openApp(
        androidPackageName: packageName,
        openStore: false,
      );

      return LaunchResult(
        success: true,
        cloneId: cloneId,
        launchMethod: 'basic_launch',
      );
    } catch (e) {
      _logger.logError(
        'launchStrategy',
        'Basic launch failed',
        error: e,
        cloneId: cloneId,
      );
      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'basic_launch',
      );
    }
  }

  // Remove a cloned app
  Future<bool> removeClonedApp(String id) async {
    _logger.log('removeClonedApp', 'Removing clone', cloneId: id);

    try {
      final prefs = await SharedPreferences.getInstance();
      final existingApps = await getClonedApps();

      existingApps.removeWhere((app) => app.id == id);

      final clonedAppsJson = existingApps
          .map((app) => json.encode(app.toJson()))
          .toList();

      await prefs.setStringList(_clonedAppsKey, clonedAppsJson);

      _logger.log('removeClonedApp', 'Clone removed successfully', cloneId: id);
      return true;
    } catch (e) {
      _logger.logError(
        'removeClonedApp',
        'Failed to remove clone',
        error: e,
        cloneId: id,
      );
      return false;
    }
  }

  // Request battery optimization exemption
  Future<bool> requestBatteryOptimizationExemption() async {
    if (kIsWeb) return false;

    try {
      final result = await _platform.invokeMethod(
        'requestBatteryOptimizationExemption',
      );
      return result == true;
    } catch (e) {
      _logger.logError(
        'permissions',
        'Failed to request battery optimization exemption',
        error: e,
      );
      return false;
    }
  }

  // Check if battery optimization is disabled
  Future<bool> isBatteryOptimizationDisabled() async {
    if (kIsWeb) return true;

    try {
      final result = await _platform.invokeMethod(
        'isBatteryOptimizationDisabled',
      );
      return result == true;
    } catch (e) {
      return false;
    }
  }

  // Check required permissions
  Future<Map<String, bool>> checkRequiredPermissions() async {
    if (kIsWeb) return {'all_granted': true};

    try {
      final result = await _platform.invokeMethod('checkClonePermissions');
      if (result is Map) {
        return Map<String, bool>.from(result);
      }
      return {'unknown': false};
    } catch (e) {
      _logger.logError('permissions', 'Failed to check permissions', error: e);
      return {'error': false};
    }
  }

  // Get device info for diagnostics
  Future<Map<String, dynamic>> getDeviceInfo() async {
    if (kIsWeb) return {'platform': 'web'};

    try {
      final result = await _platform.invokeMethod('getDeviceInfo');
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return {};
    } catch (e) {
      return {'error': e.toString()};
    }
  }

  // Collect diagnostics for a specific package
  Future<Map<String, dynamic>> collectDiagnostics(String packageName) async {
    if (kIsWeb) return {'platform': 'web'};

    try {
      final result = await _platform.invokeMethod('collectDiagnostics', {
        'packageName': packageName,
      });
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return {};
    } catch (e) {
      return {'error': e.toString()};
    }
  }

  // Export logs for feedback
  Future<String> exportLogsForFeedback() async {
    return _logger.exportLogsAsJson();
  }

  // Get recent launch logs
  List<Map<String, dynamic>> getRecentLogs({int count = 50}) {
    return _logger.getRecentLogs(count: count);
  }

  // Check if an app supports multiple instances
  Future<bool> supportsMultipleInstances(String packageName) async {
    final multiInstanceSupported = [
      'com.whatsapp',
      'com.instagram.android',
      'com.facebook.katana',
      'com.twitter.android',
      'com.zhiliaoapp.musically',
      'org.telegram.messenger',
      'com.linkedin.android',
      'com.snapchat.android',
      'com.viber.voip',
      'com.skype.raider',
      'com.zerodha.kite3',
      'com.upstox.pro',
      'com.angelbroking.smartapi',
    ];

    return multiInstanceSupported.contains(packageName);
  }

  // Create app shortcut for cloned app
  Future<bool> createCloneShortcut(
    String packageName,
    String appName,
    String cloneId,
  ) async {
    if (kIsWeb) return false;

    try {
      final result = await _platform.invokeMethod('createAppShortcut', {
        'packageName': packageName,
        'appName': appName,
        'cloneId': cloneId,
      });

      return result == true;
    } catch (e) {
      _logger.logError(
        'shortcut',
        'Failed to create shortcut',
        error: e,
        data: {'packageName': packageName, 'cloneId': cloneId},
      );
      return false;
    }
  }
}
