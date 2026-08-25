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
N2            IN PROGRESS  (Backend Connectivity — see N2.0 below)
N2.0          COMPLETE
N2.1          COMPLETE
N2.2          COMPLETE
N2.3          COMPLETE
N2.4          COMPLETE
N2.5          COMPLETE
N2.6          COMPLETE
N2.7          COMPLETE
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
