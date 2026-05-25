package com.cloneapp.multiaccount.virtualization

import android.annotation.SuppressLint
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection Utilities for accessing hidden Android APIs
 */
object ReflectionUtils {
    
    private val classCache = mutableMapOf<String, Class<*>>()
    private val fieldCache = mutableMapOf<String, Field>()
    private val methodCache = mutableMapOf<String, Method>()
    
    fun getClass(className: String): Class<*> {
        return classCache.getOrPut(className) { Class.forName(className) }
    }
    
    fun getField(clazz: Class<*>, fieldName: String): Field {
        val key = "${clazz.name}#$fieldName"
        return fieldCache.getOrPut(key) {
            var currentClass = clazz
            var field: Field? = null
            while (field == null && currentClass != Object::class.java) {
                try {
                    field = currentClass.getDeclaredField(fieldName)
                } catch (e: NoSuchFieldException) {
                    currentClass = currentClass.superclass ?: Object::class.java
                }
            }
            field?.also { it.isAccessible = true } ?: throw NoSuchFieldException(fieldName)
        }
    }
    
    fun getMethod(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method {
        val key = "${clazz.name}#$methodName#${parameterTypes.joinToString { it.name }}"
        return methodCache.getOrPut(key) {
            var currentClass = clazz
            var method: Method? = null
            while (method == null && currentClass != Object::class.java) {
                try {
                    method = currentClass.getDeclaredMethod(methodName, *parameterTypes)
                } catch (e: NoSuchMethodException) {
                    currentClass = currentClass.superclass ?: Object::class.java
                }
            }
            method?.also { it.isAccessible = true } ?: throw NoSuchMethodException(methodName)
        }
    }
    
    fun setFieldValue(obj: Any, fieldName: String, value: Any?) {
        val field = getField(obj.javaClass, fieldName)
        field.set(obj, value)
    }
    
    fun <T> getFieldValue(obj: Any, fieldName: String): T? {
        val field = getField(obj.javaClass, fieldName)
        @Suppress("UNCHECKED_CAST")
        return field.get(obj) as T?
    }
    
    /**
     * Copy fields from one object to another (for Activity migration)
     */
    fun copyFields(source: Any, dest: Any, clazz: Class<*>) {
        var current = clazz
        while (current != Object::class.java && current != android.content.Context::class.java) {
            current.declaredFields.forEach { field ->
                try {
                    field.isAccessible = true
                    val value = field.get(source)
                    field.set(dest, value)
                } catch (e: Exception) {
                    // Ignore, usually final fields or type mismatches
                }
            }
            current = current.superclass ?: Object::class.java
        }
    }
}
