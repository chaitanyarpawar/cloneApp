import 'package:flutter/material.dart';

class AppTheme {
  // Futuristic accent colors
  static const Color primaryCyan = Color(0xFF00BCD4);
  static const Color darkGray = Color(0xFF212121);
  static const Color pureWhite = Color(0xFFFFFFFF);
  static const Color neonBlue = Color(0xFF00E5FF);
  static const Color neonPurple = Color(0xFF7C4DFF);
  static const Color cardDark = Color(0xFF2D2D2D);
  static const Color backgroundDark = Color(0xFF1A1A1A);

  // Light Theme
  static ThemeData get lightTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      colorScheme: ColorScheme.fromSeed(
        seedColor: primaryCyan,
        brightness: Brightness.light,
      ),
      appBarTheme: AppBarTheme(
        centerTitle: true,
        elevation: 0,
        backgroundColor: Colors.transparent,
        foregroundColor: darkGray,
        titleTextStyle: const TextStyle(
          fontFamily: 'GoogleSans',
          fontSize: 20,
          fontWeight: FontWeight.bold,
          color: darkGray,
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 8,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        shadowColor: primaryCyan.withValues(alpha: 0.3),
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: primaryCyan,
        foregroundColor: pureWhite,
        elevation: 12,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primaryCyan,
          foregroundColor: pureWhite,
          elevation: 8,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontFamily: 'GoogleSans',
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }

  // Dark Theme
  static ThemeData get darkTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: ColorScheme.fromSeed(
        seedColor: primaryCyan,
        brightness: Brightness.dark,
      ),
      scaffoldBackgroundColor: backgroundDark,
      appBarTheme: AppBarTheme(
        centerTitle: true,
        elevation: 0,
        backgroundColor: Colors.transparent,
        foregroundColor: pureWhite,
        titleTextStyle: const TextStyle(
          fontFamily: 'GoogleSans',
          fontSize: 20,
          fontWeight: FontWeight.bold,
          color: pureWhite,
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 12,
        color: cardDark,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        shadowColor: neonBlue.withValues(alpha: 0.3),
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: neonBlue,
        foregroundColor: backgroundDark,
        elevation: 16,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: neonBlue,
          foregroundColor: backgroundDark,
          elevation: 12,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontFamily: 'GoogleSans',
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }

  // Gradient Decorations
  static BoxDecoration get neonGradient {
    return BoxDecoration(
      gradient: LinearGradient(
        colors: [neonBlue, neonPurple],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ),
    );
  }

  static BoxDecoration get cardGradient {
    return BoxDecoration(
      gradient: LinearGradient(
        colors: [
          primaryCyan.withValues(alpha: 0.1),
          neonPurple.withValues(alpha: 0.1),
        ],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ),
      borderRadius: BorderRadius.circular(16),
    );
  }
}
