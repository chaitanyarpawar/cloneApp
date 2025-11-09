import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
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
      rethrow;
    }
  }

  // Dark Mode Settings
  Future<bool> getDarkMode() async {
    return _prefs.getBool(_darkModeKey) ?? false;
  }

  Future<void> setDarkMode(bool isDarkMode) async {
    await _prefs.setBool(_darkModeKey, isDarkMode);
  }

  // Biometric Lock Settings
  Future<bool> getBiometricLock() async {
    return _prefs.getBool(_biometricLockKey) ?? false;
  }

  Future<void> setBiometricLock(bool isEnabled) async {
    await _prefs.setBool(_biometricLockKey, isEnabled);
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
    if (kIsWeb || _localAuth == null) return true; // Skip authentication on web for demo

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
    return _prefs.getBool(_notificationsKey) ?? true;
  }

  Future<void> setNotifications(bool isEnabled) async {
    await _prefs.setBool(_notificationsKey, isEnabled);
  }

  // Incognito Mode Settings
  Future<bool> getIncognitoMode() async {
    return _prefs.getBool(_incognitoModeKey) ?? false;
  }

  Future<void> setIncognitoMode(bool isEnabled) async {
    await _prefs.setBool(_incognitoModeKey, isEnabled);
  }

  // Check if biometric lock should be enforced before app access
  Future<bool> shouldRequireBiometricAuth() async {
    final bool isEnabled = await getBiometricLock();
    final bool isAvailable = await isBiometricAvailable();
    return isEnabled && isAvailable;
  }
}
