# ConnectX Android Development — Living Document

Status: **LIVING DOCUMENT** — updated as each phase/sub-phase progresses. This is the
authoritative record of what exists, what is planned, and what has been decided (or
explicitly deferred) for the native Android ConnectX client.

All claims in this document are grounded in direct inspection of the repository at
`d:\Vamsi\ConnectX-The Secure Messaging Service\` as of 2026-08-24. Where something is
planned rather than built, it is explicitly labeled **PLANNED**, never stated as done.

---

## 1. Project Overview

ConnectX is a secure messaging application with an existing Spring Boot backend
(`connectx-backend`) and an existing React/TypeScript web client (`connectx-frontend`).
This document tracks the addition of a third client: a **native Android application**
(`connectx-android`), built independently of the web frontend's codebase, communicating
with the same backend over the same REST/WebSocket contract.

The Android client is being built incrementally, in small, explicitly-approved phases,
against a backend that currently runs on the developer's own PC. The web frontend and
backend are **not** being modified as part of this effort except where a phase explicitly
requires and approves a backend change (e.g. future FCM support).

## 2. ConnectX Android Development Philosophy

- **Small, verified modules.** Work proceeds phase by phase (N0–N14), sub-phase by
  sub-phase within N1. Nothing outside the currently-approved phase is implemented, even
  if it would be convenient to add "while we're in there."
- **Protocol reuse, not code reuse.** The web frontend's TypeScript cannot run on
  Android. What Android reuses from the existing system is the *contract*: REST endpoint
  shapes, STOMP destinations/event types, the call-signaling message protocol, and the
  E2EE wire format (ciphertext/nonce/keyVersion shapes). Android reimplements these
  natively in Kotlin rather than porting JavaScript.
- **No speculative architecture.** Packages, dependencies, and abstractions are added
  when their phase begins, not pre-created "for later." See Section 20.
- **Host-agnostic by construction.** The backend currently runs on a developer PC behind
  Cloudflare on a temporary domain. Android must never hardcode that domain (or any
  domain) into feature logic — see Sections 12–14.
- **Inspection before implementation.** Each phase begins with inspecting the actual
  current state of the project before writing code, so decisions are grounded in what
  exists, not assumptions.
- **Explicit approval gates.** Architecturally consequential decisions (e.g. E2EE
  private-key custody model, backend host strategy) are surfaced for explicit sign-off
  rather than silently decided.

## 3. Technology Stack

**CURRENT (implemented in `connectx-android` today):**

| Concern | Choice | Version |
|---|---|---|
| Language | Kotlin | 2.2.10 |
| UI toolkit | Jetpack Compose | BOM `2026.02.01` |
| Build system | Gradle (Kotlin DSL) | 9.5.0 |
| Android Gradle Plugin | AGP | 9.3.2 |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` | tied to Kotlin 2.2.10 |
| Core AndroidX | `core-ktx` | 1.10.1 |
| Lifecycle | `lifecycle-runtime-ktx` | 2.6.1 |
| Activity/Compose bridge | `activity-compose` | 1.8.0 |
| UI components | `compose-material3` | via BOM |
| Testing (unit) | JUnit | 4.13.2 |
| Testing (instrumented) | `androidx.test.ext:junit`, Espresso | 1.1.5 / 3.5.1 |
| Dependency injection | Hilt | 2.60.1 (via KSP 2.2.10-2.0.2) |
| HTTP client | OkHttp + Retrofit | 5.4.0 / 3.0.0 |
| Navigation | Navigation Compose | 2.9.7 |
| Icons | `androidx.compose.material:material-icons-core` | via Compose BOM |

**PLANNED (not yet added — introduced phase by phase):**

| Concern | Planned choice | Introduced in |
|---|---|---|
| JSON serialization | TBD — decided at N2 alongside real backend DTOs | N2 |
| Realtime/WebSocket | A native STOMP-over-WebSocket client (library TBD at N6) | N6 |
| Local persistence | Room + Jetpack DataStore | N7 |
| Secure storage | Android Keystore / Jetpack Security (`EncryptedSharedPreferences`) | N7 (tokens), N3 (initial JWT storage) |
| Push notifications | Firebase Cloud Messaging | N8 (requires new backend work) |
| Calling media | `org.webrtc` (Google WebRTC for Android) | N11 |
| Telephony integration | `ConnectionService`/`TelecomManager` | N11 |

No library beyond the "CURRENT" table has been added to the project as of this document's
last update. Do not assume any planned library is available until its phase is complete.

## 4. Current Android Project Structure

Verified directly from the filesystem (N1.2 inspection), single Gradle module:

```
connectx-android/
├── build.gradle.kts                  (root — plugin declarations only)
├── settings.gradle.kts                (repositories + :app include)
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── wrapper/gradle-wrapper.properties     (Gradle 9.5.0)
│   ├── libs.versions.toml                    (version catalog)
│   └── gradle-daemon-jvm.properties          (daemon JVM pin — see Section 8)
└── app/
    ├── build.gradle.kts               (Compose-only; no networking/DI/nav deps)
    ├── .gitignore
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     (single Activity; android.permission.INTERNET declared — see Section 9a)
        │   ├── java/com/connectx/app/
        │   │   ├── MainActivity.kt     (stock Compose template, minor cosmetic edit)
        │   │   └── ui/theme/{Color,Theme,Type}.kt   (stock Material3 palette)
        │   ├── res/{drawable,mipmap-*,values,xml}/  (stock template assets)
        │   └── keepRules/rules.keep
        ├── test/java/com/connectx/app/ExampleUnitTest.kt          (stock template)
        └── androidTest/java/com/connectx/app/ExampleInstrumentedTest.kt  (stock template)
```

There is currently **one application module** (`:app`). No feature modules, no
multi-module split. A multi-module structure is not currently planned; if the project
grows large enough to warrant one, that would be a deliberate future decision, not a
default.

**Git status as of this document:** the entire `connectx-android/` tree above is staged
(`git add`) but has **never been committed**. `git log` shows zero Android-related
commits. The first Android commit is scheduled for N1.10.

## 5. Application ID and Package

- `applicationId` = `com.connectx.app`
- `namespace` = `com.connectx.app`

Both set identically in `app/build.gradle.kts`. This is treated as fixed/final for the
life of the project — application ID changes after any real release are disruptive
(Play Store identity, existing installs) and there is no reason to change it.

## 6. Current Build Configuration

From `app/build.gradle.kts` (verified, not paraphrased):

- `compileSdk`: 37 (new-style `compileSdk { version = release(37) }` syntax)
- `minSdk`: 26
- `targetSdk`: 37
- `versionCode`: 1
- `versionName`: "1.0"
- `testInstrumentationRunner`: `androidx.test.runner.AndroidJUnitRunner`
- `buildTypes.release.optimization.enable`: `false` (R8/minification currently off for
  release builds — expected to be revisited at N14 Production Hardening, not before)
- `compileOptions.sourceCompatibility` / `targetCompatibility`: `JavaVersion.VERSION_11`
- `buildFeatures.compose`: `true`

No `debug`/`release` signing configuration exists yet. No build variants beyond the AGP
defaults (`debug`, `release`) exist.

## 7. Kotlin / AGP / Gradle / Compose Versions

| Component | Version | Source |
|---|---|---|
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` |
| AGP | 9.3.2 | `gradle/libs.versions.toml` |
| Gradle | 9.5.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Compose BOM | 2026.02.01 | `gradle/libs.versions.toml` |

All four were generated together by a current Android Studio "Empty Activity (Compose)"
project wizard and are mutually compatible. **No upgrades or downgrades are planned** —
per explicit decision, these versions are frozen until a concrete reason to change them
arises.

## 8. JDK and Gradle JVM Configuration

Three independent JVM-related settings exist in this project; they are **not the same
thing** and must not be conflated:

1. **Gradle daemon JVM** — `connectx-android/gradle/gradle-daemon-jvm.properties`,
   auto-generated by Gradle's `updateDaemonJvm` mechanism. Pins `toolchainVersion=25`
   for the JVM that runs the Gradle build process itself, with per-OS/arch
   `toolchainUrl.*` entries pointing at the Foojay Disco API (`api.foojay.io`) for
   auto-provisioning if JDK 25 isn't already available locally. **Decision: kept
   unchanged.**
2. **App bytecode target** — `app/build.gradle.kts` `compileOptions`:
   `sourceCompatibility`/`targetCompatibility = JavaVersion.VERSION_11`. This controls
   what Java bytecode level the app's Kotlin/Java sources compile to — unrelated to which
   JVM runs Gradle. **Decision: kept unchanged (JDK 11 target) for now.**
3. **Development machine JVM** — verified via direct inspection: `JAVA_HOME` is unset in
   the current shell, and the `java` resolved on `PATH` reports version **25.0.3**
   (Oracle, `C:\Program Files\Common Files\Oracle\Java\javapath\java`). No
   `org.gradle.java.home` override exists in `gradle.properties`.

**Explicit decision recorded (2026-08-24):** No Java/Kotlin/AGP/Gradle/Compose version
changes are being made at this time. The daemon JVM stays on JDK 25 auto-provisioning,
and the app's `compileOptions` stays on JDK 11 target. **This will be revisited during
N1.3** (environment/server configuration), which is the next point in the roadmap where
JDK alignment (e.g. matching the backend's JDK 17) may become relevant.

## 9. Android SDK Configuration

Per N0 (Environment Setup, COMPLETE): Android Studio, Android SDK, Android SDK Platform
Tools, and an Android API 36+ environment are already installed and confirmed working —
the project already builds and launches from this environment. `compileSdk`/`targetSdk 37`
(Section 6) indicates the installed SDK Manager platform includes API 37 at minimum.

Exact installed SDK platform/build-tools versions beyond what `compileSdk 37` implies are
**NOT DETERMINED FROM CURRENT REPOSITORY** — this is IDE/SDK Manager state, not
repository state, and was not independently re-verified for this document.

### 9a. Android Permissions

**N1.4 — Required Android Permissions: COMPLETE.**

**Permission added:**

| Permission | Type | Reason |
|---|---|---|
| `android.permission.INTERNET` | Normal (install-time, no runtime prompt) | Required by the network-layer foundation (N1.6, Retrofit + OkHttp) and all backend communication from N2 onward. Without it, no socket the app opens can succeed regardless of application code correctness — this is a foundation requirement, not a speculative future-feature permission. |

**Permissions deliberately NOT added, deferred to the phase that actually needs them:**

| Permission | Deferred to |
|---|---|
| `ACCESS_NETWORK_STATE` | N6 (Realtime/STOMP) or N9 (Background Architecture) — network-change detection |
| `RECORD_AUDIO` | N11 (Native WebRTC + Audio) |
| Bluetooth (`BLUETOOTH_CONNECT`/related) | N11 — audio routing |
| `POST_NOTIFICATIONS` | N8 (Push Notifications) |
| Camera | Not yet scheduled — no camera feature identified |
| Media (`READ_MEDIA_*`) | N5 (Messaging — media attachments) |
| Phone/Telecom | N11 — `ConnectionService` integration |
| Location, Contacts | Not currently scheduled in the roadmap |
| Foreground service | N9 |

No permission is added ahead of the phase whose feature genuinely requires it — this
list exists so future phases don't need to re-derive the reasoning each time.

### 9b. Dependency Injection (Hilt)

**N1.5 — Hilt Foundation: COMPLETE.**

**Why Hilt was introduced:** to establish the project's dependency-injection foundation
before any networking, repository, or ViewModel code is written (N1.6+), so those layers
are built directly against Hilt-provided dependencies rather than retrofitted onto a
manual singleton/service-locator pattern later, per the architecture principles in
Section 15.

**Hilt version actually used:** `2.60.1`, with **KSP `2.2.10-2.0.2`** as the annotation
processor (KSP was chosen over kapt — it's the modern, faster, Kotlin-native processor
and is what current Hilt guidance recommends for a new project; kapt was not used).

**A real compatibility blocker was hit and resolved, not silently worked around:** this
project's AGP version (9.3.2) includes AGP's new "built-in Kotlin" compilation feature.
KSP does not yet support that feature (a known, currently-open upstream issue,
`google/ksp#2729`) — the initial build failed with *"Using kotlin.sourceSets DSL to add
Kotlin sources is not allowed with built-in Kotlin."* This was surfaced to the user
before any fix was applied. The approved resolution was Google's own documented
workaround: `android.disallowKotlinSourceSets=false`, added to `gradle.properties`. It
does not change compiled output or any other build behavior — it only permits KSP's
current source-set registration approach. **This should be revisited/removed once KSP
publishes built-in-Kotlin support upstream.** (Recorded again in Section 24.)

Note also encountered, informational only, no action needed: the Gradle daemon (JDK 25,
per Section 8) logs *"Kotlin does not yet support 25 JDK target, falling back to Kotlin
JVM_24 JVM target"* — Kotlin silently targets JVM 24 bytecode instead of 25 for its own
tooling internals; this does not affect the app's own `compileOptions` (still JDK 11,
unchanged) and required no configuration change.

**Files added:**
- `app/src/main/java/com/connectx/app/ConnectXApplication.kt` — `@HiltAndroidApp`
  Application class, the root of the Hilt dependency graph.

**Files modified:**
- `app/src/main/AndroidManifest.xml` — `<application android:name=".ConnectXApplication">`
- `app/src/main/java/com/connectx/app/MainActivity.kt` — `@AndroidEntryPoint` added
  (required for any Android component that will consume Hilt-injected dependencies;
  added now as foundation, even though `MainActivity` injects nothing yet)
- `gradle/libs.versions.toml` — added `hilt`/`ksp` version entries and library/plugin
  aliases, following the project's existing version-catalog convention
- `build.gradle.kts` (root) — added `hilt-android`/`ksp` plugin declarations (`apply false`)
- `app/build.gradle.kts` — applied the Hilt and KSP plugins; added `hilt-android`
  implementation dependency and `hilt-android-compiler` via `ksp(...)`
- `gradle.properties` — added the `disallowKotlinSourceSets` workaround described above

**What this phase deliberately did NOT include:** no `NetworkModule`, no Retrofit, no
OkHttp, no repositories, no ViewModels, no `BuildConfig.BASE_URL` consumption anywhere.
Hilt's dependency graph was empty aside from its two entry points
(`ConnectXApplication`, `MainActivity`) until N1.6 (Section 9c) added the first
`@Provides` module.

### 9c. Network Foundation (Retrofit + OkHttp)

**N1.6 — Retrofit + OkHttp Foundation: COMPLETE.**

Establishes the first three layers of the intended network architecture:

```
BuildConfig.BASE_URL → OkHttpClient → Retrofit
```

Repositories, API interfaces, DTOs, and actual backend requests are **not** part of this
phase — they begin at N2.

**Versions used:**
- Retrofit `3.0.0` (`com.squareup.retrofit2:retrofit`) — requires Android API 21+,
  comfortably compatible with this project's `minSdk 26`.
- OkHttp `5.4.0` (`com.squareup.okhttp3:okhttp`).

**Location:** `app/src/main/java/com/connectx/app/core/network/NetworkModule.kt` — the
only new package created this phase (`core/network/`), matching the structure proposed
in Section 16.

**How Retrofit receives the base URL:** `NetworkModule.provideRetrofit()` calls
`Retrofit.Builder().baseUrl(BuildConfig.BASE_URL)...` directly — `BuildConfig.BASE_URL`
is the sole source of the URL string. No literal `http://10.0.2.2:8080/` or
`https://app.myconnect.sbs/` exists anywhere in `NetworkModule.kt` or any other Kotlin
source file (verified by searching the entire `app/src` tree after implementation — the
only match for either string in the non-generated project is the `buildConfigField`
declaration in `app/build.gradle.kts` itself, which is the intended single source of
truth per Section 13).

**Hilt integration:** `NetworkModule` is a Kotlin `object` annotated `@Module
@InstallIn(SingletonComponent::class)`, providing `OkHttpClient` and `Retrofit` as
`@Provides @Singleton` functions — `provideRetrofit` takes the Hilt-provided
`OkHttpClient` as a parameter and calls `.client(okHttpClient)`, so the two are wired
together entirely through Hilt's graph, not manual construction. No existing Hilt setup
(from N1.5) was changed or redesigned. The graph was validated by successful
`kspDebugKotlin`/`hiltAggregateDepsDebug`/`hiltJavaCompileDebug` compilation — no test
code was added purely to prove injection works, per the approved scope.

**OkHttpClient:** a bare `OkHttpClient.Builder().build()` — no interceptors of any kind
(no auth/JWT, no logging, no retry, no cache, no certificate pinning, no custom headers).
These are all explicitly deferred to the phase that defines their actual requirements
(auth interceptor → N3; logging → developer preference, not yet decided; WebSocket
concerns are a separate client entirely, N6).

**JSON converter/serialization — deliberately NOT added.** No serialization library
(Gson, Moshi, kotlinx-serialization) existed in the project before this phase, and none
was added. Retrofit does not require a converter factory to be constructed — it's only
required once an API interface method needs automatic request/response body conversion,
and no API interfaces exist yet (that's N2's job). Adding a JSON library now would be
exactly the kind of speculative dependency the project's development rules (Section 20,
rule 3) warn against. **The converter and its library choice will be decided at N2**,
when real backend DTOs are being modeled and the actual shape of the API responses is
known.

**No HTTP request was made.** No API interface, no endpoint definition, no DTO, no
repository was created. `Retrofit`'s presence in the Hilt graph was validated purely
through successful compilation/code generation, not by calling anything.

### 9d. Navigation Foundation

**N1.7 — Navigation Foundation: COMPLETE.**

**Correction recorded (2026-08-24):** this phase was reported complete once already, but
direct inspection at the start of the N1.8 request found no Navigation Compose
dependency, no `navigation/` package, and `MainActivity` still on the unmodified N1.5
scaffold — this document's own status table still read `N1.7 NOT STARTED` at that point
too. The discrepancy was surfaced before any N1.8 work began; the user confirmed N1.7
should actually be implemented now, which is what this section documents. Noted here so
the record is honest rather than silently overwritten.

**Version used:** Navigation Compose `2.9.7` (`androidx.navigation:navigation-compose`)
— the established, widely-documented Compose navigation library (as opposed to the
newer Navigation3, which is architecturally different — `NavDisplay`/`NavKey` rather than
`NavHost`/`NavController` — and was not chosen, to keep this foundation on the
better-documented, lower-risk path for a solo long-term project).

**Location:** `app/src/main/java/com/connectx/app/navigation/ConnectXNavHost.kt` — the
only new package this phase (`navigation/`).

**What was built:** a `ConnectXNavHost` composable wrapping `NavHost` with **two**
routes:
- `ConnectXDestinations.HOME` — renders the existing `Greeting` composable plus a
  "Go to Navigation Test" button.
- `ConnectXDestinations.NAVIGATION_TEST` — a second, temporary, N1.7-only placeholder
  destination ("Navigation Test Destination" text + a "Back" button).

`MainActivity` calls `ConnectXNavHost(modifier = ...)` inside its `Scaffold` instead of
calling `Greeting` directly. This establishes both the routing mechanism (a
`NavHostController`, a destinations object, a `NavHost` graph) and — per the approved
correction — an actually-exercised two-destination flow, which N1.8's design showcase
route and N4's real application screens will both build on, without pre-building any of
N4's actual navigation graph.

**Navigation flow verified manually on the Pixel 7a emulator (2026-08-24), via ADB tap
input + UI-hierarchy dumps, not just compilation:**
1. App launched on `HOME` — "Hello Vamsi!" and the "Go to Navigation Test" button
   confirmed present in the UI hierarchy.
2. Tapped the button (`navController.navigate(NAVIGATION_TEST)`) — UI hierarchy
   re-dumped, confirmed "Navigation Test Destination" and "Back" button now present,
   `HOME`'s content no longer present.
3. Tapped "Back" (`navController.popBackStack()`) — UI hierarchy re-dumped, confirmed
   return to `HOME` ("Hello Vamsi!" + button visible again).
4. `logcat` checked across the whole sequence for `FATAL`/`AndroidRuntime`/`Exception` —
   none found.

**Deliberately NOT included:** no bottom navigation, no drawer, no deep links, no
argument-passing routes, no transition animations — just the two placeholder
destinations needed to prove the navigation mechanism actually works, per the approved
minimal scope. `NAVIGATION_TEST` is explicitly a temporary N1.7 verification route, not
a real ConnectX screen — it is expected to be removed once N4 replaces it with the real
application shell, not carried forward as production UI.

### 9e. UI Foundation (Design System)

**N1.8 — ConnectX UI Foundation: COMPLETE.**

This phase establishes the reusable Compose design system future screens will consume.
It is NOT any real ConnectX screen (no login, home, chat, contacts, groups, profile,
settings, or calling UI was built) — those remain scoped to their own later phases
(N3-N11).

**Existing React UI actually inspected** (not assumed): `connectx-frontend/tailwind.config.js`,
`connectx-frontend/src/index.css`, `features/auth/AuthModal.tsx`, `components/common/UserAvatar.tsx`,
`components/chat/MessageBubble.tsx`, plus a grep sweep across `components/` for recurring
color/radius/shadow utility classes.

**ConnectX visual identity identified from the React app:**
- Brand gradient: `violet-600` (#7C3AED, primary CTA) paired with `indigo-600` (#4F46E5,
  avatar/sent-bubble gradient partner); hover state `violet-500` (#8B5CF6).
- Semantic colors: success/online presence = `emerald-500` (#10B981, confirmed
  consistent across `NavigationRail.tsx`, `ContactInfoDrawer.tsx`, `ProfileModal.tsx`
  etc.); warning = `amber-500`; error/destructive = `rose-500`/`rose-600`.
- Light surfaces (`src/index.css :root`): background `#F8FAFC`, card `#FFFFFF`, sidebar
  `#F1F5F9`, border `#E2E8F0`, text primary `#0F172A`, text secondary `#64748B`.
- Dark surfaces (`src/index.css .dark`): background `#090D16`, card `#1E293B`,
  sidebar/rail `#0F172A`, border `#334155`, text primary `#F8FAFC`, text secondary `#94A3B8`.
- Corner radii: `rounded-xl` (12dp) used consistently for buttons/inputs/small cards;
  `rounded-2xl` (16dp) used for message bubbles and larger surfaces.
- Shadow language: restrained — a colored shadow only on the primary CTA button
  (`shadow-violet-600/20`), everything else uses a plain border/`shadow-sm` rather than
  heavy elevation. Confirmed by direct inspection of `AuthModal.tsx`'s button classes and
  the general absence of heavy `shadow-lg`/`shadow-xl` usage elsewhere.
- Font: `Plus Jakarta Sans` (Google Font, loaded via `src/index.css`).
- Avatars: circular, indigo→violet gradient background, initials fallback (`UserAvatar.tsx`).
- Touch targets: React already enforces `min-h-[44px]` on primary buttons — a real,
  carried-forward accessibility signal, not a native-only addition.

**Visual decisions that could NOT be confidently determined from the React app, called
out explicitly rather than invented:**
- **Received-message bubble color in light mode.** `MessageBubble.tsx` uses a fixed
  `bg-slate-800/95` (dark slate) for received bubbles with no separate light-mode class
  at all — i.e. the web app itself may not have fully addressed this for light mode.
  Copying a dark bubble onto a light background natively would likely look wrong, so
  `ConnectXExtendedColors.messageReceived` uses the theme's own `surfaceVariant` for
  light mode and the React app's literal dark-slate value only for dark mode. This is
  flagged as a **new native decision**, to be revisited when N5 (Messaging) builds real
  message bubbles against actual product intent.
- **Native font.** Bundling `Plus Jakarta Sans` natively (as a raw font resource or via
  the Google Fonts downloadable-fonts provider) is a real new dependency/asset decision
  — not required for the design-system foundation itself. **Deferred**; `FontFamily.Default`
  (system sans-serif) is used for now. See Section 24.

**Color system:** `ui/theme/Color.kt` — raw palette constants (never referenced directly
by screens); `ui/theme/Theme.kt` — `ConnectXExtendedColors` (a `CompositionLocal` for
tokens Material3's `ColorScheme` has no slot for: `success`, `warning`,
`presenceOnline`, `messageSentGradient`, `messageReceived`, and their `onXxx` pairs) plus
`ConnectXLightColorScheme`/`ConnectXDarkColorScheme` mapping the palette onto Material3's
standard slots (primary/secondary/background/surface/surfaceVariant/outline/error).

**Typography:** `ui/theme/Type.kt` — a `Typography` scale (`titleLarge/Medium/Small`,
`bodyLarge/Medium/Small`, `labelLarge/Medium/Small`) with weights/sizes chosen to mirror
the React app's heading/body/button/caption hierarchy, using `FontFamily.Default` (see
font decision above).

**Spacing:** `ui/theme/Dimensions.kt` — `ConnectXSpacing` object (`xs`=4dp, `sm`=8dp,
`md`=12dp, `lg`=16dp, `xl`=24dp, `xxl`=32dp, `screenMargin`=`lg`).

**Shapes:** `ui/theme/Shapes.kt` — `ConnectXRadius` (`small`=8dp, `medium`=12dp matching
React's `rounded-xl`, `large`=16dp matching React's `rounded-2xl`) plus a Material3
`Shapes()` instance wired into `ConnectXTheme`.

**Surface/elevation:** follows the React app's border-over-shadow preference directly —
`ConnectXCard` uses `OutlinedCard` (a 1dp `outline`-colored border, no drop shadow)
rather than Material3's default elevated `Card`.

**Components created** (`ui/components/`), each consuming theme tokens only, no
hardcoded colors/shapes:
- `ConnectXButton` — primary CTA, `enabled`/`isLoading` states (spinner replaces
  nothing else, matches React's `AuthModal.tsx` submit-button pattern), 48dp minimum
  height.
- `ConnectXTextField` — filled/outlined hybrid via `OutlinedTextField`, primary-colored
  focus border (stands in for React's focus-ring treatment), `enabled`/`isError`/
  `supportingText`/`isPassword` params.
- `ConnectXCard` — bordered flat surface (see above).
- `ConnectXAvatar` — circular, indigo→violet gradient, initials-only (`Small`/`Medium`/`Large`
  sizes). Deliberately **not** image-loading capable yet — Coil/Glide is a real new
  dependency with no genuine use until real profile-photo data exists in a later phase;
  adding it now would be speculative.
- `ConnectXLoadingIndicator`, `ConnectXEmptyState`, `ConnectXErrorState`
  (`ui/components/ConnectXStateViews.kt`) — generic, reusable loading/empty/error
  patterns for future screens (empty conversation lists, failed loads), not tied to any
  one feature.

Evaluated and deliberately **not** created: `ConnectXTopBar` (Material3's `TopAppBar`
with theme tokens already suffices; no wrapper adds real value yet) and `ConnectXDivider`
(Material3's `HorizontalDivider` colored with `MaterialTheme.colorScheme.outline` is a
one-line call, not worth a dedicated file).

**Icon dependency decision:** `Icons.AutoMirrored.Filled.ArrowBack` etc. required adding
`androidx.compose.material:material-icons-core` explicitly — despite being present
transitively in the Gradle cache, it was not actually resolvable from the app module's
compile classpath without an explicit dependency. This is still the first-party, ~1000-icon
Compose Material **core** set (not a third-party library). The much larger
`material-icons-extended` artifact was deliberately **not** added — four icons initially
used (`ErrorOutline`, `Inbox`, `DarkMode`, `LightMode`) turned out to only exist in
`-extended`, and rather than pull in that whole library for four icons, they were
swapped for core-set equivalents (`Warning`, `Info`) or, for the showcase's theme
toggle, replaced with a plain text `TextButton` ("Dark theme"/"Light theme") — no icon
needed at all.

**Light/dark theme:** `ConnectXTheme(darkTheme = isSystemInDarkTheme(), content)`.
**Android 12+ dynamic color is deliberately NOT supported at all** — the stock template
this project started from included it; it was removed because dynamic color derives the
palette from the user's wallpaper, which would override the ConnectX brand identity this
phase exists to establish. This is a considered N1.8 decision, not an oversight.

**Accessibility:** `ConnectXButton` enforces a 48dp minimum height (exceeds React's
44dp); `ConnectXAvatar` sets `contentDescription` via `Modifier.semantics`; icons used
purely decoratively (state-view icons) pass `contentDescription = null` deliberately
(their meaning is carried by adjacent text, avoiding double-announcement); all text uses
`sp`-based `Typography` styles (scales with system font size); color choices reuse
Material3's contrast-considered slot system rather than arbitrary hex pairings.

**Responsive design:** no fixed dp/px screen-size assumptions anywhere — all layout uses
`dp`/`sp` and Compose layout primitives (`Column`/`Row`/`LazyColumn`/`weight`/`fillMaxWidth`).
Not independently verified on a second emulator size or the physical Vivo X200 FE this
session (see Verification below).

**Animation:** no elaborate animations were built. The showcase's light/dark toggle is
an instant recompose (no transition), consistent with "establish conventions only, no
elaborate application animations yet."

**Design Showcase:** `ui/showcase/DesignShowcaseScreen.kt` — explicitly commented as
TEMPORARY, N1.8-only, not a ConnectX screen. Demonstrates colors, typography, buttons
(enabled/disabled/loading), text fields (normal/error/disabled), cards, avatars, a
divider, loading/empty/error states, and the spacing scale, in a scrollable `LazyColumn`.
Manages its own local light/dark override (independent of system setting) purely so both
themes can be inspected in one session — showcase-only infrastructure, not a real app
setting.

**Temporary navigation:** the existing N1.7 `HOME`/`NAVIGATION_TEST` routes were left
completely unchanged. One new temporary route, `DESIGN_SHOWCASE`, was added, reachable
via a new "Design System Showcase" button on `HOME` (alongside the existing "Go to
Navigation Test" button) and a back arrow in the showcase's own top bar. Like
`NAVIGATION_TEST`, `DESIGN_SHOWCASE` is explicitly commented as temporary and expected to
be removed once N4 (Core App Shell) replaces this scaffolding.

**Deferred to later phases (explicitly, not silently skipped):** login/registration/OTP
UI (N3), home/conversation-list/chat/contacts/groups/profile/settings/calling screens
(N4-N11), real application navigation graph (N4), native `Plus Jakarta Sans` font asset,
image-loading avatars (Coil/Glide), and the light-mode received-message-bubble color
decision (all flagged above for revisit in N5+).

### 9f. N1.9 — Build + Emulator Verification

**N1.9 — Build + Emulator Verification: COMPLETE.** A pure verification pass — no
source code was modified. Confirms the complete N1 foundation (N1.1-N1.8) builds,
installs, launches, navigates, and renders correctly as one integrated project before
the N1.10 git baseline commit.

**Pre-verification inspection (not assumed from prior reports):** directly re-read
`gradle/libs.versions.toml`, both `build.gradle.kts` files, `gradle.properties`,
`AndroidManifest.xml`, and listed every `.kt` file under `app/src/main/java` — confirmed
Hilt 2.60.1/KSP 2.2.10-2.0.2, Retrofit 3.0.0/OkHttp 5.4.0, Navigation Compose 2.9.7,
`material-icons-core`, the `disallowKotlinSourceSets=false` workaround, both
`BuildConfig.BASE_URL` build-type values, the single `INTERNET` permission, and all 15
expected Kotlin files (`ConnectXApplication`, `MainActivity`, `NetworkModule`,
`ConnectXNavHost`, 5 theme files, 5 component files, `DesignShowcaseScreen`) were present
exactly as documented in Sections 9a-9e — no discrepancy found this time.

**Clean build:**
```
./gradlew clean          -> BUILD SUCCESSFUL
./gradlew assembleDebug  -> BUILD SUCCESSFUL (42/42 tasks executed, none UP-TO-DATE,
                             confirming a genuinely fresh compile/dex/package cycle)
```
Re-run once more with `--no-configuration-cache` specifically to force the configuration
phase to re-print its warnings (a prior cached-config run had suppressed them from the
console, though the underlying condition is unaffected either way) — only one warning
appeared: the already-known, already-documented `android.disallowKotlinSourceSets=false`
experimental-option notice (Section 9b/24). No errors, no deprecation warnings, no new
warnings.

**APK installation:** existing install fully **uninstalled** first, then a fresh
`install` — a true clean install, not an overwrite — succeeded.

**Pixel 7a launch:** `MainActivity` confirmed as `topResumedActivity` after launch;
`logcat` scanned for `FATAL`/`AndroidRuntime`/`Exception` in the app's process —
none found.

**Navigation verification (real interaction via ADB tap input + UI-hierarchy dumps at
each step, not source inspection):**
1. `HOME` rendered — "Hello Vamsi!" + both buttons present.
2. Tapped "Go to Navigation Test" — `navigation_test` rendered ("Navigation Test
   Destination" + "Back"), `HOME` content gone, app still foreground.
3. Tapped "Back" — returned to `HOME`, both buttons visible again.
4. No crash, no exception, throughout.

**Design System Showcase verification (full scroll, screenshots reviewed at every
section):** Colors, Typography, Buttons (enabled/disabled/loading), Text Fields
(normal/error/disabled), Cards, Avatars, Divider, States (Loading/Empty/Error), and the
Spacing scale all rendered correctly — no clipping, no overlapping content, no broken
padding, no text truncation, no invisible content, no incorrect colors. Confirmed on this
freshly-clean-built APK, not reused from the N1.8 install.

**Light theme:** confirmed — light backgrounds, dark-on-light text, correct brand colors,
all surfaces distinguishable.

**Dark theme:** toggled via the showcase's own theme switch — confirmed navy top bar,
dark card surfaces with visible borders, light-on-dark text properly contrasted, brand
colors (violet/indigo/emerald/amber/rose) all still legible against the dark background.
No crash on toggle.

**Hilt/KSP:** `kspDebugKotlin`, `hiltCollectClassesDebug`, `hiltAggregateDepsDebug`, and
`hiltJavaCompileDebug` all executed and succeeded in the clean build — confirms
`ConnectXApplication`'s `@HiltAndroidApp` graph, `MainActivity`'s `@AndroidEntryPoint`,
and `NetworkModule`'s `@Provides` chain all compile and generate correctly. No artificial
test injections were added; verified via the existing project state only, per instruction.

**Retrofit/OkHttp:** compiled successfully as part of the same clean build; confirmed via
`compileDebugKotlin` succeeding with `NetworkModule.kt` (which references both) in the
source set.

**INTERNET permission:** confirmed present, sole `<uses-permission>` entry in the
manifest. No new permission added.

**Backend/network request confirmation:** grepped the entire `app/src` tree for
`BASE_URL`, `app.myconnect.sbs`, and `10.0.2.2` — **all three appear in exactly one
file, `NetworkModule.kt`** (their intended, already-existing consumer). No other file
references any of them. No Retrofit call, API interface, or endpoint exists anywhere.
Zero network requests were made during this verification.

**Vivo X200 FE:** **NOT VERIFIED / DEVICE UNAVAILABLE** — only `emulator-5554` was
listed by `adb devices` in this environment. The Pixel 7a remains the authoritative N1
verification device, per the approved scope.

**Warnings:** one, already-documented (`disallowKotlinSourceSets` experimental notice).
No new warnings surfaced.

**Genuine issues discovered:** none. No code changes were required.

**Issues deliberately deferred (unchanged from prior phases, re-confirmed still
accurate, not re-litigated here):** native `Plus Jakarta Sans` font, light-mode
received-message-bubble color, physical-device `BASE_URL` LAN-address support, KSP/AGP9
`disallowKotlinSourceSets` workaround revisit — see Section 24.

**Architectural observations noted for a future phase (not implemented, per N1.9's
freeze):** none beyond what Section 24 already tracks — no new recommendation arose
during this verification pass.

## 10. Emulator Configuration

**Decision recorded:** a **Pixel 7a** Android Virtual Device (AVD) is the designated
primary everyday-development emulator, used for day-to-day UI/build iteration.

**N1.1 — Android Virtual Device: COMPLETE.** Verified configuration:

- Device: Pixel 7a
- Android version: 16.0
- API level: 36.1
- System image: Google APIs Intel x86_64 Atom System Image
- Emulator successfully created
- Emulator successfully booted
- ConnectX successfully launched on the emulator
- "Hello Vamsi!" was successfully displayed, confirming the current stock
  `MainActivity`/`ConnectXTheme` build runs correctly on this AVD

This emulator is now the primary everyday-development target alongside the physical
Vivo X200 FE device (Section 11).

## 11. Physical Device Testing

**Vivo X200 FE** is the designated physical test device. Per N0, ADB/device connectivity
to this device is already confirmed working (the stock Compose template has been built
and launched on it successfully). It remains the physical-device verification target for
all future phases, particularly ones with physical-hardware-dependent behavior:
microphone capture (N11), audio routing (N11), background/battery-management behavior
(N9) — Vivo devices are known in the broader Android ecosystem for aggressive
OEM-level background-process management, which makes this device a meaningful real-world
test target for N9/N12, not just a convenience device.

## 12. Backend Hosting Context

**CURRENT (factual, not aspirational):**

- The ConnectX backend (Spring Boot, `connectx-backend`) currently **runs on the
  developer's own PC** — it is not deployed to any cloud host, VPS, or managed platform.
- **Cloudflare** currently sits in front of this PC-hosted backend, providing the public
  routing/TLS termination for the current subdomain (Section 13).
- No money is currently being spent on deployment/hosting infrastructure. This is a
  deliberate, temporary state — real (paid) deployment is planned for later, at a point
  not yet scheduled.
- Because the backend runs on a developer machine, its availability is tied to that
  machine being on and the backend process running — Android development against a live
  backend should account for this being an intermittent, developer-controlled endpoint
  rather than an always-on service.

## 13. Backend Base URL Strategy

**CURRENT domain in use:** root domain `myconnect.sbs`, application subdomain
`app.myconnect.sbs`, fronted by Cloudflare, pointing at the PC-hosted backend.

**N1.3 — Environment/Server Configuration: COMPLETE.** The base URL is now established
as configuration, not feature logic, via `BuildConfig.BASE_URL`, sourced from the
existing `debug`/`release` Gradle build types in `app/build.gradle.kts`
(`buildFeatures.buildConfig = true` was enabled to generate it):

- `debug` → `BASE_URL = "http://10.0.2.2:8080/"` — the Android emulator's special host-loopback
  alias, reaching the Spring Boot backend on the development PC's `localhost:8080`. This
  address is valid **only** for the Pixel 7a emulator; it is not usable from the physical
  Vivo X200 FE device.
- `release` → `BASE_URL = "https://app.myconnect.sbs/"` — the current production/Cloudflare-fronted
  address.

Both values retain a trailing slash and carry no API path segments (`/api`, `/v1`, etc.)
— path structure is deferred to N2, per the approved N1.3 design.

`BuildConfig.BASE_URL` is the **single source of truth** for the backend host. No
hostname is hardcoded anywhere else in the project — `MainActivity`, UI, and all
future ViewModels/repositories/authentication/messaging/calling/WebSocket/E2EE code must
depend on this constant (indirectly, via the network layer once it exists) rather than on
a literal URL string.

**Explicitly deferred, per the approved design** (not implemented in N1.3):
- Product flavors (`dev`/`prod` installable variants) — the existing `debug`/`release`
  build types are sufficient for now.
- Remote/bootstrap configuration (fetching the base URL at runtime) — deferred until a
  real, stably-hosted production backend and an actual install base exist.
- A `local.properties`-based debug override — deferred; the `debug` build type's value is
  currently a fixed literal in `app/build.gradle.kts`.

**Not yet built:** no networking code exists yet (Section 3) — `NetworkModule`,
OkHttp, and Retrofit (which will be the sole consumers of `BuildConfig.BASE_URL`) are
scheduled for N1.6. N1.3 only established the configuration value itself; nothing reads
it yet.

**Physical device note:** the Vivo X200 FE will eventually need a PC LAN address (not
`10.0.2.2`) to reach the locally-hosted backend. Per the approved N1.3 scope, a
separate physical-device configuration path was explicitly **not** implemented — this
is a known, deliberately deferred gap, not an oversight (see Section 24).

## 14. Domain Migration Strategy

The current domain (`app.myconnect.sbs` behind Cloudflare, backend on a developer PC) is
explicitly understood to be **temporary**. The backend will eventually move to paid,
more permanent hosting — the specific target (VPS, managed cloud, container platform,
etc.) is **not yet decided** and is out of scope for Android work.

Because Section 13's base-URL strategy isolates the host behind a single configuration
point, migrating the backend to a new host/domain in the future should require **only a
configuration change** in the Android app (updating the one resolved base URL), not a
rewrite of any networking, authentication, messaging, or calling feature code. This
requirement is binding on how N1.3/N1.6 are implemented, even though the migration itself
is not scheduled.

## 15. Android Architecture Principles

- **MVVM** — Compose UI observes `ViewModel`-exposed state (`StateFlow`); `ViewModel`s
  depend on repository/use-case layers, never directly on Retrofit services or database
  DAOs from UI code.
- **Single module today, package-based separation of concerns** (Section 16) — feature
  boundaries are enforced by package structure and dependency direction, not by Gradle
  module boundaries, until/unless project size later justifies a multi-module split.
  That split is not currently planned.
- **Protocol-first, not code-ported** — as stated in Section 2, Android reimplements the
  backend's REST/STOMP/call-signaling/crypto *contracts* natively; it does not attempt to
  port `connectx-frontend` TypeScript.
- **Host-agnostic networking** — per Sections 13–14, no feature package may hardcode a
  backend host.
- **Security-conscious local storage** — tokens and any future key material use
  Android Keystore-backed mechanisms (`EncryptedSharedPreferences` at minimum), never
  plain `SharedPreferences` or unencrypted files, from the first phase that introduces
  them (N3 for tokens, N7 for crypto keys).
- **DI via Hilt** once introduced (N1.5) — no manual singleton/service-locator pattern is
  planned as an interim measure; Hilt is adopted directly when N1.5 begins.
- **No premature abstraction** — interfaces/abstractions are introduced when a second
  implementation or a testing need actually exists, not speculatively.

## 16. Recommended Package Structure

**PLANNED target structure** (proposed, not yet created — packages are added only when
their owning phase begins, per Section 2's "no future-phase packages" rule):

```
com.connectx.app/
├── ConnectXApplication.kt        (N1.5 — @HiltAndroidApp — IMPLEMENTED)
├── MainActivity.kt                (@AndroidEntryPoint added at N1.5; becomes NavHost host at N1.7)
├── core/
│   └── network/
│       └── NetworkModule.kt        (N1.6 — IMPLEMENTED — OkHttpClient + Retrofit,
│                                     consuming BuildConfig.BASE_URL, see Section 9c)
│
│   (a separate core/config package was not needed — N1.3 decided BuildConfig.BASE_URL
│   directly as the mechanism; a separate core/di package was not needed either —
│   NetworkModule.kt itself is the Hilt module, no extra indirection layer added)
├── navigation/
│   └── ConnectXNavHost.kt          (N1.7/N1.8 — IMPLEMENTED — HOME, NAVIGATION_TEST,
│                                     DESIGN_SHOWCASE routes; see Sections 9d/9e)
├── ui/
│   ├── theme/                      (N1.8 — IMPLEMENTED — see Section 9e)
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   ├── Type.kt
│   │   ├── Dimensions.kt
│   │   └── Shapes.kt
│   ├── components/                 (N1.8 — IMPLEMENTED — see Section 9e)
│   │   ├── ConnectXButton.kt
│   │   ├── ConnectXTextField.kt
│   │   ├── ConnectXCard.kt
│   │   ├── ConnectXAvatar.kt
│   │   └── ConnectXStateViews.kt
│   └── showcase/                   (N1.8 — TEMPORARY, see Section 9e)
│       └── DesignShowcaseScreen.kt
│
│  — everything below is FUTURE-PHASE and does not exist yet —
│
├── auth/                          (N3)
├── conversation/                  (N4/N5)
├── chat/                          (N5)
├── group/                         (N5, mirrors backend group/ package)
├── contacts/                      (N5)
├── profile/                       (N4)
├── media/                         (N5)
├── crypto/                        (N7)
├── device/                        (N13)
├── notifications/                 (N8)
└── calling/
    ├── signaling/                 (N10)
    ├── webrtc/                    (N11)
    ├── telecom/                   (N11)
    └── audio/                     (N11)
```

Only the `core/`, `navigation/`, and `ui/theme/` entries are in-scope for N1 sub-phases
currently approved or in progress. Everything below the divider is documented here for
long-term planning continuity only.

## 17. Phase Roadmap (N0–N14)

| Phase | Name |
|---|---|
| N0 | Environment Setup |
| N1 | Android Foundation |
| N2 | Backend Connectivity |
| N3 | Authentication |
| N4 | Core App Shell |
| N5 | Messaging |
| N6 | Realtime / STOMP |
| N7 | Local Data + E2EE |
| N8 | Push Notifications |
| N9 | Background Architecture |
| N10 | Native Call Signaling |
| N11 | Native WebRTC + Audio |
| N12 | Call Reliability |
| N13 | Multi-device |
| N14 | Production |

See Section 19/26 for current per-phase status.

## 18. Detailed N1 Sub-Phases

| Sub-phase | Description |
|---|---|
| N1.0 | Android development documentation (this document) |
| N1.1 | Android Virtual Device (Pixel 7a) creation/verification |
| N1.2 | Inspect and freeze current Android project structure |
| N1.3 | Environment/server (backend base URL) configuration strategy |
| N1.4 | Required Android permissions — `INTERNET` (see Section 9a) |
| N1.5 | Hilt foundation (DI setup, `@HiltAndroidApp` Application class) — see Section 9b |
| N1.6 | Retrofit + OkHttp foundation (HTTP client, no endpoint calls yet) — see Section 9c |
| N1.7 | Navigation foundation (Navigation Compose, `NavHost`, placeholder routes) — see Section 9d |
| N1.8 | ConnectX theme/UI foundation (replace stock Material3 palette) — see Section 9e |
| N1.9 | Build + emulator verification (confirm everything above builds/runs) — see Section 9f |
| N1.10 | Git baseline commit (first real commit of the Android module) |

Each sub-phase is implemented and verified individually; later sub-phases are not started
until the current one is confirmed working.

## 19. Current Phase Status

See the Change Log / Status table in Section 26 — that table is the single source of
truth for phase status and is updated as work progresses. Do not duplicate status
tracking elsewhere in this document.

## 20. Development Rules

Binding for all future Android work in this project, carried forward from the approved
plan:

1. Do not blindly rewrite the project.
2. Do not upgrade dependencies just because newer versions exist.
3. Do not introduce unnecessary libraries.
4. Do not create future-phase packages just for the sake of creating them.
5. Do not hardcode `app.myconnect.sbs` (or any other host) into feature code.
6. Do not implement API calls before N2.
7. Do not implement authentication before N3.
8. Do not implement WebSocket before N6.
9. Do not implement FCM before N8.
10. Do not implement WebRTC before N11.
11. Do not implement E2EE before N7.
12. Do not implement calling before N10/N11.
13. Do not change the existing backend without explicit approval for that specific change.
14. Do not change the existing web frontend.
15. Do not assume a cloud deployment exists — the backend runs on a developer PC today.
16. Do not assume multiple domains/environments exist beyond what's documented in
    Section 13.
17. Do not invent APIs that do not exist in the current backend — verify against
    `connectx-backend` source before assuming an endpoint exists.
18. Do not perform large refactors without explaining why, in the corresponding
    phase's discussion.

## 21. Testing Strategy

**CURRENT:** the project has only the stock Android Studio template tests —
`ExampleUnitTest.kt` (JVM unit test, `2+2==4`) and `ExampleInstrumentedTest.kt`
(instrumented test asserting the package name) — neither exercises any ConnectX-specific
logic, because none exists yet.

**PLANNED, by phase:**
- N1 onward: build verification (does it compile, does it launch on emulator + physical
  device) is the primary N1 "test" — see N1.9.
- N2+: unit tests for repository/networking logic as it's introduced; instrumented/UI
  tests for Compose screens as they're built.
- N6+: realtime/WebSocket logic should be tested against reconnect/disconnect scenarios
  specifically, given the known reliability gaps already documented in the web client's
  equivalent code (see the prior `CONNECTX_REALTIME_ARCHITECTURE_AUDIT.md` /
  `CONNECTX_REALTIME_REST_ARCHITECTURE_ANALYSIS.md` root-level audits).
- N11/N12: calling logic should be tested against real network conditions (Wi-Fi↔cellular
  switching, restrictive NAT) on the physical Vivo X200 FE device specifically, not just
  the emulator, since audio routing/Telecom/background behavior cannot be fully validated
  in an emulator.
- No specific test framework beyond JUnit/Espresso (already present) is currently
  planned to be added; this may be revisited if a phase's complexity warrants it (e.g.
  Turbine for `Flow` testing at N6, MockWebServer for N2 networking tests) — such
  additions will be called out explicitly in that phase, not pre-added now.

## 22. Git Strategy

- The `connectx-android` module is currently **staged but uncommitted**; the first
  commit is deliberately deferred to **N1.10**, after the full N1 foundation (permissions,
  Hilt, Retrofit, Navigation, theme, verified build) is in place, so the baseline commit
  represents a coherent, working foundation rather than a partial scaffold.
- Documentation changes (like this file) are committed independently and do not require
  waiting for N1.10, since they don't affect build state — but no such commit is made
  automatically; git actions remain explicit and user-approved per standing project
  practice.
- Branch: work is proceeding on `phase-6-e2ee-media-stable` per current repository state;
  whether Android work eventually moves to its own branch is an open decision, not yet
  needed at N1's scale.
- `.gitignore` coverage was verified during N1.2: `build/` and `local.properties` are
  correctly excluded at both the module and app level. One minor gap noted: the
  auto-generated `gradle/gradle-daemon-jvm.properties` is currently untracked and not
  explicitly covered by any `.gitignore` rule — to be resolved (tracked deliberately or
  gitignored) as part of N1.10's baseline commit, not before.

## 23. Architecture Decisions

Recorded decisions to date, with rationale:

- **Native Kotlin + Jetpack Compose, not Capacitor/hybrid.** Decided based on this
  project's specific requirements (native calling, Telecom integration, audio routing,
  background reliability) rather than generic guidance — these are the areas where a
  WebView-based hybrid approach would need native plugins anyway, making a fully native
  approach more direct, not more expensive.
- **Single Gradle module.** No multi-module split for now; revisit only if project size
  genuinely warrants it.
- **MVVM with Hilt DI**, introduced at N1.5, not before.
- **Frozen toolchain versions** (Kotlin 2.2.10 / AGP 9.3.2 / Gradle 9.5.0 / Compose BOM
  2026.02.01) — no upgrades without explicit reason.
- **Gradle daemon JVM (JDK 25) and app `compileOptions` (JDK 11) both left unchanged**,
  despite the apparent mismatch with the backend's JDK 17 — explicitly deferred to N1.3
  rather than decided now (see Section 8).
- **Host-agnostic base URL strategy required** (Sections 13–14) — mechanism TBD at N1.3,
  but the constraint (no hardcoded host in feature code) is decided now.
- **Protocol reuse over code reuse** from the existing web frontend — no attempt to share
  TypeScript logic; REST/STOMP/call-signaling/crypto *contracts* are the reused asset.

## 24. Known Issues / Open Decisions

Carried forward from the pre-N1 architecture audit, relevant to future Android phases:

- **E2EE private-key custody model (relevant to N7/N13):** the existing backend stores
  each user's DIRECT-message ECDH private key in plaintext (`User.masterPrivateKey`) and
  serves it back to any authenticated device via `GET /api/v1/users/me/identity-key`.
  Whether Android's N7 implementation replicates this shared-key model as-is, or moves
  toward device-local key generation (which would require coordinated backend/web
  changes), is an **open decision requiring explicit approval before N7 begins**.
- **No TURN server exists anywhere** (backend or frontend) for the existing calling
  feature — relevant to N11/N12; TURN infrastructure provisioning is new work, not
  something Android can assume is already available.
- **No FCM/APNs integration exists on the backend** — Web Push (VAPID) is the only
  existing push mechanism, and it does not work for a native Android app. N8 requires new
  backend work (a device-token registration path + `firebase-admin`-based sending) before
  Android push notifications can function at all.
- **Split-brain real-time gap:** connection-request, group-invitation, and group
  membership/role-change mutations currently have zero WebSocket broadcast on the
  backend (REST-only) — this affects any client, including Android once N6 is reached;
  whether/when this gets fixed backend-side is outside Android's control but affects
  Android's realtime UX expectations.
- **JDK alignment** (Section 8) — whether to eventually align the app's `compileOptions`
  target with the backend's JDK 17, or leave it at 11, is unresolved and deferred to N1.3.
- **Physical-device base URL:** the `debug` build type's `BASE_URL` (`10.0.2.2`) only
  resolves correctly on the Android emulator. Testing the locally-hosted backend from the
  physical Vivo X200 FE will require a PC LAN address — deliberately not implemented in
  N1.3 (no product flavors, no `local.properties` override yet); revisit if/when
  physical-device testing against the local backend is needed before N1.3's deferred
  items (flavors, remote config) are otherwise addressed.
- **Gradle daemon JVM `.gitignore` coverage** (Section 22) — minor, deferred to N1.10.
- **KSP / AGP 9 built-in-Kotlin incompatibility** (Section 9b): `gradle.properties` carries
  `android.disallowKotlinSourceSets=false` as a workaround for a currently-open upstream
  bug (`google/ksp#2729`) between KSP (Hilt's annotation processor) and AGP 9.3.2's
  built-in-Kotlin compilation feature. Revisit and remove this flag once KSP publishes
  built-in-Kotlin support — check the upstream issue before any future AGP/KSP version
  change.
- **Native `Plus Jakarta Sans` font** (Section 9e): the React frontend uses this Google
  Font throughout; the Android design system currently uses `FontFamily.Default` instead.
  Deliberately deferred — not required for the N1.8 foundation, and bundling it is a
  real new dependency/asset decision. Revisit explicitly if native Plus Jakarta Sans is
  genuinely wanted for a later phase.
- **Light-mode received-message-bubble color** (Section 9e): React's `MessageBubble.tsx`
  uses a fixed dark-slate background for received bubbles with no separate light-mode
  variant — this specific value could not be confidently determined from the existing UI.
  `ConnectXExtendedColors.messageReceived` uses a new native decision (theme
  `surfaceVariant` for light mode) rather than copying the dark value onto a light
  background. Revisit against actual product intent when N5 (Messaging) builds real
  message bubbles.

## 25. Future Enhancement Guidelines

- New dependencies are added only within the sub-phase that needs them, via the existing
  `gradle/libs.versions.toml` version-catalog convention — not hardcoded directly into
  `app/build.gradle.kts`.
- Any new backend endpoint assumption must be verified against actual
  `connectx-backend` source (controller + service) before Android code is written
  against it — never inferred from the web frontend's usage alone, since the frontend
  itself has documented gaps (Section 24) that shouldn't be silently inherited.
- When a phase's implementation reveals a genuine backend gap (e.g. FCM, TURN), that gap
  is recorded here (Section 24) and raised explicitly, not worked around unilaterally on
  the Android side.
- Package structure additions follow Section 16's plan; deviations should be recorded as
  a new Architecture Decision (Section 23) with rationale, not made silently.
- This document is updated at the end of each completed phase/sub-phase — status table
  (Section 26), Known Issues (Section 24), and Architecture Decisions (Section 23) are
  the three sections expected to change most often.

## 26. Change Log / Status

### Change Log

- **2026-08-24** — Document created (N1.0). Reflects state after N0 (complete) and N1.2
  (inspection complete, reviewed and approved). Gradle daemon JVM configuration
  inspected and explicitly left unchanged pending N1.3.
- **2026-08-24** — N1.1 (Pixel 7a AVD) recorded COMPLETE. N1.3 (Environment/Server
  Configuration) implemented and recorded COMPLETE: `BuildConfig.BASE_URL` added via
  existing `debug`/`release` build types in `app/build.gradle.kts`
  (`debug` = `http://10.0.2.2:8080/`, `release` = `https://app.myconnect.sbs/`); no
  networking code consumes it yet (deferred to N1.6).
- **2026-08-24** — N1.4 (Required Android Permissions) implemented and recorded
  COMPLETE: `android.permission.INTERNET` added to `AndroidManifest.xml` (see Section
  9a). All other permissions (network-state, audio, Bluetooth, notifications, camera,
  media, telecom, location, contacts) deliberately deferred to the phase that actually
  implements the feature needing them.
- **2026-08-24** — N1.5 (Hilt Foundation) implemented and recorded COMPLETE: Hilt
  `2.60.1` + KSP `2.2.10-2.0.2` added; `ConnectXApplication` (`@HiltAndroidApp`) created
  and registered in the manifest; `@AndroidEntryPoint` added to `MainActivity`. Hit and
  resolved (with explicit user approval before applying the fix) a real upstream
  incompatibility between KSP and AGP 9.3.2's built-in-Kotlin feature — see Section 9b
  and Section 24. No networking/repository/ViewModel code added; Hilt's dependency graph
  is currently empty aside from its two entry points.
- **2026-08-24** — N1.6 (Retrofit + OkHttp Foundation) implemented and recorded
  COMPLETE: Retrofit `3.0.0` and OkHttp `5.4.0` added; `core/network/NetworkModule.kt`
  created, providing a bare `OkHttpClient` and a `Retrofit` instance (consuming
  `BuildConfig.BASE_URL` directly, no hardcoded URL anywhere in source) as Hilt
  `@Provides @Singleton` bindings in `SingletonComponent`. No interceptors, no JSON
  converter (deliberately deferred to N2 — no serialization library existed and none was
  added), no API interfaces, no repositories, no HTTP request made. Verified via
  successful Hilt/KSP code generation and a clean app launch on the Pixel 7a emulator.
- **2026-08-24** — N1.7 (Navigation Foundation) implemented and recorded COMPLETE.
  **Correction:** a prior report claimed N1.7 was already complete, but direct
  inspection at the start of the N1.8 request found it had never actually been built (no
  navigation dependency, no `navigation/` package, `MainActivity` unchanged since N1.5) —
  this document's own status table still read `N1.7 NOT STARTED` at that point too. Flagged
  to the user before proceeding; N1.7 was then implemented for real: Navigation Compose
  `2.9.7` added, `navigation/ConnectXNavHost.kt` created with a single `HOME` placeholder
  route hosting the existing `Greeting` composable, `MainActivity` wired to render
  `ConnectXNavHost` instead of `Greeting` directly. See Section 9d.
- **2026-08-24** — N1.7 review correction: the approved scope actually called for a
  minimal **two-destination** flow (to verify navigation itself, not just host one
  screen). Added a second temporary `NAVIGATION_TEST` route with a button on `HOME` to
  navigate to it and a "Back" button to return. Manually verified the full
  HOME → navigation_test → HOME round trip on the Pixel 7a emulator via ADB tap input
  and UI-hierarchy dumps (not just compilation) — no crashes. No changes made to Hilt,
  Retrofit, OkHttp, BuildConfig, or any later-phase architecture. See Section 9d.
- **2026-08-24** — N1.8 (ConnectX UI Foundation) implemented and recorded COMPLETE: a
  full Compose design system (colors, typography, spacing, shapes, extended theme
  tokens) derived from direct inspection of the React frontend's Tailwind
  config/`index.css`/component classes (violet/indigo brand, emerald/amber/rose
  semantics, light/dark surface scale, `rounded-xl`/`rounded-2xl` radii, border-over-shadow
  language); 5 reusable components (`ConnectXButton`, `ConnectXTextField`, `ConnectXCard`,
  `ConnectXAvatar`, plus loading/empty/error state views); a temporary
  `DesignShowcaseScreen` reachable via a new `DESIGN_SHOWCASE` route (N1.7's `HOME`/
  `NAVIGATION_TEST` routes left untouched). Added `material-icons-core` (first-party
  Compose icon set) after discovering it wasn't actually on the compile classpath despite
  Gradle-cache presence; explicitly avoided `material-icons-extended` by swapping 4
  icons for core-set equivalents. Two visual decisions flagged as newly-native rather than
  React-derived (font family, light-mode received-bubble color) — see Section 24.
  Verified via full manual walkthrough on the Pixel 7a emulator (light theme, dark theme
  toggle, all showcase sections, scrolling, back-stack) with screenshots — no crashes.
- **2026-08-24** — N1.9 (Build + Emulator Verification) completed and recorded COMPLETE.
  No source-code changes were required. Re-inspected the full repository state directly
  (not trusting prior reports) and confirmed it matches Sections 9a-9e exactly. Ran
  `./gradlew clean` then `./gradlew assembleDebug` -> BUILD SUCCESSFUL, 42/42 tasks
  freshly executed; only warning was the already-known `disallowKotlinSourceSets`
  notice. Fresh uninstall+install on the Pixel 7a emulator, full HOME -> Navigation Test
  -> Back -> HOME flow re-verified via real ADB tap input, full Design System Showcase
  re-verified (all sections, light theme, dark theme toggle) via screenshots, zero
  crashes throughout. Confirmed `BuildConfig.BASE_URL`/`app.myconnect.sbs`/`10.0.2.2`
  appear in exactly one file (`NetworkModule.kt`) across the whole source tree — no
  network request made. Vivo X200 FE: NOT VERIFIED / DEVICE UNAVAILABLE (only the
  emulator was reachable in this environment). See Section 9f.
- **2026-08-24** — N1.10 (Git Baseline) completed: the verified N1 Android Foundation
  committed as a single baseline commit on `phase-6-e2ee-media-stable` — the first
  Android commit in this repository. Pre-existing, unrelated backend/frontend changes
  (modified files + untracked `call`/`calling` feature directories) discovered in the
  working tree were explicitly reported and left completely untouched, staged separately
  from — never mixed into — the Android baseline. **N1 — Android Foundation: COMPLETE.**
  N2 remains NOT STARTED.

### 9g. N1.10 — Git Baseline

**N1.10 — Git Baseline: COMPLETE. N1 — Android Foundation: COMPLETE.**

The verified N1 Android Foundation (N1.1-N1.9) was committed as a single baseline commit
on branch `phase-6-e2ee-media-stable` — the first Android-related commit in this
repository's history. Exact commit hash/message: see this session's final report and
`git log` (not duplicated here to avoid this file needing a follow-up edit purely to
record a hash, which would mean either a second N1.10 commit or rewriting the baseline
commit — both explicitly avoided per the approved N1.10 scope).

**Pre-commit discovery — reported before any git operation:** the working tree at the
start of N1.10 contained substantial changes with **no relation to the Android N1
work**: modified files in `connectx-backend/` (`application.yml`,
`application-test.yml`) and `connectx-frontend/` (`App.tsx`, `ChatHeader.tsx`,
`ChatScreen.tsx`, `ContactInfoDrawer.tsx`, `main.tsx`, `types/index.ts`,
`notificationSound.ts`, `WebSocketClient.ts`), plus untracked backend/frontend "calling"
feature directories (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`) — all pre-existing, unrelated in-progress work
from before this Android-focused effort began. **None of this was staged, committed,
modified, or discarded.** The baseline commit was scoped with explicit pathspecs
(`git add connectx-android/ docs/CONNECTX_ANDROID_DEVELOPMENT.md`), never a broad
`git add -A`/`git add .`, and verified afterward (`git diff --cached --name-only`) to
contain only those two paths before committing.

**`gradle/gradle-daemon-jvm.properties`** — previously flagged (Sections 8, 22, 24) as
untracked with no `.gitignore` coverage — was deliberately **included** in the baseline.
It is a legitimate, reproducibility-relevant Gradle daemon JVM toolchain pin (the same
category as `gradle-wrapper.properties`, already tracked), not a generated/personal
artifact, so tracking it deliberately (rather than gitignoring it) is the correct
resolution of that long-standing open item.

**Verified before committing:** no build artifacts (`app/build/`, `.gradle/`,
`local.properties`, APKs) were tracked or staged — confirmed via `git ls-files` and
`git check-ignore -v`, consistent with the correct `.gitignore` coverage already
verified back in N1.2.

### Phase Status

```
N0            COMPLETE
N1            COMPLETE  (Android Foundation — see N1.0-N1.10 below)
N1.0          COMPLETE
N1.1          COMPLETE
N1.2          COMPLETE
N1.3          COMPLETE
N1.4          COMPLETE
N1.5          COMPLETE
N1.6          COMPLETE
N1.7          COMPLETE
N1.8          COMPLETE
N1.9          COMPLETE
N1.10         COMPLETE
N2            NOT STARTED
N3            NOT STARTED
N4            NOT STARTED
N5            NOT STARTED
N6            NOT STARTED
N7            NOT STARTED
N8            NOT STARTED
N9            NOT STARTED
N10           NOT STARTED
N11           NOT STARTED
N12           NOT STARTED
N13           NOT STARTED
N14           NOT STARTED
```

### Explicitly NOT YET implemented (as of this document)

Room, DataStore. (Hilt was added in N1.5 — Section 9b; Retrofit + OkHttp in N1.6 —
Section 9c; Navigation Compose in N1.7 — Section 9d; the design system/UI foundation in
N1.8 — Section 9e. No API interfaces, DTOs, repositories, or ViewModels exist yet, no
HTTP request has been made, and no real ConnectX application screen — login, home,
chat, contacts, groups, profile, settings, or calling — has been built.)

Authentication, messaging, WebSocket/STOMP client, E2EE/crypto, FCM/push notifications,
WebRTC/calling — no code for any of these exists anywhere in `connectx-android`.
