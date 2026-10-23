import 'dart:async';
import 'package:flutter/services.dart';
import '../models/app_usage_state.dart';

class AppMonitorService {
  static const platform = MethodChannel('com.gabriel.attemption/permissions');

  Future<bool> checkUsagePermission() async {
    try {
      final bool result = await platform.invokeMethod('checkUsagePermission');
      return result;
    } on PlatformException {
      return false;
    }
  }

  Future<void> requestUsagePermission() async {
    try {
      await platform.invokeMethod('requestUsagePermission');
    } on PlatformException {}
  }

  Future<bool> checkOverlayPermission() async {
    try {
      final bool result = await platform.invokeMethod('checkOverlayPermission');
      return result;
    } on PlatformException {
      return false;
    }
  }

  Future<void> requestOverlayPermission() async {
    try {
      await platform.invokeMethod('requestOverlayPermission');
    } on PlatformException {}
  }

  Future<void> startService() async {
    try {
      await platform.invokeMethod('startMonitorService');
    } on PlatformException {}
  }
}
