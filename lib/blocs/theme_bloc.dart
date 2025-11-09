import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter/material.dart';
import '../services/settings_service.dart';

// Theme Events
abstract class ThemeEvent {}

class ThemeChanged extends ThemeEvent {
  final bool isDarkMode;
  ThemeChanged(this.isDarkMode);
}

class ThemeInitialized extends ThemeEvent {}

// Theme States
abstract class ThemeState {
  final ThemeData themeData;
  final bool isDarkMode;

  const ThemeState({required this.themeData, required this.isDarkMode});
}

class ThemeLoadingState extends ThemeState {
  const ThemeLoadingState({required super.themeData, required super.isDarkMode});
}

class LightThemeState extends ThemeState {
  const LightThemeState({required super.themeData, required super.isDarkMode});
}

class DarkThemeState extends ThemeState {
  const DarkThemeState({required super.themeData, required super.isDarkMode});
}

// Theme BLoC
class ThemeBloc extends Bloc<ThemeEvent, ThemeState> {
  final SettingsService _settingsService = SettingsService();

  ThemeBloc()
      : super(ThemeLoadingState(themeData: _lightTheme, isDarkMode: false)) {
    on<ThemeInitialized>(_onThemeInitialized);
    on<ThemeChanged>(_onThemeChanged);
  }

  static final ThemeData _lightTheme = ThemeData(
    brightness: Brightness.light,
    primarySwatch: Colors.blue,
    scaffoldBackgroundColor: Colors.grey[50],
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.blue,
      foregroundColor: Colors.white,
      elevation: 0,
    ),
    cardTheme: CardThemeData(
      color: Colors.white,
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) {
          return Colors.white;
        }
        return Colors.grey[300];
      }),
      trackColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) {
          return Colors.blue;
        }
        return Colors.grey[300];
      }),
    ),
    colorScheme: ColorScheme.fromSeed(
      seedColor: Colors.blue,
      brightness: Brightness.light,
    ),
    useMaterial3: true,
  );

  static final ThemeData _darkTheme = ThemeData(
    brightness: Brightness.dark,
    primarySwatch: Colors.blue,
    scaffoldBackgroundColor: const Color(0xFF0D1117),
    appBarTheme: const AppBarTheme(
      backgroundColor: Color(0xFF161B22),
      foregroundColor: Colors.white,
      elevation: 0,
    ),
    cardTheme: CardThemeData(
      color: const Color(0xFF161B22),
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) {
          return Colors.white;
        }
        return Colors.grey[600];
      }),
      trackColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.selected)) {
          return Colors.blueAccent;
        }
        return Colors.grey[700];
      }),
    ),
    colorScheme: ColorScheme.fromSeed(
      seedColor: Colors.blue,
      brightness: Brightness.dark,
    ),
    useMaterial3: true,
  );

  void _onThemeInitialized(
    ThemeInitialized event,
    Emitter<ThemeState> emit,
  ) async {
    try {
      final bool isDarkMode = await _settingsService.getDarkMode();
      if (isDarkMode) {
        emit(DarkThemeState(themeData: _darkTheme, isDarkMode: true));
      } else {
        emit(LightThemeState(themeData: _lightTheme, isDarkMode: false));
      }
    } catch (e) {
      // Fallback to light theme if there's an error loading settings
      emit(LightThemeState(themeData: _lightTheme, isDarkMode: false));
    }
  }

  void _onThemeChanged(ThemeChanged event, Emitter<ThemeState> emit) async {
    await _settingsService.setDarkMode(event.isDarkMode);
    if (event.isDarkMode) {
      emit(DarkThemeState(themeData: _darkTheme, isDarkMode: true));
    } else {
      emit(LightThemeState(themeData: _lightTheme, isDarkMode: false));
    }
  }
}
