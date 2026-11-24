import 'package:android_flashlight/features/flash_alerts/flash_alerts_api.dart';
import 'package:android_flashlight/features/flash_alerts/flash_alerts_settings.dart';
import 'package:android_flashlight/features/flash_alerts/flash_alerts_settings_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeApi implements FlashAlertsApi {
  FakeApi(this.status, {this.supported = true});
  FlashAlertsStatus status;
  final bool supported;
  FlashAlertsSettings settings = const FlashAlertsSettings();
  int tests = 0;
  int openedAccess = 0;

  @override
  bool get isSupportedPlatform => supported;
  @override
  Future<FlashAlertsStatus> getStatus() async => status;
  @override
  Future<FlashAlertsSettings> getSettings() async => settings;
  @override
  Future<FlashAlertsSettings> updateSettings(FlashAlertsSettings s) async => settings = s;
  @override
  Future<bool> openNotificationAccessSettings() async {
    openedAccess++;
    return true;
  }

  @override
  Future<bool> testFlash([FlashPatternConfig? pattern]) async {
    tests++;
    return true;
  }

  @override
  dynamic noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

Future<void> pump(WidgetTester t, FakeApi api) async {
  await t.binding.setSurfaceSize(const Size(420, 1600));
  addTearDown(() => t.binding.setSurfaceSize(null));
  await t.pumpWidget(MaterialApp(home: FlashAlertsSettingsScreen(api: api)));
  await t.pumpAndSettle();
}

void main() {
  testWidgets('no flash hardware shows explanation', (t) async {
    await pump(t, FakeApi(const FlashAlertsStatus(supported: true, hasFlash: false, sdkInt: 34)));
    expect(find.textContaining('no camera flash'), findsOneWidget);
    expect(find.text('Test flash'), findsNothing);
  });

  testWidgets('unsupported platform', (t) async {
    await pump(t, FakeApi(FlashAlertsStatus.unsupported, supported: false));
    expect(find.textContaining('only available on Android'), findsOneWidget);
  });

  testWidgets('access missing shows action and restricted-settings hint when sideloaded on 13+', (t) async {
    final api = FakeApi(const FlashAlertsStatus(supported: true, hasFlash: true, sdkInt: 34, sideloaded: true));
    await pump(t, api);
    expect(find.text('Notification access'), findsOneWidget);
    expect(find.textContaining('Allow restricted settings'), findsOneWidget);
    await t.tap(find.widgetWithText(TextButton, 'Open'));
    expect(api.openedAccess, 1);
  });

  testWidgets('all good: no hint, master toggle persists, test flash works', (t) async {
    final api = FakeApi(const FlashAlertsStatus(
        supported: true, hasFlash: true, sdkInt: 34, listenerEnabled: true, listenerConnected: true, batteryOptimized: false));
    await pump(t, api);
    expect(find.textContaining('Allow restricted settings'), findsNothing);
    expect(find.text('Active'), findsOneWidget);

    await t.tap(find.widgetWithText(SwitchListTile, 'Flash for alerts'));
    await t.pumpAndSettle();
    expect(api.settings.enabled, isTrue);

    await t.tap(find.text('Fast'));
    await t.pumpAndSettle();
    expect(api.settings.speed, FlashSpeed.fast);

    await t.scrollUntilVisible(find.text('Test flash'), 300);
    await t.ensureVisible(find.text('Test flash'));
    await t.pumpAndSettle();
    await t.tap(find.text('Test flash'));
    await t.pumpAndSettle();
    expect(find.byType(SnackBar), findsOneWidget);
    expect(find.byIcon(Icons.close), findsOneWidget);
    await t.tap(find.byIcon(Icons.close));
    await t.pumpAndSettle();
    expect(find.byType(SnackBar), findsNothing);
    expect(api.tests, 1);
  });

  testWidgets('duplicate system flash warning', (t) async {
    await pump(t, FakeApi(const FlashAlertsStatus(
        supported: true, hasFlash: true, sdkInt: 34, systemFlashNotificationsLikelyOn: true)));
    expect(find.textContaining('built-in flash notification'), findsOneWidget);
  });
}
