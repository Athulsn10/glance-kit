// Asks Firebase to validate the hug message (dry run: nothing is delivered) against a real
// push token, so a malformed message fails the deploy instead of every hug.
const admin = require("../functions/node_modules/firebase-admin");
const { buildMessage } = require("../functions/message");
admin.initializeApp({ projectId: "glance-kit-cf358" });

(async () => {
  const toks = await admin.firestore().collection("tokens").limit(5).get();
  const token = toks.docs.map((d) => d.get("token")).find(Boolean);
  if (!token) { console.log("No push tokens yet; skipped."); return; }
  const samples = [
    { type: "action", groupId: "check", circle: "couple", action: "hug", from: "check", fromName: "Check", tab: "people" },
    { type: "action", groupId: "check", circle: "friends", action: "poke", from: "check", fromName: "Check", tab: "people" },
    { type: "sync", groupId: "check", event: "left", fromName: "Check" },
  ];
  for (const data of samples) {
    try {
      await admin.messaging().send({ token, ...buildMessage(data) }, true);
      console.log(`ok: ${data.type} ${data.action || data.event}`);
    } catch (e) {
      if (e.code === "messaging/registration-token-not-registered") { console.log("Token is stale; skipped."); return; }
      console.log(`INVALID ${data.type} ${data.action || data.event}: ${e.code} ${e.message}`);
      process.exitCode = 1;
    }
  }
})().catch((e) => { console.log(`check failed: ${e.message}`); process.exitCode = 1; });
