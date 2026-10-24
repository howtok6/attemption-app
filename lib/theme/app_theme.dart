import 'package:flutter/material.dart';

/// Paleta oscura de Attemption. Todos los colores de la app salen de aquí.
class AppColors {
  AppColors._();

  static const background = Color(0xFF121017); // casi negro, con un toque morado
  static const surface = Color(0xFF1C1824); // tarjetas y diálogos
  static const appBar = Color(0xFF241538); // morado muy oscuro
  static const primary = Color(0xFF8E6BD8); // morado de acento (escudo, botones)
  static const onPrimary = Color(0xFFFFFFFF);
  static const textPrimary = Color(0xFFECE6F2);
  static const textSecondary = Color(0xFFA59DB3);

  // Estados, en tonos apagados para no encandilar sobre fondo oscuro
  static const success = Color(0xFF4E9F5A);
  static const warning = Color(0xFFD08A2E);
  static const neutral = Color(0xFF6F7F93);
  static const danger = Color(0xFFD0564F);

  /// Fondo de una tarjeta teñida con el color de su estado.
  static Color tint(Color color) => Color.alphaBlend(color.withValues(alpha: 0.14), surface);
}

ThemeData buildAppTheme() {
  final scheme = ColorScheme.fromSeed(seedColor: AppColors.primary, brightness: Brightness.dark).copyWith(
    primary: AppColors.primary,
    onPrimary: AppColors.onPrimary,
    surface: AppColors.surface,
    onSurface: AppColors.textPrimary,
    onSurfaceVariant: AppColors.textSecondary,
    error: AppColors.danger,
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: AppColors.background,
    appBarTheme: const AppBarTheme(backgroundColor: AppColors.appBar, foregroundColor: AppColors.textPrimary),
    dialogTheme: const DialogThemeData(backgroundColor: AppColors.surface),
    snackBarTheme: const SnackBarThemeData(
      backgroundColor: AppColors.textPrimary,
      contentTextStyle: TextStyle(color: AppColors.background),
    ),
  );
}
