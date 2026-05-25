import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mobile_ads/google_mobile_ads.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'core/app_constants.dart';
import 'screens/splash_screen.dart';
import 'services/settings_service.dart';
import 'services/clone_logger.dart';
import 'blocs/theme_bloc.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final logger = CloneLogger();
  logger.log('main', 'App starting');

  // Initialize services with error handling - non-blocking
  _initializeServicesAsync(logger);

  // Set system UI overlay style
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.transparent,
    ),
  );

  // Run app immediately - don't wait for ad SDK
  runApp(const MyApp());
}

/// Initialize services asynchronously to prevent blocking app startup.
/// Fix A: Move ad SDK init off UI thread
Future<void> _initializeServicesAsync(CloneLogger logger) async {
  // Initialize settings service (fast, required for app functionality)
  try {
    await SettingsService().initialize();
    logger.log('init', 'Settings service initialized');
  } catch (e) {
    logger.logError('init', 'Settings service failed to initialize', error: e);
  }

  // Initialize Google Mobile Ads SDK asynchronously with timeout
  // This runs in the background and doesn't block app entry
  if (!kIsWeb) {
    _initializeAdsWithTimeout(logger);
  }
}

/// Initialize ads with a timeout to prevent blocking.
/// If ads don't initialize within 3 seconds, we proceed anyway.
Future<void> _initializeAdsWithTimeout(CloneLogger logger) async {
  logger.logAdInitStart('app_startup');
  final startTime = DateTime.now();

  try {
    // Run ad init with timeout
    await MobileAds.instance.initialize().timeout(
      const Duration(seconds: 3),
      onTimeout: () {
        final elapsed = DateTime.now().difference(startTime);
        logger.logAdInitComplete('app_startup', false, elapsed);
        logger.logWarning(
          'init',
          'Ad SDK init timeout - proceeding without ads',
        );
        return InitializationStatus({});
      },
    );

    final elapsed = DateTime.now().difference(startTime);
    logger.logAdInitComplete('app_startup', true, elapsed);
    logger.log('init', 'Ad SDK initialized in ${elapsed.inMilliseconds}ms');
  } catch (e) {
    final elapsed = DateTime.now().difference(startTime);
    logger.logAdInitComplete('app_startup', false, elapsed);
    logger.logError('init', 'Ad SDK failed to initialize', error: e);
    // Don't rethrow - allow app to continue without ads
  }
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (context) => ThemeBloc()..add(ThemeInitialized()),
      child: BlocBuilder<ThemeBloc, ThemeState>(
        builder: (context, state) {
          return MaterialApp(
            title: AppConstants.appName,
            theme: state.themeData,
            home: const SplashScreen(),
            debugShowCheckedModeBanner: false,
          );
        },
      ),
    );
  }
}
