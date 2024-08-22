package com.leywin.android_apk_installer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

class AndroidApkInstallerPlugin: FlutterPlugin, MethodCallHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel
  private lateinit var context: Context

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "android_apk_installer")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "installApk" -> {
        val apkPath: String? = call.argument("apkPath")
        if (apkPath != null) {
          installApk(apkPath)
          result.success("Installation started")
        } else {
          result.error("INVALID_ARGUMENT", "APK path is required", null)
        }
      }
      "uninstallApk" -> {
        val packageName: String? = call.argument("packageName")
        if (packageName != null) {
          uninstallApk(packageName)
          result.success("Uninstallation started")
        } else {
          result.error("INVALID_ARGUMENT", "Package name is required", null)
        }
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  private fun installApk(apkPath: String) {
    val packageInstaller = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val file = File(apkPath)
    params.setSize(file.length())

    try {
      val sessionId = packageInstaller.createSession(params)
      val session = packageInstaller.openSession(sessionId)
      val out: OutputStream = session.openWrite("package", 0, -1)
      val inputStream = FileInputStream(file)
      inputStream.copyTo(out)
      session.fsync(out)
      inputStream.close()
      out.close()

      session.commit(PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent("android.intent.action.MAIN"),
        0
      ).intentSender)
      session.close()
    } catch (e: IOException) {
      e.printStackTrace()
    }
  }

  @SuppressLint("MissingPermission")
  private fun uninstallApk(packageName: String) {
    val packageInstaller = context.packageManager.packageInstaller
    packageInstaller.uninstall(packageName, PendingIntent.getBroadcast(
      context,
      0,
      Intent("android.intent.action.MAIN"),
      0
    ).intentSender)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
}
