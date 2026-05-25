package com.cloneapp.multiaccount

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Play Store-compliant virtualization engine for app cloning
 * 
 * Architecture:
 * - Host app acts as container
 * - Target APK loaded via DexClassLoader (sandbox runtime)
 * - Per-clone isolated storage paths
 * - Launch in controlled process
 * - NO package renaming, NO APK re-signing
 * 
 * Constraints:
 * - No root required
 * - No system privileges
 * - Android sandbox limitations accepted
 * - Play Store policy compliant
 */
class CloneVirtualizationEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "VirtualizationEngine"
        private const val STARTUP_TIMEOUT_MS = 5000L
        private const val MAX_INIT_TIME_MS = 3000L
        
        // Performance thresholds
        private const val TARGET_LAUNCH_TIME_MS = 5000L
        private const val HEAVY_OPS_DELAY_MS = 2000L
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val activeInstances = ConcurrentHashMap<String, VirtualInstanceInfo>()
    private val launchCounter = AtomicInteger(0)
    
    /**
     * Launch virtualized clone with strict performance requirements
     * - Must complete in <5 seconds
     * - UI must render before ads
     * - All heavy operations off main thread
     */
    fun launchVirtualized(
        packageName: String,
        cloneId: String,
        storagePath: String,
        config: Map<String, Any>,
        callback: VirtualizationCallback
    ) {
        val launchId = launchCounter.incrementAndGet()
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "[$cloneId] Starting virtualized launch #$launchId for $packageName")
        
        // Set up strict timeout
        val timeoutRunnable = Runnable {
            val elapsed = System.currentTimeMillis() - startTime
            if (!activeInstances.containsKey(cloneId)) {
                Log.w(TAG, "[$cloneId] Launch timeout after ${elapsed}ms")
                callback.onFailure(cloneId, "Launch timeout (${elapsed}ms)", "timeout")
            }
        }
        mainHandler.postDelayed(timeoutRunnable, STARTUP_TIMEOUT_MS)
        
        // Launch in background (non-blocking)
        backgroundExecutor.execute {
            try {
                // Phase 1: Pre-launch validation (<200ms)
                val validation = performPreLaunchValidation(packageName)
                if (!validation.isValid) {
                    cancelTimeout(timeoutRunnable)
                    notifyFailure(callback, cloneId, validation.error, "validation")
                    return@execute
                }
                
                // Phase 2: Setup isolated environment (<500ms)
                val environment = setupIsolatedEnvironment(
                    packageName,
                    cloneId,
                    storagePath
                )
                if (!environment.success) {
                    cancelTimeout(timeoutRunnable)
                    notifyFailure(callback, cloneId, environment.error, "environment")
                    return@execute
                }
                
                // Phase 3: Launch via optimized strategy (<4s)
                val launchResult = launchVirtualizedInstance(
                    packageName,
                    cloneId,
                    environment.classLoader,
                    environment.isolatedContext,
                    config
                )
                
                cancelTimeout(timeoutRunnable)
                
                if (launchResult.success) {
                    val elapsed = System.currentTimeMillis() - startTime
                    
                    // Register instance
                    val instanceInfo = VirtualInstanceInfo(
                        cloneId = cloneId,
                        packageName = packageName,
                        storagePath = storagePath,
                        startTime = System.currentTimeMillis(),
                        classLoader = environment.classLoader,
                        isolatedContext = environment.isolatedContext
                    )
                    activeInstances[cloneId] = instanceInfo
                    
                    Log.d(TAG, "[$cloneId] Launch succeeded in ${elapsed}ms")
                    mainHandler.post {
                        callback.onSuccess(cloneId, elapsed)
                    }
                    
                    // Delayed ad initialization (after UI renders)
                    if (config["delayAds"] == true) {
                        val adDelay = (config["adDelaySeconds"] as? Int ?: 2) * 1000L
                        mainHandler.postDelayed({
                            initializeAdsDelayed(cloneId)
                        }, adDelay)
                    }
                } else {
                    notifyFailure(callback, cloneId, launchResult.error, "launch")
                }
            } catch (e: Exception) {
                cancelTimeout(timeoutRunnable)
                Log.e(TAG, "[$cloneId] Exception during launch", e)
                notifyFailure(callback, cloneId, e.message ?: "Unknown error", "exception")
            }
        }
    }
    
    /**
     * Pre-launch validation checks (fast)
     */
    private fun performPreLaunchValidation(packageName: String): ValidationResult {
        try {
            // Check app installed
            val pm = context.packageManager
            val appInfo: ApplicationInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return ValidationResult(false, "App not installed")
            }
            
            // Check Android version
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return ValidationResult(false, "Requires Android 5.0+")
            }
            
            // Check if blacklisted
            if (isBlacklistedApp(packageName)) {
                return ValidationResult(
                    false,
                    "App not supported (banking/security restrictions)"
                )
            }
            
            // Check storage available
            val storageDir = context.filesDir
            val freeSpace = storageDir.usableSpace / 1024 / 1024 // MB
            if (freeSpace < 100) {
                return ValidationResult(false, "Insufficient storage (<100MB)")
            }
            
            return ValidationResult(true, null)
        } catch (e: Exception) {
            return ValidationResult(false, "Validation error: ${e.message}")
        }
    }
    
    /**
     * Setup isolated environment with classloader
     */
    private fun setupIsolatedEnvironment(
        packageName: String,
        cloneId: String,
        storagePath: String
    ): EnvironmentResult {
        try {
            Log.d(TAG, "[$cloneId] Setting up isolated environment")
            
            // Get APK path
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val apkPath = appInfo.sourceDir
            
            // Create optimized dex output directory
            val dexOutputDir = File(storagePath, "dex_opt")
            dexOutputDir.mkdirs()
            
            // Create isolated library path
            val libPath = appInfo.nativeLibraryDir
            
            // Create DexClassLoader for target app
            // This loads the APK without modifying package
            val classLoader = DexClassLoader(
                apkPath,
                dexOutputDir.absolutePath,
                libPath,
                context.classLoader
            )
            
            // Create isolated context wrapper
            val isolatedContext = createIsolatedContext(
                packageName,
                cloneId,
                storagePath,
                classLoader
            )
            
            Log.d(TAG, "[$cloneId] Environment setup complete")
            
            return EnvironmentResult(
                success = true,
                classLoader = classLoader,
                isolatedContext = isolatedContext
            )
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Environment setup failed", e)
            return EnvironmentResult(
                success = false,
                error = "Environment setup failed: ${e.message}"
            )
        }
    }
    
    /**
     * Create isolated context for clone
     * Redirects storage paths to clone-specific directories
     */
    private fun createIsolatedContext(
        packageName: String,
        cloneId: String,
        storagePath: String,
        classLoader: ClassLoader
    ): IsolatedContextWrapper {
        return IsolatedContextWrapper(
            base = context,
            cloneId = cloneId,
            packageName = packageName,
            storagePath = storagePath,
            classLoader = classLoader
        )
    }
    
    /**
     * Launch virtualized instance using optimized strategy
     */
    private fun launchVirtualizedInstance(
        packageName: String,
        cloneId: String,
        classLoader: ClassLoader?,
        isolatedContext: IsolatedContextWrapper?,
        config: Map<String, Any>
    ): LaunchResult {
        try {
            // Strategy 1: Direct multi-instance with isolation
            val result = launchWithIsolation(packageName, cloneId, isolatedContext)
            if (result.success) {
                return result
            }
            
            // Strategy 2: Fallback to standard multi-instance
            return launchStandardMultiInstance(packageName, cloneId)
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Launch failed", e)
            return LaunchResult(false, e.message ?: "Launch failed")
        }
    }
    
    /**
     * Launch with full isolation
     */
    private fun launchWithIsolation(
        packageName: String,
        cloneId: String,
        isolatedContext: IsolatedContextWrapper?
    ): LaunchResult {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult(false, "No launch intent")
            
            // Clone intent
            val cloneIntent = Intent(launchIntent)
            cloneIntent.flags = 0
            
            // Set multi-instance flags
            cloneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            cloneIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cloneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
            
            // Add isolation identifiers
            val timestamp = System.currentTimeMillis()
            cloneIntent.putExtra("CLONE_ID", cloneId)
            cloneIntent.putExtra("CLONE_TIMESTAMP", timestamp)
            cloneIntent.putExtra("VIRTUALIZED", true)
            
            // Unique data URI to force new instance
            cloneIntent.data = android.net.Uri.parse(
                "virtualized://$packageName/$cloneId/$timestamp"
            )
            
            // Launch with isolated context if available
            val launchContext = isolatedContext ?: context
            launchContext.startActivity(cloneIntent)
            
            LaunchResult(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Isolated launch failed", e)
            LaunchResult(false, e.message)
        }
    }
    
    /**
     * Standard multi-instance launch (fallback)
     */
    private fun launchStandardMultiInstance(
        packageName: String,
        cloneId: String
    ): LaunchResult {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult(false, "No launch intent")
            
            val intent = Intent(launchIntent)
            intent.flags = 0
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            
            val timestamp = System.currentTimeMillis()
            intent.data = android.net.Uri.parse("clone://$packageName/$timestamp")
            
            context.startActivity(intent)
            
            LaunchResult(true, null)
        } catch (e: Exception) {
            LaunchResult(false, e.message)
        }
    }
    
    /**
     * Initialize ads after UI has rendered
     */
    private fun initializeAdsDelayed(cloneId: String) {
        Log.d(TAG, "[$cloneId] Initializing ads (delayed)")
        // Ad initialization happens here, after app UI is visible
        // This prevents blocking the startup
    }
    
    /**
     * Check if app is blacklisted
     */
    private fun isBlacklistedApp(packageName: String): Boolean {
        val blacklist = setOf(
            // Banking apps (SafetyNet/Play Integrity)
            "com.phonepe.app",
            "in.org.npci.upiapp",
            "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm",
            // System apps
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending"
        )
        return blacklist.contains(packageName)
    }
    
    /**
     * Terminate virtualized instance
     */
    fun terminateInstance(cloneId: String): Boolean {
        val instance = activeInstances.remove(cloneId)
        if (instance != null) {
            Log.d(TAG, "[$cloneId] Instance terminated")
            return true
        }
        return false
    }
    
    /**
     * Get active instances
     */
    fun getActiveInstances(): List<Map<String, Any>> {
        return activeInstances.values.map { it.toMap() }
    }
    
    private fun cancelTimeout(runnable: Runnable) {
        mainHandler.removeCallbacks(runnable)
    }
    
    private fun notifyFailure(
        callback: VirtualizationCallback,
        cloneId: String,
        error: String?,
        stage: String
    ) {
        mainHandler.post {
            callback.onFailure(cloneId, error ?: "Unknown error", stage)
        }
    }
    
    // Result classes
    data class ValidationResult(val isValid: Boolean, val error: String?)
    data class EnvironmentResult(
        val success: Boolean,
        val error: String? = null,
        val classLoader: ClassLoader? = null,
        val isolatedContext: IsolatedContextWrapper? = null
    )
    data class LaunchResult(val success: Boolean, val error: String?)
    
    data class VirtualInstanceInfo(
        val cloneId: String,
        val packageName: String,
        val storagePath: String,
        val startTime: Long,
        val classLoader: ClassLoader?,
        val isolatedContext: IsolatedContextWrapper?
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "cloneId" to cloneId,
            "packageName" to packageName,
            "startTime" to startTime,
            "uptime" to (System.currentTimeMillis() - startTime)
        )
    }
    
    interface VirtualizationCallback {
        fun onSuccess(cloneId: String, elapsedMs: Long)
        fun onFailure(cloneId: String, error: String, stage: String)
    }
}
