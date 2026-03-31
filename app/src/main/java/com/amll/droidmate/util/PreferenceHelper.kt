package com.amll.droidmate.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple wrapper around [SharedPreferences] to reduce boilerplate when
 * accessing named preferences.  Usage examples:
 *
 * ```kotlin
 * val prefs = PreferenceHelper(context, "my_prefs")
 * prefs.putString("key", value)
 * val existing = prefs.getString("key", "")
 * prefs.remove("key")
 * ```
 */
class PreferenceHelper(context: Context, name: String) {
    private val prefs: SharedPreferences? = runCatching {
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }.getOrNull()
    private val memory = linkedMapOf<String, Any?>()

    fun getString(key: String, default: String? = null): String? =
        prefs?.getString(key, default) ?: (memory[key] as? String ?: default)

    fun putString(key: String, value: String?) {
        if (prefs != null) {
            prefs.edit().putString(key, value).apply()
        } else {
            memory[key] = value
        }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs?.getBoolean(key, default) ?: (memory[key] as? Boolean ?: default)

    fun putBoolean(key: String, value: Boolean) {
        if (prefs != null) {
            prefs.edit().putBoolean(key, value).apply()
        } else {
            memory[key] = value
        }
    }

    fun getLong(key: String, default: Long = 0L): Long =
        prefs?.getLong(key, default) ?: (memory[key] as? Long ?: default)

    fun putLong(key: String, value: Long) {
        if (prefs != null) {
            prefs.edit().putLong(key, value).apply()
        } else {
            memory[key] = value
        }
    }

    fun remove(key: String) {
        if (prefs != null) {
            prefs.edit().remove(key).apply()
        } else {
            memory.remove(key)
        }
    }

    fun clear() {
        if (prefs != null) {
            prefs.edit().clear().apply()
        } else {
            memory.clear()
        }
    }

    /**
     * Perform multiple editor operations in a single transaction.
     */
    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
    }
}
