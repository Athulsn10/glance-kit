/**
 * Glance Kit People: pairing with short codes, and delivering hugs, pokes and "home safe"
 * as push messages. Locations never pass through here; each phone writes its own to Firestore,
 * and only the people in the same group can read it (see ../firestore.rules).
 */
const crypto = require("crypto");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
setGlobalOptions({ region: "asia-south1", maxInstances: 5, memory: "256MiB" });

const db = admin.firestore();
const { FieldValue, Timestamp } = admin.firestore;

const CIRCLES = {
  couple: { max: 2, actions: ["hug", "miss", "kiss"] },
  friends: { max: 2, actions: ["highfive", "poke", "hangout"] },
  family: { max: 5, actions: ["home", "call", "love"] },
};
const MAX_GROUPS = 10;
const CODE_MINUTES = 10;
const ACTION_GAP_MS = 3000;
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
  if (typeof gid !== "string" || !gid || gid.length > 64) throw new HttpsError("invalid-argument", "Unknown group.");
  const ref = db.collection("groups").doc(gid);
  const snap = await ref.get();
  if (!snap.exists || !(snap.get("members") || []).includes(uid)) throw new HttpsError("permission-denied", "You're not in this group.");
  return { ref, snap };
}

/** Make a code. Creates a new group, or (family) invites into an existing one. */
exports.createInvite = onCall(async (req) => {
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
exports.joinWithCode = onCall(async (req) => {
  const uid = needUser(req);
  const code = String((req.data && req.data.code) || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (code.length !== 6) throw new HttpsError("invalid-argument", "Codes have 6 letters and numbers.");
  const invRef = db.collection("invites").doc(code);

  return db.runTransaction(async (tx) => {
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
    if (members.includes(uid)) return { groupId: gref.id, circle };
    if (members.length >= CIRCLES[circle].max) throw new HttpsError("failed-precondition", "This group is already full.");
    tx.update(gref, { members: FieldValue.arrayUnion(uid) });
    if (members.length + 1 >= CIRCLES[circle].max || circle !== "family") tx.delete(invRef);
    return { groupId: gref.id, circle };
  });
});

/** Send a hug, poke, etc. Buzzes everyone else in the group. */
exports.sendAction = onCall(async (req) => {
  const uid = needUser(req);
  const action = req.data && req.data.action;
  const { ref, snap } = await groupFor(uid, req.data && req.data.groupId);
  const circle = snap.get("circle");
  if (!CIRCLES[circle].actions.includes(action)) throw new HttpsError("invalid-argument", "Unknown action.");

  // A little rate limit, so a stuck finger doesn't send fifty hugs.
  const rate = db.collection("rate").doc(uid);
  await db.runTransaction(async (tx) => {
    const r = await tx.get(rate);
    const last = r.exists ? r.get("at").toMillis() : 0;
    if (Date.now() - last < ACTION_GAP_MS) throw new HttpsError("resource-exhausted", "Slow down a little.");
    tx.set(rate, { at: Timestamp.now() });
  });

  await ref.update({ lastFrom: uid, lastAction: action, lastAt: FieldValue.serverTimestamp() });

  const me = await ref.collection("members").doc(uid).get();
  const fromName = (me.exists && me.get("name")) || "Someone";
  const others = (snap.get("members") || []).filter((m) => m !== uid);
  if (!others.length) return { sent: 0 };
  const tokenDocs = await db.getAll(...others.map((m) => db.collection("tokens").doc(m)));
  const tokens = tokenDocs.filter((d) => d.exists && d.get("token")).map((d) => d.get("token"));
  if (!tokens.length) return { sent: 0 };

  const res = await admin.messaging().sendEachForMulticast({
    tokens,
    data: { groupId: ref.id, circle, action, from: uid, fromName: String(fromName).slice(0, 24) },
    android: { priority: "high", ttl: 60 * 60 * 1000 },
  });
  // Forget tokens that are no longer valid (app uninstalled, etc.).
  const dead = [];
  res.responses.forEach((r, i) => {
    const code = r.error && r.error.code;
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") dead.push(i);
  });
  await Promise.all(dead.map((i) => {
    const owner = tokenDocs.filter((d) => d.exists && d.get("token"))[i];
    return owner ? owner.ref.delete() : null;
  }));
  return { sent: res.successCount };
});

/** Leave a group. Deletes your profile and location from it; the group goes when empty. */
exports.leaveGroup = onCall(async (req) => {
  const uid = needUser(req);
  const { ref, snap } = await groupFor(uid, req.data && req.data.groupId);
  await ref.collection("members").doc(uid).delete();
  const left = (snap.get("members") || []).filter((m) => m !== uid);
  if (left.length === 0) {
    await db.recursiveDelete(ref);
  } else {
    await ref.update({ members: FieldValue.arrayRemove(uid) });
  }
  return { ok: true };
});
