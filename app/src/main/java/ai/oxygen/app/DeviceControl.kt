package ai.oxygen.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Apps OXYGEN will never read or act on, no matter what asks it to: banking, payments, wallets,
 * UPI, and one-time-code senders. Checked by package name AND by the app's visible name, since
 * package names vary. This is enforced for BOTH reading the screen and opening an app.
 */
private val BLOCKED_KEYWORDS = listOf(
    "bank", "pay", "wallet", "upi", "neft", "rtgs", "otp", "netbanking", "creditcard",
    "debitcard", "paytm", "phonepe", "gpay", "googlepay", "amazonpay", "mobikwik", "freecharge",
)

private fun isBlocked(packageName: String, label: String): Boolean {
    val hay = (packageName + " " + label).lowercase()
    return BLOCKED_KEYWORDS.any { hay.contains(it) }
}

/**
 * The Accessibility Service: Android calls this automatically whenever the screen changes, so it
 * can track which app is in front. It does NOT act by itself — it only answers when the bridge
 * below asks it to, which only happens when you give OXYGEN a command.
 */
class OxygenAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: WeakReference<OxygenAccessibilityService>? = null
        @Volatile var currentPackage: String = ""
            private set

        fun isRunning(): Boolean = instance?.get() != null

        fun dumpScreen(context: Context): JSONObject {
            val svc = instance?.get() ?: return JSONObject().put("error", "not-running")
            val label = appLabel(context, currentPackage)
            if (isBlocked(currentPackage, label)) {
                return JSONObject().put("error", "blocked").put("package", currentPackage).put("label", label)
            }
            val root = svc.rootInActiveWindow
                ?: return JSONObject().put("error", "no-content").put("package", currentPackage).put("label", label)
            val nodes = JSONArray()
            val budget = intArrayOf(400)   // cap how much of the screen we read, for speed and safety
            collect(root, nodes, budget)
            root.recycle()
            return JSONObject().put("package", currentPackage).put("label", label).put("nodes", nodes)
        }

        private fun collect(node: AccessibilityNodeInfo, out: JSONArray, budget: IntArray) {
            if (budget[0] <= 0) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || node.isClickable) {
                val o = JSONObject()
                if (!text.isNullOrEmpty()) o.put("text", text.take(200))
                if (!desc.isNullOrEmpty()) o.put("desc", desc.take(200))
                node.viewIdResourceName?.let { o.put("id", it.substringAfterLast('/')) }
                o.put("clickable", node.isClickable)
                o.put("class", node.className?.toString()?.substringAfterLast('.') ?: "")
                out.put(o)
                budget[0]--
            }
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) break
                val child = node.getChild(i) ?: continue
                collect(child, out, budget)
                child.recycle()
            }
        }

        private fun appLabel(context: Context, packageName: String): String {
            if (packageName.isEmpty()) return ""
            return try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Exception) {
                packageName
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrEmpty()) currentPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

/** What the OXYGEN web page can ask for. Every method here only runs when the page calls it — i.e. when you asked OXYGEN to do something. */
class DeviceBridge(private val activity: Activity) {

    @JavascriptInterface
    fun isEnabled(): Boolean = OxygenAccessibilityService.isRunning()

    @JavascriptInterface
    fun requestEnable() {
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    /** Current app on screen: {"package":"...","label":"...","blocked":true/false} */
    @JavascriptInterface
    fun currentApp(): String {
        val pkg = OxygenAccessibilityService.currentPackage
        val label = try {
            val pm = activity.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        return JSONObject().put("package", pkg).put("label", label).put("blocked", isBlocked(pkg, label)).toString()
    }

    /** Reads what's on screen right now. Returns JSON; see OxygenAccessibilityService.dumpScreen. */
    @JavascriptInterface
    fun readScreen(): String {
        if (!OxygenAccessibilityService.isRunning()) return JSONObject().put("error", "disabled").toString()
        return OxygenAccessibilityService.dumpScreen(activity).toString()
    }

    /** Finds an installed, launchable app by name (fuzzy match on its visible label). JSON: {"package":...,"label":...} or {"error":"not-found"} */
    @JavascriptInterface
    fun findApp(query: String): String {
        val pm = activity.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            emptyList()
        }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return JSONObject().put("error", "not-found").toString()
        var best: Pair<String, String>? = null
        var bestScore = Int.MAX_VALUE
        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            val l = label.lowercase()
            val score = when {
                l == q -> 0
                l.startsWith(q) -> 1
                l.contains(q) -> 2
                else -> continue
            }
            if (score < bestScore) { bestScore = score; best = pkg to label }
        }
        val found = best ?: return JSONObject().put("error", "not-found").toString()
        if (isBlocked(found.first, found.second)) {
            return JSONObject().put("error", "blocked").put("label", found.second).toString()
        }
        return JSONObject().put("package", found.first).put("label", found.second).toString()
    }

    /**
     * Opens an app by its package name (use findApp first to resolve a spoken name to a package).
     * Refuses anything on the blocklist even if asked directly by its package name.
     */
    @JavascriptInterface
    fun openApp(packageName: String): Boolean {
        return try {
            val label = try {
                val pm = activity.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Exception) {
                packageName
            }
            if (isBlocked(packageName, label)) return false
            val intent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
