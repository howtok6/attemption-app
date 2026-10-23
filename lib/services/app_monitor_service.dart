import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class AppMonitorService {
  static const platform = MethodChannel('com.gabriel.attemption/permissions');

  Future<bool> checkUsagePermission() => _invokeBool('checkUsagePermission');

  Future<void> requestUsagePermission() => _invokeBool('requestUsagePermission');

  Future<bool> checkOverlayPermission() => _invokeBool('checkOverlayPermission');

  Future<void> requestOverlayPermission() => _invokeBool('requestOverlayPermission');

  /// Devuelve `true` si el servicio nativo se inició.
  Future<bool> startService() => _invokeBool('startMonitorService');

  /// Llama al canal nativo y devuelve `false` ante cualquier error.
  /// `MissingPluginException` (iOS, web, escritorio) no es un `PlatformException`,
  /// por eso se capturan ambos.
  Future<bool> _invokeBool(String method) async {
    try {
      return await platform.invokeMethod<bool>(method) ?? false;
    } on PlatformException catch (e) {
      debugPrint('Attemption: $method falló: ${e.code} ${e.message}');
      return false;
    } on MissingPluginException {
      debugPrint('Attemption: $method no está disponible en esta plataforma');
      return false;
    }
  }
}
