package com.cloneapp.multiaccount

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages ad SDK initialization off the main thread to prevent blocking clone launches.
 * Implements timeout mechanism to ensure app entry is never blocked by ads.
 */
class AdInitManager private constructor() {
    
    companion object {
        private const val TAG = "AdInitManager"
        private const val AD_INIT_TIMEOUT_MS = 3000L
        
        @Volatile
        private var instance: AdInitManager? = null
        
        fun getInstance(): AdInitManager {
            return instance ?: synchronized(this) {
                instance ?: AdInitManager().also { instance = it }
            }
        }
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adInitExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isInitialized = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private var initCallback: AdInitCallback? = null
    
    /**
     * Initialize ad SDK asynchronously with timeout.
     * This method is safe to call from any thread.
     */
    fun initializeAsync(context: Context, callback: AdInitCallback? = null) {
        if (isInitialized.get()) {
            Log.d(TAG, "Ad SDK already initialized")
            callback?.onAdInitComplete(true, 0)
            return
        }
        
        if (isInitializing.getAndSet(true)) {
            Log.d(TAG, "Ad SDK initialization already in progress")
            return
        }
        
        this.initCallback = callback
        val startTime = System.currentTimeMillis()
        val initCompleted = AtomicBoolean(false)
        
        Log.d(TAG, "Starting async ad SDK initialization")
        
        // Set up timeout - will proceed with app launch even if ads not ready
        mainHandler.postDelayed({
            if (!initCompleted.get()) {
                Log.w(TAG, "Ad SDK init timeout after ${AD_INIT_TIMEOUT_MS}ms - proceeding without ads")
                isInitializing.set(false)
                val elapsed = System.currentTimeMillis() - startTime
                callback?.onAdInitTimeout(elapsed)
            }
        }, AD_INIT_TIMEOUT_MS)
        
        // Perform ad initialization in background thread
        adInitExecutor.execute {
            try {
                // Perform actual ad SDK initialization here
                // This is where you would call MobileAds.initialize() or similar
                performAdInitialization(context)
                
                val elapsed = System.currentTimeMillis() - startTime
                initCompleted.set(true)
                isInitialized.set(true)
                isInitializing.set(false)
                
                Log.d(TAG, "Ad SDK initialized successfully in ${elapsed}ms")
                
                mainHandler.post {
                    callback?.onAdInitComplete(true, elapsed)
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                initCompleted.set(true)
                isInitializing.set(false)
                
                Log.e(TAG, "Ad SDK initialization failed: ", e)
                
                mainHandler.post {
                    callback?.onAdInitFailed(e.message ?: "Unknown error", elapsed)
                }
            }
        }
    }
    
    /**
     * Actual ad initialization logic - runs on background thread
     */
    private fun performAdInitialization(context: Context) {
        // Note: This is a placeholder. In production, you would call the actual
        // ad SDK initialization here. The key is that this runs off the main thread.
        
        // Example for AdMob (commented out as it depends on actual SDK):
        // com.google.android.gms.ads.MobileAds.initialize(context) { initStatus ->
        //     Log.d(TAG, "AdMob init status: $initStatus")
        // }
        
        // Simulate initialization time for testing
        // In production, remove this and use actual ad SDK
        try {
            Thread.sleep(100) // Minimal delay for actual init
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
    
    /**
     * Check if ads are ready to be shown
     */
    fun isAdReady(): Boolean = isInitialized.get()
    
    /**
     * Load a banner ad asynchronously
     */
    fun loadBannerAdAsync(
        context: Context,
        adUnitId: String,
        callback: BannerAdCallback
    ) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Ad SDK not initialized, attempting late init")
            initializeAsync(context, object : AdInitCallback {
                override fun onAdInitComplete(success: Boolean, elapsedMs: Long) {
                    if (success) {
                        performBannerLoad(context, adUnitId, callback)
                    } else {
                        callback.onBannerLoadFailed("Ad SDK not initialized")
                    }
                }
                override fun onAdInitFailed(error: String, elapsedMs: Long) {
                    callback.onBannerLoadFailed("Ad SDK init failed: $error")
                }
                override fun onAdInitTimeout(elapsedMs: Long) {
                    callback.onBannerLoadFailed("Ad SDK init timeout")
                }
            })
            return
        }
        
        performBannerLoad(context, adUnitId, callback)
    }
    
    private fun performBannerLoad(context: Context, adUnitId: String, callback: BannerAdCallback) {
        adInitExecutor.execute {
            try {
                // Placeholder for actual banner loading logic
                // In production, create and load the actual banner ad here
                
                mainHandler.post {
                    callback.onBannerLoaded()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Banner ad load failed: ", e)
                mainHandler.post {
                    callback.onBannerLoadFailed(e.message ?: "Load failed")
                }
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun shutdown() {
        adInitExecutor.shutdown()
        try {
            if (!adInitExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                adInitExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            adInitExecutor.shutdownNow()
        }
    }
    
    /**
     * Callback interface for ad initialization
     */
    interface AdInitCallback {
        fun onAdInitComplete(success: Boolean, elapsedMs: Long)
        fun onAdInitFailed(error: String, elapsedMs: Long)
        fun onAdInitTimeout(elapsedMs: Long)
    }
    
    /**
     * Callback interface for banner ad loading
     */
    interface BannerAdCallback {
        fun onBannerLoaded()
        fun onBannerLoadFailed(error: String)
    }
}
