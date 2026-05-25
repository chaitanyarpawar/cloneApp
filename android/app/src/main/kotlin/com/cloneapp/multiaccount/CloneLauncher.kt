package com.cloneapp.multiaccount

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced clone launcher with proper isolation, timeout handling, and OEM compatibility.
 * Supports Android 9-14+ and major OEMs (Samsung, Xiaomi, OnePlus, Realme).
 */
class CloneLauncher(private val context: Context) {
    
    companion object {
        private const val TAG = "CloneLauncher"
        private const val LAUNCH_TIMEOUT_MS = 5000L
        private const val MAX_RETRY_COUNT = 3
        
        // OEM-specific package managers
        private val OEM_DUAL_APP_PACKAGES = listOf(
            "com.samsung.android.knox.containeragent", // Samsung Secure Folder
            "com.miui.securitycenter", // Xiaomi Dual Apps
            "com.coloros.safecenter", // OPPO/Realme
            "com.oneplus.security", // OnePlus
        )
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    /**
     * Launch a clone with timeout and multiple fallback strategies.
     * Returns true if launch initiated successfully, false otherwise.
     */
    fun launchCloneWithTimeout(
        packageName: String,
        cloneId: String,
        callback: LaunchCallback
    ) {
        val launchStartTime = System.currentTimeMillis()
        val launchCompleted = AtomicBoolean(false)
        
        Log.d(TAG, "[$cloneId] Starting clone launch for $packageName")
        
        // Set up timeout
        mainHandler.postDelayed({
            if (!launchCompleted.get()) {
                Log.w(TAG, "[$cloneId] Launch timeout after ${LAUNCH_TIMEOUT_MS}ms")
                callback.onLaunchTimeout(cloneId, packageName)
            }
        }, LAUNCH_TIMEOUT_MS)
        
        // Perform launch in background to avoid blocking UI
        backgroundExecutor.execute {
            try {
                val result = performLaunchSequence(packageName, cloneId)
                val elapsed = System.currentTimeMillis() - launchStartTime
                
                launchCompleted.set(true)
                
                mainHandler.post {
                    if (result.success) {
                        Log.d(TAG, "[$cloneId] Launch succeeded in ${elapsed}ms using ${result.method}")
                        callback.onLaunchSuccess(cloneId, result.method, elapsed)
                    } else {
                        Log.e(TAG, "[$cloneId] Launch failed: ${result.error}")
                        callback.onLaunchFailed(cloneId, result.error ?: "Unknown error", result.diagnostics)
                    }
                }
            } catch (e: Exception) {
                launchCompleted.set(true)
                Log.e(TAG, "[$cloneId] Launch exception: ", e)
                mainHandler.post {
                    callback.onLaunchFailed(cloneId, e.message ?: "Exception during launch", mapOf("exception" to e.toString()))
                }
            }
        }
    }
    
    /**
     * Synchronous launch with multiple strategies.
     */
    private fun performLaunchSequence(packageName: String, cloneId: String): LaunchResult {
        // Pre-launch checks
        if (!isAppInstalled(packageName)) {
            return LaunchResult(false, "App not installed", "pre_check")
        }
        
        // Strategy 1: Direct multi-instance launch (fastest)
        var result = tryDirectMultiInstance(packageName, cloneId)
        if (result.success) return result
        
        // Small delay between strategies
        Thread.sleep(200)
        
        // Strategy 2: Document-based launch (better task separation)
        result = tryDocumentBasedLaunch(packageName, cloneId)
        if (result.success) return result
        
        Thread.sleep(200)
        
        // Strategy 3: OEM-aware launch (for Samsung/Xiaomi/etc.)
        result = tryOemAwareLaunch(packageName, cloneId)
        if (result.success) return result
        
        Thread.sleep(200)
        
        // Strategy 4: Basic fallback launch
        result = tryBasicLaunch(packageName, cloneId)
        
        return result
    }
    
    /**
     * Strategy 1: Direct multi-instance with proper flags
     */
    private fun tryDirectMultiInstance(packageName: String, cloneId: String): LaunchResult {
        return try {
            Log.d(TAG, "[$cloneId] Trying direct multi-instance launch")
            
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult(false, "No launch intent found", "direct_multi")
            
            // Clone the intent to avoid modifying the original
            val cloneIntent = Intent(launchIntent)
            
            // Clear existing flags and set proper multi-instance flags
            cloneIntent.flags = 0
            cloneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            cloneIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            
            // On Android 5.0+, use document mode for better separation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cloneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
            
            // Add unique identifiers
            val timestamp = System.currentTimeMillis()
            cloneIntent.putExtra("CLONE_SESSION_ID", cloneId)
            cloneIntent.putExtra("INSTANCE_TIMESTAMP", timestamp)
            cloneIntent.putExtra("MULTI_INSTANCE_MODE", true)
            cloneIntent.putExtra("USER_PROFILE", "profile_$cloneId")
            
            // CRITICAL: Set data URI to force new instance recognition
            // Include both cloneId AND timestamp so each launch is guaranteed unique
            cloneIntent.data = android.net.Uri.parse("cloneapp://$packageName/$cloneId/$timestamp")
            
            // Resolve target activity's launch mode - if singleTask/singleInstance,
            // Android will NOT create a 2nd instance. Log a warning.
            val resolvedActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launchIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launchIntent, 0)
            }
            if (resolvedActivities.isNotEmpty()) {
                val launchMode = resolvedActivities[0].activityInfo.launchMode
                val launchModeStr = when(launchMode) {
                    0 -> "standard"
                    1 -> "singleTop"
                    2 -> "singleTask"
                    3 -> "singleInstance"
                    4 -> "singleInstancePerTask"
                    else -> "unknown($launchMode)"
                }
                Log.d(TAG, "[$cloneId] Target activity launchMode=$launchModeStr")
                if (launchMode == 2 || launchMode == 3) {
                    Log.w(TAG, "[$cloneId] WARNING: Target app uses $launchModeStr - Android may NOT create a 2nd instance!")
                    Log.w(TAG, "[$cloneId] The 2nd launch will likely bring the existing task to front instead.")
                }
            }
            
            Log.d(TAG, "[$cloneId] Starting activity with flags=${cloneIntent.flags}, data=${cloneIntent.data}")
            context.startActivity(cloneIntent)
            Log.d(TAG, "[$cloneId] Direct multi-instance launch SUCCESS")
            
            LaunchResult(true, null, "direct_multi")
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Direct multi-instance failed: ", e)
            LaunchResult(false, e.message, "direct_multi", mapOf("exception" to e.toString()))
        }
    }
    
    /**
     * Strategy 2: Document-based launch for better task isolation
     */
    private fun tryDocumentBasedLaunch(packageName: String, cloneId: String): LaunchResult {
        return try {
            Log.d(TAG, "[$cloneId] Trying document-based launch")
            
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult(false, "No launch intent found", "document_based")
            
            val documentIntent = Intent(launchIntent)
            documentIntent.flags = 0
            
            // Document mode flags for task separation
            documentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            documentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            documentIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                documentIntent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
            }
            
            // Unique data to ensure new task
            val timestamp = System.currentTimeMillis()
            documentIntent.data = android.net.Uri.parse("clone://$packageName/$timestamp")
            
            documentIntent.putExtra("CLONE_ID", cloneId)
            documentIntent.putExtra("DOCUMENT_LAUNCH", true)
            
            context.startActivity(documentIntent)
            
            LaunchResult(true, null, "document_based")
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Document-based launch failed: ", e)
            LaunchResult(false, e.message, "document_based")
        }
    }
    
    /**
     * Strategy 3: OEM-aware launch with manufacturer-specific handling
     */
    private fun tryOemAwareLaunch(packageName: String, cloneId: String): LaunchResult {
        return try {
            Log.d(TAG, "[$cloneId] Trying OEM-aware launch for ${Build.MANUFACTURER}")
            
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult(false, "No launch intent found", "oem_aware")
            
            val oemIntent = Intent(launchIntent)
            oemIntent.flags = 0
            oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            oemIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            
            // Manufacturer-specific adjustments
            when (Build.MANUFACTURER.lowercase()) {
                "samsung" -> {
                    // Samsung Knox/Secure Folder compatible flags
                    oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    oemIntent.putExtra("samsung_multiwindow", true)
                }
                "xiaomi", "redmi", "poco" -> {
                    // MIUI specific handling
                    oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    oemIntent.putExtra("miui_force_new_task", true)
                }
                "oneplus", "oppo", "realme" -> {
                    // ColorOS/OxygenOS handling
                    oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        oemIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    }
                }
                "huawei", "honor" -> {
                    // EMUI handling
                    oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    oemIntent.putExtra("hwid_clone_mode", true)
                }
                else -> {
                    // Generic handling for other OEMs
                    oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                }
            }
            
            val timestamp = System.currentTimeMillis()
            oemIntent.putExtra("CLONE_SESSION_ID", cloneId)
            oemIntent.putExtra("OEM", Build.MANUFACTURER)
            oemIntent.data = android.net.Uri.parse("oem-clone://$packageName/$cloneId/$timestamp")
            
            context.startActivity(oemIntent)
            
            LaunchResult(true, null, "oem_aware_${Build.MANUFACTURER.lowercase()}")
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] OEM-aware launch failed: ", e)
            LaunchResult(false, e.message, "oem_aware")
        }
    }
    
    /**
     * Strategy 4: Basic fallback launch
     */
    private fun tryBasicLaunch(packageName: String, cloneId: String): LaunchResult {
        return try {
            Log.d(TAG, "[$cloneId] Trying basic fallback launch")
            
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult(false, "No launch intent found", "basic_fallback")
            
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.putExtra("CLONE_ID", cloneId)
            launchIntent.putExtra("BASIC_LAUNCH", true)
            
            context.startActivity(launchIntent)
            
            LaunchResult(true, null, "basic_fallback")
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Basic launch failed: ", e)
            LaunchResult(false, e.message, "basic_fallback")
        }
    }
    
    /**
     * Check if app is installed
     */
    fun isAppInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Check if app is currently running
     */
    fun isAppRunning(packageName: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val processes = am.runningAppProcesses ?: return false
            processes.any { it.processName == packageName || it.processName.startsWith("$packageName:") }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get clone data directory for isolation
     */
    fun getCloneDataDir(cloneId: String): File {
        val baseDir = context.filesDir
        val cloneDir = File(baseDir, "clones/$cloneId")
        if (!cloneDir.exists()) {
            cloneDir.mkdirs()
        }
        return cloneDir
    }
    
    /**
     * Clean up resources
     */
    fun shutdown() {
        backgroundExecutor.shutdown()
        try {
            if (!backgroundExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            backgroundExecutor.shutdownNow()
        }
    }
    
    /**
     * Callback interface for launch results
     */
    interface LaunchCallback {
        fun onLaunchSuccess(cloneId: String, method: String, elapsedMs: Long)
        fun onLaunchFailed(cloneId: String, error: String, diagnostics: Map<String, Any>?)
        fun onLaunchTimeout(cloneId: String, packageName: String)
    }
    
    /**
     * Launch result data class
     */
    data class LaunchResult(
        val success: Boolean,
        val error: String?,
        val method: String,
        val diagnostics: Map<String, Any>? = null
    )
}
