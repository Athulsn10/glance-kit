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

/**
 * Puts the People widget through a real widget host (the same system path a launcher uses),
 * at several sizes, and fails with the size and reason if the host can't show it.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHostTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext

    class ProbeView(c: android.content.Context) : android.appwidget.AppWidgetHostView(c) {
        @Volatile var errors = 0
        @Volatile var updates = 0
        override fun getErrorView(): android.view.View { errors++; return super.getErrorView() }
        override fun updateAppWidget(remoteViews: RemoteViews?) { updates++; super.updateAppWidget(remoteViews) }
    }

    class ProbeHost(c: android.content.Context) : android.appwidget.AppWidgetHost(c, 4242) {
        override fun onCreateView(context: android.content.Context, appWidgetId: Int, appWidget: android.appwidget.AppWidgetProviderInfo?) = ProbeView(context)
    }

    private fun bytes(rv: RemoteViews): Int {
        val p = android.os.Parcel.obtain()
        return try { rv.writeToParcel(p, 0); p.dataSize() } finally { p.recycle() }
    }

    @Test fun peopleThroughHost() {
        ins.uiAutomation.executeShellCommand("appwidget grantbind --package ${ctx.packageName} --user 0").close()
        Thread.sleep(500)
        val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
        val host = ProbeHost(ctx)
        ins.runOnMainSync { host.startListening() }
        val id = host.allocateAppWidgetId()
        try {
            val bound = mgr.bindAppWidgetIdIfAllowed(id, android.content.ComponentName(ctx, PeopleWidget::class.java))
            if (!bound) throw AssertionError("Couldn't bind a test widget (grantbind failed)")
            val them = Person("them", "Sam", Avatar(), 10.52, 76.21, System.currentTimeMillis(), false, "Asia/Kolkata", false, null, 0)
            PeopleRepo.saveGroups(ctx, listOf(PeopleRepo.GroupData(Group("g1", Circle.COUPLE, listOf("me", "them"), null, null, 0), listOf(them))))
            val report = StringBuilder()
            var failed = false
            for ((w, h) in listOf(300 to 160, 380 to 220, 420 to 300, 460 to 420)) {
                lateinit var view: ProbeView
                ins.runOnMainSync { view = host.createView(ctx, id, mgr.getAppWidgetInfo(id)) as ProbeView }
                Thread.sleep(800)
                val rv = PeopleViews.views(ctx, w.toFloat(), h.toFloat())
                val size = bytes(rv)
                val err = runCatching { mgr.updateAppWidget(id, rv) }.exceptionOrNull()
                Thread.sleep(1500)
                ins.waitForIdleSync()
                val line = "${w}x${h}dp: ${size / 1024} KB, updates=${view.updates}, errors=${view.errors}" + (err?.let { ", update threw ${it.javaClass.simpleName}: ${it.message}" } ?: "")
                report.appendLine(line)
                android.util.Log.i("WidgetHostTest", line)
                if (view.errors > 0 || err != null) failed = true
            }
            if (failed) throw AssertionError("People widget failed in a real host:\n$report")
            // Pass, but still surface the sizes in the results.
            android.util.Log.i("WidgetHostTest", report.toString())
        } finally {
            host.deleteAppWidgetId(id)
            ins.runOnMainSync { host.stopListening() }
            PeopleRepo.saveGroups(ctx, emptyList())
        }
    }
}
