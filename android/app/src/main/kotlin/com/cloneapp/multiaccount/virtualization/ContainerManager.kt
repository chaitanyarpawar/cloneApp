package com.cloneapp.multiaccount.virtualization

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Core Container Manager for Android App Virtualization
 * 
 * Architecture similar to Parallel Space / Dual Space:
 * - Manages lightweight virtual containers in userland
 * - Each container has separate process space, data directories, and UID mapping
 * - Containers persist across app restarts
 * - NO root, NO APK modification, NO kernel hooks
 * 
 * Container Structure:
 * /data/data/[host_app]/virtual/containers/[container_id]/
 *   ├── metadata.json           # Container configuration
 *   ├── uid_mapping.json        # Virtual UID assignments
 *   ├── apps/
 *   │   └── [package_name]/
 *   │       ├── data/           # App data directory
 *   │       ├── cache/          # App cache
 *   │       ├── databases/      # SQLite databases
 *   │       ├── shared_prefs/   # SharedPreferences
 *   │       ├── files/          # Files directory
 *   │       └── lib/            # Native libraries (symlinked)
 *   └── runtime/
 *       ├── dex_opt/            # Optimized DEX files
 *       └── class_cache/        # ClassLoader cache
 */
class ContainerManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ContainerManager"
        private const val CONTAINER_VERSION = 2
        private const val MAX_CONTAINERS = 10
        private const val MAX_APPS_PER_CONTAINER = 20
        
        // Virtual UID base (offset from real UID)
        private const val VIRTUAL_UID_BASE = 100000
        
        @Volatile
        private var INSTANCE: ContainerManager? = null
        
        fun getInstance(context: Context): ContainerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContainerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val containersRoot: File = File(context.filesDir, "virtual/containers")
    private val activeContainers = ConcurrentHashMap<String, VirtualContainer>()
    private val uidCounter = AtomicInteger(VIRTUAL_UID_BASE)
    private val containerLock = Object()
    
    init {
        containersRoot.mkdirs()
        loadExistingContainers()
    }
    
    /**
     * Create a new virtual container
     */
    fun createContainer(containerName: String? = null): ContainerResult {
        synchronized(containerLock) {
            if (activeContainers.size >= MAX_CONTAINERS) {
                return ContainerResult(
                    success = false,
                    error = "Maximum container limit reached ($MAX_CONTAINERS)"
                )
            }
            
            try {
                val containerId = generateContainerId()
                val containerDir = File(containersRoot, containerId)
                
                if (containerDir.exists()) {
                    return ContainerResult(
                        success = false,
                        error = "Container already exists"
                    )
                }
                
                // Create container structure
                val structure = createContainerStructure(containerDir)
                if (!structure.success) {
                    return ContainerResult(
                        success = false,
                        error = "Failed to create container structure: ${structure.error}"
                    )
                }
                
                // Assign virtual UID
                val virtualUid = uidCounter.incrementAndGet()
                
                // Create container metadata
                val container = VirtualContainer(
                    id = containerId,
                    name = containerName ?: "Container ${activeContainers.size + 1}",
                    rootPath = containerDir.absolutePath,
                    virtualUid = virtualUid,
                    createdAt = System.currentTimeMillis(),
                    version = CONTAINER_VERSION,
                    status = ContainerStatus.CREATED
                )
                
                // Save metadata
                saveContainerMetadata(container)
                
                // Register container
                activeContainers[containerId] = container
                
                Log.d(TAG, "Container created: $containerId (UID: $virtualUid)")
                
                return ContainerResult(
                    success = true,
                    containerId = containerId,
                    container = container
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create container", e)
                return ContainerResult(
                    success = false,
                    error = "Container creation failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Install an app into a container (without modifying APK)
     */
    fun installAppInContainer(
        containerId: String,
        packageName: String
    ): InstallResult {
        val container = activeContainers[containerId]
            ?: return InstallResult(false, "Container not found")
        
        try {
            // Verify app is installed on system
            val pm = context.packageManager
            val appInfo: ApplicationInfo
            try {
                appInfo = pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return InstallResult(false, "App not installed on device")
            }
            
            // Check if already installed in container
            val installedApps = getInstalledApps(containerId)
            if (installedApps.size >= MAX_APPS_PER_CONTAINER) {
                return InstallResult(false, "Maximum apps per container reached")
            }
            
            // Create app-specific directories
            val appDir = File(container.rootPath, "apps/$packageName")
            createAppDirectories(appDir)
            
            // Create symlink to native libraries (no copy needed)
            val libDir = File(appDir, "lib")
            if (appInfo.nativeLibraryDir != null) {
                createSymlink(appInfo.nativeLibraryDir, libDir.absolutePath)
            }
            
            // Create app metadata
            val appMetadata = ContainerAppMetadata(
                packageName = packageName,
                apkPath = appInfo.sourceDir,
                nativeLibPath = appInfo.nativeLibraryDir,
                installedAt = System.currentTimeMillis(),
                lastLaunched = 0,
                dataPath = appDir.absolutePath,
                virtualUid = container.virtualUid
            )
            
            saveAppMetadata(containerId, appMetadata)
            
            Log.d(TAG, "App installed in container: $packageName -> $containerId")
            
            return InstallResult(
                success = true,
                appPath = appDir.absolutePath,
                metadata = appMetadata
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install app in container", e)
            return InstallResult(false, "Installation failed: ${e.message}")
        }
    }
    
    /**
     * Prepare container for app launch
     * Sets up isolated environment and returns launch context
     */
    fun prepareForLaunch(
        containerId: String,
        packageName: String
    ): LaunchPreparation {
        Log.d(TAG, "[prepareForLaunch] START: containerId=$containerId, package=$packageName")
        
        val container = activeContainers[containerId]
            ?: run {
                Log.e(TAG, "[prepareForLaunch] Container NOT FOUND: $containerId")
                Log.d(TAG, "[prepareForLaunch] Available containers: ${activeContainers.keys}")
                return LaunchPreparation(false, error = "Container not found: $containerId")
            }
        
        try {
            // Verify app is installed in container
            val appMetadata = getAppMetadata(containerId, packageName)
                ?: run {
                    Log.e(TAG, "[prepareForLaunch] App NOT installed in container: $containerId/$packageName")
                    return LaunchPreparation(false, error = "App not installed in container: $containerId")
                }
            
            Log.d(TAG, "[prepareForLaunch] App found: apkPath=${appMetadata.apkPath}")
            
            // Update container status
            container.status = ContainerStatus.RUNNING
            container.lastActivity = System.currentTimeMillis()
            
            // Prepare isolated paths
            val paths = IsolatedPaths(
                dataDir = "${container.rootPath}/apps/$packageName/data",
                cacheDir = "${container.rootPath}/apps/$packageName/cache",
                filesDir = "${container.rootPath}/apps/$packageName/files",
                databasesDir = "${container.rootPath}/apps/$packageName/databases",
                sharedPrefsDir = "${container.rootPath}/apps/$packageName/shared_prefs",
                dexOptDir = "${container.rootPath}/runtime/dex_opt/$packageName",
                apkPath = appMetadata.apkPath,
                nativeLibPath = appMetadata.nativeLibPath ?: ""
            )
            
            // Ensure all directories exist
            ensureDirectoriesExist(paths)
            
            // Update last launch time
            appMetadata.lastLaunched = System.currentTimeMillis()
            saveAppMetadata(containerId, appMetadata)
            
            Log.d(TAG, "Container prepared for launch: $containerId/$packageName")
            
            return LaunchPreparation(
                success = true,
                containerId = containerId,
                packageName = packageName,
                virtualUid = container.virtualUid,
                paths = paths
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare for launch", e)
            return LaunchPreparation(false, error = "Preparation failed: ${e.message}")
        }
    }
    
    /**
     * Get or create container for a specific clone instance.
     * IMPORTANT: Each cloneId gets its OWN container for true isolation.
     * Multiple clones of the same app each get separate containers.
     */
    fun getOrCreateContainerForApp(
        packageName: String,
        cloneId: String
    ): ContainerResult {
        Log.d(TAG, "[getOrCreateContainerForApp] packageName=$packageName, cloneId=$cloneId")
        Log.d(TAG, "[getOrCreateContainerForApp] Active containers: ${activeContainers.size}")
        
        // Check if THIS specific cloneId already has a container
        // We match by container name which encodes the cloneId
        for ((id, container) in activeContainers) {
            if (container.name == "Clone_$cloneId") {
                val appMetadata = getAppMetadata(id, packageName)
                if (appMetadata != null) {
                    Log.d(TAG, "[getOrCreateContainerForApp] Reusing existing container $id for clone $cloneId")
                    return ContainerResult(
                        success = true,
                        containerId = id,
                        container = container
                    )
                }
            }
        }
        
        // ALWAYS create a NEW container for a new cloneId, even if same package
        // This is critical for 2nd instance isolation
        Log.d(TAG, "[getOrCreateContainerForApp] Creating NEW container for clone $cloneId (package: $packageName)")
        val containerResult = createContainer("Clone_$cloneId")
        if (!containerResult.success) {
            Log.e(TAG, "[getOrCreateContainerForApp] Container creation failed: ${containerResult.error}")
            return containerResult
        }
        
        // Install app in the new container
        val installResult = installAppInContainer(containerResult.containerId!!, packageName)
        if (!installResult.success) {
            // Cleanup failed container
            Log.e(TAG, "[getOrCreateContainerForApp] App install failed: ${installResult.error}")
            deleteContainer(containerResult.containerId)
            return ContainerResult(
                success = false,
                error = "Failed to install app: ${installResult.error}"
            )
        }
        
        Log.d(TAG, "[getOrCreateContainerForApp] SUCCESS: container=${containerResult.containerId} for clone=$cloneId")
        return containerResult
    }
    
    /**
     * Delete a container and all its data
     */
    fun deleteContainer(containerId: String): Boolean {
        synchronized(containerLock) {
            val container = activeContainers.remove(containerId) ?: return false
            
            try {
                val containerDir = File(container.rootPath)
                if (containerDir.exists()) {
                    containerDir.deleteRecursively()
                }
                
                Log.d(TAG, "Container deleted: $containerId")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete container: $containerId", e)
                return false
            }
        }
    }
    
    /**
     * Get all active containers
     */
    fun getAllContainers(): List<VirtualContainer> {
        return activeContainers.values.toList()
    }
    
    /**
     * Get container by ID
     */
    fun getContainer(containerId: String): VirtualContainer? {
        return activeContainers[containerId]
    }
    
    /**
     * Get apps installed in a container
     */
    fun getInstalledApps(containerId: String): List<ContainerAppMetadata> {
        val container = activeContainers[containerId] ?: return emptyList()
        val appsDir = File(container.rootPath, "apps")
        
        if (!appsDir.exists()) return emptyList()
        
        return appsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { getAppMetadata(containerId, it.name) }
            ?: emptyList()
    }
    
    /**
     * Clear app data within a container
     */
    fun clearAppData(containerId: String, packageName: String): Boolean {
        val container = activeContainers[containerId] ?: return false
        
        try {
            val appDir = File(container.rootPath, "apps/$packageName")
            
            // Clear data directories but preserve structure
            listOf("data", "cache", "databases", "shared_prefs", "files").forEach { dir ->
                val dirFile = File(appDir, dir)
                if (dirFile.exists()) {
                    dirFile.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
            
            Log.d(TAG, "App data cleared: $containerId/$packageName")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear app data", e)
            return false
        }
    }
    
    /**
     * Get container storage usage
     */
    fun getContainerSize(containerId: String): Long {
        val container = activeContainers[containerId] ?: return 0
        return calculateDirectorySize(File(container.rootPath))
    }
    
    // ===== Private Helper Methods =====
    
    private fun generateContainerId(): String {
        return "container_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
    
    private fun createContainerStructure(containerDir: File): StructureResult {
        try {
            // Create main directories
            val directories = listOf(
                containerDir,
                File(containerDir, "apps"),
                File(containerDir, "runtime/dex_opt"),
                File(containerDir, "runtime/class_cache")
            )
            
            directories.forEach { dir ->
                if (!dir.mkdirs() && !dir.exists()) {
                    return StructureResult(false, "Failed to create: ${dir.path}")
                }
            }
            
            return StructureResult(true)
        } catch (e: Exception) {
            return StructureResult(false, e.message)
        }
    }
    
    private fun createAppDirectories(appDir: File) {
        listOf("data", "cache", "databases", "shared_prefs", "files", "lib").forEach { dir ->
            File(appDir, dir).mkdirs()
        }
    }
    
    private fun ensureDirectoriesExist(paths: IsolatedPaths) {
        listOf(
            paths.dataDir,
            paths.cacheDir,
            paths.filesDir,
            paths.databasesDir,
            paths.sharedPrefsDir,
            paths.dexOptDir
        ).forEach { path ->
            File(path).mkdirs()
        }
    }
    
    private fun createSymlink(target: String, link: String): Boolean {
        return try {
            val linkFile = File(link)
            if (linkFile.exists()) {
                linkFile.delete()
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.nio.file.Files.createSymbolicLink(
                    java.nio.file.Paths.get(link),
                    java.nio.file.Paths.get(target)
                )
                true
            } else {
                // Fallback: copy directory reference
                Runtime.getRuntime().exec(arrayOf("ln", "-s", target, link)).waitFor() == 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create symlink: $target -> $link", e)
            false
        }
    }
    
    private fun loadExistingContainers() {
        try {
            containersRoot.listFiles()?.forEach { containerDir ->
                if (containerDir.isDirectory) {
                    loadContainerFromDisk(containerDir)
                }
            }
            Log.d(TAG, "Loaded ${activeContainers.size} existing containers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load existing containers", e)
        }
    }
    
    private fun loadContainerFromDisk(containerDir: File) {
        try {
            val metadataFile = File(containerDir, "metadata.json")
            if (!metadataFile.exists()) return
            
            val json = metadataFile.readText()
            val container = VirtualContainer.fromJson(json)
            
            if (container != null) {
                container.status = ContainerStatus.STOPPED
                activeContainers[container.id] = container
                
                // Update UID counter if needed
                if (container.virtualUid >= uidCounter.get()) {
                    uidCounter.set(container.virtualUid + 1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load container: ${containerDir.name}", e)
        }
    }
    
    private fun saveContainerMetadata(container: VirtualContainer) {
        try {
            val metadataFile = File(container.rootPath, "metadata.json")
            metadataFile.writeText(container.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save container metadata", e)
        }
    }
    
    private fun getAppMetadata(containerId: String, packageName: String): ContainerAppMetadata? {
        val container = activeContainers[containerId] ?: return null
        
        try {
            val metadataFile = File(container.rootPath, "apps/$packageName/app_metadata.json")
            if (!metadataFile.exists()) return null
            
            return ContainerAppMetadata.fromJson(metadataFile.readText())
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun saveAppMetadata(containerId: String, metadata: ContainerAppMetadata) {
        val container = activeContainers[containerId] ?: return
        
        try {
            val metadataFile = File(container.rootPath, "apps/${metadata.packageName}/app_metadata.json")
            metadataFile.parentFile?.mkdirs()
            metadataFile.writeText(metadata.toJson())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save app metadata", e)
        }
    }
    
    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }
}

// ===== Data Classes =====

data class VirtualContainer(
    val id: String,
    val name: String,
    val rootPath: String,
    val virtualUid: Int,
    val createdAt: Long,
    val version: Int,
    var status: ContainerStatus,
    var lastActivity: Long = 0
) {
    fun toJson(): String {
        return """
        {
            "id": "$id",
            "name": "$name",
            "rootPath": "$rootPath",
            "virtualUid": $virtualUid,
            "createdAt": $createdAt,
            "version": $version,
            "status": "${status.name}",
            "lastActivity": $lastActivity
        }
        """.trimIndent()
    }
    
    companion object {
        fun fromJson(json: String): VirtualContainer? {
            return try {
                val obj = org.json.JSONObject(json)
                VirtualContainer(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    rootPath = obj.getString("rootPath"),
                    virtualUid = obj.getInt("virtualUid"),
                    createdAt = obj.getLong("createdAt"),
                    version = obj.optInt("version", 1),
                    status = ContainerStatus.valueOf(obj.optString("status", "STOPPED")),
                    lastActivity = obj.optLong("lastActivity", 0)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

enum class ContainerStatus {
    CREATED,
    RUNNING,
    STOPPED,
    ERROR
}

data class ContainerAppMetadata(
    val packageName: String,
    val apkPath: String,
    val nativeLibPath: String?,
    val installedAt: Long,
    var lastLaunched: Long,
    val dataPath: String,
    val virtualUid: Int
) {
    fun toJson(): String {
        return """
        {
            "packageName": "$packageName",
            "apkPath": "$apkPath",
            "nativeLibPath": "${nativeLibPath ?: ""}",
            "installedAt": $installedAt,
            "lastLaunched": $lastLaunched,
            "dataPath": "$dataPath",
            "virtualUid": $virtualUid
        }
        """.trimIndent()
    }
    
    companion object {
        fun fromJson(json: String): ContainerAppMetadata? {
            return try {
                val obj = org.json.JSONObject(json)
                ContainerAppMetadata(
                    packageName = obj.getString("packageName"),
                    apkPath = obj.getString("apkPath"),
                    nativeLibPath = obj.optString("nativeLibPath").takeIf { it.isNotEmpty() },
                    installedAt = obj.getLong("installedAt"),
                    lastLaunched = obj.getLong("lastLaunched"),
                    dataPath = obj.getString("dataPath"),
                    virtualUid = obj.getInt("virtualUid")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class IsolatedPaths(
    val dataDir: String,
    val cacheDir: String,
    val filesDir: String,
    val databasesDir: String,
    val sharedPrefsDir: String,
    val dexOptDir: String,
    val apkPath: String,
    val nativeLibPath: String
)

data class ContainerResult(
    val success: Boolean,
    val error: String? = null,
    val containerId: String? = null,
    val container: VirtualContainer? = null
)

data class InstallResult(
    val success: Boolean,
    val error: String? = null,
    val appPath: String? = null,
    val metadata: ContainerAppMetadata? = null
)

data class LaunchPreparation(
    val success: Boolean,
    val error: String? = null,
    val containerId: String? = null,
    val packageName: String? = null,
    val virtualUid: Int = 0,
    val paths: IsolatedPaths? = null
)

data class StructureResult(
    val success: Boolean,
    val error: String? = null
)
