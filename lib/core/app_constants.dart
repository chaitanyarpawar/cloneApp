class AppConstants {
  static const String appName = 'CloneApp';
  static const String packageName = 'com.cloneapp.multiaccount';
  static const String appTagline = 'Use multiple accounts. Simplified.';

  // SharedPreferences Keys
  static const String clonedAppsKey = 'cloned_apps';
  static const String themeKey = 'app_theme';
  static const String securityKey = 'security_enabled';
  static const String firstLaunchKey = 'first_launch';

  // Security
  static const String pinKey = 'user_pin';
  static const String biometricKey = 'biometric_enabled';

  // AdMob Production IDs
  static const String bannerAdUnitId = 'ca-app-pub-3363212064618859/3697514530';
  static const String interstitialAdUnitId =
      'ca-app-pub-3363212064618859/3697514530'; // Use same for now, create more units if needed
  static const String rewardedAdUnitId =
      'ca-app-pub-3363212064618859/3697514530'; // Use same for now, create more units if needed

  // Animation Durations
  static const Duration splashDuration = Duration(
    milliseconds: 1500,
  ); // Reduced from 3 seconds
  static const Duration cardAnimationDuration = Duration(milliseconds: 300);
  static const Duration pageTransitionDuration = Duration(milliseconds: 250);
}
