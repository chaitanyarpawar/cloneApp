package com.cloneapp.multiaccount

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Lightweight proxy activity for launching cloned app instances.
 * This activity launches the target app with proper isolation flags and finishes immediately.
 * Supports Android 9-14+ and handles OEM-specific behaviors.
 */
class CloneActivity : Activity() {
    
    companion object {
        private const val TAG = "CloneActivity"
        private const val LAUNCH_DELAY_MS = 100L
        
        /**
         * Create an intent to launch a clone via this activity
         */
        fun createCloneIntent(
            context: android.content.Context,
            packageName: String,
            cloneId: String
        ): Intent {
            val intent = Intent(context, CloneActivity::class.java)
            intent.action = "com.cloneapp.multiaccount.CLONE_LAUNCH"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            intent.putExtra("TARGET_PACKAGE", packageName)
            intent.putExtra("CLONE_ID", cloneId)
            intent.putExtra("IS_CLONE_INSTANCE", true)
            intent.putExtra("LAUNCH_TIMESTAMP", System.currentTimeMillis())
            return intent
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val targetPackage = intent.getStringExtra("TARGET_PACKAGE")
        val cloneId = intent.getStringExtra("CLONE_ID") ?: System.currentTimeMillis().toString()
        
        Log.d(TAG, "[$cloneId] CloneActivity started for package: $targetPackage")
        
        if (targetPackage.isNullOrEmpty()) {
            Log.e(TAG, "[$cloneId] Missing target package")
            finish()
            return
        }
        
        // Small delay to ensure activity is fully started before launching target
        Handler(Looper.getMainLooper()).postDelayed({
            launchTargetApp(targetPackage, cloneId)
            finish()
        }, LAUNCH_DELAY_MS)
    }
    
    /**
     * Launch the target app with multi-instance support
     */
    private fun launchTargetApp(packageName: String, cloneId: String) {
        try {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            
            if (launchIntent == null) {
                Log.e(TAG, "[$cloneId] No launch intent found for package: $packageName")
                return
            }
            
            // Clone the intent and configure for multi-instance
            val cloneIntent = Intent(launchIntent)
            configureMultiInstanceIntent(cloneIntent, packageName, cloneId)
            
            startActivity(cloneIntent)
            Log.d(TAG, "[$cloneId] Successfully launched clone for $packageName")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "[$cloneId] Security exception launching $packageName", e)
            tryFallbackLaunch(packageName, cloneId)
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Exception launching $packageName", e)
            tryFallbackLaunch(packageName, cloneId)
        }
    }
    
    /**
     * Configure intent with proper flags for multi-instance launch
     */
    private fun configureMultiInstanceIntent(intent: Intent, packageName: String, cloneId: String) {
        // Clear existing flags
        intent.flags = 0
        
        // Add multi-instance flags
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        
        // Document mode for better task separation (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            intent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
        }
        
        // Launch adjacent for multi-window (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        
        // Set unique data URI to force new task
        val timestamp = System.currentTimeMillis()
        intent.data = Uri.parse("cloneapp-launch://$packageName/$cloneId/$timestamp")
        
        // Add identifying extras
        intent.putExtra("CLONE_SESSION_ID", cloneId)
        intent.putExtra("MULTI_INSTANCE_MODE", true)
        intent.putExtra("INSTANCE_TIMESTAMP", timestamp)
        intent.putExtra("USER_PROFILE", "profile_$cloneId")
        intent.putExtra("APP_INSTANCE_ID", "${packageName}_${cloneId}_${timestamp}")
        intent.putExtra("LAUNCHED_BY_CLONEAPP", true)
        
        // OEM-specific extras
        addOemSpecificExtras(intent, packageName, cloneId)
    }
    
    /**
     * Add OEM-specific extras for better compatibility
     */
    private fun addOemSpecificExtras(intent: Intent, packageName: String, cloneId: String) {
        when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> {
                intent.putExtra("samsung_multiwindow_intent", true)
                intent.putExtra("samsung_split_screen", true)
            }
            "xiaomi", "redmi", "poco" -> {
                intent.putExtra("miui_force_new_task", true)
                intent.putExtra("miui_dual_app_mode", true)
            }
            "oneplus", "oppo", "realme" -> {
                intent.putExtra("coloros_clone_launch", true)
                intent.putExtra("parallel_app_mode", true)
            }
            "huawei", "honor" -> {
                intent.putExtra("emui_twin_app_mode", true)
            }
            "vivo" -> {
                intent.putExtra("funtouch_app_clone", true)
            }
        }
    }
    
    /**
     * Fallback launch method if primary method fails
     */
    private fun tryFallbackLaunch(packageName: String, cloneId: String) {
        try {
            Log.d(TAG, "[$cloneId] Trying fallback launch for $packageName")
            
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("FALLBACK_LAUNCH", true)
            intent.putExtra("CLONE_ID", cloneId)
            
            startActivity(intent)
            Log.d(TAG, "[$cloneId] Fallback launch succeeded for $packageName")
            
        } catch (e: Exception) {
            Log.e(TAG, "[$cloneId] Fallback launch also failed for $packageName", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Ensure we finish if somehow still alive
        if (!isFinishing) {
            finish()
        }
    }
}