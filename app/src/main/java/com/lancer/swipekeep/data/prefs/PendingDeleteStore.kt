package com.lancer.swipekeep.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lancer.swipekeep.domain.SavedSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一个进程一份 —— DataStore 不允许同一个文件被两个实例打开。
 *
 * **必须给 `corruptionHandler`**：默认值是 null，文件一旦损坏（掉电中断、被清理工具截断）
 * DataStore 就抛 `CorruptionException`，而这条路径在最前面 —— 范围页、滑卡页、结算页
 * 全都在启动时读它，于是表现是**每次打开 App 都崩**，用户连重置的机会都没有。
 * 换成空 Preferences 的代价是丢掉那份待删标记，而照片本身一个字节都没动过，
 * 用户重滑几遍就能补回来。
 */
private val Context.pendingStore: DataStore<Preferences> by preferencesDataStore(
    name = "pending_deletes",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 待删队列的落盘处。**这一层只管存取，不管对账** —— 把身份换成照片记录要一次新扫描，
 * 那是 [com.lancer.swipekeep.domain.restoreSession] 的事。
 *
 * ## 为什么是「合并写」而不是每划一张就写一次
 *
 * 滑卡是唯一在乎帧率的路径，而 DataStore 每次 `edit{}` 都要重写整个 preferences 文件、
 * 还要过一次磁盘 fsync。人类滑卡可以到 3 张/秒，把 IO 挂在那个路径上迟早会掉帧 ——
 * 而 iOS 那套「滑一下就存一下」在这里换来的只是「最多少存 4 张」。
 *
 * 所以：滑动只改内存里的 [stage]（纯赋值，零 IO），真正落盘由 [flush] 负责，
 * 触发点是「每 N 张」「离开滑卡页」「进程要走了」。
 *
 * 代价说清楚：**如果进程在两个 flush 之间被杀，最后那几张会丢**。所以触发点里
 * 必须有「离开页面」这一条 —— 正常退出（返回、去结算页、切后台）一条都不会丢，
 * 只有被系统强杀才会丢最后几秒的操作。
 */
class PendingDeleteStore(private val context: Context) {

    private val key = stringPreferencesKey("saved_session")

    /** 内存里累积的那一份。`@Volatile`：写在主线程（滑卡回调），读在 IO 线程（flush）。 */
    @Volatile
    private var staged: SavedSession? = null

    /**
     * 串行化 [flush] 与 [clear]。
     *
     * 没有它就会有一个很隐蔽的竞态：「全部撤销」写空的**同时**，上一次离开滑卡页时
     * 投递的那次 flush 正好在写旧值 —— 谁后写谁赢，而用户看到的横幅已经消失了，
     * 下次进来那批待删又冒出来。
     */
    private val ioLock = Mutex()

    /** 磁盘上的那份。启动时的范围页横幅读它。 */
    val session: Flow<SavedSession?> = context.pendingStore.data.map { prefs ->
        prefs[key]?.let(SavedSessionCodec::decode)
    }

    /**
     * 当前最新的那一份。
     *
     * **先看内存再看磁盘**：滑卡页每划一张都 [stage] 到内存，真正落盘是每 5 张 / 离开页面。
     * 结算页紧跟着滑卡页打开，那时磁盘上可能还差最后几张 —— 而结算页的「确认删除」
     * 要提交的正是这份列表。等一次 flush 再读会让按钮点下去少删几张，且完全不可见。
     *
     * 反过来，`staged == null` 时读磁盘一定是对的：要么刚 flush 过（值时同一个），
     * 要么是重开 App 后的第一次读（只有磁盘）。
     */
    suspend fun load(): SavedSession? = staged ?: session.first()

    /** 只改内存，不碰 IO。滑卡路径上调用它。 */
    fun stage(session: SavedSession) {
        staged = session
    }

    suspend fun flush() {
        ioLock.withLock {
            // 循环而不是写一次：写入期间 `stage()` 又被调用时（滑卡在主线程、刷盘在 IO 线程），
            // 只写一次就会把那张新划的照片落下 —— 而落下的是「用户已经标记要删的一张」。
            //
            // **先把值写进磁盘，再清 `staged`**，顺序不能反：滑卡页 `onStop` 投递的 flush
            // 与结算页刚建好时的 [load] 是并发的，中间那个「staged 已清、磁盘还没写完」的
            // 窗口会让结算页读到上一版（甚至空）的队列，于是「确认删除」按钮根本不出来。
            while (true) {
                val snapshot = staged ?: break
                context.pendingStore.edit { prefs -> prefs[key] = SavedSessionCodec.encode(snapshot) }
                // 写入期间又被 stage 过（引用一定会变，`toSavedSession` 每次都是新实例）：
                // 别清，再写一轮。
                if (staged !== snapshot) continue
                staged = null
                break
            }
        }
    }

    /** 「全部撤销」。磁盘一个字节都没动过，所以这里只是把记录抹掉。 */
    suspend fun clear() {
        ioLock.withLock {
            staged = null
            context.pendingStore.edit { prefs -> prefs.remove(key) }
        }
    }
}
