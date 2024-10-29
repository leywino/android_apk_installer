import 'package:flutter/services.dart';

class AndroidApkInstaller {
  static const MethodChannel _channel = MethodChannel('android_apk_installer');
  static const eventChannel = EventChannel('app_install_uninstall_events');

  /// Install an APK from the specified file path.
  /// Returns a message indicating the result of the installation.
  static Future<String> installApk(String apkPath) async {
    try {
      final String? result =
          await _channel.invokeMethod('installApk', {'apkPath': apkPath});
      return result ?? "Installation successful";
    } on PlatformException catch (e) {
      return "Failed to install APK: '${e.message}'.";
    }
  }

  /// Uninstall an APK by its package name.
  /// Returns a message indicating the result of the uninstallation.
  static Future<String> uninstallApk(String packageName,
      {bool askPrompt = false}) async {
    try {
      final String? result = await _channel.invokeMethod(
        'uninstallApk',
        {
          'packageName': packageName,
          'askPrompt': askPrompt,
        },
      );
      return result ?? "Uninstallation successful";
    } on PlatformException catch (e) {
      return "Failed to uninstall APK: '${e.message}'.";
    }
  }

  /// Toggle full-screen mode.
  /// Pass [enable] as `true` to enable full-screen mode,
  /// or `false` to disable it and restore the system UI.
  static Future<void> setFullScreenMode(bool enable) async {
    try {
      await _channel.invokeMethod('setFullScreenMode', {'enable': enable});
    } on PlatformException catch (e) {
      print("Failed to set full-screen mode: '${e.message}'.");
    }
  }

  
  static Stream<Map<String, dynamic>> get installUninstallEvents async* {
    await for (var event in eventChannel.receiveBroadcastStream()) {
      if (event is Map) {
        yield {
          "action": event["action"] as String?,
          "packageName": event["packageName"] as String?
        };
      }
    }
  }
}
