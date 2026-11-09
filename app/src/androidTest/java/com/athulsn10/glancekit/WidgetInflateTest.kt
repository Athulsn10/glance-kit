package com.athulsn10.glancekit

import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Applies widget RemoteViews the same way a launcher does (same class filter and remotable-method
 * checks). A failure here is what shows up on the home screen as "Can't load widget".
 */
@RunWith(AndroidJUnit4::class)
class WidgetInflateTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext

    private fun apply(name: String, rv: () -> RemoteViews) {
        var err: Throwable? = null
        ins.runOnMainSync {
            try { rv().apply(ctx, FrameLayout(ctx)) } catch (t: Throwable) { err = t }
        }
        err?.let { throw AssertionError("$name: ${generateSequence(it) { e -> e.cause }.joinToString(" <- ") { e -> "${e.javaClass.simpleName}: ${e.message}" }}", it) }
    }

    @Test fun peopleInitialLayout() = apply("people initial layout") { RemoteViews(ctx.packageName, R.layout.people_widget) }

    @Test fun peopleEmpty() {
        PeopleRepo.saveGroups(ctx, emptyList())
        apply("people, no groups") { PeopleViews.views(ctx, 320f, 180f) }
    }

    @Test fun peoplePaired() {
        val them = Person("them", "Sam", Avatar(), 10.52, 76.21, System.currentTimeMillis(), false, "Asia/Kolkata", false, null, 0)
        for (circle in Circle.entries) {
            PeopleRepo.saveGroups(ctx, listOf(PeopleRepo.GroupData(Group("g1", circle, listOf("me", "them"), null, null, 0), listOf(them))))
            apply("people, paired ($circle)") { PeopleViews.views(ctx, 320f, 180f) }
        }
        PeopleRepo.saveGroups(ctx, emptyList())
    }
}
