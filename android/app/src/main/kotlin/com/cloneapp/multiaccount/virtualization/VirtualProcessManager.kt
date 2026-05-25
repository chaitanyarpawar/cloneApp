package com.cloneapp.multiaccount.virtualization

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Virtual Process Manager
 * 
 * Manages virtual processes for cloned apps:
 * - Process lifecycle management
 * - ClassLoader isolation per clone
 * - Resource isolation
 * - Memory management
 * - Process limits
 * 
 * Note: Android doesn't allow true process isolation without root.
 * This implements "virtual" process isolation within the app's process space.
 */
class VirtualProcessManager(private val context: Context) {
    
    companion object {
        private const val TAG = "VirtualProcessMgr"
        
        // Maximum concurrent virtual processes
        private const val MAX_VIRTUAL_PROCESSES = 10
        
        // Process monitoring interval
        private const val MONITOR_INTERVAL_MS = 5000L
        
        // Memory threshold for cleanup
        private const val MEMORY_THRESHOLD_MB = 100
        
        // Virtual process ID base
        private const val VIRTUAL_PID_BASE = 100000
        
        @Volatile
        private var INSTANCE: VirtualProcessManager? = null
        
        fun getInstance(context: Context): VirtualProcessManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VirtualProcessManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Active virtual processes
    private val virtualProcesses = ConcurrentHashMap<String, VirtualProcess>()
    
    // ClassLoader cache per package
    private val classLoaderCache = ConcurrentHashMap<String, ClassLoader>()
    
    // Virtual PID counter
    private val pidCounter = AtomicInteger(VIRTUAL_PID_BASE)
    
    // Executors
    private val processExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val monitorExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Activity Manager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    init {
        startProcessMonitor()
    }
    
    /**
     * Create a new virtual process for a clone
     */
    fun createVirtualProcess(
        cloneId: String,
        packageName: String,
        containerId: String,
        storagePath: String
    ): VirtualProcessResult {
        // Check process limit
        if (virtualProcesses.size >= MAX_VIRTUAL_PROCESSES) {
            // Try to cleanup old processes
            cleanupIdleProcesses()
            
            if (virtualProcesses.size >= MAX_VIRTUAL_PROCESSES) {
                return VirtualProcessResult(
                    success = false,
                    error = "Maximum virtual process limit reached"
                )
            }
        }
        
        // Check if process already exists for this cloneId
        val existingProcess = virtualProcesses[cloneId]
        if (existingProcess != null) {
            if (existingProcess.state == ProcessState.RUNNING || existingProcess.state == ProcessState.PAUSED) {
                // Process is still active - update timestamp and reuse
                Log.d(TAG, "[$cloneId] Reusing existing ACTIVE process (state=${existingProcess.state})")
                existingProcess.lastActivity = System.currentTimeMillis()
                return VirtualProcessResult(
                    success = true,
                    process = existingProcess,
                    alreadyExisted = true
                )
            } else {
                // Process exists but is stopped/killed - clean it up and create fresh
                Log.d(TAG, "[$cloneId] Existing process is ${existingProcess.state}, cleaning up and recreating")
                virtualProcesses.remove(cloneId)
                // Also clear the ClassLoader cache for this package to get a fresh one
                classLoaderCache.remove(existingProcess.packageName + "_" + cloneId)
            }
        }
        
        try {
            // Create isolated ClassLoader for this process
            val classLoader = getOrCreateClassLoader(packageName, storagePath)
            
            // Generate virtual PID
            val virtualPid = pidCounter.incrementAndGet()
            
            // Create virtual process
            val process = VirtualProcess(
                cloneId = cloneId,
                packageName = packageName,
                containerId = containerId,
                virtualPid = virtualPid,
                realPid = Process.myPid(),
                classLoader = classLoader,
                storagePath = storagePath,
                state = ProcessState.CREATED,
                startTime = System.currentTimeMillis()
            )
            
            // Register process
            virtualProcesses[cloneId] = process
            
            Log.d(TAG, "Created virtual process: $cloneId (vPid: $virtualPid)")
            
            return VirtualProcessResult(
                success = true,
                process = process
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual process", e)
            return VirtualProcessResult(
                success = false,
                error = "Process creation failed: ${e.message}"
            )
        }
    }
    
    /**
     * Start a virtual process
     */
    fun startVirtualProcess(cloneId: String): Boolean {
        val process = virtualProcesses[cloneId] ?: return false
        
        if (process.state == ProcessState.RUNNING) {
            return true
        }
        
        process.state = ProcessState.RUNNING
        process.lastActivity = System.currentTimeMillis()
        
        Log.d(TAG, "Started virtual process: $cloneId")
        return true
    }
    
    /**
     * Stop a virtual process
     */
    fun stopVirtualProcess(cloneId: String, force: Boolean = false): Boolean {
        val process = virtualProcesses[cloneId] ?: return false
        
        if (process.state == ProcessState.STOPPED && !force) {
            return true
        }
        
        process.state = ProcessState.STOPPED
        
        // Cleanup if forced
        if (force) {
            virtualProcesses.remove(cloneId)
            Log.d(TAG, "Force stopped and removed virtual process: $cloneId")
        } else {
            Log.d(TAG, "Stopped virtual process: $cloneId")
        }
        
        return true
    }
    
    /**
     * Kill a virtual process
     */
    fun killVirtualProcess(cloneId: String): Boolean {
        val process = virtualProcesses.remove(cloneId)
        
        if (process != null) {
            process.state = ProcessState.KILLED
            
            // Clear ClassLoader from cache
            classLoaderCache.remove(process.packageName)
            
            Log.d(TAG, "Killed virtual process: $cloneId")
            return true
        }
        
        return false
    }
    
    /**
     * Get a virtual process by clone ID
     */
    fun getVirtualProcess(cloneId: String): VirtualProcess? {
        return virtualProcesses[cloneId]
    }
    
    /**
     * Get all virtual processes
     */
    fun getAllVirtualProcesses(): List<VirtualProcess> {
        return virtualProcesses.values.toList()
    }
    
    /**
     * Get running virtual processes
     */
    fun getRunningProcesses(): List<VirtualProcess> {
        return virtualProcesses.values.filter { it.state == ProcessState.RUNNING }
    }
    
    /**
     * Check if a virtual process is running
     */
    fun isProcessRunning(cloneId: String): Boolean {
        return virtualProcesses[cloneId]?.state == ProcessState.RUNNING
    }
    
    /**
     * Get process count for a package
     */
    fun getProcessCountForPackage(packageName: String): Int {
        return virtualProcesses.values.count { it.packageName == packageName }
    }
    
    /**
     * Get or create ClassLoader for a package + clone combination.
     * Each clone instance gets its own ClassLoader for proper isolation.
     */
    fun getOrCreateClassLoader(packageName: String, storagePath: String): ClassLoader {
        // Use storagePath as part of the key to ensure each clone gets its own ClassLoader
        val cacheKey = "${packageName}_${storagePath.hashCode()}"
        Log.d(TAG, "[getOrCreateClassLoader] key=$cacheKey, packageName=$packageName, storagePath=$storagePath")
        return classLoaderCache.getOrPut(cacheKey) {
            createClassLoader(packageName, storagePath)
        }
    }
    
    /**
     * Create isolated ClassLoader for a package
     */
    private fun createClassLoader(packageName: String, storagePath: String): ClassLoader {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        val apkPath = appInfo.sourceDir
        
        // Create dex output directory
        val dexOutputDir = File(storagePath, "dex_opt")
        dexOutputDir.mkdirs()
        
        // Native library path
        val nativeLibPath = appInfo.nativeLibraryDir
        
        // Create DexClassLoader
        val classLoader = DexClassLoader(
            apkPath,
            dexOutputDir.absolutePath,
            nativeLibPath,
            context.classLoader
        )
        
        Log.d(TAG, "Created ClassLoader for $packageName")
        
        return classLoader
    }
    
    /**
     * Get memory info for virtual processes
     */
    fun getMemoryInfo(): VirtualProcessMemoryInfo {
        val runtime = Runtime.getRuntime()
        
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        
        val processMemory = virtualProcesses.values.sumOf { it.estimatedMemoryUsage }
        
        return VirtualProcessMemoryInfo(
            totalMemory = totalMemory,
            freeMemory = freeMemory,
            usedMemory = usedMemory,
            maxMemory = maxMemory,
            virtualProcessMemory = processMemory,
            processCount = virtualProcesses.size
        )
    }
    
    /**
     * Cleanup idle virtual processes
     */
    fun cleanupIdleProcesses(maxIdleTimeMs: Long = 5 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val idleProcesses = virtualProcesses.entries.filter { (_, process) ->
            process.state == ProcessState.STOPPED && 
            (now - process.lastActivity) > maxIdleTimeMs
        }
        
        idleProcesses.forEach { (cloneId, _) ->
            killVirtualProcess(cloneId)
        }
        
        if (idleProcesses.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${idleProcesses.size} idle processes")
        }
    }
    
    /**
     * Request garbage collection
     */
    fun requestGC() {
        System.gc()
        Log.d(TAG, "Requested garbage collection")
    }
    
    /**
     * Start process monitoring
     */
    private fun startProcessMonitor() {
        monitorExecutor.scheduleAtFixedRate({
            try {
                monitorProcesses()
            } catch (e: Exception) {
                Log.e(TAG, "Process monitor error", e)
            }
        }, MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }
    
    /**
     * Monitor virtual processes
     */
    private fun monitorProcesses() {
        val memInfo = getMemoryInfo()
        
        // Check memory pressure
        val usedPercentage = (memInfo.usedMemory.toDouble() / memInfo.maxMemory) * 100
        
        if (usedPercentage > 85) {
            Log.w(TAG, "High memory usage: ${usedPercentage.toInt()}%")
            
            // Cleanup idle processes
            cleanupIdleProcesses(60 * 1000) // 1 minute idle
            
            // Request GC
            requestGC()
        }
        
        // Update process activity
        virtualProcesses.values.forEach { process ->
            if (process.state == ProcessState.RUNNING) {
                process.estimatedMemoryUsage = estimateProcessMemory(process)
            }
        }
    }
    
    /**
     * Estimate memory usage for a virtual process
     */
    private fun estimateProcessMemory(process: VirtualProcess): Long {
        // Base estimate - in a real implementation, this would be more sophisticated
        val baseMemory = 10 * 1024 * 1024L // 10MB base
        
        // Add per-class memory estimate
        val classLoaderOverhead = 5 * 1024 * 1024L // 5MB for ClassLoader
        
        return baseMemory + classLoaderOverhead
    }
    
    /**
     * Get low memory killer priority
     */
    fun getProcessPriority(cloneId: String): Int {
        val process = virtualProcesses[cloneId] ?: return Int.MAX_VALUE
        
        return when (process.state) {
            ProcessState.RUNNING -> 100 // High priority
            ProcessState.PAUSED -> 200 // Medium priority
            ProcessState.STOPPED -> 300 // Low priority
            else -> Int.MAX_VALUE
        }
    }
    
    /**
     * Trim memory for low memory conditions
     */
    fun onTrimMemory(level: Int) {
        Log.d(TAG, "Trim memory called: level=$level")
        
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // Kill all stopped processes
                virtualProcesses.filter { it.value.state == ProcessState.STOPPED }
                    .keys
                    .forEach { killVirtualProcess(it) }
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // Cleanup idle processes
                cleanupIdleProcesses(30 * 1000) // 30 seconds
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                // Cleanup very old idle processes
                cleanupIdleProcesses(2 * 60 * 1000) // 2 minutes
            }
        }
        
        requestGC()
    }
    
    /**
     * Shutdown all virtual processes
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down all virtual processes")
        
        virtualProcesses.keys.forEach { cloneId ->
            killVirtualProcess(cloneId)
        }
        
        processExecutor.shutdown()
        monitorExecutor.shutdown()
    }
}

/**
 * Virtual process representation
 */
data class VirtualProcess(
    val cloneId: String,
    val packageName: String,
    val containerId: String,
    val virtualPid: Int,
    val realPid: Int,
    val classLoader: ClassLoader,
    val storagePath: String,
    var state: ProcessState,
    val startTime: Long,
    var lastActivity: Long = System.currentTimeMillis(),
    var estimatedMemoryUsage: Long = 0
) {
    /**
     * Get process uptime
     */
    val uptime: Long
        get() = System.currentTimeMillis() - startTime
    
    /**
     * Check if process is active
     */
    val isActive: Boolean
        get() = state == ProcessState.RUNNING || state == ProcessState.PAUSED
    
    fun toMap(): Map<String, Any> = mapOf(
        "cloneId" to cloneId,
        "packageName" to packageName,
        "containerId" to containerId,
        "virtualPid" to virtualPid,
        "state" to state.name,
        "startTime" to startTime,
        "lastActivity" to lastActivity,
        "uptime" to uptime,
        "memoryUsage" to estimatedMemoryUsage
    )
}

/**
 * Process state
 */
enum class ProcessState {
    CREATED,
    RUNNING,
    PAUSED,
    STOPPED,
    KILLED
}

/**
 * Result of creating a virtual process
 */
data class VirtualProcessResult(
    val success: Boolean,
    val error: String? = null,
    val process: VirtualProcess? = null,
    val alreadyExisted: Boolean = false
)

/**
 * Memory info for virtual processes
 */
data class VirtualProcessMemoryInfo(
    val totalMemory: Long,
    val freeMemory: Long,
    val usedMemory: Long,
    val maxMemory: Long,
    val virtualProcessMemory: Long,
    val processCount: Int
) {
    fun toMap(): Map<String, Any> = mapOf(
        "totalMemoryMB" to (totalMemory / 1024 / 1024),
        "freeMemoryMB" to (freeMemory / 1024 / 1024),
        "usedMemoryMB" to (usedMemory / 1024 / 1024),
        "maxMemoryMB" to (maxMemory / 1024 / 1024),
        "virtualProcessMemoryMB" to (virtualProcessMemory / 1024 / 1024),
        "processCount" to processCount
    )
}

/**
 * AppLoader for dynamic APK loading
 * Loads target APK code without modifying the package
 */
class AppLoader(private val context: Context) {
    
    companion object {
        private const val TAG = "AppLoader"
    }
    
    /**
     * Load an app's classes into a ClassLoader
     */
    fun loadApp(
        packageName: String,
        storagePath: String
    ): AppLoaderResult {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            
            val apkPath = appInfo.sourceDir
            val nativeLibPath = appInfo.nativeLibraryDir
            
            // Create dex output directory
            val dexOutputDir = File(storagePath, "dex_opt")
            dexOutputDir.mkdirs()
            
            // Create DexClassLoader
            val classLoader = DexClassLoader(
                apkPath,
                dexOutputDir.absolutePath,
                nativeLibPath,
                context.classLoader
            )
            
            Log.d(TAG, "Loaded app: $packageName from $apkPath")
            
            return AppLoaderResult(
                success = true,
                classLoader = classLoader,
                apkPath = apkPath,
                nativeLibPath = nativeLibPath
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "App not found: $packageName", e)
            return AppLoaderResult(
                success = false,
                error = "App not installed: $packageName"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load app: $packageName", e)
            return AppLoaderResult(
                success = false,
                error = "Load failed: ${e.message}"
            )
        }
    }
    
    /**
     * Load a specific class from an app
     */
    fun loadClass(
        classLoader: ClassLoader,
        className: String
    ): Class<*>? {
        return try {
            classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found: $className")
            null
        }
    }
    
    /**
     * Get application class name from manifest
     */
    fun getApplicationClassName(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appInfo.className
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get launcher activity class name
     */
    fun getLauncherActivityClassName(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return null
            launchIntent.component?.className
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Result of loading an app
 */
data class AppLoaderResult(
    val success: Boolean,
    val error: String? = null,
    val classLoader: ClassLoader? = null,
    val apkPath: String? = null,
    val nativeLibPath: String? = null
)
