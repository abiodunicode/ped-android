# ped-android

Kotlin Android frontend for the PED Go API.

What it includes:

- Login screen for `POST /api/login`
- Authenticated capture screen with the same four record types as the Nuxt app
- Offline queue stored on-device
- Separate sync screen for pushing queued records to the Go API
- Logout support via `POST /api/logout`

Implementation notes:

- Built with Kotlin + Jetpack Compose
- Uses `SharedPreferences` for session preferences and SQLite for queued captures
- Uses `HttpURLConnection` so the project has no API client library dependency
- Uses full-resolution file capture with JPEG resizing/compression to keep uploads near or under 200 KB

API configuration:

- Edit `PED_API_BASE` in [gradle.properties](gradle.properties)
- The configured value points to the PED Go API; override it for your target environment
- For phone testing, do not use `localhost`; use an ngrok HTTPS backend URL or another reachable host

Suggested run flow:

1. Open `ped-android` in Android Studio.
2. Let Gradle sync.
3. Update `PED_API_BASE`.
4. Run on an emulator or physical device.

The Android SDK and Android Studio bundled JBR can build this project locally. See the pre-inspection test instructions below.

## PED pre-inspection tally

Open **Tally** in the bottom navigation, or **PED → Open pre-inspection tally**. This feature is limited to
PED; Offdock, Container and Scrap keep their existing capture workflows.

1. Sign in online with **Remember me** enabled to reopen the app offline.
2. Tap **Prepare offline capture** to verify the account and download the full
   administrator-created voyage directory and vehicle make suggestions.
3. Select an open voyage and capture vehicles without internet. **Save & next
   vehicle** commits a record to SQLite before clearing the form. **Save & review
   tally** opens the local queue. All eleven observations support YES, NO and
   Not recorded; key/sensor counts distinguish zero from unanswered.
4. When connected, open **Saved tally → Sync**. Only this inspector's records for
   the configured API are sent. A UUID is assigned once to each saved vehicle.
   Interrupted uploads reuse the original UUID and payload; a server-confirmed
   record becomes Synced and remains available for local review.
5. Refresh voyages before going offline again. Administrators continue creating
   voyages and managing report completion in the web application.

The first preparation requires connectivity. Cached voyages and saved captures
survive app restarts; unsaved form changes are protected by a back confirmation.
Local capture times are shown on-device; server audit timestamps represent when
the backend receives the submission. Saved records can sync automatically when connected; manual sync remains available. If a session
expires, sign in with the same account and prepare again to access its queue.
Unsent records may be edited or removed with confirmation. Once an upload is
attempted its payload is immutable so retries stay idempotent. Conflicts (such as
completed reports or a duplicate chassis) remain on-device with the API's error
for administrator review; the app never overwrites remote records.

Storage: `pre-inspection.db`, separate from the existing PED image queue. Both
voyage caches and vehicle records are scoped by API URL and authenticated user ID.
The API contract works with the Go and Laravel inspection endpoints. No vessel
creation or report completion is performed from Android.

### Build and verify

Use JDK 17+ (Android Studio's bundled JBR is suitable) and the configured Android
SDK. `./gradlew :app:assembleDebug` builds the APK. With an emulator running,
`./gradlew :app:connectedDebugAndroidTest` tests real SQLite persistence, null/zero
semantics, account isolation, pagination, failed refresh, safe replay after a
server commit, completed-report rejection, and the Compose capture/sync flow.
Tests use an in-process fake HTTP API, never the production database.

For a local emulator API build, use
`./gradlew :app:assembleDebug -PPED_API_BASE=http://10.0.2.2:9002/api`.
Only debug builds allow cleartext local HTTP; release keeps the platform's
HTTPS-only default. The normal configured API URL is unchanged.

## Mobile interface

The shared navy-and-gold theme covers sign-in, Capture, Tally and Uploads, with
a matching dark palette. Capture forms group identification and photo evidence,
show required fields, and confirm before clearing unsaved work. Photo previews
are downsampled without changing the saved originals. Draft fields and photos
survive activity recreation; tap **Save capture offline** to persist a capture
beyond the current app session.

The **Uploads** tab shows locally saved operational captures and manual upload
feedback. Pre-inspection keeps its account-scoped **Saved tally** queue. Automatic
upload settings are available in Uploads. Sign-in supports password visibility, keyboard
actions and inline validation; signing out asks for confirmation.

The emulator UI test also covers sign-in validation, password visibility, draft
retention after activity recreation, required photo validation, cancelling a
record-type change, local saving, uploading and sign-out confirmation.
Design rules are in [the mobile design system](design-system/ped-mobile/MASTER.md).

### Chassis photo scanning

In Capture Evidence or pre-inspection vehicle identity, tap **Scan chassis from photo**, photograph
the label, then review or correct the extracted value and tap **Use this number**.
Both screens use the same scanner: it checks VIN/serial labels first, then falls
back to chassis text. It uses bundled on-device ML Kit text
recognition, so it works offline. Cancellation and recognition failures preserve
the current field. Temporary scan photos are deleted after recognition and are
not uploaded as evidence; capture the required evidence photos separately. Saved read-only submissions cannot be scanned.

## Field workflow and account ownership

Capture, Tally, and Uploads remain available throughout the signed-in app. Uploads combines the two queues and their counts, with separate explicit upload buttons. Capture history records successful server responses and local confirmation times; tally history also includes the report reference. Automatic uploads use the same account-scoped queues.

New capture queues, upload errors, receipts, and recoverable drafts are scoped to the verified API origin and account ID. Login verifies `/profile`; offline reopening uses the cached binding for that token. Upload rechecks the account before sending. Signing out preserves saved work for its owner. Drafts are written on a background dispatcher using an atomic file; wait for “Draft saved on this device” before closing the app. Inspection drafts are stored under their own verified owner key.

Older image-capture queues did not record an owner. They remain in the original `ped_queue.db` and are **not automatically assigned or uploaded** by a newly signed-in account. The Uploads screen reports their count. An administrator must verify ownership against existing records before recovery; retain the original database and `queue-images` directory. Do not clear application data or uninstall the original app to resolve this message.

A failed/ambiguous regular capture POST may already have reached the server. Its message asks the user to check the server before retrying; this Android change does not add backend idempotency to legacy capture endpoints. Inspection submissions retain their existing UUID-based safe retries. “Uploaded” means acknowledged by the API, not completed/discharged.

Photo review offers pinch/pan and button-based zoom, reset, and close. Optional photos can be removed with confirmation. Saved inspections use a read-only summary with a separate edit action for unsent vehicles. Record errors expose retry, reauthentication, or administrator-review guidance.

### Isolated device verification

Use `-PPED_APPLICATION_ID=com.ped.mobile.review` when running instrumentation so test installation never replaces the operational app:

```bash
./gradlew -PPED_APPLICATION_ID=com.ped.mobile.review connectedDebugAndroidTest
```

Tests use a local fake API. The connected device must be unlocked. The normal `assembleDebug` command still builds application ID `com.ped.mobile`.

### Offline vehicle makes and models

**Tally → Prepare offline capture / Refresh directory** downloads the shared
vehicle catalogue alongside voyages. The first download uses pages of 250;
subsequent refreshes request only changed records. Each SQLite page and sync
cursor commit together, so interruptions resume safely. Storage is isolated by
API URL and verified account. A changed server catalogue identity triggers a
fresh local reference download; saved captures are never removed.

Typing at least two characters into Vehicle make searches cached make/model
pairs (six suggestions maximum). Selecting a model saves `Make Model` in the
existing inspection identity field. Custom text and General Cargo remain
available. Deactivated models disappear from suggestions after refresh while
previous inspections retain their captured text. The server must include the
vehicle catalogue endpoints; an outdated server gives a refresh error and keeps
previously downloaded reference data.

### Tally capture controls

Vehicle make uses an editable dropdown: tap for makes and General Cargo, or type
to search downloaded make/model entries. Custom entries remain available. The
tally save actions sit after Remarks inside the scrolling form. “Save & next
vehicle” saves locally, clears the entry, and returns to vehicle identity.

The **Use dropdown** switch above Vehicle make selects either the searchable
dropdown or the original text field with suggestions. Only one input is shown.
Switching keeps the entered value; the preference is remembered on this device.


## Roles and permissions

PED uses a shared RBAC contract (`database/data/rbac.json` in Laravel and
`internal/store/data/rbac.json` in Go). Administrators manage custom roles,
multiple role assignments and access history from the Nuxt `/roles` page.
Both APIs enforce the same permissions on every authenticated operation;
unknown authenticated actions fail closed. Existing record ownership rules
still apply to inspection editing and completion.

Existing users without an explicit assignment retain the built-in Standard user
role. Newly created accounts have an explicit empty assignment and need a role
before operational use. Administrator accounts remain protected with full access;
the regular user editor cannot demote or delete them. Built-in role templates
are protected; duplicate one to create an editable role. Deactivating a custom
role removes its grants without erasing assignments. Multiple active roles combine
their permissions. Delegated managers cannot grant or remove permissions they do
not hold, alter administrator accounts, or change their own role assignment.
Changes use revisions to reject stale saves and are recorded transactionally.

Apply Laravel migration `2026_10_05_120000_create_rbac_tables` when deploying.
Go creates compatible additive tables on startup. These two initialization paths
are idempotent and preserve assignments; deploy the APIs before the clients.

Android caches verified permissions with the account and API address for offline
capture. Older offline sessions retain their previous access until verified online.
Revocation is enforced by the server at sync; rejected uploads remain on the device.
Nuxt also retains cached access when offline. An offline device cannot learn about
revocation until it reconnects. The Print/export records permission controls the
provided web print action; anyone allowed to view data can still copy that data.

Scope: the legacy `/search` and `/printsearch` record endpoints now require bearer
authentication and their respective permissions. Existing public image/file URLs
remain unchanged; private media delivery and tenancy are not part of this RBAC change.

## Field workspace navigation

The app opens on **Capture Evidence**. The hamburger drawer contains Capture
Evidence (PED, Offdock, Container and Scrap), **Inspection Tally**, and **Uploads**,
with **Account & Settings** at the bottom. A wide tablet keeps the sidebar visible.
Destinations respect cached permissions; Uploads remains available to review
preserved records even when capture permission is removed.

Navigation preserves unfinished evidence and tally drafts. Save buttons scroll
with the forms. Inspection capture follows voyage selection, vehicle capture and
a readable saved-vehicle review. Account settings contain sign-out, offline
sign-in preference and a server connection check.

Uploads combines both record types in Pending, Failed and Uploaded filters.
**Upload all** sends queued evidence and tally vehicles for the signed-in account;
failed records remain on the device. Uploaded means the server confirmed receipt.
Automatic sync is available alongside these manual controls; see below.

### Offline Cargo ID assistance

Both chassis photo scanners use the same read-only Cargo ID reference bundled in
the Android APK. The reference is independent of PED captures and inspection
tallies: matching a Cargo ID neither creates a record nor blocks another workflow.

The imported chassis example.json contained 391,494 rows. After trimming,
uppercasing and deduplicating, 388,903 identifiers meet the reference policy
(6–25 ASCII letters/digits, with at least one digit). 640 distinct unsuitable
entries are excluded without guessing corrections. Source and asset checksums
and counts are in app/src/main/assets/cargo-reference-v1.json. Source row ID
values are deliberately not used as chassis references.

Recognition checks labelled VIN/chassis text first and otherwise looks for exact
Cargo IDs among bounded OCR candidates. Possible matches use indexed character
confusion groups (0/O/Q, 1/I/L, 8/B, 5/S, 2/Z), with at most two differences and
five displayed suggestions. These are suggestions, not verified identities.
There is no broad nearest-neighbour replacement. Short identifiers get exact
matching only. Multiple exact candidates remain available for review.

The review dialog shows Exact match, Possible match, Not in reference, or
Reference unavailable. Users must explicitly confirm the number; edits and new
chassis numbers remain allowed. If the reference cannot be opened, ordinary
photo reading and manual entry remain available. All lookups run off the UI
thread and need no internet. No server migration is required.

To replace the bundled snapshot, run from ped-android and rebuild the APK:

    python3 scripts/build_cargo_reference.py "/path/to/chassis example.json"

The content checksum selects a fresh installed copy after an app update. This
version ships a reference snapshot; it does not download updates from the API.

### Automatic and manual uploads

Automatic sync is enabled by default for saved evidence and tally records. Open
**Uploads** to disable it or limit it to unmetered Wi-Fi. The default also allows
mobile data. **Upload all** and per-evidence **Upload / Retry upload** remain
available and can use the current connection even when automatic sync is off or
waiting for Wi-Fi. Drafts are never uploaded until explicitly saved.

The app checks the signed-in account against the configured API before uploading,
shows an in-app status banner, and uses one Android notification for progress and
completion when notification permission is granted. Declining notifications does
not disable sync. Users may continue capturing during uploads.

WorkManager schedules network-constrained background work, with exponential retry
starting at 30 seconds and a 15-minute recovery job. Android decides when work can
run while the app is backgrounded; force-stop and battery restrictions can delay
it. Keep **Remember me** enabled for uploads after process restart. Without it,
automatic uploads use only the current in-memory session. Signing out cancels
automatic work, and every run checks the account owner and current permissions.

Tally retries preserve their submission UUID; completed/invalid/forbidden records
wait for review. Evidence APIs currently do not support idempotent replay. Before
an evidence POST, the app persists an attempt marker. If confirmation is lost,
that record stays on-device and is excluded from further automatic attempts.
Check the server first, then use manual retry when appropriate. This also prevents
process death during an evidence request from silently creating duplicate review
cases. Confirmed records require a record ID or a duplicate-review reference before
local removal. Neither backend nor its schema needs changing for this feature.
