import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mobile_ads/google_mobile_ads.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'core/app_constants.dart';
import 'screens/splash_screen.dart';
import 'services/settings_service.dart';
import 'blocs/theme_bloc.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize services
  await SettingsService().initialize();

  // Initialize Google Mobile Ads SDK
  if (!kIsWeb) {
    await MobileAds.instance.initialize();
  }

  // Set system UI overlay style
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.transparent,
    ),
  );

  runApp(const MyApp());
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
