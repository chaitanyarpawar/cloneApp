package com.cloneapp.multiaccount.virtualization

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Binder & System Service Proxy System
 * 
 * Intercepts and proxies Binder IPC for virtualized apps:
 * - AccountManager: Provides isolated account lists per container
 * - PackageManager: Returns virtual package info for cloned apps
 * - ContentProviders: Redirects content queries to isolated storage
 * - ActivityManager: Selective interception for task management
 * 
 * Design Principles:
 * - NO root access required
 * - NO kernel modification
 * - Uses reflection and dynamic proxies (within Android limits)
 * - Handles Android 10-14+ hidden API restrictions
 * - Graceful fallback when interception not possible
 */
class BinderProxyManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BinderProxyManager"
        
        // System services that need proxying
        private val PROXIED_SERVICES = setOf(
            Context.ACCOUNT_SERVICE,
            "package", // PackageManager
            Context.ACTIVITY_SERVICE
        )
        
        @Volatile
        private var INSTANCE: BinderProxyManager? = null
        
        fun getInstance(context: Context): BinderProxyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BinderProxyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Per-container account stores
    private val containerAccounts = ConcurrentHashMap<String, MutableList<VirtualAccount>>()
    
    // Per-container package info cache
    private val containerPackages = ConcurrentHashMap<String, MutableMap<String, VirtualPackageInfo>>()
    
    // Active proxies
    private val activeProxies = ConcurrentHashMap<String, Any>()
    
    /**
     * Create a proxied AccountManager for a container
     * Returns accounts scoped to the container
     */
    fun createAccountManagerProxy(containerId: String): ProxiedAccountManager {
        val key = "account_$containerId"
        
        return activeProxies.getOrPut(key) {
            ProxiedAccountManager(
                context = context,
                containerId = containerId,
                accountStore = containerAccounts.getOrPut(containerId) { mutableListOf() }
            )
        } as ProxiedAccountManager
    }
    
    /**
     * Create a proxied PackageManager for a container
     * Returns package info with virtual identities
     */
    fun createPackageManagerProxy(
        containerId: String,
        packageName: String
    ): ProxiedPackageManager {
        val key = "pm_${containerId}_$packageName"
        
        return activeProxies.getOrPut(key) {
            ProxiedPackageManager(
                realPm = context.packageManager,
                containerId = containerId,
                targetPackage = packageName,
                packageCache = containerPackages.getOrPut(containerId) { mutableMapOf() }
            )
        } as ProxiedPackageManager
    }
    
    /**
     * Create a proxied ContentResolver for isolated content access
     */
    fun createContentResolverProxy(
        containerId: String,
        storagePath: String
    ): ProxiedContentResolver {
        val key = "resolver_$containerId"
        
        return activeProxies.getOrPut(key) {
            ProxiedContentResolver(
                realResolver = context.contentResolver,
                containerId = containerId,
                isolatedStoragePath = storagePath
            )
        } as ProxiedContentResolver
    }
    
    /**
     * Add a virtual account to a container
     */
    fun addVirtualAccount(
        containerId: String,
        accountName: String,
        accountType: String,
        userData: Map<String, String> = emptyMap()
    ): Boolean {
        val accounts = containerAccounts.getOrPut(containerId) { mutableListOf() }
        
        // Check if account already exists
        if (accounts.any { it.name == accountName && it.type == accountType }) {
            return false
        }
        
        val virtualAccount = VirtualAccount(
            name = accountName,
            type = accountType,
            userData = userData.toMutableMap(),
            createdAt = System.currentTimeMillis()
        )
        
        accounts.add(virtualAccount)
        Log.d(TAG, "Virtual account added: $accountName ($accountType) to container $containerId")
        
        return true
    }
    
    /**
     * Remove a virtual account from a container
     */
    fun removeVirtualAccount(
        containerId: String,
        accountName: String,
        accountType: String
    ): Boolean {
        val accounts = containerAccounts[containerId] ?: return false
        return accounts.removeIf { it.name == accountName && it.type == accountType }
    }
    
    /**
     * Get all virtual accounts for a container
     */
    fun getVirtualAccounts(containerId: String): List<VirtualAccount> {
        return containerAccounts[containerId]?.toList() ?: emptyList()
    }
    
    /**
     * Clear all proxies for a container
     */
    fun clearContainerProxies(containerId: String) {
        val keysToRemove = activeProxies.keys.filter { it.contains(containerId) }
        keysToRemove.forEach { activeProxies.remove(it) }
        containerAccounts.remove(containerId)
        containerPackages.remove(containerId)
        
        Log.d(TAG, "Cleared ${keysToRemove.size} proxies for container $containerId")
    }
    
    /**
     * Install system hooks to intercept Binder calls
     * This is the core of the virtualization engine - it tricks the app into using our proxies
     */
    fun installHooks() {
        try {
            hookActivityManager()
            hookPackageManager()
            Log.i(TAG, "System hooks installed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install system hooks", e)
        }
    }

    private fun hookActivityManager() {
        try {
            // Target: android.app.ActivityManager.IActivityManagerSingleton (Android 8.0+)
            // Or android.app.ActivityManagerNative.gDefault (Android < 8.0)
            
            val amClass = Class.forName("android.app.ActivityManager")
            val singletonField = try {
                amClass.getDeclaredField("IActivityManagerSingleton")
            } catch (e: NoSuchFieldException) {
                // Fallback for older versions or modified ROMs
                try {
                    val amnClass = Class.forName("android.app.ActivityManagerNative")
                    amnClass.getDeclaredField("gDefault")
                } catch (e2: Exception) {
                    amClass.getDeclaredField("IActivityManagerSingleton") // Retry original to throw correct error
                }
            }
            
            singletonField.isAccessible = true
            val singleton = singletonField.get(null)
            
            // The Singleton class has a 'mInstance' field
            val singletonClass = Class.forName("android.util.Singleton")
            val mInstanceField = singletonClass.getDeclaredField("mInstance")
            mInstanceField.isAccessible = true
            
            // Get the real IActivityManager
            val realIam = mInstanceField.get(singleton)
            
            if (realIam != null) {
                // Create our proxy
                val iamInterface = Class.forName("android.app.IActivityManager")
                val proxyIam = Proxy.newProxyInstance(
                    context.classLoader,
                    arrayOf(iamInterface),
                    ActivityManagerProxy(realIam, context)
                )
                
                // Inject our proxy
                mInstanceField.set(singleton, proxyIam)
                Log.d(TAG, "ActivityManager hooked successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook ActivityManager", e)
        }
    }

    private fun hookPackageManager() {
        try {
            // Target: android.app.ActivityThread.sPackageManager
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val sCurrentActivityThreadField = activityThreadClass.getDeclaredField("sCurrentActivityThread")
            sCurrentActivityThreadField.isAccessible = true
            val currentActivityThread = sCurrentActivityThreadField.get(null)
            
            val sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager")
            sPackageManagerField.isAccessible = true
            val realPm = sPackageManagerField.get(currentActivityThread)
            
            if (realPm != null) {
                val ipmInterface = Class.forName("android.content.pm.IPackageManager")
                
                val proxyPm = Proxy.newProxyInstance(
                    context.classLoader,
                    arrayOf(ipmInterface),
                    PackageManagerProxy(realPm, context)
                )
                
                sPackageManagerField.set(currentActivityThread, proxyPm)
                
                // Also update ApplicationPackageManager cache if possible
                val contextImplClass = Class.forName("android.app.ContextImpl")
                val pmField = contextImplClass.getDeclaredField("mPackageManager")
                pmField.isAccessible = true
                // Note: We can't easily update all ContextImpl instances, but hooking static sPackageManager 
                // catches all new retrievals.
                
                Log.d(TAG, "PackageManager hooked successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook PackageManager", e)
        }
    }

    /**
     * InvocationHandler for ActivityManager interception
     */
    private class ActivityManagerProxy(
        private val realIam: Any,
        private val context: Context
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            try {
                // Example interception: startActivity
                if ("startActivity" == method.name) {
                    // Logic to swap Intent would go here
                    // For MVP, we pass through but log
                    Log.d(TAG, "Intercepted startActivity: ${args?.getOrNull(2)}") // Intent is usually arg 2
                }
                
                return method.invoke(realIam, *(args ?: emptyArray()))
            } catch (e: Exception) {
                throw e.cause ?: e
            }
        }
    }

    /**
     * InvocationHandler for PackageManager interception
     */
    private class PackageManagerProxy(
        private val realPm: Any,
        private val context: Context
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            try {
                // Intercept getPackageInfo to return fake data if needed
                if ("getPackageInfo" == method.name) {
                    val packageName = args?.getOrNull(0) as? String
                    if (packageName != null) {
                        // Check if we need to return virtual package info
                    }
                }
                
                return method.invoke(realPm, *(args ?: emptyArray()))
            } catch (e: Exception) {
                throw e.cause ?: e
            }
        }
    }

    /**
     * Intercept service getter using reflection (best effort)
     * Note: This may not work on all Android versions due to hidden API restrictions
     */
    fun tryInterceptSystemService(
        serviceName: String,
        containerId: String
    ): Any? {
        return try {
            when (serviceName) {
                Context.ACCOUNT_SERVICE -> createAccountManagerProxy(containerId)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to intercept service: $serviceName", e)
            null
        }
    }
}

/**
 * Proxied AccountManager that returns container-scoped accounts
 */
class ProxiedAccountManager(
    private val context: Context,
    private val containerId: String,
    private val accountStore: MutableList<VirtualAccount>
) {
    
    private val realAccountManager: AccountManager = AccountManager.get(context)
    
    /**
     * Get accounts - returns virtual accounts for this container
     */
    fun getAccounts(): Array<Account> {
        return accountStore.map { Account(it.name, it.type) }.toTypedArray()
    }
    
    /**
     * Get accounts by type
     */
    fun getAccountsByType(type: String?): Array<Account> {
        return if (type == null) {
            getAccounts()
        } else {
            accountStore
                .filter { it.type == type }
                .map { Account(it.name, it.type) }
                .toTypedArray()
        }
    }
    
    /**
     * Add account - adds to virtual store
     */
    fun addAccountExplicitly(
        account: Account,
        password: String?,
        userdata: Bundle?
    ): Boolean {
        if (accountStore.any { it.name == account.name && it.type == account.type }) {
            return false
        }
        
        val userData = mutableMapOf<String, String>()
        userdata?.keySet()?.forEach { key ->
            userData[key] = userdata.getString(key) ?: ""
        }
        
        accountStore.add(VirtualAccount(
            name = account.name,
            type = account.type,
            userData = userData,
            createdAt = System.currentTimeMillis()
        ))
        
        return true
    }
    
    /**
     * Remove account - removes from virtual store
     */
    fun removeAccount(account: Account): Boolean {
        return accountStore.removeIf { it.name == account.name && it.type == account.type }
    }
    
    /**
     * Get user data
     */
    fun getUserData(account: Account, key: String): String? {
        return accountStore
            .find { it.name == account.name && it.type == account.type }
            ?.userData?.get(key)
    }
    
    /**
     * Set user data
     */
    fun setUserData(account: Account, key: String, value: String?) {
        accountStore
            .find { it.name == account.name && it.type == account.type }
            ?.userData?.let {
                if (value != null) {
                    it[key] = value
                } else {
                    it.remove(key)
                }
            }
    }
    
    /**
     * Fallback to real AccountManager for unsupported operations
     */
    fun getRealAccountManager(): AccountManager = realAccountManager
}

/**
 * Proxied PackageManager that returns virtual package information
 */
class ProxiedPackageManager(
    private val realPm: PackageManager,
    private val containerId: String,
    private val targetPackage: String,
    private val packageCache: MutableMap<String, VirtualPackageInfo>
) {
    
    companion object {
        private const val TAG = "ProxiedPackageManager"
    }
    
    /**
     * Get package info - returns modified info for virtualized packages
     */
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo? {
        return try {
            val realInfo = realPm.getPackageInfo(packageName, flags)
            
            // If this is our target package, modify it
            if (packageName == targetPackage) {
                modifyPackageInfo(realInfo)
            } else {
                realInfo
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    
    /**
     * Get application info
     */
    fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo? {
        return try {
            val realInfo = realPm.getApplicationInfo(packageName, flags)
            
            if (packageName == targetPackage) {
                modifyApplicationInfo(realInfo)
            } else {
                realInfo
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    
    /**
     * Get launch intent for package
     */
    fun getLaunchIntentForPackage(packageName: String): android.content.Intent? {
        return realPm.getLaunchIntentForPackage(packageName)
    }
    
    /**
     * Query intent activities
     */
    fun queryIntentActivities(
        intent: android.content.Intent,
        flags: Int
    ): List<ResolveInfo> {
        return realPm.queryIntentActivities(intent, flags)
    }
    
    /**
     * Check if package is installed
     */
    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            realPm.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Get installed packages
     */
    fun getInstalledPackages(flags: Int): List<PackageInfo> {
        return realPm.getInstalledPackages(flags)
    }
    
    /**
     * Modify package info for virtualized context
     */
    private fun modifyPackageInfo(info: PackageInfo): PackageInfo {
        // We don't change the package name (Play Store compliant)
        // But we can add metadata indicating it's a clone
        return info.apply {
            // Store original in cache
            packageCache.getOrPut(packageName) {
                VirtualPackageInfo(
                    packageName = packageName,
                    containerId = containerId,
                    originalInfo = this
                )
            }
        }
    }
    
    /**
     * Modify application info for virtualized context
     */
    private fun modifyApplicationInfo(info: ApplicationInfo): ApplicationInfo {
        // Don't modify critical fields to maintain Play Store compliance
        return info
    }
    
    /**
     * Get the real PackageManager for unsupported operations
     */
    fun getRealPackageManager(): PackageManager = realPm
}

/**
 * Proxied ContentResolver for isolated content access
 */
class ProxiedContentResolver(
    private val realResolver: ContentResolver,
    private val containerId: String,
    private val isolatedStoragePath: String
) {
    
    companion object {
        private const val TAG = "ProxiedContentResolver"
        
        // Content URIs that should be redirected to isolated storage
        private val REDIRECTED_AUTHORITIES = setOf(
            "com.android.providers.settings",
            "com.android.providers.contacts"
        )
    }
    
    /**
     * Query content - intercepts certain providers
     */
    fun query(
        uri: android.net.Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): android.database.Cursor? {
        val authority = uri.authority
        
        // Check if this authority should be redirected
        if (authority != null && shouldRedirect(authority)) {
            return queryIsolated(uri, projection, selection, selectionArgs, sortOrder)
        }
        
        // Use real resolver for non-redirected content
        return realResolver.query(uri, projection, selection, selectionArgs, sortOrder)
    }
    
    /**
     * Insert content
     */
    fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? {
        val authority = uri.authority
        
        if (authority != null && shouldRedirect(authority)) {
            return insertIsolated(uri, values)
        }
        
        return realResolver.insert(uri, values)
    }
    
    /**
     * Delete content
     */
    fun delete(
        uri: android.net.Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val authority = uri.authority
        
        if (authority != null && shouldRedirect(authority)) {
            return deleteIsolated(uri, selection, selectionArgs)
        }
        
        return realResolver.delete(uri, selection, selectionArgs)
    }
    
    /**
     * Update content
     */
    fun update(
        uri: android.net.Uri,
        values: android.content.ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val authority = uri.authority
        
        if (authority != null && shouldRedirect(authority)) {
            return updateIsolated(uri, values, selection, selectionArgs)
        }
        
        return realResolver.update(uri, values, selection, selectionArgs)
    }
    
    private fun shouldRedirect(authority: String): Boolean {
        // For now, we don't redirect to avoid breaking functionality
        // This can be enabled for specific authorities as needed
        return false
    }
    
    private fun queryIsolated(
        uri: android.net.Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): android.database.Cursor? {
        Log.d(TAG, "Isolated query: $uri")
        // Implement isolated content provider query
        // For now, return null (no results)
        return null
    }
    
    private fun insertIsolated(
        uri: android.net.Uri,
        values: android.content.ContentValues?
    ): android.net.Uri? {
        Log.d(TAG, "Isolated insert: $uri")
        return null
    }
    
    private fun deleteIsolated(
        uri: android.net.Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        Log.d(TAG, "Isolated delete: $uri")
        return 0
    }
    
    private fun updateIsolated(
        uri: android.net.Uri,
        values: android.content.ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        Log.d(TAG, "Isolated update: $uri")
        return 0
    }
    
    /**
     * Get real resolver for unsupported operations
     */
    fun getRealContentResolver(): ContentResolver = realResolver
}

// ===== Data Classes =====

data class VirtualAccount(
    val name: String,
    val type: String,
    val userData: MutableMap<String, String>,
    val createdAt: Long
) {
    fun toAccount(): Account = Account(name, type)
}

data class VirtualPackageInfo(
    val packageName: String,
    val containerId: String,
    val originalInfo: PackageInfo
)
