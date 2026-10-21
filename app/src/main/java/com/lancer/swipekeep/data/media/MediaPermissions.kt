package com.lancer.swipekeep.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 相册读取权限的判定与请求清单（实施计划 §4.3）。
 *
 * 本文件只负责**逻辑**：要什么权限、现在拿到什么级别。引导页/拒绝页/PARTIAL 横幅的 UI
 * 属于阶段 7，本阶段只用到 `accessLevel()` 来判断「能不能查相册」。
 */
object MediaPermissions {

    enum class AccessLevel {
        /** 能看全部照片 */
        FULL,

        /** API 34+ 的「仅选择的照片」。能读，但用户看到的只是他自己挑的那几张 */
        PARTIAL,

        DENIED,
    }

    /**
     * API 34+ 必须**两个一起请求**，用户才能看到完整的「允许全部 / 选择照片」对话框；
     * 只请求 READ_MEDIA_IMAGES 会直接跳过选择权，用户只能二选一全给或全不给。
     */
    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

        Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) // API 30–32
    }

    /**
     * **每次都重新查，不要缓存、不要相信 `RequestMultiplePermissions` 回调给的 map。**
     *
     * 回调的 map 在两类场景下与真实状态不一致：OEM 改了权限弹窗行为（MIUI 是重灾区），
     * 以及 API 34+ 的部分授权流程。用 `ContextCompat.checkSelfPermission` 问系统是唯一
     * 可靠的判据。这也是为什么这个函数要在 `onResume` 里被反复调用。
     */
    fun accessLevel(context: Context): AccessLevel = when {
        granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> AccessLevel.FULL

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> AccessLevel.PARTIAL

        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) -> AccessLevel.FULL

        else -> AccessLevel.DENIED
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
