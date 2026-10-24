import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/app_usage_state.dart';
import '../services/app_monitor_service.dart';

class HomeViewModel extends ChangeNotifier {
  HomeViewModel({AppMonitorService? service}) : _monitorService = service ?? AppMonitorService() {
    _init();
  }

  final AppMonitorService _monitorService;
  StreamSubscription<AppUsageModel>? _statusSubscription;
  bool _disposed = false;

  bool _hasUsagePermission = false;
  bool _hasOverlayPermission = false;
  bool _hasNotificationPermission = false;
  bool _isIgnoringBatteryOptimizations = false;
  String _manufacturer = '';
  bool _hasAppLaunchSettings = false;
  AppUsageModel _usage = AppUsageModel.empty;

  bool get isAndroid => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
  bool get hasUsagePermission => _hasUsagePermission;
  bool get hasOverlayPermission => _hasOverlayPermission;
  bool get hasNotificationPermission => _hasNotificationPermission;
  bool get isIgnoringBatteryOptimizations => _isIgnoringBatteryOptimizations;
  bool get isReady => _hasUsagePermission && _hasOverlayPermission;
  bool get hasAppLaunchSettings => _hasAppLaunchSettings;
  bool get isServiceRunning => _usage.running;
  AppUsageModel get usage => _usage;

  /// Marca del teléfono con la primera letra en mayúscula ("Xiaomi"), o vacío si no se conoce.
  String get manufacturer => _manufacturer.isEmpty ? '' : _manufacturer[0].toUpperCase() + _manufacturer.substring(1);

  Future<void> _init() async {
    if (!isAndroid) return;
    _statusSubscription = _monitorService.statusStream().listen((status) {
      _usage = status;
      _notify();
    }, onError: (Object e) => debugPrint('Attemption: error en el estado del servicio: $e'));
    _manufacturer = await _monitorService.getManufacturer();
    _hasAppLaunchSettings = await _monitorService.hasAppLaunchSettings();
    await refresh();
  }

  /// Revisa permisos y, si la protección estaba activada pero el servicio no corre
  /// (por ejemplo tras "Forzar detención"), la vuelve a iniciar.
  Future<void> refresh() async {
    if (!isAndroid) return;
    await checkPermissions();
    final status = await _monitorService.getStatus();
    if (status != null) _usage = status;
    if (_usage.protectionEnabled && !_usage.running && isReady) {
      await startService();
    }
    _notify();
  }

  Future<void> checkPermissions() async {
    _hasUsagePermission = await _monitorService.checkUsagePermission();
    _hasOverlayPermission = await _monitorService.checkOverlayPermission();
    _hasNotificationPermission = await _monitorService.checkNotificationPermission();
    _isIgnoringBatteryOptimizations = await _monitorService.checkBatteryOptimization();
    _notify();
  }

  // Los permisos se vuelven a revisar cuando la app regresa a primer plano (HomeView).
  Future<void> requestUsagePermission() => _monitorService.requestUsagePermission();
  Future<void> requestOverlayPermission() => _monitorService.requestOverlayPermission();
  Future<void> requestNotificationPermission() => _monitorService.requestNotificationPermission();
  Future<void> requestBatteryExemption() => _monitorService.requestBatteryExemption();
  Future<void> openBatteryGuide() => _monitorService.openBatteryGuide();
  Future<void> openAppLaunchSettings() => _monitorService.openAppLaunchSettings();

  Future<void> startService() async {
    if (!isReady) return;
    final started = await _monitorService.startService();
    if (started) {
      _usage = _usage.copyWith(running: true, protectionEnabled: true, state: UsageState.allowed);
    }
    _notify();
  }

  Future<StopResult> stopService() async {
    final result = await _monitorService.stopService();
    if (result == StopResult.stopped) {
      _usage = _usage.copyWith(running: false, protectionEnabled: false, state: UsageState.inactive);
      _notify();
    }
    return result;
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _statusSubscription?.cancel();
    super.dispose();
  }
}
