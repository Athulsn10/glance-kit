// Prints each People group's members and whether each can receive pushes (no names or places).
// Used by .github/workflows/backend-logs.yml.
const admin = require("../functions/node_modules/firebase-admin");
admin.initializeApp({ projectId: "glance-kit-cf358" });
const db = admin.firestore();
const ago = (ms) => (ms ? `${Math.round((Date.now() - ms) / 60000)} min ago` : "never");

(async () => {
  const groups = await db.collection("groups").get();
  const out = [];
  for (const g of groups.docs) {
    const members = g.get("members") || [];
    out.push(`group ${g.id.slice(0, 6)} ${g.get("circle")} | last action ${g.get("lastAction") || "-"} from ${(g.get("lastFrom") || "-").slice(0, 6)} ${ago(g.get("lastAt") && g.get("lastAt").toMillis())}`);
    for (const uid of members) {
      const [t, m] = await Promise.all([db.collection("tokens").doc(uid).get(), g.ref.collection("members").doc(uid).get()]);
      const tok = t.exists ? `push token saved ${ago(t.get("updatedAt") && t.get("updatedAt").toMillis())}` : "NO push token";
      const prof = m.exists ? `profile ok, location ${m.get("lat") != null ? "shared " + ago(m.get("at")) : "not shared"}${m.get("paused") ? " (paused)" : ""}` : "NO profile";
      out.push(`   member ${uid.slice(0, 6)}: ${tok}; ${prof}`);
    }
  }
  console.log(out.length ? out.join("\n") : "No groups.");
})().catch((e) => { console.log(`Couldn't read groups: ${e.message}`); process.exit(1); });
