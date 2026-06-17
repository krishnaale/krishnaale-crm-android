/**
 * Krishna Ale CRM — push backend (Firebase Cloud Functions, 2nd gen)
 *
 * Two HTTPS endpoints:
 *   POST /registerDevice      {email, token, platform}  -> stores email -> token map
 *   POST /agencyHandyWebhook  <AgencyHandy event JSON>   -> pushes to that client's devices
 *
 * Flow:
 *   AgencyHandy webhook  ->  agencyHandyWebhook  ->  FCM  ->  the client's phone(s)
 *
 * NOTE: The exact AgencyHandy payload shape must be confirmed with one real captured
 * event (use AgencyHandy's "Testing Webhook Event" / redeliver feature). The helpers
 * below try several likely field paths and log the raw payload when DEBUG_LOG_PAYLOAD
 * is set, so the mapping can be locked down quickly.
 */

const { onRequest } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

setGlobalOptions({ region: "australia-southeast1", maxInstances: 10 });

const DEVICES = "devices";

// ---------------------------------------------------------------------------
// 1) Device registration
// ---------------------------------------------------------------------------
exports.registerDevice = onRequest(async (req, res) => {
  if (req.method !== "POST") return res.status(405).send("Method not allowed");

  const { email, token, platform } = req.body || {};
  if (!email || !token) {
    return res.status(400).json({ error: "email and token are required" });
  }

  const key = String(email).trim().toLowerCase();
  try {
    const ref = db.collection(DEVICES).doc(key);
    await ref.set(
      {
        email: key,
        tokens: admin.firestore.FieldValue.arrayUnion(token),
        platform: platform || "android",
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
    logger.info(`Registered device for ${key}`);
    return res.status(200).json({ ok: true });
  } catch (e) {
    logger.error("registerDevice failed", e);
    return res.status(500).json({ error: "registration failed" });
  }
});

// ---------------------------------------------------------------------------
// 2) AgencyHandy webhook -> push
// ---------------------------------------------------------------------------
exports.agencyHandyWebhook = onRequest(async (req, res) => {
  if (req.method !== "POST") return res.status(405).send("Method not allowed");

  // --- Optional shared-secret check (set WEBHOOK_SECRET to enable) ---
  const expected = process.env.WEBHOOK_SECRET;
  if (expected) {
    const provided = req.get("x-webhook-secret") || req.query.secret;
    if (provided !== expected) {
      logger.warn("Rejected webhook: bad secret");
      return res.status(401).send("unauthorized");
    }
  }

  const payload = req.body || {};
  if (process.env.DEBUG_LOG_PAYLOAD === "true") {
    logger.info("RAW AGENCYHANDY PAYLOAD", JSON.stringify(payload));
  }

  // Always 200 quickly so AgencyHandy doesn't retry on our processing time.
  res.status(200).send("ok");

  try {
    const eventType = detectEventType(payload);
    const message = buildMessage(eventType, payload);
    if (!message) {
      logger.info(`Ignored event type: ${eventType}`);
      return;
    }

    const email = extractClientEmail(payload);
    if (!email) {
      logger.warn("No client email found in payload; cannot target push.");
      return;
    }

    await sendToClient(email.toLowerCase(), message);
  } catch (e) {
    logger.error("webhook processing failed", e);
  }
});

// ---------------------------------------------------------------------------
// Helpers — adjust field paths once a real payload is captured
// ---------------------------------------------------------------------------

function detectEventType(p) {
  const raw =
    p.event || p.type || p.eventType || p.event_name || p.action || "";
  return String(raw).toLowerCase();
}

/** Map an AgencyHandy event to a push. Return null to skip. */
function buildMessage(eventType, p) {
  const portal = process.env.PORTAL_URL || "https://krishnaale.agencyhandy.com/";

  // Task created / assigned
  if (eventType.includes("task") && (eventType.includes("creat") || eventType.includes("assign"))) {
    return {
      title: "New task",
      body: taskName(p) ? `A new task was added: ${taskName(p)}` : "A new task was added to your project.",
      type: "task",
      url: portal,
    };
  }
  // Task completed
  if (eventType.includes("task") && (eventType.includes("complet") || eventType.includes("done"))) {
    return {
      title: "Task completed",
      body: taskName(p) ? `Completed: ${taskName(p)}` : "A task on your project was completed.",
      type: "task",
      url: portal,
    };
  }
  // Invoice created / status change
  if (eventType.includes("invoice")) {
    return {
      title: "New invoice",
      body: invoiceLabel(p) ? `Invoice ${invoiceLabel(p)} is ready to view.` : "You have a new invoice to view.",
      type: "invoice",
      url: portal,
    };
  }
  // Payment received (bonus)
  if (eventType.includes("payment") && eventType.includes("receiv")) {
    return {
      title: "Payment received",
      body: "Thanks — your payment has been received.",
      type: "invoice",
      url: portal,
    };
  }
  return null;
}

/** Try several likely locations for the client's email. */
function extractClientEmail(p) {
  return (
    pick(p, "client.email") ||
    pick(p, "data.client.email") ||
    pick(p, "order.client.email") ||
    pick(p, "invoice.client.email") ||
    pick(p, "task.client.email") ||
    pick(p, "customer.email") ||
    pick(p, "data.email") ||
    pick(p, "email") ||
    null
  );
}

function taskName(p) {
  return pick(p, "task.name") || pick(p, "data.task.name") || pick(p, "data.name") || pick(p, "name") || null;
}

function invoiceLabel(p) {
  return (
    pick(p, "invoice.invoiceNumber") ||
    pick(p, "invoice.number") ||
    pick(p, "data.invoice.invoiceNumber") ||
    pick(p, "data.invoiceNumber") ||
    null
  );
}

/** Safe nested getter: pick(obj, "a.b.c"). */
function pick(obj, path) {
  return path.split(".").reduce((acc, key) => (acc && acc[key] != null ? acc[key] : undefined), obj);
}

async function sendToClient(email, message) {
  const snap = await db.collection(DEVICES).doc(email).get();
  if (!snap.exists) {
    logger.info(`No registered devices for ${email}`);
    return;
  }
  const tokens = (snap.data().tokens || []).filter(Boolean);
  if (tokens.length === 0) return;

  const res = await admin.messaging().sendEachForMulticast({
    tokens,
    data: {
      title: message.title,
      body: message.body,
      type: message.type,
      url: message.url || "",
    },
    android: { priority: "high" },
  });

  logger.info(`Sent to ${email}: ${res.successCount}/${tokens.length} delivered`);

  // Prune tokens that are no longer valid.
  const invalid = [];
  res.responses.forEach((r, i) => {
    if (!r.success) {
      const code = r.error && r.error.code;
      if (
        code === "messaging/invalid-registration-token" ||
        code === "messaging/registration-token-not-registered"
      ) {
        invalid.push(tokens[i]);
      }
    }
  });
  if (invalid.length) {
    await db
      .collection(DEVICES)
      .doc(email)
      .update({ tokens: admin.firestore.FieldValue.arrayRemove(...invalid) });
    logger.info(`Pruned ${invalid.length} stale token(s) for ${email}`);
  }
}
