import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

/// Where the update manifest lives. Host a small JSON file anywhere with a stable direct URL
/// (Firebase Hosting / Storage, GitHub raw, your server). Leave empty to disable update checks.
///
/// {
///   "versionCode": 12,            // must be > installed versionCode (the number after + in pubspec)
///   "versionName": "1.0.3",
///   "apkUrl": "https://.../blinkr-1.0.3.apk",   // direct download, NOT a Google Drive share page
///   "sha256": "<hex sha256 of the apk>",
///   "notes": "What's new",
///   "mandatory": false
/// }
const String kUpdateManifestUrl = 'https://blinkr-updates.web.app/version.json';

class UpdateInfo {
  UpdateInfo({required this.versionCode, required this.versionName, required this.apkUrl,
      this.sha256, this.notes = '', this.mandatory = false});

  final int versionCode;
  final String versionName;
  final String apkUrl;
  final String? sha256;
  final String notes;
  final bool mandatory;

  static UpdateInfo? fromJson(Object? j) {
    if (j is! Map) return null;
    final code = j['versionCode'];
    final url = j['apkUrl'];
    if (code is! int || url is! String || !url.startsWith('https://')) return null;
    return UpdateInfo(
      versionCode: code,
      versionName: (j['versionName'] ?? '$code').toString(),
      apkUrl: url,
      sha256: j['sha256'] as String?,
      notes: (j['notes'] ?? '').toString(),
      mandatory: j['mandatory'] == true,
    );
  }
}

enum InstallResult { started, needsPermission, badHash, failed }

class AppUpdater {
  AppUpdater({MethodChannel? channel, this.manifestUrl = kUpdateManifestUrl})
      : _channel = channel ?? const MethodChannel('com.example.android_flashlight/app_update');

  final MethodChannel _channel;
  final String manifestUrl;

  /// Returns the available update, or null (up to date, disabled, offline or bad manifest).
  Future<UpdateInfo?> check() async {
    if (manifestUrl.isEmpty) return null;
    try {
      final current = await _channel.invokeMethod<int>('getVersionCode') ?? 0;
      final client = HttpClient()..connectionTimeout = const Duration(seconds: 8);
      try {
        final req = await client.getUrl(Uri.parse(manifestUrl));
        final res = await req.close().timeout(const Duration(seconds: 10));
        if (res.statusCode != 200) return null;
        final body = await res.transform(utf8.decoder).join();
        final info = UpdateInfo.fromJson(jsonDecode(body));
        return info != null && info.versionCode > current ? info : null;
      } finally {
        client.close(force: true);
      }
    } catch (_) {
      return null;
    }
  }

  /// Downloads the APK (reporting 0..1 progress) and opens the system installer.
  Future<InstallResult> downloadAndInstall(UpdateInfo info, void Function(double) onProgress) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 15);
    try {
      final dir = await _channel.invokeMethod<String>('updatesDir');
      final file = File('$dir/update-${info.versionCode}.apk');
      if (file.existsSync()) file.deleteSync();
      final req = await client.getUrl(Uri.parse(info.apkUrl));
      final res = await req.close();
      if (res.statusCode != 200) return InstallResult.failed;
      final total = res.contentLength;
      var got = 0;
      final sink = file.openWrite();
      try {
        await for (final chunk in res) {
          sink.add(chunk);
          got += chunk.length;
          if (total > 0) onProgress(got / total);
        }
      } finally {
        await sink.close();
      }
      final r = await _channel.invokeMethod<String>('install', {'path': file.path, 'sha256': info.sha256});
      return switch (r) {
        'OK' => InstallResult.started,
        'NEEDS_PERMISSION' => InstallResult.needsPermission,
        'BAD_HASH' => InstallResult.badHash,
        _ => InstallResult.failed,
      };
    } catch (_) {
      return InstallResult.failed;
    } finally {
      client.close(force: true);
    }
  }
}
