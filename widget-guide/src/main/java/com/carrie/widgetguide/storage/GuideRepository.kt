package com.carrie.widgetguide.storage

import com.carrie.widgetguide.core.GuideState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** One instance per process/file. SP and MMKV are not interchangeable multi-process transactions. */
interface StringStateStore {
    fun read(): String?
    /** Return only after the backend has accepted its durable-write contract. */
    fun write(value: String): Boolean
}

interface GuideRepository {
    suspend fun read(): GuideState
    suspend fun update(transform: (GuideState) -> GuideState): GuideState
}

class PersistentGuideRepository(
    private val store: StringStateStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : GuideRepository {
    private val mutex = Mutex()
    override suspend fun read(): GuideState = mutex.withLock {
        withContext(dispatcher) { decode() }
    }

    override suspend fun update(transform: (GuideState) -> GuideState): GuideState = mutex.withLock {
        withContext(dispatcher) {
            val updated = transform(decode())
            // Always write, even when equal: a previous SP commit(false) may have changed its RAM copy.
            if (!store.write(GuideStateCodec.encode(updated))) throw IOException("Guide state commit failed")
            updated
        }
    }

    private fun decode() = store.read()?.let(GuideStateCodec::decode) ?: GuideState()
}
