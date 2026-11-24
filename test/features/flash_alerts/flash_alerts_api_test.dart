import 'package:android_flashlight/features/flash_alerts/flash_alerts_api.dart';
import 'package:android_flashlight/features/flash_alerts/flash_alerts_settings.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel(FlashAlertsApi.channelName);
  final calls = <MethodCall>[];

  void mock(Future<Object?> Function(MethodCall) h) {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (c) {
      calls.add(c);
      return h(c);
    });
  }

  tearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null));

  test('channel name matches native', () {
    expect(FlashAlertsApi.channelName, 'com.example.android_flashlight/flash_alerts');
  });

  test('non-Android returns unsupported and never touches the channel', () async {
    mock((_) async => fail('channel must not be invoked'));
    final api = FlashAlertsApi(isAndroid: false);
    expect((await api.getStatus()).supported, isFalse);
    expect(await api.testFlash(), isFalse);
    expect(await api.openNotificationAccessSettings(), isFalse);
    await api.stopFlash();
    expect(calls, isEmpty);
    expect(() => api.calibrate(), throwsA(isA<FlashAlertsException>()));
  });

  test('getStatus maps fields', () async {
    mock((c) async => {
          'supported': true,
          'hasFlash': true,
          'sdkInt': 34,
          'manufacturer': 'Google',
          'model': 'Pixel',
          'listenerEnabled': true,
          'listenerConnected': false,
          'phoneStatePermission': false,
          'batteryOptimized': true,
          'maxStrengthLevel': 5,
          'calibratedMinMs': 50,
          'systemFlashNotificationsLikelyOn': true,
        });
    final s = await FlashAlertsApi(isAndroid: true).getStatus();
    expect(calls.single.method, 'getStatus');
    expect(s.sdkInt, 34);
    expect(s.calibratedMinMs, 50);
    expect(s.systemFlashNotificationsLikelyOn, isTrue);
    expect(s.hasFlash, isTrue);
  });

  test('updateSettings sends a full map and parses the validated reply', () async {
    mock((c) async {
      final m = Map<Object?, Object?>.from(c.arguments as Map);
      m['notificationPattern'] = {...(m['notificationPattern'] as Map), 'onMs': 40}; // native clamps
      return m;
    });
    final api = FlashAlertsApi(isAndroid: true);
    final out = await api.updateSettings(const FlashAlertsSettings(enabled: true)
        .withSpeed(FlashSpeed.fast)
        .copyWith(
          notificationPattern: const FlashPatternConfig(onMs: 5),
          flashOnNormal: true,
          flashOnVibrate: false,
          flashOnSilent: true,
        ));
    expect(calls.single.method, 'updateSettings');
    final sent = calls.single.arguments as Map;
    expect(sent['enabled'], true);
    expect(sent['flashOnNormal'], true);
    expect(sent['flashOnVibrate'], false);
    expect(sent['flashOnSilent'], true);
    expect((sent['notificationPattern'] as Map)['onMs'], 5);
    expect(out.notificationPattern.onMs, 40);
  });

  test('testFlash passes optional pattern map', () async {
    mock((c) async => true);
    final api = FlashAlertsApi(isAndroid: true);
    expect(await api.testFlash(), isTrue);
    expect(calls.last.arguments, isNull);
    await api.testFlash(const FlashPatternConfig(onMs: 50));
    expect((calls.last.arguments as Map)['onMs'], 50);
  });

  test('trigger sends reason', () async {
    mock((c) async => true);
    await FlashAlertsApi(isAndroid: true).trigger(reason: 'fcm');
    expect(calls.single.method, 'trigger');
    expect(calls.single.arguments, {'reason': 'fcm'});
  });

  test('calibrate parses result', () async {
    mock((c) async => {'calibratedMinMs': 60, 'medianLatencyMs': 12, 'confirmedRatio': 0.97});
    final r = await FlashAlertsApi(isAndroid: true).calibrate();
    expect(r.calibratedMinMs, 60);
    expect(r.confirmedRatio, closeTo(0.97, 1e-9));
  });

  test('simple methods use documented names', () async {
    mock((c) async => c.method == 'getDiagnostics' ? 'log' : true);
    final api = FlashAlertsApi(isAndroid: true);
    await api.openNotificationAccessSettings();
    await api.openBatteryOptimizationSettings();
    await api.openAppDetailsSettings();
    await api.requestPhoneStatePermission();
    await api.stopFlash();
    expect(await api.getDiagnostics(), 'log');
    await api.clearDiagnostics();
    expect(calls.map((c) => c.method), [
      'openNotificationAccessSettings',
      'openBatteryOptimizationSettings',
      'openAppDetailsSettings',
      'requestPhoneStatePermission',
      'stopFlash',
      'getDiagnostics',
      'clearDiagnostics',
    ]);
  });

  test('error codes map to typed exceptions', () async {
    for (final code in ['UNSUPPORTED', 'NO_FLASH', 'CAMERA_UNAVAILABLE', 'INVALID_ARGS', 'NO_ACTIVITY']) {
      mock((_) async => throw PlatformException(code: code, message: 'm'));
      await expectLater(
        FlashAlertsApi(isAndroid: true).testFlash(),
        throwsA(isA<FlashAlertsException>().having((e) => e.code, 'code', code)),
      );
    }
    mock((_) async => throw PlatformException(code: 'NO_FLASH'));
    try {
      await FlashAlertsApi(isAndroid: true).testFlash();
    } on FlashAlertsException catch (e) {
      expect(e.isNoFlash, isTrue);
    }
  });

  test('settings model round trip and speed preset detection', () {
    final s = const FlashAlertsSettings(enabled: true, packages: ['a.b']).withSpeed(FlashSpeed.slow);
    expect(s.speed, FlashSpeed.slow);
    final back = FlashAlertsSettings.fromMap(s.toMap());
    expect(back.toMap(), s.toMap());
    expect(const FlashAlertsSettings().speed, FlashSpeed.normal);
    // Phone with a 60 ms minimum: native clamps Fast's 40 ms pulses to 60 ms; it must still read as Fast.
    final clamped = const FlashAlertsSettings().copyWith(
        notificationPattern: const FlashPatternConfig(onMs: 60, offMs: 60, pulsesPerBurst: 4, burstGapMs: 300));
    expect(clamped.speed, isNull);
    expect(clamped.speedFor(60), FlashSpeed.fast);
    expect(const FlashAlertsSettings().speedFor(60), FlashSpeed.normal);
  });

  test('getInstalledApps parses app list from channel', () async {
    mock((c) async => [
          {'name': 'WhatsApp', 'packageName': 'com.whatsapp', 'isSystemApp': false},
          {'name': 'Settings', 'packageName': 'com.android.settings', 'isSystemApp': true},
        ]);
    final apps = await FlashAlertsApi(isAndroid: true).getInstalledApps();
    expect(calls.single.method, 'getInstalledApps');
    expect(apps.length, 2);
    expect(apps.first.name, 'WhatsApp');
    expect(apps.first.packageName, 'com.whatsapp');
    expect(apps.first.isSystemApp, isFalse);
    expect(apps.last.isSystemApp, isTrue);
  });
}
