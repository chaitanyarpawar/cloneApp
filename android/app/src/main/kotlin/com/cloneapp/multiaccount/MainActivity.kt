package com.cloneapp.multiaccount

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.cloneapp.multiaccount.virtualization.VirtualizationFacade
import com.cloneapp.multiaccount.virtualization.ContainerManager

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.cloneapp.multiaccount/app_launcher"
    private val VIRTUALIZATION_CHANNEL = "com.cloneapp.multiaccount/virtualization"
    private val TAG = "CloneApp.MainActivity"
    
    private lateinit var cloneLauncher: CloneLauncher
    private lateinit var virtualizationEngine: CloneVirtualizationEngine
    private var adInitManager: AdInitManager? = null
    
    // New: Advanced virtualization facade
    private lateinit var virtualizationFacade: VirtualizationFacade

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Initialize clone launcher
        cloneLauncher = CloneLauncher(this)
        
        // Initialize virtualization engine (legacy)
        virtualizationEngine = CloneVirtualizationEngine(this)
        
        // Initialize advanced virtualization facade
        virtualizationFacade = VirtualizationFacade.getInstance(this)
        
        // Initialize ad manager asynchronously (NON-BLOCKING, DELAYED)
        // Ads must NOT block onCreate() or onResume()
        adInitManager = AdInitManager.getInstance()
        adInitManager?.initializeAsync(this, object : AdInitManager.AdInitCallback {
            override fun onAdInitComplete(success: Boolean, elapsedMs: Long) {
                Log.d(TAG, "Ad SDK init complete: success=$success, elapsed=${elapsedMs}ms")
            }
            override fun onAdInitFailed(error: String, elapsedMs: Long) {
                Log.e(TAG, "Ad SDK init failed: $error")
            }
            override fun onAdInitTimeout(elapsedMs: Long) {
                Log.w(TAG, "Ad SDK init timeout after ${elapsedMs}ms")
            }
        })
        
        // Setup main app launcher channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    // ===== Enhanced Launch Methods =====
                    "launchDirectMultiInstance" -> {
                        val packageName = call.argument<String>("packageName")
                        val cloneId = call.argument<String>("cloneId")
                        if (packageName != null && cloneId != null) {
                            launchWithCloneLauncher(packageName, cloneId, result)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name and clone ID are required", null)
                        }
                    }
                    
                    "launchViaCloneActivity" -> {
                        val packageName = call.argument<String>("packageName")
                        val cloneId = call.argument<String>("cloneId")
                        if (packageName != null && cloneId != null) {
                            val success = launchViaCloneActivity(packageName, cloneId)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name and clone ID are required", null)
                        }
                    }
                    
                    // ===== Legacy Methods (backwards compatibility) =====
                    "launchAppWithNewTask" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            val success = launchAppWithNewTask(packageName)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name is required", null)
                        }
                    }
                    
                    "launchAppWithMultipleInstance" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            val success = launchAppWithMultipleInstance(packageName)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name is required", null)
                        }
                    }
                    
                    "launchAppWithVirtualContainer" -> {
                        val packageName = call.argument<String>("packageName")
                        val cloneId = call.argument<String>("cloneId")
                        if (packageName != null && cloneId != null) {
                            val success = launchAppWithVirtualContainer(packageName, cloneId)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name and clone ID are required", null)
                        }
                    }
                    
                    "launchMultipleInstancePro" -> {
                        val packageName = call.argument<String>("packageName")
                        val cloneId = call.argument<String>("cloneId")
                        if (packageName != null && cloneId != null) {
                            launchWithCloneLauncher(packageName, cloneId, result)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name and clone ID are required", null)
                        }
                    }
                    
                    "launchWithForceRestart" -> {
                        val packageName = call.argument<String>("packageName")
                        val cloneId = call.argument<String>("cloneId")
                        if (packageName != null && cloneId != null) {
                            val success = launchWithForceRestart(packageName, cloneId)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name and clone ID are required", null)
                        }
                    }
                    
                    // ===== Utility Methods =====
                    "forceKillApp" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            val success = forceKillApp(packageName)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name is required", null)
                        }
                    }
                    
                    "isAppRunning" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            val isRunning = cloneLauncher.isAppRunning(packageName)
                            result.success(isRunning)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name is required", null)
                        }
                    }
                    
                    "isAppInstalled" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            val isInstalled = cloneLauncher.isAppInstalled(packageName)
                            result.success(isInstalled)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name is required", null)
                        }
                    }
                    
                    "createAppShortcut" -> {
                        val packageName = call.argument<String>("packageName")
                        val appName = call.argument<String>("appName")
                        val cloneId = call.argument<String>("cloneId")
                        if (packageName != null && appName != null && cloneId != null) {
                            val success = createAppShortcut(packageName, appName, cloneId)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "All parameters are required", null)
                        }
                    }
                    
                    // ===== Permission Methods =====
                    "requestBatteryOptimizationExemption" -> {
                        val success = requestBatteryOptimizationExemption()
                        result.success(success)
                    }
                    
                    "checkClonePermissions" -> {
                        val permissions = checkClonePermissions()
                        result.success(permissions)
                    }
                    
                    "isBatteryOptimizationDisabled" -> {
                        val isDisabled = isBatteryOptimizationDisabled()
                        result.success(isDisabled)
                    }
                    
                    // ===== Diagnostics =====
                    "getDeviceInfo" -> {
                        result.success(getDeviceInfo())
                    }
                    
                    "collectDiagnostics" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            result.success(collectDiagnostics(packageName))
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name is required", null)
                        }
                    }
                    
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Method call error: ${call.method}", e)
                result.error("NATIVE_ERROR", e.message, e.stackTraceToString())
            }
        }
        
        // Setup virtualization channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VIRTUALIZATION_CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "launchVirtualized" -> {
                        val packageName = call.argument<String>("packageName")
                        val cloneId = call.argument<String>("cloneId")
                        val storagePath = call.argument<String>("storagePath")
                        @Suppress("UNCHECKED_CAST")
                        val config = call.argument<Map<String, Any>>("config") ?: emptyMap()
                        
                        if (packageName != null && cloneId != null && storagePath != null) {
                            launchVirtualizedClone(packageName, cloneId, storagePath, config, result)
                        } else {
                            result.error("INVALID_ARGUMENT", "Required parameters missing", null)
                        }
                    }
                    
                    // New: Launch via advanced VirtualizationFacade
                    "launchVirtualizedAdvanced" -> {
                        val packageName = call.argument<String>("packageName")
                        val cloneId = call.argument<String>("cloneId")
                        
                        if (packageName != null && cloneId != null) {
                            launchViaAdvancedVirtualization(packageName, cloneId, result)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name and clone ID required", null)
                        }
                    }
                    
                    "terminateVirtualized" -> {
                        val cloneId = call.argument<String>("cloneId")
                        if (cloneId != null) {
                            val success = virtualizationEngine.terminateInstance(cloneId)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Clone ID required", null)
                        }
                    }
                    
                    // New: Terminate via advanced facade
                    "terminateVirtualizedAdvanced" -> {
                        val cloneId = call.argument<String>("cloneId")
                        if (cloneId != null) {
                            val success = virtualizationFacade.terminateClone(cloneId)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Clone ID required", null)
                        }
                    }
                    
                    "getActiveInstances" -> {
                        val instances = virtualizationEngine.getActiveInstances()
                        result.success(instances)
                    }
                    
                    // New: Get active clones from facade
                    "getActiveClones" -> {
                        val clones = virtualizationFacade.getActiveClones()
                        val cloneList = clones.map { clone ->
                            mapOf(
                                "cloneId" to clone.cloneId,
                                "packageName" to clone.packageName,
                                "launchTime" to clone.launchTime,
                                "isActive" to clone.isActive
                            )
                        }
                        result.success(cloneList)
                    }
                    
                    "initializeEnvironment" -> {
                        // Environment initialization handled internally
                        result.success(mapOf("success" to true))
                    }
                    
                    "isAppInstalled" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            val isInstalled = cloneLauncher.isAppInstalled(packageName)
                            result.success(isInstalled)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name required", null)
                        }
                    }
                    
                    "getAndroidVersion" -> {
                        result.success(Build.VERSION.SDK_INT)
                    }
                    
                    "hasAvailableStorage" -> {
                        val minMB = call.argument<Int>("minMB") ?: 100
                        val available = filesDir.usableSpace / 1024 / 1024
                        result.success(available >= minMB)
                    }
                    
                    "getVirtualizationDiagnostics" -> {
                        val cloneId = call.argument<String>("cloneId")
                        val diagnostics = mapOf(
                            "cloneId" to cloneId,
                            "androidVersion" to Build.VERSION.SDK_INT,
                            "timestamp" to System.currentTimeMillis()
                        )
                        result.success(diagnostics)
                    }
                    
                    // New: Advanced diagnostics from facade
                    "getSystemDiagnostics" -> {
                        val diagnostics = virtualizationFacade.getDiagnostics()
                        result.success(diagnostics)
                    }
                    
                    // New: Check compatibility
                    "checkCompatibility" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName != null) {
                            val compatibility = virtualizationFacade.getCompatibility(packageName)
                            result.success(compatibility)
                        } else {
                            result.error("INVALID_ARGUMENT", "Package name required", null)
                        }
                    }
                    
                    // New: Clear clone data
                    "clearCloneData" -> {
                        val cloneId = call.argument<String>("cloneId")
                        if (cloneId != null) {
                            val success = virtualizationFacade.clearCloneData(cloneId)
                            result.success(success)
                        } else {
                            result.error("INVALID_ARGUMENT", "Clone ID required", null)
                        }
                    }
                    
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Virtualization method call error: ${call.method}", e)
                result.error("NATIVE_ERROR", e.message, e.stackTraceToString())
            }
        }
    }

    /**
     * Launch virtualized clone with strict performance requirements
     */
    private fun launchVirtualizedClone(
        packageName: String,
        cloneId: String,
        storagePath: String,
        config: Map<String, Any>,
        result: MethodChannel.Result
    ) {
        virtualizationEngine.launchVirtualized(
            packageName,
            cloneId,
            storagePath,
            config,
            object : CloneVirtualizationEngine.VirtualizationCallback {
                override fun onSuccess(cloneId: String, elapsedMs: Long) {
                    Log.d(TAG, "[$cloneId] Virtualized launch succeeded in ${elapsedMs}ms")
                    result.success(mapOf(
                        "success" to true,
                        "cloneId" to cloneId,
                        "elapsedMs" to elapsedMs,
                        "method" to "virtualized"
                    ))
                }
                
                override fun onFailure(cloneId: String, error: String, stage: String) {
                    Log.e(TAG, "[$cloneId] Virtualized launch failed at $stage: $error")
                    result.success(mapOf(
                        "success" to false,
                        "error" to error,
                        "stage" to stage
                    ))
                }
            }
        )
    }
    
    /**
     * Launch via advanced VirtualizationFacade with full container isolation
     */
    private fun launchViaAdvancedVirtualization(
        packageName: String,
        cloneId: String,
        result: MethodChannel.Result
    ) {
        try {
            val startTime = System.currentTimeMillis()
            
            Log.d(TAG, "========== launchViaAdvancedVirtualization ==========")
            Log.d(TAG, "[$cloneId] package=$packageName")
            Log.d(TAG, "[$cloneId] Active clones before launch: ${virtualizationFacade.getActiveClones().size}")
            Log.d(TAG, "[$cloneId] Active clones for this package: ${virtualizationFacade.getCloneCountForPackage(packageName)}")
            
            // Use the unified VirtualizationFacade
            val success = virtualizationFacade.launchClone(packageName, cloneId)
            
            val elapsedMs = System.currentTimeMillis() - startTime
            
            if (success) {
                Log.d(TAG, "[$cloneId] Advanced virtualized launch succeeded in ${elapsedMs}ms")
                result.success(mapOf(
                    "success" to true,
                    "cloneId" to cloneId,
                    "elapsedMs" to elapsedMs,
                    "method" to "advanced_virtualized"
                ))
            } else {
                Log.e(TAG, "[$cloneId] Advanced virtualized launch failed")
                result.success(mapOf(
                    "success" to false,
                    "error" to "Launch failed - check logs for details",
                    "stage" to "facade_launch"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Advanced virtualized launch exception", e)
            result.success(mapOf(
                "success" to false,
                "error" to (e.message ?: "Unknown error"),
                "stage" to "exception"
            ))
        }
    }

    /**
     * Launch using the new CloneLauncher with proper timeout and callbacks
     */
    private fun launchWithCloneLauncher(
        packageName: String,
        cloneId: String,
        result: MethodChannel.Result
    ) {
        val latch = CountDownLatch(1)
        var launchSuccess = false
        var launchError: String? = null
        
        cloneLauncher.launchCloneWithTimeout(packageName, cloneId, object : CloneLauncher.LaunchCallback {
            override fun onLaunchSuccess(cloneId: String, method: String, elapsedMs: Long) {
                Log.d(TAG, "Clone launch success: method=$method, elapsed=${elapsedMs}ms")
                launchSuccess = true
                latch.countDown()
            }
            
            override fun onLaunchFailed(cloneId: String, error: String, diagnostics: Map<String, Any>?) {
                Log.e(TAG, "Clone launch failed: $error")
                launchSuccess = false
                launchError = error
                latch.countDown()
            }
            
            override fun onLaunchTimeout(cloneId: String, packageName: String) {
                Log.w(TAG, "Clone launch timeout")
                launchSuccess = false
                launchError = "Launch timeout"
                latch.countDown()
            }
        })
        
        // Wait for result with timeout (slightly longer than launcher timeout)
        val completed = latch.await(6, TimeUnit.SECONDS)
        
        if (!completed) {
            result.error("TIMEOUT", "Launch operation timed out", null)
        } else if (launchSuccess) {
            result.success(true)
        } else {
            // Return success=false instead of error to allow fallback in Dart
            result.success(false)
        }
    }

    /**
     * Launch via CloneActivity proxy
     */
    private fun launchViaCloneActivity(packageName: String, cloneId: String): Boolean {
        return try {
            val intent = CloneActivity.createCloneIntent(this, packageName, cloneId)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Launch via CloneActivity failed: ", e)
            false
        }
    }

    // ===== Legacy Launch Methods =====
    
    private fun launchAppWithNewTask(packageName: String): Boolean {
        return try {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
            
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            launchIntent.putExtra("clone_id", System.currentTimeMillis().toString())
            
            startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchAppWithNewTask failed: ", e)
            false
        }
    }

    private fun launchAppWithMultipleInstance(packageName: String): Boolean {
        return try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(packageName)
            
            val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            
            if (activities.isNotEmpty()) {
                val mainActivity = activities[0].activityInfo
                val launchIntent = Intent(Intent.ACTION_MAIN)
                launchIntent.setClassName(packageName, mainActivity.name)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                
                val uniqueId = System.currentTimeMillis().toString()
                launchIntent.putExtra("MULTI_ACCOUNT_SESSION", uniqueId)
                launchIntent.putExtra("CLONE_IDENTIFIER", uniqueId)
                
                startActivity(launchIntent)
                true
            } else {
                val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                startActivity(launchIntent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchAppWithMultipleInstance failed: ", e)
            false
        }
    }

    private fun launchAppWithVirtualContainer(packageName: String, cloneId: String): Boolean {
        return try {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
            
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            }
            
            launchIntent.action = Intent.ACTION_MAIN
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            val timestamp = System.currentTimeMillis()
            launchIntent.putExtra("multi_account_id", cloneId)
            launchIntent.putExtra("clone_session", true)
            launchIntent.putExtra("user_profile", "profile_$cloneId")
            launchIntent.data = Uri.parse("cloneapp://$packageName/$cloneId/$timestamp")
            
            startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchAppWithVirtualContainer failed: ", e)
            false
        }
    }

    private fun launchWithForceRestart(packageName: String, cloneId: String): Boolean {
        return try {
            Log.d(TAG, "[$cloneId] Force restart launch for $packageName")
            
            // IMPORTANT: Do NOT kill existing clone instances!
            // force-killing would destroy the 1st clone when launching the 2nd.
            // Only kill if no other clones of this app are active.
            val activeCloneCount = virtualizationFacade.getCloneCountForPackage(packageName)
            if (activeCloneCount > 0) {
                Log.d(TAG, "[$cloneId] $activeCloneCount active clone(s) found - skipping force-kill to preserve them")
            } else {
                Log.d(TAG, "[$cloneId] No active clones - safe to force-kill first")
                forceKillApp(packageName)
            }
            Thread.sleep(300)
            
            // Launch with multi-instance flags
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
            
            launchIntent.flags = 0
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            
            val timestamp = System.currentTimeMillis()
            launchIntent.putExtra("FORCE_RESTART", true)
            launchIntent.putExtra("CLONE_ID", cloneId)
            launchIntent.putExtra("TIMESTAMP", timestamp)
            launchIntent.data = Uri.parse("force-restart://$packageName/$cloneId/$timestamp")
            
            startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Force restart launch failed: ", e)
            false
        }
    }

    // ===== Utility Methods =====
    
    private fun forceKillApp(packageName: String): Boolean {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(packageName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "forceKillApp failed: ", e)
            false
        }
    }

    private fun createAppShortcut(packageName: String, appName: String, cloneId: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                val shortcutManager = getSystemService(android.content.pm.ShortcutManager::class.java)
                
                val intent = Intent(Intent.ACTION_MAIN)
                intent.setPackage(packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra("CLONE_ID", cloneId)
                intent.putExtra("SHORTCUT_LAUNCH", true)
                
                val shortcut = android.content.pm.ShortcutInfo.Builder(this, "clone_${cloneId}")
                    .setShortLabel("$appName Clone")
                    .setLongLabel("$appName - Clone Instance")
                    .setIcon(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_agenda))
                    .setIntent(intent)
                    .build()
                
                shortcutManager.addDynamicShortcuts(listOf(shortcut))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "createAppShortcut failed: ", e)
            false
        }
    }

    // ===== Permission Methods =====
    
    private fun requestBatteryOptimizationExemption(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                    return true
                }
            }
            true // Already exempted or not applicable
        } catch (e: Exception) {
            Log.e(TAG, "requestBatteryOptimizationExemption failed: ", e)
            false
        }
    }
    
    private fun isBatteryOptimizationDisabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkClonePermissions(): Map<String, Boolean> {
        val permissions = mutableMapOf<String, Boolean>()
        
        // Battery optimization
        permissions["battery_optimization_disabled"] = isBatteryOptimizationDisabled()
        
        // Query packages permission (implicit in manifest)
        permissions["query_packages"] = true
        
        // Check overlay permission (if needed for some features)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions["can_draw_overlays"] = Settings.canDrawOverlays(this)
        } else {
            permissions["can_draw_overlays"] = true
        }
        
        return permissions
    }

    // ===== Diagnostics =====
    
    private fun getDeviceInfo(): Map<String, Any> {
        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "hardware" to Build.HARDWARE
        )
    }
    
    private fun collectDiagnostics(packageName: String): Map<String, Any> {
        val diagnostics = mutableMapOf<String, Any>()
        
        diagnostics["device"] = getDeviceInfo()
        diagnostics["isAppInstalled"] = cloneLauncher.isAppInstalled(packageName)
        diagnostics["isAppRunning"] = cloneLauncher.isAppRunning(packageName)
        diagnostics["batteryOptimizationDisabled"] = isBatteryOptimizationDisabled()
        diagnostics["permissions"] = checkClonePermissions()
        diagnostics["timestamp"] = System.currentTimeMillis()
        
        return diagnostics
    }

    override fun onDestroy() {
        super.onDestroy()
        cloneLauncher.shutdown()
        adInitManager?.shutdown()
        
        // Cleanup virtualization facade
        virtualizationFacade.terminateAllClones()
    }
}
