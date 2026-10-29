package com.shilapi.xcertplay

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout

/** Some head units miss the first traversal of a menu added during a multi-touch event. */
internal object SettingsMenuLayout {
    fun prepare(root: FrameLayout, menu: View): Boolean {
        if(menu.parent!==root || !root.isAttachedToWindow || !root.isShown || root.width<=0 || root.height<=0)
            return false
        menu.visibility=View.VISIBLE
        menu.bringToFront()
        // Do not wait indefinitely for a requested traversal before handing input to the menu.
        menu.measure(View.MeasureSpec.makeMeasureSpec(root.width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height,View.MeasureSpec.EXACTLY))
        menu.layout(0,0,root.width,root.height)
        root.requestLayout()
        root.postInvalidateOnAnimation()
        return ready(menu)
    }
    fun ready(menu: View?): Boolean {
        if(menu==null || !menu.isAttachedToWindow || !menu.isShown || menu.width<=0 || menu.height<=0) return false
        val visible=Rect()
        if(!menu.getGlobalVisibleRect(visible) || visible.isEmpty) return false
        val panel=(menu as? android.view.ViewGroup)?.getChildAt(0)
        return panel!=null && panel.width>0 && panel.height>0
    }
}
