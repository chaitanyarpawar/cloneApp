package com.cloneapp.multiaccount

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Isolated context wrapper that redirects storage operations
 * to clone-specific directories
 * 
 * This ensures each clone has its own:
 * - SharedPreferences
 * - Databases
 * - Files
 * - Cache
 * 
 * Prevents shared static state between clones
 */
class IsolatedContextWrapper(
    base: Context,
    private val cloneId: String,
    private val packageName: String,
    private val storagePath: String,
    private val classLoader: ClassLoader
) : ContextWrapper(base) {
    
    private val cloneRoot = File(storagePath)
    private val dataDir = File(cloneRoot, "data")
    private val cacheDir = File(cloneRoot, "cache")
    private val databaseDir = File(cloneRoot, "databases")
    private val filesDir = File(cloneRoot, "files")
    private val prefsDir = File(cloneRoot, "shared_prefs")
    
    init {
        // Ensure all directories exist
        dataDir.mkdirs()
        cacheDir.mkdirs()
        databaseDir.mkdirs()
        filesDir.mkdirs()
        prefsDir.mkdirs()
    }
    
    override fun getFilesDir(): File {
        return filesDir
    }
    
    override fun getCacheDir(): File {
        return cacheDir
    }
    
    override fun getDataDir(): File {
        return dataDir
    }
    
    override fun getDatabasePath(name: String): File {
        return File(databaseDir, name)
    }
    
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase {
        val dbFile = File(databaseDir, name)
        return SQLiteDatabase.openOrCreateDatabase(dbFile, factory)
    }
    
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        val dbFile = File(databaseDir, name)
        return SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, factory, errorHandler)
    }
    
    override fun deleteDatabase(name: String): Boolean {
        val dbFile = File(databaseDir, name)
        return dbFile.delete()
    }
    
    override fun databaseList(): Array<String> {
        return databaseDir.list() ?: emptyArray()
    }
    
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        // Prefix with cloneId to ensure isolation
        val isolatedName = "${cloneId}_${name}"
        val prefsFile = File(prefsDir, "$isolatedName.xml")
        
        return IsolatedSharedPreferences(prefsFile)
    }
    
    override fun getDir(name: String, mode: Int): File {
        val dir = File(dataDir, name)
        dir.mkdirs()
        return dir
    }
    
    override fun getExternalFilesDir(type: String?): File? {
        val dir = if (type != null) {
            File(filesDir, type)
        } else {
            filesDir
        }
        dir.mkdirs()
        return dir
    }
    
    override fun getExternalCacheDir(): File {
        return cacheDir
    }
    
    override fun getClassLoader(): ClassLoader {
        return classLoader
    }
    
    /**
     * Get clone-specific file
     */
    fun getCloneFile(name: String): File {
        return File(filesDir, name)
    }
    
    /**
     * Get clone storage root
     */
    fun getCloneRoot(): File {
        return cloneRoot
    }
}

/**
 * Simple SharedPreferences implementation for isolated storage
 */
class IsolatedSharedPreferences(private val prefsFile: File) : SharedPreferences {
    
    private val data = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    
    init {
        loadFromFile()
    }
    
    private fun loadFromFile() {
        if (!prefsFile.exists()) return
        
        try {
            // Simple key=value parser (production would use XML)
            prefsFile.readLines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = parts[1]
                    data[key] = parseValue(value)
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
    }
    
    private fun saveToFile() {
        prefsFile.parentFile?.mkdirs()
        prefsFile.writeText(
            data.entries.joinToString("\n") { "${it.key}=${it.value}" }
        )
    }
    
    private fun parseValue(value: String): Any? {
        return when {
            value == "null" -> null
            value == "true" || value == "false" -> value.toBoolean()
            value.toLongOrNull() != null -> value.toLong()
            value.toFloatOrNull() != null -> value.toFloat()
            else -> value
        }
    }
    
    override fun getAll(): Map<String, *> = data.toMap()
    
    override fun getString(key: String, defValue: String?): String? {
        return data[key] as? String ?: defValue
    }
    
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return data[key] as? Set<String> ?: defValues
    }
    
    override fun getInt(key: String, defValue: Int): Int {
        return (data[key] as? Long)?.toInt() ?: defValue
    }
    
    override fun getLong(key: String, defValue: Long): Long {
        return data[key] as? Long ?: defValue
    }
    
    override fun getFloat(key: String, defValue: Float): Float {
        return data[key] as? Float ?: defValue
    }
    
    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return data[key] as? Boolean ?: defValue
    }
    
    override fun contains(key: String): Boolean {
        return data.containsKey(key)
    }
    
    override fun edit(): SharedPreferences.Editor {
        return IsolatedEditor(this)
    }
    
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.add(listener)
    }
    
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)
    }
    
    private inner class IsolatedEditor(
        private val prefs: IsolatedSharedPreferences
    ) : SharedPreferences.Editor {
        
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }
        
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            changes[key] = values
            return this
        }
        
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            changes[key] = value.toLong()
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
            clear = true
            return this
        }
        
        override fun commit(): Boolean {
            apply()
            return true
        }
        
        override fun apply() {
            if (clear) {
                prefs.data.clear()
            }
            
            changes.forEach { (key, value) ->
                if (value == null) {
                    prefs.data.remove(key)
                } else {
                    prefs.data[key] = value
                }
                
                // Notify listeners
                prefs.listeners.forEach { listener ->
                    listener.onSharedPreferenceChanged(prefs, key)
                }
            }
            
            prefs.saveToFile()
        }
    }
}
