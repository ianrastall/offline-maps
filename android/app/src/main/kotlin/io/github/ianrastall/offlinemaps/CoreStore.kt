package io.github.ianrastall.offlinemaps

import io.github.ianrastall.offlinemaps.core.NativeCore
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Closing waits for every tile/search call; a late WebView request cannot use a freed handle. */
class CoreStore(store: String, overview: String) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private var handle = NativeCore.open(store, overview)
    fun <T> use(block: (Long) -> T): T = lock.read {
        check(handle != 0L) { "The map folder is closed." }
        block(handle)
    }
    override fun close() = lock.write {
        if (handle != 0L) { NativeCore.close(handle); handle = 0L }
    }
}
