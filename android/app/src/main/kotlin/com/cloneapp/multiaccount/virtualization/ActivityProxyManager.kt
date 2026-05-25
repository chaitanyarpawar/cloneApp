package com.cloneapp.multiaccount.virtualization

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Activity Proxy System for Intent & Activity Management
 * 
 * Implements a ProxyActivity mechanism where:
 * - All cloned app activities are launched inside ProxyActivity
 * - Lifecycle callbacks are properly forwarded
 * - Back-stack handling is managed
 * - Task switching compatibility is maintained
 * 
 * Features:
 * - Activity lifecycle forwarding (onCreate, onResume, onPause, onDestroy)
 * - Intent result handling
 * - Configuration change handling
 * - Multi-window support (Android N+)
 * - Picture-in-picture support (Android O+)
 */
class ActivityProxyManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ActivityProxyManager"
        
        // Intent extras for proxy communication
        const val EXTRA_TARGET_PACKAGE = "proxy_target_package"
        const val EXTRA_TARGET_ACTIVITY = "proxy_target_activity"
        const val EXTRA_CONTAINER_ID = "proxy_container_id"
        const val EXTRA_CLONE_ID = "proxy_clone_id"
        const val EXTRA_ORIGINAL_INTENT = "proxy_original_intent"
        const val EXTRA_PROXY_SESSION = "proxy_session_id"
        
        // Request codes
        private const val REQUEST_CODE_BASE = 10000
        
        @Volatile
        private var INSTANCE: ActivityProxyManager? = null
        
        fun getInstance(context: Context): ActivityProxyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ActivityProxyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Active proxy sessions
    private val activeSessions = ConcurrentHashMap<String, ProxySession>()
    
    // Activity result callbacks
    private val resultCallbacks = ConcurrentHashMap<Int, ActivityResultCallback>()
    
    // Request code generator
    private val requestCodeCounter = AtomicInteger(REQUEST_CODE_BASE)
    
    // Main thread handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Create a proxy intent to launch a cloned app activity
     */
    fun createProxyIntent(
        targetPackage: String,
        containerId: String,
        cloneId: String,
        originalIntent: Intent? = null
    ): ProxyIntentResult {
        try {
            Log.d(TAG, "[createProxyIntent] START: package=$targetPackage, container=$containerId, clone=$cloneId")
            Log.d(TAG, "[createProxyIntent] Active sessions: ${activeSessions.size}")
            
            // Get launch intent for target package
            val pm = context.packageManager
            val launchIntent = originalIntent ?: pm.getLaunchIntentForPackage(targetPackage)
            
            if (launchIntent == null) {
                Log.e(TAG, "[createProxyIntent] No launch intent for package: $targetPackage")
                return ProxyIntentResult(
                    success = false,
                    error = "No launch intent available for package: $targetPackage"
                )
            }
            
            Log.d(TAG, "[createProxyIntent] Launch intent component: ${launchIntent.component}")
            
            // Create session
            val sessionId = generateSessionId()
            val session = ProxySession(
                sessionId = sessionId,
                containerId = containerId,
                cloneId = cloneId,
                targetPackage = targetPackage,
                targetActivity = launchIntent.component?.className,
                startTime = System.currentTimeMillis()
            )
            activeSessions[sessionId] = session
            
            Log.d(TAG, "[createProxyIntent] Created session: $sessionId")
            
            // Create proxy intent
            val proxyIntent = Intent(context, VirtualProxyActivity::class.java).apply {
                // Set action to clone launch
                action = "com.cloneapp.multiaccount.PROXY_LAUNCH"
                
                // Copy original intent extras
                launchIntent.extras?.let { putExtras(it) }
                
                // Add proxy metadata
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                putExtra(EXTRA_TARGET_ACTIVITY, launchIntent.component?.className)
                putExtra(EXTRA_CONTAINER_ID, containerId)
                putExtra(EXTRA_CLONE_ID, cloneId)
                putExtra(EXTRA_PROXY_SESSION, sessionId)
                putExtra(EXTRA_ORIGINAL_INTENT, launchIntent)
                
                // Set proper flags for multi-instance
                // CRITICAL: These flags together force Android to create a NEW task
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                }
                
                // Set unique data to force new task — each clone gets unique URI
                val timestamp = System.currentTimeMillis()
                data = android.net.Uri.parse(
                    "cloneproxy://$targetPackage/$containerId/$cloneId/$sessionId/$timestamp"
                )
            }
            
            Log.d(TAG, "[createProxyIntent] Proxy intent created: flags=${proxyIntent.flags}, data=${proxyIntent.data}")
            Log.d(TAG, "[createProxyIntent] SUCCESS for $targetPackage (session: $sessionId, clone: $cloneId)")
            
            return ProxyIntentResult(
                success = true,
                proxyIntent = proxyIntent,
                sessionId = sessionId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create proxy intent", e)
            return ProxyIntentResult(
                success = false,
                error = "Failed to create proxy intent: ${e.message}"
            )
        }
    }
    
    /**
     * Launch activity through proxy
     */
    fun launchThroughProxy(
        targetPackage: String,
        containerId: String,
        cloneId: String,
        originalIntent: Intent? = null,
        options: Bundle? = null
    ): LaunchThroughProxyResult {
        Log.d(TAG, "[launchThroughProxy] START: package=$targetPackage, container=$containerId, clone=$cloneId")
        
        val proxyResult = createProxyIntent(targetPackage, containerId, cloneId, originalIntent)
        
        if (!proxyResult.success || proxyResult.proxyIntent == null) {
            Log.e(TAG, "[launchThroughProxy] createProxyIntent FAILED: ${proxyResult.error}")
            return LaunchThroughProxyResult(
                success = false,
                error = proxyResult.error ?: "Failed to create proxy intent"
            )
        }
        
        return try {
            val activityOptions = options ?: createDefaultActivityOptions()
            Log.d(TAG, "[launchThroughProxy] Starting activity with options=${activityOptions != null}")
            context.startActivity(proxyResult.proxyIntent, activityOptions)
            Log.d(TAG, "[launchThroughProxy] SUCCESS: Activity started for clone=$cloneId")
            
            LaunchThroughProxyResult(
                success = true,
                sessionId = proxyResult.sessionId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch through proxy", e)
            LaunchThroughProxyResult(
                success = false,
                error = "Launch failed: ${e.message}"
            )
        }
    }
    
    /**
     * Create activity options for multi-instance launch
     */
    fun createDefaultActivityOptions(): Bundle? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ActivityOptions.makeBasic().apply {
                // Allow multi-window launch
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    launchBounds = null // Use default bounds
                }
            }.toBundle()
        } else {
            null
        }
    }
    
    /**
     * Create activity options for split-screen launch
     */
    fun createSplitScreenOptions(bounds: Rect): Bundle? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ActivityOptions.makeBasic().apply {
                launchBounds = bounds
            }.toBundle()
        } else {
            null
        }
    }
    
    /**
     * Forward activity result to cloned app
     */
    fun handleActivityResult(
        sessionId: String,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        val callback = resultCallbacks.remove(requestCode)
        callback?.onResult(resultCode, data)
        
        // Update session
        activeSessions[sessionId]?.let { session ->
            session.lastActivity = System.currentTimeMillis()
        }
    }
    
    /**
     * Register for activity result
     */
    fun registerForResult(callback: ActivityResultCallback): Int {
        val requestCode = requestCodeCounter.incrementAndGet()
        resultCallbacks[requestCode] = callback
        return requestCode
    }
    
    /**
     * Get active session
     */
    fun getSession(sessionId: String): ProxySession? {
        return activeSessions[sessionId]
    }
    
    /**
     * End a proxy session
     */
    fun endSession(sessionId: String) {
        val session = activeSessions.remove(sessionId)
        if (session != null) {
            Log.d(TAG, "Ended proxy session: $sessionId")
        }
    }
    
    /**
     * Get all active sessions for a container
     */
    fun getContainerSessions(containerId: String): List<ProxySession> {
        return activeSessions.values.filter { it.containerId == containerId }
    }
    
    /**
     * End all sessions for a container
     */
    fun endContainerSessions(containerId: String) {
        val keysToRemove = activeSessions.entries
            .filter { it.value.containerId == containerId }
            .map { it.key }
        
        keysToRemove.forEach { activeSessions.remove(it) }
        Log.d(TAG, "Ended ${keysToRemove.size} sessions for container: $containerId")
    }
    
    /**
     * Build intent for starting activity for result within proxy
     */
    fun buildActivityForResultIntent(
        sourceSession: ProxySession,
        targetIntent: Intent,
        callback: ActivityResultCallback
    ): StartForResultData {
        val requestCode = registerForResult(callback)
        
        return StartForResultData(
            intent = targetIntent,
            requestCode = requestCode,
            sessionId = sourceSession.sessionId
        )
    }
    
    // ===== Private Methods =====
    
    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

/**
 * Proxy session data
 */
data class ProxySession(
    val sessionId: String,
    val containerId: String,
    val cloneId: String,
    val targetPackage: String,
    val targetActivity: String?,
    val startTime: Long,
    var lastActivity: Long = System.currentTimeMillis(),
    var state: ProxySessionState = ProxySessionState.CREATED
)

enum class ProxySessionState {
    CREATED,
    RUNNING,
    PAUSED,
    STOPPED,
    DESTROYED
}

/**
 * Result of creating a proxy intent
 */
data class ProxyIntentResult(
    val success: Boolean,
    val error: String? = null,
    val proxyIntent: Intent? = null,
    val sessionId: String? = null
)

/**
 * Result of launching through proxy
 */
data class LaunchThroughProxyResult(
    val success: Boolean,
    val error: String? = null,
    val sessionId: String? = null
)

/**
 * Data for starting activity for result
 */
data class StartForResultData(
    val intent: Intent,
    val requestCode: Int,
    val sessionId: String
)

/**
 * Callback for activity results
 */
interface ActivityResultCallback {
    fun onResult(resultCode: Int, data: Intent?)
}

/**
 * Intent interceptor for cloned apps
 * Intercepts startActivity calls and routes through proxy
 */
class IntentInterceptor(
    private val context: Context,
    private val containerId: String,
    private val proxyManager: ActivityProxyManager
) {
    
    companion object {
        private const val TAG = "IntentInterceptor"
    }
    
    /**
     * Intercept and potentially redirect an intent
     */
    fun interceptIntent(intent: Intent): InterceptResult {
        // Check if this intent should be intercepted
        if (!shouldIntercept(intent)) {
            return InterceptResult(intercepted = false, modifiedIntent = intent)
        }
        
        // Modify intent for proper routing
        val modifiedIntent = modifyIntent(intent)
        
        return InterceptResult(
            intercepted = true,
            modifiedIntent = modifiedIntent
        )
    }
    
    /**
     * Check if intent targets an external app that should go through proxy
     */
    private fun shouldIntercept(intent: Intent): Boolean {
        val component = intent.component
        val packageName = component?.packageName ?: intent.`package`
        
        // Don't intercept intents to host app
        if (packageName == context.packageName) {
            return false
        }
        
        // Don't intercept system intents
        if (intent.action?.startsWith("android.") == true) {
            return false
        }
        
        // Intercept explicit intents to other apps
        return component != null
    }
    
    /**
     * Modify intent for proper container routing
     */
    private fun modifyIntent(intent: Intent): Intent {
        val modified = Intent(intent)
        
        // Add container context
        modified.putExtra(ActivityProxyManager.EXTRA_CONTAINER_ID, containerId)
        
        // Add multi-instance flags
        modified.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        modified.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        
        return modified
    }
}

/**
 * Result of intent interception
 */
data class InterceptResult(
    val intercepted: Boolean,
    val modifiedIntent: Intent
)

/**
 * Activity lifecycle callbacks handler for proxied activities
 */
class ProxyLifecycleHandler(
    private val sessionId: String,
    private val proxyManager: ActivityProxyManager
) {
    
    companion object {
        private const val TAG = "ProxyLifecycleHandler"
    }
    
    fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Log.d(TAG, "[$sessionId] onActivityCreated")
        proxyManager.getSession(sessionId)?.state = ProxySessionState.CREATED
    }
    
    fun onActivityStarted(activity: Activity) {
        Log.d(TAG, "[$sessionId] onActivityStarted")
        proxyManager.getSession(sessionId)?.state = ProxySessionState.RUNNING
    }
    
    fun onActivityResumed(activity: Activity) {
        Log.d(TAG, "[$sessionId] onActivityResumed")
        proxyManager.getSession(sessionId)?.apply {
            state = ProxySessionState.RUNNING
            lastActivity = System.currentTimeMillis()
        }
    }
    
    fun onActivityPaused(activity: Activity) {
        Log.d(TAG, "[$sessionId] onActivityPaused")
        proxyManager.getSession(sessionId)?.state = ProxySessionState.PAUSED
    }
    
    fun onActivityStopped(activity: Activity) {
        Log.d(TAG, "[$sessionId] onActivityStopped")
        proxyManager.getSession(sessionId)?.state = ProxySessionState.STOPPED
    }
    
    fun onActivityDestroyed(activity: Activity) {
        Log.d(TAG, "[$sessionId] onActivityDestroyed")
        proxyManager.getSession(sessionId)?.state = ProxySessionState.DESTROYED
        proxyManager.endSession(sessionId)
    }
    
    fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        Log.d(TAG, "[$sessionId] onActivitySaveInstanceState")
        // Save session ID for restoration
        outState.putString(ActivityProxyManager.EXTRA_PROXY_SESSION, sessionId)
    }
    
    fun onConfigurationChanged(activity: Activity, newConfig: android.content.res.Configuration) {
        Log.d(TAG, "[$sessionId] onConfigurationChanged")
        // Handle configuration changes (rotation, etc.)
    }
    
    fun onBackPressed(activity: Activity): Boolean {
        Log.d(TAG, "[$sessionId] onBackPressed")
        // Return false to allow default back behavior
        return false
    }
}

/**
 * Back stack manager for proper task navigation
 */
class ProxyBackStackManager(private val containerId: String) {
    
    companion object {
        private const val TAG = "ProxyBackStackManager"
    }
    
    private val backStack = mutableListOf<BackStackEntry>()
    
    /**
     * Push activity onto back stack
     */
    fun push(sessionId: String, activityName: String) {
        backStack.add(BackStackEntry(
            sessionId = sessionId,
            activityName = activityName,
            timestamp = System.currentTimeMillis()
        ))
        Log.d(TAG, "Pushed to back stack: $activityName (depth: ${backStack.size})")
    }
    
    /**
     * Pop from back stack
     */
    fun pop(): BackStackEntry? {
        return if (backStack.isNotEmpty()) {
            val entry = backStack.removeAt(backStack.lastIndex)
            Log.d(TAG, "Popped from back stack: ${entry.activityName} (remaining: ${backStack.size})")
            entry
        } else {
            null
        }
    }
    
    /**
     * Peek at top of back stack
     */
    fun peek(): BackStackEntry? {
        return backStack.lastOrNull()
    }
    
    /**
     * Clear back stack
     */
    fun clear() {
        backStack.clear()
        Log.d(TAG, "Cleared back stack")
    }
    
    /**
     * Get current depth
     */
    fun getDepth(): Int = backStack.size
    
    /**
     * Check if back stack is empty
     */
    fun isEmpty(): Boolean = backStack.isEmpty()
}

/**
 * Back stack entry
 */
data class BackStackEntry(
    val sessionId: String,
    val activityName: String,
    val timestamp: Long
)
