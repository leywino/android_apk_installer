import 'package:flutter/services.dart';

class AndroidApkInstaller {
  static const MethodChannel _channel = MethodChannel('android_apk_installer');

  /// Install an APK from the specified file path
  static Future<String?> installApk(String apkPath) async {
    try {
      final String? result = await _channel.invokeMethod('installApk', {'apkPath': apkPath});
      return result;
    } on PlatformException catch (e) {
      return "Failed to install APK: '${e.message}'.";
    }
  }

  /// Uninstall an APK by its package name
  static Future<String?> uninstallApk(String packageName) async {
    try {
      final String? result = await _channel.invokeMethod('uninstallApk', {'packageName': packageName});
      return result;
    } on PlatformException catch (e) {
      return "Failed to uninstall APK: '${e.message}'.";
    }
  }
}
