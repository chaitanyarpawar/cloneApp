import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:local_auth/local_auth.dart';
import '../core/app_theme.dart';
import '../core/app_constants.dart';
import '../services/settings_service.dart';
import '../blocs/theme_bloc.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _isDarkMode = false;
  bool _biometricEnabled = false;
  bool _notificationsEnabled = true;
  bool _incognitoMode = false;
  bool _biometricAvailable = false;
  List<BiometricType> _availableBiometrics = [];

  final SettingsService _settingsService = SettingsService();

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Update dark mode state based on current theme
    final themeState = context.read<ThemeBloc>().state;
    _isDarkMode = themeState.isDarkMode;
  }

  void _loadSettings() async {
    // Load all settings
    final darkMode = await _settingsService.getDarkMode();
    final biometric = await _settingsService.getBiometricLock();
    final notifications = await _settingsService.getNotifications();
    final incognito = await _settingsService.getIncognitoMode();
    final biometricAvailable = await _settingsService.isBiometricAvailable();
    final availableBiometrics = await _settingsService.getAvailableBiometrics();

    setState(() {
      _isDarkMode = darkMode;
      _biometricEnabled = biometric;
      _notificationsEnabled = notifications;
      _incognitoMode = incognito;
      _biometricAvailable = biometricAvailable;
      _availableBiometrics = availableBiometrics;
    });
  }

  String _getBiometricTypeString() {
    if (_availableBiometrics.isEmpty) return 'biometrics';

    if (_availableBiometrics.contains(BiometricType.fingerprint)) {
      return 'fingerprint';
    } else if (_availableBiometrics.contains(BiometricType.face)) {
      return 'face recognition';
    } else if (_availableBiometrics.contains(BiometricType.iris)) {
      return 'iris scan';
    } else {
      return 'biometric authentication';
    }
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: AppTheme.primaryCyan,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Settings',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            fontFamily: 'GoogleSans',
          ),
        ),
        backgroundColor: Colors.transparent,
        elevation: 0,
        flexibleSpace: Container(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: isDark
                  ? [AppTheme.neonBlue, AppTheme.neonPurple]
                  : [AppTheme.primaryCyan, Colors.blue.shade600],
            ),
          ),
        ),
        foregroundColor: Colors.white,
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Theme Section
          _buildSectionHeader('Appearance'),
          _buildSettingCard(
            icon: Icons.palette_rounded,
            title: 'Dark Mode',
            subtitle: 'Toggle between light and dark themes',
            trailing: Switch(
              value: _isDarkMode,
              onChanged: (value) async {
                setState(() {
                  _isDarkMode = value;
                });

                // Update theme using BLoC
                context.read<ThemeBloc>().add(ThemeChanged(value));

                _showSnackBar(
                  value ? 'Dark mode enabled' : 'Light mode enabled',
                );
              },
              activeTrackColor: AppTheme.primaryCyan,
              thumbColor: WidgetStateProperty.resolveWith((states) {
                if (states.contains(WidgetState.selected)) {
                  return Colors.white;
                }
                return Colors.grey;
              }),
            ),
          ),

          const SizedBox(height: 24),

          // Security Section
          _buildSectionHeader('Security'),
          _buildSettingCard(
            icon: Icons.fingerprint_rounded,
            title: 'Biometric Lock',
            subtitle: _biometricAvailable
                ? 'Secure cloned apps with ${_getBiometricTypeString()}'
                : 'Biometric authentication not available',
            trailing: Switch(
              value: _biometricEnabled && _biometricAvailable,
              onChanged: _biometricAvailable
                  ? (value) async {
                      if (value) {
                        // Test biometric authentication before enabling
                        final authenticated = await _settingsService
                            .authenticateWithBiometrics(
                              reason:
                                  'Authenticate to enable biometric lock for cloned apps',
                            );

                        if (authenticated) {
                          setState(() {
                            _biometricEnabled = true;
                          });
                          await _settingsService.setBiometricLock(true);
                          _showSnackBar('Biometric lock enabled successfully');
                        } else {
                          _showSnackBar(
                            'Authentication failed. Biometric lock not enabled.',
                          );
                        }
                      } else {
                        setState(() {
                          _biometricEnabled = false;
                        });
                        await _settingsService.setBiometricLock(false);
                        _showSnackBar('Biometric lock disabled');
                      }
                    }
                  : null,
              activeTrackColor: AppTheme.primaryCyan,
              thumbColor: WidgetStateProperty.resolveWith((states) {
                if (states.contains(WidgetState.selected)) {
                  return Colors.white;
                }
                return Colors.grey;
              }),
            ),
          ),

          _buildSettingCard(
            icon: Icons.visibility_off_rounded,
            title: 'Incognito Mode',
            subtitle: 'Hide cloned apps from launcher',
            trailing: Switch(
              value: _incognitoMode,
              onChanged: (value) async {
                setState(() {
                  _incognitoMode = value;
                });
                await _settingsService.setIncognitoMode(value);
                _showSnackBar(
                  value
                      ? 'Incognito mode enabled - apps will be hidden'
                      : 'Incognito mode disabled - apps will be visible',
                );
              },
              activeTrackColor: AppTheme.primaryCyan,
              thumbColor: WidgetStateProperty.resolveWith((states) {
                if (states.contains(WidgetState.selected)) {
                  return Colors.white;
                }
                return Colors.grey;
              }),
            ),
          ),

          const SizedBox(height: 24),

          // Notifications Section
          _buildSectionHeader('Notifications'),
          _buildSettingCard(
            icon: Icons.notifications_rounded,
            title: 'App Notifications',
            subtitle: 'Receive updates about cloned apps',
            trailing: Switch(
              value: _notificationsEnabled,
              onChanged: (value) async {
                setState(() {
                  _notificationsEnabled = value;
                });
                await _settingsService.setNotifications(value);
                _showSnackBar(
                  value ? 'Notifications enabled' : 'Notifications disabled',
                );
              },
              activeTrackColor: AppTheme.primaryCyan,
              thumbColor: WidgetStateProperty.resolveWith((states) {
                if (states.contains(WidgetState.selected)) {
                  return Colors.white;
                }
                return Colors.grey;
              }),
            ),
          ),

          const SizedBox(height: 24),

          // About Section
          _buildSectionHeader('About'),
          _buildSettingCard(
            icon: Icons.info_rounded,
            title: 'App Version',
            subtitle: '1.0.0 (Beta)',
            trailing: const Icon(Icons.arrow_forward_ios, size: 16),
            onTap: () {
              _showAboutDialog();
            },
          ),

          _buildSettingCard(
            icon: Icons.privacy_tip_rounded,
            title: 'Privacy Policy',
            subtitle: 'View our privacy policy',
            trailing: const Icon(Icons.arrow_forward_ios, size: 16),
            onTap: () {
              _showSnackBar('Privacy policy will be available soon');
            },
          ),

          _buildSettingCard(
            icon: Icons.help_rounded,
            title: 'Help & Support',
            subtitle: 'Get help using CloneApp',
            trailing: const Icon(Icons.arrow_forward_ios, size: 16),
            onTap: () {
              _showSnackBar('Help & Support will be available soon');
            },
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.bold,
          color: Theme.of(context).primaryColor,
          fontFamily: 'GoogleSans',
        ),
      ),
    );
  }

  Widget _buildSettingCard({
    required IconData icon,
    required String title,
    required String subtitle,
    Widget? trailing,
    VoidCallback? onTap,
  }) {
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: isDark
            ? LinearGradient(
                colors: [
                  AppTheme.cardDark,
                  AppTheme.cardDark.withValues(alpha: 0.8),
                ],
              )
            : null,
        color: isDark ? null : Colors.white,
        boxShadow: [
          BoxShadow(
            color: isDark
                ? AppTheme.neonBlue.withValues(alpha: 0.1)
                : Colors.grey.withValues(alpha: 0.2),
            blurRadius: 8,
            spreadRadius: 1,
          ),
        ],
      ),
      child: ListTile(
        leading: Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(8),
            gradient: LinearGradient(
              colors: [AppTheme.primaryCyan, AppTheme.neonPurple],
            ),
          ),
          child: Icon(icon, color: Colors.white, size: 20),
        ),
        title: Text(
          title,
          style: const TextStyle(
            fontWeight: FontWeight.w600,
            fontFamily: 'GoogleSans',
          ),
        ),
        subtitle: Text(
          subtitle,
          style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
        ),
        trailing: trailing,
        onTap: onTap,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
    );
  }

  void _showAboutDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(8),
                gradient: LinearGradient(
                  colors: [AppTheme.primaryCyan, AppTheme.neonPurple],
                ),
              ),
              child: const Icon(
                Icons.content_copy_rounded,
                color: Colors.white,
                size: 24,
              ),
            ),
            const SizedBox(width: 12),
            Text(
              AppConstants.appName,
              style: const TextStyle(
                fontFamily: 'GoogleSans',
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(AppConstants.appTagline),
            const SizedBox(height: 16),
            const Text('Version: 1.0.0 (Beta)'),
            const Text('Package: com.cloneapp.multiaccount'),
            const SizedBox(height: 16),
            const Text(
              'CloneApp allows you to use multiple accounts of the same app on one device.',
              style: TextStyle(fontSize: 12),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }
}
