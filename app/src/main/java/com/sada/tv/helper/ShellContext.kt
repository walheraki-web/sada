package com.sada.tv.helper

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper

/**
 * A Context for code started with app_process as the shell user (no app
 * process around it). Audio capture checks the caller's package and uid, so it
 * must present itself as "com.android.shell" / uid 2000.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
@android.annotation.TargetApi(31)
internal class ShellContext(base: Context) : ContextWrapper(base) {

    override fun getPackageName(): String = HelperContext.SHELL_PACKAGE
    override fun getOpPackageName(): String = HelperContext.SHELL_PACKAGE
    override fun getApplicationContext(): Context = this

    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(HelperContext.SHELL_UID).setPackageName(HelperContext.SHELL_PACKAGE).build()
}

/** Builds the context the capture helper runs with. */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object HelperContext {
    const val SHELL_PACKAGE = "com.android.shell"
    const val SHELL_UID = 2000

    @SuppressLint("NewApi")
    fun create(): Context {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val ctor = at.getDeclaredConstructor().apply { isAccessible = true }
        val thread = ctor.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        runCatching {
            val bind = Class.forName("android.app.ActivityThread\$AppBindData")
            val data = bind.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val info = ApplicationInfo().apply { packageName = SHELL_PACKAGE }
            bind.getDeclaredField("appInfo").apply { isAccessible = true }.set(data, info)
            at.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, data)
        }
        val system = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
        return if (Build.VERSION.SDK_INT >= 31) ShellContext(system) else system
    }
}
