package com.cloneapp.multiaccount.virtualization

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Permission Mediator for Cloned Apps
 * 
 * Manages permissions for virtualized applications:
 * - All permission requests go through host app
 * - Permissions are granted once and reused
 * - Per-clone permission states are tracked internally
 * - Handles runtime permissions (Android 6.0+)
 * - Manages dangerous vs normal permissions
 * - Supports permission groups
 */
class PermissionMediator(private val context: Context) {
    
    companion object {
        private const val TAG = "PermissionMediator"
        
        // Request code base for permission requests
        private const val REQUEST_CODE_BASE = 20000
        
        // Dangerous permissions that require runtime request
        val DANGEROUS_PERMISSIONS = setOf(
            // Calendar
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            // Camera
            Manifest.permission.CAMERA,
            // Contacts
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
            // Location
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            // Microphone
            Manifest.permission.RECORD_AUDIO,
            // Phone
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.ADD_VOICEMAIL,
            Manifest.permission.USE_SIP,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            // Sensors
            Manifest.permission.BODY_SENSORS,
            // SMS
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.RECEIVE_MMS,
            // Storage
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        
        // Permission groups
        val PERMISSION_GROUPS = mapOf(
            "CALENDAR" to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            "CAMERA" to listOf(Manifest.permission.CAMERA),
            "CONTACTS" to listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS),
            "LOCATION" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            "MICROPHONE" to listOf(Manifest.permission.RECORD_AUDIO),
            "PHONE" to listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG),
            "SENSORS" to listOf(Manifest.permission.BODY_SENSORS),
            "SMS" to listOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
            "STORAGE" to listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        )
        
        @Volatile
        private var INSTANCE: PermissionMediator? = null
        
        fun getInstance(context: Context): PermissionMediator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PermissionMediator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Per-clone permission states
    // Map<CloneId, Map<Permission, PermissionState>>
    private val clonePermissionStates = ConcurrentHashMap<String, MutableMap<String, PermissionState>>()
    
    // Pending permission requests
    private val pendingRequests = ConcurrentHashMap<Int, PermissionRequest>()
    
    // Request code counter
    private var requestCodeCounter = REQUEST_CODE_BASE
    
    /**
     * Check if a permission is granted for a clone
     */
    fun checkPermission(cloneId: String, permission: String): PermissionStatus {
        // First check if host app has the permission
        val hostStatus = ContextCompat.checkSelfPermission(context, permission)
        
        if (hostStatus != PackageManager.PERMISSION_GRANTED) {
            return PermissionStatus.DENIED_HOST
        }
        
        // Check clone-specific state
        val cloneState = clonePermissionStates[cloneId]?.get(permission)
        
        return when (cloneState) {
            PermissionState.GRANTED -> PermissionStatus.GRANTED
            PermissionState.DENIED -> PermissionStatus.DENIED_CLONE
            PermissionState.DENIED_PERMANENTLY -> PermissionStatus.DENIED_PERMANENTLY
            null -> {
                // Permission granted to host but not yet tracked for clone
                // Grant it to the clone automatically
                grantPermissionToClone(cloneId, permission)
                PermissionStatus.GRANTED
            }
        }
    }
    
    /**
     * Check multiple permissions for a clone
     */
    fun checkPermissions(cloneId: String, permissions: Array<String>): Map<String, PermissionStatus> {
        return permissions.associateWith { checkPermission(cloneId, it) }
    }
    
    /**
     * Request permissions for a clone
     * Returns request code for tracking result
     */
    fun requestPermissions(
        activity: Activity,
        cloneId: String,
        permissions: Array<String>,
        callback: PermissionRequestCallback
    ): Int {
        // Filter out already granted permissions
        val permissionsToRequest = permissions.filter { permission ->
            checkPermission(cloneId, permission) != PermissionStatus.GRANTED
        }.toTypedArray()
        
        if (permissionsToRequest.isEmpty()) {
            // All permissions already granted
            callback.onPermissionsResult(permissions.associateWith { PermissionResult.GRANTED })
            return -1
        }
        
        // First check if host needs to request
        val hostPermissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        val requestCode = ++requestCodeCounter
        
        if (hostPermissionsNeeded.isNotEmpty()) {
            // Need to request from system
            pendingRequests[requestCode] = PermissionRequest(
                cloneId = cloneId,
                permissions = permissions.toList(),
                callback = callback
            )
            
            ActivityCompat.requestPermissions(activity, hostPermissionsNeeded, requestCode)
        } else {
            // Host has permissions, just need to grant to clone
            permissionsToRequest.forEach { permission ->
                grantPermissionToClone(cloneId, permission)
            }
            callback.onPermissionsResult(permissions.associateWith { PermissionResult.GRANTED })
        }
        
        return requestCode
    }
    
    /**
     * Handle permission request result from activity
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val request = pendingRequests.remove(requestCode) ?: return
        
        val results = mutableMapOf<String, PermissionResult>()
        
        permissions.forEachIndexed { index, permission ->
            val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            
            if (granted) {
                grantPermissionToClone(request.cloneId, permission)
                results[permission] = PermissionResult.GRANTED
            } else {
                denyPermissionToClone(request.cloneId, permission)
                results[permission] = PermissionResult.DENIED
            }
        }
        
        // Fill in permissions not in result (already granted)
        request.permissions.forEach { permission ->
            if (!results.containsKey(permission)) {
                results[permission] = PermissionResult.GRANTED
            }
        }
        
        request.callback.onPermissionsResult(results)
        
        Log.d(TAG, "Permission request result for ${request.cloneId}: $results")
    }
    
    /**
     * Grant a permission to a clone (internal tracking)
     */
    fun grantPermissionToClone(cloneId: String, permission: String) {
        clonePermissionStates.getOrPut(cloneId) { mutableMapOf() }[permission] = PermissionState.GRANTED
        Log.d(TAG, "Granted $permission to clone $cloneId")
    }
    
    /**
     * Deny a permission to a clone (internal tracking)
     */
    fun denyPermissionToClone(cloneId: String, permission: String, permanent: Boolean = false) {
        val state = if (permanent) PermissionState.DENIED_PERMANENTLY else PermissionState.DENIED
        clonePermissionStates.getOrPut(cloneId) { mutableMapOf() }[permission] = state
        Log.d(TAG, "Denied $permission to clone $cloneId (permanent: $permanent)")
    }
    
    /**
     * Revoke a permission from a clone
     */
    fun revokePermissionFromClone(cloneId: String, permission: String) {
        clonePermissionStates[cloneId]?.remove(permission)
        Log.d(TAG, "Revoked $permission from clone $cloneId")
    }
    
    /**
     * Get all permissions for a clone
     */
    fun getClonePermissions(cloneId: String): Map<String, PermissionState> {
        return clonePermissionStates[cloneId]?.toMap() ?: emptyMap()
    }
    
    /**
     * Get granted permissions for a clone
     */
    fun getGrantedPermissions(cloneId: String): List<String> {
        return clonePermissionStates[cloneId]
            ?.filter { it.value == PermissionState.GRANTED }
            ?.keys
            ?.toList()
            ?: emptyList()
    }
    
    /**
     * Clear all permissions for a clone
     */
    fun clearClonePermissions(cloneId: String) {
        clonePermissionStates.remove(cloneId)
        Log.d(TAG, "Cleared all permissions for clone $cloneId")
    }
    
    /**
     * Check if permission requires runtime request
     */
    fun isDangerousPermission(permission: String): Boolean {
        return DANGEROUS_PERMISSIONS.contains(permission)
    }
    
    /**
     * Get permission group for a permission
     */
    fun getPermissionGroup(permission: String): String? {
        return PERMISSION_GROUPS.entries.find { it.value.contains(permission) }?.key
    }
    
    /**
     * Should show permission rationale for a clone
     */
    fun shouldShowRequestPermissionRationale(
        activity: Activity,
        permission: String
    ): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
    
    /**
     * Get required permissions for an app package
     */
    fun getRequiredPermissions(packageName: String): List<String> {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            packageInfo.requestedPermissions?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get permissions for $packageName", e)
            emptyList()
        }
    }
    
    /**
     * Get dangerous permissions required by an app
     */
    fun getDangerousPermissionsRequired(packageName: String): List<String> {
        return getRequiredPermissions(packageName).filter { isDangerousPermission(it) }
    }
    
    /**
     * Check if clone has all required dangerous permissions
     */
    fun hasAllRequiredPermissions(cloneId: String, packageName: String): Boolean {
        val required = getDangerousPermissionsRequired(packageName)
        return required.all { checkPermission(cloneId, it) == PermissionStatus.GRANTED }
    }
    
    /**
     * Get missing permissions for a clone/package
     */
    fun getMissingPermissions(cloneId: String, packageName: String): List<String> {
        val required = getDangerousPermissionsRequired(packageName)
        return required.filter { checkPermission(cloneId, it) != PermissionStatus.GRANTED }
    }
}

/**
 * Permission state
 */
enum class PermissionState {
    GRANTED,
    DENIED,
    DENIED_PERMANENTLY
}

/**
 * Permission status check result
 */
enum class PermissionStatus {
    GRANTED,
    DENIED_HOST,      // Host app doesn't have permission
    DENIED_CLONE,     // Clone-specific denial
    DENIED_PERMANENTLY
}

/**
 * Permission request result
 */
enum class PermissionResult {
    GRANTED,
    DENIED,
    DENIED_PERMANENTLY
}

/**
 * Pending permission request
 */
data class PermissionRequest(
    val cloneId: String,
    val permissions: List<String>,
    val callback: PermissionRequestCallback
)

/**
 * Callback for permission request results
 */
interface PermissionRequestCallback {
    fun onPermissionsResult(results: Map<String, PermissionResult>)
}

/**
 * Permission request builder for convenient permission handling
 */
class PermissionRequestBuilder(
    private val mediator: PermissionMediator,
    private val activity: Activity,
    private val cloneId: String
) {
    private val permissions = mutableListOf<String>()
    private var rationale: String? = null
    private var onGranted: (() -> Unit)? = null
    private var onDenied: ((List<String>) -> Unit)? = null
    
    /**
     * Add permission to request
     */
    fun addPermission(permission: String): PermissionRequestBuilder {
        permissions.add(permission)
        return this
    }
    
    /**
     * Add multiple permissions to request
     */
    fun addPermissions(vararg perms: String): PermissionRequestBuilder {
        permissions.addAll(perms)
        return this
    }
    
    /**
     * Set rationale to show user
     */
    fun setRationale(rationale: String): PermissionRequestBuilder {
        this.rationale = rationale
        return this
    }
    
    /**
     * Callback when all permissions granted
     */
    fun onAllGranted(callback: () -> Unit): PermissionRequestBuilder {
        this.onGranted = callback
        return this
    }
    
    /**
     * Callback when any permission denied
     */
    fun onDenied(callback: (List<String>) -> Unit): PermissionRequestBuilder {
        this.onDenied = callback
        return this
    }
    
    /**
     * Execute the permission request
     */
    fun request(): Int {
        return mediator.requestPermissions(
            activity,
            cloneId,
            permissions.toTypedArray(),
            object : PermissionRequestCallback {
                override fun onPermissionsResult(results: Map<String, PermissionResult>) {
                    val denied = results.filter { it.value != PermissionResult.GRANTED }.keys.toList()
                    
                    if (denied.isEmpty()) {
                        onGranted?.invoke()
                    } else {
                        onDenied?.invoke(denied)
                    }
                }
            }
        )
    }
}

/**
 * Extension function to create permission request builder
 */
fun PermissionMediator.requestBuilder(
    activity: Activity,
    cloneId: String
): PermissionRequestBuilder {
    return PermissionRequestBuilder(this, activity, cloneId)
}
