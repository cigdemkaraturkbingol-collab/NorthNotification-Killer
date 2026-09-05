package com.kuzey.notificationkiller

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var remoteService: IRemoteService? = null
    private val shizukuPermissionRequestCode = 1001

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, PrivilegedService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("notification_service")
            .debuggable(false)
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = IRemoteService.Stub.asInterface(service)
            sendStatus("ready", "Shizuku hazır")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            sendStatus("error", "Shizuku servisi bağlantısı kesildi")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkPermissionAndBind()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == shizukuPermissionRequestCode) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindUserService()
                } else {
                    sendStatus("error", "Shizuku izni verilmedi")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun checkPermissionAndBind() {
        try {
            if (Shizuku.isPreV11()) {
                sendStatus("error", "Shizuku sürümü çok eski")
                return
            }

            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindUserService()
                Shizuku.shouldShowRequestPermissionRationale() ->
                    sendStatus("error", "Shizuku izni reddedilmiş. Shizuku içinden tekrar izin ver.")
                else -> Shizuku.requestPermission(shizukuPermissionRequestCode)
            }
        } catch (e: Throwable) {
            sendStatus("error", "Shizuku çalışmıyor olabilir: ${e.message}")
        }
    }

    private fun bindUserService() {
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Throwable) {
            sendStatus("error", "Servis başlatılamadı: ${e.message}")
        }
    }

    private fun shell(command: String): String {
        return remoteService?.runCommand(command)
            ?: "ERR: Shizuku servisi hazır değil"
    }

    private fun packageLabel(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    private fun isSystemPackage(pkg: String): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun listPackages(includeSystem: Boolean): JSONArray {
        val cmd = if (includeSystem) "pm list packages" else "pm list packages -3"
        val raw = shell(cmd)
        val arr = JSONArray()

        raw.lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() && it != packageName }
            .distinct()
            .sortedBy { packageLabel(it).lowercase() }
            .forEach { pkg ->
                val o = JSONObject()
                o.put("packageName", pkg)
                o.put("label", packageLabel(pkg))
                o.put("system", isSystemPackage(pkg))
                arr.put(o)
            }

        return arr
    }

    private fun setNotificationPermission(pkg: String, enabled: Boolean): String {
        if (pkg == packageName) return "SKIP:self"
        val action = if (enabled) "grant" else "revoke"
        return shell("pm $action '$pkg' android.permission.POST_NOTIFICATIONS")
    }

    private fun bulkSet(enabled: Boolean, includeSystem: Boolean): JSONObject {
        val packages = listPackages(includeSystem)
        var ok = 0
        var failed = 0
        val failures = JSONArray()

        for (i in 0 until packages.length()) {
            val pkg = packages.getJSONObject(i).getString("packageName")
            val result = setNotificationPermission(pkg, enabled)
            if (result.startsWith("ERR:") || result.contains("Exception", ignoreCase = true)) {
                failed++
                failures.put(JSONObject().put("packageName", pkg).put("result", result))
            } else {
                ok++
            }
        }

        return JSONObject()
            .put("ok", ok)
            .put("failed", failed)
            .put("failures", failures)
    }

    private fun sendStatus(type: String, message: String) {
        if (!::webView.isInitialized) return
        val safeType = JSONObject.quote(type)
        val safeMessage = JSONObject.quote(message)
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onNativeStatus && window.onNativeStatus($safeType,$safeMessage);",
                null
            )
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun requestShizuku() {
            runOnUiThread { checkPermissionAndBind() }
        }

        @JavascriptInterface
        fun getStatus(): String {
            val obj = JSONObject()
            return try {
                obj.put("binderAlive", Shizuku.pingBinder())
                obj.put("permission", Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                obj.put("serviceReady", remoteService != null)
                obj.toString()
            } catch (e: Throwable) {
                obj.put("binderAlive", false)
                obj.put("permission", false)
                obj.put("serviceReady", false)
                obj.put("error", e.message)
                obj.toString()
            }
        }

        @JavascriptInterface
        fun getApps(includeSystem: Boolean): String {
            return listPackages(includeSystem).toString()
        }

        @JavascriptInterface
        fun setNotifications(packageName: String, enabled: Boolean): String {
            return setNotificationPermission(packageName, enabled)
        }

        @JavascriptInterface
        fun bulkSet(enabled: Boolean, includeSystem: Boolean): String {
            return bulkSet(enabled, includeSystem).toString()
        }
    }
}
