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
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

class AndroidApkInstallerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context
    private var installResult: MethodChannel.Result? = null
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
        val filter = IntentFilter().apply {
            addAction(PackageInstaller.ACTION_SESSION_COMMITTED)
        }
        context.registerReceiver(installReceiver, filter)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        try {
            context.unregisterReceiver(installReceiver)
        } catch (e: IllegalArgumentException) {
            Log.e("AndroidApkInstaller", "Receiver already unregistered.")
        }
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

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
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
                val askPrompt: Boolean = call.argument("askPrompt") ?: false
                if (packageName != null) {
                    try {
                        uninstallApk(packageName, askPrompt, result)
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

            val intent = Intent(context, installReceiver::class.java).apply {
                action = PackageInstaller.ACTION_SESSION_COMMITTED
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }

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
            installResult?.error("INSTALLATION_FAILED", "Installation failed: ${e.message}", null)
        }
    }

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val result = installResult ?: return
            val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

            if (status == PackageInstaller.STATUS_SUCCESS) {
                result.success("Installation successful")
            } else {
                val errorMsg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                result.error("INSTALLATION_FAILED", "Installation failed: $errorMsg", null)
            }
            installResult = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun uninstallApk(packageName: String, askPrompt: Boolean, result: MethodChannel.Result) {
        Log.d("Uninstall", "Attempting to uninstall $packageName")
        val packageInstaller = context.packageManager.packageInstaller

        try {
            if (askPrompt) {
                uninstallApkWithIntent(packageName)
                result.success("Prompting for uninstallation")
                return
            }
            packageInstaller.uninstall(
                packageName, PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent("android.intent.action.MAIN"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).intentSender
            )
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

    private fun uninstallApkWithIntent(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = Uri.parse("package:$packageName")
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UninstallError", "Error uninstalling package using Intent: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun registerInstallUninstallReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(installUninstallReceiver, filter)
    }

    private fun unregisterInstallUninstallReceiver() {
        try {
            context.unregisterReceiver(installUninstallReceiver)
        } catch (e: IllegalArgumentException) {
            Log.e("UnregisterReceiver", "Receiver not registered: ${e.message}")
        }
    }

    private val installUninstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val packageName = intent?.data?.encodedSchemeSpecificPart
            events?.success(mapOf("action" to action, "packageName" to packageName))
        }
    }
}
