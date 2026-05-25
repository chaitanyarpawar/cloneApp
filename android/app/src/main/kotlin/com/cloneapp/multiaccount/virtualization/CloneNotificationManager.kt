package com.cloneapp.multiaccount.virtualization

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notification Manager for Cloned Apps
 * 
 * Captures and re-posts notifications from cloned apps:
 * - Intercepts notification objects from clones
 * - Re-posts via host process for system visibility
 * - Maintains correct app identity in notification
 * - Groups notifications by clone instance
 * - Handles notification channels (Android O+)
 * - Manages foreground services
 * - Handles background execution limits (Android 12+)
 */
class CloneNotificationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CloneNotificationMgr"
        
        // Channel IDs
        private const val CHANNEL_CLONE_NOTIFICATIONS = "clone_notifications"
        private const val CHANNEL_CLONE_FOREGROUND = "clone_foreground"
        private const val CHANNEL_CLONE_IMPORTANT = "clone_important"
        
        // Notification ID ranges per clone
        private const val NOTIFICATION_ID_BASE = 50000
        private const val NOTIFICATION_ID_RANGE = 1000
        
        @Volatile
        private var INSTANCE: CloneNotificationManager? = null
        
        fun getInstance(context: Context): CloneNotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloneNotificationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    // Active notifications per clone
    private val cloneNotifications = ConcurrentHashMap<String, MutableMap<Int, NotificationData>>()
    
    // Clone ID to notification ID base mapping
    private val cloneIdBases = ConcurrentHashMap<String, Int>()
    
    // Notification counter per clone
    private val notificationCounters = ConcurrentHashMap<String, AtomicInteger>()
    
    // Clone ID counter for assigning bases
    private val cloneCounter = AtomicInteger(0)
    
    init {
        createNotificationChannels()
    }
    
    /**
     * Create required notification channels
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Main clone notifications channel
            val mainChannel = NotificationChannel(
                CHANNEL_CLONE_NOTIFICATIONS,
                "Clone App Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from cloned apps"
                enableLights(true)
                enableVibration(true)
            }
            
            // Foreground service channel
            val foregroundChannel = NotificationChannel(
                CHANNEL_CLONE_FOREGROUND,
                "Clone Background Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background tasks for cloned apps"
                setShowBadge(false)
            }
            
            // Important notifications channel
            val importantChannel = NotificationChannel(
                CHANNEL_CLONE_IMPORTANT,
                "Important Clone Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important notifications from cloned apps"
                enableLights(true)
                enableVibration(true)
            }
            
            notificationManager.createNotificationChannels(
                listOf(mainChannel, foregroundChannel, importantChannel)
            )
            
            Log.d(TAG, "Notification channels created")
        }
    }
    
    /**
     * Create a notification channel for a specific clone
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun createCloneChannel(
        cloneId: String,
        appName: String,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT
    ): String {
        val channelId = "clone_${cloneId}_notifications"
        
        val channel = NotificationChannel(
            channelId,
            "$appName (Clone)",
            importance
        ).apply {
            description = "Notifications from $appName clone"
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }
        
        notificationManager.createNotificationChannel(channel)
        
        return channelId
    }
    
    /**
     * Post a notification for a cloned app
     */
    fun postCloneNotification(
        cloneId: String,
        appName: String,
        packageName: String,
        title: String,
        content: String,
        icon: Int? = null,
        largeIcon: Bitmap? = null,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
        autoCancel: Boolean = true,
        clickIntent: PendingIntent? = null,
        extras: Bundle? = null
    ): Int {
        // Get notification ID for this clone
        val notificationId = getNextNotificationId(cloneId)
        
        // Determine channel
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (priority) {
                NotificationCompat.PRIORITY_HIGH, NotificationCompat.PRIORITY_MAX -> CHANNEL_CLONE_IMPORTANT
                NotificationCompat.PRIORITY_LOW, NotificationCompat.PRIORITY_MIN -> CHANNEL_CLONE_FOREGROUND
                else -> CHANNEL_CLONE_NOTIFICATIONS
            }
        } else {
            CHANNEL_CLONE_NOTIFICATIONS
        }
        
        // Build notification
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle("[$appName] $title")
            .setContentText(content)
            .setPriority(priority)
            .setAutoCancel(autoCancel)
            .setGroup("clone_$cloneId")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        
        // Set icon
        if (icon != null) {
            builder.setSmallIcon(icon)
        } else {
            // Use app icon as fallback
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        }
        
        // Set large icon
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }
        
        // Set click intent
        if (clickIntent != null) {
            builder.setContentIntent(clickIntent)
        } else {
            // Create default intent to launch clone
            val launchIntent = createCloneLaunchIntent(cloneId, packageName)
            builder.setContentIntent(launchIntent)
        }
        
        // Add extras
        extras?.let { builder.setExtras(it) }
        
        // Add action to open clone
        builder.addAction(
            android.R.drawable.ic_menu_view,
            "Open",
            createCloneLaunchIntent(cloneId, packageName)
        )
        
        val notification = builder.build()
        
        // Post notification
        notificationManager.notify(notificationId, notification)
        
        // Track notification
        trackNotification(cloneId, notificationId, NotificationData(
            id = notificationId,
            cloneId = cloneId,
            packageName = packageName,
            title = title,
            content = content,
            timestamp = System.currentTimeMillis()
        ))
        
        Log.d(TAG, "Posted notification $notificationId for clone $cloneId")
        
        return notificationId
    }
    
    /**
     * Repost a notification from a cloned app
     * Used when intercepting notifications from the clone
     */
    fun repostNotification(
        cloneId: String,
        appName: String,
        packageName: String,
        originalNotification: Notification,
        originalId: Int
    ): Int {
        val notificationId = getNextNotificationId(cloneId)
        
        // Create modified notification
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CHANNEL_CLONE_NOTIFICATIONS
        } else {
            ""
        }
        
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        
        // Copy properties from original
        builder.apply {
            // Modify title to show clone identity
            val originalTitle = originalNotification.extras?.getString(Notification.EXTRA_TITLE) ?: ""
            setContentTitle("[$appName] $originalTitle")
            
            // Copy other properties
            setContentText(originalNotification.extras?.getString(Notification.EXTRA_TEXT))
            setSmallIcon(originalNotification.smallIcon ?: Icon.createWithResource(context, android.R.drawable.ic_dialog_info))
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                originalNotification.largeIcon?.let { setLargeIcon(it) }
            }
            
            // Set click intent to launch clone
            setContentIntent(createCloneLaunchIntent(cloneId, packageName))
            
            setAutoCancel(true)
            
            // Group with other clone notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setGroup("clone_$cloneId")
            }
        }
        
        val notification = builder.build()
        notificationManager.notify(notificationId, notification)
        
        // Track notification
        trackNotification(cloneId, notificationId, NotificationData(
            id = notificationId,
            cloneId = cloneId,
            packageName = packageName,
            title = originalNotification.extras?.getString(Notification.EXTRA_TITLE) ?: "",
            content = originalNotification.extras?.getString(Notification.EXTRA_TEXT) ?: "",
            timestamp = System.currentTimeMillis(),
            originalId = originalId
        ))
        
        Log.d(TAG, "Reposted notification $notificationId for clone $cloneId (original: $originalId)")
        
        return notificationId
    }
    
    /**
     * Create a foreground service notification for a clone
     */
    fun createForegroundNotification(
        cloneId: String,
        appName: String,
        packageName: String,
        content: String = "Running in background"
    ): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CHANNEL_CLONE_FOREGROUND
        } else {
            ""
        }
        
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("$appName (Clone)")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(createCloneLaunchIntent(cloneId, packageName))
            .build()
    }
    
    /**
     * Cancel a specific notification
     */
    fun cancelNotification(cloneId: String, notificationId: Int) {
        notificationManager.cancel(notificationId)
        
        cloneNotifications[cloneId]?.remove(notificationId)
        
        Log.d(TAG, "Cancelled notification $notificationId for clone $cloneId")
    }
    
    /**
     * Cancel all notifications for a clone
     */
    fun cancelAllNotifications(cloneId: String) {
        val notifications = cloneNotifications[cloneId]?.values ?: return
        
        notifications.forEach { data ->
            notificationManager.cancel(data.id)
        }
        
        cloneNotifications.remove(cloneId)
        
        Log.d(TAG, "Cancelled all notifications for clone $cloneId")
    }
    
    /**
     * Get active notification count for a clone
     */
    fun getActiveNotificationCount(cloneId: String): Int {
        return cloneNotifications[cloneId]?.size ?: 0
    }
    
    /**
     * Get all active notifications for a clone
     */
    fun getActiveNotifications(cloneId: String): List<NotificationData> {
        return cloneNotifications[cloneId]?.values?.toList() ?: emptyList()
    }
    
    /**
     * Create a summary notification for grouped clone notifications
     */
    fun postSummaryNotification(cloneId: String, appName: String, count: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        val channelId = CHANNEL_CLONE_NOTIFICATIONS
        val summaryId = getCloneNotificationBase(cloneId) - 1
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("$appName (Clone)")
            .setContentText("$count new notifications")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setGroup("clone_$cloneId")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(summaryId, notification)
    }
    
    // ===== Private Helper Methods =====
    
    private fun getCloneNotificationBase(cloneId: String): Int {
        return cloneIdBases.getOrPut(cloneId) {
            NOTIFICATION_ID_BASE + (cloneCounter.incrementAndGet() * NOTIFICATION_ID_RANGE)
        }
    }
    
    private fun getNextNotificationId(cloneId: String): Int {
        val base = getCloneNotificationBase(cloneId)
        val counter = notificationCounters.getOrPut(cloneId) { AtomicInteger(0) }
        return base + (counter.incrementAndGet() % NOTIFICATION_ID_RANGE)
    }
    
    private fun trackNotification(cloneId: String, notificationId: Int, data: NotificationData) {
        cloneNotifications.getOrPut(cloneId) { mutableMapOf() }[notificationId] = data
    }
    
    private fun createCloneLaunchIntent(cloneId: String, packageName: String): PendingIntent {
        val intent = Intent(context, VirtualProxyActivity::class.java).apply {
            action = "com.cloneapp.multiaccount.PROXY_LAUNCH"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ActivityProxyManager.EXTRA_TARGET_PACKAGE, packageName)
            putExtra(ActivityProxyManager.EXTRA_CLONE_ID, cloneId)
            data = android.net.Uri.parse("cloneapp-notification://$packageName/$cloneId")
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        return PendingIntent.getActivity(context, cloneId.hashCode(), intent, flags)
    }
}

/**
 * Notification data
 */
data class NotificationData(
    val id: Int,
    val cloneId: String,
    val packageName: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val originalId: Int? = null
)

/**
 * Foreground Service Manager for cloned apps
 * Handles background execution limits on Android 12+
 */
class CloneForegroundServiceManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CloneForegroundSvcMgr"
        
        @Volatile
        private var INSTANCE: CloneForegroundServiceManager? = null
        
        fun getInstance(context: Context): CloneForegroundServiceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloneForegroundServiceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val notificationManager = CloneNotificationManager.getInstance(context)
    
    // Active foreground services per clone
    private val activeForegroundServices = ConcurrentHashMap<String, ForegroundServiceInfo>()
    
    /**
     * Start a foreground service for a clone
     * Required for background execution on Android 12+
     */
    fun startForegroundForClone(
        cloneId: String,
        appName: String,
        packageName: String,
        serviceType: Int = 0 // ForegroundServiceType
    ): ForegroundServiceResult {
        try {
            // Check if already running
            if (activeForegroundServices.containsKey(cloneId)) {
                return ForegroundServiceResult(
                    success = true,
                    alreadyRunning = true,
                    notificationId = activeForegroundServices[cloneId]!!.notificationId
                )
            }
            
            // Create foreground notification
            val notification = notificationManager.createForegroundNotification(
                cloneId, appName, packageName
            )
            
            // Generate notification ID
            val notificationId = "fg_$cloneId".hashCode()
            
            // Track service
            activeForegroundServices[cloneId] = ForegroundServiceInfo(
                cloneId = cloneId,
                packageName = packageName,
                notificationId = notificationId,
                startTime = System.currentTimeMillis()
            )
            
            Log.d(TAG, "Started foreground for clone $cloneId")
            
            return ForegroundServiceResult(
                success = true,
                notificationId = notificationId,
                notification = notification
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground for clone $cloneId", e)
            return ForegroundServiceResult(
                success = false,
                error = e.message
            )
        }
    }
    
    /**
     * Stop foreground service for a clone
     */
    fun stopForegroundForClone(cloneId: String): Boolean {
        val serviceInfo = activeForegroundServices.remove(cloneId) ?: return false
        
        // Cancel the notification
        val notificationMgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationMgr.cancel(serviceInfo.notificationId)
        
        Log.d(TAG, "Stopped foreground for clone $cloneId")
        
        return true
    }
    
    /**
     * Check if clone has active foreground service
     */
    fun hasForegroundService(cloneId: String): Boolean {
        return activeForegroundServices.containsKey(cloneId)
    }
    
    /**
     * Get foreground service info for a clone
     */
    fun getForegroundServiceInfo(cloneId: String): ForegroundServiceInfo? {
        return activeForegroundServices[cloneId]
    }
    
    /**
     * Get all active foreground services
     */
    fun getAllActiveForegroundServices(): List<ForegroundServiceInfo> {
        return activeForegroundServices.values.toList()
    }
}

/**
 * Foreground service info
 */
data class ForegroundServiceInfo(
    val cloneId: String,
    val packageName: String,
    val notificationId: Int,
    val startTime: Long
)

/**
 * Result of starting foreground service
 */
data class ForegroundServiceResult(
    val success: Boolean,
    val error: String? = null,
    val alreadyRunning: Boolean = false,
    val notificationId: Int = 0,
    val notification: Notification? = null
)
