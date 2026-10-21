package com.lancer.swipekeep

import android.content.Context
import coil.ImageLoader
import com.lancer.swipekeep.core.SoundPlayer
import com.lancer.swipekeep.data.media.MediaStoreDataSource
import com.lancer.swipekeep.data.media.buildImageLoader
import com.lancer.swipekeep.data.prefs.PendingDeleteStore
import com.lancer.swipekeep.data.repository.PhotoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖容器。刻意不引 Hilt：1 个 Activity、6 个 Fragment、1 个共享仓储，
 * 注解处理器带来的 KSP 与 Kotlin 2.0.21 严格版本绑定成本高于收益。
 *
 * 进程级单例都挂在这里，且一律 `by lazy` —— `Application.onCreate` 里的任何耗时都会
 * 直接算进冷启动时间，而这些依赖首次进入范围页才需要。
 * 尤其是需要跨页面存活的 SoundPlayer（阶段 8）：放进 Fragment 的 `onDestroyView`
 * 会在导航离开的瞬间掐掉声音。
 */
class AppContainer(private val app: Context) {

    /**
     * 与进程同生命周期。ContentObserver 这类长生命周期订阅挂这里，
     * 不要挂 Fragment —— 否则每次重建都会泄漏一个 observer。
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val mediaStoreDataSource: MediaStoreDataSource by lazy { MediaStoreDataSource(app) }

    val photoRepository: PhotoRepository by lazy {
        PhotoRepository(app, mediaStoreDataSource, applicationScope)
    }

    /**
     * 跨进程重启活下来的待删队列。范围页的横幅与滑卡页的恢复都读它。
     *
     * 不放在 `PhotoRepository` 里：那是「相册现在有什么」，这是「用户决定要删什么」，
     * 后者在相册一个字节都没动的时候就存在了。
     */
    val pendingDeleteStore: PendingDeleteStore by lazy { PendingDeleteStore(app) }

    /**
     * 一个进程一份。Coil 的 ImageLoader 自带位图内存缓存与线程池，
     * 每个 Fragment 各建一个会让缓存分裂、内存翻倍。
     */
    val imageLoader: ImageLoader by lazy { buildImageLoader(app) }

    /**
     * 音效。挂在这里而不是 Fragment 上，原因见 [SoundPlayer] 的类注释：
     * 导航离开滑卡页时不能把正在响的音掐掉，而 Fragment 的 `onDestroyView` 恰好在那时跑。
     *
     * 不做显式释放：`ToneGenerator` 持有的是进程内的音频输出通道，进程结束就随系统回收，
     * 没有需要提前归还的资源。（`Application.onTerminate` 只在模拟器上触发，
     * 挂在那里等于假装释放了。）
     */
    val soundPlayer: SoundPlayer by lazy { SoundPlayer() }
}
