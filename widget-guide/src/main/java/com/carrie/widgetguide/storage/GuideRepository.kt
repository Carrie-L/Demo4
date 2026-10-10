package com.carrie.widgetguide.storage

import com.carrie.widgetguide.core.GuideState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException


/**
 * SP/MMKV 的最小后端接口，整个 GuideState 使用一个 JSON 字符串存储。
 * 同文件只允许一个进程内仓库实例管理，不提供跨进程事务。
 */
interface StringStateStore {
    /**
     * 读取完整 JSON；仅未存过时返回 null。损坏或后端错误应传播，不能伪装成空状态。
     */
    fun read(): String?

    /**
     * 按后端约定写入整个 JSON，返回写入是否成功；异常或 false 必须传给仓库。
     */
    fun write(value: String): Boolean
}

/**
 * 挂起式领域仓库；对调用方隐藏 JSON、IO 和互斥实现。
 */
interface GuideRepository {
    /**
     * 读取并严格解码当前状态；缺少数据才返回初始状态。
     */
    suspend fun read(): GuideState
    /**
     * 在仓库锁内读取最新值、执行纯转换并写回，返回成功保存的快照。
     * transform 不得执行 UI、副作用或回调仓库；执行时位于 IO dispatcher。
     */
    suspend fun update(transform: (GuideState) -> GuideState): GuideState
}

/**
 * 单进程持久仓库，用同一 Mutex 覆盖完整读改写过程，避免并发丢更新。
 * @param store 可替换的 SP/MMKV 单值存储。
 * @param dispatcher 读写和 JSON 编解码使用的调度器，默认 IO。
 */
class PersistentGuideRepository(
    private val store: StringStateStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : GuideRepository {
    /**
     * 只保护本仓库实例，不能协调其他 repository 或其他进程直接修改同一个 key。
     */
    private val mutex = Mutex()
    /**
     * 串行读取；将阻塞存储操作移到 IO，避免主线程磁盘访问。
     */
    override suspend fun read(): GuideState = mutex.withLock {
        withContext(dispatcher) { decode() }
    }

    /**
     * 每次基于后端最新数据转换；检查 write 返回值后才向控制器返回新状态。
     */
    override suspend fun update(transform: (GuideState) -> GuideState): GuideState = mutex.withLock {
        withContext(dispatcher) {
            val updated = transform(decode())
            // 即使内容相同也重新写入：此前 commit(false) 可能已改变 SP 内存副本，读回相同不代表落盘成功。
            if (!store.write(GuideStateCodec.encode(updated))) throw IOException("Guide state commit failed")
            updated
        }
    }

    /**
     * 不存在数据时初始化；JSON 损坏、未知版本等交给上层异常暂停处理。
     */
    private fun decode() = store.read()?.let(GuideStateCodec::decode) ?: GuideState()
}
