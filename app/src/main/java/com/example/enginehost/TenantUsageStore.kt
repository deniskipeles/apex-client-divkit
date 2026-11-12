package com.example.enginehost

import android.content.Context
import android.content.SharedPreferences

/** Visit telemetry for a single tenant, used to order the launcher by usage. */
data class TenantUsage(
    val visitCount: Int,
    val lastUsedEpochMs: Long
)

/**
 * Lightweight, synchronous persistence for tenant usage ordering and the last-opened
 * workspace, so the app can resume where the user left off and float frequently used
 * tenants to the top of the launcher.
 *
 * Backed by a dedicated [SharedPreferences] file rather than SQLite: the values are tiny,
 * written on the UI-adjacent path, and must be readable before the workspace databases open.
 */
class TenantUsageStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun usage(): Map<String, TenantUsage> {
        val raw = prefs.getString(KEY_USAGE, null) ?: return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0]
            val count = parts[1].toIntOrNull() ?: return@mapNotNull null
            val last = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            id to TenantUsage(count, last)
        }.toMap()
    }

    fun recordVisit(tenantId: String, nowMs: Long = System.currentTimeMillis()) {
        val current = usage()
        val prev = current[tenantId] ?: TenantUsage(0, 0L)
        write(current + (tenantId to TenantUsage(prev.visitCount + 1, nowMs)))
    }

    fun forget(tenantId: String) {
        write(usage() - tenantId)
        if (lastOpenedTenantId() == tenantId) setLastOpened(null, workspaceOpen = false)
    }

    fun lastOpenedTenantId(): String? = prefs.getString(KEY_LAST_TENANT, null)

    fun wasWorkspaceOpen(): Boolean = prefs.getBoolean(KEY_WORKSPACE_OPEN, false)

    fun setLastOpened(tenantId: String?, workspaceOpen: Boolean) {
        prefs.edit()
            .putString(KEY_LAST_TENANT, tenantId)
            .putBoolean(KEY_WORKSPACE_OPEN, workspaceOpen)
            .apply()
    }

    private fun write(map: Map<String, TenantUsage>) {
        val encoded = map.entries.joinToString(";") { (id, u) ->
            "$id,${u.visitCount},${u.lastUsedEpochMs}"
        }
        prefs.edit().putString(KEY_USAGE, encoded).apply()
    }

    private companion object {
        const val PREFS_NAME = "apex_tenant_usage"
        const val KEY_USAGE = "tenant_usage"
        const val KEY_LAST_TENANT = "last_opened_tenant"
        const val KEY_WORKSPACE_OPEN = "last_workspace_open"
    }
}

/**
 * Stable ordering: most recently used first, then most visited, then original order.
 * Tenants that have never been opened (no usage entry) sink to the bottom.
 */
fun List<TenantWorkspaceSpec>.sortedByUsage(usage: Map<String, TenantUsage>): List<TenantWorkspaceSpec> =
    withIndex().sortedWith(
        compareByDescending<IndexedValue<TenantWorkspaceSpec>> {
            usage[it.value.tenantId]?.lastUsedEpochMs ?: 0L
        }.thenByDescending {
            usage[it.value.tenantId]?.visitCount ?: 0
        }.thenBy { it.index }
    ).map { it.value }
