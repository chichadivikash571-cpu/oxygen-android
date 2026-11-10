# Scheduled AI Calls — Android implementation notes

## Current scaffold

This branch adds the Android presentation primitives for a scheduled OXYGEN assistant call:

- A dedicated high-importance notification channel.
- An incoming-call notification with a full-screen intent and a Decline action.
- A lock-screen-capable incoming-call activity with Accept and Decline controls.
- A decline receiver that records a local declined status.
- Manifest declarations for the activity, receiver, and full-screen intent permission.

## Deliberately not connected yet

This is a UI foundation, not an end-to-end calling feature. It does not yet:

- Fetch scheduled call records from Supabase.
- Trigger calls from the existing reminder sync worker.
- Persist accepted/missed/completed status to the backend.
- Start an authenticated OXYGEN voice session after Accept.
- Guarantee full-screen presentation; Android version, user settings, and full-screen-intent eligibility can restrict it.

## Next integration steps

1. Extend the authenticated reminder sync contract to return scheduled-call tasks separately from reminders.
2. Reconcile call tasks locally and schedule them with AlarmManager, with duplicate-occurrence protection.
3. On trigger, verify network availability and show the incoming call notification.
4. On Accept, open OXYGEN with a validated call ID and task context, then start the existing supported voice/chat flow only after explicit user acceptance.
5. Save accepted, declined, missed, and completed states through an authenticated backend endpoint.
6. Test on Android 10 and Android 12 devices, including locked screen, app process death, reboot, notification denial, and full-screen-intent restrictions.

## Privacy and safety

- No SIM-call, contacts, or call-log permissions are needed.
- Microphone capture must not begin before Accept.
- Decline must stop the alert and must not auto-redial.
- Backend secrets must never be embedded in the APK.
