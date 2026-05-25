package com.cloneapp.multiaccount.virtualization

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import com.cloneapp.multiaccount.virtualization.ReflectionUtils

/**
 * Virtual Proxy Activity
 * 
 * A transparent proxy activity that:
 * - Launches cloned app activities inside itself
 * - Provides isolated context and storage
 * - Manages lifecycle forwarding
 * - Handles configuration changes
 * - Maintains back-stack within the clone
 * 
 * This is the core activity that enables multi-instance app launching
 * without modifying the original APK.
 */
class VirtualProxyActivity : Activity() {
    
    companion object {
        private const val TAG = "VirtualProxyActivity"
        private const val LAUNCH_DELAY_MS = 100L
    }
    
    // Session information
    private var sessionId: String? = null
    private var containerId: String? = null
    private var cloneId: String? = null
    private var targetPackage: String? = null
    private var targetActivity: String? = null
    
    // Managers
    private lateinit var proxyManager: ActivityProxyManager
    private lateinit var containerManager: ContainerManager
    private var lifecycleHandler: ProxyLifecycleHandler? = null
    
    // State
    private var isLaunching = false
    private var launchStartTime = 0L
    private var savedState: Bundle? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.savedState = savedInstanceState
        
        launchStartTime = System.currentTimeMillis()
        
        // Initialize managers
        proxyManager = ActivityProxyManager.getInstance(applicationContext)
        containerManager = ContainerManager.getInstance(applicationContext)
        
        // Extract proxy parameters
        sessionId = intent.getStringExtra(ActivityProxyManager.EXTRA_PROXY_SESSION)
            ?: savedInstanceState?.getString(ActivityProxyManager.EXTRA_PROXY_SESSION)
        containerId = intent.getStringExtra(ActivityProxyManager.EXTRA_CONTAINER_ID)
        cloneId = intent.getStringExtra(ActivityProxyManager.EXTRA_CLONE_ID)
        targetPackage = intent.getStringExtra(ActivityProxyManager.EXTRA_TARGET_PACKAGE)
        targetActivity = intent.getStringExtra(ActivityProxyManager.EXTRA_TARGET_ACTIVITY)
        
        Log.d(TAG, "========== VirtualProxyActivity.onCreate ==========")
        Log.d(TAG, "session=$sessionId")
        Log.d(TAG, "containerId=$containerId")
        Log.d(TAG, "cloneId=$cloneId")
        Log.d(TAG, "targetPackage=$targetPackage")
        Log.d(TAG, "targetActivity=$targetActivity")
        Log.d(TAG, "intent.data=${intent.data}")
        Log.d(TAG, "intent.flags=${intent.flags}")
        Log.d(TAG, "taskId=$taskId")
        Log.d(TAG, "isTaskRoot=$isTaskRoot")
        Log.d(TAG, "============================================")
        
        // Validate required parameters
        if (targetPackage.isNullOrEmpty()) {
            Log.e(TAG, "Missing target package")
            finishWithError("Missing target package")
            return
        }
        
        // Initialize lifecycle handler
        if (sessionId != null) {
            lifecycleHandler = ProxyLifecycleHandler(sessionId!!, proxyManager)
            lifecycleHandler?.onActivityCreated(this, savedInstanceState)
        }
        
        // Launch the target app
        Handler(Looper.getMainLooper()).postDelayed({
            launchTargetApp()
        }, LAUNCH_DELAY_MS)
    }
    
    /**
     * Launch the target app with proper isolation
     */
    private fun launchTargetApp() {
        if (isLaunching) {
            Log.w(TAG, "[$cloneId] Already launching, ignoring duplicate call")
            return
        }
        isLaunching = true
        
        try {
            val packageName = targetPackage!!
            Log.d(TAG, "[$cloneId] launchTargetApp: package=$packageName, containerId=$containerId")
            
            // Prepare container if available
            if (!containerId.isNullOrEmpty()) {
                Log.d(TAG, "[$cloneId] Preparing container $containerId for launch...")
                val preparation = containerManager.prepareForLaunch(containerId!!, packageName)
                if (preparation.success) {
                    Log.d(TAG, "[$cloneId] Container prepared successfully. Paths: dataDir=${preparation.paths?.dataDir}")
                    launchWithIsolation(packageName, preparation)
                } else {
                    Log.e(TAG, "[$cloneId] Container preparation failed: ${preparation.error}")
                    finishWithError("Container preparation failed: ${preparation.error}")
                }
            } else {
                Log.e(TAG, "[$cloneId] Container ID missing")
                finishWithError("Container ID missing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Failed to launch target app", e)
            finishWithError("Launch failed: ${e.message}")
        }
    }
    
    /**
     * Launch with full container isolation using "Activity Grafting"
     * We load the target activity and graft it onto the current process/token
     */
    private fun launchWithIsolation(packageName: String, preparation: LaunchPreparation) {
        try {
            // Get APK info
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            
            // Create isolated context
            val paths = preparation.paths!!
            
            // Load the target app's code
            val appLoader = VirtualProcessManager.getInstance(applicationContext)
            // Use parent of dataDir to get to the container root
            val containerRoot = File(paths.dataDir).parent!!
            val classLoader = appLoader.getOrCreateClassLoader(packageName, containerRoot)
            
            // Get target activity class name
            var targetClassName = targetActivity
            if (targetClassName == null) {
                targetClassName = pm.getLaunchIntentForPackage(packageName)?.component?.className
            }
            
            if (targetClassName == null) {
                throw Exception("Could not resolve target activity for $packageName")
            }
            
            Log.d(TAG, "Grafting target activity: $targetClassName")
            
            // 1. Load the class
            val targetClass = classLoader.loadClass(targetClassName)
            
            // 2. Instantiate the activity
            val targetInstance = targetClass.newInstance() as Activity
            
            // 3. Create Virtual Context
            // We MUST use the target package's context as base to get correct Resources (layouts/assets)
            val targetBaseContext = createPackageContext(packageName, android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY)
            
            val virtualContext = VirtualContextWrapper(
                base = targetBaseContext,
                containerId = preparation.containerId!!,
                packageName = packageName,
                storagePath = containerRoot,
                isolatedPaths = paths
            )
            
            // 4. Perform the Transplant (The "Hack")
            // We need to retrieve the internal members of this (Proxy) activity and pass them to the Target
            
            // Get 'attach' method arguments from this Activity
            // Since we can't easily intercept 'attach', we copy key fields instead.
            
            // Copy mBase, mToken, mInstrumentation, mMainThread, mCalled, mIdent, mApplication, mIntent...
            ReflectionUtils.copyFields(this, targetInstance, Activity::class.java)
            
            // Set the virtual Context
            ReflectionUtils.setFieldValue(targetInstance, "mBase", virtualContext)
            
            // Update Application
            // In a real engine we'd create a VirtualApplication, but here we reuse ours or null it
            // ReflectionUtils.setFieldValue(targetInstance, "mApplication", ... ) 
            
            // Set Intent (rewrite component to target)
            val newIntent = Intent(intent)
            newIntent.component = android.content.ComponentName(packageName, targetClassName)
            ReflectionUtils.setFieldValue(targetInstance, "mIntent", newIntent)
            
            // Reset "mCalled"
            ReflectionUtils.setFieldValue(targetInstance, "mCalled", false)
            
            Log.i(TAG, "Transplant complete. Starting lifecycle for $targetClassName")
            
            // 5. Manually drive lifecycle
            // We are acting as the System now.
            
            // Call onCreate
            val onCreateMethod = ReflectionUtils.getMethod(Activity::class.java, "performCreate", Bundle::class.java) 
                                 ?: ReflectionUtils.getMethod(Activity::class.java, "onCreate", Bundle::class.java)
            
            // Call performCreate (which calls onCreate)
            // Note: performCreate is hidden, so we used ReflectionUtils above
            // If performCreate is not available (older APIs), we might need direct onCreate, 
            // but performCreate handles fragments/loaders.
            
            try {
                // Try performCreate first (Android ICS+)
                 val performCreate = ReflectionUtils.getMethod(Activity::class.java, "performCreate", Bundle::class.java)
                 performCreate.invoke(targetInstance, savedState)
            } catch (e: Exception) {
                // Fallback to onCreate directly
                val onCreate = ReflectionUtils.getMethod(Activity::class.java, "onCreate", Bundle::class.java)
                onCreate.isAccessible = true
                onCreate.invoke(targetInstance, savedState)
            }
            
            // Swap ourselves with the target in the Window (if possible)
            // This is hard. Usually ProxyActivity stays as the wrapper.
            // But we just corrupted 'this' state by copying it? No, we copied FROM this.
            
            // 6. Handover complete. TargetInstance is now "Running" on top of this Context.
            
            val elapsed = System.currentTimeMillis() - launchStartTime
            Log.d(TAG, "Graft success in ${elapsed}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Grafting/Isolation launch failed", e)
            // CRITICAL: Do NOT fall back to launchBasic. 
            // If we fail here, we must fail loudly so we don't open the host app.
            finishWithError("Isolation Error: ${e.message}")
        }
    }
    
    /**
     * Basic launch without full isolation (fallback)
     */

    
    /**
     * Configure intent for multi-instance launch
     */
    private fun configureMultiInstanceIntent(intent: Intent, packageName: String) {
        // Clear and set proper flags
        intent.flags = 0
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            intent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        
        // Unique data URI to force new task
        val timestamp = System.currentTimeMillis()
        intent.data = android.net.Uri.parse(
            "cloneapp-virtual://$packageName/$containerId/$cloneId/$timestamp"
        )
        
        // Add identifying extras
        intent.putExtra("CLONE_SESSION_ID", sessionId ?: cloneId)
        intent.putExtra("CONTAINER_ID", containerId)
        intent.putExtra("MULTI_INSTANCE_MODE", true)
        intent.putExtra("INSTANCE_TIMESTAMP", timestamp)
        intent.putExtra("USER_PROFILE", "profile_$cloneId")
        intent.putExtra("LAUNCHED_BY_CLONEAPP", true)
        intent.putExtra("VIRTUALIZED_LAUNCH", true)
        
        // Add OEM-specific extras
        addOemExtras(intent, packageName)
    }
    
    /**
     * Add OEM-specific extras for better compatibility
     */
    private fun addOemExtras(intent: Intent, packageName: String) {
        when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> {
                intent.putExtra("samsung_multiwindow_intent", true)
                intent.putExtra("samsung_split_screen", true)
                intent.putExtra("android.intent.extra.SPLIT_SCREEN", true)
            }
            "xiaomi", "redmi", "poco" -> {
                intent.putExtra("miui_force_new_task", true)
                intent.putExtra("miui_dual_app_mode", true)
                intent.putExtra("force_show_status_bar", true)
            }
            "oneplus", "oppo", "realme" -> {
                intent.putExtra("coloros_clone_launch", true)
                intent.putExtra("parallel_app_mode", true)
                intent.putExtra("force_new_instance", true)
            }
            "huawei", "honor" -> {
                intent.putExtra("emui_twin_app_mode", true)
                intent.putExtra("hw_split_mode", true)
            }
            "vivo" -> {
                intent.putExtra("funtouch_app_clone", true)
            }
            "motorola", "lenovo" -> {
                intent.putExtra("moto_dual_app", true)
            }
        }
    }
    
    private fun finishAfterDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                finish()
            }
        }, 200)
    }
    
    private fun finishWithError(error: String) {
        Log.e(TAG, "Finishing with error: $error")
        setResult(RESULT_CANCELED, Intent().putExtra("error", error))
        finish()
    }
    
    override fun onStart() {
        super.onStart()
        lifecycleHandler?.onActivityStarted(this)
    }
    
    override fun onResume() {
        super.onResume()
        lifecycleHandler?.onActivityResumed(this)
    }
    
    override fun onPause() {
        super.onPause()
        lifecycleHandler?.onActivityPaused(this)
    }
    
    override fun onStop() {
        super.onStop()
        lifecycleHandler?.onActivityStopped(this)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleHandler?.onActivityDestroyed(this)
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lifecycleHandler?.onActivitySaveInstanceState(this, outState)
        
        // Save session info for restoration
        sessionId?.let { outState.putString(ActivityProxyManager.EXTRA_PROXY_SESSION, it) }
        containerId?.let { outState.putString(ActivityProxyManager.EXTRA_CONTAINER_ID, it) }
        cloneId?.let { outState.putString(ActivityProxyManager.EXTRA_CLONE_ID, it) }
        targetPackage?.let { outState.putString(ActivityProxyManager.EXTRA_TARGET_PACKAGE, it) }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // Forward result to proxy manager
        sessionId?.let {
            proxyManager.handleActivityResult(it, requestCode, resultCode, data)
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val handled = lifecycleHandler?.onBackPressed(this) ?: false
        if (!handled) {
            super.onBackPressed()
        }
    }
}

/**
 * Virtual Context Wrapper for isolated app execution
 * Extends IsolatedContextWrapper with additional virtualization features
 */
class VirtualContextWrapper(
    base: android.content.Context,
    private val containerId: String,
    private val packageName: String,
    private val storagePath: String,
    private val isolatedPaths: IsolatedPaths
) : android.content.ContextWrapper(base) {
    
    companion object {
        private const val TAG = "VirtualContextWrapper"
    }
    
    private val fileRedirector = FileRedirector.getInstance(
        base,
        containerId,
        packageName,
        File(storagePath).parent!!
    )
    
    override fun getFilesDir(): File = fileRedirector.getFilesDir()
    
    override fun getCacheDir(): File = fileRedirector.getCacheDir()
    
    override fun getDataDir(): File = fileRedirector.getDataDir()
    
    override fun getDatabasePath(name: String): File = fileRedirector.getDatabasePath(name)
    
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: android.database.sqlite.SQLiteDatabase.CursorFactory?
    ): android.database.sqlite.SQLiteDatabase {
        return fileRedirector.openOrCreateDatabase(name, mode, factory)
    }
    
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
        errorHandler: android.database.DatabaseErrorHandler?
    ): android.database.sqlite.SQLiteDatabase {
        return fileRedirector.openOrCreateDatabase(name, mode, factory, errorHandler)
    }
    
    override fun deleteDatabase(name: String): Boolean = fileRedirector.deleteDatabase(name)
    
    override fun databaseList(): Array<String> = fileRedirector.databaseList()
    
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        return fileRedirector.getSharedPreferences(name, mode)
    }
    
    override fun getDir(name: String, mode: Int): File = fileRedirector.getDir(name, mode)
    
    override fun getExternalFilesDir(type: String?): File? = fileRedirector.getExternalFilesDir(type)
    
    override fun getExternalCacheDir(): File = fileRedirector.getExternalCacheDir()
    
    override fun getNoBackupFilesDir(): File = fileRedirector.getNoBackupFilesDir()
    
    override fun getCodeCacheDir(): File = fileRedirector.getCodeCacheDir()
}
