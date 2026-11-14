/**
 * Glance Kit People: pairing with short codes, and delivering hugs, pokes and "home safe"
 * as push messages. Locations never pass through here; each phone writes its own to Firestore,
 * and only the people in the same group can read it (see ../firestore.rules).
 */
const crypto = require("crypto");
const { buildMessage } = require("./message");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
// A full vCPU makes cold starts and each call noticeably quicker than the fractional default.
setGlobalOptions({ region: "asia-south1", maxInstances: 5, memory: "512MiB", cpu: 1, concurrency: 20 });

const db = admin.firestore();

/**
 * Wraps a callable so every call is logged (Cloud Logging) and every failure is also kept in
 * Firestore logs/ for 14 days, where the "Backend logs" workflow can read it.
 */
function logged(name, handler) {
  return onCall(async (req) => {
    const started = Date.now();
    const uid = (req.auth && req.auth.uid) || null;
    const input = summarize(req.data);
    try {
      const out = await handler(req);
      console.log(JSON.stringify({ fn: name, uid, ms: Date.now() - started, input, ok: true, out: summarize(out) }));
      return out;
    } catch (e) {
      const code = e instanceof HttpsError ? e.code : "internal";
      const entry = {
        source: "server", fn: name, uid, ms: Date.now() - started, input,
        code, message: String(e && e.message || e).slice(0, 500),
        stack: e instanceof HttpsError ? null : String(e && e.stack || "").slice(0, 1500),
      };
      console.error(JSON.stringify(entry));
      try {
        await db.collection("logs").add({ ...entry, at: FieldValue.serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 14 * 86400000) });
      } catch (_) { /* logging must never hide the real error */ }
      if (e instanceof HttpsError) throw e;
      throw new HttpsError("internal", `${name} failed: ${entry.message}`.slice(0, 200));
    }
  });
}

/** What was sent, without anything personal (no names, places or codes in full). */
function summarize(d) {
  if (!d || typeof d !== "object") return d === undefined ? null : typeof d;
  const out = {};
  for (const [k, v] of Object.entries(d)) {
    if (k === "code" && typeof v === "string") out[k] = `${v.length} chars`;
    else if (k === "name") out[k] = typeof v;
    else out[k] = v === null || ["number", "boolean"].includes(typeof v) ? v : typeof v === "string" ? v.slice(0, 40) : typeof v;
  }
  return out;
}
const { FieldValue, Timestamp } = admin.firestore;

const CIRCLES = {
  // Partners can send everything, including what friends and family have.
  couple: { max: 2, actions: ["hug", "miss", "kiss", "love", "highfive", "poke", "hangout", "call", "home"] },
  friends: { max: 2, actions: ["highfive", "poke", "hangout"] },
  family: { max: 5, actions: ["home", "call", "love"] },
};
const MAX_GROUPS = 10;
const CODE_MINUTES = 10;
const ACTION_GAP_MS = 1000; // just enough to stop a stuck finger
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I/L

function needUser(req) {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Please try again in a moment.");
  return uid;
}

function newCode() {
  let s = "";
  for (let i = 0; i < 6; i++) s += ALPHABET[crypto.randomInt(ALPHABET.length)];
  return s;
}

async function groupFor(uid, gid) {
  if (typeof gid !== "string" || !gid || gid.length > 64) throw new HttpsError("invalid-argument", `Unknown group (${gid === undefined ? "missing" : typeof gid}).`);
  const ref = db.collection("groups").doc(gid);
  const snap = await ref.get();
  if (!snap.exists || !(snap.get("members") || []).includes(uid)) throw new HttpsError("permission-denied", "You're not in this group.");
  return { ref, snap };
}

/** Push tokens for these people, as [{ref, token}]. */
async function tokensFor(uids) {
  if (!uids.length) return [];
  const docs = await db.getAll(...uids.map((m) => db.collection("tokens").doc(m)));
  return docs.filter((d) => d.exists && d.get("token")).map((d) => ({ ref: d.ref, token: d.get("token") }));
}

/** Send a data push to these people and forget tokens that no longer work. */
async function push(uids, data) {
  const owners = await tokensFor(uids);
  if (!owners.length) return { sent: 0, tokens: 0, error: null };
  const { android } = buildMessage(data);
  const res = await admin.messaging().sendEachForMulticast({
    tokens: owners.map((o) => o.token),
    data,
    android,
  });
  const dead = [];
  res.responses.forEach((r, i) => {
    const code = r.error && r.error.code;
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") dead.push(owners[i].ref.delete());
  });
  await Promise.all(dead);
  const firstErr = res.responses.find((r) => r.error);
  if (firstErr) console.warn("push error", firstErr.error.code, firstErr.error.message);
  return {
    sent: res.successCount, tokens: owners.length,
    error: firstErr ? firstErr.error.code : null,
    errorMessage: firstErr ? String(firstErr.error.message).slice(0, 300) : null,
  };
}

/** Tell the others in a group to refetch it now (someone joined or left). Never fails the call. */
async function nudge(gid, uids, event, fromName) {
  try {
    await push(uids, { type: "sync", groupId: gid, event, fromName: String(fromName || "Someone").slice(0, 24) });
  } catch (e) {
    console.warn("nudge failed", e);
  }
}

async function nameOf(gref, uid) {
  const d = await gref.collection("members").doc(uid).get();
  return (d.exists && d.get("name")) || "Someone";
}

/** Make a code. Creates a new group, or (family) invites into an existing one. */
exports.createInvite = logged("createInvite", async (req) => {
  const uid = needUser(req);
  const circle = req.data && req.data.circle;
  if (!CIRCLES[circle]) throw new HttpsError("invalid-argument", "Pick Partner, Friend or Family.");

  let gid = req.data.groupId;
  if (gid) {
    const { snap } = await groupFor(uid, gid);
    if ((snap.get("members") || []).length >= CIRCLES[snap.get("circle")].max) {
      throw new HttpsError("failed-precondition", "This group is full.");
    }
  } else {
    const mine = await db.collection("groups").where("members", "array-contains", uid).count().get();
    if (mine.data().count >= MAX_GROUPS) throw new HttpsError("resource-exhausted", `You can be in up to ${MAX_GROUPS} groups.`);
    const ref = db.collection("groups").doc();
    await ref.set({ circle, members: [uid], createdBy: uid, createdAt: FieldValue.serverTimestamp() });
    gid = ref.id;
  }

  for (let attempt = 0; attempt < 6; attempt++) {
    const code = newCode();
    try {
      await db.collection("invites").doc(code).create({
        groupId: gid,
        createdBy: uid,
        expiresAt: Timestamp.fromMillis(Date.now() + CODE_MINUTES * 60 * 1000),
      });
      return { code, groupId: gid };
    } catch (e) {
      if (e.code !== 6) throw e; // 6 = ALREADY_EXISTS: try another code
    }
  }
  throw new HttpsError("unavailable", "Couldn't make a code. Try again.");
});

/** Join a group with someone's code. */
exports.joinWithCode = logged("joinWithCode", async (req) => {
  const uid = needUser(req);
  const code = String((req.data && req.data.code) || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (code.length !== 6) throw new HttpsError("invalid-argument", "Codes have 6 letters and numbers.");
  const invRef = db.collection("invites").doc(code);

  const out = await db.runTransaction(async (tx) => {
    const inv = await tx.get(invRef);
    if (!inv.exists) throw new HttpsError("not-found", "That code doesn't exist or has expired.");
    if (inv.get("expiresAt").toMillis() < Date.now()) {
      tx.delete(invRef);
      throw new HttpsError("deadline-exceeded", "That code has expired. Ask for a new one.");
    }
    const gref = db.collection("groups").doc(inv.get("groupId"));
    const g = await tx.get(gref);
    if (!g.exists) throw new HttpsError("not-found", "That group no longer exists.");
    const members = g.get("members") || [];
    const circle = g.get("circle");
    if (members.includes(uid)) return { groupId: gref.id, circle, others: [] };
    if (members.length >= CIRCLES[circle].max) throw new HttpsError("failed-precondition", "This group is already full.");
    tx.update(gref, { members: FieldValue.arrayUnion(uid) });
    if (members.length + 1 >= CIRCLES[circle].max || circle !== "family") tx.delete(invRef);
    return { groupId: gref.id, circle, others: members };
  });
  // Let the people already in the group see the newcomer straight away.
  await nudge(out.groupId, out.others, "joined", req.data && req.data.name);
  return { groupId: out.groupId, circle: out.circle };
});

/** Send a hug, poke, etc. Buzzes everyone else in the group (never the sender). */
exports.sendAction = logged("sendAction", async (req) => {
  const uid = needUser(req);
  // The app calls this with warm: true when People opens, so the first real send is quick.
  if (req.data && req.data.warm) return { warm: true };
  // "Test alerts" in the app: push a hug to your own phone to check the whole path.
  if (req.data && req.data.test) {
    const r = await push([uid], { type: "action", test: "1", groupId: "test", circle: "couple", action: "hug", sender: uid, fromName: "Test:", tab: "people" });
    return { ...r, others: 1 };
  }
  const action = req.data && req.data.action;
  const rate = db.collection("rate").doc(uid);
  const [{ ref, snap }, r] = await Promise.all([groupFor(uid, req.data && req.data.groupId), rate.get()]);
  const circle = snap.get("circle");
  if (!CIRCLES[circle].actions.includes(action)) throw new HttpsError("invalid-argument", `Unknown action "${action}" for ${circle}.`);

  // A little rate limit, so a stuck finger doesn't send fifty hugs.
  if (r.exists && Date.now() - r.get("at").toMillis() < ACTION_GAP_MS) throw new HttpsError("resource-exhausted", "Wait a second before sending another.");

  const others = (snap.get("members") || []).filter((m) => m !== uid);
  const [, , fromName] = await Promise.all([
    rate.set({ at: Timestamp.now() }),
    ref.update({ lastFrom: uid, lastAction: action, lastAt: FieldValue.serverTimestamp() }),
    nameOf(ref, uid),
  ]);
  const out = await push(others, { type: "action", groupId: ref.id, circle, action, sender: uid, fromName: String(fromName).slice(0, 24), tab: "people" });
  if (out.sent < others.length) {
    await db.collection("logs").add({
      source: "server", fn: "sendAction", uid, code: "not-delivered", input: { groupId: ref.id, action },
      message: `Reached ${out.sent} of ${others.length} (tokens ${out.tokens}, error ${out.error || "none"}${out.errorMessage ? ": " + out.errorMessage : ""})`,
      at: FieldValue.serverTimestamp(),
    }).catch(() => {});
  }
  return { ...out, others: others.length };
});

/** Leave a group. Deletes your profile and location from it; the group goes when empty. */
exports.leaveGroup = logged("leaveGroup", async (req) => {
  const uid = needUser(req);
  const { ref, snap } = await groupFor(uid, req.data && req.data.groupId);
  const name = await nameOf(ref, uid);
  const left = (snap.get("members") || []).filter((m) => m !== uid);
  if (left.length === 0) {
    await db.recursiveDelete(ref);
  } else {
    await Promise.all([ref.collection("members").doc(uid).delete(), ref.update({ members: FieldValue.arrayRemove(uid) })]);
    // Tell the others right away instead of waiting for their next sync.
    await nudge(ref.id, left, "left", name);
  }
  return { ok: true };
});
