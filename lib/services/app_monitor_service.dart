import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/app_usage_state.dart';

enum StopResult { stopped, locked, failed }

/// Puente con el código nativo de Android (MethodChannel + EventChannel).
class AppMonitorService {
  static const platform = MethodChannel('com.gabriel.attemption/permissions');
  static const statusChannel = EventChannel('com.gabriel.attemption/status');

  /// Estado en vivo del servicio: se emite al suscribirse y en cada revisión (cada 2 s).
  Stream<AppUsageModel> statusStream() =>
      statusChannel.receiveBroadcastStream().map((event) => AppUsageModel.fromMap(event as Map<Object?, Object?>));

  Future<AppUsageModel?> getStatus() async {
    final map = await _invoke<Map<Object?, Object?>>('getStatus');
    return map == null ? null : AppUsageModel.fromMap(map);
  }

  Future<bool> checkUsagePermission() => _invokeBool('checkUsagePermission');
  Future<void> requestUsagePermission() => _invokeBool('requestUsagePermission');

  Future<bool> checkOverlayPermission() => _invokeBool('checkOverlayPermission');
  Future<void> requestOverlayPermission() => _invokeBool('requestOverlayPermission');

  Future<bool> checkNotificationPermission() => _invokeBool('checkNotificationPermission');
  Future<void> requestNotificationPermission() => _invokeBool('requestNotificationPermission');

  Future<bool> checkBatteryOptimization() => _invokeBool('checkBatteryOptimization');
  Future<void> requestBatteryExemption() => _invokeBool('requestBatteryExemption');

  Future<String> getManufacturer() async => await _invoke<String>('getManufacturer') ?? '';
  Future<void> openBatteryGuide() => _invokeBool('openBatteryGuide');

  /// Pantalla "Inicio de aplicaciones" de Honor/Huawei, si el teléfono la tiene.
  Future<bool> hasAppLaunchSettings() => _invokeBool('hasAppLaunchSettings');
  Future<void> openAppLaunchSettings() => _invokeBool('openAppLaunchSettings');

  /// Devuelve `true` si el servicio nativo se inició.
  Future<bool> startService() => _invokeBool('startMonitorService');

  Future<StopResult> stopService() async {
    try {
      await platform.invokeMethod<bool>('stopMonitorService');
      return StopResult.stopped;
    } on PlatformException catch (e) {
      return e.code == 'LOCKED' ? StopResult.locked : StopResult.failed;
    } on MissingPluginException {
      return StopResult.failed;
    }
  }

  Future<bool> _invokeBool(String method) async => await _invoke<bool>(method) ?? false;

  /// Llama al canal nativo y devuelve `null` ante cualquier error.
  /// `MissingPluginException` (iOS, web, escritorio) no es un `PlatformException`,
  /// por eso se capturan ambos.
  Future<T?> _invoke<T>(String method) async {
    try {
      return await platform.invokeMethod<T>(method);
    } on PlatformException catch (e) {
      debugPrint('Attemption: $method falló: ${e.code} ${e.message}');
      return null;
    } on MissingPluginException {
      debugPrint('Attemption: $method no está disponible en esta plataforma');
      return null;
    }
  }
}
