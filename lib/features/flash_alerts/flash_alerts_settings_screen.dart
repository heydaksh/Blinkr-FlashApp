import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'flash_alerts_api.dart';
import 'flash_alerts_settings.dart';

class FlashAlertsSettingsScreen extends StatefulWidget {
  const FlashAlertsSettingsScreen({super.key, required this.api});
  final FlashAlertsApi api;

  @override
  State<FlashAlertsSettingsScreen> createState() => _FlashAlertsSettingsScreenState();
}

class _FlashAlertsSettingsScreenState extends State<FlashAlertsSettingsScreen>
    with WidgetsBindingObserver {
  FlashAlertsStatus _status = FlashAlertsStatus.unsupported;
  FlashAlertsSettings _settings = const FlashAlertsSettings();
  bool _loaded = false;
  bool _busy = false;
  final _packages = TextEditingController();
  List<InstalledAppInfo> _installedApps = [];
  bool _loadingApps = false;

  FlashAlertsApi get api => widget.api;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _packages.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshStatus();
  }

  Future<void> _load() async {
    try {
      final status = await api.getStatus();
      final settings = await api.getSettings();
      if (!mounted) return;
      setState(() {
        _status = status;
        _settings = settings;
        _packages.text = settings.packages.join(', ');
        _loaded = true;
      });
    } on FlashAlertsException catch (e) {
      _toast('Error: ${e.code}');
      if (mounted) setState(() => _loaded = true);
    }
  }

  Future<void> _refreshStatus() async {
    try {
      final s = await api.getStatus();
      if (mounted) setState(() => _status = s);
    } on FlashAlertsException catch (_) {}
  }

  Future<void> _fetchInstalledApps() async {
    if (_installedApps.isNotEmpty) return;
    setState(() => _loadingApps = true);
    try {
      final list = await api.getInstalledApps();
      if (mounted) {
        setState(() {
          _installedApps = list;
          _loadingApps = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loadingApps = false);
    }
  }

  void _openAppPicker() async {
    await _fetchInstalledApps();
    if (!mounted) return;
    final selected = Set<String>.from(_settings.packages);
    final result = await showModalBottomSheet<Set<String>>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => _AppPickerSheet(initialSelected: selected, apps: _installedApps),
    );
    if (result != null && mounted) {
      final sorted = result.toList()..sort();
      _save(_settings.copyWith(packages: sorted));
      _packages.text = sorted.join(', ');
    }
  }

  Future<void> _save(FlashAlertsSettings next) async {
    setState(() => _settings = next);
    try {
      final stored = await api.updateSettings(next);
      if (mounted) setState(() => _settings = stored);
      _refreshStatus(); // calibratedMinMs may change
    } on FlashAlertsException catch (e) {
      _toast('Could not save: ${e.code}');
    }
  }

  void _toggleSwitch(FlashAlertsSettings next) {
    HapticFeedback.lightImpact();
    _save(next);
  }

  static String _cleanName(String pkg) {
    final parts = pkg.split('.');
    if (parts.length > 1) {
      final last = parts.last;
      return last.isNotEmpty ? (last[0].toUpperCase() + last.substring(1)) : pkg;
    }
    return pkg;
  }

  void _toast(String m) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(m),
          showCloseIcon: true,
          closeIconColor: Theme.of(context).brightness == Brightness.dark
              ? Colors.black54
              : Colors.white70,
          behavior: SnackBarBehavior.floating,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      );
  }

  Future<void> _guard(Future<void> Function() body) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await body();
    } on FlashAlertsException catch (e) {
      _toast(switch (e.code) {
        'NO_FLASH' => 'This device has no flash hardware.',
        'CAMERA_UNAVAILABLE' => 'Flashlight unavailable (camera in use or device too hot).',
        'UNSUPPORTED' => 'Needs Android 6 or newer.',
        _ => 'Error: ${e.code}',
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _test() => _guard(() async {
    HapticFeedback.mediumImpact();
    final started = await api.testFlash();
    _toast(started ? 'Flashing…' : 'Skipped: your flashlight is already on.');
  });

  Future<void> _calibrate() => _guard(() async {
    HapticFeedback.mediumImpact();
    _toast('Calibrating… the LED will flicker for a few seconds.');
    final r = await api.calibrate();
    await _load();
    _toast(
      'Minimum pulse ${r.calibratedMinMs} ms (latency ${r.medianLatencyMs} ms, '
      '${(r.confirmedRatio * 100).round()}% confirmed)',
    );
  });

  Future<void> _export() => _guard(() async {
    HapticFeedback.lightImpact();
    final text = await api.getDiagnostics();
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: const Row(
          children: [
            Icon(Icons.terminal_rounded, size: 24),
            SizedBox(width: 8),
            Text('Diagnostics Log'),
          ],
        ),
        content: SizedBox(
          width: double.maxFinite,
          child: Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(ctx).colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
              borderRadius: BorderRadius.circular(12),
            ),
            child: SingleChildScrollView(
              child: SelectableText(
                text.isEmpty ? '(log is empty)' : text,
                style: const TextStyle(fontSize: 11, fontFamily: 'monospace', height: 1.4),
              ),
            ),
          ),
        ),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.delete_outline, size: 18),
            onPressed: () async {
              HapticFeedback.mediumImpact();
              await api.clearDiagnostics();
              if (ctx.mounted) Navigator.pop(ctx);
              _toast('Diagnostics log cleared');
            },
            label: const Text('Clear'),
          ),
          FilledButton.tonalIcon(
            icon: const Icon(Icons.copy_rounded, size: 18),
            onPressed: () async {
              HapticFeedback.lightImpact();
              await Clipboard.setData(ClipboardData(text: text));
              if (ctx.mounted) Navigator.pop(ctx);
              _toast('Copied to clipboard');
            },
            label: const Text('Copy'),
          ),
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  });

  void _showHelpSheet() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) => DraggableScrollableSheet(
        initialChildSize: 0.56,
        minChildSize: 0.4,
        maxChildSize: 0.9,
        expand: false,
        builder: (_, scroll) => ListView(
          controller: scroll,
          padding: const EdgeInsets.fromLTRB(24, 16, 24, 32),
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: Colors.grey.withValues(alpha: 0.3),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(Icons.flash_on_rounded, color: Theme.of(context).colorScheme.primary),
                ),
                const SizedBox(width: 14),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Blinkr',
                      style: Theme.of(
                        context,
                      ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                    ),
                    Text(
                      'Version 1.0.0 • Battery-friendly',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 20),
            Text(
              'How it works',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            Text(
              'Blinkr monitors system notifications and incoming calls entirely inside native Android. '
              'When an alert arrives, your camera flash blinks in crisp, synchronized bursts without keeping background services permanently running.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(height: 1.4),
            ),
            const SizedBox(height: 16),
            Text(
              'Why notification access?',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            Text(
              'Android allows apps to trigger flashes on incoming notifications only when Notification Access is granted. '
              'The app only checks the app identifier and alerting priority. Message contents, titles, and text are strictly never read or stored.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(height: 1.4),
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => Navigator.pop(ctx),
              style: FilledButton.styleFrom(
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: const Text('Got it'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: SvgPicture.asset(
          'assets/icon/torch.svg',
          height: 40,
          width: 40,
          fit: BoxFit.contain,
          colorFilter: ColorFilter.mode(
            Theme.of(context).brightness == Brightness.dark ? Colors.white : Colors.black,
            BlendMode.srcIn,
          ),
        ),
        actions: [
          IconButton(
            tooltip: 'Diagnostics',
            icon: const Icon(Icons.terminal_rounded),
            onPressed: _export,
          ),
          IconButton(
            tooltip: 'About & Help',
            icon: const Icon(Icons.info_outline_rounded),
            onPressed: _showHelpSheet,
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : !api.isSupportedPlatform || !_status.supported
          ? const _Message('Blinkr is only available on Android 6.0 or newer.')
          : !_status.hasFlash && !_status.debuggable
          ? const _Message('This device has no camera flash, so LED alerts are not available.')
          : _content(),
    );
  }

  Widget _content() {
    final s = _settings;
    final canEnable = _status.hasFlash || _status.debuggable;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 40),
      children: [
        // Disclosure must be visible before the user is sent to Notification access
        const _PrivacyCard(),
        const SizedBox(height: 12),
        // Device Readiness & Status Checklist
        _StatusCard(
          status: _status,
          onNotificationAccess: api.openNotificationAccessSettings,
          onPhone: () async {
            await api.requestPhoneStatePermission();
            _refreshStatus();
          },
          onBattery: api.openBatteryOptimizationSettings,
          onAppInfo: api.openAppDetailsSettings,
        ),

        if (_status.systemFlashNotificationsLikelyOn) ...[
          const SizedBox(height: 10),
          const _Warning(
            'Your phone\'s built-in flash notification setting looks enabled. '
            'Both systems would flash; turn one of them off.',
          ),
        ],

        const SizedBox(height: 12),
        Card(
          child: Column(
            children: [
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(
                    Icons.flash_on_rounded,
                    color: Theme.of(context).colorScheme.primary,
                    size: 20,
                  ),
                ),
                title: const Text(
                  'Flash for alerts',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                subtitle: const Text('Master switch'),
                value: s.enabled,
                onChanged: canEnable ? (v) => _toggleSwitch(s.copyWith(enabled: v)) : null,
              ),
              const Divider(height: 1, indent: 56),
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Colors.green.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.phone_in_talk_rounded, color: Colors.green, size: 20),
                ),
                title: const Text('Incoming calls', style: TextStyle(fontWeight: FontWeight.w600)),
                value: s.callsEnabled,
                onChanged: (v) => _toggleSwitch(s.copyWith(callsEnabled: v)),
              ),
              const Divider(height: 1, indent: 56),
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Colors.blue.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(
                    Icons.notifications_active_rounded,
                    color: Colors.blue,
                    size: 20,
                  ),
                ),
                title: const Text('Notifications', style: TextStyle(fontWeight: FontWeight.w600)),
                value: s.notificationsEnabled,
                onChanged: (v) => _toggleSwitch(s.copyWith(notificationsEnabled: v)),
              ),
              const Divider(height: 1, indent: 56),
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Colors.purple.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(
                    Icons.screen_lock_portrait_rounded,
                    color: Colors.purple,
                    size: 20,
                  ),
                ),
                title: const Text(
                  'Only when screen is off or locked',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                value: s.onlyWhenScreenOff,
                onChanged: (v) => _toggleSwitch(s.copyWith(onlyWhenScreenOff: v)),
              ),
              const Divider(height: 1, indent: 56),
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Colors.deepOrange.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(
                    Icons.do_not_disturb_on_rounded,
                    color: Colors.deepOrange,
                    size: 20,
                  ),
                ),
                title: const Text(
                  'Respect Do Not Disturb',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                value: s.respectDnd,
                onChanged: (v) => _toggleSwitch(s.copyWith(respectDnd: v)),
              ),
            ],
          ),
        ),

        const SizedBox(height: 16),
        _sectionHeader('Active Sound Profiles', Icons.volume_up_rounded),
        const SizedBox(height: 8),

        Card(
          child: Column(
            children: [
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Colors.amber.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.volume_up_rounded, color: Colors.amber, size: 20),
                ),
                title: const Text('Ring Mode', style: TextStyle(fontWeight: FontWeight.w600)),
                subtitle: const Text('Flash when phone sounds ringtone'),
                value: s.flashOnNormal,
                onChanged: (v) => _toggleSwitch(s.copyWith(flashOnNormal: v)),
              ),
              const Divider(height: 1, indent: 56),
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Colors.teal.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.vibration_rounded, color: Colors.teal, size: 20),
                ),
                title: const Text('Vibrate Mode', style: TextStyle(fontWeight: FontWeight.w600)),
                subtitle: const Text('Flash when phone is set to vibrate'),
                value: s.flashOnVibrate,
                onChanged: (v) => _toggleSwitch(s.copyWith(flashOnVibrate: v)),
              ),
              const Divider(height: 1, indent: 56),
              SwitchListTile(
                dense: true,
                secondary: Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: Colors.deepOrange.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.volume_off_rounded, color: Colors.deepOrange, size: 20),
                ),
                title: const Text('Silent Mode', style: TextStyle(fontWeight: FontWeight.w600)),
                subtitle: const Text('Flash when phone is completely muted'),
                value: s.flashOnSilent,
                onChanged: (v) => _toggleSwitch(s.copyWith(flashOnSilent: v)),
              ),
            ],
          ),
        ),

        const SizedBox(height: 16),
        _sectionHeader('Flash Speed', Icons.speed_rounded),
        const SizedBox(height: 8),

        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Flexible(
                      child: Text(
                        'Flash speed',
                        style: Theme.of(
                          context,
                        ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.12),
                        borderRadius: BorderRadius.circular(20),
                      ),
                      child: Text(
                        'Min: ${_status.calibratedMinMs} ms',
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.bold,
                          color: Theme.of(context).colorScheme.primary,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  'Rapid short pulses. Timing is limited by your phone (fastest reliable pulse: ${_status.calibratedMinMs} ms).',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Colors.grey),
                ),
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  child: SegmentedButton<FlashSpeed>(
                    emptySelectionAllowed: true,
                    showSelectedIcon: false,
                    style: SegmentedButton.styleFrom(
                      selectedBackgroundColor: Theme.of(context).colorScheme.primary,
                      selectedForegroundColor: Theme.of(context).colorScheme.onPrimary,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                    ),
                    segments: [
                      for (final v in FlashSpeed.values)
                        ButtonSegment(value: v, label: Text(v.label)),
                    ],
                    selected: {
                      if (s.speedFor(_status.calibratedMinMs) != null)
                        s.speedFor(_status.calibratedMinMs)!,
                    },
                    onSelectionChanged: (sel) {
                      if (sel.isNotEmpty) {
                        HapticFeedback.mediumImpact();
                        _save(s.withSpeed(sel.first));
                      }
                    },
                  ),
                ),
                if (_status.calibratedMinMs > FlashSpeed.fast.onMs)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'This phone cannot blink faster than ${_status.calibratedMinMs} ms, so Fast uses '
                      '${_status.calibratedMinMs} ms pulses with more pulses and shorter pauses.',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Colors.grey),
                    ),
                  ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    _speedInfoTile('Pulses', '${s.notificationPattern.pulsesPerBurst}x'),
                    const SizedBox(width: 8),
                    _speedInfoTile('Pulse On', '${s.notificationPattern.onMs}ms'),
                    const SizedBox(width: 8),
                    _speedInfoTile('Gap', '${s.notificationPattern.burstGapMs}ms'),
                    const SizedBox(width: 8),
                    _speedInfoTile('Bursts', '${s.notificationPattern.bursts}x'),
                  ],
                ),
              ],
            ),
          ),
        ),

        const SizedBox(height: 16),
        _sectionHeader('App Filter', Icons.filter_alt_outlined),
        const SizedBox(height: 8),

        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                DropdownButtonFormField<String>(
                  isExpanded: true,
                  initialValue: s.packageFilterMode,
                  decoration: InputDecoration(
                    labelText: 'Alert Filter Mode',
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
                    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  ),
                  items: const [
                    DropdownMenuItem(value: 'all', child: Text('All apps')),
                    DropdownMenuItem(value: 'allowlist', child: Text('Only these apps')),
                    DropdownMenuItem(value: 'denylist', child: Text('All except these apps')),
                  ],
                  onChanged: (v) {
                    if (v != null) {
                      HapticFeedback.mediumImpact();
                      _save(s.copyWith(packageFilterMode: v));
                    }
                  },
                ),
                if (s.packageFilterMode != 'all') ...[
                  const SizedBox(height: 14),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.tonalIcon(
                      style: FilledButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 14),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                      ),
                      icon: _loadingApps
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.checklist_rounded, size: 20),
                      label: Text(
                        s.packages.isEmpty
                            ? 'Select Installed Apps'
                            : 'Select Apps (${s.packages.length} selected)',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      onPressed: _loadingApps
                          ? null
                          : () {
                              HapticFeedback.mediumImpact();
                              _openAppPicker();
                            },
                    ),
                  ),
                  if (s.packages.isNotEmpty) ...[
                    const SizedBox(height: 14),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Selected Apps (${s.packages.length}):',
                          style: Theme.of(
                            context,
                          ).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.bold),
                        ),
                        TextButton(
                          onPressed: () {
                            HapticFeedback.mediumImpact();
                            _save(s.copyWith(packages: []));
                            _packages.clear();
                          },
                          child: const Text('Clear all', style: TextStyle(fontSize: 12)),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: s.packages.map((pkg) {
                        final found = _installedApps.firstWhere(
                          (a) => a.packageName == pkg,
                          orElse: () => InstalledAppInfo(name: _cleanName(pkg), packageName: pkg),
                        );
                        return Chip(
                          labelPadding: const EdgeInsets.symmetric(horizontal: 4),
                          avatar: found.iconBytes != null
                              ? ClipOval(
                                  child: Image.memory(
                                    found.iconBytes!,
                                    width: 16,
                                    height: 16,
                                    fit: BoxFit.cover,
                                  ),
                                )
                              : null,
                          label: Text(found.name, style: const TextStyle(fontSize: 12)),
                          deleteIcon: const Icon(Icons.close, size: 14),
                          onDeleted: () {
                            HapticFeedback.mediumImpact();
                            final next = List<String>.from(s.packages)..remove(pkg);
                            _save(s.copyWith(packages: next));
                            _packages.text = next.join(', ');
                          },
                        );
                      }).toList(),
                    ),
                  ],
                  const SizedBox(height: 14),
                  Text(
                    'Quick Add Popular Apps:',
                    style: Theme.of(
                      context,
                    ).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 6,
                    children: [
                      _appChip('WhatsApp', 'com.whatsapp', s),
                      _appChip('Instagram', 'com.instagram.android', s),
                      _appChip('Telegram', 'org.telegram.messenger', s),
                      _appChip('Messages', 'com.google.android.apps.messaging', s),
                      _appChip('Gmail', 'com.google.android.gm', s),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Theme(
                    data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
                    child: ExpansionTile(
                      dense: true,
                      tilePadding: EdgeInsets.zero,
                      childrenPadding: EdgeInsets.zero,
                      title: const Text(
                        'Manual package name input (optional)',
                        style: TextStyle(fontSize: 12, color: Colors.grey),
                      ),
                      children: [
                        TextField(
                          controller: _packages,
                          decoration: InputDecoration(
                            labelText: 'Package names, comma separated',
                            hintText: 'com.whatsapp, com.google.android.gm',
                            suffixIcon: IconButton(
                              icon: const Icon(Icons.check_circle_outline),
                              onPressed: () {
                                _save(
                                  s.copyWith(
                                    packages: _packages.text
                                        .split(',')
                                        .map((e) => e.trim())
                                        .where((e) => e.isNotEmpty)
                                        .toList(),
                                  ),
                                );
                              },
                            ),
                            border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
                          ),
                          onSubmitted: (v) => _save(
                            s.copyWith(
                              packages: v
                                  .split(',')
                                  .map((e) => e.trim())
                                  .where((e) => e.isNotEmpty)
                                  .toList(),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),

        const SizedBox(height: 16),
        _advanced(s),

        const SizedBox(height: 20),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              onPressed: _busy ? null : _test,
              icon: const Icon(Icons.flash_on_rounded),
              label: const Text('Test flash'),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
            ),
            OutlinedButton.icon(
              onPressed: _busy ? null : _calibrate,
              icon: const Icon(Icons.tune_rounded),
              label: const Text('Calibrate'),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
            ),
            OutlinedButton.icon(
              onPressed: _busy ? null : _export,
              icon: const Icon(Icons.description_outlined),
              label: const Text('Export diagnostics'),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _sectionHeader(String title, IconData icon) {
    return Padding(
      padding: const EdgeInsets.only(left: 4),
      child: Row(
        children: [
          Icon(icon, size: 18, color: Theme.of(context).colorScheme.primary),
          const SizedBox(width: 8),
          Text(
            title,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.bold,
              letterSpacing: 0.5,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _speedInfoTile(String label, String value) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 8),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: 0.35),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(
          children: [
            Text(value, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
            const SizedBox(height: 2),
            Text(label, style: const TextStyle(fontSize: 10, color: Colors.grey)),
          ],
        ),
      ),
    );
  }

  Widget _appChip(String name, String pkg, FlashAlertsSettings s) {
    final has = s.packages.contains(pkg);
    return FilterChip(
      label: Text(name),
      selected: has,
      showCheckmark: false,
      avatar: has ? const Icon(Icons.check, size: 14) : const Icon(Icons.add, size: 14),
      onSelected: (selected) {
        HapticFeedback.mediumImpact();
        final list = List<String>.from(s.packages);
        if (selected) {
          list.add(pkg);
        } else {
          list.remove(pkg);
        }
        _save(s.copyWith(packages: list));
        _packages.text = list.join(', ');
      },
    );
  }

  Widget _advanced(FlashAlertsSettings s) {
    final p = s.notificationPattern;
    Widget slider(
      String label,
      int value,
      int min,
      int max,
      FlashAlertsSettings Function(int) apply, {
      int step = 10,
    }) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(label, style: const TextStyle(fontWeight: FontWeight.w500, fontSize: 13)),
              Text(
                '$value',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ],
          ),
          Slider(
            value: value.clamp(min, max).toDouble(),
            min: min.toDouble(),
            max: max.toDouble(),
            divisions: ((max - min) / step).round(),
            label: '$value',
            onChanged: (v) {
              final rounded = v.round();
              if (rounded != value) {
                HapticFeedback.selectionClick();
                setState(() => _settings = apply(rounded));
              }
            },
            onChangeEnd: (v) {
              HapticFeedback.lightImpact();
              _save(_settings);
            },
          ),
        ],
      );
    }

    return Card(
      child: ExpansionTile(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        collapsedShape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        leading: Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: Colors.blueGrey.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(10),
          ),
          child: const Icon(Icons.display_settings_rounded, color: Colors.blueGrey),
        ),
        title: const Text('Advanced Pulse Studio', style: TextStyle(fontWeight: FontWeight.bold)),
        subtitle: const Text('Fine-tune millisecond timings & cooldown'),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Divider(),
          const SizedBox(height: 8),
          slider(
            'Pulse on (ms)',
            p.onMs,
            20,
            300,
            (v) => s.copyWith(notificationPattern: p.copyWith(onMs: v)),
          ),
          slider(
            'Pulse off (ms)',
            p.offMs,
            20,
            300,
            (v) => s.copyWith(notificationPattern: p.copyWith(offMs: v)),
          ),
          slider(
            'Pulses per burst',
            p.pulsesPerBurst,
            1,
            10,
            (v) => s.copyWith(notificationPattern: p.copyWith(pulsesPerBurst: v)),
            step: 1,
          ),
          slider(
            'Pause between bursts (ms)',
            p.burstGapMs,
            0,
            2000,
            (v) => s.copyWith(notificationPattern: p.copyWith(burstGapMs: v)),
            step: 50,
          ),
          slider(
            'Bursts',
            p.bursts,
            1,
            10,
            (v) => s.copyWith(notificationPattern: p.copyWith(bursts: v)),
            step: 1,
          ),
          slider(
            'Cooldown (ms)',
            s.cooldownMs,
            0,
            10000,
            (v) => s.copyWith(cooldownMs: v),
            step: 250,
          ),
          if (_status.debuggable) ...[
            const Divider(),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text(
                'Dry run (debug builds only)',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              subtitle: const Text('Log toggles instead of using the LED'),
              value: s.dryRun,
              onChanged: (v) => _toggleSwitch(s.copyWith(dryRun: v)),
            ),
          ],
        ],
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.info_outline_rounded, size: 48, color: Theme.of(context).colorScheme.primary),
          const SizedBox(height: 16),
          Text(
            text,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 16, height: 1.4),
          ),
        ],
      ),
    ),
  );
}

class _Warning extends StatelessWidget {
  const _Warning(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Card(
    color: Theme.of(context).colorScheme.tertiaryContainer,
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    child: ListTile(
      leading: const Icon(Icons.warning_amber_rounded, color: Colors.orange),
      title: Text(text, style: const TextStyle(fontSize: 13, height: 1.4)),
    ),
  );
}

class _PrivacyCard extends StatelessWidget {
  const _PrivacyCard();
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: Colors.teal.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Icon(Icons.shield_outlined, color: Colors.teal, size: 22),
          ),
          const SizedBox(width: 12),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Your privacy', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                SizedBox(height: 3),
                Text(
                  'To flash when alerts arrive, this app needs Notification access. '
                  'It only looks at which app sent a notification and what type it is, never the title or message. '
                  'Nothing leaves your device.',
                  style: TextStyle(fontSize: 12, height: 1.35, color: Colors.grey),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.status,
    required this.onNotificationAccess,
    required this.onPhone,
    required this.onBattery,
    required this.onAppInfo,
  });

  final FlashAlertsStatus status;
  final Future<bool> Function() onNotificationAccess;
  final Future<void> Function() onPhone;
  final Future<bool> Function() onBattery;
  final Future<bool> Function() onAppInfo;

  @override
  Widget build(BuildContext context) {
    final ok = Colors.green;
    final bad = Theme.of(context).colorScheme.error;

    Widget row(
      bool done,
      String title, {
      String? sub,
      String? action,
      VoidCallback? onTap,
      bool optional = false,
    }) => ListTile(
      dense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 0),
      leading: Icon(
        done ? Icons.check_circle : (optional ? Icons.info_outline : Icons.error_outline),
        color: done ? ok : (optional ? Colors.amber : bad),
        size: 20,
      ),
      title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5)),
      subtitle: sub == null ? null : Text(sub, style: const TextStyle(fontSize: 11.5)),
      trailing: done || action == null ? null : TextButton(onPressed: onTap, child: Text(action)),
    );

    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              child: Row(
                children: [
                  Icon(
                    Icons.checklist_rounded,
                    size: 18,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'System Readiness Checklist',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 13,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            row(status.hasFlash, 'Flash hardware', sub: status.hasFlash ? null : 'No flash found'),
            row(
              status.listenerEnabled,
              'Notification access',
              sub: status.listenerEnabled
                  ? (status.listenerConnected
                        ? 'Active'
                        : 'Enabled, waiting for the system to connect')
                  : 'Required for background flashing',
              action: 'Open',
              onTap: onNotificationAccess,
            ),
            if (status.needsRestrictedSettingsHint)
              row(
                false,
                'Setting greyed out?',
                sub:
                    'Installed outside the Play Store: open App info, tap ⋮ and choose "Allow restricted settings", then try again.',
                action: 'App info',
                onTap: onAppInfo,
                optional: true,
              ),
            row(
              !status.batteryOptimized,
              'Battery: unrestricted',
              sub: status.batteryOptimized
                  ? 'Set this app to "Unrestricted"${_oem(status.manufacturer)}'
                  : null,
              action: 'Settings',
              onTap: onBattery,
              optional: true,
            ),
          ],
        ),
      ),
    );
  }

  static String _oem(String m) {
    final v = m.toLowerCase();
    if (v.contains('xiaomi') || v.contains('redmi') || v.contains('poco'))
      return ' and enable Autostart.';
    if (v.contains('oppo') || v.contains('realme') || v.contains('oneplus'))
      return ' and allow background activity.';
    if (v.contains('vivo')) return ' and allow high background power use.';
    if (v.contains('huawei') || v.contains('honor')) return ' and set App launch to manual.';
    if (v.contains('samsung')) return ' and remove it from "sleeping apps".';
    return '.';
  }
}

class _AppPickerSheet extends StatefulWidget {
  const _AppPickerSheet({required this.initialSelected, required this.apps});

  final Set<String> initialSelected;
  final List<InstalledAppInfo> apps;

  @override
  State<_AppPickerSheet> createState() => _AppPickerSheetState();
}

class _AppPickerSheetState extends State<_AppPickerSheet> {
  late final Set<String> _selected = Set<String>.from(widget.initialSelected);
  String _query = '';
  String _filter = 'all'; // 'all', 'user', 'selected'

  @override
  Widget build(BuildContext context) {
    final filtered = widget.apps.where((a) {
      if (_filter == 'user' && a.isSystemApp) return false;
      if (_filter == 'selected' && !_selected.contains(a.packageName)) return false;
      if (_query.trim().isEmpty) return true;
      final q = _query.toLowerCase();
      return a.name.toLowerCase().contains(q) || a.packageName.toLowerCase().contains(q);
    }).toList();

    return DraggableScrollableSheet(
      initialChildSize: 0.88,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      builder: (ctx, scroll) => Container(
        decoration: BoxDecoration(
          color: Theme.of(context).scaffoldBackgroundColor,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: Column(
          children: [
            const SizedBox(height: 10),
            Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.grey.withValues(alpha: 0.3),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Row(
                children: [
                  const Icon(Icons.apps_rounded, size: 22),
                  const SizedBox(width: 10),
                  Text(
                    'Select Apps for Alerts',
                    style: Theme.of(
                      context,
                    ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                  ),
                  const Spacer(),
                  IconButton(
                    icon: const Icon(Icons.close_rounded),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              child: TextField(
                decoration: InputDecoration(
                  hintText: 'Search installed apps...',
                  prefixIcon: const Icon(Icons.search, size: 20),
                  suffixIcon: _query.isNotEmpty
                      ? IconButton(
                          icon: const Icon(Icons.clear, size: 18),
                          onPressed: () => setState(() => _query = ''),
                        )
                      : null,
                  filled: true,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide.none,
                  ),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                ),
                onChanged: (v) => setState(() => _query = v),
              ),
            ),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                children: [
                  FilterChip(
                    label: Text('All (${widget.apps.length})'),
                    selected: _filter == 'all',
                    onSelected: (_) {
                      HapticFeedback.selectionClick();
                      setState(() => _filter = 'all');
                    },
                  ),
                  const SizedBox(width: 8),
                  FilterChip(
                    label: const Text('User Apps'),
                    selected: _filter == 'user',
                    onSelected: (_) {
                      HapticFeedback.selectionClick();
                      setState(() => _filter = 'user');
                    },
                  ),
                  const SizedBox(width: 8),
                  FilterChip(
                    label: Text('Selected (${_selected.length})'),
                    selected: _filter == 'selected',
                    onSelected: (_) {
                      HapticFeedback.selectionClick();
                      setState(() => _filter = 'selected');
                    },
                  ),
                  const SizedBox(width: 12),
                  TextButton(
                    onPressed: () {
                      HapticFeedback.mediumImpact();
                      setState(() {
                        for (final a in filtered) {
                          _selected.add(a.packageName);
                        }
                      });
                    },
                    child: const Text('Select all', style: TextStyle(fontSize: 12)),
                  ),
                  TextButton(
                    onPressed: () {
                      HapticFeedback.mediumImpact();
                      setState(() => _selected.clear());
                    },
                    child: const Text('Clear all', style: TextStyle(fontSize: 12)),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: filtered.isEmpty
                  ? Center(
                      child: Text(
                        widget.apps.isEmpty
                            ? 'Loading installed apps…'
                            : 'No apps match your search',
                        style: const TextStyle(color: Colors.grey),
                      ),
                    )
                  : ListView.builder(
                      controller: scroll,
                      itemCount: filtered.length,
                      itemBuilder: (ctx, i) {
                        final app = filtered[i];
                        final isSel = _selected.contains(app.packageName);
                        return CheckboxListTile(
                          value: isSel,
                          secondary: _buildAppIcon(app),
                          title: Text(
                            app.name,
                            style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
                          ),
                          subtitle: Text(
                            app.packageName,
                            style: const TextStyle(fontSize: 11, color: Colors.grey),
                          ),
                          onChanged: (val) {
                            HapticFeedback.mediumImpact();
                            setState(() {
                              if (val == true) {
                                _selected.add(app.packageName);
                              } else {
                                _selected.remove(app.packageName);
                              }
                            });
                          },
                        );
                      },
                    ),
            ),
            Container(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 24),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surface,
                border: Border(
                  top: BorderSide(color: Theme.of(context).dividerColor.withValues(alpha: 0.1)),
                ),
              ),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  onPressed: () {
                    HapticFeedback.mediumImpact();
                    Navigator.pop(context, _selected);
                  },
                  child: Text(
                    'Done (${_selected.length} Apps Selected)',
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppIcon(InstalledAppInfo app) {
    if (app.iconBytes != null && app.iconBytes!.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: Image.memory(
          app.iconBytes!,
          width: 38,
          height: 38,
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => _avatar(app.name),
        ),
      );
    }
    return _avatar(app.name);
  }

  Widget _avatar(String name) {
    final letter = name.trim().isNotEmpty ? name.trim()[0].toUpperCase() : '?';
    return Container(
      width: 38,
      height: 38,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: Colors.primaries[name.hashCode.abs() % Colors.primaries.length].withValues(
          alpha: 0.2,
        ),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        letter,
        style: TextStyle(
          fontWeight: FontWeight.bold,
          color: Colors.primaries[name.hashCode.abs() % Colors.primaries.length],
        ),
      ),
    );
  }
}
