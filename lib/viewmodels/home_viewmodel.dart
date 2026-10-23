import 'package:flutter/material.dart';
import '../services/app_monitor_service.dart';

class HomeViewModel extends ChangeNotifier {
  final AppMonitorService _monitorService = AppMonitorService();
  
  bool _hasUsagePermission = false;
  bool _hasOverlayPermission = false;
  bool _isServiceRunning = false;

  bool get hasUsagePermission => _hasUsagePermission;
  bool get hasOverlayPermission => _hasOverlayPermission;
  bool get isReady => _hasUsagePermission && _hasOverlayPermission;
  bool get isServiceRunning => _isServiceRunning;

  HomeViewModel() {
    checkPermissions();
  }

  Future<void> checkPermissions() async {
    _hasUsagePermission = await _monitorService.checkUsagePermission();
    _hasOverlayPermission = await _monitorService.checkOverlayPermission();
    notifyListeners();
  }

  Future<void> requestUsagePermission() async {
    await _monitorService.requestUsagePermission();
    // Re-check after returning to app
    Future.delayed(const Duration(seconds: 2), checkPermissions);
  }

  Future<void> requestOverlayPermission() async {
    await _monitorService.requestOverlayPermission();
    // Re-check after returning to app
    Future.delayed(const Duration(seconds: 2), checkPermissions);
  }

  Future<void> startService() async {
    if (isReady) {
      _isServiceRunning = await _monitorService.startService();
      notifyListeners();
    }
  }
}
