package com.cloneapp.multiaccount.virtualization

import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * File System Redirection System
 * 
 * Intercepts and redirects file system operations to container-specific paths:
 * - getFilesDir() -> /virtual/containers/[id]/apps/[pkg]/files/
 * - getDataDir() -> /virtual/containers/[id]/apps/[pkg]/data/
 * - getCacheDir() -> /virtual/containers/[id]/apps/[pkg]/cache/
 * - getDatabasePath() -> /virtual/containers/[id]/apps/[pkg]/databases/
 * - SharedPreferences -> /virtual/containers/[id]/apps/[pkg]/shared_prefs/
 * 
 * Ensures:
 * - No data leakage between clones
 * - No data leakage to host profile
 * - Full compatibility with normal Android APIs
 * - Scoped storage compliance (Android 10+)
 */
class FileRedirector(
    private val context: Context,
    private val containerId: String,
    private val packageName: String,
    private val containerBasePath: String
) {
    
    companion object {
        private const val TAG = "FileRedirector"
        
        // Standard Android directory names
        private const val DIR_FILES = "files"
        private const val DIR_CACHE = "cache"
        private const val DIR_DATA = "data"
        private const val DIR_DATABASES = "databases"
        private const val DIR_SHARED_PREFS = "shared_prefs"
        private const val DIR_NO_BACKUP = "no_backup"
        private const val DIR_CODE_CACHE = "code_cache"
        
        // Redirector instances cache
        private val instances = ConcurrentHashMap<String, FileRedirector>()
        
        fun getInstance(
            context: Context,
            containerId: String,
            packageName: String,
            containerBasePath: String
        ): FileRedirector {
            val key = "${containerId}_$packageName"
            return instances.getOrPut(key) {
                FileRedirector(context, containerId, packageName, containerBasePath)
            }
        }
    }
    
    // Isolated directory paths
    private val appRootDir: File = File(containerBasePath, "apps/$packageName")
    private val _filesDir: File = File(appRootDir, DIR_FILES)
    private val _cacheDir: File = File(appRootDir, DIR_CACHE)
    private val _dataDir: File = File(appRootDir, DIR_DATA)
    val databasesDir: File = File(appRootDir, DIR_DATABASES)
    val sharedPrefsDir: File = File(appRootDir, DIR_SHARED_PREFS)
    val noBackupDir: File = File(appRootDir, DIR_NO_BACKUP)
    private val _codeCacheDir: File = File(appRootDir, DIR_CODE_CACHE)
    
    // SharedPreferences cache
    private val sharedPreferencesCache = ConcurrentHashMap<String, IsolatedSharedPrefs>()
    
    // Database path cache
    private val databasePathCache = ConcurrentHashMap<String, File>()
    
    init {
        initializeDirectories()
    }
    
    /**
     * Initialize all required directories
     */
    private fun initializeDirectories() {
        listOf(
            appRootDir,
            _filesDir,
            _cacheDir,
            _dataDir,
            databasesDir,
            sharedPrefsDir,
            noBackupDir,
            _codeCacheDir
        ).forEach { dir ->
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) {
                    Log.w(TAG, "Failed to create directory: ${dir.absolutePath}")
                }
            }
        }
        
        Log.d(TAG, "Initialized directories for $containerId/$packageName at ${appRootDir.absolutePath}")
    }
    
    /**
     * Get redirected files directory
     */
    fun getFilesDir(): File = _filesDir
    
    /**
     * Get redirected cache directory
     */
    fun getCacheDir(): File = _cacheDir
    
    /**
     * Get redirected data directory (Android N+)
     */
    fun getDataDir(): File = _dataDir
    
    /**
     * Get redirected database path
     */
    fun getDatabasePath(name: String): File {
        return databasePathCache.getOrPut(name) {
            File(databasesDir, name)
        }
    }
    
    /**
     * Get list of databases
     */
    fun databaseList(): Array<String> {
        return databasesDir.list() ?: emptyArray()
    }
    
    /**
     * Delete a database
     */
    fun deleteDatabase(name: String): Boolean {
        val dbFile = getDatabasePath(name)
        var deleted = false
        
        // Delete main database file
        if (dbFile.exists()) {
            deleted = dbFile.delete()
        }
        
        // Delete associated files (journal, wal, shm)
        listOf("-journal", "-wal", "-shm").forEach { suffix ->
            val associatedFile = File("${dbFile.absolutePath}$suffix")
            if (associatedFile.exists()) {
                associatedFile.delete()
            }
        }
        
        databasePathCache.remove(name)
        return deleted
    }
    
    /**
     * Open or create a database
     */
    fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase {
        val dbFile = getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(dbFile, factory)
    }
    
    /**
     * Open or create a database with error handler
     */
    fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        val dbFile = getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath,
            factory,
            errorHandler
        )
    }
    
    /**
     * Get redirected SharedPreferences
     */
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val actualName = name.ifEmpty { "${packageName}_preferences" }
        
        return sharedPreferencesCache.getOrPut(actualName) {
            IsolatedSharedPrefs(File(sharedPrefsDir, "$actualName.xml"))
        }
    }
    
    /**
     * Get a custom named directory
     */
    fun getDir(name: String, mode: Int): File {
        val dir = File(_dataDir, name)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Get redirected external files directory
     * Note: On Android 10+, scoped storage may limit access
     */
    fun getExternalFilesDir(type: String?): File? {
        val baseDir = if (type != null) {
            File(_filesDir, "external/$type")
        } else {
            File(_filesDir, "external")
        }
        baseDir.mkdirs()
        return baseDir
    }
    
    /**
     * Get redirected external cache directory
     */
    fun getExternalCacheDir(): File {
        val dir = File(_cacheDir, "external")
        dir.mkdirs()
        return dir
    }
    
    /**
     * Get no backup files directory
     */
    fun getNoBackupFilesDir(): File {
        return noBackupDir
    }
    
    /**
     * Get code cache directory
     */
    fun getCodeCacheDir(): File {
        return _codeCacheDir
    }
    
    /**
     * Redirect an arbitrary path to isolated storage
     */
    fun redirectPath(originalPath: String): String {
        // Check if path is already in isolated storage
        if (originalPath.startsWith(appRootDir.absolutePath)) {
            return originalPath
        }
        
        // Map common paths to isolated equivalents
        val realDataDir = context.dataDir?.absolutePath ?: context.filesDir.parent
        val realFilesDir = context.filesDir.absolutePath
        val realCacheDir = context.cacheDir.absolutePath
        
        return when {
            originalPath.startsWith(realFilesDir) -> {
                originalPath.replace(realFilesDir, _filesDir.absolutePath)
            }
            originalPath.startsWith(realCacheDir) -> {
                originalPath.replace(realCacheDir, _cacheDir.absolutePath)
            }
            realDataDir != null && originalPath.startsWith(realDataDir) -> {
                originalPath.replace(realDataDir, _dataDir.absolutePath)
            }
            else -> originalPath
        }
    }
    
    /**
     * Check if a path is within isolated storage
     */
    fun isIsolatedPath(path: String): Boolean {
        return path.startsWith(appRootDir.absolutePath)
    }
    
    /**
     * Get total storage usage for this app in container
     */
    fun getStorageUsage(): StorageUsageInfo {
        return StorageUsageInfo(
            totalBytes = calculateDirSize(appRootDir),
            dataBytes = calculateDirSize(_dataDir),
            cacheBytes = calculateDirSize(_cacheDir),
            databasesBytes = calculateDirSize(databasesDir),
            filesBytes = calculateDirSize(_filesDir),
            prefsBytes = calculateDirSize(sharedPrefsDir)
        )
    }
    
    /**
     * Clear cache for this app in container
     */
    fun clearCache(): Boolean {
        return try {
            deleteDirectoryContents(_cacheDir)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
            false
        }
    }
    
    /**
     * Clear all data for this app in container
     */
    fun clearAllData(): Boolean {
        return try {
            deleteDirectoryContents(_dataDir)
            deleteDirectoryContents(_cacheDir)
            deleteDirectoryContents(databasesDir)
            deleteDirectoryContents(_filesDir)
            deleteDirectoryContents(sharedPrefsDir)
            sharedPreferencesCache.clear()
            databasePathCache.clear()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all data", e)
            false
        }
    }
    
    /**
     * Copy file with path redirection
     */
    fun copyFile(source: File, dest: File): Boolean {
        return try {
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy file: ${source.path} -> ${dest.path}", e)
            false
        }
    }
    
    // ===== Private Helper Methods =====
    
    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }
    
    private fun deleteDirectoryContents(dir: File): Boolean {
        if (!dir.exists()) return true
        
        var success = true
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                success = file.deleteRecursively() && success
            } else {
                success = file.delete() && success
            }
        }
        return success
    }
}

/**
 * Isolated SharedPreferences implementation
 * Stores preferences in container-specific files
 */
class IsolatedSharedPrefs(private val prefsFile: File) : SharedPreferences {
    
    private val data = ConcurrentHashMap<String, Any?>()
    private val listeners = ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>()
    
    init {
        loadFromFile()
    }
    
    private fun loadFromFile() {
        if (!prefsFile.exists()) return
        
        try {
            prefsFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split("|||", limit = 3)
                    if (parts.size == 3) {
                        val (type, key, value) = parts
                        data[key] = when (type) {
                            "S" -> value
                            "I" -> value.toIntOrNull()
                            "L" -> value.toLongOrNull()
                            "F" -> value.toFloatOrNull()
                            "B" -> value.toBoolean()
                            "SS" -> value.split("|||").toSet()
                            else -> value
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("IsolatedSharedPrefs", "Failed to load prefs: ${prefsFile.path}", e)
        }
    }
    
    private fun saveToFile() {
        try {
            prefsFile.parentFile?.mkdirs()
            prefsFile.bufferedWriter().use { writer ->
                data.forEach { (key, value) ->
                    val (type, serialized) = when (value) {
                        is String -> "S" to value
                        is Int -> "I" to value.toString()
                        is Long -> "L" to value.toString()
                        is Float -> "F" to value.toString()
                        is Boolean -> "B" to value.toString()
                        is Set<*> -> "SS" to (value as Set<*>).joinToString("|||")
                        else -> "S" to value.toString()
                    }
                    writer.write("$type|||$key|||$serialized")
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            Log.w("IsolatedSharedPrefs", "Failed to save prefs: ${prefsFile.path}", e)
        }
    }
    
    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    
    override fun getString(key: String, defValue: String?): String? {
        return data[key] as? String ?: defValue
    }
    
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (data[key] as? Set<String>)?.toMutableSet() ?: defValues
    }
    
    override fun getInt(key: String, defValue: Int): Int {
        return (data[key] as? Number)?.toInt() ?: defValue
    }
    
    override fun getLong(key: String, defValue: Long): Long {
        return (data[key] as? Number)?.toLong() ?: defValue
    }
    
    override fun getFloat(key: String, defValue: Float): Float {
        return (data[key] as? Number)?.toFloat() ?: defValue
    }
    
    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return data[key] as? Boolean ?: defValue
    }
    
    override fun contains(key: String): Boolean = data.containsKey(key)
    
    override fun edit(): SharedPreferences.Editor = IsolatedEditor(this)
    
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners[listener] = true
    }
    
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(key: String) {
        listeners.keys.forEach { it.onSharedPreferenceChanged(this, key) }
    }
    
    inner class IsolatedEditor(private val prefs: IsolatedSharedPrefs) : SharedPreferences.Editor {
        
        private val changes = mutableMapOf<String, Any?>()
        private var clearFlag = false
        
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            changes[key] = values?.toSet()
            return this
        }
        
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        
        override fun remove(key: String): SharedPreferences.Editor {
            changes[key] = null
            return this
        }
        
        override fun clear(): SharedPreferences.Editor {
            clearFlag = true
            return this
        }
        
        override fun commit(): Boolean {
            applyChanges()
            return true
        }
        
        override fun apply() {
            applyChanges()
        }
        
        private fun applyChanges() {
            if (clearFlag) {
                prefs.data.clear()
            }
            
            changes.forEach { (key, value) ->
                if (value == null) {
                    prefs.data.remove(key)
                } else {
                    prefs.data[key] = value
                }
                prefs.notifyListeners(key)
            }
            
            prefs.saveToFile()
        }
    }
}

/**
 * Storage usage information
 */
data class StorageUsageInfo(
    val totalBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val databasesBytes: Long,
    val filesBytes: Long,
    val prefsBytes: Long
) {
    fun toMap(): Map<String, Long> = mapOf(
        "total" to totalBytes,
        "data" to dataBytes,
        "cache" to cacheBytes,
        "databases" to databasesBytes,
        "files" to filesBytes,
        "prefs" to prefsBytes
    )
    
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
