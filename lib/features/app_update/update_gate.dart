import 'package:flutter/material.dart';

import 'app_updater.dart';

/// Checks for an update once on launch and offers it in a dialog.
class UpdateGate extends StatefulWidget {
  const UpdateGate({super.key, required this.child, this.updater});
  final Widget child;
  final AppUpdater? updater;

  @override
  State<UpdateGate> createState() => _UpdateGateState();
}

class _UpdateGateState extends State<UpdateGate> {
  late final AppUpdater _updater = widget.updater ?? AppUpdater();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final info = await _updater.check();
      if (info != null && mounted) _offer(info);
    });
  }

  void _offer(UpdateInfo info) {
    showDialog<void>(
      context: context,
      barrierDismissible: !info.mandatory,
      builder: (_) => PopScope(
        canPop: !info.mandatory,
        child: _UpdateDialog(info: info, updater: _updater),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

class _UpdateDialog extends StatefulWidget {
  const _UpdateDialog({required this.info, required this.updater});
  final UpdateInfo info;
  final AppUpdater updater;

  @override
  State<_UpdateDialog> createState() => _UpdateDialogState();
}

class _UpdateDialogState extends State<_UpdateDialog> {
  double? _progress;
  String? _error;

  Future<void> _start() async {
    setState(() { _progress = 0; _error = null; });
    final r = await widget.updater.downloadAndInstall(widget.info, (p) {
      if (mounted) setState(() => _progress = p);
    });
    if (!mounted) return;
    setState(() {
      _progress = null;
      _error = switch (r) {
        InstallResult.started => null,
        InstallResult.needsPermission => 'Allow "Install unknown apps" for Blinkr, then tap Update again.',
        InstallResult.badHash => 'Downloaded file is corrupted. Please try again.',
        InstallResult.failed => 'Download failed. Check your connection and try again.',
      };
    });
    if (r == InstallResult.started && !widget.info.mandatory && mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final i = widget.info;
    final busy = _progress != null;
    return AlertDialog(
      title: Text('Update available (${i.versionName})'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (i.notes.isNotEmpty) Text(i.notes),
          if (busy) ...[
            const SizedBox(height: 16),
            LinearProgressIndicator(value: _progress == 0 ? null : _progress),
          ],
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
        ],
      ),
      actions: [
        if (!i.mandatory && !busy) TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Later')),
        FilledButton(onPressed: busy ? null : _start, child: const Text('Update')),
      ],
    );
  }
}
