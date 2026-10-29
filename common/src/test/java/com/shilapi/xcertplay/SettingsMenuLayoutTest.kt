package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25],manifest=Config.NONE)
class SettingsMenuLayoutTest {
    @Test fun newlyAddedZeroSizeMenuIsLaidOutBeforeInputIsBlocked() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity=owner.get()
            val root=FrameLayout(activity)
            activity.setContentView(root)
            root.layout(0,0,1280,720)
            val menu=FrameLayout(activity).apply {
                addView(View(activity),FrameLayout.LayoutParams(600,500))
                visibility=View.GONE
            }
            root.addView(menu,FrameLayout.LayoutParams(-1,-1))
            assertEquals(0,menu.width)
            assertTrue(SettingsMenuLayout.prepare(root,menu))
            assertEquals(1280,menu.width)
            assertEquals(720,menu.height)
            assertTrue(SettingsMenuLayout.ready(menu))
            menu.visibility=View.GONE
            assertFalse(SettingsMenuLayout.ready(menu))
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun unavailableWindowCannotClaimMenuInput() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root=FrameLayout(owner.get())
            val menu=FrameLayout(owner.get()).apply { addView(View(context)) }
            root.addView(menu)
            assertFalse(SettingsMenuLayout.prepare(root,menu))
            assertFalse(SettingsMenuLayout.ready(menu))
        } finally { owner.pause().stop().destroy() }
    }
}
