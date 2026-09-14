package com.homehotspot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HotspotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogManager.log(
            "ACCESSIBILITY_CONNECTED",
            LogStatus.INFO,
            "HotspotAccessibilityService connected and active"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutomationActive) return

        val rootNode = rootInActiveWindow ?: return
        handleSettingsAutomation(rootNode)
    }

    override fun onInterrupt() {
        LogManager.log("ACCESSIBILITY_INTERRUPTED", LogStatus.INFO, "HotspotAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isAutomationActive = false
    }

    private fun handleSettingsAutomation(root: AccessibilityNodeInfo) {
        synchronized(lock) {
            if (!isAutomationActive) return

            // Look for Hotspot switch or toggle
            val switchNode = findHotspotSwitch(root)
            if (switchNode != null) {
                if (switchNode.isChecked) {
                    // Already ON
                    completeAutomation(true, "Mobile hotspot is ON")
                    return
                }

                // Click to turn ON
                val clicked = switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                              clickParentClickable(switchNode)

                if (clicked) {
                    mainHandler.postDelayed({
                        synchronized(lock) {
                            if (!isAutomationActive) return@synchronized
                            val updatedRoot = rootInActiveWindow
                            val rechecked = if (updatedRoot != null) findHotspotSwitch(updatedRoot) else null
                            if (rechecked?.isChecked == true) {
                                completeAutomation(true, "Mobile hotspot successfully enabled via Accessibility automation")
                            } else {
                                completeAutomation(true, "Toggled mobile hotspot switch via Accessibility")
                            }
                        }
                    }, 1000L)
                } else {
                    completeAutomation(false, "Failed to click mobile hotspot switch node")
                }
                return
            }

            // If we are on a top-level tethering menu, look for "Wi-Fi hotspot" or "Mobile Hotspot" row to click into
            val hotspotRow = findHotspotSubmenuRow(root)
            if (hotspotRow != null) {
                val entered = hotspotRow.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                              clickParentClickable(hotspotRow)
                if (entered) {
                    LogManager.log("ACCESSIBILITY_NAV", LogStatus.INFO, "Navigated into Hotspot sub-settings")
                }
            }
        }
    }

    private fun findHotspotSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val targets = listOf(
            "wi-fi hotspot", "wifi hotspot", "mobile hotspot",
            "portable hotspot", "personal hotspot", "hotspot", "tethering"
        )

        // 1. Check if root itself is a switch with hotspot text/desc
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var candidateSwitch: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val className = node.className?.toString() ?: ""

            val isSwitch = node.isCheckable ||
                    className.contains("Switch", ignoreCase = true) ||
                    className.contains("CompoundButton", ignoreCase = true) ||
                    className.contains("CheckBox", ignoreCase = true)

            val matchesHotspot = targets.any { text.contains(it) || desc.contains(it) }

            if (isSwitch && matchesHotspot) {
                return node
            }

            // Also check if node is a switch located inside or adjacent to a hotspot row
            if (isSwitch && candidateSwitch == null) {
                // If it has a parent or sibling with hotspot text
                if (hasHotspotParentOrSibling(node, targets)) {
                    candidateSwitch = node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return candidateSwitch
    }

    private fun hasHotspotParentOrSibling(node: AccessibilityNodeInfo, targets: List<String>): Boolean {
        val parent = node.parent ?: return false
        val parentText = (parent.text?.toString() ?: "").lowercase()
        val parentDesc = (parent.contentDescription?.toString() ?: "").lowercase()
        if (targets.any { parentText.contains(it) || parentDesc.contains(it) }) {
            return true
        }

        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            val sibText = (sibling.text?.toString() ?: "").lowercase()
            val sibDesc = (sibling.contentDescription?.toString() ?: "").lowercase()
            if (targets.any { sibText.contains(it) || sibDesc.contains(it) }) {
                return true
            }
        }
        return false
    }

    private fun findHotspotSubmenuRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val targets = listOf("wi-fi hotspot", "mobile hotspot", "portable hotspot", "personal hotspot")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()

            if (targets.any { text.contains(it) || desc.contains(it) }) {
                if (node.isClickable) return node
                val parent = node.parent
                if (parent?.isClickable == true) return parent
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun clickParentClickable(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    companion object {
        @Volatile
        private var instance: HotspotAccessibilityService? = null
        private val lock = Any()
        private val mainHandler = Handler(Looper.getMainLooper())

        private var isAutomationActive = false
        private var currentCallback: ((Boolean, String) -> Unit)? = null
        private var timeoutRunnable: Runnable? = null

        fun isRunning(): Boolean = instance != null

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, HotspotAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }

        fun requestEnableHotspot(context: Context, onResult: (Boolean, String) -> Unit) {
            synchronized(lock) {
                if (instance == null) {
                    onResult(false, "Accessibility Service is not enabled in Android Settings")
                    return
                }

                isAutomationActive = true
                currentCallback = onResult

                // 15 seconds timeout
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                val timeout = Runnable {
                    completeAutomation(false, "Timeout (15s) waiting for hotspot settings automation")
                }
                timeoutRunnable = timeout
                mainHandler.postDelayed(timeout, 15_000L)

                // Launch tethering settings
                try {
                    val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        completeAutomation(false, "Could not open Tethering Settings intent: ${e.message}")
                    }
                }
            }
        }

        private fun completeAutomation(success: Boolean, details: String) {
            var callbackToInvoke: ((Boolean, String) -> Unit)? = null
            synchronized(lock) {
                if (!isAutomationActive) return
                isAutomationActive = false
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable = null
                callbackToInvoke = currentCallback
                currentCallback = null
            }
            callbackToInvoke?.let { callback ->
                mainHandler.post { callback(success, details) }
            }
        }
    }
}
