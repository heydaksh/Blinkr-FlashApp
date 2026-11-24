import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flash_alerts_settings.dart';

/// Stable native error codes (Section 5.2).
class FlashAlertsException implements Exception {
  FlashAlertsException(this.code, [this.message]);
  final String code; // UNSUPPORTED, NO_FLASH, CAMERA_UNAVAILABLE, INVALID_ARGS, NO_ACTIVITY
  final String? message;

  bool get isNoFlash => code == 'NO_FLASH';
  bool get isCameraUnavailable => code == 'CAMERA_UNAVAILABLE';

  @override
  String toString() => 'FlashAlertsException($code${message == null ? '' : ': $message'})';
}

/// Thin typed wrapper over the platform channel. No-ops off Android.
class FlashAlertsApi {
  FlashAlertsApi({MethodChannel? channel, bool? isAndroid})
      : _channel = channel ?? const MethodChannel(channelName),
        _isAndroid = isAndroid ?? (!kIsWeb && defaultTargetPlatform == TargetPlatform.android);

  static const channelName = 'com.example.android_flashlight/flash_alerts';

  final MethodChannel _channel;
  final bool _isAndroid;

  bool get isSupportedPlatform => _isAndroid;

  Future<T> _call<T>(String method, T fallback, [Object? args]) async {
    if (!_isAndroid) return fallback;
    try {
      final r = await _channel.invokeMethod<Object?>(method, args);
      return (r ?? fallback) as T;
    } on PlatformException catch (e) {
      throw FlashAlertsException(e.code, e.message);
    }
  }

  Future<Map<Object?, Object?>> _map(String method, [Object? args]) async {
    if (!_isAndroid) return const {};
    try {
      final r = await _channel.invokeMethod<Map<Object?, Object?>>(method, args);
      return r ?? const {};
    } on PlatformException catch (e) {
      throw FlashAlertsException(e.code, e.message);
    }
  }

  Future<FlashAlertsStatus> getStatus() async {
    if (!_isAndroid) return FlashAlertsStatus.unsupported;
    return FlashAlertsStatus.fromMap(await _map('getStatus'));
  }

  Future<FlashAlertsSettings> getSettings() async =>
      FlashAlertsSettings.fromMap(await _map('getSettings'));

  /// Returns the validated settings as stored natively.
  Future<FlashAlertsSettings> updateSettings(FlashAlertsSettings s) async {
    if (!_isAndroid) return s;
    return FlashAlertsSettings.fromMap(await _map('updateSettings', s.toMap()));
  }

  Future<bool> openNotificationAccessSettings() => _call('openNotificationAccessSettings', false);
  Future<bool> openBatteryOptimizationSettings() => _call('openBatteryOptimizationSettings', false);
  Future<bool> openAppDetailsSettings() => _call('openAppDetailsSettings', false);
  Future<bool> requestPhoneStatePermission() => _call('requestPhoneStatePermission', false);

  /// Returns true if a run started. Throws [FlashAlertsException] (NO_FLASH / CAMERA_UNAVAILABLE).
  Future<bool> testFlash([FlashPatternConfig? pattern]) => _call('testFlash', false, pattern?.toMap());

  Future<void> stopFlash() => _call<Object?>('stopFlash', null);

  Future<CalibrationResult> calibrate() async {
    if (!_isAndroid) throw FlashAlertsException('UNSUPPORTED');
    final m = await _map('calibrate');
    return CalibrationResult(
      (m['calibratedMinMs'] as num?)?.toInt() ?? 0,
      (m['medianLatencyMs'] as num?)?.toInt() ?? 0,
      (m['confirmedRatio'] as num?)?.toDouble() ?? 0,
    );
  }

  /// Foreground fallback only (e.g. from an FCM onMessage handler when notification access is off).
  Future<bool> trigger({String reason = 'foreground'}) => _call('trigger', false, {'reason': reason});

  Future<String> getDiagnostics() => _call('getDiagnostics', '');
  Future<void> clearDiagnostics() => _call<Object?>('clearDiagnostics', null);

  Future<List<InstalledAppInfo>> getInstalledApps() async {
    if (!_isAndroid) return const [];
    try {
      final list = await _channel.invokeListMethod<Map<Object?, Object?>>('getInstalledApps');
      if (list == null) return const [];
      return list.map((m) => InstalledAppInfo.fromMap(m)).toList();
    } catch (_) {
      return const [];
    }
  }
}

class InstalledAppInfo {
  const InstalledAppInfo({
    required this.name,
    required this.packageName,
    this.isSystemApp = false,
    this.iconBytes,
  });

  final String name;
  final String packageName;
  final bool isSystemApp;
  final Uint8List? iconBytes;

  factory InstalledAppInfo.fromMap(Map<Object?, Object?> m) {
    return InstalledAppInfo(
      name: m['name'] as String? ?? '',
      packageName: m['packageName'] as String? ?? '',
      isSystemApp: m['isSystemApp'] as bool? ?? false,
      iconBytes: m['iconBytes'] as Uint8List?,
    );
  }
}
