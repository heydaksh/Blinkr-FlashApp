/// Immutable models mirroring the native settings/status maps (Section 5.2).
library;

class FlashPatternConfig {
  const FlashPatternConfig({
    this.onMs = 60,
    this.offMs = 60,
    this.pulsesPerBurst = 3,
    this.burstGapMs = 400,
    this.bursts = 2,
    this.maxDurationMs = 3000,
    this.strengthLevel,
  });

  final int onMs;
  final int offMs;
  final int pulsesPerBurst;
  final int burstGapMs;
  final int bursts;
  final int maxDurationMs;
  final int? strengthLevel;

  factory FlashPatternConfig.fromMap(Map<Object?, Object?>? m,
      [FlashPatternConfig base = const FlashPatternConfig()]) {
    if (m == null) return base;
    int i(String k, int d) => (m[k] as num?)?.toInt() ?? d;
    return FlashPatternConfig(
      onMs: i('onMs', base.onMs),
      offMs: i('offMs', base.offMs),
      pulsesPerBurst: i('pulsesPerBurst', base.pulsesPerBurst),
      burstGapMs: i('burstGapMs', base.burstGapMs),
      bursts: i('bursts', base.bursts),
      maxDurationMs: i('maxDurationMs', base.maxDurationMs),
      strengthLevel: (m['strengthLevel'] as num?)?.toInt(),
    );
  }

  Map<String, Object?> toMap() => {
        'onMs': onMs,
        'offMs': offMs,
        'pulsesPerBurst': pulsesPerBurst,
        'burstGapMs': burstGapMs,
        'bursts': bursts,
        'maxDurationMs': maxDurationMs,
        'strengthLevel': strengthLevel,
      };

  FlashPatternConfig copyWith({
    int? onMs,
    int? offMs,
    int? pulsesPerBurst,
    int? burstGapMs,
    int? bursts,
    int? maxDurationMs,
  }) =>
      FlashPatternConfig(
        onMs: onMs ?? this.onMs,
        offMs: offMs ?? this.offMs,
        pulsesPerBurst: pulsesPerBurst ?? this.pulsesPerBurst,
        burstGapMs: burstGapMs ?? this.burstGapMs,
        bursts: bursts ?? this.bursts,
        maxDurationMs: maxDurationMs ?? this.maxDurationMs,
        strengthLevel: strengthLevel,
      );

  @override
  bool operator ==(Object other) =>
      other is FlashPatternConfig &&
      other.onMs == onMs &&
      other.offMs == offMs &&
      other.pulsesPerBurst == pulsesPerBurst &&
      other.burstGapMs == burstGapMs &&
      other.bursts == bursts &&
      other.maxDurationMs == maxDurationMs &&
      other.strengthLevel == strengthLevel;

  @override
  int get hashCode => Object.hash(onMs, offMs, pulsesPerBurst, burstGapMs, bursts, maxDurationMs, strengthLevel);
}

enum FlashSpeed {
  fast('Fast', 40, 40, 4, 300),
  normal('Normal', 60, 60, 3, 400),
  slow('Slow', 100, 100, 2, 500);

  const FlashSpeed(this.label, this.onMs, this.offMs, this.pulses, this.gapMs);
  final String label;
  final int onMs;
  final int offMs;
  final int pulses;
  final int gapMs;
}

class FlashAlertsSettings {
  const FlashAlertsSettings({
    this.enabled = false,
    this.notificationsEnabled = true,
    this.callsEnabled = true,
    this.onlyWhenScreenOff = false,
    this.respectDnd = true,
    this.notificationPattern = const FlashPatternConfig(),
    this.callPattern = const FlashPatternConfig(
        burstGapMs: 500, bursts: 2147483647, maxDurationMs: 30000),
    this.strengthLevel,
    this.packageFilterMode = 'all',
    this.packages = const [],
    this.cooldownMs = 2000,
    this.dryRun = false,
  });

  final bool enabled;
  final bool notificationsEnabled;
  final bool callsEnabled;
  final bool onlyWhenScreenOff;
  final bool respectDnd;
  final FlashPatternConfig notificationPattern;
  final FlashPatternConfig callPattern;
  final int? strengthLevel;
  final String packageFilterMode; // all | allowlist | denylist
  final List<String> packages;
  final int cooldownMs;
  final bool dryRun;

  factory FlashAlertsSettings.fromMap(Map<Object?, Object?> m) {
    const d = FlashAlertsSettings();
    return FlashAlertsSettings(
      enabled: m['enabled'] as bool? ?? d.enabled,
      notificationsEnabled: m['notificationsEnabled'] as bool? ?? d.notificationsEnabled,
      callsEnabled: m['callsEnabled'] as bool? ?? d.callsEnabled,
      onlyWhenScreenOff: m['onlyWhenScreenOff'] as bool? ?? d.onlyWhenScreenOff,
      respectDnd: m['respectDnd'] as bool? ?? d.respectDnd,
      notificationPattern: FlashPatternConfig.fromMap(
          m['notificationPattern'] as Map<Object?, Object?>?, d.notificationPattern),
      callPattern: FlashPatternConfig.fromMap(m['callPattern'] as Map<Object?, Object?>?, d.callPattern),
      strengthLevel: (m['strengthLevel'] as num?)?.toInt(),
      packageFilterMode: m['packageFilterMode'] as String? ?? d.packageFilterMode,
      packages: ((m['packages'] as List?)?.cast<String>()) ?? d.packages,
      cooldownMs: (m['cooldownMs'] as num?)?.toInt() ?? d.cooldownMs,
      dryRun: m['dryRun'] as bool? ?? d.dryRun,
    );
  }

  Map<String, Object?> toMap() => {
        'enabled': enabled,
        'notificationsEnabled': notificationsEnabled,
        'callsEnabled': callsEnabled,
        'onlyWhenScreenOff': onlyWhenScreenOff,
        'respectDnd': respectDnd,
        'notificationPattern': notificationPattern.toMap(),
        'callPattern': callPattern.toMap(),
        'strengthLevel': strengthLevel,
        'packageFilterMode': packageFilterMode,
        'packages': packages,
        'cooldownMs': cooldownMs,
        'dryRun': dryRun,
      };

  FlashAlertsSettings copyWith({
    bool? enabled,
    bool? notificationsEnabled,
    bool? callsEnabled,
    bool? onlyWhenScreenOff,
    bool? respectDnd,
    FlashPatternConfig? notificationPattern,
    FlashPatternConfig? callPattern,
    String? packageFilterMode,
    List<String>? packages,
    int? cooldownMs,
    bool? dryRun,
  }) =>
      FlashAlertsSettings(
        enabled: enabled ?? this.enabled,
        notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
        callsEnabled: callsEnabled ?? this.callsEnabled,
        onlyWhenScreenOff: onlyWhenScreenOff ?? this.onlyWhenScreenOff,
        respectDnd: respectDnd ?? this.respectDnd,
        notificationPattern: notificationPattern ?? this.notificationPattern,
        callPattern: callPattern ?? this.callPattern,
        strengthLevel: strengthLevel,
        packageFilterMode: packageFilterMode ?? this.packageFilterMode,
        packages: packages ?? this.packages,
        cooldownMs: cooldownMs ?? this.cooldownMs,
        dryRun: dryRun ?? this.dryRun,
      );

  /// Applies a speed preset to both patterns (call pattern keeps its repeat/gap-length semantics).
  FlashAlertsSettings withSpeed(FlashSpeed s) => copyWith(
        notificationPattern: notificationPattern.copyWith(
            onMs: s.onMs, offMs: s.offMs, pulsesPerBurst: s.pulses, burstGapMs: s.gapMs),
        callPattern: callPattern.copyWith(
            onMs: s.onMs, offMs: s.offMs, pulsesPerBurst: s.pulses, burstGapMs: s.gapMs + 100),
      );

  /// Which preset is active. Native code clamps pulses to the phone's calibrated
  /// minimum, so presets are compared after the same clamp.
  FlashSpeed? speedFor(int minMs) {
    final p = notificationPattern;
    final floor = minMs < 20 ? 20 : minMs;
    for (final s in FlashSpeed.values) {
      if (p.onMs == (s.onMs < floor ? floor : s.onMs) &&
          p.offMs == (s.offMs < floor ? floor : s.offMs) &&
          p.pulsesPerBurst == s.pulses &&
          p.burstGapMs == s.gapMs) {
        return s;
      }
    }
    return null;
  }

  FlashSpeed? get speed => speedFor(20);
}

class FlashAlertsStatus {
  const FlashAlertsStatus({
    this.supported = false,
    this.hasFlash = false,
    this.sdkInt = 0,
    this.manufacturer = '',
    this.model = '',
    this.listenerEnabled = false,
    this.listenerConnected = false,
    this.phoneStatePermission = false,
    this.batteryOptimized = true,
    this.maxStrengthLevel = 1,
    this.calibratedMinMs = 40,
    this.systemFlashNotificationsLikelyOn = false,
    this.sideloaded = false,
    this.debuggable = false,
  });

  static const unsupported = FlashAlertsStatus();

  final bool supported;
  final bool hasFlash;
  final int sdkInt;
  final String manufacturer;
  final String model;
  final bool listenerEnabled;
  final bool listenerConnected;
  final bool phoneStatePermission;
  final bool batteryOptimized;
  final int maxStrengthLevel;
  final int calibratedMinMs;
  final bool systemFlashNotificationsLikelyOn;
  final bool sideloaded;
  final bool debuggable;

  /// Android 13+ sideloaded builds need "Allow restricted settings" first.
  bool get needsRestrictedSettingsHint => sideloaded && sdkInt >= 33 && !listenerEnabled;

  factory FlashAlertsStatus.fromMap(Map<Object?, Object?> m) => FlashAlertsStatus(
        supported: m['supported'] as bool? ?? false,
        hasFlash: m['hasFlash'] as bool? ?? false,
        sdkInt: (m['sdkInt'] as num?)?.toInt() ?? 0,
        manufacturer: m['manufacturer'] as String? ?? '',
        model: m['model'] as String? ?? '',
        listenerEnabled: m['listenerEnabled'] as bool? ?? false,
        listenerConnected: m['listenerConnected'] as bool? ?? false,
        phoneStatePermission: m['phoneStatePermission'] as bool? ?? false,
        batteryOptimized: m['batteryOptimized'] as bool? ?? true,
        maxStrengthLevel: (m['maxStrengthLevel'] as num?)?.toInt() ?? 1,
        calibratedMinMs: (m['calibratedMinMs'] as num?)?.toInt() ?? 40,
        systemFlashNotificationsLikelyOn: m['systemFlashNotificationsLikelyOn'] as bool? ?? false,
        sideloaded: m['sideloaded'] as bool? ?? false,
        debuggable: m['debuggable'] as bool? ?? false,
      );
}

class CalibrationResult {
  const CalibrationResult(this.calibratedMinMs, this.medianLatencyMs, this.confirmedRatio);
  final int calibratedMinMs;
  final int medianLatencyMs;
  final double confirmedRatio;
}
