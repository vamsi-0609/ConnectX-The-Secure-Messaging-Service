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
| JSON serialization | kotlinx.serialization (`kotlinx-serialization-json` + Retrofit's official `converter-kotlinx-serialization`) | 1.11.0 / 3.0.0 |

**PLANNED (not yet added — introduced phase by phase):**

| Concern | Planned choice | Introduced in |
|---|---|---|
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

### 9g. N1.10 — Git Baseline

**N1.10 — Git Baseline: COMPLETE. N1 — Android Foundation: COMPLETE.**

The verified N1 Android Foundation (N1.1-N1.9) was committed as a single baseline commit
on branch `phase-6-e2ee-media-stable` — the first Android-related commit in this
repository's history, and subsequently **pushed to `origin/phase-6-e2ee-media-stable`**.

- Commit hash: `e68130d7e3ef41d4e471d2345faff03db668cf34`
- Commit message: `feat(android): establish ConnectX N1 foundation`
- Remote status after push: local and `origin/phase-6-e2ee-media-stable` in sync.

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
contain only those two paths before committing. These same unrelated changes remain
present, unstaged/untracked, and untouched as of N2.0 as well.

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
- **2026-08-24** — N2.0 (Backend/API Inspection) completed. Full read-only inventory of
  all 84 existing REST endpoints across 10 controller groups, JWT/auth architecture,
  global exception handling, CORS, WebSocket/STOMP config, and (encountered
  incidentally, read-only) the untracked call-signaling feature. No Android, backend, or
  frontend source code was modified; no network requests were made; pre-existing PWA
  calling changes confirmed untouched. See Section 27. N2.1+ not yet begun.
- **2026-08-24** — N2.1 (Android API Contract & JSON Serialization Foundation)
  completed: kotlinx.serialization (`kotlinx-serialization-json` 1.11.0) selected and
  wired into Retrofit via its official `converter-kotlinx-serialization` 3.0.0
  converter; minimal `AuthApi` (register + login only) and matching request/response
  DTOs created, modeled field-for-field against directly re-read backend source (not
  the N2.0 summary); shared `ApiResponse`/`ErrorResponse` wrapper models added under
  `core/network/model/`. 5 JVM-only serialization contract tests pass (0 network calls).
  Clean build, Pixel 7a re-verified (navigation + showcase still work, no crash). No
  authentication behavior, token storage, or HTTP requests implemented — all deferred to
  N3. PWA calling changes confirmed untouched. See Section 28.
- **2026-08-24** — N2.2 (User API Contract) completed. Re-verified the actual User
  backend source and found a real discrepancy vs. the N2.0 summary: 11 User endpoints
  exist (10 in `UserController` + 1 in `ProfileImageController`), not 10 as previously
  reported — corrected in Section 29. Full `UserApi` interface created (all 11
  endpoints), reusing N2.1's `UserDto`/`ApiResponse` rather than duplicating them; new
  `PublicUserDto`, `UserProfileUpdateDto`, `UserIdentityKeyDto`, and two typed
  request models replacing the backend's raw `Map<String,String>` bodies. 6 new
  serialization tests pass (11/11 total combined with N2.1). Clean build; Pixel 7a
  re-verified (HOME, Navigation Test flow, Design System Showcase all still working).
  Bonus: the physical Vivo X200 FE was unexpectedly reachable via wireless ADB this
  session and was also verified working — first time this device has been reachable in
  the project's history. No authentication, HTTP requests, or UI/repository/ViewModel
  code added. PWA calling changes confirmed untouched. See Section 29.
- **2026-08-24** — N2.3 (Connection + Block API Contract) completed. Re-verified both
  backend controllers directly; unlike N2.2, the N2.0 counts were confirmed accurate
  this time (Connection=8, Block=3, no discrepancy). Full `ConnectionApi` (8 endpoints)
  and separate `BlockApi` (3 endpoints) created; all four DTOs
  (`SendConnectionRequestDto`, `ConnectionRequestDto`, `UserConnectionDto`,
  `UserBlockDto`) modeled as new, non-reused models after confirming they're
  genuinely flat shapes distinct from `PublicUserDto`/`UserDto`. 8 new serialization
  tests pass (20/20 total). Clean build; both Pixel 7a and the still-reachable Vivo
  X200 FE re-verified (HOME, navigation flow, Design Showcase, light/dark toggle — no
  crashes on either device). No authentication, WebSocket, HTTP requests, or
  UI/repository/ViewModel code added. PWA calling changes confirmed untouched. See
  Section 30.
- **2026-08-24** — N2.4 (Conversation API Contract) completed. Re-verified
  `ConversationController` directly and confirmed the N2.0 count (13 endpoints) was
  accurate, no discrepancy. Full `ConversationApi` created (13 endpoints); `PublicUserDto`
  genuinely reused (first real cross-phase model reuse in N2) since
  `ConversationMemberDto.user` embeds it verbatim; confirmed no pagination exists on
  `GET /conversations` (plain `List`); confirmed `ConversationDto`'s last-message fields
  are flat scalars, not a nested message object, so no `MessageApi`/message models were
  introduced. Enum-like fields (`type`, `role`, `lastMessageType`) kept as plain
  `String`, matching the established convention. 6 new serialization tests pass (26/26
  total). Clean build; both Pixel 7a and the Vivo X200 FE re-verified (HOME, navigation
  flow, Design Showcase, light/dark toggle — no crashes on either device). No
  authentication, WebSocket, HTTP requests, or UI/repository/ViewModel code added. PWA
  calling changes confirmed untouched. See Section 31.
- **2026-08-24** — N2.5 (Message API Contract) completed. Re-verified
  `MessageController` directly and found a **second real discrepancy**: 11 endpoints
  exist, not the 10 reported by N2.0 (the first was N2.2's User undercount). Full
  `MessageApi` created (11 endpoints, the largest API group so far); `MessageDto` (32
  fields) modeled exactly, confirming reply-to fields are flat scalars (not a
  self-referential nested message) and media fields (mediaId/mimeType/fileSizeBytes/
  mediaNonce) are scalar fields on MessageDto itself, so no MediaApi was created.
  Confirmed the backend's custom cursor-based `PagedMessageResponseDto` pagination
  (not Spring Page, not offset/limit) and modeled it exactly. 7 new serialization
  tests pass (33/33 total). Clean build; both Pixel 7a and the Vivo X200 FE
  re-verified (HOME, navigation flow, Design Showcase, light/dark toggle — no crashes
  on either device). No authentication, WebSocket, E2EE/local-data, MediaApi, HTTP
  requests, or UI/repository/ViewModel code added. PWA calling changes confirmed
  untouched. See Section 32.
- **2026-08-24** — N2.6 (Media API Contract) completed. Re-verified `MediaController`
  directly and confirmed the N2.0 count (2 endpoints) was accurate, no discrepancy —
  the third group checked (after User and Message) and the first with nothing to
  correct, underscoring that per-phase re-verification (not selective trust) is the
  right standing rule. `MediaApi` created with 2 methods: multipart upload (no JSON
  request body — optional fields are genuine multipart form parts, modeled as
  `RequestBody?`, not a serialized DTO) and a raw-binary streaming download
  (`ResponseBody`, never `ApiResponse`-wrapped — same pattern as N2.2's
  `getProfileImage`). `MediaUploadResponseDto` modeled as a new, non-reused flat
  shape. 4 new contract tests pass (37/37 total), including two network-free
  `ResponseBody` checks for the binary-download representation. Clean build; both
  Pixel 7a and the Vivo X200 FE re-verified (HOME, navigation flow, Design Showcase,
  light/dark toggle — no crashes on either device). No authentication, WebSocket,
  E2EE, file picker, upload/download manager, media UI, or HTTP requests added. PWA
  calling changes confirmed untouched. See Section 33.
- **2026-08-25** — N2.7 (Device + Push + Group API Contract, the final N2 sub-phase)
  completed. Re-verified `DeviceController` (5), `PushNotificationController` (3), and
  all five current group controllers (`GroupController` 8, `GroupMembershipController`
  4, `GroupInvitationController` 6, `GroupImageController` 1, `GroupKeyController` 4 —
  23 total) directly against source; all three counts matched the N2.0 summary exactly,
  no discrepancies this phase. `DeviceApi` (5 methods), `PushApi` (3 methods — Web
  Push/VAPID, not FCM; `getVapidPublicKey` modeled as `Map<String, String>`, not a
  single-field DTO; subscribe/unsubscribe as `ApiResponse<Unit>`), and `GroupApi` (23
  methods spanning all five controllers in one interface) all created. Reused
  `ConversationMemberDto` for `getGroupMembers`/`changeRole` (backend returns the exact
  same class) and `PublicUserDto` inside `GroupInvitationDto` — no unnecessary
  duplicate models. 13 new response/request models created for Device/Push/Group; all
  policy/role/status enums (`whoCanInvite`, `whoCanSendMessages`,
  `whoCanEditGroupInfo`, `role`, invitation `status`) kept as plain Strings, continuing
  the established convention. Group avatar/image download modeled as raw
  `ResponseBody` + `@Streaming`, same pattern as N2.2/N2.6. 26 new contract tests pass
  (63/63 total). Clean `assembleDebug` build. Neither the Pixel 7a nor the Vivo X200 FE
  were reachable via adb this session (only an emulator was attached) — both reported
  NOT VERIFIED / DEVICE UNAVAILABLE per the phase's own fallback instructions, no
  device configuration was modified to force availability. No authentication, FCM,
  WebSocket, E2EE, local data, or Device/Push/Group UI added; zero HTTP requests made.
  PWA calling changes confirmed untouched. **N2 (Backend Connectivity — all REST API
  contracts) is now COMPLETE.** See Section 34.
- **2026-08-25** — Git checkpoint commit `4f23b6b` created for the completed N1 +
  N2 work (`feat(android): complete N1 foundation and N2 REST contracts`, 68
  files, local only — not pushed). N3.0 (Authentication Architecture & Backend
  Contract Re-verification) completed as an inspection/design-only phase — no
  source code was modified. Re-read the current backend auth source directly
  (`AuthController`, `AuthService`, `JwtTokenProvider`, `JwtAuthenticationFilter`,
  `SecurityConfig`, `GlobalExceptionHandler`, OTP DTOs) and found **7 current auth
  endpoints**, not the 2 modeled by N2.1's deliberately-minimal `AuthApi` —
  register/login (modeled) plus logout/refresh/forgot-password (request-otp,
  verify-otp, reset-password), all pre-existing and undocumented as a full
  inventory until now. Confirmed Section 27's JWT facts still hold exactly:
  HS256, access token 24h / refresh token 7 days (both from `application.yml`,
  overridable via env), access and refresh tokens **structurally identical**
  (same claims, no `typ` claim — a real architectural quirk, not an Android
  limitation), and `POST /auth/refresh` performs blind rotation with **no
  server-side refresh-token store/revocation** — `logout()` only calls
  `SecurityContextHolder.clearContext()`, which is a no-op for stateless
  per-request JWT auth (logout is effectively client-side-only on this
  backend). Designed (not implemented) the full Android auth architecture:
  `AuthRepository` + `SessionManager` as the only two new layers, EncryptedSharedPreferences
  (Jetpack Security) as the token-storage mechanism, a single `AuthInterceptor` +
  `Authenticator` pair for OkHttp with a `Mutex`-guarded single-flight refresh to
  prevent concurrent-401 refresh storms, and a `SessionState` sealed class
  (`Unknown/Authenticated/Unauthenticated`) read once at app startup. Zero
  authentication network requests made. PWA calling changes confirmed untouched.
  See Section 35.
- **2026-08-25** — N3.1 (Secure Token & Session Storage) completed. Added
  `androidx.security:security-crypto` 1.1.0 (Jetpack Security was not previously
  present, confirmed via `libs.versions.toml`) and built `TokenStorage`
  (interface) + `EncryptedTokenStorage` (impl, `EncryptedSharedPreferences` +
  Keystore-backed `MasterKey`, file `connectx_secure_auth_prefs`, keys
  `access_token`/`refresh_token`, synchronous `commit()` not `apply()` for
  read-after-write safety) under `data/local/auth/`, bound via a new minimal
  `AuthStorageModule` (kept separate from `NetworkModule` on purpose). Added a
  small storage-specific `TokenPair` model rather than coupling storage to the
  network `AuthResponse` DTO. Built `SessionState` (`Unknown`/`Authenticated`/
  `Unauthenticated`, no extra states) and `SessionManager`
  (`StateFlow<SessionState>`, `restoreSession`/`setAuthenticated`/
  `clearSession`) under `core/session/`. Partial single-token state is detected
  and cleared outright, never "repaired," per N3.0's decision. Added minimal,
  targeted backup/data-extraction exclusion rules for the new preferences file
  only (`backup_rules.xml`, `data_extraction_rules.xml`) — no broader
  backup-policy change. 7 new `SessionManager` unit tests (a hand-written JVM
  `TokenStorage` fake, no mocking framework, no Android dependency) — 70/70
  total tests pass. Clean `assembleDebug` (Hilt graph compiles with the new
  module). Verified on both the Pixel 7a emulator and the Vivo device (V2503,
  reachable via wireless ADB this session) — fresh install, launch, live PID,
  zero `FATAL`/`AndroidRuntime`/`Exception` in logcat on either. Real
  encrypted-storage read/write was NOT separately verified via an instrumented
  test in this phase (see Section 36's explicit note). Zero authentication
  network requests, no UI/navigation change, no backend/frontend files touched.
  PWA calling changes confirmed untouched. See Section 36.
- **2026-08-25** — N3.2 (Authentication Repository) completed. Re-verified
  `AuthController`/`AuthService` directly: both `/auth/register` and
  `/auth/login` return a real, immediately-usable access/refresh token pair —
  so registration establishes an authenticated session exactly like login, not
  a fabricated assumption. Built `AuthRepository`/`AuthRepositoryImpl` (+
  minimal `AuthRepositoryModule` Hilt binding) in `data/remote/auth/`, wiring
  `AuthApi → AuthRepository → SessionManager` (never touching `TokenStorage`
  directly, per the approved layering). Added a flat 5-variant `AuthResult`
  sealed interface (`Success/ApiError/NetworkError/InvalidResponse/
  UnexpectedError`) — no use-case/data-source/manager classes created, none
  proved necessary. Blank/missing access or refresh tokens in an otherwise
  "successful" response are rejected before anything is persisted; a
  `HttpException` is parsed back into `ErrorResponse` fields using the
  already-existing `Json` bean; `IOException` (no connectivity) and
  `SerializationException` (malformed body) are kept distinct from each other
  and from a backend API error. Session state only flips to `Authenticated`
  after `SessionManager.setAuthenticated` completes — a persistence failure
  (simulated in tests) leaves the session exactly where it was. `logout()`
  exposed as a thin local-only delegate to `SessionManager.clearSession()` —
  still no backend call (N3.0's finding that `/auth/logout` has no
  server-side effect still holds). 9 new repository tests (hand-written
  `FakeAuthApi`/`FakeTokenStorage`, a real `SessionManager` on top of the
  fake storage, zero network/Retrofit/OkHttp calls) — 79/79 total tests pass.
  Clean `assembleDebug`. Verified on both the Pixel 7a emulator and the Vivo
  device (V2503) — fresh install, launch, live PID, zero crash lines in
  logcat on either; no login/register UI exists yet to click through, so
  verification was launch-only, same as N3.1. Zero authentication network
  requests made. No UI/navigation/backend/frontend files touched. PWA calling
  changes confirmed untouched. See Section 37.
- **2026-08-25** — N3.3 (Authenticated Network Layer) completed. Split
  `NetworkModule` into two independent `OkHttpClient`/`Retrofit` pairs instead
  of one shared client, to break a real dependency cycle
  (`OkHttpClient → Authenticator → AuthRepository → AuthApi → Retrofit →
  OkHttpClient`) identified during design: an `@UnauthenticatedClient`
  pair carries `AuthApi` only (register/login/**refresh**, all `permitAll()`
  on the backend), and an `@AuthenticatedClient` pair (new `AuthInterceptor` +
  `TokenAuthenticator` attached) carries every other API. This one design
  choice also structurally prevents a bad-credentials 401 from `login` from
  ever reaching `TokenAuthenticator`, and prevents the refresh call itself
  from recursively re-triggering it — both for free, not via a hardcoded
  path-exclusion list. Added `RefreshTokenRequest` + `AuthApi.refresh(...)` +
  `AuthRepository.refresh(refreshToken: String)` (re-verified the backend
  contract directly first; reuses the existing `AuthResponse`, no duplicate
  token-response model). `TokenAuthenticator` implements single-flight refresh
  (a `Mutex` + compare-current-token-before-refreshing check, `runBlocking`
  bridging into the suspending `AuthRepository.refresh`), a hard retry limit
  (`priorResponse` chain depth ≥ 2 → give up), and distinguishes credential
  rejection (`ApiError`/`InvalidResponse`/`UnexpectedError` → `AuthRepository.logout()`
  clears the session) from a network failure during refresh
  (`NetworkError` → nothing is cleared, since the stored credentials may still
  be perfectly valid). Added `com.squareup.okhttp3:mockwebserver3` 5.4.0
  (matching the project's existing OkHttp version) as a test-only dependency —
  genuinely needed to test real OkHttp-layer header/401/retry/concurrency
  behavior, which hand-written fakes can't exercise. 24 new tests (2
  repository-level refresh tests + 3 `AuthInterceptorTest` + 9
  `TokenAuthenticatorTest`, including a 5-thread concurrent-401 test proving
  exactly one refresh call, and two tests proving a 403/500 never invokes the
  authenticator at all) — 93/93 total tests pass, re-run 3x with no flake.
  Clean `assembleDebug` (Hilt graph resolves with no circular dependency).
  Verified on both the Pixel 7a emulator and the Vivo device (V2503) — fresh
  install, launch, live PID, zero crash lines in logcat on either. Zero real
  authentication/network requests made during verification — all interceptor/
  authenticator tests run against a local `MockWebServer`, never the live
  backend. No UI/navigation/backend/frontend files touched. PWA calling
  changes confirmed untouched. See Section 38.
- **2026-08-25** — N3.4 (Authentication Session Integration) completed.
  Inspected `SessionManager`/`TokenStorage`/`EncryptedTokenStorage` directly
  and found the N3.0-N3.3 architecture already correct and complete — no
  redesign, no defect found. Added exactly one production change:
  `ConnectXApplication` now field-injects the same `@Singleton SessionManager`
  every other layer already depends on and calls `restoreSession()` once at
  startup, launched on a small `Dispatchers.IO`-backed `applicationScope`
  (not the main thread — `EncryptedTokenStorage`'s constructor and every read
  do real Keystore/disk I/O, confirmed by direct inspection) rather than
  reaching for AndroidX Startup or a new DI-provided scope for a single call
  site. No duplicate session state was created anywhere — `SessionManager`
  remains the sole source of truth. Verified (not changed): logout stays
  local-only, partial-token state is still rejected outright, and
  `TokenAuthenticator`'s credential-rejection-vs-network-failure distinction
  from N3.3 still holds. Added 4 new tests in a new `SessionIntegrationTest`
  proving things N3.1-N3.3's tests didn't: the REAL `SessionManager` observed
  through the fully-wired real stack (`AuthRepositoryImpl` +
  `TokenAuthenticator` + `AuthInterceptor` against a `MockWebServer`, not a
  fake `AuthRepository`) for both a successful and a rejected refresh, the
  same for a refresh network failure, and a second `SessionManager` instance
  restoring a session a first, now-discarded instance had persisted
  (simulating process death/recreation). No N3.1-N3.3 test was duplicated.
  97/97 total tests pass, re-run twice more with no flake. Clean
  `assembleDebug`. Verified on both the Pixel 7a emulator and the Vivo device
  (V2503) — fresh install, launch, live PID, `ActivityTaskManager: Displayed`
  confirmed, zero `FATAL`/`AndroidRuntime` lines on either (one benign,
  differently-tagged `WindowManager` warning from a rapid reinstall/relaunch
  cycle was investigated and ruled out as unrelated to this app). No real
  authentication/network requests made. Existing HOME/Navigation
  Test/Design Showcase/theme untouched — no UI, navigation graph, or
  observation screen was added, consistent with N3.1-N3.3's launch-only
  device-verification precedent. No backend/frontend files touched. PWA
  calling changes confirmed untouched. See Section 39.
- **2026-08-25** — N3.5 (Authentication Security & App Protection) completed
  as a security-audit phase. Re-inspected the entire N3.0-N3.4 auth stack plus
  a full source-tree grep for logging/token-exposure patterns directly (not
  from prior reports) and found it already clean: no `Log.*`/`println`, no
  `HttpLoggingInterceptor`, no clipboard/Intent/SavedStateHandle/
  contentDescription token exposure anywhere. One genuine, previously
  undiscovered gap was found and fixed: no network security config or
  `usesCleartextTraffic` override existed anywhere in the project, so under
  Android's default policy (targetSdk 37 ≥ 28 disallows cleartext), the debug
  build's plain-HTTP `BASE_URL` (`http://10.0.2.2:8080/`) would fail outright
  with "CLEARTEXT communication not permitted" the first time a real
  authenticated request was attempted — never previously surfaced since every
  N2/N3 phase deliberately made zero real HTTP requests. Fixed with a
  debug-source-set-only Network Security Config
  (`app/src/debug/res/xml/network_security_config_debug.xml` +
  `app/src/debug/AndroidManifest.xml`) permitting cleartext to exactly
  `10.0.2.2` — confirmed present in the merged debug manifest and, just as
  importantly, confirmed **absent** from the merged release manifest (`grep`
  found zero matches), so release's HTTPS-only posture is completely
  unaffected. Added 2 new behavioral tests (`AuthSecurityTest`, real
  `MockWebServer`) proving properties no N3.1-N3.4 test explicitly covered:
  `AuthInterceptor` attaches only the access token even when a refresh token
  is simultaneously stored (refresh token value never appears in any header),
  and a live refresh call built exactly like `NetworkModule`'s real
  unauthenticated client sends the refresh token only in the JSON body with
  no `Authorization` header at all. FLAG_SECURE deliberately deferred (no
  auth UI exists yet to apply it to; a blanket application-level flag would
  needlessly affect unrelated future screens) — documented as an explicit
  open decision for N3.6+/N4, not silently dropped. 99/99 total tests pass.
  Clean `assembleDebug` AND `assembleRelease` (both verified this phase, the
  latter specifically to confirm the release manifest excludes the new debug
  network security config). Verified on the Pixel 7a emulator — fresh
  install, launch, live PID, `ActivityTaskManager: Displayed` confirmed, zero
  crash lines. Vivo X200 FE **NOT VERIFIED / DEVICE UNAVAILABLE** this
  session (not reachable via `adb devices`, including a daemon restart — no
  device configuration was changed to force availability). No real
  authentication/network requests made. No UI/backend/frontend files
  touched. PWA calling changes confirmed untouched. See Section 40.
- **2026-08-25** — N3.6 (Complete Remaining Authentication Contracts &
  Account-Recovery Operations) completed. Re-verified `AuthController`/
  `AuthService` directly: the auth endpoint family is still exactly 7,
  unchanged — `register`/`login`/`refresh` already modeled; `logout` remains
  deliberately local-only (no server-side effect to call, per N3.0); the
  three forgot-password endpoints (`request-otp`/`verify-otp`/`reset-password`)
  were the only remaining gap, confirmed to return `ApiResponse<String>` (a
  plain confirmation message), never `AuthResponse`/tokens. Modeled
  `ForgotPasswordRequestDto`/`VerifyOtpRequestDto`/`ResetPasswordRequestDto`
  (matching backend field names exactly) and extended `AuthApi` with the
  three endpoints — all still bound to the same unauthenticated client as
  register/login/refresh. Since none of the three operations authenticate,
  reusing `AuthResult` (hardcoded to `Success(user: UserDto)`) would have
  been wrong — added a small parallel `AccountRecoveryResult` sealed
  interface instead, mirroring `AuthResult`'s four failure categories exactly
  but with a plain-message `Success`; extracted the shared
  `HttpException`-to-`ErrorResponse` parsing logic (previously only inside
  `authenticate()`) into one private `parseApiError()` helper reused by both
  the existing `authenticate()` and the new `recover()` flow, avoiding
  duplicating that logic a second time. `AuthRepository` gained
  `requestPasswordResetOtp`/`verifyPasswordResetOtp`/`resetPassword` —
  confirmed by direct test that none of the three ever call
  `SessionManager`, including when an existing authenticated session is
  already active (a dedicated regression test asserts a password-reset call
  leaves a pre-existing `Authenticated` session completely untouched). No
  OTP or password value is cached, logged, or held beyond the single request
  object's lifetime — confirmed by inspection, no new persistence was added.
  Username-first identity re-confirmed and preserved throughout: no
  phone-number field, no Android Contacts permission/API, nothing beyond the
  backend's actual email-based recovery contract was introduced. 11 new
  tests (extending `AuthRepositoryImplTest`, same fixture/pattern as
  existing login/register/refresh tests — no new test infrastructure) —
  110/110 total tests pass. Clean `assembleDebug`. Verified on the Pixel 7a
  emulator — fresh install, launch, live PID, `Displayed` confirmed, zero
  crash lines. Vivo X200 FE NOT VERIFIED / DEVICE UNAVAILABLE this session
  (not reachable, including a daemon restart). Zero real authentication
  requests made. No UI/backend/frontend files touched. PWA calling changes
  confirmed untouched. See Section 41.
- **2026-08-25** — N3.7 (Authentication Lifecycle & Expired-Session
  Verification) completed as a verification-only phase. Re-inspected the
  entire N3.0-N3.6 auth stack directly and confirmed it matched the N3.6
  report exactly — no discrepancy, no redesign. Mapped all 16 required test
  scenarios against the existing 100+ auth tests one by one and found 15
  already genuinely covered without duplication (access-token attachment,
  401→refresh→retry, token rotation, single-flight concurrency, retry-limit,
  credential-rejection vs. network-failure, logout, process recreation,
  partial-token rejection, recovery session-neutrality, refresh-token-never-
  in-header). Found exactly one real gap: no existing test ever exercised a
  genuine JSON-parsing failure — every prior refresh test used a
  hand-written fake returning ready-made Kotlin objects, never real bytes
  through the kotlinx.serialization converter, so `AuthRepositoryImpl`'s
  `catch (e: SerializationException)` path had literally never executed in
  any test. Added one new test (`SessionIntegrationTest`) using a REAL
  `AuthApi` built via Retrofit against a second `MockWebServer` returning
  deliberately malformed JSON, proving the existing (unmodified) design
  already handles it correctly: `TokenAuthenticator` groups
  `UnexpectedError` with credential-rejection (per its own N3.3 doc
  comment), producing a clean, fully-consistent `Unauthenticated` state —
  both tokens atomically `null`, never a corrupted half-written pair. No
  production code was changed — the audit found the existing behavior
  already correct, only untested. 111/111 total tests pass, re-run twice
  more with no flake. Clean `assembleDebug`. Verified on the Pixel 7a
  emulator — fresh install, launch, live PID, `Displayed` confirmed, zero
  crash lines. Vivo X200 FE NOT VERIFIED / DEVICE UNAVAILABLE this session.
  Zero real authentication requests made. No UI/backend/frontend files
  touched. PWA calling changes confirmed untouched. See Section 42.
- **2026-08-25** — N3.8 (Authentication Phase Git Checkpoint & Final
  Verification) completed — **N3 (Authentication) is now COMPLETE.**
  Re-inspected the full Android diff one final time: exactly the N3.0-N3.7
  authentication implementation and tests, nothing else — no accidental
  N4/UI/messaging/WebSocket/FCM/WebRTC/calling/E2EE/phone-contact code found
  anywhere. Ran the complete verification gate one more time: 111/111 tests
  pass (re-confirmed, no change since N3.7), `assembleDebug` AND
  `assembleRelease` both BUILD SUCCESSFUL. Went beyond the usual launch-only
  device check for this final gate — live-interacted with the Pixel 7a
  emulator via `uiautomator`/`input tap`: HOME → Navigation Test → Back →
  Design Showcase → dark theme → light theme, all confirmed rendering
  correctly via UI dumps and screenshots, same process (PID) throughout, zero
  crash lines in logcat across the whole sequence. Vivo X200 FE NOT VERIFIED
  / DEVICE UNAVAILABLE this session. Final security regression audit (fresh
  greps, not reused from N3.5/N3.7) confirmed zero logging/HttpLoggingInterceptor/
  Contacts-or-phone-number code/clipboard/SavedStateHandle usage anywhere,
  backup exclusions still present, `AuthInterceptor` still never reads the
  refresh token. Staged and committed exactly the N3 Android implementation,
  tests, and this document — verified via `git diff --cached --name-only`
  before committing that zero backend/frontend/PWA-calling files were
  included. One commit created. See Section 43 for the full checkpoint
  record (commit hash, staged-file list, remote sync status) and the
  session's final report for the commit hash itself.

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
N2            COMPLETE  (Backend Connectivity — see N2.0 below)
N2.0          COMPLETE
N2.1          COMPLETE
N2.2          COMPLETE
N2.3          COMPLETE
N2.4          COMPLETE
N2.5          COMPLETE
N2.6          COMPLETE
N2.7          COMPLETE
N3            COMPLETE  (Authentication — see N3.0 below)
N3.0          COMPLETE
N3.1          COMPLETE
N3.2          COMPLETE
N3.3          COMPLETE
N3.4          COMPLETE
N3.5          COMPLETE
N3.6          COMPLETE
N3.7          COMPLETE
N3.8          COMPLETE
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
N1.8 — Section 9e; kotlinx.serialization + a minimal `AuthApi` contract in N2.1 —
Section 28. No repositories or ViewModels exist yet, no HTTP request has been made, and
no real ConnectX application screen — login, home, chat, contacts, groups, profile,
settings, or calling — has been built. Only the auth, User, Connection, Block,
Conversation, Message, and Media API contracts exist; every other API group — Device,
Push, Group — remains unimplemented.)

Authentication, messaging, WebSocket/STOMP client, E2EE/crypto, FCM/push notifications,
WebRTC/calling — no code for any of these exists anywhere in `connectx-android`.

## 27. N2.0 — Backend/API Inspection

**N2.0 — Backend/API Inspection: COMPLETE.** Inspection date: 2026-08-24. This section
is a factual inventory of the *existing* Spring Boot backend, produced before any
Android networking code is written. No Android, backend, or frontend source was
modified to produce it. Full per-endpoint detail lives in this session's N2.0 report
(request/response DTO field lists in particular are long); this section summarizes and
indexes that report so future phases (N2.1+) can be designed against it without
re-deriving it.

**Base REST prefix:** `/api/v1` (baked into each controller's `@RequestMapping`, not a
global Spring context-path). Server port `${PORT:8080}`. All success responses wrap in
`ApiResponse<T>` (`success:boolean, code:String, message:String, data:T, timestamp:String`);
all error responses use `ErrorResponse` (`timestamp, status:int, code:String, message:String, path:String`).
Two binary/raw endpoints exist (`GET /profile-images/{userId}`, `GET /group-images/{groupId}`,
`GET /media/{mediaId}`) that return `Resource` directly, not wrapped in `ApiResponse`.

**Controller groups found (10):** Auth (7 endpoints), User (10, incl. `ProfileImageController`),
Connection (8), Block (3), Conversation (13), Message (10), Media (2), Device (5), Push (3),
Group (23 across 5 controllers: `GroupController`, `GroupImageController`,
`GroupInvitationController`, `GroupKeyController`, `GroupMembershipController`) — **84
REST endpoints total**, all confirmed by direct controller/DTO source reads, none invented.

**Authentication architecture** (`common/security/JwtTokenProvider`, `JwtAuthenticationFilter`,
`config/SecurityConfig`): HS256 JWT, stateless. `Authorization: Bearer <token>` header, with a
`?token=` query-param fallback (used by `<img>`-style raw endpoints and originally added for
WebSocket handshake). Access token expiry `connectx.jwt.expiration-ms` (default 24h), refresh
token expiry `connectx.jwt.refresh-expiration-ms` (default 7 days). **Access and refresh
tokens are structurally identical JWTs** — no `typ`/`token_type` claim distinguishes them;
only the expiry duration differs. `POST /auth/refresh` performs rotation (mints a new
access+refresh pair) but does not track/whitelist refresh tokens, so there is no
server-side revocation. `permitAll()` paths: `/api/v1/auth/**`, `/api/v1/profile-images/**`,
`/api/v1/group-images/**`, `/ws/**`, `/h2-console/**`, `/favicon.ico`, `/error` — everything
else requires authentication. No `@PreAuthorize` annotations exist anywhere; all
authorization is enforced imperatively inside service classes.

**Error handling** (`common/exception/GlobalExceptionHandler`): `ApiException` (custom,
per-throw-site status+code+message, no fixed error-code catalog) → its own status/code;
`MethodArgumentNotValidException` → 400 `VALIDATION_ERROR`; `AuthenticationException` → 401
`UNAUTHORIZED`; `AccessDeniedException` → 403 `FORBIDDEN`; catch-all `Exception` → 500
`INTERNAL_SERVER_ERROR` (generic message only, real exception logged server-side, never
leaked to the client).

**CORS** (`config/CorsConfig`): effectively `allowedOriginPatterns("*")` with
`allowCredentials(true)` — a `connectx.cors.allowed-origins` property is read but never
actually applied (dead config). Irrelevant to a native Android client either way (no
browser-origin enforcement applies to a non-browser HTTP client).

**WebSocket/STOMP** (`config/WebSocketConfig`, `WebSocketAuthChannelInterceptor`):
endpoint `/ws`, registered both with SockJS and raw — **a native client should use the
raw endpoint directly**, no SockJS framing needed. Broker prefixes `/topic`/`/queue`, app
prefix `/app`, user prefix `/user`. JWT passed as a native STOMP CONNECT header (`Authorization`
or `token`). SUBSCRIBE-time authorization is checked only for `/topic/conversation/{id}`
(active-membership check); `/user/queue/*` relies on Spring's inherent per-session scoping.
Simple in-memory broker — single-instance only, not an external broker.

**Call signaling** (`call/controller/CallSignalController`, `call/service/CallService` —
**currently untracked in git**, read for information only, not modified): pure in-memory
relay, no DB persistence. 8 inbound `/app/call.*` destinations (offer/accept/reject/cancel/
end/webrtc-offer/webrtc-answer/ice/connected), all outbound events delivered to
`/user/queue/calls` as a `WsEvent{type, requestId, payload}` envelope. Full event catalog
(`CALL_RINGING`, `CALL_INCOMING`, `CALL_ACCEPTED`, `CALL_WEBRTC_OFFER/ANSWER`, `CALL_ICE`,
`CALL_CONNECTED`, `CALL_REJECTED`, `CALL_CANCELLED`, `CALL_ENDED`, `CALL_TIMEOUT`, `CALL_BUSY`,
`CALL_FAILED`) documented in this session's N2.0 report. Ring timeout configurable
(`connectx.call.ring-timeout-seconds`, default 30s) — the working tree's modified
`application.yml` adds exactly this one key; no other config differs from the committed
baseline. **Out of scope for N2 itself** — recorded here only because it was encountered
during inspection, per the approved instructions to read-but-not-modify.

**Android network foundation — confirmed current state (re-verified, not assumed):**
`core/network/NetworkModule.kt` provides exactly `OkHttpClient.Builder().build()` →
`Retrofit.Builder().baseUrl(BuildConfig.BASE_URL).client(okHttpClient).build()` — no
converter factory, no interceptors. A repo-wide search for `interface`/`@GET`/`@POST`/
`Repository`/`ViewModel` under `app/src` found zero matches (aside from one unrelated
ProGuard keep-rule file) — confirming no API interfaces, DTOs, repositories, or
ViewModels exist yet, exactly as expected going into N2.

**Known gaps/ambiguities for N2.1+ to account for:**
- No endpoint lists a user's **starred messages** (`POST/DELETE .../star` exist, no `GET`).
- Access/refresh token indistinguishability (above) — relevant to N2.1's token-handling design and N3.
- `CorsConfig`'s dead `allowed-origins` property — confirmed irrelevant to Android, not a blocker.
- `ProfileImageController`/`GroupImageController` are `permitAll()` at the Spring Security
  layer but self-enforce authorization/visibility inside the controller — Android must still
  send a token (via the `?token=` fallback or header) even though the path itself isn't
  gated by the security filter.
- Group `whoCanInvite`/`whoCanSendMessages`/`whoCanEditGroupInfo` policy enums are returned
  as plain strings on `GroupDto`, not a fixed Java enum exposed via any metadata endpoint —
  Android will need to hardcode the known value sets.

**Recommended N2 implementation order** (based only on what exists): Auth (register/login/
refresh — smallest, self-contained, needed before anything else can be tested end-to-end) →
User (`/me`, search, public profile) → Connection/Block → Conversation → Message → Media →
Device → Push → Group (largest surface, 23 endpoints, naturally last). This mirrors the
backend's own dependency order (conversations depend on connections; messages depend on
conversations; groups depend on the conversation/message pipeline already existing).

**Files inspected:** all 10 controller packages under `connectx-backend/src/main/java/com/connectx/`
and their referenced request/response DTOs; `SecurityConfig`, `JwtTokenProvider`,
`JwtAuthenticationFilter`; `GlobalExceptionHandler`, `ApiException`, `ApiResponse`,
`ErrorResponse`; `CorsConfig`; `WebSocketConfig`, `WebSocketAuthChannelInterceptor`;
`CallSignalController`, `CallService` (read-only, untracked feature); `application.yml`
(modified, one added key); `app/src/main/java/com/connectx/app/core/network/NetworkModule.kt`.

**Files modified:** none in `connectx-backend`, `connectx-frontend`, or `connectx-android`
source. Only `docs/CONNECTX_ANDROID_DEVELOPMENT.md` (this section) was written.

**No network requests were made** — inspection was source-code-only throughout; no `curl`,
no HTTP client invocation, no test against `10.0.2.2` or `app.myconnect.sbs`.

**PWA calling changes confirmed untouched:** `git status --short` was re-checked after
this inspection — identical to the state reported in Section 9g/N1.10; nothing staged,
committed, modified, or deleted.

## 28. N2.1 — Android API Contract & JSON Serialization Foundation

**N2.1 — Android API Contract: COMPLETE.**

Establishes the Android-side JSON serialization approach and the minimum Retrofit
contract for authentication, based strictly on the backend source re-read directly for
this phase (not assumed from the Section 27 summary). **No authentication behavior,
token storage, or network calls exist yet** — this is a compile-time contract only,
consumed starting at N3.

**Backend auth contract re-verified directly** (not from the N2.0 summary):
`auth/controller/AuthController.java`, `auth/dto/RegisterRequest.java`,
`auth/dto/LoginRequest.java`, `auth/dto/AuthResponse.java`, `user/dto/UserDto.java`,
`common/response/ApiResponse.java`, `common/response/ErrorResponse.java`, and
`config/JacksonConfig.java` (to confirm `Instant` fields serialize as ISO-8601 strings,
not numeric timestamps — `WRITE_DATES_AS_TIMESTAMPS` is explicitly disabled).

**Exact auth JSON contract modeled:**

| Field | `RegisterRequest` (request) | `LoginRequest` (request) | `AuthResponse` (response, inside `data`) |
|---|---|---|---|
| — | `username: String` (required, 3-50 chars) | `usernameOrEmail: String` (required) | `accessToken: String` |
| — | `email: String` (required, valid email) | `password: String` (required) | `refreshToken: String` |
| — | `password: String` (required, min 6) | | `tokenType: String` (default `"Bearer"`) |
| — | `displayName: String?` (optional, no validation) | | `user: UserDto` |

`UserDto` (nested in `AuthResponse.user`, matches `user/dto/UserDto.java` field-for-field):
`id: Long, username: String, email: String, displayName: String?, profileImageUrl: String?,
status: String?, lastSeenAt: String?, createdAt: String?, profilePhotoVisibility: String?,
groupAddPrivacy: String?` — `lastSeenAt`/`createdAt` kept as raw ISO-8601 strings, no date
type/library introduced.

Both `POST /api/v1/auth/register` and `POST /api/v1/auth/login` return `200 OK` with
`ApiResponse<AuthResponse>` on success (`ApiResponse` fields: `success: Boolean, code:
String?, message: String?, data: T?, timestamp: String?`, matching
`common/response/ApiResponse.java` exactly), or a non-2xx status with `ErrorResponse`
(`timestamp, status: Int, code, message, path`, matching `ErrorResponse.java` exactly)
via `GlobalExceptionHandler` — e.g. 400 `VALIDATION_ERROR` for bean-validation failures.

**Serialization library selected: kotlinx.serialization**, `kotlinx-serialization-json
1.11.0` (current stable), via the `org.jetbrains.kotlin.plugin.serialization` Kotlin
compiler plugin pinned to this project's exact Kotlin version (`2.2.10`, reusing the
existing `kotlin` version-catalog entry — the plugin ships as part of the Kotlin
distribution itself, so there is no separate compatibility lookup needed the way a
third-party library would require).

**Why kotlinx.serialization over Gson/Moshi/Jackson:** it is Kotlin's own first-party,
JetBrains-maintained solution — version-locked to the Kotlin compiler (eliminates an
entire class of "which Kotlin version does this library support" question that a
third-party library like Moshi or Gson would introduce), works natively with Kotlin
`data class`es via a compiler plugin (no reflection at runtime, unlike Gson), and
Retrofit 3.0.0 (already in this project) ships an official first-party converter for it
(`com.squareup.retrofit2:converter-kotlinx-serialization`, released at the identical
`3.0.0` version as the existing Retrofit dependency — reusing the `retrofit` version-catalog
entry, no separate version to track). This avoids adding a second, unrelated annotation
processor/compiler-plugin pipeline (Moshi's KSP codegen would technically reuse the
existing KSP setup from N1.5, which was considered, but kotlinx.serialization's tighter
Kotlin-version coupling and Retrofit's same-version official converter made it the
clearer fit for this specific project). Gson was not considered further — reflection-based,
not Kotlin-null-safety-aware, and no longer Square's own recommendation for new projects.
**Exactly one serialization approach was added — no second JSON library exists.**

**Retrofit converter configuration** (`core/network/NetworkModule.kt`): a new
`@Provides @Singleton fun provideJson(): Json` bean (`ignoreUnknownKeys = true,
explicitNulls = false`), and `provideRetrofit` now adds
`.addConverterFactory(json.asConverterFactory("application/json".toMediaType()))`
before `.build()`. `BuildConfig.BASE_URL` remains the sole base URL source — verified via
a repo-wide grep after implementation that no literal `10.0.2.2`/`app.myconnect.sbs`
string exists anywhere in Kotlin source. The dependency graph is now:
```
Hilt → OkHttpClient → Json → Retrofit(baseUrl=BuildConfig.BASE_URL, converter=Json) → AuthApi
```

**Auth API interface** (`data/remote/auth/AuthApi.kt`) — deliberately minimal, exactly
the two endpoints named as the approved example:
```kotlin
interface AuthApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiResponse<AuthResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): ApiResponse<AuthResponse>
}
```
Paths have no leading `/` since `BuildConfig.BASE_URL` already ends in `/`. `refresh`,
`logout`, and the forgot-password/OTP endpoints were **not** modeled — they exist on the
backend (Section 27) but are either token-lifecycle concerns (N3) or a distinct
password-recovery sub-flow, not part of this phase's minimal contract. Provided via Hilt:
`@Provides @Singleton fun provideAuthApi(retrofit: Retrofit): AuthApi =
retrofit.create(AuthApi::class.java)` — no separate DI module created.

**Files created:**
- `core/network/model/ApiResponse.kt`, `ErrorResponse.kt` — shared response wrappers,
  reusable by every future API group, not auth-specific.
- `data/remote/auth/model/RegisterRequest.kt`, `LoginRequest.kt`, `AuthResponse.kt`, `UserDto.kt`
- `data/remote/auth/AuthApi.kt`
- `app/src/test/java/com/connectx/app/data/remote/auth/AuthContractSerializationTest.kt` —
  5 JVM unit tests, zero network/Retrofit/OkHttp involvement, encoding/decoding
  hand-written JSON shaped exactly like real backend request/response bodies (including
  a realistic login success payload and a realistic 401 `ErrorResponse`). All 5 pass.

**Files modified:** `gradle/libs.versions.toml` (added `kotlinxSerializationJson`
version + `kotlinx-serialization-json`/`retrofit-converter-kotlinx-serialization`
library aliases + `kotlin-serialization` plugin alias), root `build.gradle.kts` and
`app/build.gradle.kts` (applied the plugin, added the two dependencies),
`core/network/NetworkModule.kt` (as described above).

**No repositories, ViewModels, DTOs beyond the auth contract, interceptors, or other API
group interfaces (`UserApi`, `ConversationApi`, etc.) were created** — exactly the auth
contract, nothing more.

**Testing/verification performed:** `./gradlew assembleDebug` → BUILD SUCCESSFUL (42/42
tasks, `kspDebugKotlin`/`hiltAggregateDepsDebug`/`hiltJavaCompileDebug` all succeeded,
confirming the Hilt graph compiles with `AuthApi` now provided); `./gradlew
testDebugUnitTest` → 5/5 serialization contract tests pass, 0 failures; fresh install +
launch on the Pixel 7a emulator, `logcat` clean (no `FATAL`/`AndroidRuntime`/`Exception`);
re-verified HOME renders both buttons and the Design System Showcase still opens and
renders correctly — N1's foundation is unaffected by N2.1's additions.

**JWT facts recorded (not implemented):** HS256, Bearer auth, 24h access / 7-day
refresh, structurally identical tokens (no `typ` claim), no server-side refresh
revocation — carried forward from Section 27 for N3's reference. No `TokenManager`, no
DataStore/encrypted storage, no `AuthInterceptor`, no refresh logic, no auth state, no
login/registration UI — all explicitly deferred to N3.

**Other API groups deliberately deferred:** User, Connection, Block, Conversation,
Message, Media, Device, Push, Group — all remain unimplemented, per N2.2-N2.7's
remaining scope and later N2 sub-phases for additional API groups.

**Zero HTTP requests were made** — verified via the same repo-wide grep approach used in
N1.6/N2.0 (no literal host string, no call site invoking `AuthApi` anywhere in the
codebase). **PWA calling changes confirmed untouched** — `git status --short` compared
before and after N2.1: identical set of modified/untracked backend/frontend files, only
the intended Android files and this document changed.

**Unresolved contract issues:** none new. The access/refresh token indistinguishability
and other Section 27 gaps remain open questions for N3, not resolved or worked around here.

## 29. N2.2 — User API Contract

**N2.2 — User API Contract: COMPLETE.**

**Discrepancy found and corrected:** Section 27 (N2.0) reported "User (10, incl.
`ProfileImageController`)." Re-inspecting the actual source for N2.2 (not trusting that
summary) found `UserController` alone has **10** `@*Mapping` methods, confirmed via a
literal grep for `@(Get|Post|Patch|Delete|Put)Mapping` in the file — search, me,
`{userId}`, PATCH me, request-otp, verify-otp, upload-photo, remove-photo,
get-identity-key, save-identity-key. Adding `ProfileImageController`'s one endpoint
(`GET /profile-images/{userId}`) makes **11 total**, not 10. This document is corrected
here rather than silently carrying the earlier undercount forward.

**Backend source re-read directly for N2.2:** `user/controller/UserController.java`,
`user/controller/ProfileImageController.java`, `user/dto/PublicUserDto.java`,
`user/dto/UserProfileUpdateDto.java`, `user/dto/UserIdentityKeyDto.java`, plus targeted
reads of `UserService.searchUsersByUsername`/`requestEmailChangeOtp`/
`verifyEmailChangeOtp` to confirm parameter/validation behavior. `user/dto/UserDto.java`
was already fully verified in N2.1 (Section 28) — not re-duplicated as a model here (see
below).

**Complete User endpoint inventory (11):**

| Method | Path | Response wrapper | Response body | Notes |
|---|---|---|---|---|
| GET | `/api/v1/users/search?username=` | `ApiResponse<List<PublicUserDto>>` | list | |
| GET | `/api/v1/users/me` | `ApiResponse<UserDto>` | full detail incl. email | |
| GET | `/api/v1/users/{userId}` | `ApiResponse<PublicUserDto>` | no email | |
| PATCH | `/api/v1/users/me` | `ApiResponse<UserDto>` | body: `UserProfileUpdateDto` | no `@Valid` on backend |
| POST | `/api/v1/users/me/email/request-otp` | `ApiResponse<String>` | body: raw `Map<String,String>{newEmail}` | |
| POST | `/api/v1/users/me/email/verify-otp` | `ApiResponse<UserDto>` | body: raw `Map<String,String>{newEmail,otpCode}` | |
| POST | `/api/v1/users/me/profile-photo` | `ApiResponse<UserDto>` | multipart, part `file` | |
| DELETE | `/api/v1/users/me/profile-photo` | `ApiResponse<UserDto>` | — | |
| GET | `/api/v1/users/me/identity-key` | `ApiResponse<UserIdentityKeyDto>` | — | |
| POST | `/api/v1/users/me/identity-key` | `ApiResponse<UserIdentityKeyDto>` | body: `UserIdentityKeyDto` | |
| GET | `/api/v1/profile-images/{userId}` | **none — raw binary** | `Resource` (image bytes) | `permitAll()` at Spring Security layer, self-enforces auth+visibility in the controller |

All 11 require authentication on the backend (an authenticated `UserPrincipal`) except
none — every one requires it, including the nominally-`permitAll()` image endpoint
(which 403s if `currentUser == null`).

**Request models created** (`data/remote/user/model/`): `UserProfileUpdateDto` (5
nullable fields, no validation replicated since the backend has none),
`RequestEmailChangeOtpRequest{newEmail}` and `VerifyEmailChangeOtpRequest{newEmail,
otpCode}` — typed models replacing the backend's ad-hoc `Map<String,String>` bodies with
the identical JSON shape (single/double-key objects), and `UserIdentityKeyDto` (also
doubles as a response model, matches the backend's identical request/response DTO).

**Response models created:** `PublicUserDto` (8 fields, no email — matches
`PublicUserDto.java` exactly), `UserIdentityKeyDto` (as above). **`UserDto` is reused
from `data/remote/auth/model/`, not duplicated** — it is the exact same backend class
(`com.connectx.user.dto.UserDto`) returned by both `/auth/*` and `/users/me`, so one
Kotlin model represents it, imported from the user package where needed.

**Nested/list structures:** `ApiResponse<List<PublicUserDto>>` for search — modeled and
tested directly (kotlinx.serialization handles generic `List<T>` payloads natively, no
special handling needed).

**Timestamp handling:** `PublicUserDto.lastSeenAt`/`createdAt` kept as raw ISO-8601
`String?`, identical convention to N2.1's `UserDto` — no new date/time library
introduced, consistent with the confirmed `JacksonConfig` behavior (Section 28).

**Response wrappers:** 10 of 11 endpoints use `ApiResponse<T>` (reused from N2.1, not
duplicated); the raw image endpoint uses **no wrapper** — modeled as `okhttp3.ResponseBody`
via `@Streaming`, which Retrofit handles natively without going through the registered
kotlinx.serialization converter (binary content isn't JSON).

**`UserApi` interface** (`data/remote/user/UserApi.kt`) — all 11 endpoints, `suspend
fun`s, `@GET`/`@POST`/`@PATCH`/`@DELETE`/`@Multipart`+`@Part`/`@Query`/`@Path`/`@Body`
used exactly matching each endpoint's actual HTTP semantics. No paths lead with `/`
(same `BuildConfig.BASE_URL`-relative convention as `AuthApi`).

**Hilt integration:** `NetworkModule.provideUserApi(retrofit: Retrofit): UserApi =
retrofit.create(UserApi::class.java)` — added following the exact `provideAuthApi`
pattern, no new module file. Graph is now `Hilt → Json → OkHttpClient → Retrofit →
{AuthApi, UserApi}`.

**Serialization tests** (`UserContractSerializationTest.kt`) — 6 tests, all passing:
realistic search-response list decoding, `getUserById` response decoding (incl. a null
`lastSeenAt`), `getCurrentUser`/`me` response decoding (confirms the reused `UserDto`
still round-trips correctly from a different endpoint), `UserProfileUpdateDto` partial
encoding (only provided fields present, matching `explicitNulls = false`), the two raw
Map-body request models' exact JSON shape, and `UserIdentityKeyDto` round-trip. Combined
with N2.1's 5 tests: **11/11 pass, 0 failures.**

**Build verification:** `./gradlew assembleDebug` → BUILD SUCCESSFUL (Hilt/KSP compiled
`UserApi`'s binding without issue, no dependency version changes needed — `UserApi`
reuses every dependency already added in N2.1, nothing new to add).

**Pixel 7a verification:** fresh install, clean launch, `logcat` clear of any
crash/exception; HOME and Design System Showcase both confirmed still rendering
correctly via real UI interaction — N1's foundation unaffected by N2.2.

**Vivo X200 FE — bonus verification (not required by N2.2's scope, but the physical
device was unexpectedly reachable via wireless ADB during this session):** same debug
APK installed and launched successfully, `logcat` clean, HOME confirmed rendering
correctly (both buttons present) after a fresh restart. First time this device has been
reachable in this project's history — previously always recorded as "NOT VERIFIED /
DEVICE UNAVAILABLE" (N1.5 onward). Not a substitute for Pixel 7a as the primary/
authoritative N1/N2 verification device, but a welcome confirmation the app also runs
correctly on real target hardware.

**Zero HTTP requests were made** — no call site invokes `UserApi` anywhere; grep
confirms no literal host string exists outside `BuildConfig`.

**No authentication/JWT behavior added** — no interceptor, no header injection, no
token storage; identical boundary to N2.1.

**No UI/repository/ViewModel added** — `UserApi` and its models are the only new
production code; the test file is JVM-only and not part of the app's runtime.

**PWA calling changes confirmed untouched** — `git status --short` compared before and
after N2.2: identical set of modified/untracked backend/frontend files.

**Remaining N2 API groups deliberately deferred:** Connection, Block, Conversation,
Message, Media, Device, Push, Group — all remain unimplemented, per N2.3-N2.7's scope.

## 30. N2.3 — Connection + Block API Contract

**N2.3 — Connection + Block API Contract: COMPLETE.**

**Endpoint counts re-verified — no discrepancy this time.** Unlike N2.2 (where the
User count was wrong), directly re-reading `ConnectionController.java` and
`BlockController.java` confirmed the N2.0 counts were accurate: **Connection = 8**,
**Block = 3**. Re-verification is still mandatory per phase (the rule established
after N2.2 is "the backend source is always the source of truth," not "assume the
prior count is now reliable") — this phase simply had no correction to make.

**Backend source re-read directly:** `connection/controller/ConnectionController.java`,
`connection/dto/SendConnectionRequestDto.java`, `ConnectionRequestDto.java`,
`UserConnectionDto.java`; `block/controller/BlockController.java`,
`block/dto/UserBlockDto.java`.

**Complete Connection endpoint inventory (8):**

| Method | Path | Response wrapper | Response body |
|---|---|---|---|
| POST | `/api/v1/connections/requests` | `ApiResponse<ConnectionRequestDto>` | body: `SendConnectionRequestDto{recipientId}` |
| GET | `/api/v1/connections/requests/pending` | `ApiResponse<List<ConnectionRequestDto>>` | incoming |
| GET | `/api/v1/connections/requests/sent` | `ApiResponse<List<ConnectionRequestDto>>` | outgoing |
| POST | `/api/v1/connections/requests/{id}/accept` | `ApiResponse<ConnectionRequestDto>` | |
| POST | `/api/v1/connections/requests/{id}/reject` | `ApiResponse<ConnectionRequestDto>` | |
| POST | `/api/v1/connections/requests/{id}/cancel` | `ApiResponse<ConnectionRequestDto>` | |
| GET | `/api/v1/connections` | `ApiResponse<List<UserConnectionDto>>` | established connections |
| DELETE | `/api/v1/connections/{userId}` | `ApiResponse<String>` | |

**Complete Block endpoint inventory (3):**

| Method | Path | Response wrapper | Response body |
|---|---|---|---|
| POST | `/api/v1/blocks/{userId}` | `ApiResponse<UserBlockDto>` | |
| DELETE | `/api/v1/blocks/{userId}` | `ApiResponse<String>` | |
| GET | `/api/v1/blocks` | `ApiResponse<List<UserBlockDto>>` | |

All 11 (8+3) endpoints require authentication on the backend.

**Request models created:** `SendConnectionRequestDto{recipientId: Long}` (matches
backend exactly, single `@NotNull` field, no other validation replicated).

**Response models created:** `ConnectionRequestDto` (12 fields — deliberately flat, not
nested `PublicUserDto`/`UserDto`, per the backend class's own javadoc excluding email
and any field not already public via search), `UserConnectionDto` (6 fields, resolved
to whichever party isn't the viewer), `UserBlockDto` (6 fields, same flat/no-email
pattern as `ConnectionRequestDto`).

**Reused models:** **none.** All four Connection/Block DTOs were checked against
`PublicUserDto`/`UserDto` and found to be genuinely different shapes (flat
`requesterUsername`/`requesterDisplayName`-style fields, not a nested user object, and
missing fields like `status`/`profilePhotoVisibility` that `PublicUserDto` has) — so no
reuse was applied, per the explicit instruction not to reuse models merely because
field names look similar.

**Timestamp handling:** `createdAt`/`respondedAt` kept as raw ISO-8601 `String?`,
identical convention to N2.1/N2.2 — no new date/time library.

**Response wrappers:** all 11 endpoints use `ApiResponse<T>` (reused from N2.1, not
duplicated) — no raw/unwrapped responses in this API group (unlike N2.2's one binary
image endpoint).

**`ConnectionApi`** (`data/remote/connection/ConnectionApi.kt`) — all 8 endpoints,
`suspend fun`s, `@GET`/`@POST`/`@DELETE`/`@Path`/`@Body` matching each endpoint exactly.

**`BlockApi`** (`data/remote/block/BlockApi.kt`) — all 3 endpoints, kept as its own
interface (not merged into `ConnectionApi`) since the backend exposes it as a fully
separate controller/domain (`/api/v1/blocks`, not nested under `/api/v1/connections`).

**Hilt integration:** `NetworkModule.provideConnectionApi`/`provideBlockApi` added,
following the exact `provideAuthApi`/`provideUserApi` pattern — no new module file.
Graph is now `Hilt → Json → OkHttpClient → Retrofit → {AuthApi, UserApi, ConnectionApi,
BlockApi}`.

**Serialization tests:** 12 tests existed before N2.3 (1 stock `ExampleUnitTest` + 5
Auth + 6 User). **8 new** (5 Connection + 3 Block). **20 total, 20 pass, 0 failures.**
New tests cover: request encoding, a realistic pending-request response, a
pending-incoming list, an established-connections list, a plain-`String` data response
(`removeConnection`), a realistic block response, a blocked-users list, and a plain-
`String` data response (`unblockUser`).

**Build result:** `./gradlew assembleDebug` → BUILD SUCCESSFUL. No dependency changes
needed — `ConnectionApi`/`BlockApi` reuse every dependency already added in N2.1/N2.2.

**Pixel 7a verification:** fresh install/launch, `logcat` clear; HOME confirmed, full
`HOME → Navigation Test → Back → HOME` flow re-verified via real interaction, Design
System Showcase opened and its light/dark theme toggle exercised (label correctly
flips `"Dark theme"` ↔ `"Light theme"`) — no crash at any step.

**Vivo X200 FE verification:** still reachable this session via wireless ADB — fresh
install/launch, `logcat` clear, HOME confirmed, full navigation flow re-verified, Design
System Showcase + light/dark theme toggle re-verified — no crash at any step. Screen
resolution differs slightly from the Pixel 7a (1080×2344 vs. 1080×2400), tap
coordinates were recalculated from a fresh UI-hierarchy dump rather than reused blindly.

**Zero HTTP requests were made** — no call site invokes `ConnectionApi`/`BlockApi`
anywhere; grep confirms no literal host string exists outside `BuildConfig`.

**No authentication/JWT behavior added; no WebSocket/STOMP code touched** — identical
boundary to N2.1/N2.2; `/ws` and realtime concerns remain entirely untouched, deferred
to N6.

**No UI/repository/ViewModel added** — `ConnectionApi`/`BlockApi` and their models are
the only new production code; both test files are JVM-only.

**PWA calling changes confirmed untouched** — `git status --short` compared before and
after N2.3: identical set of modified/untracked backend/frontend files.

**Discrepancies discovered:** none this phase (see above) — recorded explicitly to show
the re-verification was actually performed, not skipped because N2.2 already found one.

**Unresolved issues:** none new.

**Remaining N2 API groups deliberately deferred:** Conversation, Message, Media, Device,
Push, Group — all remain unimplemented, per N2.4-N2.7's scope.

## 31. N2.4 — Conversation API Contract

**N2.4 — Conversation API Contract: COMPLETE.**

**Endpoint count re-verified — no discrepancy.** Directly re-reading
`ConversationController.java` confirmed the N2.0-reported count of **13** is accurate.
Consistent with the rule established after N2.2 ("the backend source is always the
source of truth," not "assume the count is now reliable"), this phase still performed
the full re-verification rather than trusting the prior report — it simply had nothing
to correct, same as N2.3.

**Backend source re-read directly:** `conversation/controller/ConversationController.java`,
`conversation/dto/CreateDirectConversationDto.java`, `ConversationDto.java`,
`ConversationMemberDto.java`.

**Complete Conversation endpoint inventory (13):**

| Method | Path | Response wrapper | Response body |
|---|---|---|---|
| GET | `/api/v1/conversations` | `ApiResponse<List<ConversationDto>>` | plain list, **no pagination** |
| POST | `/api/v1/conversations/direct` | `ApiResponse<ConversationDto>` | body: `CreateDirectConversationDto{userId}` |
| GET | `/api/v1/conversations/{conversationId}` | `ApiResponse<ConversationDto>` | |
| DELETE | `/api/v1/conversations/{conversationId}` | `ApiResponse<String>` | per-user soft delete |
| POST | `/api/v1/conversations/{conversationId}/clear` | `ApiResponse<String>` | |
| POST | `/api/v1/conversations/{conversationId}/pin` | `ApiResponse<ConversationDto>` | |
| POST | `/api/v1/conversations/{conversationId}/unpin` | `ApiResponse<ConversationDto>` | |
| POST | `/api/v1/conversations/{conversationId}/mute` | `ApiResponse<ConversationDto>` | body: optional `{mutedUntil}` |
| POST | `/api/v1/conversations/{conversationId}/unmute` | `ApiResponse<ConversationDto>` | |
| POST | `/api/v1/conversations/{conversationId}/archive` | `ApiResponse<ConversationDto>` | |
| POST | `/api/v1/conversations/{conversationId}/unarchive` | `ApiResponse<ConversationDto>` | |
| POST | `/api/v1/conversations/{conversationId}/mark-unread` | `ApiResponse<ConversationDto>` | |
| POST | `/api/v1/conversations/{conversationId}/mark-read` | `ApiResponse<ConversationDto>` | |

All 13 require authentication on the backend.

**Request models created:** `CreateDirectConversationDto{userId: Long}`,
`MuteConversationRequest{mutedUntil: String?}` (typed replacement for the backend's
optional raw `Map<String,String>` mute body — matches N2.2's ad-hoc-map convention;
Retrofit sends no body at all when the parameter is passed `null`, matching the
backend's `@RequestBody(required = false)`).

**Response models created:** `ConversationDto` (18 fields), `ConversationMemberDto`
(12 fields).

**Nested models:** `ConversationMemberDto` nests `List<ConversationMemberDto>` inside
`ConversationDto`, and each member nests a user object.

**Reused models:** `ConversationMemberDto.user` is a **genuine, exact reuse of
`PublicUserDto`** (from `data/remote/user/model/`, N2.2) — the backend embeds the real
`PublicUserDto.fromEntity(...)` result verbatim, not a similarly-named-but-different
shape, so no duplicate model was created. This is the first real cross-phase model
reuse in the N2 work.

**Pagination handling:** **none exists.** `GET /conversations` returns a plain
`List<ConversationDto>` — confirmed directly from the controller signature
(`ResponseEntity<ApiResponse<List<ConversationDto>>>`), not a `Page`/`Slice`/cursor
structure. No pagination abstraction was introduced, per the instruction not to invent
one where the backend doesn't have it.

**Timestamp handling:** all `Instant` fields (`createdAt`, `updatedAt`,
`lastMessageSentAt`, `pinnedAt`, `mutedUntil`, `archivedAt`, `joinedAt`) kept as raw
ISO-8601 `String?`, identical convention to N2.1-N2.3.

**Enum/status handling:** `type` (DIRECT/GROUP) and `lastMessageType`
(TEXT/IMAGE/LOCATION/DOCUMENT) on `ConversationDto`, and `role` (OWNER/ADMIN/MEMBER/null)
on `ConversationMemberDto`, are all backed by real, small Java enums on the backend
(`ConversationType`, `GroupRole`) serialized via `.name()`. **Kept as plain nullable
Kotlin `String`, not Android enums** — consistent with N2.2/N2.3's established
convention (e.g. `ConnectionRequestDto.status`) and deliberately chosen to avoid
brittleness: an Android `enum class` would fail to deserialize (or require an
`UNKNOWN` fallback) if the backend ever adds a new value, whereas a plain `String`
degrades safely. This decision is now consistent across every N2 phase so far, not
a one-off.

**Response wrappers:** all 13 endpoints use `ApiResponse<T>` — no raw/unwrapped
responses in this API group.

**`ConversationApi`** (`data/remote/conversation/ConversationApi.kt`) — all 13
endpoints, `suspend fun`s, `@GET`/`@POST`/`@DELETE`/`@Path`/`@Body` matching each
endpoint exactly, including the nullable `@Body` for the optional mute request.

**Hilt integration:** `NetworkModule.provideConversationApi` added, following the
established pattern. Graph is now `Hilt → Json → OkHttpClient → Retrofit → {AuthApi,
UserApi, ConnectionApi, BlockApi, ConversationApi}`.

**Message API boundary respected:** `ConversationDto`'s last-message information
(`lastMessageId`, `lastMessageSenderUserId`, `lastMessageSentAt`,
`lastMessageDeletedForEveryone`, `lastMessageType`, `lastMessageCaption`) is a set of
**flat scalar fields**, confirmed directly from `ConversationDto.java` — the backend
never embeds a nested `Message`/`MessageDto` object here. No `MessageApi` and no
message request/response models were created; this stays entirely within N2.4's
Conversation contract, as instructed.

**Serialization tests:** 20 tests existed before N2.4 (1 stock + 5 Auth + 6 User + 5
Connection + 3 Block). **6 new** Conversation tests. **26 total, 26 pass, 0 failures.**
New tests cover: request encoding (including the optional-field mute request encoding
both with and without a value), a realistic `DIRECT` conversation with a nested
`PublicUserDto` member, a `GROUP` conversation with a member `role` and no last
message, a plain-`String` data response (`deleteConversationForUser`), and an
empty-`members`-list edge case.

**Build result:** `./gradlew assembleDebug` → BUILD SUCCESSFUL. No dependency changes
needed.

**Pixel 7a verification:** fresh install/launch, `logcat` clear; HOME, full
`HOME → Navigation Test → Back → HOME` flow, Design System Showcase, and the light/dark
theme toggle (label correctly flips) all re-verified via real interaction — no crashes.

**Vivo X200 FE verification:** still reachable via wireless ADB — identical full
verification pass performed and confirmed working, no crashes. Tap coordinates were
recalculated from a fresh UI-hierarchy dump each time (device resolution: 1080×2344),
not reused from the Pixel 7a's coordinates.

**Zero HTTP requests were made** — no call site invokes `ConversationApi` anywhere; grep
confirms no literal host string exists outside `BuildConfig`.

**No authentication/JWT behavior added; no WebSocket/STOMP code touched** — identical
boundary to every prior N2 phase; `/ws` and realtime remain entirely untouched.

**No MessageApi added** — see "Message API boundary respected" above.

**No UI/repository/ViewModel/use-case added** — `ConversationApi` and its models are
the only new production code; the test file is JVM-only.

**PWA calling changes confirmed untouched** — `git status --short` compared before and
after N2.4: identical set of modified/untracked backend/frontend files.

**Discrepancies discovered:** none this phase.

**Unresolved contract issues:** none new.

**Remaining N2 API groups deliberately deferred:** Message, Media, Device, Push,
Group — all remain unimplemented, per N2.5-N2.7's scope.

## 32. N2.5 — Message API Contract

**N2.5 — Message API Contract: COMPLETE.**

**Discrepancy found and corrected — again.** N2.0 reported "Message = 10 endpoints."
Directly re-reading `MessageController.java` for N2.5 found **11**, confirmed via a
literal grep for `@(Get|Post|Put|Patch|Delete)Mapping`: history, send, delete,
add/remove reaction, edit, pin/unpin, get-pinned, star/unstar. This is the second real
undercount in the N2.0 summary (after N2.2's User group) — both discrepancies are now
on record, reinforcing that per-phase re-verification (not trusting the N2.0 document)
is the correct standing rule for the rest of N2.

**Backend source re-read directly:** `message/controller/MessageController.java`,
`message/dto/SendMessageRequestDto.java`, `MessageDto.java`, `MessageReactionDto.java`,
`PagedMessageResponseDto.java`.

**Complete Message endpoint inventory (11):**

| Method | Path | Response wrapper | Response body |
|---|---|---|---|
| GET | `/api/v1/conversations/{conversationId}/messages` | `ApiResponse<PagedMessageResponseDto>` | query: `before` (optional cursor), `limit` (default 30) |
| POST | `/api/v1/messages` | `ApiResponse<MessageDto>` | body: `SendMessageRequestDto` |
| DELETE | `/api/v1/messages/{messageId}` | `ApiResponse<String>` | query: `deleteForEveryone` (default false) |
| POST | `/api/v1/messages/{messageId}/reactions` | `ApiResponse<MessageDto>` | body: `{reaction}` |
| DELETE | `/api/v1/messages/{messageId}/reactions` | `ApiResponse<MessageDto>` | |
| PUT | `/api/v1/messages/{messageId}` | `ApiResponse<MessageDto>` | body: `{ciphertext, nonce}` |
| POST | `/api/v1/messages/{messageId}/pin` | `ApiResponse<MessageDto>` | |
| DELETE | `/api/v1/messages/{messageId}/pin` | `ApiResponse<MessageDto>` | |
| GET | `/api/v1/conversations/{conversationId}/pinned-message` | `ApiResponse<MessageDto?>` | data is nullable when nothing pinned |
| POST | `/api/v1/messages/{messageId}/star` | `ApiResponse<String>` | |
| DELETE | `/api/v1/messages/{messageId}/star` | `ApiResponse<String>` | |

All 11 require authentication on the backend.

**Request models created:** `SendMessageRequestDto` (16 fields), `AddReactionRequest{reaction}`
and `EditMessageRequest{ciphertext, nonce}` (typed replacements for the backend's raw
`Map<String,String>` bodies, same convention as N2.2/N2.4).

**Response models created:** `MessageDto` (32 fields — the largest DTO modeled so far),
`MessageReactionDto` (6 fields, flat — not reusing `PublicUserDto` since the shape
genuinely differs), `PagedMessageResponseDto` (4 fields).

**Nested models:** `MessageDto.reactions: List<MessageReactionDto>`. Reply-to fields
(`replyToMessageId`, `replyToSenderUsername`, `replyToMessageType`, `replyToCaption`,
`replyToDeleted`) are **flat scalars, not a nested `MessageDto`** — confirmed directly
from `MessageDto.java`, so no self-referential model was needed.

**Reused models:** none new this phase — every Message DTO is a genuinely distinct
shape from anything in `auth`/`user`/`connection`/`block`/`conversation`.

**Pagination/history handling:** the backend's own **custom cursor-based** structure,
`PagedMessageResponseDto{messages, hasMore, nextCursor, limit}` — confirmed directly
from source, **not** Spring `Page`/`Slice`, **not** offset/limit. `nextCursor` is a
message ID passed back as the `before` query parameter for the next page. Modeled
exactly as-is; no generic pagination abstraction was introduced.

**Timestamp handling:** all `Instant` fields (`sentAt`, `deliveredAt`, `readAt`,
`editedAt`, `pinnedAt`, reaction `createdAt`) kept as raw ISO-8601 `String?`, consistent
with N2.1-N2.4.

**Message type/status handling:** `messageType`/`replyToMessageType`
(TEXT/IMAGE/LOCATION/DOCUMENT) kept as plain nullable `String`, not an Android enum —
same convention as N2.4's `ConversationDto.type`, for the same brittleness-avoidance
reason.

**Media-reference handling:** `mediaId`, `mimeType`, `fileSizeBytes`, `mediaNonce` are
scalar fields modeled directly on `MessageDto` (confirmed they are NOT a nested Media
object on the backend) — genuinely part of this REST contract per the approved
instructions. **No `MediaApi` was created**; no upload/download behavior exists
anywhere — that remains N2.6's scope entirely.

**Reply/reference handling:** flat scalar fields on `MessageDto`, described above.

**Reaction handling:** `MessageDto.reactions: List<MessageReactionDto>` (nested list);
`addOrUpdateReaction`/`removeReaction` endpoints modeled with the typed
`AddReactionRequest`.

**Read/delivery handling:** `deliveredAt`/`readAt` are present as fields on `MessageDto`
(REST snapshot values only) — no realtime read-receipt/delivery behavior was
implemented; the WebSocket equivalents (`/app/message.delivered`, `/app/message.read`)
remain entirely out of scope, deferred to N6.

**Response wrappers:** `ApiResponse<T>` for all 11 endpoints — including one genuinely
nullable case (`getPinnedMessage`'s `data` is `null` when nothing is pinned), tested
explicitly.

**`MessageApi`** (`data/remote/message/MessageApi.kt`) — all 11 endpoints, `suspend
fun`s, exact Retrofit annotations including `@Query` for the optional `before`/`limit`
history parameters and `deleteForEveryone`.

**Hilt integration:** `NetworkModule.provideMessageApi` added, established pattern.
Graph is now `Hilt → Json → OkHttpClient → Retrofit → {AuthApi, UserApi, ConnectionApi,
BlockApi, ConversationApi, MessageApi}`.

**Serialization tests:** 26 tests existed before N2.5 (1 stock + 5 Auth + 6 User + 5
Connection + 3 Block + 6 Conversation). **7 new** Message tests. **33 total, 33 pass, 0
failures.** New tests cover: minimal TEXT-send request encoding (confirming omitted
optional fields don't appear), both ad-hoc-map request models, a realistic
`sendMessage` response with a nested reaction, a reply referencing a deleted message,
a cursor-paginated history page, plain-`String` data responses (star/delete), and the
nullable-`data` pinned-message case.

**Build result:** `./gradlew assembleDebug` → BUILD SUCCESSFUL. No dependency changes
needed.

**Pixel 7a verification:** fresh install/launch, `logcat` clear; HOME, full
`HOME → Navigation Test → Back → HOME` flow, Design System Showcase, and the light/dark
theme toggle all re-verified via real interaction — no crashes.

**Vivo X200 FE verification:** still reachable via wireless ADB — identical full
verification pass performed and confirmed working, no crashes. Tap coordinates
recalculated per-device from a fresh UI-hierarchy dump, not reused from the Pixel 7a.

**Zero HTTP requests were made** — no call site invokes `MessageApi`, `ConversationApi`,
`UserApi`, `ConnectionApi`, `BlockApi`, or `AuthApi` anywhere; grep confirms no literal
host string exists outside `BuildConfig`.

**No authentication/JWT behavior added; no WebSocket/STOMP code touched; no E2EE/local
data code added; no MediaApi created** — all four boundaries held exactly as scoped
(N3, N6, N7, N2.6 respectively).

**No UI/repository/ViewModel/use-case added** — `MessageApi` and its models are the
only new production code; the test file is JVM-only.

**PWA calling changes confirmed untouched** — `git status --short` compared before and
after N2.5: identical set of modified/untracked backend/frontend files.

**Discrepancies discovered:** the Message endpoint count (11, not 10) — see above.

**Unresolved contract issues:** none new.

**Remaining N2 API groups deliberately deferred:** Media, Device, Push, Group — all
remain unimplemented, per N2.6-N2.7's scope.

## 33. N2.6 — Media API Contract

**N2.6 — Media API Contract: COMPLETE.**

**Endpoint count re-verified — no discrepancy.** Directly re-reading `MediaController.java`
confirmed the N2.0-reported count of **2** is accurate. Unlike N2.2 (User) and N2.5
(Message), this group had nothing to correct — a useful data point that the N2.0
summary was right about roughly half of the groups checked so far and wrong about the
other half, which is exactly why per-phase re-verification (not selective trust) has
been the right standing rule throughout N2.

**Backend source re-read directly:** `media/controller/MediaController.java`,
`media/dto/MediaUploadResponseDto.java`.

**Complete Media endpoint inventory (2):**

| Method | Path | Response wrapper | Response body | Notes |
|---|---|---|---|---|
| POST | `/api/v1/conversations/{conversationId}/media` | `ApiResponse<MediaUploadResponseDto>` | multipart: `file` (required) + optional form fields `nonce`/`groupKeyVersion`/`mimeType` | |
| GET | `/api/v1/media/{mediaId}` | **none — raw binary** | `Resource` (bytes), `Content-Type`/`Content-Disposition` decided server-side | encrypted media forced to `application/octet-stream` + `attachment`; plaintext served `inline` with real MIME type |

Both require authentication on the backend.

**Request models created:** none as a formal `@Serializable` class — the upload
endpoint's optional `nonce`/`groupKeyVersion`/`mimeType` fields are genuine **multipart
form fields** (the backend reads them via `@RequestParam` under a
`multipart/form-data` content type, confirmed from the controller's `consumes =
MULTIPART_FORM_DATA_VALUE`), not a JSON body — so they are modeled as optional
`@Part("...") RequestBody?` Retrofit parameters directly, not a serialized DTO class.
This is the first N2 endpoint with no JSON request body at all.

**Response models created:** `MediaUploadResponseDto` (6 fields) — a genuinely
distinct flat shape, no reuse of any existing model.

**Reused models:** none — `MediaUploadResponseDto` checked against every existing
model and confirmed to be a new shape.

**JSON response handling:** the upload endpoint's `ApiResponse<MediaUploadResponseDto>`
is JSON, handled by the existing kotlinx.serialization converter, same as every other
API group.

**Binary response handling:** the download endpoint returns `okhttp3.ResponseBody`
directly (via `@Streaming`), **never `ApiResponse`-wrapped** — confirmed from the
backend controller returning `ResponseEntity<Resource>`, not
`ResponseEntity<ApiResponse<...>>`. This is the exact same pattern already established
for `UserApi.getProfileImage` in N2.2, applied consistently here.

**Multipart handling:** `@Multipart` + `@Part file: MultipartBody.Part` for the
required file, plus three optional `@Part("name") RequestBody?` parameters for the
non-file form fields. No file picker, no upload manager, no progress state — the
method signature is the contract only; nothing calls it.

**Content-Type behavior:** the backend decides `Content-Type` dynamically per-request
based on whether the stored media is encrypted (`nonce` presence) — this is
server-side logic the Android contract does not need to replicate; the client reads
whatever `Content-Type`/`Content-Disposition` the response actually carries.

**Streaming behavior:** `@Streaming` applied to `getMedia()`, avoiding buffering the
full response body in memory before it's consumed — matches the same reasoning
already documented for `UserApi.getProfileImage`.

**Timestamp handling:** not applicable — `MediaUploadResponseDto` has no date/time
fields.

**Media type/status handling:** `mimeType` is a plain string (e.g. `"image/png"`), not
an enum of any kind on either side — no representation decision was needed here.

**Encryption-related fields:** `nonce` (upload response + optional upload form field)
and `groupKeyVersion` (same) are modeled as opaque/scalar fields exactly as the
backend defines them — **no cryptographic behavior was implemented**; E2EE remains
N7's responsibility entirely.

**`MediaApi`** (`data/remote/media/MediaApi.kt`) — 2 methods: `uploadConversationMedia`
(multipart POST) and `getMedia` (streaming GET, raw `ResponseBody`).

**Hilt integration:** `NetworkModule.provideMediaApi` added, established pattern. Graph
is now `Hilt → Json → OkHttpClient → Retrofit → {AuthApi, UserApi, ConnectionApi,
BlockApi, ConversationApi, MessageApi, MediaApi}` — every API group planned for N2 is
now present in the Hilt graph except Device, Push, and Group (N2.7's scope).

**Serialization/contract tests:** 33 tests existed before N2.6 (1 stock + 5 Auth + 6
User + 5 Connection + 3 Block + 6 Conversation + 7 Message). **4 new** Media tests.
**37 total, 37 pass, 0 failures.** New tests cover: a realistic DIRECT
(unencrypted) upload response, a realistic GROUP E2EE upload response (both
`nonce`/`groupKeyVersion` present), and two contract-only `ResponseBody` checks (a
plaintext-image-shaped body and an encrypted-octet-stream-shaped body) confirming the
raw-binary representation holds and reads back arbitrary bytes correctly without any
network involvement.

**Build result:** `./gradlew assembleDebug` → BUILD SUCCESSFUL. No dependency changes
needed — `MediaApi` reuses Retrofit's built-in multipart/`ResponseBody` support,
already transitively available via the existing Retrofit/OkHttp dependencies.

**Pixel 7a verification:** fresh install/launch, `logcat` clear; HOME, full
`HOME → Navigation Test → Back → HOME` flow, Design System Showcase, and the light/dark
theme toggle all re-verified via real interaction — no crashes.

**Vivo X200 FE verification:** still reachable via wireless ADB — identical full
verification pass performed and confirmed working, no crashes.

**Zero HTTP requests were made** — no call site invokes `MediaApi` or any other API
interface anywhere; grep confirms no literal host string exists outside `BuildConfig`.

**No authentication/JWT behavior added; no WebSocket/STOMP code touched; no E2EE code
added** — all boundaries held exactly as scoped (N3, N6, N7 respectively).

**No file picker, storage access, upload/download manager, or media UI added** — the
API interface and its one response model are the only new production code.

**No UI/repository/ViewModel/use-case added** — the test file is JVM-only.

**PWA calling changes confirmed untouched** — `git status --short` compared before and
after N2.6: identical set of modified/untracked backend/frontend files.

**Discrepancies discovered:** none this phase.

**Unresolved contract issues:** none new.

**Remaining N2 API groups deliberately deferred:** Device, Push, Group — all remain
unimplemented, per N2.7's scope (the final N2 sub-phase).

## 34. N2.7 — Device + Push + Group API Contract (FINAL N2 phase)

**N2.7 — Device + Push + Group API Contract: COMPLETE. N2 is now COMPLETE.**

**All three endpoint counts re-verified — no discrepancies.** Directly re-reading
`DeviceController.java`, `PushNotificationController.java`, and all five current group
controllers confirmed the N2.0-reported counts (Device 5, Push 3, Group 23) are all
accurate. The fourth and fifth groups checked (Device, Push) plus Group had nothing to
correct, matching Media's outcome — only User (N2.2) and Message (N2.5) were
undercounted by N2.0, out of all seven groups now verified.

**Backend source re-read directly:** `device/controller/DeviceController.java`,
`device/dto/{RegisterDeviceDto,DeviceResponseDto,UserPublicKeyDto}.java`,
`push/controller/PushNotificationController.java`,
`push/dto/PushSubscriptionRequestDto.java`,
`group/controller/{GroupController,GroupMembershipController,GroupInvitationController,
GroupImageController,GroupKeyController}.java`, and every `group/dto/*.java` +
`group/entity/{WhoCanInvite,WhoCanSendMessages,WhoCanEditGroupInfo,
GroupInvitationStatus}.java` enum.

**Complete Device endpoint inventory (5):**

| Method | Path | Response wrapper | Response body |
|---|---|---|---|
| POST | `/api/v1/devices` | `ApiResponse<DeviceResponseDto>` | register a device |
| GET | `/api/v1/devices` | `ApiResponse<List<DeviceResponseDto>>` | caller's devices |
| DELETE | `/api/v1/devices/{deviceId}` | `ApiResponse<String>` | deactivate |
| POST | `/api/v1/devices/{deviceId}/seen` | `ApiResponse<DeviceResponseDto>` | mark seen |
| GET | `/api/v1/users/{userId}/devices/public-keys` | `ApiResponse<List<UserPublicKeyDto>>` | target user's per-device public keys |

All 5 require authentication; the last is a Device endpoint despite its `/users/...`
path (lives on `DeviceController`, kept in `DeviceApi`, not `UserApi`).

**Complete Push endpoint inventory (3):**

| Method | Path | Response wrapper | Response body |
|---|---|---|---|
| GET | `/api/v1/push/vapid-public-key` | `ApiResponse<Map<String,String>>` | `{"vapidPublicKey": "..."}` |
| POST | `/api/v1/push/subscribe` | `ApiResponse<Void>` | body: `PushSubscriptionRequestDto` |
| POST | `/api/v1/push/unsubscribe` | `ApiResponse<Void>` | query param `endpoint` (optional) |

This is Web Push (VAPID/p256dh, the browser Push API shape already used by the PWA
frontend) — **not Firebase Cloud Messaging.** All 3 require authentication except
`vapid-public-key` (implicitly public — no `@AuthenticationPrincipal` used).

**Complete Group endpoint inventory (23, across 5 controllers):**

*GroupController (8):*

| Method | Path | Response wrapper |
|---|---|---|
| POST | `/api/v1/groups` | `ApiResponse<GroupDto>` |
| GET | `/api/v1/groups/{groupId}` | `ApiResponse<GroupDto>` |
| GET | `/api/v1/groups/{groupId}/members` | `ApiResponse<List<ConversationMemberDto>>` |
| PATCH | `/api/v1/groups/{groupId}/settings` | `ApiResponse<GroupDto>` |
| PATCH | `/api/v1/groups/{groupId}/info` | `ApiResponse<GroupDto>` |
| POST (multipart) | `/api/v1/groups/{groupId}/avatar` | `ApiResponse<GroupDto>` |
| DELETE | `/api/v1/groups/{groupId}/avatar` | `ApiResponse<GroupDto>` |
| DELETE | `/api/v1/groups/{groupId}` | `ApiResponse<String>` |

*GroupMembershipController (4):*

| Method | Path | Response wrapper |
|---|---|---|
| PATCH | `/api/v1/groups/{groupId}/members/{userId}/role` | `ApiResponse<ConversationMemberDto>` |
| DELETE | `/api/v1/groups/{groupId}/members/{userId}` | `ApiResponse<String>` |
| POST | `/api/v1/groups/{groupId}/leave` | `ApiResponse<String>` |
| POST | `/api/v1/groups/{groupId}/ownership/transfer` | `ApiResponse<String>` |

*GroupInvitationController (6):*

| Method | Path | Response wrapper |
|---|---|---|
| POST | `/api/v1/groups/{groupId}/invitations` | `ApiResponse<CreateGroupInvitationResponseDto>` |
| POST | `/api/v1/groups/invitations/{invitationId}/accept` | `ApiResponse<GroupInvitationDto>` |
| POST | `/api/v1/groups/invitations/{invitationId}/reject` | `ApiResponse<GroupInvitationDto>` |
| POST | `/api/v1/groups/invitations/{invitationId}/cancel` | `ApiResponse<GroupInvitationDto>` |
| GET | `/api/v1/groups/invitations/received` | `ApiResponse<List<GroupInvitationDto>>` |
| GET | `/api/v1/groups/invitations/sent` | `ApiResponse<List<GroupInvitationDto>>` |

*GroupImageController (1):*

| Method | Path | Response wrapper | Response body |
|---|---|---|---|
| GET | `/api/v1/group-images/{groupId}` | **none — raw binary** | group avatar bytes |

Distinct base path (`/api/v1/group-images`, not `/api/v1/groups`) — mirrors
`ProfileImageController`'s enforcement pattern (`permitAll()` at the security layer,
auth + visibility enforced in-controller since an `<img src>` can't carry an
`Authorization` header).

*GroupKeyController (4):*

| Method | Path | Response wrapper |
|---|---|---|
| POST | `/api/v1/groups/{groupId}/keys` | `ApiResponse<GroupMemberKeyDto>` |
| GET | `/api/v1/groups/{groupId}/keys/me` | `ApiResponse<GroupMemberKeyDto>` |
| POST | `/api/v1/groups/{groupId}/keys/request` | `ApiResponse<GroupKeyRequestResultDto>` |
| POST | `/api/v1/groups/{groupId}/keys/rotate-for-recovery` | `ApiResponse<GroupKeyRotationResultDto>` |

All 23 require authentication (`GroupImageController` additionally self-enforces
group-membership visibility beyond bare authentication).

**Request models created:** `RegisterDeviceDto` (Device); `PushSubscriptionRequestDto`
+ nested `KeysDto` (Push — the standard W3C `PushSubscription.toJSON()` shape, not a
ConnectX invention); `CreateGroupRequestDto`, `UpdateGroupSettingsRequestDto`,
`UpdateGroupInfoRequestDto`, `UpdateMemberRoleRequestDto`,
`TransferOwnershipRequestDto`, `CreateGroupInvitationRequestDto`,
`SubmitGroupMemberKeyRequestDto` (Group) — 9 total.

**Response models created:** `DeviceResponseDto`, `UserPublicKeyDto` (Device);
`GroupDto`, `CreateGroupInvitationResponseDto`, `GroupInvitationDto`,
`GroupMemberKeyDto`, `GroupKeyRequestResultDto`, `GroupKeyRotationResultDto` (Group) —
8 total (Push introduced no dedicated response model — see below). No unnecessary
request DTO was created for the Group avatar multipart upload, same reasoning as
N2.6's Media upload (`file` is the only part; no other form fields exist on this
endpoint, unlike Media's `nonce`/`groupKeyVersion`/`mimeType`).

**No dedicated Push response DTO:** `getVapidPublicKey`'s data is a raw
`Map<String, String>` on the backend (`Map.of("vapidPublicKey", publicKey)`) — modeled
as `Map<String, String>` directly in the API signature rather than inventing a
single-field wrapper class for a shape the backend itself doesn't name.

**Nested structures:** `GroupInvitationDto.invitee`/`.invitedBy` are genuine, exact
`PublicUserDto` reuse (backend embeds the real `PublicUserDto.fromEntity(...)` result
verbatim, same as `ConversationMemberDto.user`). `PushSubscriptionRequestDto.keys` is a
nested `KeysDto` (`p256dh`/`auth`), matching the backend's own nested `KeysDto` static
class exactly — not flattened, since the backend JSON itself is nested.

**Reused models:** `ConversationMemberDto` (`data/remote/conversation/model/`) for
`GroupController#getGroupMembers` and `GroupMembershipController#changeRole` — the
backend literally returns `com.connectx.conversation.dto.ConversationMemberDto` from
both, the exact same class `ConversationApi` already models, not a similarly-shaped
but distinct type. `PublicUserDto` reused inside `GroupInvitationDto` (see above). No
other existing model (`UserDto`, `ConnectionRequestDto`, `UserConnectionDto`,
`UserBlockDto`, `ConversationDto`, `MessageDto`, `MessageReactionDto`,
`PagedMessageResponseDto`, `MediaUploadResponseDto`) was a genuine match for any
Device/Push/Group shape — `UserIdentityKeyDto` (a user's single account-level master
key pair) was checked against `UserPublicKeyDto` (a per-device public key entry) and
confirmed to be a different shape/semantics entirely, so no incorrect reuse there.

**Timestamp handling:** `DeviceResponseDto.createdAt`/`lastSeenAt` and
`GroupDto.createdAt`/`updatedAt` and `GroupInvitationDto.createdAt`/`respondedAt` all
kept as raw ISO-8601 strings — same convention as every timestamp field across
N2.1-N2.6, no new date/time dependency introduced.

**Enum/status handling:** `GroupDto.whoCanInvite`/`.whoCanSendMessages`/
`.whoCanEditGroupInfo`/`.currentUserRole`, `UpdateGroupSettingsRequestDto`'s three
policy fields, `UpdateMemberRoleRequestDto.role`, and `GroupInvitationDto.status` are
all kept as plain nullable/non-null Strings, not Android enums — continuing the
established convention (see `ConversationMemberDto.role`'s doc comment) of not
creating brittle enums for backend enum-name strings that could gain new values.
Backend enum sources: `WhoCanInvite{OWNER_ADMIN_ONLY,ALL_MEMBERS}`,
`WhoCanSendMessages{EVERYONE,ADMINS_ONLY}`,
`WhoCanEditGroupInfo{OWNER_ADMIN_ONLY,ALL_MEMBERS}`, `GroupRole{OWNER,ADMIN,MEMBER}`,
`GroupInvitationStatus{PENDING,ACCEPTED,REJECTED,CANCELLED}`.

**Response wrappers:** every Device and Group endpoint uses `ApiResponse<T>` except
`GroupImageController#getGroupImage` (raw `Resource`, modeled as `ResponseBody` +
`@Streaming`, the same established pattern as `MediaApi.getMedia`/
`UserApi.getProfileImage`). Push's `subscribe`/`unsubscribe` declare
`ApiResponse<Void>` on the backend (`data` always null) — modeled as
`ApiResponse<Unit>`, since `ApiResponse.data` stays nullable regardless of `T`, so
decoding a null `"data"` field works correctly; verified by a dedicated test.

**`DeviceApi`** (`data/remote/device/DeviceApi.kt`) — 5 methods, matches
`DeviceController` + one `/users/.../public-keys` endpoint exactly.

**`PushApi`** (`data/remote/push/PushApi.kt`) — 3 methods, matches
`PushNotificationController` exactly. No Firebase SDK, no FCM dependency, no
notification channel/permission code — Web Push contract only.

**`GroupApi`** (`data/remote/group/GroupApi.kt`) — 23 methods spanning all five
group controllers in a single interface (per N2.7's "create GroupApi.kt" instruction),
grouped with `// ----` section comments by originating controller for traceability.

**Hilt integration:** `NetworkModule.provideDeviceApi`/`providePushApi`/
`provideGroupApi` added, established pattern (no new `OkHttpClient`/`Retrofit`
instance, no new DI module). Graph is now `Hilt → Json → OkHttpClient → Retrofit →
{AuthApi, UserApi, ConnectionApi, BlockApi, ConversationApi, MessageApi, MediaApi,
DeviceApi, PushApi, GroupApi}` — every API group planned for N2 is now present.

**Serialization/contract tests:** 37 tests existed before N2.7. **26 new** tests (5
Device + 4 Push + 17 Group). **63 total, 63 pass, 0 failures.** New tests cover:
Device registration/list/deactivate/public-keys round trips including a
`lastSeenAt: null` inactive device; Push subscription encoding with/without optional
fields, the `Map<String,String>` VAPID response, and the `ApiResponse<Unit>` null-data
shape for subscribe/unsubscribe; Group create/details/settings/info/role/ownership
request-response round trips, both `CreateGroupInvitationResponseDto` outcomes
(`DIRECT_ADDED` with null invitation, `INVITATION_SENT` with a nested invitation),
received/sent pending invitation lists, a raw-binary group-avatar `ResponseBody` check
(same pattern as N2.6's Media tests), and all three group-key endpoint response shapes
(submit/get, request-rewrap, rotate-for-recovery).

**Build result:** `./gradlew assembleDebug` → BUILD SUCCESSFUL. No dependency, AGP,
Kotlin, Gradle, JDK, Compose, Hilt, KSP, Retrofit, or OkHttp version changes needed.

**Pixel 7a verification:** **NOT VERIFIED / DEVICE UNAVAILABLE.** `adb devices -l`
(including a daemon restart) showed only an attached emulator this session — no
physical Pixel 7a was reachable. Device configuration was not modified to force
availability, per instructions.

**Vivo X200 FE verification:** **NOT VERIFIED / DEVICE UNAVAILABLE.** Same `adb`
check — not reachable this session.

**Zero HTTP requests were made** — no call site invokes `DeviceApi`, `PushApi`,
`GroupApi`, or any other API interface anywhere; contract-only, same as every prior
N2 sub-phase.

**No authentication/JWT behavior added; no FCM/Firebase code added; no
WebSocket/STOMP code touched; no local database/DataStore added; no E2EE
implemented; no Device/Push/Group UI added; no calling code touched** — all boundaries
held exactly as scoped (deferred to N3, N8/N9, N6, N7, N7, later feature phases, and
N10/N11 respectively). `wrappedKey`/`wrapNonce`/`publicKey`/`keyAlgorithm`/
`p256dh`/`auth` are all modeled as opaque scalar fields only.

**No repository/ViewModel/use-case/business logic added** — the three API interfaces
and their 17 new models are the only new production code; test files are JVM-only.

**PWA calling changes confirmed untouched** — `git status --short` compared before and
after N2.7: identical set of modified/untracked backend/frontend files
(`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, and the other pre-existing uncommitted PWA
calling/unrelated changes) — none staged, committed, or modified.

**Discrepancies discovered:** none this phase — Device, Push, and Group endpoint
counts all matched N2.0's original summary exactly.

**Unresolved contract issues:** none new.

**Additional REST API groups discovered:** none — Auth, User, Connection, Block,
Conversation, Message, Media, Device, Push, and Group account for every
`@RestController` in `connectx-backend/src/main/java/com/connectx/`. No hidden or
unaccounted-for REST group exists.

**N2 completion review:**

```
N2.0  Backend inspection                  COMPLETE
N2.1  Auth API                            COMPLETE
N2.2  User API                            COMPLETE
N2.3  Connection + Block                  COMPLETE
N2.4  Conversation                        COMPLETE
N2.5  Message                             COMPLETE
N2.6  Media                               COMPLETE
N2.7  Device + Push + Group               COMPLETE
```

**N2 (Backend Connectivity — all REST API contracts) is COMPLETE.** STOPPED here per
instructions — N3 has not been started; waiting for review before proceeding.

**Git checkpoint:** commit `4f23b6b` — `feat(android): complete N1 foundation and N2
REST contracts`, 68 files (all of `connectx-android/` plus this document), created on
`phase-6-e2ee-media-stable`, local only (`ahead 1` of origin, not pushed). PWA calling
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`) and other pre-existing unrelated
backend/frontend edits were verified excluded from the commit and remain uncommitted.

## 35. N3.0 — Authentication Architecture & Backend Contract Re-verification

**N3.0 — Authentication Architecture & Backend Contract Re-verification: COMPLETE.**
**Inspection and design only — no source code was modified, no authentication network
request was made.**

### 1-3. Current backend auth endpoint contract (re-verified directly, not from Section 27)

**Backend files re-read for this phase:** `auth/controller/AuthController.java`,
`auth/service/AuthService.java`, `auth/dto/{LoginRequest,RegisterRequest,AuthResponse,
ForgotPasswordRequestDto,VerifyOtpRequestDto,ResetPasswordRequestDto}.java`,
`auth/entity/OtpToken.java`, `common/security/{JwtTokenProvider,
JwtAuthenticationFilter,UserPrincipal,CustomUserDetailsService}.java`,
`config/SecurityConfig.java`, `common/exception/{GlobalExceptionHandler,
ApiException}.java`, `common/response/{ApiResponse,ErrorResponse}.java`,
`application.yml`.

**7 current auth endpoints exist** — 5 more than N2.1's `AuthApi.kt` models (a
deliberate, already-documented scope decision, not a defect — see Section 28):

| Method | Path | Auth required | Request body | Response wrapper |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | no | `RegisterRequest` | `ApiResponse<AuthResponse>` |
| POST | `/api/v1/auth/login` | no | `LoginRequest` | `ApiResponse<AuthResponse>` |
| POST | `/api/v1/auth/logout` | **optional** (reads `@AuthenticationPrincipal`, but no-op either way) | none | `ApiResponse<String>` |
| POST | `/api/v1/auth/refresh` | no (token itself is the credential) | raw `Map<String,String>` — `{"refreshToken": "..."}`, **not** a typed DTO | `ApiResponse<AuthResponse>` |
| POST | `/api/v1/auth/forgot-password/request-otp` | no | `ForgotPasswordRequestDto` (`email`) | `ApiResponse<String>` |
| POST | `/api/v1/auth/forgot-password/verify-otp` | no | `VerifyOtpRequestDto` (`email`, `otpCode`) | `ApiResponse<String>` |
| POST | `/api/v1/auth/forgot-password/reset-password` | no | `ResetPasswordRequestDto` (`email`, `otpCode`, `newPassword`) | `ApiResponse<String>` |

All 7 live under `/api/v1/auth/**`, which is `permitAll()` at the Spring Security
layer (`SecurityConfig`) — none require a prior `Authorization` header, consistent
with being the entry points into the authenticated system. `logout`'s
`@AuthenticationPrincipal` is honored if present but never required — an
unauthenticated caller still gets `200 OK "Logged out"`.

Not modeled anywhere yet (correctly out of scope for N2.1/N3.0): the
`refresh`/`logout`/forgot-password family. This is a genuine gap N3 must close
before real login/refresh/logout can work — flagged as an open question below,
not fixed in this phase.

**Distinct from this family:** `UserApi`'s `requestEmailChangeOtp`/
`verifyEmailChangeOtp` (already modeled in N2.2) are a different feature —
changing a logged-in user's email — sharing the OTP *mechanism* but not the
`auth/forgot-password` endpoints or purpose. No overlap/confusion found.

### 4-5. JWT architecture (re-verified — Section 27's summary confirmed accurate, no changes)

- **Algorithm:** HS256 (`Jwts.SIG.HS256`), key = `connectx.jwt.secret` (env
  `JWT_SECRET`, base64 string) via `Keys.hmacShaKeyFor(...)`.
- **Claims:** `sub` = user id (as a string), `username` (custom claim), `iat`,
  `exp`. **No `typ`/`token_type` claim, no `iss`, no `aud`, no custom
  access-vs-refresh marker of any kind.**
- **Access-token lifetime:** `connectx.jwt.expiration-ms`, default `86400000`
  (24h), env-overridable via `JWT_EXPIRATION_MS`.
- **Refresh-token lifetime:** `connectx.jwt.refresh-expiration-ms`, default
  `604800000` (7 days), env-overridable via `JWT_REFRESH_EXPIRATION_MS`.
- **Access/refresh distinction:** **structurally identical JWTs** — both minted by
  the same `generateTokenFromUserId(userId, username, expirationMs)`, differing
  *only* in which expiration duration was passed in. `JwtTokenProvider.validateToken`
  has no way to tell them apart; neither does `JwtAuthenticationFilter`. **A
  refresh token is a fully valid Bearer access credential for every authenticated
  endpoint until it expires, and an access token is accepted by `POST
  /auth/refresh` exactly as if it were a real refresh token.** This is a backend
  design characteristic, confirmed by direct source read, not an assumption
  carried over from N2.0/Section 27.

### 6. Refresh token behavior (Step 3's 14 questions, answered from source)

1. Yes — `POST /api/v1/auth/refresh`.
2. A raw JSON body `{"refreshToken": "<jwt>"}` — `AuthController#refresh` reads
   `Map<String,String> body` directly, not a typed request DTO.
3. No — it is **not** sent as an `Authorization: Bearer` header; it is a JSON
   body field on an otherwise-unauthenticated `permitAll()` endpoint.
4. Yes (see 2).
5. No.
6. No.
7. **It cannot** — see "Access/refresh distinction" above; the backend has no
   mechanism to distinguish them.
8. No token-type claim exists.
9. No — no `OtpToken`-style entity/table backs issued access or refresh tokens;
   nothing is persisted about a token once minted.
10. No — there is no revocation list, blacklist, or per-token record to revoke
    against.
11. No — `logout()` (`AuthService.logout`) calls
    `SecurityContextHolder.clearContext()` only, which clears the **current
    request's** thread-local security context — meaningless for a stateless JWT
    API where every request builds its own `SecurityContext` from scratch in
    `JwtAuthenticationFilter`. **Logout has zero server-side effect on the token
    itself.** Both the just-used access token and its paired refresh token remain
    fully valid until their natural expiration, regardless of "logout."
12. **Yes** — a stolen refresh (or access) token remains fully usable for its
    entire lifetime; there is no way to invalidate it early, including via
    "logout."
13. `tokenProvider.validateToken(refreshToken)` returns `false` once past `exp`
    (parsing throws `JwtException`, caught and converted to `false`) → `refresh()`
    throws `ApiException(401, "INVALID_REFRESH_TOKEN", ...)`.
14. `401 Unauthorized`, code `INVALID_REFRESH_TOKEN`, message "Refresh token is
    invalid or expired" — same shape for a genuinely-expired token, a malformed
    token, and (per point 7) an access token used past *its own* shorter
    expiration.

`refresh()` performs full rotation: mints and returns **both** a new access token
and a new refresh token every call (`AuthResponse` shape identical to
register/login). The old refresh token is not invalidated by this rotation
either (no revocation exists) — it simply becomes one of potentially several
still-valid tokens for that user until each naturally expires.

### 7. Logout behavior — documented above (Step 4). Summary: **purely a formality
from the token's perspective.** The only real-world effect of "logout" on this
backend is whatever the *client* does with the tokens it's holding — which is
exactly why Android-side logout must be an entirely client-side operation
(discard stored tokens), not something that depends on a server call succeeding.

### 8. Authentication error contract (re-verified against `GlobalExceptionHandler`/`ApiException`/`SecurityConfig`)

| Scenario | HTTP status | Code | Source |
|---|---|---|---|
| Invalid username/password | 401 | `UNAUTHORIZED` | Spring's `AuthenticationException` (bad credentials) → `GlobalExceptionHandler.handleAuthenticationException`, message "Full authentication is required to access this resource" (Spring's default `BadCredentialsException` message is not surfaced — the generic handler message is used) |
| Duplicate username | 400 | `USERNAME_EXISTS` | `ApiException` thrown explicitly in `AuthService.register` |
| Duplicate email | 400 | `EMAIL_EXISTS` | same, explicit `ApiException` |
| Invalid/expired refresh token | 401 | `INVALID_REFRESH_TOKEN` | explicit `ApiException` in `AuthService.refresh` |
| Expired/malformed/missing access token on a protected endpoint | 401 | `UNAUTHORIZED` | custom `AuthenticationEntryPoint` bean in `SecurityConfig` (a deliberate fix — see its doc comment — ensuring 401, not Spring's 403 default, so `apiClient.ts`'s existing 401-only refresh-retry logic on the **frontend** keeps working; the same 401 contract is what any Android `Authenticator` must key off) |
| Forbidden (authenticated but not permitted) | 403 | `FORBIDDEN` | `AccessDeniedException` → `GlobalExceptionHandler.handleAccessDeniedException` |
| Bean-validation failure (e.g. password < 6 chars) | 400 | `VALIDATION_ERROR` | `MethodArgumentNotValidException`, message = joined field errors |
| Expired OTP | 400 | `EXPIRED_OTP` | explicit `ApiException` in `AuthService.validateOtpToken` |
| Incorrect OTP | 400 | `INVALID_OTP` | same method, wrong-code branch (constant-time `MessageDigest.isEqual` comparison) |
| OTP not found / already used | 400 | `INVALID_OTP` | same — an already-used or nonexistent token surfaces identically to a wrong code (no information leak about *why*) |
| Too many OTP attempts (≥5) | 429 | `OTP_LOCKED` | same method |
| OTP requested too frequently (<60s since last) | 429 | `OTP_RATE_LIMITED` | `AuthService.enforceOtpRequestRateLimit`, in-memory per-email cooldown (single-instance only — no distributed cache, documented in the source itself) |
| Unhandled/unexpected error | 500 | `INTERNAL_SERVER_ERROR` | catch-all handler; real exception logged server-side only, never leaked to the client |

Every error response is a single, consistent `ErrorResponse{timestamp, status,
code, message, path}` shape (matching N2.1's already-modeled Android
`ErrorResponse.kt` exactly) — no per-endpoint variation in envelope structure.

### 9. Existing Android AuthApi state (re-inspected, not modified)

`AuthApi.kt` currently models exactly `register`/`login` (2 methods), matching
N2.1's deliberate scope. `AuthResponse.kt`, `LoginRequest.kt`, `RegisterRequest.kt`,
`UserDto.kt` all still match their backend counterparts field-for-field — no
discrepancy found, no changes needed. `ApiResponse.kt`/`ErrorResponse.kt`
(`core/network/model/`) remain the shared envelope models, already correct and
reusable as-is for the 5 additional endpoints once they're modeled (whichever N3
sub-phase takes that on — see open questions). `NetworkModule.kt` still provides
a plain `OkHttpClient.Builder().build()` with **no interceptors** — confirmed via
direct re-read, not assumed. **No genuine contract discrepancy was found — no
STOP condition triggered.**

### 10. Authentication architecture (design only — nothing built)

The full conceptual chain from the prompt (`UI → ViewModel → UseCase/Repository →
Session/Auth Manager → Secure Token Storage → OkHttp → Retrofit → Backend`) is
more layering than this backend's actual behavior justifies. Given there is **no
server-side session state, no revocation, and refresh is a single stateless
POST**, a "use case" layer would add indirection without decision logic to hold —
recommended minimum:

```
Auth UI (N3.5)
      ↓
AuthViewModel (N3.5) — holds UI state, calls AuthRepository directly
      ↓
AuthRepository (N3.2) — wraps AuthApi calls (register/login/logout/refresh/
                         forgot-password), translates ApiResponse<T>/ErrorResponse
                         into a domain Result type; the ONLY thing that calls AuthApi
      ↓
SessionManager (N3.2) — the single source of truth for "am I logged in," exposes
                         current tokens + a SessionState flow; owns writing to/
                         clearing TokenStorage; the ONLY thing every other
                         layer (AuthRepository AND the OkHttp layer) reads from
      ↓
TokenStorage (N3.1) — EncryptedSharedPreferences wrapper, get/set/clear access +
                       refresh token strings, nothing else
      ↓
AuthInterceptor + Authenticator (N3.3) — reads TokenStorage (or SessionManager)
                                          directly at the OkHttp layer; does NOT
                                          go through AuthRepository (that would be
                                          circular — AuthRepository itself makes
                                          HTTP calls through this same client)
      ↓
Retrofit → AuthApi / every other *Api
```

**No separate "use case" classes recommended** — `AuthRepository`'s methods
(`register`, `login`, `logout`, `refresh`, `requestPasswordResetOtp`,
`verifyPasswordResetOtp`, `resetPassword`) already are the use cases; a thin
`RegisterUseCase`/`LoginUseCase` wrapper around a single repository call each
would be pure ceremony for this app's current complexity. Revisit only if
business logic genuinely accumulates around a call (e.g. client-side password
strength checks before calling `register`) that doesn't belong in either the
ViewModel or the repository.

**Two new architectural components, not more:** `AuthRepository` and
`SessionManager`. `TokenStorage` and `AuthInterceptor`/`Authenticator` are
existing-pattern extensions (a Jetpack Security wrapper class; two more OkHttp
hooks alongside the client already in `NetworkModule`), not new architectural
layers in their own right.

### 11. Token storage (design only — nothing implemented; deferred to N3.1)

- **Where:** a single `EncryptedSharedPreferences` file (Jetpack Security
  Crypto, `androidx.security:security-crypto`), storing two string values —
  `access_token`, `refresh_token`. AES-256-GCM value encryption + AES-256-SIV
  key encryption, backed by a key generated/held in the Android Keystore
  (hardware-backed on most devices) — this is the standard, currently-recommended
  Android mechanism for exactly this use case (small secret strings), not a
  custom Keystore integration.
- **Access vs. refresh together:** stored in the *same* encrypted file (no
  security benefit to separating them — see Section 13 below on why this
  backend's refresh-token model makes the usual separation-of-concerns argument
  moot), but as two independent keys so either can be updated without touching
  the other (relevant for a future world where the backend *does* start
  distinguishing them).
- **Survive app restart:** yes — that's the entire point; `EncryptedSharedPreferences`
  persists to disk like normal `SharedPreferences`, just encrypted at rest.
- **Survive process death:** yes, same mechanism (disk-backed, not in-memory).
- **Logout clears them:** `SessionManager.clear()` (or equivalent) calls through
  to `TokenStorage.clear()`, wiping both keys — this is the *only* thing "logout"
  can meaningfully do given the backend has no server-side state to invalidate
  (Section 7).
- **App startup restoration:** `SessionManager` reads both stored tokens once at
  startup; presence of a non-null access token (even if expired — expiry is
  checked lazily on first use via the interceptor/authenticator, not proactively
  parsed at startup) is treated as "was logged in," yielding an optimistic
  `Authenticated` state that a subsequent 401 + failed refresh can still
  downgrade (see Section 12's state diagram).
- **Token updates:** every successful login/register/refresh response's
  `accessToken`/`refreshToken` pair overwrites both stored values atomically
  (a single `SharedPreferences.Editor` commit covering both keys — never a
  window where one is updated and the other isn't).

**Not decided/deferred to N3.1 itself:** exact `EncryptedSharedPreferences` file
name, exact Hilt provision shape for the wrapper class, whether
`MasterKey.Builder` uses the default scheme or a custom alias — implementation
detail, not architecture.

### 12. Session state machine (design only)

```
        App start
            │
            ▼
      ┌──────────┐
      │  Unknown  │  (SessionManager reading TokenStorage)
      └─────┬─────┘
            │
   token present?  ──── no ───►  Unauthenticated ──► Auth UI (N3.5/N3.6)
            │ yes
            ▼
      Authenticated (optimistic — access token not proactively validated)
            │
     API call made
            │
    401 response? ──── no ───►  stays Authenticated
            │ yes
            ▼
   Authenticator triggers single refresh (Section 14)
            │
     refresh succeeds? ──yes──► new tokens stored ──► Authenticated,
            │                                          original request retried
            │ no (401/expired refresh, or refresh itself network-fails
            │     in a way that isn't retryable)
            ▼
      TokenStorage cleared ──► Unauthenticated ──► Auth UI (N3.6 handles
                                                     the actual navigation)
```

Only three states are needed — `Unknown` (transient, resolved synchronously
before first Compose frame that depends on it), `Authenticated`,
`Unauthenticated`. No separate `Authenticating` state is needed for *session
restoration* (it's synchronous local storage reads, not a network call) — an
`Authenticating` UI state belongs to the login/register *screen* itself (N3.5),
not this session state machine. Lives in `SessionManager` as a
`StateFlow<SessionState>`, the single source of truth N3.6 (app startup/nav)
and N3.5 (auth UI) both observe.

### 13. OkHttp interceptor architecture (design only — nothing implemented; deferred to N3.3)

- **A single `AuthInterceptor`** attaches `Authorization: Bearer <accessToken>`
  to every outgoing request, reading the current token from `SessionManager`/
  `TokenStorage` synchronously (no suspend function inside an OkHttp
  interceptor — `TokenStorage` reads must be synchronous `SharedPreferences`
  calls, not `DataStore`'s suspend/Flow API, which is one concrete reason to
  prefer `EncryptedSharedPreferences` over `DataStore` here).
- **`AuthApi` requests must bypass token attachment for `register`/`login`**
  (no token exists yet) **but not for `logout`/`refresh`/forgot-password** in
  the same interceptor-bypass sense — `refresh` doesn't need the interceptor's
  *access*-token header (it sends the refresh token in its body instead, per
  Section 6), and the backend's `permitAll()` on all of `/api/v1/auth/**` means
  attaching a (possibly stale) access token to these calls is harmless, not
  required. Simplest correct rule: **the `AuthInterceptor` always attaches
  whatever access token is currently stored (or attaches nothing if none
  exists) and never special-cases individual paths** — since every `/auth/**`
  endpoint ignores the header anyway (`permitAll()`), no path-based
  bypass logic is needed at all. This keeps the interceptor a single,
  unconditional rule instead of a per-endpoint allowlist that would need
  updating every time a new `AuthApi` endpoint is added.
- **Recursive-interception avoidance for the refresh call itself:** the actual
  HTTP call `POST /auth/refresh` makes must go through a **plain, un-authenticated
  Retrofit/OkHttp client** (or at minimum bypass the `Authenticator` described
  below), not the same client whose `Authenticator` is what triggers refresh in
  the first place — otherwise a failed refresh call could itself 401 and
  re-trigger the `Authenticator`, looping. Concretely: `AuthApi.refresh(...)`
  should be invoked directly by `AuthRepository`/`SessionManager` using
  Retrofit's existing `AuthApi` instance (already provided via the shared
  `Retrofit` in `NetworkModule`) but the `OkHttpClient.Authenticator` hook
  (below) must be the *only* place that decides to call it reactively — the
  `Authenticator` itself must not be attached to the request it makes to
  `/auth/refresh`, which OkHttp's `Authenticator` contract already guarantees
  isn't re-entered for the same failed-request chain by default, but this is
  worth an explicit test in N3.3, not just an assumption.
- **401 responses should trigger refresh automatically** via OkHttp's
  `Authenticator` interface (`okhttp3.Authenticator`), not the `Interceptor`
  interface — `Authenticator.authenticate(route, response)` is specifically
  designed for this reactive "got a 401, get a new credential, retry once"
  pattern, and integrates cleanly with OkHttp's retry-once-per-chain default
  (`response.priorResponse() == null` check to avoid infinite retry loops on a
  request that 401s even with a fresh token).
- **Failed refresh transitions to logged-out:** the `Authenticator` calls
  `SessionManager.clear()` (Section 11) and returns `null` (telling OkHttp not
  to retry) when refresh itself fails — `SessionManager`'s `StateFlow`
  transitioning to `Unauthenticated` is what N3.6's navigation layer reacts to,
  not anything OkHttp-specific.

### 14. Refresh concurrency strategy (design only — nothing implemented; deferred to N3.3)

Multiple concurrent 401s (the prompt's "5 requests fail simultaneously" scenario)
must produce **exactly one** `/auth/refresh` call, not five. Recommended: a
`kotlinx.coroutines.sync.Mutex` (or `synchronized` block, since
`Authenticator.authenticate` is called on an OkHttp dispatcher thread, not a
coroutine — a plain `Mutex.withLock` used from a blocking context via
`runBlocking`, or a `synchronized` block calling a blocking Retrofit call, are
both viable; the exact mechanism is an N3.3 implementation detail) around the
refresh-and-store operation, plus a **"is the token I'm about to refresh still
the one that just failed"** check: each of the 5 blocked threads, once it
acquires the lock, first re-reads the current stored access token — if it
already differs from the token that caused *this specific* 401 (because an
earlier thread already refreshed it while this one was waiting), it uses the
already-refreshed token directly and skips calling `/auth/refresh` again. Only
the first thread to reach the lock with a still-stale token actually performs
the network call; the other four observe the update and retry with the new
token for free. This is the standard "compare-and-refresh" pattern for exactly
this OkHttp `Authenticator` scenario — not a new invention for this project.

### 15. App startup / session restoration (design only — deferred to N3.6; N4 owns the shell)

```
App process starts
      │
      ▼
SessionManager reads TokenStorage synchronously (fast, local, no I/O beyond
disk-backed SharedPreferences — acceptable to do before first Compose frame)
      │
      ▼
SessionState = Authenticated or Unauthenticated (Section 12) published
immediately, no Unknown state persists past this synchronous read
      │
      ▼
Navigation layer (N3.6, reading N4's eventual app shell) picks a start
destination based on SessionState: Authenticated → main app graph,
Unauthenticated → auth graph
```

No proactive access-token *validation* (e.g. decoding `exp` client-side) is
recommended at startup — the app optimistically assumes a stored token is
still good and lets the first real API call's 401 (if any) drive the
refresh-or-logout flow from Section 12/14. This avoids duplicating JWT parsing
logic in the app for a check that the first API call will perform anyway (and
avoids clock-skew edge cases from comparing device time to `exp` client-side).

### 16. Security review (documenting weaknesses, not silently working around backend limitations)

- **Token storage:** `EncryptedSharedPreferences` is the currently-correct
  Android mechanism for this data — no weaker fallback recommended.
- **Access-token exposure:** every authenticated request carries it in a header
  (standard, unavoidable); it must never be logged. `NetworkModule` currently
  has **no** `HttpLoggingInterceptor` — recommend explicitly that if one is ever
  added (debug builds only), it must use `Level.BASIC` or a custom redactor,
  never `Level.HEADERS`/`Level.BODY`, which would print the raw JWT and
  password fields to Logcat.
- **Refresh-token exposure:** sent in a JSON body (not a header) to one
  specific endpoint — fine — but because it is **structurally indistinguishable
  from an access token** (Section 5), if it were ever accidentally attached as
  a Bearer header to a normal API call (a bug, not the designed flow), the
  backend would accept it exactly like an access token. This is a backend
  characteristic to design around carefully in N3.3, not an Android-side flaw
  to compensate for with extra complexity — the mitigation is simply "never
  read the refresh token into the same variable/field the access token flows
  through," an implementation discipline for N3.1-N3.3, not a new component.
- **Logging:** no token value should ever appear in a `Log.d`/`Log.e` call
  anywhere in the auth layer being designed — a rule for N3.1-N3.7's
  implementation, noted here as a requirement.
- **Accidental persistence:** tokens must live *only* in `TokenStorage`
  (`EncryptedSharedPreferences`) — never duplicated into a `ViewModel`'s saved
  instance state, a `Bundle`, or plain (non-encrypted) `SharedPreferences`.
- **Screenshots/Logcat:** `FLAG_SECURE` on auth screens (N3.5) is a UI concern
  for preventing screenshots of a password field, not a token-storage concern
  — noted here as a cross-reference for N3.5, not designed further in N3.0.
- **Backup behavior:** `EncryptedSharedPreferences` files are, by default,
  eligible for Android Auto Backup unless excluded — N3.1 must add an explicit
  backup-exclusion rule (`android:fullBackupContent`/`dataExtractionRules`,
  standard Android manifest configuration) for the file(s) it creates, so an
  encrypted-at-rest token doesn't get backed up to a Google account and
  restored onto a different device with a different Keystore key (which would
  make it unreadable anyway, but excluding it is the correct hygiene).
- **HTTPS for production:** already correctly handled by the existing
  `BuildConfig.BASE_URL` split — release builds already point at
  `https://app.myconnect.sbs/`; debug builds intentionally use plaintext HTTP to
  the emulator's `10.0.2.2` host loopback (standard local-dev practice, not a
  production exposure). **No change needed or recommended here** — this is
  exactly Step 14's "must remain independent of the actual hostname" requirement,
  already satisfied by existing N1/N2 architecture. Nothing in the auth design
  above reads or hardcodes either URL.
- **Backend-side limitation, documented, not compensated for:** no server-side
  refresh-token revocation exists (Section 6/7). A genuinely stolen refresh
  token remains valid for up to 7 days regardless of anything the Android app
  does (uninstalling the app, clearing storage, or "logging out" all only
  affect the *local* copy — they cannot invalidate the token itself). This is
  a real security gap in the current backend, out of scope to fix from the
  Android side, and out of scope for N3 to silently work around with, e.g., a
  client-side token blacklist (which would provide no real security benefit
  since the attacker's copy of the token is unaffected by the victim's device
  state). Recorded as an open backend limitation below.

### 17. Domain/base-URL independence — confirmed preserved

No hostname, `10.0.2.2`, or `app.myconnect.sbs` string appears anywhere in this
design. Every layer above (`AuthRepository`, `SessionManager`, `AuthInterceptor`,
`Authenticator`) is specified purely in terms of `AuthApi`'s existing
Retrofit-relative paths and the shared `Retrofit`/`OkHttpClient` instances
already built from `BuildConfig.BASE_URL` in `NetworkModule` — no new base-URL
source is introduced.

### 18. Explicit N3.0 boundaries (what this phase did NOT do)

No `TokenStorage`, `SessionManager`, `AuthRepository`, `AuthInterceptor`,
`Authenticator`, `AuthViewModel`, or any auth UI/navigation code was created.
`AuthApi.kt` was **not** extended with the 5 additional endpoints found — that
remains an open decision for whichever N3 sub-phase actually needs to call them
(most likely N3.2, when `AuthRepository` is built and needs `refresh`/`logout`/
forgot-password contracts to wrap). No Gradle/dependency changes were made — the
recommended `androidx.security:security-crypto` dependency for N3.1 was
**not** added in this phase. `docs/CONNECTX_ANDROID_DEVELOPMENT.md` is the only
file touched.

### 19-20. Open questions / backend limitations for N3 to carry forward

1. **AuthApi's 5 missing endpoints** (`logout`, `refresh`, and the 3
   forgot-password endpoints) must be modeled before N3.2 (`AuthRepository`) can
   be built — recommend doing so as the first concrete step of N3.2 itself
   (extending the existing, already-correct `AuthApi.kt`/model files), not as a
   silent addition during this phase.
2. **No server-side refresh-token revocation** (Section 6/7/16) — a backend
   limitation, not something N3 should attempt to paper over from the client.
3. **Access/refresh token structural indistinguishability** (Section 5) — a
   backend characteristic N3.3's `AuthInterceptor`/`Authenticator` design
   already accounts for (Section 13/16), but worth flagging to product/backend
   owners as a candidate for a future backend fix (e.g. adding a `typ` claim)
   outside N3's scope.
4. **`POST /auth/refresh`'s untyped `Map<String,String>` body** (Section 3) means
   the eventual Android `AuthApi.refresh(...)` method should model its request
   as a small dedicated `RefreshTokenRequest(refreshToken: String)`
   `@Serializable` data class (encodes to the same `{"refreshToken":"..."}`
   shape) rather than passing a raw `Map` through Retrofit — a modeling detail
   for whichever phase adds the endpoint, noted here so it isn't relitigated.
5. **OTP rate-limiting is in-memory, single-instance only** (Section 8) — not an
   Android concern, but worth knowing that a backend redeploy/restart resets
   the cooldown state; not a blocker for N3.

**Files inspected:** listed in "1-3" above (14 backend files) plus the current
`AuthApi.kt`/`AuthResponse.kt`/`LoginRequest.kt`/`RegisterRequest.kt`/
`UserDto.kt`/`ApiResponse.kt`/`ErrorResponse.kt`/`NetworkModule.kt` on the
Android side.

**Files modified:** `docs/CONNECTX_ANDROID_DEVELOPMENT.md` only (this section
plus the Phase Status/changelog updates). No Kotlin source, no Gradle file, no
backend file, no frontend file was changed.

**HTTP requests made: zero.** No `curl`, no Postman, no code path invoking
`AuthApi` or any other API interface was executed.

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase — identical set of modified/untracked paths
(`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same pre-existing unrelated
backend/frontend edits already present at N2.7's checkpoint) — nothing staged,
committed, restored, reset, or cleaned.

**N3.0 status: COMPLETE. N3.1 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.1 (secure token/session
storage).

## 36. N3.1 — Secure Token & Session Storage

**N3.1 — Secure Token & Session Storage: COMPLETE.**

Implements exactly the first two foundations from N3.0's approved architecture
(`TokenStorage → SessionManager`) and nothing past that boundary — no
`AuthRepository`, no `AuthInterceptor`/`Authenticator`, no login/register, no
auth UI, no navigation change.

### 1. Jetpack Security dependency

Not previously present — confirmed by inspecting `gradle/libs.versions.toml`
before adding anything (no `security-crypto`/`androidx.security` entry existed).
Added `androidx.security:security-crypto` **1.1.0** (current stable) as a new
version-catalog entry (`securityCrypto`) and library alias
(`androidx-security-crypto`), then `implementation(libs.androidx.security.crypto)`
in `app/build.gradle.kts`. No other dependency was added or upgraded.

**Known deprecation, documented not hidden:** `EncryptedSharedPreferences` and
`MasterKey` are marked `@Deprecated` as of `security-crypto` 1.1.0 (Google's own
Javadoc points toward Tink directly / a future replacement API, without yet
shipping a stable drop-in successor). The build compiles clean aside from these
deprecation warnings — no error, no behavior change. This is the
currently-correct, still-functioning, officially-shipped mechanism for exactly
this use case; a future N3 sub-phase (or a dedicated storage-migration phase)
should revisit if/when Google ships a non-deprecated successor, but switching
now would mean adopting an unstable/undocumented replacement mid-phase, which
N3.1's scope does not call for.

### 2-3. TokenPair / TokenStorage

`data/local/auth/TokenPair.kt` — a small `data class TokenPair(accessToken:
String, refreshToken: String)`. **Not** a reuse of
`data/remote/auth/model/AuthResponse` — that network DTO also carries
`tokenType`/`user` (a full `UserDto`) and is a `@Serializable` JSON model shaped
by the backend contract; coupling local storage to it would leak the network
layer into storage and couple two unrelated concerns (an unrelated `UserDto`
field change would have no reason to touch storage code). `TokenPair` is the
entire, deliberately tiny boundary between the two.

`data/local/auth/TokenStorage.kt` — interface with exactly the six methods
specified: `saveTokens(accessToken, refreshToken)`, `getAccessToken()`,
`getRefreshToken()`, `getTokens(): TokenPair?`, `clearTokens()`,
`hasTokens(): Boolean`. `getTokens()`/`hasTokens()` only ever report a
**complete** pair — never a partial one (see "6. Partial-token behavior" below).

### 4. Encrypted storage implementation

`data/local/auth/EncryptedTokenStorage.kt` — the sole `TokenStorage`
implementation. Uses `EncryptedSharedPreferences.create(...)` with a
`MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`
(Android Keystore-backed, hardware-backed on most devices),
`PrefKeyEncryptionScheme.AES256_SIV` for keys, `PrefValueEncryptionScheme.AES256_GCM`
for values — the standard Jetpack Security configuration for this exact use
case, no custom Keystore integration written. The underlying `SharedPreferences`
instance is a `private val` — never exposed outside the class; every caller
talks to the six-method `TokenStorage` interface only.

### 5. Storage keys

Preferences file: `connectx_secure_auth_prefs`. Keys: `access_token`,
`refresh_token` — both stored as encrypted `String` values.

### 6. Token persistence / partial-token behavior

`saveTokens` writes both keys through a single `Editor` and a single
**synchronous** `commit()` (not `apply()`) — chosen deliberately so a reader on
another thread can never observe one token updated and the other still stale,
and so a save's success/failure is known before the call returns (relevant for
N3.2's future `AuthRepository`, which will want to know a save actually landed
before updating in-memory state). `clearTokens()` removes both keys the same
way.

**Partial-token decision (Step 9, explicitly required to be documented):** if
only one of the two tokens is present in storage, `getTokens()` returns `null`
and `hasTokens()` returns `false` — the pair is never treated as usable, and
N3.1 makes **no attempt to repair or guess** which value (if either) is still
trustworthy. `SessionManager.restoreSession()` (below) is the layer that acts
on this: on detecting exactly one token present, it calls `clearTokens()`
outright and transitions to `Unauthenticated`, rather than leaving a half-valid
state sitting in storage. This can only realistically happen from an
interrupted/corrupted prior write; treating it as "no session" is the safe
default.

### 7-9. SessionState / SessionManager / restoration

`core/session/SessionState.kt` — `sealed interface` with exactly the three
approved states as `data object`s: `Unknown`, `Authenticated`,
`Unauthenticated`. No `Loading`/`Refreshing`/`Error`/`LoggingIn` — those are
explicitly out of scope for this foundational layer.

`core/session/SessionManager.kt` — `@Singleton`, constructor-injects
`TokenStorage`, exposes `val sessionState: StateFlow<SessionState>` (backed by
a private `MutableStateFlow`, starting at `Unknown`). Three operations, exactly
as scoped:
- `restoreSession()` — reads `getTokens()`; a complete pair →
  `Authenticated`. Otherwise checks whether either individual token is present
  (a partial pair) and, if so, clears storage before publishing
  `Unauthenticated`. **No JWT decoding, no `exp` parsing, no network call** —
  purely local, purely synchronous, matching N3.0's explicit "optimistic local
  restoration" decision (the first real API call's 401, in N3.3/N3.7, is what
  actually discovers an expired token).
- `setAuthenticated(accessToken, refreshToken)` — saves the pair and publishes
  `Authenticated`. Exists now so N3.2's `AuthRepository` has something to call
  after a successful login/register/refresh, without N3.1 needing to guess at
  `AuthRepository`'s own shape.
- `clearSession()` — clears storage and publishes `Unauthenticated`. **No
  backend call** — `POST /auth/logout` has no server-side revocation to
  trigger (N3.0 Section 7), so a network call here would accomplish nothing;
  local-only clearing is the entire correct behavior for this phase, and
  remains so even once N3.2 adds a "best-effort" server logout call (that
  would be additive, not something this method needs to know about).

### 10. Logout behavior — see `clearSession()` above; intentionally local-only,
per N3.0's finding that the backend's logout endpoint has no server-side effect
to call.

### 11. Hilt integration

`data/local/auth/AuthStorageModule.kt` — a new, minimal `@Module
@InstallIn(SingletonComponent::class) abstract class` with one `@Binds
@Singleton abstract fun bindTokenStorage(impl: EncryptedTokenStorage):
TokenStorage`. Deliberately **not** added to the existing `NetworkModule`
(HTTP/Retrofit concerns only) — token storage is a separate, application-level
persistence boundary. `SessionManager` needs no explicit `@Provides`/`@Binds`
— its `@Inject constructor(private val tokenStorage: TokenStorage)` plus
`@Singleton` is enough for Hilt to construct it directly. `NetworkModule.kt`
itself was **not modified**.

### 12. Backup/security configuration

`android:allowBackup="true"` was already present (pre-existing, from N1), with
already-referenced-but-empty `backup_rules.xml` (legacy pre-API 31 full backup)
and `data_extraction_rules.xml` (API 31+ cloud backup / device transfer) —
neither file previously excluded anything, so the new encrypted preferences
file would otherwise have been backed up by default. Added exactly one
`<exclude domain="sharedpref" path="connectx_secure_auth_prefs.xml"/>` rule to
each of the three relevant scopes: `backup_rules.xml`'s `<full-backup-content>`,
and both `<cloud-backup>` and `<device-transfer>` inside
`data_extraction_rules.xml`. No other backup policy was touched — `allowBackup`
itself was left as-is (broader than this phase's scope to revisit), and no
other preference file/domain was added to either exclusion list. Rationale
documented inline in both XML files: the Keystore-backed encryption key is
non-exportable, so a backed-up/transferred copy of the encrypted file would be
permanently undecryptable garbage on any other device anyway — excluding it is
correct hygiene regardless.

(One iteration note: an initial draft of both XML comments used a `--`
double-hyphen as a stylistic separator, which XML forbids inside comments —
`parseDebugLocalResources`/`mergeDebugResources` failed with `"The string "--"
is not permitted within comments"` until reworded. Caught and fixed before this
report, not left in the codebase.)

### 13. Tests

`app/src/test/java/com/connectx/app/core/session/SessionManagerTest.kt` — **7
new JVM tests**, using a hand-written in-memory `FakeTokenStorage` (plus two
narrower single-field fakes for the partial-token cases) implementing the
plain-Kotlin `TokenStorage` interface — no mocking framework introduced, no
Android/instrumented test environment needed, exactly as instructed. Covers:
initial `Unknown` state, restore-with-no-tokens, restore-with-complete-pair,
restore-with-only-access-token (clears + `Unauthenticated`),
restore-with-only-refresh-token (clears + `Unauthenticated`), `clearSession`,
and `setAuthenticated` transitioning `Unauthenticated → Authenticated`.

**Test results: 70/70 total, 0 failures** (63 pre-existing contract tests + 7
new `SessionManagerTest` tests). No existing test was modified.

### 14. Encrypted-storage runtime verification

**Not separately verified via an instrumented Android test in this phase** —
per Step 13's explicit permission to substitute build + Hilt graph compilation
+ unit-test behavior + documentation when an instrumented environment isn't
warranted for this phase's scope. What WAS verified: `assembleDebug` succeeds
(confirming `EncryptedTokenStorage`/`AuthStorageModule` compile and the Hilt
graph resolves `TokenStorage` correctly), and a fresh install + launch on both
the Pixel 7a emulator and the Vivo device (below) completed with zero crashes —
since `ConnectXApplication` is `@HiltAndroidApp`, a broken Hilt binding
(missing/circular/wrong-scope) would have failed at app startup, not silently;
a clean launch is meaningful evidence the graph — including
`EncryptedTokenStorage`'s constructor actually running (`MasterKey`/
`EncryptedSharedPreferences.create` execute during Hilt's eager singleton
graph validation path in this app's structure) — initializes without error on
real hardware. **Actually writing/reading a token value through
`EncryptedTokenStorage` on a device was not exercised** (nothing in N3.1 calls
`saveTokens`/`getAccessToken` yet — there is no call site until N3.2's
`AuthRepository` exists). This remains an explicit follow-up verification item
once N3.2 provides a real caller — noted here rather than fabricated.

### 15. Build / device verification

`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, 70/70 pass. `./gradlew
assembleDebug` → BUILD SUCCESSFUL (compiler deprecation warnings only, see
"1. Jetpack Security dependency" above — no errors). Installed the resulting
debug APK on:
- **Pixel 7a emulator** (`emulator-5554`, confirmed via `adb emu avd name` →
  `Pixel_7a`) — fresh `install -r`, `am start`, live PID confirmed
  (`pidof com.connectx.app`), zero `FATAL`/`AndroidRuntime`/`Exception` in a
  200-line logcat tail.
- **Vivo device** (model `V2503`, reachable via wireless ADB this session,
  matching the "Vivo X200 FE" device referenced in earlier N2 phases) — same
  fresh `install -r`, `am start`, live PID, zero crash lines in logcat.

Neither device's HOME/Navigation Test/Design Showcase screens were touched or
re-verified beyond confirming the app launches and stays running — N3.1 made
no UI change, so there was nothing new to click through.

### 16. Explicit N3.1 boundaries (what this phase did NOT do)

No `AuthRepository`, `AuthInterceptor`, `Authenticator`, login/register use
case, auth ViewModel, auth UI, or navigation change was created. `AuthApi.kt`
was not extended (still register/login only, per N3.0's open item, deferred to
N3.2). `SessionManager.restoreSession()` is not called from anywhere yet — no
app-startup call site exists (that's N3.6). No JWT parsing/expiration logic
exists anywhere. No network request — authentication or otherwise — was made
from any new code. `connectx-backend/` and `connectx-frontend/` were not
touched. `BuildConfig.BASE_URL` was not referenced by any new file.

### 17. Deferred to N3.2+

Extending `AuthApi.kt` with the 5 previously-unmodeled endpoints
(`logout`/`refresh`/forgot-password family, per N3.0 Section 35 §19-20),
building `AuthRepository` on top of `SessionManager` + the extended `AuthApi`,
then `AuthInterceptor`/`Authenticator` (N3.3), login/register use cases (N3.4),
auth UI (N3.5), auth navigation/app-startup wiring that actually calls
`restoreSession()` (N3.6), refresh/error handling (N3.7), and the final N3
verification/checkpoint (N3.8).

**Files created:** `data/local/auth/{TokenPair,TokenStorage,
EncryptedTokenStorage,AuthStorageModule}.kt`, `core/session/{SessionState,
SessionManager}.kt`,
`app/src/test/java/com/connectx/app/core/session/SessionManagerTest.kt`.

**Files modified:** `gradle/libs.versions.toml` (added `securityCrypto` version
+ `androidx-security-crypto` library alias), `app/build.gradle.kts` (added the
one dependency line), `app/src/main/res/xml/{backup_rules,
data_extraction_rules}.xml` (added the targeted exclusion rules above),
`docs/CONNECTX_ANDROID_DEVELOPMENT.md` (Phase Status + this section).
`NetworkModule.kt` was **not** modified.

**No authentication network requests were made** — no code path anywhere
invokes `AuthApi` or any other API interface; `SessionManager`/`TokenStorage`
are entirely local-storage-only in this phase.

**No UI was changed** — HOME, Navigation Test, and Design System Showcase are
untouched; no new screen, button, or navigation destination was added.

**No backend/frontend files were changed** — confirmed via `git status --short`
before and after (below).

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase — identical set of pre-existing modified/untracked
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same unrelated
backend/frontend edits already present since the N2 checkpoint) — nothing
staged, committed, restored, reset, or cleaned. N3.1 is explicitly not a Git
checkpoint (per instructions) — no `git add`/`commit` was performed; the
checkpoint happens at N3.8.

**N3.1 status: COMPLETE. N3.2 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.2 (`AuthRepository`).

## 37. N3.2 — Authentication Repository

**N3.2 — Authentication Repository: COMPLETE.**

### 1. Repository responsibility

`AuthRepository` (`data/remote/auth/AuthRepository.kt`) is the sole boundary
between the network authentication contract (`AuthApi`) and application
authentication state (`SessionManager`, and — indirectly through it —
`TokenStorage`). Future ViewModels/UI are meant to depend on this interface
only, never on `AuthApi`, `TokenStorage`, or `SessionManager` directly. Two
authentication operations (`register`, `login`) plus a local-only `logout` —
nothing else. No `AuthUseCase`/`LoginUseCase`/`RegisterUseCase`/
`AuthDataSource`/`AuthManager` was created; inspection found no genuine need
for an extra layer between the repository and a future ViewModel — the
repository's three methods already are the "use cases" at this app's current
complexity (matches the reasoning already recorded in Section 35 §10).

### 2. AuthApi integration (re-verified, unchanged)

Re-read `AuthController`/`AuthService` directly for this phase (not assumed
from N3.0/N3.1's reports): `POST /api/v1/auth/register` and `POST
/api/v1/auth/login` are unchanged, still exactly the two endpoints `AuthApi`
already models. **Confirmed from source, not assumed:** both endpoints return
a real, immediately-usable access+refresh token pair in the same call —
`AuthService.register` mints tokens via `tokenProvider.generateToken(...)`/
`generateRefreshToken(...)` in the same method that creates the account, no
separate email-verification gate blocks it. This is why registration
legitimately establishes the authenticated session in this repository, not a
fabricated assumption (Step 4's explicit warning against assuming this).
`AuthApi.kt` itself was **not modified** — still register/login only, per
N3.0's still-open item (the 5 additional endpoints remain deferred).

### 3-4. Login / registration flow

Both `register(request)` and `login(request)` funnel through one private
`authenticate(call)` helper (`AuthRepositoryImpl.kt`) to avoid duplicating the
validate → persist → publish sequence:

```
call() -> ApiResponse<AuthResponse>
   |
   +- HttpException      -> AuthResult.ApiError   (parsed ErrorResponse)
   +- IOException         -> AuthResult.NetworkError
   +- SerializationException -> AuthResult.UnexpectedError
   |
response.success == false          -> AuthResult.InvalidResponse
response.data == null              -> AuthResult.InvalidResponse
accessToken/refreshToken blank     -> AuthResult.InvalidResponse
   |
sessionManager.setAuthenticated(accessToken, refreshToken)  [may throw]
   |
   +- throws -> AuthResult.UnexpectedError (session never flips)
   +- succeeds -> AuthResult.Success(user)
```

### 5. Token validation before persistence

Neither `accessToken` nor `refreshToken` is ever handed to `SessionManager`
without both first being checked non-blank (Kotlin's non-nullable `String`
type on `AuthResponse` already rules out `null`, so a genuinely "missing"
token would only ever arrive as an empty string from a malformed backend
response — `isBlank()` is the correct check for that). A blank token on
either field short-circuits to `AuthResult.InvalidResponse` **before** any
call to `SessionManager`/`TokenStorage` — confirmed by the "missing access
token"/"missing refresh token" tests asserting `getAccessToken()`/
`getRefreshToken()` stay `null` afterward.

### 6-7. TokenStorage / SessionManager integration

`AuthRepositoryImpl` depends on `SessionManager` only — it **never**
references `TokenStorage` directly (Step 9's explicit requirement), matching
`AuthRepository`'s own doc comment. `SessionManager.setAuthenticated(...)`
already does "save tokens, then flip state" as a single call (established in
N3.1); because the state assignment is the second statement in that method,
an exception thrown while saving (e.g. a persistence failure) means the state
assignment is never reached — the session is provably never marked
`Authenticated` off the back of a failed save, without N3.2 needing to modify
`SessionManager`'s or `TokenStorage`'s signatures to add an explicit
success/failure return value. `AuthRepositoryImpl` wraps that call in its own
`try/catch` so a persistence failure still produces a clean `AuthResult`
(`UnexpectedError`) instead of an uncaught exception reaching a future
ViewModel.

### 8. Error/result architecture

`AuthResult` (`data/remote/auth/AuthResult.kt`) — a flat, 5-variant `sealed
interface`: `Success(user: UserDto)`, `ApiError(status: Int?, code: String?,
message: String?)`, `NetworkError(message: String?)`,
`InvalidResponse(message: String)`, `UnexpectedError(message: String?)`. No
nested hierarchy, no retry metadata, no request-id tracking, no per-field
validation-error type — this is authentication only, not a general-purpose
network error framework (Step 7's explicit instruction). `ApiError` carries
the backend's actual `status`/`code`/`message` where parseable (from
`ErrorResponse`, reusing the exact model N2.1 already created — no new
serialized model introduced, no duplicate serialization tests added, per Step
17), falling back to the raw `HttpException`'s own status/message if the
error body isn't parseable JSON for some reason.

### 9. Network failure handling

`IOException` (connection refused, timeout, host unresolvable, etc.) is
caught separately from `HttpException` (a real HTTP response, just a
non-2xx one) and separately again from `SerializationException` (a malformed/
unparseable response body — a real bug, not a network condition) — three
distinct, non-overlapping catch blocks, exactly the three failure boundaries
Step 8 asked to be kept apart from each other and from
`AuthResult.InvalidResponse` (a well-formed, successful HTTP response that
just doesn't carry what's needed).

### 10. Local logout behavior

`AuthRepository.logout()` is a synchronous, non-suspending, thin delegate to
`SessionManager.clearSession()` — clears both tokens and publishes
`Unauthenticated`. **No network request** — re-confirmed from N3.0 that
`/auth/logout` has no server-side revocation effect, so calling it would
accomplish nothing; a future phase could add a best-effort server call on top
without changing what this method means today.

### 11. Explicit refresh deferral

No `POST /auth/refresh` call, no refresh logic, no `Authenticator`, no
refresh mutex/single-flight coordination, no automatic 401 handling exists
anywhere in this phase's code — all explicitly deferred to N3.3/N3.7, per
instructions.

### 12. Hilt integration

`AuthRepositoryModule` (`data/remote/auth/AuthRepositoryModule.kt`) — one new,
minimal `@Module @InstallIn(SingletonComponent::class) abstract class` with a
single `@Binds @Singleton abstract fun bindAuthRepository(impl:
AuthRepositoryImpl): AuthRepository`, matching the exact pattern
`AuthStorageModule` already established in N3.1. `NetworkModule.kt` was
**not modified** — `AuthApi` and the shared `Json` bean it already provides
are reused as-is; `SessionManager` needs no module (self-provided via its own
`@Inject constructor`, unchanged since N3.1).

### 13. Tests

`app/src/test/java/com/connectx/app/data/remote/auth/AuthRepositoryImplTest.kt`
— **9 new JVM tests**, using a hand-written `FakeAuthApi` (configurable
per-test `register`/`login` lambdas that return a canned `ApiResponse` or
`throw`) and a hand-written `FakeTokenStorage` (with a `throwOnSave` flag to
simulate a persistence failure) — no mocking framework, no real
Retrofit/OkHttp call, no network. A **real** `SessionManager` sits on top of
the fake storage in every test — deliberately not faked, since exercising the
actual `SessionManager` is what proves the repository's wiring into it is
correct, not just that the repository calls some interface method. Covers:
successful login (persists + `Authenticated` + `Success(user)`), successful
register (same), token-persistence failure (`UnexpectedError`, session stays
`Unknown`), missing access token, missing refresh token, a `success: false`
envelope, a real `retrofit2.HttpException` constructed via
`Response.error(...)` around a hand-written `ErrorResponse` JSON body
(`ApiError` with parsed `status`/`code`/`message`), a raw `IOException`
(`NetworkError`), and `logout()` clearing both storage and session state.

**Test results: 79/79 total, 0 failures** (70 pre-existing + 9 new). No
existing test was modified.

### 14. Build verification

`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, 79/79 pass. `./gradlew
assembleDebug` → BUILD SUCCESSFUL, no errors (Hilt graph resolves
`AuthRepository` correctly through the new module).

### 15. Emulator / device verification

Installed the resulting debug APK on:
- **Pixel 7a emulator** (`emulator-5554`) — fresh `install -r`, `am start`,
  live PID (`pidof com.connectx.app`), zero `FATAL`/`AndroidRuntime`/
  `Exception` in a 200-line logcat tail.
- **Vivo device** (model `V2503`, reachable via wireless ADB this session) —
  identical fresh install/launch/live-PID/clean-logcat verification.

No login/register UI exists yet (N3.5), so verification was necessarily
launch-only — the same scope as N3.1's device verification, since N3.2 adds
no visible surface to interact with. No authentication network request
occurs merely from launching the app (`AuthRepository` has no call site yet
either — nothing invokes `register`/`login`/`logout` outside the new unit
tests).

### 16. Explicit N3.2 boundaries (what this phase did NOT do)

No `AuthInterceptor`, no OkHttp `Authenticator`, no refresh handling, no
automatic 401 handling, no login/register/logout UI, no navigation change, no
ViewModel, no use-case classes were created. `AuthApi.kt` was not extended.
`TokenStorage`/`SessionManager`'s public APIs from N3.1 were not modified.
`connectx-backend/` and `connectx-frontend/` were not touched.
`BuildConfig.BASE_URL` was not referenced by any new file — `AuthRepositoryImpl`
depends on `AuthApi` only, preserving the existing
`BuildConfig.BASE_URL → NetworkModule → Retrofit → AuthApi` chain unchanged.

### 17. Deferred to N3.3+

`AuthInterceptor` (attach the current access token to outgoing requests),
`Authenticator` (reactive 401 → refresh → retry, with the single-flight
mutex-guarded strategy designed in N3.0 Section 35 §13-14), extending
`AuthApi` with `refresh`/`logout`/forgot-password endpoints, login/register
use cases and UI (N3.4/N3.5), auth navigation and the actual
`SessionManager.restoreSession()` startup call site (N3.6), refresh/error
handling polish (N3.7), and the final N3 verification/checkpoint (N3.8).

**Files created:** `data/remote/auth/{AuthResult,AuthRepository,
AuthRepositoryImpl,AuthRepositoryModule}.kt`,
`app/src/test/java/com/connectx/app/data/remote/auth/AuthRepositoryImplTest.kt`.

**Files modified:** `docs/CONNECTX_ANDROID_DEVELOPMENT.md` (Phase Status +
this section) only. No Gradle/version-catalog change was needed — this phase
introduced no new dependency.

**No authentication network requests were made** — verification used
hand-written fakes exclusively; no code path anywhere calls the live backend.

**No UI was changed** — `MainActivity`, `ConnectXNavHost`,
`DesignShowcaseScreen`, theme, and all existing components are untouched.

**No backend/frontend files were changed** — confirmed via `git status
--short` before and after (below).

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase — identical set of pre-existing modified/untracked
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same unrelated
backend/frontend edits already present since the N2 checkpoint) — nothing
staged, committed, restored, reset, or cleaned. N3.2 is not a Git checkpoint —
no `git add`/`commit` was performed; the checkpoint happens at N3.8.

**N3.2 status: COMPLETE. N3.3 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.3
(`AuthInterceptor`/`Authenticator`).

## 38. N3.3 — Authenticated Network Layer

**N3.3 — Authenticated Network Layer: COMPLETE.**

### 1. AuthInterceptor

`core/network/auth/AuthInterceptor.kt` -- attaches `Authorization: Bearer
<accessToken>` to every request when `TokenStorage.getAccessToken()` returns a
non-blank value; otherwise the request passes through unmodified. No refresh,
no retry, no Retrofit call, no logging, no unrelated header modification --
confirmed by `AuthInterceptorTest`'s "does not remove or alter unrelated
existing headers" test. `TokenStorage` reads are synchronous
`SharedPreferences` calls, safe to call directly inside `intercept` (always
invoked on an OkHttp dispatcher thread, never the main thread).

### 2. Access-token attachment

Verified against a real `MockWebServer` (not a hand-written fake) in
`AuthInterceptorTest`: a stored token produces exactly `Bearer
stored-access-token` on the wire; no token produces no `Authorization` header
at all (not an empty one).

### 3. Public/auth endpoint handling -- the two-client architecture

This is the central design decision of N3.3. `NetworkModule` now provides
**two** independent `OkHttpClient`/`Retrofit` pairs, distinguished by two new
Hilt qualifiers (`core/network/NetworkQualifiers.kt`:
`@AuthenticatedClient`/`@UnauthenticatedClient`):

```
@UnauthenticatedClient OkHttpClient (no interceptor, no authenticator)
      -> @UnauthenticatedClient Retrofit -> AuthApi (register/login/refresh)

@AuthenticatedClient OkHttpClient (AuthInterceptor + TokenAuthenticator)
      -> @AuthenticatedClient Retrofit -> every other API (User/Connection/
                                            Block/Conversation/Message/Media/
                                            Device/Push/Group)
```

**Why not a hardcoded path-exclusion list inside the interceptor/authenticator
instead?** Three problems that approach would have, that the two-client split
solves structurally instead:

1. **The literal dependency cycle Step 15 warned about.** A single shared
   client's `Authenticator` would need `AuthRepository` (to call refresh),
   which needs `AuthApi`, which needs the SAME `Retrofit`/`OkHttpClient` the
   `Authenticator` is attached to -- `OkHttpClient -> Authenticator ->
   AuthRepository -> AuthApi -> Retrofit -> OkHttpClient`. Splitting `AuthApi`
   onto its own, separate client breaks this: `TokenAuthenticator` (attached
   only to the AUTHENTICATED client) depends on `AuthRepository`, which
   depends on `AuthApi`, which is built from the UNAUTHENTICATED client -- a
   completely different object with no path back to the client
   `TokenAuthenticator` is attached to. Confirmed cycle-free by the Hilt graph
   actually compiling (Dagger/Hilt fails annotation processing on a real
   dependency cycle; `kspDebugKotlin`/`hiltAggregateDepsDebug` both succeeded).
2. **A bad-credentials 401 from `login` would otherwise reach the
   `Authenticator`.** The backend returns 401 `UNAUTHORIZED` for a wrong
   password (N3.0 Section 35's error table) -- if `AuthApi` shared the
   authenticated client, a failed login attempt would spuriously trigger
   `TokenAuthenticator`, which would then try to refresh using whatever
   refresh token (if any) happens to be stored, entirely unrelated to the
   login attempt that just failed. Since `AuthApi` isn't on that client at
   all, this is structurally impossible, not just avoided by convention.
3. **The refresh call itself would risk recursing into the same
   `Authenticator`.** Since `AuthApi.refresh` also lives on the
   UNAUTHENTICATED client, a 401 from the refresh call itself (e.g. an
   already-expired refresh token) never re-enters `TokenAuthenticator.authenticate`
   -- it simply surfaces as an `HttpException` inside `AuthRepositoryImpl`,
   handled exactly like any other `ApiError`.

No per-endpoint path exclusion list exists anywhere in `AuthInterceptor` or
`TokenAuthenticator` -- unnecessary given this design, and Step 2 explicitly
warned against hardcoding one.

### 4. Refresh API contract (re-verified directly, added)

Re-read `AuthController#refresh`/`AuthService#refresh` for this phase (not
assumed from N3.0's report): `POST /api/v1/auth/refresh`, still exactly
`{"refreshToken": "..."}` as a raw JSON body, still returns the same
`AuthResponse` shape (rotated access+refresh pair) as register/login,
unchanged since N3.0. Added `RefreshTokenRequest(refreshToken: String)`
(`data/remote/auth/model/RefreshTokenRequest.kt`) -- a real `@Serializable`
data class encoding to the identical JSON shape, per N3.0's recorded open
item (docs Section 35 §19-20, point 4) -- and `AuthApi.refresh(request:
RefreshTokenRequest): ApiResponse<AuthResponse>`. **No duplicate token
response model** -- `AuthResponse` is reused exactly as-is. No other
endpoint (logout, forgot-password/OTP) was added.

`AuthRepository`/`AuthRepositoryImpl` gained `suspend fun refresh(refreshToken:
String): AuthResult`, implemented via the exact same `authenticate {}` helper
already backing `register`/`login` -- identical envelope validation, identical
blank-token rejection, identical "persist only after validation, flip session
state only after persistence succeeds" ordering. Deliberately takes
`refreshToken` as a parameter rather than reading it from `TokenStorage`
itself, preserving `AuthRepository`'s existing "never touches `TokenStorage`
directly" boundary from N3.2 -- the caller (`TokenAuthenticator`, which
already needs `TokenStorage` for its own reasons) supplies it. **Per the
CRITICAL instruction, `AuthRepository.login`/`.register` are never called for
refresh anywhere** -- `refresh` is its own method with its own backend call.

### 5. Authenticator

`core/network/auth/TokenAuthenticator.kt` -- an `okhttp3.Authenticator`
attached only to the authenticated `OkHttpClient`. Implemented in full: this
phase's inspection concluded the complete N3.0-planned design (interceptor +
authenticator + single-flight refresh) is exactly the right N3.3 boundary --
splitting it further wasn't warranted once the two-client design resolved the
cycle.

### 6. Refresh flow

```
401 response (only ever reaches here from a request built on the
              AUTHENTICATED client -- never AuthApi)
   |
responseCount(response) >= 2 ?  --yes--> return null (already retried once)
   |no
extract the access token that was actually on the failed request
   |
acquire refreshMutex
   |
current stored access token != failed token?  --yes--> reuse it, skip refresh
   |no (still stale)
read stored refresh token
   |
   +-- null/blank --> AuthRepository.logout() --> return null
   |
AuthRepository.refresh(refreshToken)
   |
   +-- Success            --> tokens already persisted by authenticate{} ->
   |                          read the new access token, retry with it
   +-- NetworkError        --> return null (nothing cleared, see #11)
   +-- ApiError/
       InvalidResponse/
       UnexpectedError     --> AuthRepository.logout() --> return null
```

### 7. Single-flight strategy

A `kotlinx.coroutines.sync.Mutex` serializes every concurrent call into
`authenticate()`, bridged from the synchronous `Authenticator` contract via
`runBlocking` -- safe because OkHttp always invokes `authenticate` on one of
its own background dispatcher threads, never the main thread (the same
mechanism N3.0 Section 35 §14 anticipated as "viable, exact mechanism an N3.3
detail"). The **compare-current-token-before-refreshing** check is what makes
this genuinely single-flight rather than just serialized-but-still-repeated:
each thread, once it holds the lock, re-reads the CURRENT stored access token
and compares it to the token that was actually on ITS OWN failed request --
if another thread already refreshed while this one waited, the tokens now
differ, and this thread reuses the already-fresh token directly instead of
calling `/auth/refresh` again. Verified directly: `concurrent 401s across
multiple threads produce exactly one refresh call` fires 5 real HTTP requests
from 5 real JVM threads against a `MockWebServer` with a custom `Dispatcher`
(responds 401 to the old token, 200 to the new one -- content-based, not
enqueue-order-based, so the test is immune to thread-scheduling timing) and
asserts `refreshCallCount == 1` and all 5 responses eventually come back 200.

### 8. Retry-limit strategy

`responseCount` walks OkHttp's `priorResponse` chain; a chain depth of 2 or
more (this exact request has already been retried once) returns `null`
immediately, without even attempting another refresh -- verified directly by
constructing a two-deep `Response` chain by hand and asserting both `null` is
returned AND `refreshCallCount` stays 0 (the check happens before any refresh
logic runs at all).

### 9. Refresh-recursion prevention

Structural, not defensive: `TokenAuthenticator` is attached only to the
AUTHENTICATED `OkHttpClient`; the refresh call it triggers goes through
`AuthRepository.refresh` -> `AuthApi.refresh`, which is bound to the
UNAUTHENTICATED `OkHttpClient` -- a different OkHttp instance with no
`Authenticator` attached at all. A 401 from the refresh call itself (e.g. an
already-expired refresh token) can therefore never re-enter
`TokenAuthenticator.authenticate` -- it surfaces as a normal `HttpException`
inside `AuthRepositoryImpl.authenticate{}`, handled the same as any other API
error. See #3 above for the full two-client rationale (this is the same
design decision solving both problems at once).

### 10. Session invalidation on credential rejection

When `AuthRepository.refresh` returns `ApiError` (e.g. the backend's actual
`INVALID_REFRESH_TOKEN` 401), `InvalidResponse`, or `UnexpectedError`,
`TokenAuthenticator` calls `AuthRepository.logout()` -- clearing both stored
tokens and publishing `SessionState.Unauthenticated` -- before returning
`null`. Verified: `refresh rejected by the backend clears the session and
does not retry` asserts `logoutCallCount == 1` and both tokens are `null`
afterward. The app is never left `Authenticated` with credentials the backend
has already rejected.

### 11. Network-failure behavior -- the credential-rejection vs. network-failure distinction

When `AuthRepository.refresh` returns `NetworkError` (no connectivity,
timeout, connection refused during the refresh attempt itself),
`TokenAuthenticator` returns `null` (this particular request attempt fails,
same as any other unreachable-backend condition) but does **NOT** call
`logout()` -- the stored tokens might still be perfectly valid; the network,
not the credentials, is the problem. Verified: `refresh failing due to a
network error does not clear the session` asserts `logoutCallCount == 0` and
both tokens remain exactly as they were. A later request (once connectivity
returns) gets to try refreshing again from a clean, still-authenticated
state, rather than the user being logged out merely because the network blipped.

### 12. Token logging

No `Log.*` call exists anywhere in `AuthInterceptor` or `TokenAuthenticator`
-- confirmed by inspection of both files (each is under 60 lines; neither
imports `android.util.Log`). No `HttpLoggingInterceptor` was added to either
`OkHttpClient`. As an incidental but real confirmation: `android.util.Log`
throws in this project's plain-JUnit (non-Robolectric) unit-test environment
if actually invoked -- every test in `AuthInterceptorTest`/
`TokenAuthenticatorTest` passing is itself evidence no such call executes on
any tested code path.

### 13. Hilt dependency graph

```
Json (Singleton)
   |
@UnauthenticatedClient OkHttpClient (Singleton, no interceptor/authenticator)
   |
@UnauthenticatedClient Retrofit (baseUrl = BuildConfig.BASE_URL)
   |
AuthApi --------------------------------------------+
   |                                                  |
AuthRepositoryImpl (Singleton) <--- SessionManager    |
   |  (bound to AuthRepository via AuthRepositoryModule)
   |
TokenAuthenticator (Singleton) <--- TokenStorage       |
   |                                                    |
@AuthenticatedClient OkHttpClient (Singleton, AuthInterceptor + TokenAuthenticator attached)
   |
@AuthenticatedClient Retrofit (baseUrl = BuildConfig.BASE_URL)
   |
UserApi, ConnectionApi, BlockApi, ConversationApi, MessageApi, MediaApi,
DeviceApi, PushApi, GroupApi
```

`AuthInterceptor` and `TokenAuthenticator` both have plain `@Inject`
constructors (no explicit `@Provides` needed -- Dagger/Hilt resolves them as
ordinary constructor parameters of `provideAuthenticatedOkHttpClient`).
**No circular dependency exists** -- confirmed both by manual trace (above:
the only path from `TokenAuthenticator` back toward an `OkHttpClient` goes
through the UNAUTHENTICATED one, never the client it's attached to) and by
the Hilt/KSP annotation-processing step of `./gradlew assembleDebug`/`./gradlew
testDebugUnitTest` actually succeeding (a real cycle fails compilation here,
not just at runtime). `NetworkQualifiers.kt` is the only new file needed to
express this; no third client, no second full network stack.

### 14. Testing strategy

Three new test files, all JVM-only, zero real network/backend calls:

- `AuthRepositoryImplTest.kt` (existing file, extended) -- 2 new tests for
  `AuthRepository.refresh`: a successful rotation (both tokens replaced,
  session stays `Authenticated`) and a backend-rejected refresh (`ApiError`
  with the real `INVALID_REFRESH_TOKEN` code, session untouched by this call
  alone -- `TokenAuthenticator`, not `AuthRepository`, owns clearing the
  session on rejection, per #10). Existing `FakeAuthApi` extended with a
  `refreshAction` the same way `registerAction`/`loginAction` already worked.
- `AuthInterceptorTest.kt` -- 3 tests, real `OkHttpClient` + local
  `MockWebServer` (no Retrofit): token attached, no token means no header,
  unrelated headers preserved.
- `TokenAuthenticatorTest.kt` -- 9 tests. A hand-written `FakeAuthRepository`
  (no mocking framework) stands in for `AuthRepositoryImpl` -- deliberately,
  since the real network call `AuthRepository.refresh` would make is already
  covered by `AuthRepositoryImplTest`; this file tests `TokenAuthenticator`'s
  OWN logic in isolation. A real `MockWebServer` is used specifically where an
  actual OkHttp round trip is the most direct proof (401->refresh->retry with
  real header inspection, the 403/500-never-invokes-the-authenticator tests
  relying on OkHttp's own contract rather than custom logic, and the 5-thread
  concurrency test); the retry-limit, credential-rejection,
  network-failure-distinction, no-refresh-token, and
  already-refreshed-by-another-caller tests call `authenticate()` directly
  against hand-built `Response` objects, since those don't need a live HTTP
  round trip to prove correctly.

**Dependency added:** `com.squareup.okhttp3:mockwebserver3` 5.4.0 (matching
the project's existing `okhttp` version exactly, via the existing `okhttp`
version-catalog ref), `testImplementation` only -- not shipped in the app.
Genuinely needed: header attachment, real 401 handling, and real thread
concurrency are OkHttp-layer behaviors that hand-written fakes structurally
cannot exercise (there's no `chain.proceed`/real dispatcher to fake around).

### 15. Test results

**93/93 total, 0 failures** (79 pre-existing + 2 new `AuthRepositoryImplTest`
+ 3 new `AuthInterceptorTest` + 9 new `TokenAuthenticatorTest` = 14 new,
79 + 14 = 93, confirmed against the actual JUnit XML output). Re-ran the full
suite 3 additional times (`--rerun`,
bypassing Gradle's test cache) specifically to check the concurrency test for
flakiness -- all 3 reruns passed with no failures.

### 16. Build verification

`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, 93/93 pass. `./gradlew
assembleDebug` → BUILD SUCCESSFUL, no errors (confirms the Hilt graph,
including both qualified client pairs, resolves with no circular dependency).
No AGP/Kotlin/Gradle upgrade was needed or performed.

### 17. Emulator/device verification

- **Pixel 7a emulator** (`emulator-5554`) -- fresh `install -r`, `am start`,
  live PID (`pidof com.connectx.app`), zero `FATAL`/`AndroidRuntime`/
  `Exception` in a 200-line logcat tail.
- **Vivo device** (model `V2503`, reachable via wireless ADB this session) --
  identical fresh install/launch/live-PID/clean-logcat verification.

No login/register/refresh UI exists yet (N3.5/N3.6), so verification remained
launch-only, same scope as N3.1/N3.2 -- nothing in the app currently calls
`AuthRepository`/either `OkHttpClient` outside the new unit tests, so no real
authentication or refresh HTTP request occurs merely from launching.

### 18. Explicit N3.3 boundaries (what this phase did NOT do)

No login/register/logout UI, no navigation change, no ViewModel, no use-case
classes were created. `AuthApi.kt` was extended by exactly one method
(`refresh`) -- `logout`/forgot-password/OTP endpoints remain unmodeled.
`MainActivity`, `ConnectXNavHost`, `DesignShowcaseScreen`, theme, and all
existing screens/components are untouched. `connectx-backend/` and
`connectx-frontend/` were not touched. `BuildConfig.BASE_URL` was not
referenced by any new file directly -- both `NetworkModule` client pairs
continue resolving it exactly as before. No AGP/Kotlin/Gradle version was
changed.

### 19. Deferred to N3.4+

Login/register use cases and UI (N3.4/N3.5), auth navigation and the actual
`SessionManager.restoreSession()` app-startup call site (N3.6), any further
refresh/error-handling polish beyond what N3.3 already implements (N3.7), and
the final N3 verification/checkpoint (N3.8). `AuthApi`'s remaining
unmodeled endpoints (`logout`, forgot-password/OTP family) remain an open
item, unaffected by this phase.

**Files created:** `core/network/NetworkQualifiers.kt`,
`core/network/auth/{AuthInterceptor,TokenAuthenticator}.kt`,
`data/remote/auth/model/RefreshTokenRequest.kt`,
`app/src/test/java/com/connectx/app/core/network/auth/{AuthInterceptorTest,
TokenAuthenticatorTest}.kt`.

**Files modified:** `core/network/NetworkModule.kt` (split into the two-client
architecture), `data/remote/auth/AuthApi.kt` (added `refresh`),
`data/remote/auth/AuthRepository.kt` + `AuthRepositoryImpl.kt` (added
`refresh(refreshToken)`), `app/src/test/java/.../AuthRepositoryImplTest.kt`
(extended `FakeAuthApi` with `refreshAction`, added 2 tests),
`gradle/libs.versions.toml` + `app/build.gradle.kts` (added the
`mockwebserver3` test dependency), `docs/CONNECTX_ANDROID_DEVELOPMENT.md`
(Phase Status + this section).

**No real authentication/network requests were made** -- every test runs
against either a hand-written fake or a local `MockWebServer`; no code path
anywhere calls `app.myconnect.sbs` or `10.0.2.2`.

**No UI was changed** -- confirmed above (#18).

**No backend/frontend files were changed** -- confirmed via `git status
--short` before and after (below).

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase -- identical set of pre-existing modified/untracked
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same unrelated
backend/frontend edits already present since the N2 checkpoint) -- nothing
staged, committed, restored, reset, or cleaned. N3.3 is not a Git checkpoint --
no `git add`/`commit` was performed; the checkpoint happens at N3.8.

**N3.3 status: COMPLETE. N3.4 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.4 (login/register use
cases).

## 39. N3.4 — Authentication Session Integration

**N3.4 — Authentication Session Integration: COMPLETE.**

### 1. Startup restoration

`ConnectXApplication.onCreate()` now calls `sessionManager.restoreSession()`
exactly once, launched inside `applicationScope.launch { ... }`. Restoration
itself is unchanged from N3.1/N3.0: local-only, optimistic ("tokens exist" ->
`Authenticated`), no network request, no JWT decoding/expiry check — that
decision was re-verified during this phase's inspection (Step 1) and found
still correct, not altered.

### 2. SessionManager lifecycle

Unchanged from N3.1 -- `@Singleton class SessionManager @Inject constructor(
private val tokenStorage: TokenStorage)`, one instance for the application's
entire lifetime, created once by Hilt the first time anything requests it
(which, before N3.4, only happened lazily via `AuthRepositoryImpl`/
`TokenAuthenticator`; N3.4's `ConnectXApplication` field injection is simply
another consumer of that same singleton, not a second construction path).

### 3. StateFlow ownership

`SessionManager.sessionState: StateFlow<SessionState>` remains the **only**
authentication state anywhere in the app. No `AuthManager`, `LoginState`,
`UserSession` singleton, duplicate `StateFlow`, or separate token/auth state
in `MainActivity`/navigation was created -- confirmed there was no existing
duplicate to find, and none was added. Any future UI layer is expected to
observe this exact flow, not build its own.

### 4. Application-scoped session state (Hilt graph)

```
ConnectXApplication (@HiltAndroidApp)
   | field-injects
SessionManager (@Singleton)
   | constructor-injects
TokenStorage (@Singleton, bound to EncryptedTokenStorage via AuthStorageModule)
```

The exact same `SessionManager` singleton is also constructor-injected into
`AuthRepositoryImpl` and (indirectly, through `AuthRepository`)
`TokenAuthenticator` -- Hilt's `@Singleton` scoping to the application
component guarantees exactly one instance is ever created, confirmed by there
being exactly one `@Inject constructor` for the class and no second binding
anywhere. `Application` field injection (`@Inject lateinit var sessionManager:
SessionManager`) happens as part of the generated `Hilt_ConnectXApplication`
base class's `onCreate()`, which runs via `super.onCreate()` before this
class's own `onCreate()` body executes -- the standard, well-established
pattern for `@HiltAndroidApp` field injection, not something new introduced
here.

### 5. Threading decision

`EncryptedTokenStorage`'s constructor (`MasterKey.Builder(...).build()` +
`EncryptedSharedPreferences.create(...)`) and every subsequent read/write are
synchronous Keystore + disk I/O -- confirmed by direct inspection, not
assumed. Calling `restoreSession()` straight from `onCreate()` would risk
blocking the main thread during app startup. `ConnectXApplication` holds one
`private val applicationScope = CoroutineScope(SupervisorJob() +
Dispatchers.IO)` field, used for exactly this one call. No AndroidX Startup
library, no Hilt-provided `@ApplicationScope CoroutineScope`, no other startup
framework was introduced -- a single scope field living exactly as long as
the `Application` instance (i.e. the process) is the simplest mechanism that
satisfies "don't block the main thread" for a single call site; promoting it
to a shared, Hilt-provided scope is deferred until a second genuine use case
exists.

### 6. Logout behavior — verified, not changed

`AuthRepository.logout()` -> `SessionManager.clearSession()` ->
`TokenStorage.clearTokens()` + `SessionState.Unauthenticated` remains exactly
as N3.2 built it: local-only, no backend call, no fake server-logout
operation. Already covered by N3.2's `AuthRepositoryImplTest` (`logout clears
tokens and transitions the session to Unauthenticated`) and N3.1's
`SessionManagerTest` (`clearSession clears storage and transitions to
Unauthenticated`) -- not re-tested here to avoid duplication, per Step 11's
explicit instruction.

### 7. Refresh-failure integration

`TokenAuthenticator`'s N3.3 behavior (credential rejection ->
`AuthRepository.logout()` -> `Unauthenticated`; network failure -> nothing
cleared) was re-verified, not redesigned. N3.4 adds direct proof that the
**real** `SessionManager` (not a call-counter on a fake, as N3.3's
`TokenAuthenticatorTest` used) reflects this correctly end to end -- see
`SessionIntegrationTest` below.

### 8. Process recreation behavior

New `a new SessionManager instance restores a session persisted by a prior,
now-discarded instance` test: a first `SessionManager` authenticates against a
shared `FakeTokenStorage`, is then discarded (simulating every in-memory
Kotlin object dying with the process); a second, brand-new `SessionManager`
constructed against the SAME storage starts at `Unknown` and, after
`restoreSession()`, correctly reaches `Authenticated` with the exact tokens
the first instance saved. This is the one genuinely new scenario no N3.1-N3.3
test exercised (their restore tests always pre-populated storage directly,
never via a first `SessionManager` instance that was then thrown away).

### 9. Partial-token behavior — verified, not changed

Already covered exhaustively by N3.1's `SessionManagerTest` (`restore with
only an access token` / `restore with only a refresh token`, both clearing
storage and landing on `Unauthenticated`). Re-inspected during N3.4 (Step 10)
and found unchanged and correct; not re-tested here.

### 10. Security considerations

- No token is logged anywhere in `ConnectXApplication.kt` -- the file
  contains no `Log.*` call at all.
- No token is exposed through any UI, `Intent` extra, navigation argument, or
  `SavedStateHandle` -- N3.4 added no UI/navigation code, and none of the
  N3.1-N3.3 files that DO hold tokens (`TokenStorage`,
  `EncryptedTokenStorage`, `AuthInterceptor`, `TokenAuthenticator`) were
  touched in a way that would introduce such exposure.
- No duplicate token persistence was introduced -- `TokenStorage`
  (`EncryptedTokenStorage`) remains the only place tokens are written to
  disk; `ConnectXApplication` only ever calls `SessionManager.restoreSession()`,
  which itself only reads.
- The N3.1 Android backup exclusions (`backup_rules.xml`/
  `data_extraction_rules.xml` excluding `connectx_secure_auth_prefs.xml`) were
  not touched or weakened.

### 11. Tests

New file: `app/src/test/java/com/connectx/app/core/session/SessionIntegrationTest.kt`
-- **4 new tests**, all using the REAL `SessionManager` and REAL
`AuthRepositoryImpl`/`TokenAuthenticator`/`AuthInterceptor` classes (only
`AuthApi` and `TokenStorage` are hand-written fakes; no mocking framework), a
real `MockWebServer` where an actual OkHttp round trip is needed:

1. `a real refresh success keeps SessionManager Authenticated with the
   rotated tokens` -- a 401 then 200 round trip; asserts the real
   `SessionManager.sessionState` stays `Authenticated` and storage holds the
   rotated pair.
2. `a real refresh rejection transitions SessionManager to Unauthenticated
   and clears both tokens` -- asserts the real `SessionManager` (not a fake's
   call counter) actually flips state and both tokens become `null`.
3. `a network failure during refresh leaves SessionManager Authenticated with
   credentials intact` -- asserts the real `SessionManager` stays
   `Authenticated` with the original tokens untouched.
4. `a new SessionManager instance restores a session persisted by a prior,
   now-discarded instance` -- the process-recreation scenario (#8 above).

One implementation issue found and fixed while writing these: the first draft
of tests 2 and 3 built the initial request without an `Authorization` header
and without attaching `AuthInterceptor` to the test client. Without that
header, `TokenAuthenticator`'s "was this token already refreshed by someone
else" comparison (`failedAccessToken` extracted from the failed request) saw
`null`, which never equals the stored token -- so it incorrectly took the
"already refreshed, reuse it" branch, built a retry request, and sent a
second HTTP call the `MockWebServer` had nothing enqueued for, hanging until
the default 10s OkHttp timeout (`SocketTimeoutException`). Fixed by adding
`AuthInterceptor(tokenStorage)` to both clients, matching test 1. Caught and
corrected before this report; not a defect in `TokenAuthenticator` itself --
purely a test-construction mistake (a real caller always goes through
`AuthInterceptor` first, so a request lacking any `Authorization` header at
all past that point isn't a state `TokenAuthenticator` needs to specially
account for).

Per Step 11/17's instruction not to duplicate existing coverage, items 1-6 and
9 from the phase's suggested test list were intentionally NOT re-added --
they already exist, unchanged and still passing, in N3.1's `SessionManagerTest`
and N3.2's `AuthRepositoryImplTest`.

### 12. Build verification

`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, **97/97 pass** (93
pre-existing + 4 new). Re-ran twice more with `--rerun` (bypassing Gradle's
test cache) -- no flake. `./gradlew assembleDebug` → BUILD SUCCESSFUL, no
errors (confirms `ConnectXApplication`'s new field injection compiles cleanly
through Hilt). No AGP/Kotlin/Gradle/Hilt/Retrofit/OkHttp version was changed.

### 13. Device verification

- **Pixel 7a emulator** (`emulator-5554`) -- fresh `install -r`, `am start`,
  live PID, `ActivityTaskManager: Displayed com.connectx.app/.MainActivity`
  confirmed in logcat, zero `FATAL`/`AndroidRuntime` lines. One differently-tagged
  `WindowManager: Exception thrown during dispatchAppVisibility ... EXITING`
  warning appeared on the very first launch attempt (a benign window-lifecycle
  race from installing/relaunching rapidly, referencing a PRIOR process
  instance already `EXITING`) -- investigated by force-stopping, clearing
  logcat, and relaunching cleanly: the warning did not recur, and the
  confirmed-clean second launch is what's reported here as the verification
  result, not the first attempt.
- **Vivo device** (model `V2503`, reachable via wireless ADB this session) --
  fresh install/launch, live PID, zero crash lines in logcat.

No login/register/session-observation UI exists (deliberately -- see
boundaries below), so verification remained launch-only, consistent with
N3.1-N3.3's precedent -- HOME, the temporary Navigation Test, and the Design
System Showcase were not re-exercised beyond confirming the app launches,
since N3.4 changed none of them.

### 14. Explicit N3.4 boundaries (what this phase did NOT do)

No login/register/home UI, no `AuthGraph`/`MainGraph`/any navigation graph, no
`DesignShowcaseScreen`/`ConnectXButton`/theme/color/typography/spacing/shape
change, no ViewModel, no observation screen of any kind was created --
`MainActivity.kt` and `ConnectXNavHost.kt` are byte-for-byte unchanged. No
second `UserDto`/user-profile store was introduced -- inspection confirmed
`SessionManager`/`TokenStorage` still track only the token pair, never the
authenticated user's profile data, and N3.4 deliberately kept it that way
(Step 6). `SessionManager`, `TokenStorage`, `AuthRepository`,
`AuthInterceptor`, `TokenAuthenticator` source was not modified -- inspection
found the existing N3.0-N3.3 architecture already correct, so nothing was
rewritten. No backend logout API call was added. `connectx-backend/` and
`connectx-frontend/` were not touched.

### 15. Deferred to N3.5+

Login/register UI (N3.5), the actual authenticated application navigation/
shell that reads `SessionManager.sessionState` to route between an auth flow
and the main app (N3.6 -- this is where the "minimal session-aware routing"
the prompt permitted as optional would actually belong; N3.4 judged it
unnecessary since launch-only device verification, matching N3.1-N3.3's
precedent, was sufficient proof), any further refresh/error-handling UX
polish (N3.7), and the final N3 verification/checkpoint (N3.8). `AuthApi`'s
remaining unmodeled endpoints (`logout`, forgot-password/OTP family) remain
unaffected, open items.

**Files created:** `app/src/test/java/com/connectx/app/core/session/SessionIntegrationTest.kt`.

**Files modified:** `ConnectXApplication.kt` (added field-injected
`SessionManager` + startup `restoreSession()` call on a new
`applicationScope`), `docs/CONNECTX_ANDROID_DEVELOPMENT.md` (Phase Status +
this section).

**No real authentication/network requests were made** -- every test uses
either a hand-written fake or a local `MockWebServer`; `ConnectXApplication`'s
new startup call is local-storage-only, calling no API.

**No UI was changed** -- confirmed above (#14); `MainActivity.kt` and
`ConnectXNavHost.kt` are unmodified.

**No backend/frontend files were changed** -- confirmed via `git status
--short` before and after (below).

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase -- identical set of pre-existing modified/untracked
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same unrelated
backend/frontend edits already present since the N2 checkpoint) -- nothing
staged, committed, restored, reset, or cleaned. N3.4 is not a Git checkpoint --
no `git add`/`commit` was performed; the checkpoint happens at N3.8.

**N3.4 status: COMPLETE. N3.5 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.5 (authentication
UI).

## 40. N3.5 — Authentication Security & App Protection

**N3.5 — Authentication Security & App Protection: COMPLETE.**

Security-hardening/audit phase only — no UI, no navigation, no application
shell, no backend/frontend change.

### 1-2. Secure token storage audit / encryption mechanism

Re-inspected `TokenPair.kt`, `TokenStorage.kt`, `EncryptedTokenStorage.kt`,
`AuthStorageModule.kt` directly for this phase (not from N3.1's report).
Unchanged and confirmed correct: `EncryptedSharedPreferences` (file
`connectx_secure_auth_prefs`, keys `access_token`/`refresh_token`), Keystore-backed
`MasterKey` (AES256_GCM), AES256_SIV key / AES256_GCM value encryption
schemes, no duplicate token persistence anywhere in the source tree
(confirmed by a full grep — only `EncryptedTokenStorage` calls `.edit()`/
`.putString()`/`.getString()` on any preferences object). `TokenStorage` is
still the only abstraction any other class touches — the underlying
`SharedPreferences` instance remains private to `EncryptedTokenStorage`.

**Deprecation status, re-confirmed:** `EncryptedSharedPreferences`/`MasterKey`
remain `@Deprecated` in `androidx.security:security-crypto` 1.1.0 (no stable
non-deprecated successor has shipped). Per this phase's explicit instruction,
**not** migrated to an experimental/unreleased alternative — they remain the
safest currently-stable option, kept exactly as N3.1 built them.

### 3. Backup/data-extraction protection — re-verified, unchanged

`backup_rules.xml`'s `<full-backup-content>` and both `<cloud-backup>`/
`<device-transfer>` scopes in `data_extraction_rules.xml` still exclude
exactly `connectx_secure_auth_prefs.xml` and nothing else — re-read directly,
byte-for-byte the same content N3.1 established. No broader backup exclusion
was added; no unrelated app data was newly excluded.

### 4. Token exposure audit

Full-source-tree greps run directly for this phase: `Log\.|println|System\.out|printStackTrace`
→ zero matches anywhere under `app/src`. `ClipboardManager|Intent\(|putExtra|SavedStateHandle|contentDescription`
→ matches only in 3 pre-existing, unrelated N1 design-system files
(`DesignShowcaseScreen.kt`'s back-button icon, `ConnectXStateViews.kt`'s
info/warning icons, `ConnectXAvatar.kt`'s avatar image description) — each
individually inspected and confirmed to describe UI icons/avatars, never a
token or auth value. No clipboard, share-intent, external-intent,
`SavedStateHandle`, or `Bundle` usage exists anywhere that could carry a
token — there is no such code at all yet (no auth UI exists). Audit result:
**clean, nothing to fix.**

### 5. Network logging audit

`HttpLoggingInterceptor`/`Interceptor.Level`/`BODY`/`HEADERS` grepped across
`app/src` → zero matches. `NetworkModule.kt` re-read directly: neither
`OkHttpClient.Builder()` (unauthenticated or authenticated) adds any logging
interceptor of any kind. Nothing was added — the instruction was explicit not
to add one merely for debugging.

### 6. AuthInterceptor security

Re-read `AuthInterceptor.kt` directly: attaches `Authorization: Bearer
<accessToken>` only when non-blank, otherwise passes the request through
unmodified; touches no other header; makes no network call itself; contains
no logging of any kind. Confirmed (Step 5's requirement) it is attached ONLY
to the `@AuthenticatedClient` `OkHttpClient` in `NetworkModule` — `AuthApi`
(register/login/refresh) is bound exclusively to the `@UnauthenticatedClient`
`Retrofit`, which has no interceptor at all, so the access token can never be
attached to a refresh/login/register request, and a refresh request can never
carry a stale or inappropriate `Authorization` header. **New behavioral
proof, not previously written:** `AuthInterceptor attaches only the access
token even when a refresh token is also stored` (`AuthSecurityTest`) — builds
a `TokenStorage` holding BOTH tokens simultaneously and asserts, against a
real `MockWebServer`, that the captured request's `Authorization` header
equals the access token exactly and that the refresh token's literal value
never appears anywhere in the recorded headers.

### 7. Refresh-token handling

Re-verified against `AuthApi.kt`/`RefreshTokenRequest.kt`/`AuthRepositoryImpl.kt`:
the refresh token is sent exactly once, as the sole field of a JSON POST body
(`{"refreshToken": "..."}`), on `AuthApi.refresh` alone. It is never read
into a header-construction code path anywhere (`AuthInterceptor` only ever
reads `getAccessToken()`, never `getRefreshToken()`), never appended to a URL
or query parameter, never logged. **New behavioral proof:** `a live refresh
call sends the refresh token only in the JSON body and carries no
Authorization header` (`AuthSecurityTest`) — constructs the refresh call
through a real `Retrofit`/`OkHttpClient` built exactly like `NetworkModule`'s
actual unauthenticated client (no interceptor), executes it against a real
`MockWebServer`, and asserts the recorded request has `Authorization ==
null` and a body that is precisely `{"refreshToken":"the-real-refresh-token-value"}` —
nothing more, nothing wrapped, nowhere else.

### 8. JWT handling decision — unchanged

No JWT library was introduced; no local decoding/expiration parsing exists or
was added anywhere. Preserved exactly as N3.0 designed: server-side
authentication authority, reactive 401-driven refresh via `TokenAuthenticator`,
optimistic local restoration via `SessionManager`. Re-confirmed correct for
this phase's purposes — this is a deliberate architectural choice, not an
oversight, and remains out of scope to "fix."

### 9. Manifest/component exposure

Re-read `AndroidManifest.xml` directly: exactly one component,
`MainActivity`, `exported="true"` — required, since it is the app's
`LAUNCHER`/`MAIN` entry point; an unexported launcher activity would make the
app unlaunchable, so this is not a vulnerability to change. No `<service>`,
`<receiver>`, or `<provider>` is declared anywhere in the project — nothing
else exists to audit or restrict. `android:allowBackup="true"` remains,
scoped down specifically by the backup/data-extraction exclusion rules
(§3) rather than disabled outright, which stays correct: disabling backup
entirely would be a broad, unrelated policy change this phase was explicitly
told not to make.

### 10. Cleartext networking decision — gap found and fixed

**This is the one concrete production-code change this phase made.**
Confirmed by direct inspection (no `network_security_config` file, no
`android:usesCleartextTraffic` attribute, no build-type-specific manifest
existed anywhere before this phase) that Android's default cleartext policy
applied unmodified: `targetSdk` 37 (≥ 28) means cleartext traffic is
**disallowed everywhere** unless a Network Security Config says otherwise.
The debug `BASE_URL` (`http://10.0.2.2:8080/`) is plain HTTP — meaning the
very first real authenticated request ever attempted from a debug build
would fail with `CLEARTEXT communication to 10.0.2.2 not permitted`. This had
never surfaced because every N2/N3 phase's device verification was
deliberately launch-only, with zero real HTTP requests made from a running
app.

**Fix:** two new debug-source-set-only files —
`app/src/debug/res/xml/network_security_config_debug.xml` (a
`<domain-config cleartextTrafficPermitted="true">` scoped to exactly
`10.0.2.2`, `includeSubdomains="false"`) and `app/src/debug/AndroidManifest.xml`
(adds `android:networkSecurityConfig="@xml/network_security_config_debug"`
to `<application>`, merging additively — no `tools:replace` needed since the
main manifest declares no such attribute). Both compile into **debug builds
only**; a release APK never contains them. Verified two ways: `grep` on the
merged debug manifest (`app/build/intermediates/merged_manifest/debug/.../AndroidManifest.xml`)
found the attribute present; the identical `grep` on the merged **release**
manifest found **zero matches** — confirmed by running `./gradlew
assembleRelease` (BUILD SUCCESSFUL) specifically to produce that merged
manifest for inspection. `BuildConfig.BASE_URL` itself was not touched for
either variant — release's `https://app.myconnect.sbs/` and its default
cleartext-disallowed posture are completely unaffected.

(One iteration note: the first draft of both new XML files used a `--`
double-hyphen inside an XML comment, which is invalid — `mergeDebugResources`/
`processDebugMainManifest` failed until reworded, the same class of mistake
already seen once in N3.1's backup-rules XML comments. Caught and fixed
before this report.)

### 11. Release security posture — documented, not engineered

Re-read `app/build.gradle.kts`: `release { optimization { enable = false }
}` — R8/minification remains explicitly disabled (a pre-existing N1
decision, not touched here); no explicit `isDebuggable` on either build type
(AGP's own defaults apply: `debug` = debuggable, `release` = not
debuggable — correct, safe defaults, nothing to change); no signing config is
declared for `release` at all. Per this phase's explicit instruction, **no**
ProGuard/R8 rules were introduced and **no** release signing configuration
was invented — that remains a genuine future release-engineering task, out
of N3.5's security-hardening scope, documented here as an open item rather
than silently addressed or silently ignored.

### 12. FLAG_SECURE decision

**Deliberately deferred, not applied.** No authentication UI exists yet (N3.5
explicitly must not build one) — there is no screen to apply
`WindowManager.LayoutParams.FLAG_SECURE` to. A blanket, application-window-level
`FLAG_SECURE` (set once in `MainActivity`, before any screen exists) was
considered and rejected: it would suppress screenshots/screen-recording and
show a blank thumbnail in Recents for the **entire app**, including every
future non-sensitive screen (chat, contacts, settings, etc.), not just a
login/session screen — a real, needless UX cost for the whole application to
close a gap that doesn't exist yet (there is nothing sensitive currently
rendered). The correct placement is scoped to the actual login/registration
screen once N3.6/N4 builds it (e.g. via
`activity.window.setFlags(FLAG_SECURE, FLAG_SECURE)` gated to that
composable's lifecycle, or a small reusable helper at that point) — recorded
here as an explicit open decision for that phase, per N3.0's original
cross-reference, not silently dropped.

### 13. Clipboard/sharing audit — clean, nothing to fix

Confirmed via §4's grep: no `ClipboardManager`, share `Intent`, or external
`Intent` construction exists anywhere in the current source tree. No token
is or could currently be copied/shared, since no code path does either at
all yet.

### 14. Accessibility audit — clean, nothing to fix

Confirmed via §4's grep: all 3 `contentDescription`/semantics usages in the
project are icon/avatar descriptions in pre-existing N1 design-system
components (back button, info/warning icons, avatar image) — none reference
or could reference a token, since no auth UI/state currently touches any of
them.

### 15. Exception/error safety

Re-inspected `AuthRepositoryImpl.kt`, `TokenAuthenticator.kt`,
`EncryptedTokenStorage.kt`, `SessionManager.kt` directly. `AuthRepositoryImpl`'s
`HttpException`/`IOException`/`SerializationException` handling (unchanged
since N3.2/N3.3) only ever surfaces `e.message`/`response.errorBody()`-parsed
`ErrorResponse` fields (`status`/`code`/`message`) into `AuthResult` — none of
these can contain a token value, since neither OkHttp/Retrofit's own
exception messages nor the backend's `ErrorResponse` body ever include
request header or body content. `TokenAuthenticator`'s only string handling
of a token is building the `Authorization` header value itself (never
logged, never included in any exception). `EncryptedTokenStorage` throws only
whatever `EncryptedSharedPreferences`/Keystore itself might throw (e.g. a
`GeneralSecurityException`) — none of which the app catches-and-logs; an
uncaught exception here would surface only in the standard Android crash
report mechanism (device-local, not sent anywhere by this app, no analytics/
crash-reporting SDK exists in the project at all). `AuthResult`'s clean,
token-free sealed-interface shape (unchanged since N3.2) remains exactly the
boundary preventing token leakage into anything a future UI/logging layer
might display.

### 16. Memory/token lifetime decision

Tokens are held as ordinary Kotlin `String`s for exactly as long as the
network/session layer needs them (a local variable during a request, a field
value read fresh from `EncryptedSharedPreferences` on each `TokenStorage`
call) — no artificial wiping, no custom `CharArray`-based "secure string"
mechanism was implemented. Per this phase's explicit instruction: JVM/Kotlin
`String`s cannot be reliably zeroed (immutable, may be interned, may be
copied by the GC before any wipe attempt) — pretending otherwise would be
false security theater, not a real improvement. This is standard, accepted
practice for this class of Android app; the real security boundary is
encrypted-at-rest storage (§1-2) and never-logged handling (§4-6, §15), not
in-memory string lifetime.

### 17. Permission audit — unchanged, nothing added

`AndroidManifest.xml` re-read directly: exactly one permission,
`android.permission.INTERNET` (from N1.4), unchanged. No storage, contacts,
location, notification, microphone, camera, phone, or Bluetooth permission
exists or was added.

### 18. Security tests

New file: `app/src/test/java/com/connectx/app/core/network/auth/AuthSecurityTest.kt`
— **2 new tests**, both described in full in §6/§7 above, both against a
real `MockWebServer` (no mocking framework, no real backend). Per Step 17's
explicit instruction to prefer behavioral tests over implementation-text
inspection, and not to duplicate existing coverage: items 1 (access token
via `TokenStorage` only), 2 (refresh token via `TokenStorage` only), 4
(partial-token clearing), 5 (logout clearing), 7 (`AuthInterceptor` attaches
only access token), 10 (credential-rejection clears), 11 (network-failure
preserves), and 12 (no stale token through the wrong client) from the
phase's suggested list were **not** re-tested — each is already covered,
unchanged and still passing, by N3.1's `SessionManagerTest`, N3.3's
`AuthInterceptorTest`/`TokenAuthenticatorTest`, and N3.4's
`SessionIntegrationTest`. Item 3 (backup exclusion) and item 6
("AuthInterceptor never logs") are verified by direct file inspection (§3)
and by the absence of any `Log`/logging code (§4-5) respectively, not by a
fabricated JVM test that would need to read Android resource XML or prove a
negative about logging in a way a unit test can't meaningfully strengthen
beyond "no such code exists."

### 19. Build verification

`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, **99/99 pass** (97
pre-existing + 2 new). `./gradlew assembleDebug` → BUILD SUCCESSFUL.
`./gradlew assembleRelease` → BUILD SUCCESSFUL (run specifically to verify
§10's manifest-scoping claim; not part of the phase's baseline build
requirement, but necessary evidence for the cleartext fix). No AGP/Kotlin/
Gradle/Hilt/Retrofit/OkHttp/Android SDK version was changed.

### 20. Device verification

- **Pixel 7a emulator** (`emulator-5554`) — fresh `install -r`, force-stopped
  and relaunched cleanly, live PID (`pidof com.connectx.app`),
  `ActivityTaskManager: Displayed com.connectx.app/.MainActivity` confirmed
  in logcat, zero `FATAL`/`AndroidRuntime` lines.
- **Vivo X200 FE** — **NOT VERIFIED / DEVICE UNAVAILABLE.** `adb devices -l`
  (including a full daemon restart) showed only the emulator this session;
  no device configuration was modified to force availability, per
  instructions.

No login/register/session-observation UI exists, so verification remained
launch-only, consistent with N3.1-N3.4's precedent.

### 21. Explicit N3.5 boundaries (what this phase did NOT do)

No login/registration/auth screen, no application shell, no messaging/
WebSocket/FCM/WebRTC/E2EE/media/background-service/calling/profile/settings
code was created. `MainActivity.kt`, `ConnectXNavHost.kt`,
`DesignShowcaseScreen.kt`, theme, colors, typography, and all reusable
components are byte-for-byte unchanged. `TokenStorage`/`EncryptedTokenStorage`/
`SessionManager`/`AuthRepository`/`AuthInterceptor`/`TokenAuthenticator`
source was **not** modified — inspection found the existing N3.0-N3.4
architecture already secure, so nothing beyond the two new debug-only
manifest/resource files was changed. No `FLAG_SECURE` was added (§12). No
JWT library or decoding was introduced (§8). No ProGuard/R8 rules or release
signing configuration were invented (§11). `connectx-backend/` and
`connectx-frontend/` were not touched. `BuildConfig.BASE_URL` itself was not
changed for either build type.

### 22. Deferred to N3.6+

Login/register UI and its `FLAG_SECURE` placement (N3.6/N3.5's explicit
cross-reference, resolved at that point per §12's decision), the actual
authenticated application navigation/shell, any further refresh/error-handling
UX polish (N3.7), and the final N3 verification/checkpoint (N3.8). Release
signing/minification engineering (§11) remains a genuine open item for
whichever future phase owns release readiness — not N3.

**Files created:** `app/src/debug/AndroidManifest.xml`,
`app/src/debug/res/xml/network_security_config_debug.xml`,
`app/src/test/java/com/connectx/app/core/network/auth/AuthSecurityTest.kt`.

**Files modified:** `docs/CONNECTX_ANDROID_DEVELOPMENT.md` (Phase Status +
this section) only. No existing Kotlin source file was modified — the
cleartext fix lives entirely in new debug-source-set files, and no defect
was found in any existing N3.1-N3.4 file that would have justified changing it.

**No real authentication/network requests were made** — both new tests use
a local `MockWebServer`; no code path calls `app.myconnect.sbs` or
`10.0.2.2`.

**No UI was changed** — confirmed above (§21).

**No backend/frontend files were changed** — confirmed via `git status
--short` before and after (below).

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase — identical set of pre-existing modified/untracked
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same unrelated
backend/frontend edits already present since the N2 checkpoint) — nothing
staged, committed, restored, reset, or cleaned. N3.5 is not a Git checkpoint —
no `git add`/`commit` was performed; the checkpoint happens at N3.8.

**N3.5 status: COMPLETE. N3.6 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.6 (authenticated
application navigation).

## 41. N3.6 — Complete Remaining Authentication Contracts & Account-Recovery Operations

**N3.6 — Complete Remaining Authentication Contracts & Account-Recovery
Operations: COMPLETE.**

### 1. Backend auth endpoints re-verified

Re-read `AuthController.java`/`AuthService.java` directly for this phase (not
assumed from N3.0's report): still exactly **7** endpoints, unchanged --
`register`, `login`, `logout`, `refresh`, `forgot-password/request-otp`,
`forgot-password/verify-otp`, `forgot-password/reset-password`. No
discrepancy from the previously documented list -- no STOP condition
triggered. `register`/`login`/`refresh` were already modeled (N2.1/N3.3);
`logout` remains deliberately unmodeled as a network call (local-only, no
server-side effect -- N3.0 Section 7); the three `forgot-password/*`
endpoints were the entire remaining gap this phase closes.

### 2. Newly modeled endpoints

| Method | Path | Request DTO (Android) | Response |
|---|---|---|---|
| POST | `/api/v1/auth/forgot-password/request-otp` | `ForgotPasswordRequestDto` | `ApiResponse<String>` |
| POST | `/api/v1/auth/forgot-password/verify-otp` | `VerifyOtpRequestDto` | `ApiResponse<String>` |
| POST | `/api/v1/auth/forgot-password/reset-password` | `ResetPasswordRequestDto` | `ApiResponse<String>` |

All three confirmed, by direct source read, to return a plain confirmation
message (e.g. `"OTP sent"`) wrapped in the existing `ApiResponse<String>` --
never `AuthResponse`, never a token pair. None of the three require
authentication on the backend (`permitAll()`, same as register/login/refresh).

### 3. Request DTOs

`data/remote/auth/model/{ForgotPasswordRequestDto,VerifyOtpRequestDto,
ResetPasswordRequestDto}.kt` -- `@Serializable` data classes matching the
backend's field names exactly (`email`; `email`+`otpCode`; `email`+`otpCode`+
`newPassword`). Kept the "Dto" suffix to match the backend class names
exactly (`ForgotPasswordRequestDto.java` etc. themselves carry it, unlike
`LoginRequest`/`RegisterRequest`, which don't) -- consistent with the
established N2 convention of mirroring each backend class name as closely as
idiomatic, not a blanket "always drop Dto" rule. No backend validation
(`@NotBlank`/`@Email`/`@Size`) is re-implemented client-side, matching every
other request model's convention of trusting the backend as validation
authority.

### 4. Response DTOs

**None created.** All three endpoints reuse the existing `ApiResponse<String>`
exactly as-is -- confirmed there is no genuine contract difference to
justify a new response model, per Step 5's explicit instruction.

### 5. AuthApi changes

`AuthApi.kt` gained three methods (`requestForgotPasswordOtp`,
`verifyForgotPasswordOtp`, `resetPassword`), all still bound to the
UNAUTHENTICATED Retrofit/OkHttpClient instance in `NetworkModule` -- same
client register/login/refresh already use, for the same reason (all six
methods are `permitAll()` on the backend). No `ForgotPasswordApi`/`OtpApi`/
`PasswordApi` was created -- kept under the single `AuthApi`, since all six
operations are part of the one backend `AuthController`, per Step 6's
explicit instruction.

### 6. AuthRepository changes

`AuthRepository`/`AuthRepositoryImpl` gained
`requestPasswordResetOtp(email)`, `verifyPasswordResetOtp(email, otpCode)`,
`resetPassword(email, otpCode, newPassword)` -- all `suspend fun` returning
[`AccountRecoveryResult`](#7-authresult-handling) (below). No use-case class
was created -- the repository's three new methods already are the complete
operation, matching the reasoning already established for
register/login/refresh (Section 35 §10, Section 37 §1).

### 7. AuthResult handling

**A new, separate type was needed, not a reuse of `AuthResult`.**
`AuthResult.Success` is hardcoded to carry `user: UserDto` -- forcing that
shape onto three operations that return neither a user nor tokens would have
been wrong. Added `data/remote/auth/AccountRecoveryResult.kt`: a sealed
interface with the same four failure categories as `AuthResult`
(`ApiError`/`NetworkError`/`InvalidResponse`/`UnexpectedError`, identical
field shape) plus a `Success(message: String?)` carrying the backend's plain
confirmation text instead of a user. This is "following the established
architecture where applicable" (Step 8) without forcing an incompatible
payload shape.

**Refactor to avoid duplicating error-parsing logic:** the `HttpException`
-> `ErrorResponse`-parsing code that previously lived only inside
`authenticate()`'s private `toApiError()` extension was extracted into one
shared private `parseApiError(): ParsedApiError` (a tiny private data class
holding `status`/`code`/`message`), now called by both `authenticate()`
(building `AuthResult.ApiError`) and the new `recover()` flow (building
`AccountRecoveryResult.ApiError`). A real, justified extraction -- this
logic was about to be written nearly identically twice, not a speculative
abstraction.

### 8. OTP handling

Re-verified the backend OTP contract directly (`AuthService`): 6-digit code,
10-minute expiry, constant-time comparison (`MessageDigest.isEqual`),
60-second per-email rate limit, 5-attempt lockout -- all server-side,
unchanged, nothing to model beyond the request/response shapes already
covered above. **No OTP value is cached, stored, or held anywhere on the
Android side beyond the single `VerifyOtpRequestDto`/`ResetPasswordRequestDto`
object's lifetime for the duration of its one POST call** -- confirmed by
inspection: `AuthRepositoryImpl` constructs the request DTO inline inside
each `recover { ... }` call and never assigns the OTP string to any field,
`companion object`, or other persistent location. No OTP caching was
invented, per Step 9's explicit instruction. No OTP value is logged (no
`Log.*` call exists in any modified file) or placed in an `Intent`/
`SavedStateHandle`/`Bundle` (none of those APIs are used anywhere in the
current source tree -- confirmed by the same grep already run in N3.5).

### 9. Password-reset behavior

`newPassword` flows through exactly one path: `ResetPasswordRequestDto` ->
one POST call -> discarded. Never logged, never included in an exception
(the `recover()` flow's `IOException`/`HttpException`/`SerializationException`
handling only ever surfaces connection/HTTP-status/parse-error messages,
never request body content), never persisted anywhere, never appears in any
`AccountRecoveryResult` variant. No password manager or credential vault was
introduced, per Step 10's explicit instruction -- request objects are used
only for the single call that needs them, exactly like every other request
DTO in this codebase already works.

### 10. Session-state interaction

**Verified by direct test, not merely by inspection:** all three new
`AuthRepository` methods route through `recover()`, which -- unlike
`authenticate()` -- never references `SessionManager` at all (confirmed by
reading the method body: no `sessionManager.setAuthenticated`/`clearSession`
call exists anywhere in `recover()` or the three new public methods). Two
tests specifically prove this holds even under adversarial conditions: a
plain "does not touch the session" assertion on every success/failure path
(11 of the 11 new tests assert `sessionManager.sessionState.value ==
SessionState.Unknown` after the call, since no prior login occurred), and a
dedicated `account recovery never authenticates even when an existing
session is already established` test that logs in first (reaching
`Authenticated`), then calls `resetPassword`, and asserts the session
remains exactly `Authenticated` with the original tokens completely
untouched afterward -- proving a recovery call can never accidentally clear
or alter a session that has nothing to do with it either.

### 11. Security considerations

All already-established N3.5 protections continue to apply unmodified to
the new code: no logging of email/OTP/password anywhere, no
`HttpLoggingInterceptor`, tokens (irrelevant here, since these calls never
produce or consume one) still never appear in these three flows,
`EncryptedTokenStorage` is never touched by any of the three new methods
(confirmed -- `recover()` has no `TokenStorage` dependency at all, matching
`AuthRepository`'s existing "never touches `TokenStorage` directly" boundary
from N3.2). Both new backend-error tests (`OTP_RATE_LIMITED`, `INVALID_OTP`,
`EXPIRED_OTP`) confirm the real backend error codes surface cleanly through
`AccountRecoveryResult.ApiError` without leaking any raw exception/response
text beyond the structured `status`/`code`/`message` fields already
established as safe in N3.5 §15.

### 12. Username-first identity rule -- confirmed preserved

Re-confirmed throughout: `LoginRequest.usernameOrEmail` (unchanged, from
N2.1) remains the only login identity field; the three new recovery DTOs use
`email` exactly as the backend defines it (account recovery is
email-based on this backend, username-based discovery/login is a separate,
already-existing concern -- the two were never conflated). No `PhoneNumber`/
`ContactNumber`/`PhoneAuthRequest`/`PhoneLoginRequest` type or field was
created anywhere.

### 13. Explicit exclusion of phone contacts

**No Android Contacts functionality was added or considered.** Confirmed:
`AndroidManifest.xml` still declares exactly one permission
(`android.permission.INTERNET`, unchanged since N1.4) -- no
`READ_CONTACTS`/`WRITE_CONTACTS` was added; no `ContactsContract` import or
reference exists anywhere in the source tree; no contact-synchronization or
phone-number-matching code was written. ConnectX's user-discovery model
remains exactly what it already was: username-based, via the existing
backend `UserApi`/username search -- this phase touched none of that and
introduced nothing that competes with or duplicates it.

### 14. Tests

Extended the existing `AuthRepositoryImplTest.kt` -- **11 new tests**, using
the exact same `Fixture`/`FakeTokenStorage`/`FakeAuthApi` pattern already
established for login/register/refresh (no new test infrastructure, no
mocking framework, no real network). `FakeAuthApi` gained three configurable
action lambdas (`requestOtpAction`/`verifyOtpAction`/`resetPasswordAction`)
plus three `last*Request` capture fields (used to assert the exact DTO
values `AuthRepositoryImpl` actually sent). Covers: request-OTP success/
API-error (`OTP_RATE_LIMITED`)/network-error, verify-OTP success/API-error
(`INVALID_OTP`)/network-error, reset-password success/API-error
(`EXPIRED_OTP`)/network-error, a malformed (`success: false`) envelope
mapped to `InvalidResponse`, and the "recovery never authenticates, even
with a pre-existing session" regression test (§10). `TokenAuthenticatorTest.kt`'s
`FakeAuthRepository` and `SessionIntegrationTest.kt`'s `FakeAuthApi` were
both extended with no-op (`error("not used in this test")`) implementations
of the three new interface members -- required purely for compilation
(both interfaces gained new abstract members), not because either test
exercises account recovery; no existing test's behavior or assertions were
changed.

### 15. Build verification

`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, **110/110 pass** (99
pre-existing + 11 new). `./gradlew assembleDebug` → BUILD SUCCESSFUL, no
errors. No AGP/Kotlin/Gradle/Hilt/Retrofit/OkHttp/Android SDK version was
changed.

### 16. Device verification

- **Pixel 7a emulator** (`emulator-5554`) -- fresh `install -r`, force-stopped
  and relaunched cleanly, live PID, `ActivityTaskManager: Displayed
  com.connectx.app/.MainActivity` confirmed in logcat, zero `FATAL`/
  `AndroidRuntime` lines.
- **Vivo X200 FE** -- **NOT VERIFIED / DEVICE UNAVAILABLE.** `adb devices -l`
  (including a full daemon restart) showed only the emulator this session;
  no device configuration was modified to force availability.

No auth/recovery UI exists, so verification remained launch-only, consistent
with every prior N3 sub-phase.

### 17. Explicit N3.6 boundaries (what this phase did NOT do)

No `LoginScreen`/`RegisterScreen`/`ForgotPasswordScreen`/OTP screen/
`ResetPasswordScreen`, no auth navigation graph, no application shell was
created -- `MainActivity.kt`, `ConnectXNavHost.kt`, and all existing UI
remain byte-for-byte unchanged. No Android Contacts permission, API, or
synchronization code was added (§13). No use-case classes, no
`ForgotPasswordApi`/`OtpApi`/`PasswordApi` split, no OTP caching, no
credential vault were created. `connectx-backend/` and `connectx-frontend/`
were not touched.

### 18. Deferred to N3.7/N3.8

Login/register/forgot-password UI and the auth navigation graph that
consumes all of `AuthRepository`'s now-complete surface (N3.4's original
"N3.5/N4" cross-reference, still applicable), any further refresh/
error-handling UX polish (N3.7), and the final N3 verification/checkpoint
(N3.8) -- which will finally create the Git commit checkpointing all of
N3.0-N3.8's work.

**Files created:** `data/remote/auth/AccountRecoveryResult.kt`,
`data/remote/auth/model/{ForgotPasswordRequestDto,VerifyOtpRequestDto,
ResetPasswordRequestDto}.kt`.

**Files modified:** `data/remote/auth/AuthApi.kt` (+3 methods),
`data/remote/auth/AuthRepository.kt` (+3 methods),
`data/remote/auth/AuthRepositoryImpl.kt` (+3 impls, refactored shared error
parsing), `app/src/test/java/.../AuthRepositoryImplTest.kt` (extended
`FakeAuthApi`, +11 tests), `app/src/test/java/.../TokenAuthenticatorTest.kt`
and `app/src/test/java/.../SessionIntegrationTest.kt` (interface-conformance
no-ops only, no behavior change), `docs/CONNECTX_ANDROID_DEVELOPMENT.md`
(Phase Status + this section).

**No real authentication/network requests were made** -- every test uses a
hand-written `FakeAuthApi`; no code path calls `app.myconnect.sbs` or
`10.0.2.2`.

**No UI was changed** -- confirmed above (§17).

**No backend/frontend files were changed** -- confirmed via `git status
--short` before and after (below).

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase -- identical set of pre-existing modified/untracked
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same unrelated
backend/frontend edits already present since the N2 checkpoint) -- nothing
staged, committed, restored, reset, or cleaned. N3.6 is not a Git checkpoint
-- no `git add`/`commit` was performed; the checkpoint happens at N3.8.

**N3.6 status: COMPLETE. N3.7 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.7.

## 42. N3.7 — Authentication Lifecycle & Expired-Session Verification

**N3.7 — Authentication Lifecycle & Expired-Session Verification: COMPLETE.**

Verification-only phase, as scoped: no ViewModel, no UI, no navigation, no
production behavior change. The one code change is a single new test.

### Inspection result (Step 1)

Directly re-read every file listed in Step 1 (`SessionState.kt` through
`ConnectXNavHost.kt`, plus backend `AuthController`/`AuthService`/
`JwtTokenProvider`/`JwtAuthenticationFilter`/`SecurityConfig`/
`GlobalExceptionHandler`) for this phase, not from the N3.6 report. **Exact
match, no discrepancy found** — the two-client `NetworkModule` split,
`AuthInterceptor`, `TokenAuthenticator`'s single-flight/retry-limit/
credential-rejection-vs-network-failure logic, `SessionManager`'s
restore/setAuthenticated/clearSession trio, and all 7 backend auth endpoints
are exactly as N3.6 documented them. No STOP condition was triggered.

### Authenticated request flow (Step 3) — verified, unchanged

`AuthInterceptor` attaches `Authorization: Bearer <accessToken>` exactly
once when a token exists, adds nothing when it doesn't, and is bound only to
the authenticated `OkHttpClient` — the unauthenticated client (`AuthApi`)
has no interceptor at all, confirmed both by `NetworkModule` source and by
`AuthSecurityTest`'s live-refresh-carries-no-Authorization-header test.
Already fully covered by `AuthInterceptorTest` (N3.3) and `AuthSecurityTest`
(N3.5) — not re-tested.

### 401/expired-token flow, token rotation, retry (Steps 4, 5, 7)

The complete `401 → TokenAuthenticator → refresh via unauthenticated client
→ rotate both tokens → retry with new access token` path, its single-flight
`Mutex` + compare-current-token-before-refreshing optimization (proven with
a real 5-thread concurrent test), and its `priorResponse`-depth retry limit
are all exactly as N3.3 built them and exactly as N3.4's `SessionIntegrationTest`
proved them against a REAL `SessionManager`. Re-verified by direct
inspection — no defect found, nothing rewritten. Already fully covered by
`TokenAuthenticatorTest` (9 tests) and `SessionIntegrationTest` (4
pre-existing tests) — not re-tested.

### Refresh failure behavior (Step 6) — the one genuine gap, now closed

**A. Refresh 401/credential rejection → `SessionManager.clearSession()` →
`Unauthenticated`, no endless retry.** Already covered
(`TokenAuthenticatorTest`, `SessionIntegrationTest`).

**B. Refresh network failure → session/credentials preserved.** Already
covered (`TokenAuthenticatorTest`, `SessionIntegrationTest`).

**C. Refresh malformed response → no corruption, no infinite loop, session
remains consistent.** **Not previously tested at all** — every existing
refresh test (in `TokenAuthenticatorTest`, `SessionIntegrationTest`, and
`AuthRepositoryImplTest`) used a hand-written fake `AuthApi` whose
`refreshAction` lambda returns a ready-made Kotlin `ApiResponse` object
directly — real JSON bytes never flow through Retrofit's kotlinx.serialization
converter in any of them, so `AuthRepositoryImpl.authenticate`'s `catch (e:
SerializationException)` branch had never actually executed in any test in
the entire suite.

**New test:** `a malformed refresh response is treated as a clean session
invalidation, not token corruption` (`SessionIntegrationTest.kt`) — builds a
**real** `AuthApi` via `Retrofit.create(AuthApi::class.java)` against a
second, independent `MockWebServer` (mirroring `NetworkModule`'s actual
unauthenticated client exactly: same `OkHttpClient.Builder().build()`, same
`kotlinx.serialization` converter, no interceptor), and has that server
return `"{ this is not valid json at all"` for the refresh call. Confirms
the **existing, unmodified** design already handles this correctly:
`TokenAuthenticator` groups `AuthResult.UnexpectedError` together with
`ApiError`/`InvalidResponse` under credential-rejection (documented in its
own N3.3 doc comment — a deliberate choice, not an oversight discovered
this phase), so a malformed refresh response results in
`AuthRepository.logout()` being called — a clean `SessionManager.clearSession()`
that atomically nulls both tokens and publishes `Unauthenticated`, never a
half-written state where one token is cleared and the other stale. The
original request's `401` propagates back to the real caller (no crash, no
infinite retry) since the `Authenticator` returns `null`. **No production
code was changed to make this pass** — the audit found the behavior already
correct on inspection; only the test coverage was missing.

### Logout (Step 8) — verified, unchanged

Local-only: `TokenStorage.clearTokens()` + `SessionState.Unauthenticated`,
no backend call, matching N3.0's finding that `/auth/logout` has no
server-side revocation effect. Already covered by `AuthRepositoryImplTest`
and `SessionManagerTest` — not re-tested.

### Process recreation / restoration (Step 9) — verified, unchanged

A second, independent `SessionManager` instance reading tokens a first,
now-discarded instance persisted correctly reaches `Authenticated` via
`restoreSession()` — no network request, no JWT decoding, purely local, as
N3.0 designed. Missing-access-only/missing-refresh-only/both-missing/partial-pair-cleared
are all covered by N3.1's `SessionManagerTest`. Already covered by
`SessionIntegrationTest` (process-recreation) and `SessionManagerTest`
(partial-token cases) — not re-tested.

### Recovery regression (Step 10) — verified, unchanged

Re-confirmed `requestPasswordResetOtp`/`verifyPasswordResetOtp`/`resetPassword`
never call `SessionManager` under any code path (`recover()` has no
`SessionManager` reference at all). Already covered by N3.6's 11
`AuthRepositoryImplTest` additions, including the dedicated "recovery never
authenticates even when an existing session is already established"
regression test — not re-tested.

### Security regression (Step 11)

Re-ran the same audit categories as N3.5 against the current source: no
`Log.*`/`println`, no `HttpLoggingInterceptor`, no token/password/OTP in any
exception message, no `Intent`/`SavedStateHandle`/`Bundle`/`ClipboardManager`
usage anywhere in the source tree, no duplicate token persistence outside
`EncryptedTokenStorage`, no `READ_CONTACTS`/`WRITE_CONTACTS`/`ContactsContract`,
no phone-number field or type anywhere. All confirmed absent, exactly as
N3.5/N3.6 left them — no new finding, nothing to fix.

### Tests

**1 new test** (`SessionIntegrationTest.kt`, described in full above). The
other 15 of Step 12's 16 required scenarios were deliberately **not**
re-tested, per the explicit "do not duplicate existing tests unnecessarily"
instruction — each is already proven, unchanged and still passing, by
`AuthInterceptorTest`/`AuthSecurityTest`/`TokenAuthenticatorTest`/
`SessionIntegrationTest`/`SessionManagerTest`/`AuthRepositoryImplTest`
(N3.1-N3.6).

**Test results: 111/111 total, 0 failures** (110 pre-existing + 1 new).
Re-ran the full suite 2 additional times (`--rerun`, bypassing Gradle's test
cache) — no flake.

### Build verification

`./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, 111/111 pass. `./gradlew
assembleDebug` → BUILD SUCCESSFUL. No AGP/Kotlin/Gradle/Hilt/Retrofit/
OkHttp/Android SDK version was changed.

### Device verification

- **Pixel 7a emulator** (`emulator-5554`) — fresh `install -r`, force-stopped
  and relaunched cleanly, live PID, `ActivityTaskManager: Displayed
  com.connectx.app/.MainActivity` confirmed, zero `FATAL`/`AndroidRuntime`
  lines.
- **Vivo X200 FE** — **NOT VERIFIED / DEVICE UNAVAILABLE.** `adb devices -l`
  (including a full daemon restart) showed only the emulator this session;
  no device configuration was modified to force availability.

No auth UI exists, so verification remained launch-only, consistent with
every prior N3 sub-phase.

### Explicit N3.7 boundaries (what this phase did NOT do)

No `LoginScreen`/`RegisterScreen`/OTP screen/`ForgotPasswordScreen`/
`ResetPasswordScreen`, no `AuthViewModel`, no auth navigation graph, no
application shell was created — `MainActivity.kt` and `ConnectXNavHost.kt`
are byte-for-byte unchanged. No `SessionManager`/`TokenStorage`/
`AuthRepository`/`AuthInterceptor`/`TokenAuthenticator` production source
was modified — inspection found the existing N3.0-N3.6 architecture already
correct; the one gap found was in test coverage, not behavior. No Android
Contacts permission/API was added. `connectx-backend/` and
`connectx-frontend/` were not touched.

**Files created:** none.

**Files modified:** `app/src/test/java/com/connectx/app/core/session/SessionIntegrationTest.kt`
(+1 test), `docs/CONNECTX_ANDROID_DEVELOPMENT.md` (Phase Status + this
section) only.

**No real authentication/network requests were made** — the new test uses
two local `MockWebServer` instances; no code path calls `app.myconnect.sbs`
or `10.0.2.2`.

**No UI was changed** — confirmed above.

**No backend/frontend files were changed** — confirmed via `git status
--short` before and after (below).

**PWA calling changes confirmed untouched:** `git status --short` compared
before and after this phase — identical set of pre-existing modified/untracked
paths (`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/`, plus the same unrelated
backend/frontend edits already present since the N2 checkpoint) — nothing
staged, committed, restored, reset, or cleaned. N3.7 is not a Git checkpoint
— no `git add`/`commit` was performed; the checkpoint happens at N3.8.

**N3.7 status: COMPLETE. N3.8 remains NOT STARTED.** STOPPED here per
instructions — waiting for review before implementing N3.8 (final N3
verification/checkpoint).

## 43. N3.8 — Authentication Phase Git Checkpoint

**N3.8 — Authentication Phase Git Checkpoint & Final Verification: COMPLETE.**
**N3 (Authentication) is now COMPLETE.**

Pure checkpoint/verification phase — no new functionality, no architecture
change. This section documents the final state committed.

### Final N3 scope

Everything committed by this checkpoint is exactly the authentication/session
domain built across N3.0-N3.7:

- **Secure token storage** (N3.1): `TokenPair`, `TokenStorage`,
  `EncryptedTokenStorage` (Keystore-backed `EncryptedSharedPreferences`),
  `AuthStorageModule`, plus targeted backup/data-extraction exclusions.
- **Session state** (N3.1/N3.4): `SessionState` (`Unknown`/`Authenticated`/
  `Unauthenticated`), `SessionManager` (the single `StateFlow` source of
  truth), wired into `ConnectXApplication.onCreate()` for local-only startup
  restoration on a dedicated `Dispatchers.IO` scope.
- **Authentication repository** (N3.2/N3.6): `AuthApi` (register, login,
  refresh, and the three forgot-password/OTP/reset-password endpoints —
  6 of the backend's 7 auth endpoints; `logout` remains deliberately
  local-only, no network call), `AuthRepository`/`AuthRepositoryImpl`,
  `AuthResult` (register/login/refresh outcomes) and the separate
  `AccountRecoveryResult` (account-recovery outcomes — never authenticates),
  `AuthRepositoryModule`.
- **Authenticated network layer** (N3.3): the two-client `NetworkModule`
  split (`@UnauthenticatedClient` for `AuthApi`, `@AuthenticatedClient` for
  every other API), `AuthInterceptor`, `TokenAuthenticator` (single-flight
  refresh, retry-limit, credential-rejection-vs-network-failure handling),
  `NetworkQualifiers`.
- **Security hardening** (N3.5): the debug-only Network Security Config
  (`app/src/debug/`) permitting cleartext to exactly `10.0.2.2`, with
  release's default cleartext-disallowed posture confirmed unaffected.
- **Comprehensive tests** (N3.1-N3.7): 111 tests across
  `SessionManagerTest`, `SessionIntegrationTest`, `AuthInterceptorTest`,
  `AuthSecurityTest`, `TokenAuthenticatorTest`, `AuthRepositoryImplTest`.

### Final authentication architecture (unchanged from N3.7)

```
AuthApi (unauthenticated client: register/login/refresh/recovery)
    ↓
AuthRepository → AuthResult / AccountRecoveryResult
    ↓
SessionManager (StateFlow<SessionState>) ← ConnectXApplication.onCreate()
    ↓
TokenStorage → EncryptedTokenStorage → EncryptedSharedPreferences → Android Keystore

Authenticated client: AuthInterceptor (attach Bearer) + TokenAuthenticator
(401 → single-flight refresh → rotate both tokens → retry)
```

### Final test count

**111/111 passing**, re-confirmed one final time this phase (no change since
N3.7 — no test was added, modified, or removed in N3.8).

### Build verification

`./gradlew assembleDebug` → BUILD SUCCESSFUL. `./gradlew assembleRelease` →
BUILD SUCCESSFUL (both re-verified this phase). No dependency/AGP/Kotlin/
Gradle/Hilt/KSP/Retrofit/OkHttp/Android SDK version was changed.

### Device verification

- **Pixel 7a emulator** (`emulator-5554`) — went beyond the launch-only
  precedent used throughout N3.1-N3.7 for this final gate: fresh install,
  launch (live PID, `Displayed` confirmed), then live-interacted via
  `uiautomator dump` + `input tap` through the full existing UI: HOME →
  tapped "Go to Navigation Test" → confirmed "Navigation Test Destination"
  text rendered → tapped Back → confirmed HOME re-rendered → tapped "Design
  System Showcase" → confirmed Colors/Typography/Buttons sections rendered
  → tapped the theme toggle → screenshot-confirmed dark theme (dark
  background, toggle now offering "Light theme") → tapped again →
  screenshot-confirmed light theme (light background, toggle now offering
  "Dark theme"). Same process PID throughout the entire sequence — no
  crash/restart. Zero `FATAL`/`AndroidRuntime`/`Exception` lines in logcat
  across the whole interaction.
- **Vivo X200 FE** — **NOT VERIFIED / DEVICE UNAVAILABLE.** `adb devices -l`
  (including a full daemon restart) showed only the emulator this session;
  no device configuration was modified to force availability.

### Security verification (final regression, re-run fresh this phase)

Fresh greps (not reused from N3.5/N3.7's results) across `app/src/main` and
`app/src/debug` confirmed, once more: zero `Log.*`/`println`/`System.out`/
`printStackTrace`, zero `HttpLoggingInterceptor`/`Level.BODY`/`Level.HEADERS`,
zero `ContactsContract`/`READ_CONTACTS`/`WRITE_CONTACTS`/phone-number
references anywhere, zero `ClipboardManager`/`SavedStateHandle` usage, and
exactly one manifest permission (`android.permission.INTERNET`). Confirmed
`connectx_secure_auth_prefs.xml` remains excluded in both `backup_rules.xml`
and both scopes of `data_extraction_rules.xml`. Confirmed `AuthInterceptor`
still never reads `getRefreshToken()` at all (only `getAccessToken()`) —
the refresh token cannot reach an `Authorization` header through this class,
by construction. No real backend authentication request was made anywhere
during this phase's verification.

### Staged-file isolation

`git diff --cached --name-only` was run and inspected **before** committing
(per Step 10/11's explicit requirement) and confirmed to contain **only**:
every file under `connectx-android/` touched across N3.0-N3.7 (7 modified +
~21 new files spanning `app/src/debug/`, `core/network/{NetworkQualifiers.kt,
auth/}`, `core/session/`, `data/local/`, `data/remote/auth/{*.kt,model/}`,
`app/src/test/java/com/connectx/app/core/`, and
`app/src/test/.../AuthRepositoryImplTest.kt`) plus
`docs/CONNECTX_ANDROID_DEVELOPMENT.md`. **Zero** files under
`connectx-backend/`, `connectx-frontend/`, or any PWA-calling path appeared
in the staged list — confirmed by direct inspection of the diff output, not
merely by construction of the `git add` command.

### PWA calling / unrelated-changes preservation

The same pre-existing modified/untracked paths present at the start of this
phase — `connectx-backend/src/main/resources/application.yml`,
`connectx-backend/src/test/resources/application-test.yml`,
`connectx-frontend/src/{App.tsx,components/chat/*,main.tsx,types/index.ts,
utils/notificationSound.ts,websocket/WebSocketClient.ts}`,
`connectx-backend/.../call/`, `connectx-frontend/src/calling/`,
`connectx-frontend/src/components/call/` — remain exactly as they were:
unstaged, uncommitted, unmodified, undeleted. No `git restore`/`reset`/
`clean`/`stash` was used anywhere in this phase.

### Commit

One commit created (`feat(android): complete ConnectX N3 authentication`) —
hash and full post-commit verification reported in the session's final
report for this phase (not duplicated here to avoid a stale hash reference
if this document is ever read independently of that report — see the git
log directly for the authoritative commit record).

### Remote synchronization status

Reported in the session's final report for this phase, from `git status -sb`/
`git branch -vv` at the time of the checkpoint. **No push was performed** —
push requires explicit separate approval, per instructions.

### N3 completion

**N3 — Authentication: COMPLETE.** All of N3.0 (architecture) through N3.8
(this checkpoint) are done, verified, and committed. **N4 — Core App Shell:
NOT STARTED** — the real Login/Register/Forgot-Password UI, authentication
navigation graph, and application shell remain entirely for N4 to build on
top of the `AuthRepository`/`SessionManager` foundation this phase
finalizes.
