/*
 * ColorOSNotifyIcon - Optimize notification icons for ColorOS and adapt to native notification icon specifications.
 * Copyright (C) 20174 Fankes Studio(qzmmcn@163.com)
 * https://github.com/fankes/ColorOSNotifyIcon
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version.
 * <p>
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 *
 * This file is created by Nep-Timeline on 2025/5/27.
 */
package com.fankes.coloros.notify.hook.entity

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.ArrayMap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.fankes.coloros.notify.R
import com.fankes.coloros.notify.bean.IconDataBean
import com.fankes.coloros.notify.const.PackageName
import com.fankes.coloros.notify.data.ConfigData
import com.fankes.coloros.notify.param.IconPackParams
import com.fankes.coloros.notify.param.factory.isAppNotifyHookAllOf
import com.fankes.coloros.notify.param.factory.isAppNotifyHookOf
import com.fankes.coloros.notify.utils.factory.appIconOf
import com.fankes.coloros.notify.utils.factory.drawableOf
import com.fankes.coloros.notify.utils.factory.safeOf
import com.fankes.coloros.notify.utils.factory.safeOfFalse
import com.fankes.coloros.notify.utils.tool.BitmapCompatTool
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.injectModuleAppResources
import com.highcapable.yukihookapi.hook.log.YLog

/**
 * 系统框架核心 Hook 类
 */
object FrameworkHooker : YukiBaseHooker() {
    /** 原生存在的类 */
    private val ContrastColorUtilClass by lazyClass("com.android.internal.util.ContrastColorUtil")

    /** ColorOS 存在的类 - 旧版本不存在 */
    private val OplusNotificationFixHelperClass by lazyClassOrNull("com.android.server.notification.OplusNotificationFixHelper")

    /** 缓存的彩色 APP 图标 */
    var appIcons = ArrayMap<String, Drawable>()

    /** 缓存的通知优化图标数组 */
    var iconDatas = ArrayList<IconDataBean>()

    private fun cachingIconDatas() {
        iconDatas = IconPackParams(param = this).iconDatas
    }

    private fun compatCustomIcon(context: Context, isGrayscaleIcon: Boolean, packageName: String): Pair<Drawable?, Boolean> {
        context.injectModuleAppResources()
        var customPair: Pair<Drawable?, Boolean>? = null
        val statSysAdbIcon = runCatching {
            val resId = "com.android.internal.R\$drawable".toClass()
                .resolve()
                .firstField { name = "stat_sys_adb" }
                .get<Int>() ?: error("Resource not found")
            context.resources.drawableOf(resId)
        }.getOrNull() ?: context.resources.drawableOf(R.drawable.ic_unsupported)
        when {
            /** 替换系统图标为 Android 默认 */
            (packageName == PackageName.SYSTEM_FRAMEWORK || packageName == PackageName.SYSTEMUI) && isGrayscaleIcon.not() ->
                customPair = Pair(statSysAdbIcon, false)
            /** 替换自定义通知图标 */
            ConfigData.isEnableNotifyIconFix -> run {
                iconDatas.takeIf { it.isNotEmpty() }?.forEach {
                    if (packageName == it.packageName && isAppNotifyHookOf(it)) {
                        if (isGrayscaleIcon.not() || isAppNotifyHookAllOf(it))
                            customPair = Pair(it.iconBitmap.toDrawable(context.resources), false)
                        return@run
                    }
                }
                if (isGrayscaleIcon.not() && ConfigData.isEnableNotifyIconFixPlaceholder)
                    customPair = Pair(context.resources.drawableOf(R.drawable.ic_message), true)
            }
        }
        return customPair ?: Pair(null, false)
    }

    private fun compatPushingIcon(iconDrawable: Drawable, opPkg: String, packageName: String) = safeOf(iconDrawable) {
        /** 给系统推送设置 APP 自己的图标 */
        if (opPkg == PackageName.SYSTEM_FRAMEWORK && opPkg != packageName && opPkg.isNotBlank())
            appIcons[packageName] ?: iconDrawable
        else iconDrawable
    }

    private fun compatStatusIcon(
        context: Context,
        isGrayscaleIcon: Boolean,
        opPkg: String,
        packageName: String,
        drawable: Drawable
    ) = compatCustomIcon(context, isGrayscaleIcon, packageName).let {
        it.first?.let { e -> Pair(e, true) } ?: Pair(if (isGrayscaleIcon) drawable else compatPushingIcon(drawable, opPkg, packageName), isGrayscaleIcon.not())
    }

    private fun isGrayscaleIcon(context: Context, drawable: Drawable) =
        if (ConfigData.isEnableColorIconCompat.not()) safeOfFalse {
            ContrastColorUtilClass.resolve()
                .optional(silent = true)
                .let {
                    it.firstMethodOrNull {
                        name = "isGrayscaleIcon"
                        parameters(Drawable::class)
                    }?.of(
                        it.firstMethodOrNull {
                            name = "getInstance"
                            parameters(Context::class)
                        }?.invoke(context)
                    )?.invokeQuietly<Boolean>(drawable) == true
                }
        } else BitmapCompatTool.isGrayscaleDrawable(drawable)

    override fun onHook() {
        cachingIconDatas()
        /** ColorOS覆盖应用通知图标方法体 */
        OplusNotificationFixHelperClass?.resolve()?.optional()?.firstMethodOrNull {
            name = "fixNotificationForOplus"
            parameters(Context::class, Notification::class, String::class, String::class, Boolean::class)
        }?.hook()?.before {
            try {
                val context = args().first().cast<Context>() ?: return@before
                val notification = args(1).cast<Notification>() ?: return@before
                val packageName = args(2).cast<String>().toString()
                val opPkg = args(3).cast<String>().toString()
                resultNull()
                notification.smallIcon.loadDrawable(context)?.also { iconDrawable ->
                    compatStatusIcon(
                        context = context,
                        isGrayscaleIcon = isGrayscaleIcon(context, iconDrawable).also {
                            /** 缓存第一次的 APP 小图标 */
                            if (it.not()) context.appIconOf(packageName)?.also { e -> appIcons[packageName] = e }
                        },
                        packageName = packageName,
                        opPkg = opPkg,
                        drawable = iconDrawable
                    ).also { pair ->
                        if (pair.second)
                            notification.javaClass.resolve().optional().firstMethod { name = "setSmallIcon" }.of(notification).invoke(Icon.createWithBitmap(pair.first.toBitmap()))
                    }
                }
            } catch (e: Exception) {
                YLog.error("Hook error: ${e.message}")
            }
        }
    }
}