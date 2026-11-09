package com.athulsn10.glancekit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

/** One avatar, gently bobbing and blinking. */
class AvatarView(ctx: Context) : View(ctx) {
    var avatar: Avatar = Avatar()
        set(v) { field = v; invalidate() }
    var mood: Mood = Mood.HAPPY
        set(v) { field = v; invalidate() }
    var arms: Arms = Arms.DOWN
    var animate = true
    private val vis = android.graphics.Rect()

    private val tick = object : Runnable {
        override fun run() {
            if (isShown && getGlobalVisibleRect(vis)) invalidate()
            postDelayed(this, if (isShown) 50L else 1000L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animate) { removeCallbacks(tick); post(tick) } }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width / 2.1f, height / 1.95f)
        val now = SystemClock.uptimeMillis()
        val bob = if (animate) sin(now / 400.0).toFloat() * s * 0.03f else 0f
        val blink = animate && (now % 3800L) < 140L
        AvatarArt.draw(canvas, width / 2f, height * 0.4f + bob, s, avatar, mood, arms, blink = blink)
    }
}

/** The People tab in the app. */
object PeoplePage {

    fun build(a: MainActivity, col: LinearLayout, scope: CoroutineScope, refresh: () -> Unit) {
        val c: Context = a
        if (!PeopleRepo.configured) {
            col.addView(Ui.card(c, "PEOPLE").apply {
                addView(Ui.text(c, "Coming soon", 22f, Ui.WHITE, medium = true))
                addView(Ui.spacer(c, 8))
                addView(Ui.text(c, "Pair with your partner, a friend or your family to see how far apart you are and send hugs and pokes. This build isn't connected yet.", 14f, Ui.SOFT))
            }, Ui.params(c, 4))
            return
        }
        val groups = PeopleRepo.cached(c)
        val me = PeopleRepo.me(c)

        // ---------- You ----------
        col.addView(Ui.card(c).apply {
            val row = Ui.hstack(c).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(AvatarView(c).apply { avatar = me.avatar; mood = me.mood ?: Mood.HAPPY },
                LinearLayout.LayoutParams(Ui.dp(c, 110), Ui.dp(c, 130)))
            val info = Ui.vstack(c).apply { setPadding(Ui.dp(c, 16), 0, 0, 0) }
            info.addView(Ui.text(c, PeopleRepo.name(c).ifEmpty { "Set your name" }, 24f, Ui.WHITE, medium = true))
            info.addView(Ui.text(c, if (PeopleRepo.paused(c)) "Sharing paused" else "Sharing every ${PeopleRepo.interval(c)} min", 13f, Ui.SOFT).apply { setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12)) })
            info.addView(Ui.pill(c, if (PeopleRepo.hasAvatar(c)) "Edit avatar" else "Make your avatar", !PeopleRepo.hasAvatar(c)) {
                a.startActivity(Intent(a, AvatarEditorActivity::class.java))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44)))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(row)
            addView(Ui.spacer(c, 14))
            addView(Ui.label(c, "TODAY I FEEL"))
            addView(Ui.spacer(c, 8))
            addView(chips(c, listOf("Auto") + Mood.entries.map { it.label }, (PeopleRepo.mood(c)?.ordinal ?: -1) + 1) { i ->
                PeopleRepo.setMood(c, if (i == 0) null else Mood.entries[i - 1])
                scope.launch { withContext(Dispatchers.IO) { PeopleRepo.pushProfile(c) }; PeopleViews.update(c) }
                refresh()
            })
        }, Ui.params(c, 4))

        // ---------- Location permission ----------
        if (groups.isNotEmpty() && (!PeopleRepo.hasFine(c) || !PeopleRepo.hasBackground(c))) {
            col.addView(Ui.card(c, "LOCATION").apply {
                val fine = PeopleRepo.hasFine(c)
                addView(Ui.text(c, if (!fine) "Allow location to share distance" else "Allow \"all the time\"", 17f, Ui.WHITE, medium = true))
                addView(Ui.spacer(c, 6))
                addView(Ui.text(c,
                    if (!fine) "Glance Kit uses your location only to work out how far apart you are. People in your groups see the distance, never a map."
                    else "So the distance can update every ${PeopleRepo.interval(c)} minutes while the app is closed. On the next screen, choose \"Allow all the time\".",
                    13f, Ui.SOFT))
                addView(Ui.spacer(c, 12))
                addView(Ui.pill(c, if (!fine) "Allow location" else "Open settings", true) {
                    if (!fine) a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_FINE)
                    else a.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 46)))
            }, Ui.params(c, 12))
        }

        // ---------- Notifications (how hugs and pokes buzz this phone) ----------
        if (groups.isNotEmpty() && !PeopleMessaging.canAlert(c)) {
            col.addView(Ui.card(c, "NOTIFICATIONS").apply {
                addView(Ui.text(c, "Allow notifications to feel their hugs", 17f, Ui.WHITE, medium = true))
                addView(Ui.spacer(c, 6))
                addView(Ui.text(c, "When someone sends you a hug, poke or \"home safe\", your phone buzzes in its own pattern. Android needs notifications on for that.", 13f, Ui.SOFT))
                addView(Ui.spacer(c, 12))
                addView(Ui.pill(c, "Allow notifications", true) {
                    if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                    } else {
                        a.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, a.packageName))
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 46)))
            }, Ui.params(c, 12))
        }

        // ---------- Groups ----------
        val shown = PeopleRepo.widgetData(c)?.group?.id
        for (gd in groups) col.addView(groupCard(a, gd, me, gd.group.id == shown && groups.size > 1, scope, refresh), Ui.params(c, 12))

        // ---------- Add someone ----------
        col.addView(Ui.card(c, if (groups.isEmpty()) "PAIR" else "ADD SOMEONE").apply {
            if (groups.isEmpty()) {
                addView(Ui.text(c, "Pair with someone", 22f, Ui.WHITE, medium = true))
                addView(Ui.spacer(c, 6))
                addView(Ui.text(c, "One of you makes a code, the other types it in. You both have to agree, and either of you can pause or leave at any time.", 14f, Ui.SOFT))
                addView(Ui.spacer(c, 14))
            }
            addView(Ui.label(c, "MAKE A CODE FOR"))
            addView(Ui.spacer(c, 8))
            val row = Ui.hstack(c)
            Circle.entries.forEachIndexed { i, circle ->
                row.addView(Ui.text(c, circle.label, 14f, Ui.WHITE, medium = true, gravity = Gravity.CENTER).apply {
                    background = Ui.rounded(circle.accent, Ui.dp(c, 22).toFloat())
                    setOnClickListener { invite(a, scope, circle, null, refresh) }
                }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { if (i > 0) marginStart = Ui.dp(c, 8) })
            }
            addView(row)
            addView(Ui.spacer(c, 10))
            addView(Ui.pill(c, "I have a code", false) { enterCode(a, scope, refresh) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 46)))
        }, Ui.params(c, 12))

        // ---------- Settings ----------
        col.addView(Ui.card(c, "SHARING").apply {
            addView(Ui.label(c, "UPDATE DISTANCE EVERY"))
            addView(Ui.spacer(c, 8))
            val opts = listOf(15, 30, 60)
            addView(chips(c, opts.map { if (it == 60) "1 hour" else "$it min" }, opts.indexOf(PeopleRepo.interval(c))) { i ->
                PeopleRepo.setInterval(c, opts[i]); PeopleSync.schedule(c, replace = true); refresh()
            })
            addView(Ui.spacer(c, 6))
            addView(Ui.text(c, "Each update takes one location and sends it to your groups. Nothing runs in between.", 12f, Ui.FAINT))
            addView(Ui.spacer(c, 14))
            val paused = PeopleRepo.paused(c)
            addView(Ui.pill(c, if (paused) "Resume sharing" else "Pause sharing", paused) {
                PeopleRepo.setPaused(c, !paused)
                scope.launch { withContext(Dispatchers.IO) { PeopleRepo.pushProfile(c) }; PeopleViews.update(c) }
                refresh()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 46)))
            if (groups.any { it.group.circle == Circle.FAMILY }) {
                addView(Ui.spacer(c, 10))
                addView(Ui.pill(c, if (PeopleRepo.hasHome(c)) "Home is set · set it here again" else "This is my home", false) {
                    val ok = PeopleRepo.setHomeHere(c)
                    Toast.makeText(c, if (ok) "Home saved on this phone. Family sees only \"home\"." else "Waiting for your location first.", Toast.LENGTH_LONG).show()
                    if (ok) PeopleSync.now(c)
                    refresh()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 46)))
            }
            if (groups.isNotEmpty()) {
                addView(Ui.spacer(c, 18))
                addView(Ui.label(c, "ALERTS ON THIS PHONE"))
                addView(Ui.spacer(c, 6))
                val (pushOk, pushErr) = PeopleRepo.pushStatus(c)
                val notif = PeopleMessaging.canAlert(c)
                val last = PeopleRepo.lastAlert(c)
                val lines = listOf(
                    (if (notif) "✓ Notifications on" else "✗ Notifications off (hugs still buzz, but without a message)"),
                    (if (pushOk) "✓ Registered for hugs and pokes" else "✗ Not registered yet" + (pushErr?.let { ": $it" } ?: "")),
                    "Last alert received: " + (if (last > 0) People.ago(last) else "never"),
                )
                addView(Ui.text(c, lines.joinToString("\n"), 13f, Ui.SOFT))
                addView(Ui.spacer(c, 10))
                addView(Ui.pill(c, "Test: send a hug to this phone", false) {
                    Toast.makeText(c, "Sending a test hug. Lock your phone to feel it.", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { runCatching { PeopleRepo.testAlert(c) } }
                        val msg = res.fold({
                            if (it.sent > 0) null
                            else if (it.tokens == 0) "This phone isn't registered for alerts. " + (PeopleRepo.pushStatus(c).second ?: "Check your internet and try again.")
                            else "The cloud couldn't reach this phone" + (it.error?.let { e -> " ($e)" } ?: "")
                        }, { PeopleLog.e(c, "testAlert", it); PeopleLog.friendly(it) })
                        if (msg != null) Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 46)))
            }
            addView(Ui.spacer(c, 12))
            addView(Ui.text(c, "Your location is rounded to about 10 m and replaced at each update; no history is kept. People in your groups see only the distance, never a map. Your home stays on this phone.", 12f, Ui.FAINT))
        }, Ui.params(c, 12))
    }

    private fun groupCard(a: MainActivity, gd: PeopleRepo.GroupData, me: Person, isWidget: Boolean, scope: CoroutineScope, refresh: () -> Unit): LinearLayout {
        val c: Context = a
        val g = gd.group
        val circle = g.circle
        val other = gd.others.firstOrNull()
        val scene = if (circle == Circle.FAMILY) Scene.NEAR else People.scene(g, me, other)
        return Ui.card(c).apply {
            val head = Ui.hstack(c)
            head.addView(Ui.label(c, circle.title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val status = if (circle == Circle.FAMILY) "${gd.others.size + 1} OF ${circle.max}" else People.status(circle, scene, g)
            head.addView(Ui.text(c, status, 11f, if (scene == Scene.TOGETHER || scene == Scene.RECEIVED) circle.accent else Ui.FAINT, mono = true))
            addView(head)
            addView(PeopleSceneView(c).apply { group = g; this.me = me; others = gd.others },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 210)))
            val (big, small) = if (circle == Circle.FAMILY) {
                if (gd.others.isEmpty()) "Waiting for family" to "share your invite code"
                else "${gd.others.size + 1} of you" to gd.others.joinToString(" · ") { p ->
                    p.name + " " + (if (p.atHome) "home" else People.distance(me, p)?.let { People.fmtDistance(it) } ?: "—")
                }
            } else People.lines(circle, scene, g, me, other)
            addView(Ui.text(c, big, 26f, Ui.WHITE, medium = true))
            addView(Ui.text(c, small, 13f, Ui.SOFT).apply { setPadding(0, Ui.dp(c, 6), 0, 0) })
            addView(Ui.spacer(c, 14))
            if (gd.others.isNotEmpty()) {
                addView(Ui.label(c, "SEND · EACH BUZZES DIFFERENTLY"))
                addView(Ui.spacer(c, 8))
                val row = Ui.hstack(c)
                circle.actions.forEachIndexed { i, act ->
                    row.addView(Ui.text(c, act.label, 14f, Ui.WHITE, medium = true, gravity = Gravity.CENTER).apply {
                        background = Ui.rounded(if (i == 0) circle.accent else 0xFF242424.toInt(), Ui.dp(c, 22).toFloat())
                        setOnClickListener {
                            // No buzz here: only the people you send it to feel it.
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { runCatching { PeopleRepo.send(c, g.id, act.key) } }
                                val msg = res.fold({ it.message(act.label, other?.name ?: "their") }, { PeopleLog.e(c, "send ${act.key}", it); PeopleLog.friendly(it) })
                                Toast.makeText(c, msg, if (res.getOrNull()?.sent ?: 0 > 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                            }
                        }
                    }, LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f).apply { if (i > 0) marginStart = Ui.dp(c, 8) })
                }
                addView(row)
                addView(Ui.spacer(c, 10))
            }
            val row2 = Ui.hstack(c)
            val pills = ArrayList<TextView>()
            if (gd.others.isEmpty() || (circle == Circle.FAMILY && gd.others.size + 1 < circle.max)) {
                pills.add(Ui.pill(c, "Invite", false) { invite(a, scope, circle, g.id, refresh) })
            }
            if (!isWidget && PeopleRepo.cached(c).size > 1) pills.add(Ui.pill(c, "Show on widget", false) {
                PeopleRepo.setWidgetGroup(c, g.id); PeopleViews.update(c); refresh()
            })
            pills.add(Ui.pill(c, "Leave", false) {
                AlertDialog.Builder(a)
                    .setTitle("Leave this group?")
                    .setMessage("You'll stop sharing with them, and they'll stop seeing you.")
                    .setPositiveButton("Leave") { _, _ ->
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                runCatching {
                                    PeopleRepo.leave(c, g.id) {
                                        scope.launch(Dispatchers.Main) { PeopleViews.update(c); refresh() }
                                    }
                                }.exceptionOrNull()
                            }
                            if (err != null) {
                                PeopleLog.e(c, "leave", err)
                                Toast.makeText(c, "Couldn't leave: " + PeopleLog.friendly(err), Toast.LENGTH_LONG).show()
                            }
                            PeopleViews.update(c); refresh()
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            })
            pills.forEachIndexed { i, p -> row2.addView(p, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { if (i > 0) marginStart = Ui.dp(c, 8) }) }
            addView(row2)
        }
    }

    private fun invite(a: MainActivity, scope: CoroutineScope, circle: Circle, groupId: String?, refresh: () -> Unit) {
        if (PeopleRepo.name(a).isEmpty() || !PeopleRepo.hasAvatar(a)) {
            Toast.makeText(a, "First, make your avatar and add your name.", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(a, AvatarEditorActivity::class.java))
            return
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { PeopleRepo.createInvite(a, circle, groupId) } }
            res.onSuccess { (code, gid) ->
                PeopleRepo.setWidgetGroup(a, gid)
                withContext(Dispatchers.IO) { PeopleRepo.refresh(a) }
                PeopleSync.schedule(a)
                a.startPeopleLive() // so you see them the moment they join
                refresh()
                val pretty = code.chunked(3).joinToString("-")
                val box = Ui.vstack(a).apply { setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), 0) }
                box.addView(Ui.text(a, pretty, 36f, circle.accent, mono = true, gravity = Gravity.CENTER),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(Ui.text(a, "Ask them to open Glance Kit > People > I have a code. It works for 10 minutes.", 14f, Ui.SOFT).apply { setPadding(0, Ui.dp(a, 12), 0, 0) })
                AlertDialog.Builder(a)
                    .setTitle("Your ${circle.label.lowercase()} code")
                    .setView(box)
                    .setPositiveButton("Share") { _, _ ->
                        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Join me on Glance Kit: open People, tap \"I have a code\" and enter $pretty"), "Share code"))
                    }
                    .setNegativeButton("Done", null).show()
                if (!PeopleRepo.hasFine(a)) a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_FINE)
            }.onFailure { PeopleLog.e(a, "createInvite ${circle.key}", it); Toast.makeText(a, PeopleLog.friendly(it), Toast.LENGTH_LONG).show() }
        }
    }

    private fun enterCode(a: MainActivity, scope: CoroutineScope, refresh: () -> Unit) {
        if (PeopleRepo.name(a).isEmpty() || !PeopleRepo.hasAvatar(a)) {
            Toast.makeText(a, "First, make your avatar and add your name.", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(a, AvatarEditorActivity::class.java))
            return
        }
        val input = EditText(a).apply {
            hint = "ABC-123"
            textSize = 26f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(8), InputFilter.AllCaps())
        }
        val box = Ui.vstack(a).apply { setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), 0); addView(input) }
        AlertDialog.Builder(a)
            .setTitle("Enter their code")
            .setMessage("By joining, you'll share your approximate distance with them, and see theirs. You can pause or leave at any time.")
            .setView(box)
            .setPositiveButton("Join") { _, _ ->
                scope.launch {
                    val res = withContext(Dispatchers.IO) { runCatching { PeopleRepo.join(a, input.text.toString()) } }
                    res.onSuccess { gid ->
                        PeopleRepo.setWidgetGroup(a, gid)
                        withContext(Dispatchers.IO) { PeopleRepo.refresh(a) }
                        PeopleSync.schedule(a); PeopleSync.now(a)
                        a.startPeopleLive()
                        PeopleViews.update(a)
                        refresh()
                        Toast.makeText(a, "You're paired!", Toast.LENGTH_SHORT).show()
                        if (!PeopleRepo.hasFine(a)) a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_FINE)
                    }.onFailure {
                        PeopleLog.e(a, "join", it)
                        Toast.makeText(a, PeopleLog.friendly(it), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** A wrapping row of selectable chips. */
    fun chips(c: Context, items: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = Ui.hstack(c)
        items.forEachIndexed { i, s ->
            val on = i == selected
            row.addView(Ui.text(c, s, 13f, if (on) Ui.BG else Ui.WHITE, medium = true, gravity = Gravity.CENTER).apply {
                setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0)
                background = Ui.rounded(if (on) Ui.WHITE else 0xFF1E1E1E.toInt(), Ui.dp(c, 17).toFloat())
                setOnClickListener { onPick(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 34)).apply { if (i > 0) marginStart = Ui.dp(c, 8) })
        }
        return HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    const val REQ_FINE = 41
    const val REQ_BG = 42
    const val REQ_NOTIF = 43
}

/** Make your avatar: start from a look, then change any part. */
class AvatarEditorActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var av = Avatar()
    private lateinit var preview: AvatarView
    private lateinit var body: LinearLayout
    private lateinit var nameField: EditText
    private var previewMood = 0
    private val moods = listOf(Mood.HAPPY, Mood.LOVE, Mood.EXCITED, Mood.WINK, Mood.LAUGH, Mood.SHY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        av = if (PeopleRepo.hasAvatar(this)) PeopleRepo.avatar(this) else AvatarArt.PRESETS.first().second
        val root = Ui.vstack(this).apply { setBackgroundColor(Ui.BG) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        body = Ui.vstack(this).apply { setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 32)) }
        scroll.addView(body)

        val top = Ui.hstack(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 16), Ui.dp(context, 8)) }
        top.addView(Ui.text(this, "Your avatar", 28f, Ui.WHITE, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.pill(this, "Save", true) { save() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)))
        root.addView(top)

        preview = AvatarView(this).apply {
            avatar = av
            setOnClickListener { previewMood = (previewMood + 1) % moods.size; mood = moods[previewMood] }
        }
        root.addView(Ui.card(this).apply {
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 200)))
            addView(Ui.text(context, "Tap to try a mood", 12f, Ui.FAINT, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ui.dp(this@AvatarEditorActivity, 16); marginEnd = Ui.dp(this@AvatarEditorActivity, 16)
        })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(0, b.top + Ui.dp(this, 12), 0, b.bottom)
            insets
        }
        nameField = EditText(this).apply {
            setText(PeopleRepo.name(context))
            hint = "Your name (what they'll see)"
            setTextColor(Ui.WHITE); setHintTextColor(Ui.FAINT)
            textSize = 17f
            filters = arrayOf(InputFilter.LengthFilter(24))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            background = Ui.rounded(0xFF1A1A1A.toInt(), Ui.dp(context, 14).toFloat())
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12))
        }
        rebuild()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun set(a: Avatar) { av = a.sane(); preview.avatar = av; rebuild() }

    private fun rebuild() {
        val c: Context = this
        val y = (body.parent as? ScrollView)?.scrollY ?: 0
        body.removeAllViews()
        (nameField.parent as? ViewGroup)?.removeView(nameField)
        body.addView(Ui.label(c, "NAME"), Ui.params(c, 8))
        body.addView(nameField, Ui.params(c, 8))

        body.addView(Ui.label(c, "STARTING LOOKS"), Ui.params(c, 20))
        val looks = Ui.hstack(c)
        for ((n, a) in AvatarArt.PRESETS) {
            val tile = Ui.vstack(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                background = Ui.rounded(0xFF181818.toInt(), Ui.dp(c, 18).toFloat())
                setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 8))
                setOnClickListener { set(a) }
            }
            tile.addView(AvatarView(c).apply { avatar = a; animate = false }, LinearLayout.LayoutParams(Ui.dp(c, 76), Ui.dp(c, 84)))
            tile.addView(Ui.text(c, n, 12f, Ui.SOFT, medium = true, gravity = Gravity.CENTER))
            looks.addView(tile, LinearLayout.LayoutParams(Ui.dp(c, 84), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(c, 8) })
        }
        body.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(looks) }, Ui.params(c, 8))

        body.addView(Ui.label(c, "BOY OR GIRL"), Ui.params(c, 20))
        body.addView(PeoplePage.chips(c, listOf("Boy", "Girl"), if (av.gender == "f") 1 else 0) { i ->
            val g = if (i == 1) "f" else "m"
            if (g != av.gender) set(av.copy(gender = g, style = if (g == "f") "long" else "short"))
        }, Ui.params(c, 8))

        val styles = if (av.gender == "f") AvatarArt.GIRL_STYLES else AvatarArt.BOY_STYLES
        body.addView(Ui.label(c, "HAIR"), Ui.params(c, 20))
        body.addView(PeoplePage.chips(c, styles.map { it.replaceFirstChar(Char::uppercase).replace("Manbun", "Bun") }, styles.indexOf(av.style)) { i -> set(av.copy(style = styles[i])) }, Ui.params(c, 8))
        body.addView(swatches(c, AvatarArt.HAIRS, av.hair) { set(av.copy(hair = it)) }, Ui.params(c, 10))

        body.addView(Ui.label(c, "SKIN"), Ui.params(c, 20))
        body.addView(swatches(c, AvatarArt.SKINS, av.skin) { set(av.copy(skin = it)) }, Ui.params(c, 8))

        body.addView(Ui.label(c, "OUTFIT"), Ui.params(c, 20))
        body.addView(PeoplePage.chips(c, AvatarArt.OUTFIT_STYLES.map { it.replaceFirstChar(Char::uppercase) }, AvatarArt.OUTFIT_STYLES.indexOf(av.outfit)) { i -> set(av.copy(outfit = AvatarArt.OUTFIT_STYLES[i])) }, Ui.params(c, 8))
        body.addView(swatches(c, AvatarArt.OUTFITS, av.color) { set(av.copy(color = it)) }, Ui.params(c, 10))

        body.addView(Ui.label(c, "EXTRAS · PICK ANY"), Ui.params(c, 20))
        val extras = Ui.vstack(c)
        AvatarArt.EXTRAS.chunked(4).forEach { chunk ->
            val row = Ui.hstack(c)
            chunk.forEachIndexed { i, e ->
                val on = e in av.acc
                row.addView(Ui.text(c, AvatarArt.EXTRA_LABELS[e] ?: e, 13f, if (on) Ui.BG else Ui.WHITE, medium = true, gravity = Gravity.CENTER).apply {
                    background = Ui.rounded(if (on) Ui.WHITE else 0xFF1E1E1E.toInt(), Ui.dp(c, 17).toFloat())
                    setOnClickListener { set(av.copy(acc = if (on) av.acc - e else av.acc + e)) }
                }, LinearLayout.LayoutParams(0, Ui.dp(c, 36), 1f).apply { if (i > 0) marginStart = Ui.dp(c, 8) })
            }
            repeat(4 - chunk.size) { row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = Ui.dp(c, 8) }) }
            extras.addView(row, Ui.params(c, 8))
        }
        body.addView(extras)
        body.post { (body.parent as? ScrollView)?.scrollTo(0, y) }
    }

    private fun swatches(c: Context, colors: IntArray, selected: Int, onPick: (Int) -> Unit): View {
        val row = Ui.hstack(c)
        colors.forEachIndexed { i, col ->
            val v = View(c).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(col)
                    if (i == selected) setStroke(Ui.dp(c, 3), Color.rgb(255, 122, 150))
                }
                setOnClickListener { onPick(i) }
            }
            row.addView(v, LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)).apply { marginEnd = Ui.dp(c, 10) })
        }
        return HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun save() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Add your name so they know it's you.", Toast.LENGTH_SHORT).show()
            return
        }
        PeopleRepo.setName(this, name)
        PeopleRepo.setAvatar(this, av)
        val app = applicationContext
        // Outlives this screen, which closes right away.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { PeopleRepo.pushProfile(app) }
            PeopleViews.update(app)
        }
        finish()
    }
}
