package com.azhan.qingying.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** 从任意 Context 往上找到宿主 Activity。Compose 里拿窗口/系统栏控制器时要用。 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
