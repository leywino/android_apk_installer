package com.leywin.android_apk_installer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

class AndroidApkInstallerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
  private lateinit var channel: MethodChannel
  private lateinit var eventChannel: EventChannel
  private lateinit var context: Context
  private lateinit var installResult: Result
  private var activity: Activity? = null
  private var events: EventChannel.EventSink? = null

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "android_apk_installer")
    channel.setMethodCallHandler(this)
    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "app_install_uninstall_events")
    eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
      override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        this@AndroidApkInstallerPlugin.events = events
        registerInstallUninstallReceiver()
      }

      override fun onCancel(arguments: Any?) {
        unregisterInstallUninstallReceiver()
      }
    })
    context = flutterPluginBinding.applicationContext

    // Register the broadcast receiver for installation completion
    val filter = IntentFilter()
    filter.addAction(PackageInstaller.ACTION_SESSION_COMMITTED)
    context.registerReceiver(installReceiver, filter)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    context.unregisterReceiver(installReceiver)
    unregisterInstallUninstallReceiver()
    channel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivity() {
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    activity = null
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "installApk" -> {
        val apkPath: String? = call.argument("apkPath")
        if (apkPath != null) {
          try {
            installResult = result
            installApk(apkPath)
          } catch (e: IOException) {
            result.error("INSTALLATION_FAILED", "Installation failed: ${e.message}", null)
          }
        } else {
          result.error("INVALID_ARGUMENT", "APK path is required", null)
        }
      }
      "uninstallApk" -> {
        val packageName: String? = call.argument("packageName")
        val askPrompt: Boolean? = call.argument("askPrompt")
        if (packageName != null) {
          try {
            uninstallApk(packageName, askPrompt!!, result)
          } catch (e: Exception) {
            result.error("UNINSTALLATION_FAILED", "Uninstallation failed: ${e.message}", null)
          }
        } else {
          result.error("INVALID_ARGUMENT", "Package name is required", null)
        }
      }
      "setFullScreenMode" -> {
        val enable: Boolean? = call.argument("enable")
        if (enable != null) {
          setFullScreenMode(enable)
          result.success(null)
        } else {
          result.error("INVALID_ARGUMENT", "Enable parameter is required", null)
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

      // Create a PendingIntent for the completion event
      val intent = Intent(context, installReceiver::class.java)
      intent.action = PackageInstaller.ACTION_SESSION_COMMITTED
      intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)

      val pendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      session.commit(pendingIntent.intentSender)
      session.close()

    } catch (e: IOException) {
      e.printStackTrace()
      throw e
    }
  }

  @SuppressLint("MissingPermission")
  private fun uninstallApk(packageName: String, askPrompt: Boolean, result: Result) {
    Log.d("Uninstall", "Attempting to uninstall $packageName")
    val packageInstaller = context.packageManager.packageInstaller

    try {
      if (askPrompt) {
        uninstallApkWithIntent(packageName)
        result.success("Prompting for uninstallation")
        return
      }
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
      uninstallApkWithIntent(packageName)
      result.success("Uninstallation fallback initiated using Intent")
    } catch (e: Exception) {
      Log.e("UninstallError", "Unexpected error during uninstallation: ${e.message}")
      e.printStackTrace()
      uninstallApkWithIntent(packageName)
      result.success("Uninstallation fallback initiated using Intent")
    }
  }

  private val installReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

      if (status == PackageInstaller.STATUS_SUCCESS) {
        installResult.success("Installation successful")
      } else {
        val errorMsg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        installResult.error("INSTALLATION_FAILED", "Installation failed: $errorMsg", null)
      }
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

  @Suppress("DEPRECATION")
  private fun setFullScreenMode(enable: Boolean) {
    activity?.let {
      if (enable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          // For Android 11 (API 30) and above
          val controller = it.window.insetsController
          if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          } else {
            Log.e("setFullScreenMode", "InsetsController is null.")
          }
        } else {
          // For Android versions below 11
          @Suppress("DEPRECATION")
          it.window.decorView.systemUiVisibility = (
                  View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                          or View.SYSTEM_UI_FLAG_FULLSCREEN
                          or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                          or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                          or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                          or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  )
        }
      } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          // For Android 11 (API 30) and above
          val controller = it.window.insetsController
          controller?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
          // For Android versions below 11
          @Suppress("DEPRECATION")
          it.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
      }
    } ?: Log.e("setFullScreenMode", "Activity is null, unable to set full screen mode.")
  }

  // Register receiver for app install/uninstall events
  private fun registerInstallUninstallReceiver() {
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addDataScheme("package")
    }
    context.registerReceiver(installUninstallReceiver, filter)
  }

  // Unregister receiver for app install/uninstall events
  private fun unregisterInstallUninstallReceiver() {
    context.unregisterReceiver(installUninstallReceiver)
  }

  // BroadcastReceiver to handle app installation and uninstallation events
  private val installUninstallReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val action = intent?.action
      val packageName = intent?.data?.encodedSchemeSpecificPart
      events?.success(mapOf("action" to action, "packageName" to packageName))
    }
  }
}
