# Complete Guided Walkthrough Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Teach Schism through a truthful value tour, a two-minute interactive local demo, contextual discovery, and replayable accessible guidance.

**Architecture:** A pure reducer owns versioned walkthrough progress while an isolated in-memory demo repository supplies deterministic group/receipt/balance state. A Compose target registry and overlay render coach marks without coupling feature screens to navigation or persistence; contextual hints use the same engine and per-account DataStore progress.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation, Hilt, DataStore, Compose UI/instrumentation tests, AccessibilityChecks.

All Android verification commands target the `standalone` distribution so walkthrough tests never
depend on Play Billing, ads, or consent SDK initialization.

## Global Constraints

- Demo state never enters Room, backend APIs, analytics, billing, ads, permissions, OCR downloads, or real share intents.
- First-run value tour and interactive demo are always skippable; contextual tips never stack.
- Demo journey: sample group, sample receipt, item assignment/totals, balances/settlement, Live Split preview.
- Settings provides Replay app tour and Reset feature tips.
- TalkBack, font scaling, RTL, rotation, dark theme, reduced motion, process death, and account switching are supported.
- Onboarding must use the backend's eight-character password rule and must not claim phone auto-linking.

---

### Task 1: Correct and simplify the first-run value tour

**Files:**
- Modify: `schism-android/app/src/main/java/ai/schism/split/onboarding/OnboardingScreen.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/onboarding/Walkthrough.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/onboarding/Illustrations.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/onboarding/OnboardingCopyTest.kt`
- Modify: `schism-android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: four truthful `WalkPage` definitions and auth validation matching backend contracts.
- Consumes: existing onboarding/auth flow.

- [ ] **Step 1: Write failing copy and validation tests**

Assert exactly four pages cover groups/balances, receipt+SMS capture, Live Split, and on-device privacy; Skip remains present; password requires eight characters; phone is optional but never described as auto-linking; receipt copy avoids universal accuracy and “AI split” claims before the model is available.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*OnboardingCopyTest'`.

Expected: FAIL on six-character password and automatic phone-link copy.

- [ ] **Step 3: Implement concise truthful copy and pages**

Move page definitions into a testable immutable list, reuse the current geometric illustrations, provide content descriptions, and keep authentication independent from tour progress.

- [ ] **Step 4: Run onboarding tests and assemble**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*Onboarding*' :app:assembleStandaloneDebug`.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src
git commit -m "fix(onboarding): make the first-run tour truthful"
```

### Task 2: Versioned walkthrough reducer and isolated demo data

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/WalkthroughState.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/WalkthroughReducer.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/WalkthroughRepository.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/DemoRepository.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/walkthrough/WalkthroughReducerTest.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/walkthrough/DemoRepositoryTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/settings/SettingsRepository.kt`

**Interfaces:**
- Produces: `WalkthroughState`, `WalkthroughAction`, pure `reduce(state,action)`, schema version `1`, and in-memory `DemoRepository` with deterministic Weekend dinner data.
- Consumes: DataStore only for per-account progress, never financial data.

- [ ] **Step 1: Write reducer/demo isolation tests**

Cover offer/accept/skip, ordered five steps, back/continue/real-action completion, process-restored step, completion, replay, reset tips, sign-out transient clear, schema upgrade offer, deterministic receipt/totals/balances, and zero calls to injected production repository/network/permission fakes.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests 'ai.schism.split.walkthrough.*'`.

Expected: FAIL because the reducer and demo repository do not exist.

- [ ] **Step 3: Implement pure state and demo scope**

Define steps `GROUP`, `RECEIPT`, `ASSIGN`, `BALANCES`, `LIVE_SPLIT`; store only version/status/current step/hint IDs keyed by user ID; expose demo models through an interface unavailable to production repositories.

- [ ] **Step 4: Run tests and mutation edge cases**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests 'ai.schism.split.walkthrough.*'`.

Expected: PASS; no demo entity can be inserted or serialized as an API request.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src/main/java/ai/schism/split/walkthrough schism-android/app/src/test
git commit -m "feat(walkthrough): add isolated demo state machine"
```

### Task 3: Accessible target registry and coach-mark renderer

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/WalkthroughTarget.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/WalkthroughOverlay.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/walkthrough/TargetPlacementTest.kt`
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/walkthrough/WalkthroughAccessibilityTest.kt`

**Interfaces:**
- Produces: `Modifier.walkthroughTarget(id)`, registry bounds flow, overlay with Back/Skip/Continue and target semantics.
- Consumes: Task 2 state only.

- [ ] **Step 1: Write failing placement/semantics tests**

Test missing/offscreen target fallback, safe insets, RTL, landscape, 200% font, target removal during navigation, focus order, step count announcement, dismiss focus return, reduced motion, and minimum 48 dp actions.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*TargetPlacementTest' :app:connectedStandaloneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.schism.split.walkthrough.WalkthroughAccessibilityTest`.

Expected: FAIL because target/overlay APIs do not exist.

- [ ] **Step 3: Implement registry and resilient overlay**

Register `LayoutCoordinates.boundsInWindow`, select above/below/center fallback based on measured card, use a scrim with a non-clickable highlight cutout, request TalkBack focus on instruction, and avoid infinite animation when reduced motion is active.

- [ ] **Step 4: Run unit/device matrix**

Run the focused unit test plus API-36 Pixel 9 Pro XL instrumentation in portrait/landscape, light/dark, RTL, and font scales 1.0/2.0.

Expected: PASS without clipped text or inaccessible underlying controls.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src
git commit -m "feat(walkthrough): add accessible Compose coach marks"
```

### Task 4: Wire the two-minute interactive demo

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/DemoTourScreen.kt`
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/WalkthroughViewModel.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/walkthrough/WalkthroughViewModelTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/nav/AppNav.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/core/nav/Routes.kt`
- Modify: group/itemized/balance/claim composables only to register target modifiers.

**Interfaces:**
- Produces: post-auth offer, demo route, five real-action-driven steps, cleanup/completion return.
- Consumes: Tasks 2–3; no production repositories.

- [ ] **Step 1: Write failing ViewModel/navigation tests**

Assert `Take the 2-minute tour` and `Explore myself`, every step advances only on its intended action, deterministic sample receipt needs no OCR/camera, Live Split is preview-only, Skip works everywhere, completion clears demo, back is bounded, and process death restores route/step.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*WalkthroughViewModelTest'`.

Expected: FAIL because demo navigation does not exist.

- [ ] **Step 3: Implement scoped demo UI using production components**

Render production group/item/balance components with `DemoRepository` models, intercept share/permission/network actions, mark targets, and return to the user's genuine empty/current app state at completion.

- [ ] **Step 4: Run end-to-end demo tests**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest :app:connectedStandaloneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.schism.split.walkthrough.WalkthroughFlowTest`.

Expected: PASS with zero backend requests and zero Room rows.

- [ ] **Step 5: Commit**

```bash
git add schism-android/app/src
git commit -m "feat(walkthrough): add the interactive Schism demo"
```

### Task 5: Contextual hints, replay controls, and release evidence

**Files:**
- Create: `schism-android/app/src/main/java/ai/schism/split/walkthrough/FeatureHints.kt`
- Create: `schism-android/app/src/test/java/ai/schism/split/walkthrough/FeatureHintsTest.kt`
- Create: `schism-android/app/src/androidTest/java/ai/schism/split/walkthrough/WalkthroughFlowTest.kt`
- Modify: `schism-android/app/src/main/java/ai/schism/split/settings/SettingsScreen.kt`
- Modify: SMS inbox, OCR preparation, participant invite, UPI settle-up, and Live Split host entry composables only to register/trigger their hint IDs.
- Create: `docs/release/v1.3/walkthrough.md`

**Interfaces:**
- Produces: one-time versioned hints and Settings replay/reset controls.
- Consumes: Task 2 repository and Task 3 overlay.

- [ ] **Step 1: Write failing hint priority/replay tests**

Assert one visible hint, relevance triggers, dismiss-once, the SMS hint only points to a default-off
enable card and never launches permission itself, the prominent disclosure precedes any SMS system
dialog after an explicit Enable tap, OCR consent precedes download, no hint during demo/ad/paywall/system
dialog, reset/replay behavior, per-account isolation, and schema-version upgrade offer.

- [ ] **Step 2: Run and confirm RED**

Run: `cd schism-android && ./gradlew :app:testStandaloneDebugUnitTest --tests '*FeatureHintsTest'`.

Expected: FAIL because contextual hints do not exist.

- [ ] **Step 3: Implement hint coordinator and Settings controls**

Use priority `SMS_OPT_IN`, `OCR_DOWNLOAD`, `PARTICIPANT_INVITE`, `UPI_SETTLE`, `LIVE_SPLIT_HOST`;
enqueue only after target layout; persist dismissal; expose Replay app tour and Reset feature tips with
confirmation. `SMS_OPT_IN` explains the optional feature and may highlight its enable card, but must
never change the opt-in bit or request permission.

- [ ] **Step 4: Verify the full walkthrough on emulator**

Run clean-install, skip, complete, process-death, rotation, TalkBack, large-font, RTL, dark-mode, replay/reset, and permission-denial paths on the Pixel 9 Pro XL. Record build SHA, emulator/API, screenshots, zero-network assertion, and test commands in `walkthrough.md`.

- [ ] **Step 5: Commit**

```bash
git add schism-android docs/release/v1.3/walkthrough.md
git commit -m "test(walkthrough): verify guided discovery end to end"
```
