# Push backend — Krishna Ale CRM

This turns AgencyHandy webhooks into push notifications:

```
AgencyHandy webhook  ->  agencyHandyWebhook (Cloud Function)  ->  FCM  ->  client's phone
```

The app registers each client's portal email + device token (`registerDevice`), so a
webhook about a given client's task/invoice is pushed only to that client's device(s).

---

## 1. Create the Firebase project

1. Go to <https://console.firebase.google.com> and **Add project** (you can reuse the
   project you create for the app's `google-services.json` — it should be the *same* project).
2. In the project, open **Build → Firestore Database** and create a database
   (Production mode is fine; the functions use the Admin SDK which bypasses rules).

## 2. Upgrade to the Blaze plan

Deploying Cloud Functions requires the **Blaze (pay-as-you-go)** plan. The free monthly
allowance is large (2M invocations, etc.), so for this use case the bill is typically **$0**.
Set a budget alert if you want peace of mind: Firebase Console → ⚙ → Usage and billing.

## 3. Install tooling and log in

```bash
npm install -g firebase-tools
firebase login
```

## 4. Point the project at your Firebase project

Edit `.firebaserc` and replace `REPLACE_WITH_YOUR_FIREBASE_PROJECT_ID` with your real
project ID (shown in Firebase Console → Project settings).

## 5. Configure environment

```bash
cd functions
cp .env.example .env
npm install
```

Leave `WEBHOOK_SECRET` blank for the very first test, set `DEBUG_LOG_PAYLOAD=true`
temporarily (see step 8).

## 6. Deploy

```bash
cd ..            # back to the backend/ folder
firebase deploy --only functions
```

After deploying, the CLI prints the function URLs. Both functions are also reachable at a
**shared base**:

```
https://australia-southeast1-YOUR_PROJECT.cloudfunctions.net/registerDevice
https://australia-southeast1-YOUR_PROJECT.cloudfunctions.net/agencyHandyWebhook
```

Use this `https://australia-southeast1-YOUR_PROJECT.cloudfunctions.net` form for the app's
`BACKEND_URL` (the app appends `/registerDevice` itself, so both endpoints must share one
host — don't use the per-function `*.run.app` URLs, which differ per function). You can
confirm the exact URLs in Firebase Console → Functions.

Copy that shared base into the app's `BACKEND_URL` in `app/build.gradle.kts`, then rebuild.

## 7. Configure the AgencyHandy webhook

In your workspace: **Integrations → Webhooks Management → Create New Webhook**.

- **Endpoint URL:** your `agencyHandyWebhook` URL.
  If you set a `WEBHOOK_SECRET`, append it as a query param: `...agencyHandyWebhook?secret=YOUR_SECRET`
  (or send it as the `x-webhook-secret` header if AgencyHandy lets you add headers).
- **Content type:** JSON.
- **Events:** tick **Task → Creation**, **Task → Completion**, **Invoice → Status change**
  (and optionally **Payment → Received**).
- Activate and **Save**.

## 8. Capture one real payload and finalise the mapping

The webhook handler guesses where the client's email and task/invoice names live in the
payload. Confirm it against a real event:

1. Set `DEBUG_LOG_PAYLOAD=true` in `.env`, redeploy.
2. In AgencyHandy, use **Testing Webhook Event** (or create/complete a test task) to fire one.
3. View the logged payload: `firebase functions:log` (look for `RAW AGENCYHANDY PAYLOAD`).
4. If the client's email isn't being found, adjust `extractClientEmail()` in `index.js`
   to match the real field path. Do the same for `taskName()` / `invoiceLabel()` if needed.
5. Set `DEBUG_LOG_PAYLOAD=false`, set a real `WEBHOOK_SECRET`, redeploy.

## Notes

- **Data-only messages**: pushes are sent as FCM *data* messages so the app controls the
  channel and tap behaviour even when backgrounded. If a user *force-stops* the app,
  Android may withhold delivery until they reopen it — normal background is unaffected.
- **Targeting trust**: clients self-enter their email in the app. For a client portal this
  is acceptable; if you want stronger guarantees later, add an email verification code step
  before activating notifications.
