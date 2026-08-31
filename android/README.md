# Meal Tracker — Android App (Skeleton)

## What this is

A minimal but real Android project skeleton: Gradle setup, Compose UI,
Material3 theming, bottom navigation with the four screens from the
design doc (Home / Journal / Meal Plan / Profile), and a working
networking layer (Retrofit + OkHttp + kotlinx.serialization) wired up to
call your backend API.

The Home screen calls `GET /health` on startup and shows whether it
successfully reached your Pi — this is the whole point of the skeleton:
prove the full chain (Compose UI → ViewModel → Retrofit → your Pi's API
→ back again) actually works on your device before building out real
screens on top of it.

## IMPORTANT — honesty about what's been tested

Unlike the Python backend (which I tested extensively against a real
running Postgres instance), **I cannot compile or run an Android/Gradle
project in this environment** — there's no Android SDK, no emulator,
no Gradle installation here. This skeleton is written carefully
following standard, well-established Android/Kotlin/Compose patterns,
but **you will be the first real build** — same situation as the
Dockerfile ARM build earlier. If something doesn't compile, paste me the
exact error and we'll fix it against real feedback, same as we did there.

## Setup steps

1. **Open in Android Studio**: File → Open → select the
   `meal-tracker-android` folder (the one containing `settings.gradle.kts`).
2. Android Studio should offer to generate the Gradle wrapper
   automatically on first open (there's no `gradlew`/wrapper jar included
   here — those are normally binary files Android Studio generates, not
   something to hand-write). If it doesn't prompt automatically: File →
   Sync Project with Gradle Files.
3. **Update the two placeholder values** in `app/build.gradle.kts`:
   - `BASE_URL` — currently set to `http://100.74.254.69:8000/` (your
     Pi's Tailscale IP from earlier in our conversation) — double check
     this still matches, since Tailscale IPs can occasionally change.
     Must end in a trailing `/`.
   - `API_KEY` — currently a placeholder — set this to match whatever
     you put in the Pi's `.env` as `API_KEY`.
4. **Make sure your phone has Tailscale installed and connected** to the
   same tailnet as the Pi — same requirement as when you tested
   `/health` from Chrome on your phone earlier.
5. Connect your phone via USB (with USB debugging enabled in Developer
   Options) or use wireless debugging, and hit Run in Android Studio.
6. You should see "Meal Tracker" with either a green checkmark and
   "Connected — API status: ok", or a red X with an error message and a
   Retry button if something's not reachable.

## Project structure

```
app/src/main/java/com/mealtracker/android/
  MainActivity.kt          — entry point, sets up the Compose content
  network/
    ApiClient.kt            — Retrofit/OkHttp singleton, adds the
                              X-API-Key header to every request
    ApiService.kt            — Retrofit interface; each function here =
                              one backend endpoint. Only /health and a
                              basic item search are here for now — add
                              more functions as we build each screen
    models/
      Models.kt              — Kotlin data classes mirroring the
                              backend's Pydantic schemas. Note: numeric
                              macro fields are Strings, not Double/Float
                              — FastAPI serializes Decimal as a JSON
                              string to preserve precision; parse to a
                              real number only where you need to do math
  ui/
    theme/                   — standard Compose Material3 theme
                              boilerplate (Color.kt, Theme.kt, Type.kt)
    navigation/
      AppNavHost.kt          — bottom nav bar + NavHost wiring the four
                              destinations
    screens/
      HomeScreen.kt          — calls /health, shows connection status
      HomeViewModel.kt       — the ViewModel/StateFlow/coroutine pattern
                              to copy for every future screen
      PlaceholderScreens.kt  — stubs for Journal/Meal Plan/Profile
```

## What's deliberately NOT here yet

- Real Journal/Meal Plan/Profile screens — placeholders only
- Any of the actual feature screens from the design doc (meal cards,
  Add Item flow, barcode scanning via ML Kit, camera integration, etc.)
- Local data persistence/caching (currently every screen would hit the
  network fresh — fine for now, revisit if offline support matters later)
- Dependency injection framework (Hilt etc.) — using simple singleton
  objects for now; revisit if the app's complexity grows enough to want it

## Kotlin-for-newcomers notes

A few things that might look unfamiliar coming from Python/Dart/TypeScript:

- `val` = immutable binding (like `const` in TS, or just... always in
  Python since you don't declare mutability). `var` = mutable, use
  sparingly.
- `?` after a type (e.g. `String?`) means nullable — like TypeScript's
  `string | null`, or Dart's `String?`. Kotlin is very strict about this
  at compile time, which catches a lot of bugs before they happen.
- `suspend fun` = an async function, conceptually like Python's
  `async def` — can only be called from within a coroutine (e.g. inside
  `viewModelScope.launch { }`, which is roughly like wrapping something
  in `asyncio.create_task()`).
- `data class` = like a Python `@dataclass` or a TypeScript interface —
  auto-generates equality, toString, copy, etc.
- Trailing lambda syntax (e.g. `viewModelScope.launch { ... }`) — the
  `{ }` block is the last argument to the function, just written outside
  the parens. Takes a little getting used to but becomes natural fast.
