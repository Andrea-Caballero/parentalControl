# Spec: firebase-messaging-service-receiver

## Purpose

Covers the contract for the real `FirebaseMessagingService` subclass that delivers FCM messages on the parent device, plus its backward-compatibility surface with the existing static callers in `FcmHelper.kt`. Closes the blocker at `FcmPushService.kt:18` (the class is currently a plain class with `companion object`, not a `FirebaseMessagingService` subclass, so the manifest intent filter at `AndroidManifest.xml:122-128` advertises FCM but no receiver fires).

## Requirements

### Requirement: FcmPushService is a real FirebaseMessagingService subclass

`FcmPushService` SHALL extend `com.google.firebase.messaging.FirebaseMessagingService`. The class SHALL be reflectively instantiable (per WorkManager's HiltWorker contract) and SHALL override `onMessageReceived(remoteMessage)` and `onNewToken(token)`.

#### Scenario: Class hierarchy is correct

- **GIVEN** the production APK is built,
- **WHEN** reflection resolves `FcmPushService`,
- **THEN** `Class.forName("com.tudominio.parentalcontrol.push.FcmPushService").superclass` SHALL equal `FirebaseMessagingService`,
- **AND** `Class.forName(...).newInstance()` SHALL succeed (JVM unit test).

#### Scenario: onMessageReceived fires on data messages

- **GIVEN** a data-only FCM with payload `{ type: "grant.approved", request_id }` arrives at the parent device,
- **WHEN** `onMessageReceived(remoteMessage)` is invoked,
- **THEN** the `data` payload SHALL be parsed,
- **AND** `FcmWorkHelper.enqueueHighPrioritySync(context)` SHALL be enqueued within 1 second of receipt,
- **AND** for `type = "child.paired"`, a local notification SHALL be surfaced (per `pairing-flow` delta).

#### Scenario: onNewToken registers the token via register-token edge function

- **GIVEN** the FCM SDK rotates the device token,
- **WHEN** `onNewToken(token)` is invoked,
- **THEN** `FcmPushService.processNewToken(applicationContext, token)` SHALL be called,
- **AND** the `register-token` edge function SHALL be invoked with the new token + the parent's `parent_id`,
- **AND** a row SHALL be upserted into `device_push_tokens` (or `parent_push_tokens` per Clarification §5).

### Requirement: Backward compatibility with existing static callers

The existing `FcmHelper.kt:22,36,56,60` static callers SHALL continue to compile and pass their existing JUnit tests. A `FcmPushService.getInstance(context)` accessor SHALL provide the single instance, and all static entry points SHALL delegate to instance methods.

#### Scenario: Static processMessage entry point still works for JVM tests

- **WHEN** a JUnit test calls `FcmPushService.processMessage(context, mockMessage)`,
- **THEN** the call SHALL delegate to an instance obtained via `getInstance(context)`,
- **AND** the existing test SHALL stay green without modification.

#### Scenario: Static processNewToken entry point still works for JVM tests

- **WHEN** a JUnit test calls `FcmPushService.processNewToken(context, "token-x")`,
- **THEN** the call SHALL delegate to the instance method,
- **AND** the existing test SHALL stay green without modification.

#### Scenario: Manifest registers the FCM service

- **GIVEN** the production `AndroidManifest.xml`,
- **WHEN** the manifest is merged,
- **THEN** the `<service>` element at lines 122-128 SHALL reference `FcmPushService` as its `android:name`,
- **AND** the `<intent-filter>` for `com.google.firebase.MESSAGING_EVENT` SHALL be present,
- **AND** no duplicate `<service>` registration is added.