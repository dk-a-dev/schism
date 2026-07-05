# Android Groups Client Implementation Plan (SP2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app that is a full Splitwise-style client against the schism-backend REST API — groups, participants, expenses (all split modes), balances/reimbursements, activity, dashboards, join-by-link/ID/QR, lightweight device profile, read-cache offline viewing, Material 3.

**Architecture:** MVVM + unidirectional data flow. Compose UI → ViewModel (StateFlow) → Repository → (Retrofit API + Room cache). Reads fill Room and the UI observes Room (offline-viewable); writes hit the API then refetch. Hilt for DI. DataStore holds the device profile + backend URL.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit + OkHttp + kotlinx.serialization, Room, Navigation Compose, DataStore Preferences, Coil, ZXing (QR), Turbine + MockWebServer + JUnit for tests.

## Global Constraints

- Package: `ai.schism.split`. Min SDK 26, target/compile SDK 35. JDK 17.
- **Money** is `Long` minor units end-to-end; never float. Format for display only, using the group's `currency`.
- API contract is authoritative: `schism-backend/docs/api-contract.md`. Base URL is user-configurable (default emulator `http://10.0.2.2:8080`). No auth; group reached by id.
- Split-mode share encoding matches the backend: `EVENLY` (equal), `BY_SHARES` (weights), `BY_PERCENTAGE` (sum 10000), `BY_AMOUNT` (sum == amount).
- Every screen supports light + dark. Slice lists render empty/loading/error states.
- One `:app` module, organized by feature package (`groups/`, `expense/`, `dashboard/`, `settings/`, `core/`).

---

## File Structure

```
app/src/main/java/ai/schism/split/
  SchismApp.kt                      # @HiltAndroidApp
  MainActivity.kt                   # setContent { SchismTheme { AppNav() } }
  core/
    theme/{Color,Theme,Type}.kt     # fixed Material 3 brand scheme (light/dark)
    money/Money.kt                  # formatMinor(amount, currency)
    net/{ApiService,ApiClient,Dto}.kt
    net/BackendUrlProvider.kt       # reads DataStore, feeds OkHttp base url
    db/{SchismDb,GroupDao,ExpenseDao,entities,Mappers}.kt
    settings/SettingsRepository.kt  # DataStore: backendUrl, profileName
    di/{NetworkModule,DbModule,AppModule}.kt
    ui/{UiState.kt,components/*}     # Scaffold, error/empty/loading, CurrencyText
    nav/AppNav.kt, nav/Routes.kt
  groups/
    data/GroupRepository.kt
    list/{GroupsListViewModel,GroupsListScreen}.kt
    create/{CreateGroupViewModel,CreateGroupScreen}.kt
    join/{JoinGroupViewModel,JoinGroupScreen,QrScan,ShareSheet}.kt
    detail/{GroupDetailViewModel,GroupDetailScreen, tabs/*}.kt
  expense/
    data/ExpenseRepository.kt
    edit/{ExpenseEditViewModel,ExpenseEditScreen}.kt
  dashboard/
    group/{GroupDashboardViewModel,GroupDashboardScreen}.kt
    personal/{PersonalDashboardViewModel,PersonalDashboardScreen}.kt
  settings/{SettingsViewModel,SettingsScreen}.kt
app/src/test/...                    # ViewModel + repository unit tests (MockWebServer, Turbine)
```

Each task ends with a green build (`./gradlew :app:assembleDebug`) and its unit tests
(`./gradlew :app:testDebugUnitTest`).

---

## Task 1: Project scaffold, Gradle, theme, app skeleton

**Files:** Gradle files (`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`), `AndroidManifest.xml`, `SchismApp.kt`, `MainActivity.kt`, `core/theme/*`, `core/nav/{AppNav,Routes}.kt`, `core/ui/UiState.kt`.

**Interfaces:**
- Produces: `SchismTheme(content)`, `sealed interface UiState<out T> { Loading; Empty; data class Error(message); data class Data(value) }`, `AppNav(navController)`, `object Routes { const val GROUPS="groups"; ... }`.

- [ ] **Step 1: Version catalog + Gradle** — add Compose BOM, Hilt, Retrofit, OkHttp, kotlinx-serialization, Room, Navigation, DataStore, Coil, ZXing, and test libs (junit, mockwebserver, turbine, coroutines-test) to `gradle/libs.versions.toml`; wire plugins (kotlin, ksp, hilt, serialization) in the two `build.gradle.kts` files. Enable `buildFeatures { compose = true }`.

- [ ] **Step 2: Manifest + Application** — `AndroidManifest.xml` with `INTERNET` permission, `.SchismApp` as `android:name`; `SchismApp` annotated `@HiltAndroidApp`; `MainActivity` `@AndroidEntryPoint` calling `setContent { SchismTheme { AppNav(rememberNavController()) } }`.

- [ ] **Step 3: Theme** — fixed brand `lightColorScheme`/`darkColorScheme` in `core/theme/Color.kt`+`Theme.kt` (no dynamic color for v1), M3 typography in `Type.kt`.

- [ ] **Step 4: UiState + nav shell** — `UiState` sealed interface; `Routes`; `AppNav` `NavHost` with a bottom `NavigationBar` (Groups, Dashboard, Settings) and placeholder composables.

- [ ] **Step 5: Build** — Run `./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL, app launches to an empty Groups screen.

- [ ] **Step 6: Commit** — `feat(android): project scaffold, Material 3 theme, nav shell`.

---

## Task 2: Networking — DTOs + Retrofit ApiService (MockWebServer test)

**Files:** `core/net/Dto.kt`, `core/net/ApiService.kt`, `core/net/ApiClient.kt`, `core/net/BackendUrlProvider.kt`; test `core/net/ApiServiceTest.kt`.

**Interfaces:**
- Produces `@Serializable` DTOs mirroring `docs/api-contract.md`: `GroupDto`, `ParticipantDto`, `CategoryDto`, `ExpenseDto`, `PaidForDto`, `BalancesResponseDto(balances: Map<String,BalanceDto>, reimbursements: List<ReimbursementDto>)`, `GroupDashboardDto`, `PersonalDashboardDto`, `CreateGroupRequest`, `ExpenseRequest`, `CreateGroupResponse(groupId)`.
- Produces `interface ApiService` with suspend functions: `listGroups(ids)`, `createGroup(body)`, `getGroup(id)`, `updateGroup(id, body)`, `listCategories()`, `listExpenses(groupId)`, `getExpense(groupId, id)`, `createExpense(groupId, body, idemKey: String?)`, `updateExpense(...)`, `deleteExpense(...)`, `getBalances(groupId)`, `listActivities(groupId)`, `groupDashboard(groupId, participant?)`, `personalDashboard(participant, groupIds)`.

- [ ] **Step 1: Write failing test** — `ApiServiceTest` uses `MockWebServer`: enqueue a categories JSON body, build `ApiService` against `server.url("/")`, assert `listCategories()` returns the parsed list. (Test fails: no ApiService.)

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ApiServiceTest*'` → FAIL.

- [ ] **Step 3: Implement DTOs** (kotlinx.serialization, field names matching the contract; `@SerialName` where Kotlin naming differs).

- [ ] **Step 4: Implement ApiService** (Retrofit interface; `@Header("Idempotency-Key")` optional param on `createExpense`; `@Query` for `ids`, `participant`, `groupIds`).

- [ ] **Step 5: Implement ApiClient** — builds OkHttp (+ `HttpLoggingInterceptor` at BODY only in debug) and Retrofit with the kotlinx-serialization converter and a base URL from `BackendUrlProvider`.

- [ ] **Step 6: Run test** → PASS. **Commit** `feat(android): API DTOs + Retrofit service`.

---

## Task 3: Room cache — entities, DAOs, mappers

**Files:** `core/db/entities.kt`, `core/db/GroupDao.kt`, `core/db/ExpenseDao.kt`, `core/db/SchismDb.kt`, `core/db/Mappers.kt`; test `core/db/MappersTest.kt`, instrumented `GroupDaoTest` (androidTest, in-memory Room).

**Interfaces:**
- Produces entities `GroupEntity`, `ParticipantEntity`, `ExpenseEntity`, `PaidForEntity`, `CategoryEntity` (+ `isFavorite`, `activeParticipantId` on group). DAOs expose `Flow<...>` reads and upsert/delete writes. Mappers `GroupDto.toEntity()/toDomain()`, etc.
- Produces domain models in `groups/data` / `expense/data` (`Group`, `Participant`, `Expense`, `PaidFor`) so UI never touches DTOs/entities directly.

- [ ] **Step 1: Failing MappersTest** — assert `GroupDto.toEntity().toDomain()` round-trips id/name/currency/participants.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement entities + `SchismDb` (Room) + DAOs (Flow reads, upsert writes) + Mappers.**
- [ ] **Step 4: Run MappersTest → PASS**; add in-memory `GroupDaoTest` (upsert then observe Flow emits the group).
- [ ] **Step 5: Commit** `feat(android): Room cache (entities, DAOs, mappers)`.

---

## Task 4: DI + Settings (DataStore)

**Files:** `core/settings/SettingsRepository.kt`, `core/di/{NetworkModule,DbModule,AppModule}.kt`; test `SettingsRepositoryTest`.

**Interfaces:**
- Produces `SettingsRepository` with `val backendUrl: Flow<String>`, `val profileName: Flow<String>`, `suspend fun setBackendUrl(String)`, `suspend fun setProfileName(String)` (DataStore Preferences; default backend `http://10.0.2.2:8080`).
- Produces Hilt modules binding `ApiService`, `SchismDb`+DAOs, `SettingsRepository`, and a `BackendUrlProvider` backed by `SettingsRepository`.

- [ ] **Step 1: Failing SettingsRepositoryTest** — write then read `profileName` via a test DataStore returns the value.
- [ ] **Step 2: Run → FAIL. Step 3: Implement SettingsRepository + Hilt modules. Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(android): Hilt DI + DataStore settings`.

---

## Task 5: GroupRepository (read-cache, online writes)

**Files:** `groups/data/GroupRepository.kt`; test `GroupRepositoryTest` (MockWebServer + in-memory Room).

**Interfaces:**
- Produces `GroupRepository`:
  - `fun observeGroups(): Flow<List<Group>>` (from Room)
  - `suspend fun refreshGroups(ids: List<String>)` (API → upsert Room)
  - `fun observeGroup(id): Flow<Group?>`
  - `suspend fun refreshGroup(id)`; `suspend fun createGroup(req): Result<String>`; `suspend fun updateGroup(id, req): Result<Unit>`
  - Reads never throw (surface cached data); writes return `Result` and refresh the cache on success.

- [ ] **Step 1: Failing test** — enqueue group JSON; `refreshGroup(id)` then `observeGroup(id).first()` emits the cached group; `createGroup` posts and returns the `groupId`.
- [ ] **Step 2: Run → FAIL. Step 3: Implement repository (map errors to `Result.failure`, wrap IO in `Dispatchers.IO`). Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(android): GroupRepository with read-cache + online writes`.

---

## Task 6: Groups list screen + ViewModel

**Files:** `groups/list/GroupsListViewModel.kt`, `groups/list/GroupsListScreen.kt`; test `GroupsListViewModelTest` (Turbine).

**Interfaces:**
- Produces `GroupsListViewModel(repo, settings)` exposing `val state: StateFlow<UiState<List<GroupSummary>>>`, `fun refresh()`; favorites pinned first.
- `GroupSummary(id, name, currency, memberCount)`.

- [ ] **Step 1: Failing VM test** — repo emits two groups → `state` becomes `Data` with 2 items; repo error on refresh → prior `Data` retained, error surfaced via a one-shot event.
- [ ] **Step 2: Run → FAIL. Step 3: Implement VM (observe repo, `refresh()` calls `refreshGroups(savedIds)`), and the Compose screen (list, FAB→create, Join action, pull-to-refresh, empty/error states). Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(android): groups list screen`.

---

## Task 7: Create group screen + ViewModel

**Files:** `groups/create/CreateGroupViewModel.kt`, `groups/create/CreateGroupScreen.kt`; test `CreateGroupViewModelTest`.

**Interfaces:** `CreateGroupViewModel` with form state (name, currency, participants list add/remove), `fun submit(onSuccess: (groupId) -> Unit)`; client-side validation mirrors backend (name ≥ 2, ≥ 1 participant, no duplicate names).

- [ ] **Step 1: Failing test** — invalid (1-char name) → `submit` sets a field error, no API call; valid → repo.createGroup called, `onSuccess(groupId)` invoked, id saved to settings' known-groups.
- [ ] **Step 2–4: Run → FAIL → implement VM + screen → PASS.**
- [ ] **Step 5: Commit** `feat(android): create group flow`.

---

## Task 8: Join group + share (link/ID + QR)

**Files:** `groups/join/{JoinGroupViewModel,JoinGroupScreen,QrScan,ShareSheet}.kt`; test `JoinGroupViewModelTest`.

**Interfaces:** `JoinGroupViewModel.join(input): parse a share link or raw id → refreshGroup → on success save id and navigate`. `ShareSheet` renders a share link (`schism://group/{id}`) + a QR bitmap (ZXing). `QrScan` uses CameraX + ML Kit/ZXing to read a code.

- [ ] **Step 1: Failing test** — `parseGroupId("schism://group/abc")` == "abc"; `parseGroupId("abc")` == "abc"; joining an unknown id yields an error state (repo returns null).
- [ ] **Step 2–4: Run → FAIL → implement parse + VM + screens (QR behind CAMERA permission). Step 5: Commit** `feat(android): join group by link/ID/QR + share sheet`.

---

## Task 9: ExpenseRepository + balances/activity

**Files:** `expense/data/ExpenseRepository.kt`; test `ExpenseRepositoryTest`.

**Interfaces:** `observeExpenses(groupId): Flow<List<Expense>>`, `refreshExpenses(groupId)`, `createExpense/updateExpense/deleteExpense(...) : Result<...>` (accepts optional idempotency key), `getBalances(groupId): Result<Balances>`, `getActivities(groupId): Result<List<Activity>>`.

- [ ] **Step 1: Failing test** — create posts and refreshes cache; `getBalances` parses `{balances,reimbursements}`. **Steps 2–4: FAIL → implement → PASS. Step 5: Commit** `feat(android): ExpenseRepository + balances/activity`.

---

## Task 10: Group detail (Expenses / Balances / Activity tabs)

**Files:** `groups/detail/{GroupDetailViewModel,GroupDetailScreen}.kt`, `groups/detail/tabs/{ExpensesTab,BalancesTab,ActivityTab}.kt`; test `GroupDetailViewModelTest`.

**Interfaces:** `GroupDetailViewModel(groupId)` exposing `expenses`, `balances`, `activities` states + `refresh()`; resolves "you" from the device profile (auto-select matching participant, persisted per group).

- [ ] **Step 1: Failing test** — states populate from repos; "you" resolves to the participant whose name matches the profile. **Steps 2–4: FAIL → implement VM + tabbed screen (balances show who-owes-whom + suggested reimbursements; participant names resolved). Step 5: Commit** `feat(android): group detail tabs`.

---

## Task 11: Add/Edit expense (all split modes)

**Files:** `expense/edit/{ExpenseEditViewModel,ExpenseEditScreen}.kt`; test `ExpenseEditViewModelTest`.

**Interfaces:** `ExpenseEditViewModel` with form (title, amount, category, paidBy, paidFor selection, splitMode, reimbursement, notes); a pure `buildRequest(): Result<ExpenseRequest>` that encodes shares per mode and validates locally (mirror backend: amount≠0/≤1e9, ≥1 paidFor, BY_AMOUNT sum==amount, BY_PERCENTAGE sum==10000).

- [ ] **Step 1: Failing test** — table over the four modes: correct share encoding + validation errors for bad sums. **Steps 2–4: FAIL → implement VM (+ per-mode inputs) + screen. Step 5: Commit** `feat(android): add/edit expense with all split modes`.

---

## Task 12: Group dashboard screen

**Files:** `dashboard/group/{GroupDashboardViewModel,GroupDashboardScreen}.kt`; test `GroupDashboardViewModelTest`.

**Interfaces:** VM calls `api.groupDashboard(groupId, you)` → state with totals, byCategory, byMonth, topExpenses, byParticipant, personal. Screen renders summary cards + a simple category bar list + monthly trend + "your share/net".

- [ ] **Step 1: Failing test** — parses `GroupDashboardDto`, maps to UI model (formatted money). **Steps 2–4: FAIL → implement → PASS. Step 5: Commit** `feat(android): group dashboard/insights`.

---

## Task 13: Personal dashboard (cross-group) + Settings

**Files:** `dashboard/personal/{PersonalDashboardViewModel,PersonalDashboardScreen}.kt`, `settings/{SettingsViewModel,SettingsScreen}.kt`; tests `PersonalDashboardViewModelTest`, `SettingsViewModelTest`.

**Interfaces:** Personal VM calls `api.personalDashboard(profileName, savedGroupIds)` → per-currency totals + per-group slices (never sums across currencies). Settings edits `profileName` + `backendUrl` (+ theme toggle placeholder).

- [ ] **Step 1: Failing tests** — personal dashboard groups totals by currency; settings persists profile/url. **Steps 2–4: FAIL → implement → PASS. Step 5: Commit** `feat(android): personal dashboard + settings`.

---

## Task 14: Wiring, end-to-end manual smoke, README

**Files:** finalize `nav/AppNav.kt` (all routes wired), `app/README` notes.

- [ ] **Step 1:** Point the app at a running `schism-backend` (`make up`; emulator uses `http://10.0.2.2:8080`).
- [ ] **Step 2:** Manual smoke: create group → add expenses in each split mode → view balances → open dashboards → create a second group → personal dashboard shows both (bucketed by currency) → kill network, confirm cached groups/expenses still render.
- [ ] **Step 3:** `./gradlew testDebugUnitTest` all green; `assembleDebug` succeeds.
- [ ] **Step 4: Commit** `docs(android): wiring + run notes`.

---

## Self-Review Notes (checked against spec §5–§10 + api-contract)

- Screens (list/create/join/detail-tabs/add-expense/settings) → Tasks 6–13. ✓
- Identity = device profile, per-group active participant → Tasks 4, 10. ✓
- Read-cache + online writes → Tasks 5, 9 (repos observe Room, writes refetch). ✓
- All split modes + local validation mirroring backend → Task 11. ✓
- Join by link/ID/QR + share → Task 8. ✓
- Dashboards (group + cross-group personal, currency-safe) → Tasks 12–13, backed by SP1 endpoints. ✓
- Material 3, light/dark, empty/loading/error states → Task 1 + per-screen. ✓
- Money as Long minor units, formatted on display → Global Constraints, `core/money`. ✓

Deferred (later sub-projects): SMS ingestion/bridge (SP3), on-device AI (SP4), full offline write sync.
```
