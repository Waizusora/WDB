package com.waizusora.wdb.adb

/**
 * Identity-based ownership for the single active ADB client.
 *
 * Every method is synchronized so late completions from a retired client cannot
 * clear or replace a newer connection, even when cleanup races with reconnect.
 */
internal class ActiveClientRegistry<T : Any> {
    private val lock = Any()
    private var active: T? = null

    fun current(): T? = synchronized(lock) { active }

    fun replace(client: T): T? = synchronized(lock) {
        active.also { active = client }
    }

    fun clear(): T? = synchronized(lock) {
        active.also { active = null }
    }

    fun isCurrent(client: T): Boolean = synchronized(lock) {
        active === client
    }

    fun clearIfCurrent(client: T): Boolean = synchronized(lock) {
        if (active === client) {
            active = null
            true
        } else {
            false
        }
    }
}
