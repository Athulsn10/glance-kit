// Prints recent People error reports from Firestore logs/ (app and server), newest first,
// and removes reports older than 14 days. Used by .github/workflows/backend-logs.yml.
// Usage: node read-logs.js <hours> <max>
const admin = require("../functions/node_modules/firebase-admin");

admin.initializeApp({ projectId: "glance-kit-cf358" });
const db = admin.firestore();

(async () => {
  const hours = Number(process.argv[2] || 24);
  const max = Number(process.argv[3] || 40);
  const since = admin.firestore.Timestamp.fromMillis(Date.now() - hours * 3600e3);
  const snap = await db.collection("logs").where("at", ">=", since).orderBy("at", "desc").limit(max).get();
  const lines = snap.docs.map((d) => {
    const x = d.data();
    const when = x.at ? x.at.toDate().toISOString().replace("T", " ").slice(5, 19) : "?";
    const who = (x.uid || "").slice(0, 6);
    if (x.source === "server") {
      return `${when} SERVER ${x.fn} [${x.code}] ${x.message} | uid ${who} | ${x.ms} ms | input ${JSON.stringify(x.input)}` + (x.stack ? ` | ${x.stack.split("\n").slice(0, 3).join(" < ")}` : "");
    }
    if (x.type === "info") return `${when} APP ${x.app} ${x.device} | ${x.where} | ${x.detail} | uid ${who}`;
    return `${when} APP ${x.app} A${x.android} ${x.device} | ${x.where} | ${x.type}${x.code ? " " + x.code : ""}: ${x.message} | uid ${who} | ${x.detail || ""}`;
  });
  console.log(lines.length ? lines.join("\n") : `No error reports in the last ${hours} h.`);

  const old = await db.collection("logs").where("at", "<", admin.firestore.Timestamp.fromMillis(Date.now() - 14 * 86400e3)).limit(400).get();
  if (!old.empty) {
    const b = db.batch();
    old.docs.forEach((d) => b.delete(d.ref));
    await b.commit();
    console.error(`(removed ${old.size} reports older than 14 days)`);
  }
})().catch((e) => { console.log(`Couldn't read logs: ${e.message}`); process.exit(1); });
