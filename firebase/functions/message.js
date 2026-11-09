// Builds the push message for a hug, poke etc. Kept separate so tools/check-push.js can ask
// Firebase to validate exactly this message (dry run) before it's deployed.

// What each action says on the other phone (matches PeopleModel.kt).
const PAST = {
  hug: "sent a hug", miss: "misses you", kiss: "sent a kiss",
  highfive: "sent a high five", poke: "poked you", hangout: "wants to hang out",
  home: "got home safe", call: "asked you to call", love: "sends love",
};

function buildMessage(data) {
  const android = { priority: "high", ttl: 60 * 60 * 1000 };
  if (data.type === "action") {
    // A notification message, not data-only: Android shows it (and vibrates in the action's
    // channel pattern) even when the app is closed. Some phones (vivo, Xiaomi, Oppo...) never wake
    // a closed app for data-only messages.
    const sym = data.circle === "friends" ? "★" : "♥";
    android.notification = {
      title: `${sym} ${data.fromName} ${PAST[data.action] || "said hi"}`,
      body: "Tap to send one back",
      channelId: `pa3_${data.circle}_${data.action}`,
      icon: "ic_w_people",
      tag: `people_${data.groupId}`,
      priority: "high",
      clickAction: "com.athulsn10.glancekit.OPEN_PEOPLE",
    };
  }
  return { data, android };
}

module.exports = { buildMessage, PAST };
