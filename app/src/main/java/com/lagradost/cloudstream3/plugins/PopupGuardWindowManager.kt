package com.lagradost.cloudstream3.plugins

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.util.Collections
import java.util.WeakHashMap

/**
 * Wraps an Activity's WindowManager and silently drops [addView] calls made
 * directly by a popup-blocked plugin.
 *
 * Dialogs, AlertDialogs and PopupWindows all obtain their WindowManager from
 * the activity context and funnel through addView — a spam repo builds its
 * own modal overlay with the app's Activity (via reflection), so this is the
 * one choke point that catches it. The app's own dialogs (and popups from
 * non-blocked repos) pass through untouched; attribution is the direct caller
 * of show(), so deep app-driven flows (e.g. the Cloudflare-bypass WebView
 * window) are never misattributed.
 *
 * Views we dropped are tracked so the plugin's later removeView/dismiss does
 * not hit the real WindowManager with an unattached view (which would crash).
 */
class PopupGuardWindowManager(
    private val delegate: WindowManager,
) : WindowManager by delegate {

    private val droppedViews: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        if (guard(view)) return
        delegate.addView(view, params)
    }

    override fun removeView(view: View) {
        if (droppedViews.remove(view)) return
        delegate.removeView(view)
    }

    override fun removeViewImmediate(view: View) {
        if (droppedViews.remove(view)) return
        delegate.removeViewImmediate(view)
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        if (droppedViews.contains(view)) return
        delegate.updateViewLayout(view, params)
    }

    /** True when the DIRECT caller of this addView is a popup-blocked plugin. */
    private fun guard(view: View): Boolean {
        // Shallow attribution: only the immediate caller chain counts, so app
        // flows that merely happen during a plugin's request stay visible.
        if (!PluginManager.isPopupBlocked(depth = 6)) return false
        droppedViews.add(view)
        Log.i(
            PluginManager.TAG,
            "Blocked popup addView (${view.javaClass.simpleName}) from a blocked plugin"
        )
        return true
    }
}