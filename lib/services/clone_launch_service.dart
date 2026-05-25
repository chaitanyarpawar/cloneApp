import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:device_info_plus/device_info_plus.dart';
import '../models/cloned_app.dart';
import '../models/launch_result.dart';
import 'clone_logger.dart';

/// Service responsible for launching cloned app instances with proper isolation
/// and error handling. Implements timeout mechanisms and fallback strategies.
class CloneLaunchService {
  static const MethodChannel _platform = MethodChannel(
    'com.cloneapp.multiaccount/app_launcher',
  );

  static const Duration _launchTimeout = Duration(seconds: 5);

  final CloneLogger _logger = CloneLogger();

  /// Launch a cloned app with comprehensive error handling and timeout
  Future<LaunchResult> launchCloneWithTimeout(
    ClonedApp clonedApp, {
    Duration? timeout,
  }) async {
    final effectiveTimeout = timeout ?? _launchTimeout;
    final cloneId = _generateCloneId(clonedApp);
    final startTime = DateTime.now();

    _logger.log(
      'launchClone',
      'Starting launch for ${clonedApp.packageName}',
      cloneId: cloneId,
      data: {
        'appName': clonedApp.appName,
        'timeout': effectiveTimeout.inSeconds,
      },
    );

    try {
      // Race between launch and timeout
      final result = await Future.any<LaunchResult>([
        _performLaunch(clonedApp, cloneId),
        _launchTimeoutFuture(effectiveTimeout, cloneId),
      ]);

      final elapsed = DateTime.now().difference(startTime);
      _logger.log(
        'launchClone',
        'Launch completed: ${result.success ? 'SUCCESS' : 'FAILED'}',
        cloneId: cloneId,
        data: {
          'elapsedMs': elapsed.inMilliseconds,
          'method': result.launchMethod,
        },
      );

      return result;
    } catch (e, stackTrace) {
      final elapsed = DateTime.now().difference(startTime);
      _logger.logError(
        'launchClone',
        'Launch failed with exception',
        error: e,
        stackTrace: stackTrace,
        cloneId: cloneId,
        data: {'elapsedMs': elapsed.inMilliseconds},
      );

      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'exception',
        diagnostics: await _collectDiagnostics(clonedApp.packageName, e),
      );
    }
  }

  /// Perform the actual launch with multiple fallback strategies
  Future<LaunchResult> _performLaunch(
    ClonedApp clonedApp,
    String cloneId,
  ) async {
    final packageName = clonedApp.packageName;

    _logger.log(
      'launchStrategy',
      '========== DART LAUNCH START ==========',
      cloneId: cloneId,
      data: {'packageName': packageName},
    );

    // Strategy 1: Direct multi-instance launch (fastest)
    _logger.log(
      'launchStrategy',
      '[Strategy 1/4] Trying direct multi-instance',
      cloneId: cloneId,
      data: {'packageName': packageName, 'cloneId': cloneId},
    );
    var result = await _tryDirectMultiInstance(packageName, cloneId);
    if (result.success) {
      _logger.log('launchStrategy', '[Strategy 1/4] SUCCESS', cloneId: cloneId);
      return result;
    }
    _logger.log(
      'launchStrategy',
      '[Strategy 1/4] FAILED: ${result.error}',
      cloneId: cloneId,
    );

    // Strategy 2: Virtual container launch
    _logger.log(
      'launchStrategy',
      '[Strategy 2/4] Trying virtual container',
      cloneId: cloneId,
    );
    result = await _tryVirtualContainer(packageName, cloneId);
    if (result.success) {
      _logger.log('launchStrategy', '[Strategy 2/4] SUCCESS', cloneId: cloneId);
      return result;
    }
    _logger.log(
      'launchStrategy',
      '[Strategy 2/4] FAILED: ${result.error}',
      cloneId: cloneId,
    );

    // Strategy 3: Clone activity proxy
    _logger.log(
      'launchStrategy',
      'Trying clone activity proxy',
      cloneId: cloneId,
    );
    result = await _tryCloneActivityProxy(packageName, cloneId);
    if (result.success) {
      _logger.log('launchStrategy', '[Strategy 3/4] SUCCESS', cloneId: cloneId);
      return result;
    }
    _logger.log(
      'launchStrategy',
      '[Strategy 3/4] FAILED: ${result.error}',
      cloneId: cloneId,
    );

    // Strategy 4: Force restart approach (slowest but most compatible)
    _logger.log(
      'launchStrategy',
      '[Strategy 4/4] Trying force restart',
      cloneId: cloneId,
    );
    result = await _tryForceRestart(packageName, cloneId);
    if (result.success) {
      _logger.log('launchStrategy', '[Strategy 4/4] SUCCESS', cloneId: cloneId);
      return result;
    }
    _logger.log(
      'launchStrategy',
      '[Strategy 4/4] FAILED: ${result.error}',
      cloneId: cloneId,
    );

    // All strategies failed
    _logger.log(
      'launchStrategy',
      '========== ALL 4 STRATEGIES FAILED ==========',
      cloneId: cloneId,
    );
    return LaunchResult(
      success: false,
      error: 'All launch strategies failed',
      cloneId: cloneId,
      launchMethod: 'all_failed',
      diagnostics: await _collectDiagnostics(packageName, null),
    );
  }

  Future<LaunchResult> _tryDirectMultiInstance(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform.invokeMethod('launchDirectMultiInstance', {
        'packageName': packageName,
        'cloneId': cloneId,
        'timestamp': DateTime.now().millisecondsSinceEpoch,
      });

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'direct_multi_instance',
      );
    } catch (e) {
      _logger.logError(
        'launchStrategy',
        'Direct multi-instance failed',
        error: e,
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

  Future<LaunchResult> _tryVirtualContainer(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform.invokeMethod(
        'launchAppWithVirtualContainer',
        {'packageName': packageName, 'cloneId': cloneId},
      );

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'virtual_container',
      );
    } catch (e) {
      _logger.logError(
        'launchStrategy',
        'Virtual container failed',
        error: e,
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

  Future<LaunchResult> _tryCloneActivityProxy(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform.invokeMethod('launchViaCloneActivity', {
        'packageName': packageName,
        'cloneId': cloneId,
      });

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'clone_activity_proxy',
      );
    } catch (e) {
      _logger.logError(
        'launchStrategy',
        'Clone activity proxy failed',
        error: e,
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

  Future<LaunchResult> _tryForceRestart(
    String packageName,
    String cloneId,
  ) async {
    try {
      final result = await _platform.invokeMethod('launchWithForceRestart', {
        'packageName': packageName,
        'cloneId': cloneId,
      });

      return LaunchResult(
        success: result == true,
        cloneId: cloneId,
        launchMethod: 'force_restart',
      );
    } catch (e) {
      _logger.logError(
        'launchStrategy',
        'Force restart failed',
        error: e,
        cloneId: cloneId,
      );
      return LaunchResult(
        success: false,
        error: e.toString(),
        cloneId: cloneId,
        launchMethod: 'force_restart',
      );
    }
  }

  Future<LaunchResult> _launchTimeoutFuture(
    Duration timeout,
    String cloneId,
  ) async {
    await Future.delayed(timeout);
    _logger.log('launchClone', 'Launch timeout reached', cloneId: cloneId);
    return LaunchResult(
      success: false,
      error: 'Launch timeout after ${timeout.inSeconds}s',
      cloneId: cloneId,
      launchMethod: 'timeout',
    );
  }

  String _generateCloneId(ClonedApp app) {
    return '${app.id}_${DateTime.now().millisecondsSinceEpoch}';
  }

  /// Collect diagnostic information for debugging
  Future<Map<String, dynamic>> _collectDiagnostics(
    String packageName,
    Object? error,
  ) async {
    final diagnostics = <String, dynamic>{
      'packageName': packageName,
      'timestamp': DateTime.now().toIso8601String(),
    };

    try {
      final deviceInfo = DeviceInfoPlugin();
      if (!kIsWeb) {
        final androidInfo = await deviceInfo.androidInfo;
        diagnostics['device'] = {
          'model': androidInfo.model,
          'manufacturer': androidInfo.manufacturer,
          'androidVersion': androidInfo.version.release,
          'sdkInt': androidInfo.version.sdkInt,
          'brand': androidInfo.brand,
        };
      }
    } catch (e) {
      diagnostics['deviceInfoError'] = e.toString();
    }

    if (error != null) {
      diagnostics['error'] = error.toString();
    }

    // Check if app is installed
    try {
      final isInstalled = await _platform.invokeMethod('isAppInstalled', {
        'packageName': packageName,
      });
      diagnostics['isAppInstalled'] = isInstalled;
    } catch (e) {
      diagnostics['installCheckError'] = e.toString();
    }

    // Check if app is running
    try {
      final isRunning = await _platform.invokeMethod('isAppRunning', {
        'packageName': packageName,
      });
      diagnostics['isAppRunning'] = isRunning;
    } catch (e) {
      diagnostics['runningCheckError'] = e.toString();
    }

    return diagnostics;
  }

  /// Request battery optimization exemption for better background launch
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

  /// Check if all required permissions are granted
  Future<Map<String, bool>> checkRequiredPermissions() async {
    if (kIsWeb) {
      return {'all_granted': true};
    }

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

  /// Get the recent launch logs for debugging
  Future<List<Map<String, dynamic>>> getRecentLaunchLogs() async {
    return _logger.getRecentLogs();
  }

  /// Export logs for user feedback
  Future<String> exportLogsAsJson() async {
    return _logger.exportLogsAsJson();
  }
}
