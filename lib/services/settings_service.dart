import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:local_auth/local_auth.dart';

class SettingsService {
  static const String _darkModeKey = 'dark_mode';
  static const String _biometricLockKey = 'biometric_lock';
  static const String _notificationsKey = 'notifications';
  static const String _incognitoModeKey = 'incognito_mode';

  static final SettingsService _instance = SettingsService._internal();
  factory SettingsService() => _instance;
  SettingsService._internal();

  LocalAuthentication? _localAuth;
  late SharedPreferences _prefs;

  // Initialize the service
  Future<void> initialize() async {
    try {
      if (!kIsWeb) {
        _localAuth = LocalAuthentication();
      }
      _prefs = await SharedPreferences.getInstance();
    } catch (e) {
      debugPrint('Failed to initialize SettingsService: $e');
      // Don't rethrow - let the app continue with fallback behavior
    }
  }

  // Safe method to get preferences with fallback
  Future<SharedPreferences?> _getSafePrefs() async {
    try {
      return _prefs;
    } catch (e) {
      try {
        _prefs = await SharedPreferences.getInstance();
        return _prefs;
      } catch (e2) {
        debugPrint('Failed to get SharedPreferences: $e2');
        return null;
      }
    }
  }

  // Dark Mode Settings
  Future<bool> getDarkMode() async {
    try {
      final prefs = await _getSafePrefs();
      return prefs?.getBool(_darkModeKey) ?? false;
    } catch (e) {
      debugPrint('Error getting dark mode: $e');
      return false;
    }
  }

  Future<void> setDarkMode(bool isDarkMode) async {
    try {
      final prefs = await _getSafePrefs();
      await prefs?.setBool(_darkModeKey, isDarkMode);
    } catch (e) {
      debugPrint('Error setting dark mode: $e');
    }
  }

  // Biometric Lock Settings
  Future<bool> getBiometricLock() async {
    try {
      final prefs = await _getSafePrefs();
      return prefs?.getBool(_biometricLockKey) ?? false;
    } catch (e) {
      debugPrint('Error getting biometric lock: $e');
      return false;
    }
  }

  Future<void> setBiometricLock(bool isEnabled) async {
    try {
      final prefs = await _getSafePrefs();
      await prefs?.setBool(_biometricLockKey, isEnabled);
    } catch (e) {
      debugPrint('Error setting biometric lock: $e');
    }
  }

  // Check if biometric authentication is available
  Future<bool> isBiometricAvailable() async {
    if (kIsWeb || _localAuth == null) return false;

    try {
      final bool isAvailable = await _localAuth!.canCheckBiometrics;
      final bool isDeviceSupported = await _localAuth!.isDeviceSupported();
      return isAvailable && isDeviceSupported;
    } catch (e) {
      return false;
    }
  }

  // Get available biometric types
  Future<List<BiometricType>> getAvailableBiometrics() async {
    if (kIsWeb || _localAuth == null) return [];

    try {
      return await _localAuth!.getAvailableBiometrics();
    } catch (e) {
      return [];
    }
  }

  // Authenticate using biometrics
  Future<bool> authenticateWithBiometrics({
    String reason = 'Please authenticate to access your cloned apps',
  }) async {
    if (kIsWeb || _localAuth == null) {
      return true; // Skip authentication on web for demo
    }

    try {
      final bool didAuthenticate = await _localAuth!.authenticate(
        localizedReason: reason,
        options: const AuthenticationOptions(
          biometricOnly: false,
          stickyAuth: true,
        ),
      );
      return didAuthenticate;
    } catch (e) {
      return false;
    }
  }

  // Notifications Settings
  Future<bool> getNotifications() async {
    try {
      final prefs = await _getSafePrefs();
      return prefs?.getBool(_notificationsKey) ?? true;
    } catch (e) {
      debugPrint('Error getting notifications: $e');
      return true;
    }
  }

  Future<void> setNotifications(bool isEnabled) async {
    try {
      final prefs = await _getSafePrefs();
      await prefs?.setBool(_notificationsKey, isEnabled);
    } catch (e) {
      debugPrint('Error setting notifications: $e');
    }
  }

  // Incognito Mode Settings
  Future<bool> getIncognitoMode() async {
    try {
      final prefs = await _getSafePrefs();
      return prefs?.getBool(_incognitoModeKey) ?? false;
    } catch (e) {
      debugPrint('Error getting incognito mode: $e');
      return false;
    }
  }

  Future<void> setIncognitoMode(bool isEnabled) async {
    try {
      final prefs = await _getSafePrefs();
      await prefs?.setBool(_incognitoModeKey, isEnabled);
    } catch (e) {
      debugPrint('Error setting incognito mode: $e');
    }
  }

  // Check if biometric lock should be enforced before app access
  Future<bool> shouldRequireBiometricAuth() async {
    final bool isEnabled = await getBiometricLock();
    final bool isAvailable = await isBiometricAvailable();
    return isEnabled && isAvailable;
  }
}
