package com.leywin.android_apk_installer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
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
  private lateinit var channel: MethodChannel
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
          try {
            installApk(apkPath)
            result.success("Installation started")
          } catch (e: IOException) {
            result.error("INSTALLATION_FAILED", "Installation failed: ${e.message}", null)
          }
        } else {
          result.error("INVALID_ARGUMENT", "APK path is required", null)
        }
      }
      "uninstallApk" -> {
        val packageName: String? = call.argument("packageName")
        if (packageName != null) {
          try {
            uninstallApk(packageName, result)
          } catch (e: Exception) {
            result.error("UNINSTALLATION_FAILED", "Uninstallation failed: ${e.message}", null)
          }
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
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      ).intentSender)
      session.close()
    } catch (e: IOException) {
      e.printStackTrace()
      throw e
    }
  }

  @SuppressLint("MissingPermission")
  private fun uninstallApk(packageName: String, result: Result) {
    val packageInstaller = context.packageManager.packageInstaller

    try {
      // Try to uninstall using PackageInstaller
      packageInstaller.uninstall(packageName, PendingIntent.getBroadcast(
        context,
        0,
        Intent("android.intent.action.MAIN"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      ).intentSender)

      Log.d("Uninstall", "Uninstallation of $packageName initiated.")
      result.success("Uninstallation initiated")
    } catch (e: IllegalArgumentException) {
      Log.e("UninstallError", "Package not found: $packageName")
      e.printStackTrace()
      // Fall back to uninstall using Intent
      uninstallApkWithIntent(packageName)
      result.success("Uninstallation fallback initiated using Intent")
    } catch (e: Exception) {
      Log.e("UninstallError", "Unexpected error during uninstallation: ${e.message}")
      e.printStackTrace()
      // Fall back to uninstall using Intent in case of other exceptions
      uninstallApkWithIntent(packageName)
      result.success("Uninstallation fallback initiated using Intent")
    }
  }

  @Suppress("DEPRECATION")
  private fun uninstallApkWithIntent(packageName: String) {
    try {
      val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
      intent.data = Uri.parse("package:$packageName")
      intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(intent)
      Log.d("Uninstall", "Fallback to Intent for uninstallation of $packageName")
    } catch (e: Exception) {
      Log.e("UninstallError", "Error uninstalling package using Intent: ${e.message}")
      e.printStackTrace()
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
}
