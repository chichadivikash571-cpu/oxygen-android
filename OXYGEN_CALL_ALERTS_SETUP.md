# OXYGEN incoming call-style alerts

OXYGEN alerts are internet push notifications. They do not use a phone number, SIM card, SMS, or the carrier calling network. The receiving device needs Wi-Fi or mobile data and Google Play services for Firebase Cloud Messaging (FCM).

## What the Android feature does

- Shows an OXYGEN-branded incoming-alert screen over the lock screen when Android grants full-screen intent access.
- Loops the Android default ringtone using the ringtone audio stream, and vibrates until the alert is accepted or declined.
- Provides separate swipe-right-to-accept and swipe-left-to-decline controls.
- Accept opens the Scheduler page for an approval request, or the assistant for a general reminder. Accepting the alert does not itself approve an automated action.
- Declining dismisses the alert only; it does not reject the underlying scheduler approval.

Media volume can be muted while the ringtone volume remains audible. Android Silent mode, Do Not Disturb, disabled notification channels, revoked notification permission, battery/network restrictions, or disabled full-screen access can still silence or downgrade the alert. OXYGEN does not override those user or OS controls.

## Firebase setup required

The source build is intentionally able to compile without Firebase project credentials. Real push delivery is disabled until a Firebase project is configured.

1. In Firebase Console, create or select a project and add an Android app with package name `ai.oxygen.app`.
2. Download that app's `google-services.json` and keep it private to your build environment. Place it at `app/google-services.json` for a local build, or store the exact JSON text in the Android GitHub repository's Actions secret named `FIREBASE_GOOGLE_SERVICES_JSON`.
3. In Google Cloud for the same Firebase project, enable the Firebase Cloud Messaging API.
4. Create a dedicated service account for server-side FCM sends. Grant it the Firebase Cloud Messaging API permission/role required to send messages, then create a JSON key. Do not put this JSON key in GitHub, the Android app, or any client-side file.
5. Add the full service-account JSON as a Supabase Edge Function secret named `FIREBASE_SERVICE_ACCOUNT_JSON`. It is only read by the Scheduler Worker server-side.

## Supabase backend deployment required

The feature branch includes:
- Migration: `20261109110000_oxygen_device_push_registry.sql`
- Edge Function: `device-push`
- Updated `scheduler-worker`, which sends push alerts after reminder delivery and when an approval request is created.

Apply the migration and deploy the Edge Function and updated Worker only after reviewing the branch. Keep the Scheduler Worker secret already in use. Add `FIREBASE_SERVICE_ACCOUNT_JSON` as a separate Edge Function secret.

## Device setup

1. Install the newly built APK and sign in to the same OXYGEN account.
2. Allow OXYGEN notifications.
3. In Android App info, keep the **OXYGEN incoming alerts** notification channel enabled and choose a ringtone.
4. On Android 14+, allow full-screen notifications for OXYGEN if the device offers that setting.
5. Set ringtone volume high. Media volume may remain muted; Silent mode and Do Not Disturb can still suppress sound.

## Important verification

A successful Gradle build does not prove FCM delivery. End-to-end verification requires a Firebase-configured APK, a registered device token, the deployed Supabase registry/API/Worker, and a real test reminder/approval. Never send high-priority FCM messages unless the app promptly displays a user-visible alert.
