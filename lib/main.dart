import 'package:flutter/material.dart';

import 'features/flash_alerts/flash_alerts_api.dart';
import 'features/flash_alerts/flash_alerts_settings_screen.dart';

void main() {
  runApp(FlashAlertsApp(api: FlashAlertsApi()));
}

class FlashAlertsApp extends StatelessWidget {
  const FlashAlertsApp({super.key, required this.api});
  final FlashAlertsApi api;

  @override
  Widget build(BuildContext context) {
    const primaryColor = Color(0xFFFFB300); // Electric Amber / Flash Torch
    const primaryContainer = Color(0xFF2A2109);
    const secondaryColor = Color(0xFF4DD0E1); // Vibrant Cyan

    final darkTheme = ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xFF0C0E12),
      colorScheme: ColorScheme.fromSeed(
        seedColor: primaryColor,
        brightness: Brightness.dark,
        surface: const Color(0xFF14171E),
        primary: primaryColor,
        primaryContainer: primaryContainer,
        secondary: secondaryColor,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        scrolledUnderElevation: 0,
      ),
      cardTheme: CardThemeData(
        color: const Color(0xFF14171E),
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: Color(0xFF232834), width: 1),
        ),
        margin: EdgeInsets.zero,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const Color(0xFF0C0E12);
          }
          return Colors.grey.shade400;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return primaryColor;
          }
          return const Color(0xFF232834);
        }),
      ),
    );

    final lightTheme = ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      scaffoldBackgroundColor: const Color(0xFFF6F8FA),
      colorScheme: ColorScheme.fromSeed(
        seedColor: primaryColor,
        brightness: Brightness.light,
        surface: Colors.white,
        primary: const Color(0xFFD98E00),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        scrolledUnderElevation: 0,
      ),
      cardTheme: CardThemeData(
        color: Colors.white,
        elevation: 1,
        shadowColor: Colors.black.withValues(alpha: 0.04),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: Color(0xFFE2E7EE), width: 1),
        ),
        margin: EdgeInsets.zero,
      ),
    );

    return MaterialApp(
      title: 'Blinkr',
      debugShowCheckedModeBanner: false,
      theme: lightTheme,
      darkTheme: darkTheme,
      themeMode: ThemeMode.system,
      home: FlashAlertsSettingsScreen(api: api),
    );
  }
}
