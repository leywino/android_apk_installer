import 'package:flutter/services.dart';

class AndroidApkInstaller {
  static const MethodChannel _channel = MethodChannel('android_apk_installer');

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
}
