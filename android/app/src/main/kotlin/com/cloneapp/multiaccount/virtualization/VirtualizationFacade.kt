package com.cloneapp.multiaccount.virtualization

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VirtualizationFacade - Unified Entry Point for App Virtualization
 * 
 * This is the main coordinator that ties together all virtualization components:
 * - ContainerManager: Container lifecycle and storage
 * - VirtualProcessManager: Process/ClassLoader isolation
 * - FileRedirector: File system redirection
 * - BinderProxyManager: System service proxying
 * - ActivityProxyManager: Activity launching and lifecycle
 * - CloneNotificationManager: Notification handling
 * - PermissionMediator: Permission management
 * 
 * Usage Flow:
 * 1. Initialize container for clone
 * 2. Install app in container
 * 3. Create virtual process
 * 4. Launch through activity proxy
 * 5. Handle lifecycle, notifications, permissions
 */
class VirtualizationFacade(private val context: Context) {
    
    companion object {
        private const val TAG = "VirtualizationFacade"
        
        // Launch timeout
        private const val LAUNCH_TIMEOUT_MS = 5000L
        
        @Volatile
        private var INSTANCE: VirtualizationFacade? = null
        
        fun getInstance(context: Context): VirtualizationFacade {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VirtualizationFacade(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Component managers
    val containerManager: ContainerManager = ContainerManager.getInstance(context)
    val processManager: VirtualProcessManager = VirtualProcessManager.getInstance(context)
    val activityProxyManager: ActivityProxyManager = ActivityProxyManager.getInstance(context)
    val notificationManager: CloneNotificationManager = CloneNotificationManager.getInstance(context)
    val permissionMediator: PermissionMediator = PermissionMediator.getInstance(context)
    val binderProxyManager: BinderProxyManager = BinderProxyManager.getInstance(context)
    
    // Background executor
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    init {
        // Initialize system hooks
        binderProxyManager.installHooks()
    }
    
    // Active clones
    private val activeClones = ConcurrentHashMap<String, ActiveCloneInfo>()
    
    /**
     * Launch a virtualized clone instance
     * Main entry point for launching cloned apps
     * Supports multiple instances of the same app with different cloneIds
     */
    fun launchVirtualizedClone(
        packageName: String,
        cloneId: String,
        config: Map<String, Any>,
        callback: VirtualizationCallback
    ) {
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "========== LAUNCH START ==========")
        Log.d(TAG, "Launching virtualized clone: cloneId=$cloneId, package=$packageName")
        Log.d(TAG, "Currently active clones: ${activeClones.size} -> ${activeClones.keys}")
        Log.d(TAG, "Config: $config")
        
        // Check if this exact cloneId is already running
        if (activeClones.containsKey(cloneId)) {
            val existing = activeClones[cloneId]!!
            Log.w(TAG, "Clone $cloneId is already active (package=${existing.packageName}, started=${existing.startTime})")
            Log.w(TAG, "Will stop existing instance before relaunching")
            stopVirtualizedClone(cloneId)
        }
        
        // Set timeout
        val timeoutRunnable = Runnable {
            if (!activeClones.containsKey(cloneId)) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.e(TAG, "[$cloneId] LAUNCH TIMEOUT after ${elapsed}ms")
                callback.onFailure(cloneId, "Launch timeout after ${elapsed}ms", "timeout")
            }
        }
        mainHandler.postDelayed(timeoutRunnable, LAUNCH_TIMEOUT_MS)
        
        // Launch in background
        executor.execute {
            try {
                // Phase 1: Get or create container (unique per cloneId)
                Log.d(TAG, "[$cloneId] Phase 1: Getting/creating container...")
                val containerResult = containerManager.getOrCreateContainerForApp(packageName, cloneId)
                if (!containerResult.success) {
                    cancelTimeout(timeoutRunnable)
                    Log.e(TAG, "[$cloneId] Phase 1 FAILED: ${containerResult.error}")
                    notifyFailure(callback, cloneId, containerResult.error ?: "Container creation failed", "container")
                    return@execute
                }
                val containerId = containerResult.containerId!!
                val container = containerResult.container!!
                Log.d(TAG, "[$cloneId] Phase 1 OK: containerId=$containerId")
                
                // Phase 2: Prepare for launch
                Log.d(TAG, "[$cloneId] Phase 2: Preparing container for launch...")
                val preparation = containerManager.prepareForLaunch(containerId, packageName)
                if (!preparation.success) {
                    cancelTimeout(timeoutRunnable)
                    Log.e(TAG, "[$cloneId] Phase 2 FAILED: ${preparation.error}")
                    notifyFailure(callback, cloneId, preparation.error ?: "Preparation failed", "preparation")
                    return@execute
                }
                Log.d(TAG, "[$cloneId] Phase 2 OK: paths=${preparation.paths?.dataDir}")
                
                // Phase 3: Create virtual process (unique per cloneId)
                Log.d(TAG, "[$cloneId] Phase 3: Creating virtual process...")
                val processResult = processManager.createVirtualProcess(
                    cloneId, packageName, containerId, preparation.paths!!.dataDir
                )
                if (!processResult.success) {
                    cancelTimeout(timeoutRunnable)
                    Log.e(TAG, "[$cloneId] Phase 3 FAILED: ${processResult.error}")
                    notifyFailure(callback, cloneId, processResult.error ?: "Process creation failed", "process")
                    return@execute
                }
                Log.d(TAG, "[$cloneId] Phase 3 OK: vPid=${processResult.process?.virtualPid}, existed=${processResult.alreadyExisted}")
                processManager.startVirtualProcess(cloneId)
                
                // Phase 4: Launch through activity proxy
                Log.d(TAG, "[$cloneId] Phase 4: Launching through activity proxy...")
                val launchResult = activityProxyManager.launchThroughProxy(
                    targetPackage = packageName,
                    containerId = containerId,
                    cloneId = cloneId
                )
                
                cancelTimeout(timeoutRunnable)
                
                if (launchResult.success) {
                    val elapsed = System.currentTimeMillis() - startTime
                    
                    // Track active clone
                    activeClones[cloneId] = ActiveCloneInfo(
                        cloneId = cloneId,
                        packageName = packageName,
                        containerId = containerId,
                        sessionId = launchResult.sessionId,
                        startTime = System.currentTimeMillis()
                    )
                    
                    Log.d(TAG, "[$cloneId] Phase 4 OK: Launch succeeded in ${elapsed}ms, sessionId=${launchResult.sessionId}")
                    Log.d(TAG, "[$cloneId] Total active clones now: ${activeClones.size} -> ${activeClones.keys}")
                    Log.d(TAG, "========== LAUNCH SUCCESS ==========")
                    notifySuccess(callback, cloneId, elapsed)
                    
                    // Handle delayed ad initialization if configured
                    if (config["delayAds"] == true) {
                        val adDelay = ((config["adDelaySeconds"] as? Int) ?: 2) * 1000L
                        mainHandler.postDelayed({
                            handleDelayedAdInit(cloneId)
                        }, adDelay)
                    }
                } else {
                    Log.e(TAG, "[$cloneId] Phase 4 FAILED: ${launchResult.error}")
                    Log.d(TAG, "========== LAUNCH FAILED ==========")
                    notifyFailure(callback, cloneId, launchResult.error ?: "Launch failed", "launch")
                }
                
            } catch (e: Exception) {
                cancelTimeout(timeoutRunnable)
                Log.e(TAG, "Launch exception", e)
                notifyFailure(callback, cloneId, e.message ?: "Unknown error", "exception")
            }
        }
    }
    
    /**
     * Stop a virtualized clone
     */
    fun stopVirtualizedClone(cloneId: String): Boolean {
        val cloneInfo = activeClones.remove(cloneId)
        
        if (cloneInfo != null) {
            // Stop virtual process
            processManager.stopVirtualProcess(cloneId, force = true)
            
            // Cancel notifications
            notificationManager.cancelAllNotifications(cloneId)
            
            // End activity session
            cloneInfo.sessionId?.let { activityProxyManager.endSession(it) }
            
            Log.d(TAG, "Stopped clone: $cloneId")
            return true
        }
        
        return false
    }
    
    /**
     * Terminate a clone (alias for stopVirtualizedClone)
     */
    fun terminateClone(cloneId: String): Boolean = stopVirtualizedClone(cloneId)
    
    /**
     * Terminate all running clones
     */
    fun terminateAllClones() {
        activeClones.keys.toList().forEach { cloneId ->
            stopVirtualizedClone(cloneId)
        }
    }
    
    /**
     * Synchronous launch for simple use cases
     */
    fun launchClone(packageName: String, cloneId: String): Boolean {
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)
        
        launchVirtualizedClone(packageName, cloneId, emptyMap(), object : VirtualizationCallback {
            override fun onSuccess(cloneId: String, elapsedMs: Long) {
                success = true
                latch.countDown()
            }
            override fun onFailure(cloneId: String, error: String, stage: String) {
                success = false
                latch.countDown()
            }
        })
        
        // Wait up to 6 seconds for launch
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        return success
    }
    
    /**
     * Get active clones (alias for getAllActiveClones)
     */
    fun getActiveClones(): List<ActiveCloneInfo> = getAllActiveClones()
    
    /**
     * Get compatibility information for an app
     */
    fun getCompatibility(packageName: String): Map<String, Any> {
        val result = VirtualizationCompatibility.checkCompatibility(packageName)
        return mapOf(
            "level" to result.level.name,
            "reason" to result.reason,
            "canAttempt" to result.canAttempt,
            "isSupported" to (result.level == CompatibilityLevel.FULLY_SUPPORTED),
            "isBlacklisted" to (result.level == CompatibilityLevel.INCOMPATIBLE),
            "warnings" to if (result.level == CompatibilityLevel.LIMITED_SUPPORT) listOf(result.reason) else emptyList<String>()
        )
    }
    
    /**
     * Get system diagnostics (no argument version)
     */
    fun getDiagnostics(): Map<String, Any> = getSystemDiagnostics()
    
    /**
     * Get active clone info
     */
    fun getActiveClone(cloneId: String): ActiveCloneInfo? {
        return activeClones[cloneId]
    }
    
    /**
     * Get all active clones
     */
    fun getAllActiveClones(): List<ActiveCloneInfo> {
        return activeClones.values.toList()
    }
    
    /**
     * Check if clone is running
     */
    fun isCloneRunning(cloneId: String): Boolean {
        return activeClones.containsKey(cloneId) && processManager.isProcessRunning(cloneId)
    }
    
    /**
     * Get clone count for a package
     */
    fun getCloneCountForPackage(packageName: String): Int {
        return activeClones.values.count { it.packageName == packageName }
    }
    
    /**
     * Clear clone data
     */
    fun clearCloneData(cloneId: String): Boolean {
        val cloneInfo = activeClones[cloneId]
        
        if (cloneInfo != null) {
            return containerManager.clearAppData(cloneInfo.containerId, cloneInfo.packageName)
        }
        
        return false
    }
    
    /**
     * Delete a clone completely
     */
    fun deleteClone(cloneId: String): Boolean {
        // Stop if running
        stopVirtualizedClone(cloneId)
        
        // Find and delete container
        val containers = containerManager.getAllContainers()
        val container = containers.find { 
            containerManager.getInstalledApps(it.id).any { app -> 
                app.packageName.contains(cloneId) 
            }
        }
        
        if (container != null) {
            containerManager.deleteContainer(container.id)
        }
        
        // Clear permissions
        permissionMediator.clearClonePermissions(cloneId)
        
        return true
    }
    
    /**
     * Get diagnostics for a clone
     */
    fun getDiagnostics(cloneId: String): Map<String, Any> {
        val cloneInfo = activeClones[cloneId]
        val process = processManager.getVirtualProcess(cloneId)
        val memInfo = processManager.getMemoryInfo()
        
        return mapOf(
            "cloneId" to cloneId,
            "isActive" to (cloneInfo != null),
            "cloneInfo" to (cloneInfo?.toMap() ?: emptyMap<String, Any>()),
            "process" to (process?.toMap() ?: emptyMap<String, Any>()),
            "memory" to memInfo.toMap(),
            "permissions" to permissionMediator.getClonePermissions(cloneId),
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * Get system-wide diagnostics
     */
    fun getSystemDiagnostics(): Map<String, Any> {
        val memInfo = processManager.getMemoryInfo()
        
        return mapOf(
            "activeClones" to activeClones.size,
            "virtualProcesses" to processManager.getAllVirtualProcesses().size,
            "containers" to containerManager.getAllContainers().size,
            "memory" to memInfo.toMap(),
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * Handle low memory conditions
     */
    fun onTrimMemory(level: Int) {
        processManager.onTrimMemory(level)
    }
    
    /**
     * Shutdown facade
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down VirtualizationFacade")
        
        // Stop all clones
        activeClones.keys.toList().forEach { cloneId ->
            stopVirtualizedClone(cloneId)
        }
        
        processManager.shutdown()
        executor.shutdown()
    }
    
    // ===== Private Methods =====
    
    private fun cancelTimeout(runnable: Runnable) {
        mainHandler.removeCallbacks(runnable)
    }
    
    private fun notifySuccess(callback: VirtualizationCallback, cloneId: String, elapsedMs: Long) {
        mainHandler.post {
            callback.onSuccess(cloneId, elapsedMs)
        }
    }
    
    private fun notifyFailure(callback: VirtualizationCallback, cloneId: String, error: String, stage: String) {
        mainHandler.post {
            callback.onFailure(cloneId, error, stage)
        }
    }
    
    private fun handleDelayedAdInit(cloneId: String) {
        Log.d(TAG, "Delayed ad initialization for $cloneId")
        // Ad initialization happens here after app UI is shown
    }
}

/**
 * Active clone information
 */
data class ActiveCloneInfo(
    val cloneId: String,
    val packageName: String,
    val containerId: String,
    val sessionId: String?,
    val startTime: Long
) {
    val uptime: Long
        get() = System.currentTimeMillis() - startTime
    
    // Aliases for MainActivity compatibility
    val launchTime: Long
        get() = startTime
    
    val isActive: Boolean
        get() = true
    
    fun toMap(): Map<String, Any?> = mapOf(
        "cloneId" to cloneId,
        "packageName" to packageName,
        "containerId" to containerId,
        "sessionId" to sessionId,
        "startTime" to startTime,
        "launchTime" to launchTime,
        "isActive" to isActive,
        "uptime" to uptime
    )
}

/**
 * Callback interface for virtualization operations
 */
interface VirtualizationCallback {
    fun onSuccess(cloneId: String, elapsedMs: Long)
    fun onFailure(cloneId: String, error: String, stage: String)
}

/**
 * Compatibility checker for app virtualization
 */
object VirtualizationCompatibility {
    
    private const val TAG = "VirtualizationCompat"
    
    // Apps known to work well with virtualization
    private val WELL_SUPPORTED_APPS = setOf(
        "com.whatsapp",
        "com.instagram.android",
        "com.facebook.katana",
        "com.twitter.android",
        "org.telegram.messenger",
        "com.snapchat.android",
        "com.linkedin.android",
        "com.zhiliaoapp.musically",
        "com.discord",
        "com.viber.voip",
        "com.skype.raider"
    )
    
    // Apps that have issues with virtualization (banking, security)
    private val BLACKLISTED_APPS = setOf(
        // Banking apps (SafetyNet/Play Integrity)
        "com.phonepe.app",
        "in.org.npci.upiapp",
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "com.mobikwik_new",
        "in.amazon.mShop.android.shopping",
        
        // Google core services (should not be cloned)
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        
        // System apps
        "com.android.settings",
        "com.android.systemui"
    )
    
    // Apps with limited support
    private val LIMITED_SUPPORT_APPS = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.photos",
        "com.google.android.gm"
    )
    
    /**
     * Check if an app is compatible with virtualization
     */
    fun checkCompatibility(packageName: String): CompatibilityResult {
        return when {
            BLACKLISTED_APPS.contains(packageName) -> {
                CompatibilityResult(
                    level = CompatibilityLevel.INCOMPATIBLE,
                    reason = "This app uses security features that prevent cloning",
                    canAttempt = false
                )
            }
            WELL_SUPPORTED_APPS.contains(packageName) -> {
                CompatibilityResult(
                    level = CompatibilityLevel.FULLY_SUPPORTED,
                    reason = "This app is known to work well with virtualization",
                    canAttempt = true
                )
            }
            LIMITED_SUPPORT_APPS.contains(packageName) -> {
                CompatibilityResult(
                    level = CompatibilityLevel.LIMITED_SUPPORT,
                    reason = "This app may have limited functionality when cloned",
                    canAttempt = true
                )
            }
            else -> {
                CompatibilityResult(
                    level = CompatibilityLevel.UNKNOWN,
                    reason = "Compatibility unknown - proceed with caution",
                    canAttempt = true
                )
            }
        }
    }
    
    /**
     * Check Android version compatibility
     */
    fun checkAndroidCompatibility(): AndroidCompatibilityResult {
        val sdkVersion = android.os.Build.VERSION.SDK_INT
        
        return when {
            sdkVersion < android.os.Build.VERSION_CODES.LOLLIPOP -> {
                AndroidCompatibilityResult(
                    compatible = false,
                    reason = "Requires Android 5.0 or higher",
                    sdkVersion = sdkVersion
                )
            }
            sdkVersion >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // Android 14+
                AndroidCompatibilityResult(
                    compatible = true,
                    reason = "Full support with latest Android features",
                    sdkVersion = sdkVersion,
                    limitations = listOf(
                        "Background restrictions may limit some features",
                        "Foreground service required for background clones"
                    )
                )
            }
            sdkVersion >= android.os.Build.VERSION_CODES.S -> {
                // Android 12-13
                AndroidCompatibilityResult(
                    compatible = true,
                    reason = "Supported with some limitations",
                    sdkVersion = sdkVersion,
                    limitations = listOf(
                        "Export component restrictions",
                        "Background execution limits"
                    )
                )
            }
            else -> {
                // Android 5-11
                AndroidCompatibilityResult(
                    compatible = true,
                    reason = "Full support",
                    sdkVersion = sdkVersion
                )
            }
        }
    }
}

/**
 * Compatibility check result
 */
data class CompatibilityResult(
    val level: CompatibilityLevel,
    val reason: String,
    val canAttempt: Boolean
)

enum class CompatibilityLevel {
    FULLY_SUPPORTED,
    LIMITED_SUPPORT,
    UNKNOWN,
    INCOMPATIBLE
}

/**
 * Android version compatibility result
 */
data class AndroidCompatibilityResult(
    val compatible: Boolean,
    val reason: String,
    val sdkVersion: Int,
    val limitations: List<String> = emptyList()
)
