# Meal Tracker — Backend Foundation

Database + migrations foundation, matching the design doc's schema exactly.
Verified end-to-end in a real Postgres instance: migration generates
correctly, applies cleanly, all constraints/foreign keys are enforced
(including the item-xor-recipe CHECK constraints), and downgrades cleanly
too.

## Setup

1. Install [Poetry](https://python-poetry.org/) if you don't have it.
2. `poetry install`
3. Copy `.env.example` to `.env` and fill in your real DB credentials + a
   real API key (generate one with
   `python -c "import secrets; print(secrets.token_urlsafe(32))"`).
4. Start Postgres: `docker-compose up -d postgres`
5. Apply migrations: `poetry run alembic upgrade head`
6. Run the API: `poetry run uvicorn app.main:app --reload --host 0.0.0.0 --port 8000`
7. Visit `http://localhost:8000/docs` for interactive API docs (Swagger UI)
   — click "Authorize" and enter your API key to try authenticated endpoints.

## Auth

Simple shared-secret auth via the `X-API-Key` header, checked in
`app/auth.py`. Appropriate for a single-user personal app reachable only
over Tailscale — no OAuth2/JWT, since there's no multi-user or
token-expiry need here. Every router except `/health` requires it.

## Project structure

```
app/
  __init__.py
  main.py         — FastAPI app entrypoint, wires up routers
  auth.py         — X-API-Key header check (require_api_key dependency)
  config.py       — settings loaded from .env (pydantic-settings)
  database.py     — SQLAlchemy engine/session setup, get_db() dependency
  models.py       — all ORM models, mirrors design doc section 3 exactly
  schemas.py      — Pydantic request/response models for the API
  nutrition.py    — shared macro-calculation math AND the rounding
                     policy. Internal computation always uses precise
                     Decimal (the `RawTotals` type); rounding UP to whole
                     numbers only happens at the API response boundary,
                     via `to_display()`/`ceil_int()`, called right before
                     a response is built. This matters: rounding every
                     component up before summing would compound into
                     meaningfully inflated weekly/monthly totals. Proven
                     end-to-end (see testing notes below) — three 9.3-kcal
                     logs each individually display as 10 (correct
                     per-entry rounding), but the day's summary correctly
                     shows 28, not 30, because it sums the precise stored
                     values first and rounds once.
  routers/
    items.py      — full CRUD for items: create, get, get-by-barcode,
                     list/search (with type filter), partial update,
                     delete, add/update/delete serving sizes
    recipes.py     — full CRUD for recipes (covers 'recipe' and 'meal'
                     types): create with ingredients, get/list/search
                     (filterable by recipe_type), partial update,
                     delete, add/update-quantity/remove ingredients.
                     Nutrition totals (whole recipe + per-serving) are
                     always computed live from ingredient items — never
                     stored — so editing an item's macros automatically
                     flows through to every recipe/meal using it, with
                     zero propagation logic needed.
    logs.py        — actual, committed food log entries. Macros are
                     SNAPSHOTTED at write time and stay frozen even if the
                     source item/recipe is edited later — historical
                     days/weekly summaries must not silently change (see
                     design doc). Includes a daily-summary aggregation
                     endpoint (sums the frozen snapshot columns, not a
                     live recompute).
    meal_plans.py  — planned, not-yet-committed meals. Deliberately the
                     OPPOSITE of logs: totals are always computed live,
                     reflecting current item/recipe data right up until
                     committed. Includes the "commit to tracker" action
                     (POST /meal-plans/commit with a date range): computes
                     and freezes macros into new `logs` rows at that exact
                     moment, then removes the source meal_plan rows —
                     this is the one-button "add this day/range/week to
                     the tracker" flow from the design doc.
    goals.py       — dated goal history (only one active at a time —
                     creating a new goal auto-closes the previous one's
                     end_date). Per-meal targets are stored as PERCENTAGES
                     of the overall goal (never absolute numbers), so
                     editing the overall kcal_target automatically
                     recalculates every meal's displayed calories with
                     zero manual sync. PUT /goals/{id}/meal-splits bulk-
                     replaces all splits at once and enforces the same
                     "must sum to exactly 100%" hard gate as the Meal
                     Calorie Goal screen in the design doc.
    user_profile.py — single-row profile (name, height, age,
                     primary_hormone, timezone, etc.) — auto-created on
                     first GET, since this is a single-user app with no
                     explicit "create profile" step needed.
alembic/
  env.py          — configured to read models.py + settings.database_url
  versions/       — migration files (initial schema already generated)
docker-compose.yml — Postgres service (FastAPI service commented out,
                      ready to uncomment for Pi deployment)
pyproject.toml    — Poetry deps (some commented out until needed: pyzbar,
                     pytesseract, langdetect, httpx — uncomment as we build
                     barcode/OCR/USDA features)
```

## What's been tested end-to-end

Ran against a real Postgres instance (not just import/syntax checks):
- Migration generates, applies, and downgrades cleanly
- All CHECK constraints enforce correctly (verified via the ORM directly
  and via HTTP requests)
- Full items CRUD via actual HTTP calls: create, duplicate-barcode
  rejection (409), get by id, get by barcode (404 when missing), list,
  search by name/brand, filter by type, partial update (PATCH), add/
  update/delete serving sizes, delete (with 404 confirmation after)
- Full recipes CRUD via actual HTTP calls: create with ingredients
  (computed totals verified by hand — math checks out exactly),
  **confirmed editing an item's macros automatically updates a recipe's
  computed totals with zero propagation code**, add/update/remove
  ingredients, duplicate-ingredient rejection (409), invalid item_id
  rejection (400), filter by recipe_type ('recipe' vs 'meal'), search by
  name, PATCH recalculates per-serving totals correctly, delete + 404
  confirmation after
- **Logs vs meal_plans snapshot behavior — the core design property,
  directly verified**: logged an item, then edited that item's kcal
  twice; confirmed the EXISTING log's `kcal_logged` stayed frozen at its
  original value both times, while a brand-new log for the same item
  picked up the current (edited) value. Then created a meal_plan for the
  same item and edited the item again — confirmed the meal_plan's
  `computed_totals` DID change (live reference, as designed) — the exact
  opposite behavior from logs, both verified end-to-end
- Logging semantics tested both ways: quantity-as-servings (with
  serving_size_id, e.g. "1 banana" = 120g) and quantity-as-grams-directly
  (no serving_size_id) — both compute correctly
- Recipe-based logging tested: logged 1.5 servings of a 4-serving recipe,
  confirmed kcal matches (per-serving total × 1.5) exactly
- Full commit-to-tracker flow tested: created two meal_plans for the same
  day, committed the date range, confirmed both meal_plans were removed
  and two new logs appeared with correctly frozen macros
- Daily summary endpoint tested: sums frozen `*_logged` columns grouped
  by date; fixed a float-precision artifact by summing in `Decimal`
  instead of `float`
- Validation tested: item_id + recipe_id both set (400), neither set
  (400), serving_size_id belonging to a different item (400), invalid
  meal_type (422 via Pydantic's Literal validation)
- **Integer rounding policy verified**: all API-facing nutrition values
  round UP to whole numbers (e.g. 111.6 kcal displays as 112), but proved
  this doesn't introduce drift — three separate 9.3-kcal logs each
  individually display as 10, yet the daily summary correctly totals 28
  (precise sum 27.9, rounded once), not the naive 30 you'd get by summing
  the already-rounded per-entry values. Verified across logs, recipe
  totals, and meal plan totals.
- Auth: missing key (422), wrong key (401), correct key (200) — verified
  on all routers
- Goals tested end-to-end: created a goal with default 25/25/25/25 meal
  split (verified computed kcal/macros per meal), replaced splits via
  PUT (rejected a 99%-summing set with 400, accepted a valid 30/30/30/10
  set), **confirmed editing the overall kcal_target automatically
  recalculated every meal's displayed calories** (600→720 etc, zero
  manual sync, since splits are stored as percentages) — the exact
  property the design called for. Also verified: creating a second goal
  auto-closes the first (end_date set to the day before the new goal's
  start), the active-goal endpoint correctly shifts to the new one, full
  history is preserved, and an out-of-order start_date is rejected (400).
- User profile tested: auto-creates as a single row on first GET, PATCH
  updates persist correctly, stays a stable singleton across requests,
  invalid `primary_hormone` value rejected (422 via Pydantic Literal)

## Sugar, saturated fat & sodium — tracked, but kept out of the main tracker UI

Per request: `items` and `logs` track `sugar`/`saturated_fat`/`sodium`
internally (same integrity rules as every other macro — frozen at log
time, editable on items with automatic recipe propagation), but these
values are deliberately **excluded** from the compact `NutritionTotals`
type used on items/recipes/logs/meal_plans, so the day-to-day tracker
UI doesn't get crowded. They only surface via a separate
`ExtendedNutritionTotals` type, used solely by `GET /logs/summary/daily`
— so you can see weekly sugar/sat-fat/sodium trends without them
cluttering every meal card or log entry. Verified end-to-end: created
items with all three fields, logged them, confirmed the log response
stayed exactly as compact as before, then confirmed the daily summary
correctly surfaced all three — and that they flow through the meal-plan
commit action too.

**Sodium note**: canonical unit is `sodium_mg_100g` (matches USDA
FoodData Central). EU nutrition labels (Lidl-DK included) show *salt* in
grams instead of sodium — the conversion is `salt(g) = sodium(g) x 2.5`,
i.e. `sodium_mg_100g = salt_g_100g x 400`. This is implemented as
`salt_g_to_sodium_mg()` (and the inverse, `sodium_mg_to_salt_g()`) in
`app/nutrition.py`, verified against known values (1.2g salt -> 480mg
sodium, 0.5g salt -> 200mg sodium, and the round trip back to salt).
**Whatever builds the OCR pipeline must call this conversion on any
salt value extracted from a label before it goes into an
ItemCreate/ItemUpdate payload — the DB only ever stores sodium, never a
raw salt number.** This is the one field where the label's unit and our
stored unit genuinely differ; every other macro maps straight across.

## Barcode scanning

`POST /items/scan-barcode` accepts an uploaded image and decodes a
barcode from it, then checks whether a matching item already exists.

**Decoder fallback chain — empirically justified, not arbitrary:**
tries `pyzbar` first, falls back to `zxing-cpp` if that finds nothing.
Testing with synthetic barcodes (rotated, blurred, low-contrast+noisy)
showed neither library is strictly better:
- `pyzbar` correctly decoded rotations up to ~40°, where `zxing-cpp`
  started failing past ~25-30°
- `zxing-cpp` correctly decoded a low-contrast/noisy image that `pyzbar`
  completely missed

So trying both, in that order, covers more real-world photo conditions
(bad lighting vs. an off-angle shot) than either alone would.

**Response shape** (`BarcodeScanResult`):
- `barcode`/`decoder_used` both `null` → neither decoder found anything;
  client should fall back to manual barcode entry
- `barcode` set, `item` `null` → decoded successfully, but no item with
  that barcode exists yet; client pre-fills the Add Item form with the
  decoded barcode
- `barcode` and `item` both set → matched an existing item directly
- `checksum_valid` → see the critical finding below before trusting this

**Docker note**: `pyzbar` is a Python wrapper around the `libzbar` C
library, which must be present at the OS level — the `Dockerfile`
installs `libzbar0` via `apt-get` before the Poetry install step.

### Critical finding from testing against 6 real product photos

Synthetic-barcode testing (above) was a useful first pass, but testing
against real photos of actual packaging (chocolate, popcorn, pasta,
bread, a Lidl jar, a supplement bag) revealed something synthetic tests
didn't: **2 of 6 photos decoded to a WRONG barcode value with no
indication anything was off** — not a failure, a confidently wrong
answer. (The other 4: 2 decoded correctly, 2 failed safely by returning
null.)

Worse: one of the two wrong values **passed its own EAN-13 checksum**.
Verified this directly — computed the checksum for the misdecoded
chocolate-bar barcode and confirmed it validates as structurally correct
despite being the wrong code entirely. This means **`checksum_valid:
true` is not proof of correctness** — a garbled read can still land on a
valid-looking checksum. EAN-13/UPC-A checksum validation is implemented
in `app/barcode.py` and does filter out *some* garbled reads (definite
checksum failures are rejected outright, forcing fallback to the other
decoder or ultimately `null`), but it is a cheap first filter, not a
guarantee.

**Consequence for the client (Android/web) that will call this
endpoint**: it must always display the decoded `barcode` number
prominently and let the user visually confirm it against the number
printed on the physical package before proceeding to match or create an
item. Never auto-proceed silently just because a barcode was decoded or
even because `checksum_valid` is `true` — real-world testing showed
that's not sufficient. This is now documented directly in
`app/barcode.py` and `BarcodeScanResult`'s docstring so it isn't
forgotten when the client is built.

**Tested end-to-end**: both with synthetic images (rotation/blur/noise
sweeps to justify the fallback ordering) and with 6 real photos of actual
home products via real HTTP requests to the running endpoint — auth
enforced, checksum validation confirmed to filter some-but-not-all bad
reads, and the client-must-confirm requirement identified as a hard
requirement rather than a suggestion.

## USDA FoodData Central integration

`GET /usda/search?query=...` and `GET /usda/food/{fdc_id}` proxy USDA's
FoodData Central API for the raw-ingredient search/import flow (see
design doc). Nothing here writes to our DB directly — results are for
the client to display, and creating an actual item happens via the
normal `POST /items` call with the user reviewing/editing the pre-filled
macros first, same pattern as barcode scanning and OCR.

**Setup**: get a free API key at https://fdc.nal.usda.gov/api-key-signup
and set `USDA_API_KEY` in `.env`. The public `DEMO_KEY` works for initial
testing but is heavily rate-limited (30/hour, 50/day) and appears to get
blocked outright once its shared usage across everyone testing USDA's
docs exceeds that.

**A real bug caught during testing, not just synthetic edge cases**:
FDC often lists multiple related nutrient entries for the same macro at
once — e.g. `Fiber, soluble` / `Fiber, insoluble` / `Fiber, total
dietary` all present together, or `Sugars, added` alongside `Sugars,
total including NLEA`. An initial naive "does the name contain 'fiber'"
substring match silently grabbed the wrong sub-type (soluble fiber
instead of total, `0` added-sugar instead of the real total sugar value)
— this would have quietly corrupted every imported item that had these
sub-breakdowns present, which is common in Foundation Foods entries.
Fixed with a priority-ordered matcher (`_MATCH_PRIORITIES` in
`app/usda.py`) that always tries "total"-phrased patterns first, across
*all* nutrient entries, before ever falling back to a looser match.
Verified against realistic fixture data modeling both of FDC's two
different nutrient JSON shapes (the search endpoint and detail endpoint
use different field names — `nutrientName`/`value` vs
`nutrient.name`/`amount` — both normalize to identical output).

**Honesty about what's actually been tested here**: this environment's
network egress doesn't include USDA's API domain, so genuine live
end-to-end testing against FDC's real API could not happen in this
sandbox — every attempt (via direct curl and via the running app's own
`httpx` calls) hit the same local network block, not USDA's real
servers. What IS verified: the nutrient-matching logic itself (including
the sub-type bug above) against realistic sample payloads matching
USDA's documented format exactly, and that the API layer (auth,
error-handling on failure, response schema) behaves correctly — a failed
upstream call returns a clean `502` with a clear message rather than
crashing, regardless of why it failed. **The first real test against
USDA's live API needs to happen on the Pi**, which has normal internet
access — try `GET /usda/search?query=banana` once deployed there and let
me know what comes back.

## OCR nutrition label scanning

`POST /items/scan-label` accepts an uploaded photo of a nutrition label,
runs it through Tesseract across all 9 supported languages combined
(Danish, German, English, Swedish, Finnish, Norwegian, Spanish, Slovak,
Czech), picks the best-matching language by counting keyword hits per
language's dictionary against the recognized text, then parses per-100g
macros using that language's keyword patterns. Like barcode scanning and
USDA import, this **never writes to our DB** — the result is a draft for
the client to pre-fill the Add Item review form, corrected by the user
before anything is saved.

**Tested against real Tesseract OCR** (not just designed on paper) —
generated realistic synthetic label images in Danish, German, and
English and ran them through the actual installed `tesseract` binary.
Two genuine bugs were found and fixed this way, not by code review:

1. **German ß misread as B**: Tesseract's `deu` model read "Eiweiß"
   (protein) as "EiweiB", silently causing zero fields to match for
   protein specifically. Fixed by making the German protein keyword
   pattern tolerant of `eiweiß`/`eiweiss`/`eiweib`/`eiweis`.
2. **Blurred "g" misread as "9", corrupting numbers**: on a rotated +
   blurred test image, Tesseract read "12,5 g" fine (space preserved
   the number) but merged "25,0 g" into "25,09" and "10,3 g" into
   "10,39" — the unit letter got misread as a digit and glued onto the
   value with no separating space, silently inflating every affected
   number. Fixed by restricting the number-extraction regex to at most
   one decimal digit, matching the fact that EU nutrition labels always
   report to 1 decimal place — the corrupted extra digit is now
   correctly left uncaptured. Verified this fix resolves the degraded
   case with zero regression on the clean cases.

**Salt→sodium conversion** (see `app/nutrition.py`) is applied
automatically during parsing for EU-language labels that report salt in
grams rather than sodium directly — verified end-to-end via real HTTP
requests to the running endpoint (1.2g salt correctly became 480mg
sodium in the returned draft).

**Honesty about language coverage**: Danish, German, and English keyword
dictionaries are verified against real OCR output as described above.
Swedish, Finnish, Norwegian, Spanish, Slovak, and Czech dictionaries use
standard EU nutrition-label vocabulary (consistent under EU FIC labeling
regulation) but have **not** been run through real OCR here — treat them
as a reasonable starting point, not verified, until tested against real
labels in those languages. If you pick up products in any of those
languages, send photos and we can validate/fix the same way we did for
the first three.

**Known limitation, not yet solved**: `per_100g_confirmed` only checks
whether a "per 100g" marker phrase was found in the recognized text — if
a label reports values per-serving instead (and doesn't clearly say so
in a way our marker check catches), we do NOT attempt to convert to
per-100g. The client should surface `per_100g_confirmed: false` as a
visible warning prompting the user to double-check serving size
themselves, since we don't have serving-size-aware recalculation yet.

**Docker note**: Tesseract itself (`tesseract-ocr` + 8 language packs)
is installed via `apt-get` in the `Dockerfile`, since `pytesseract` is
just a Python wrapper around the binary.

## Every core router is now built

items, recipes, logs, meal_plans, goals, and user_profile are all
implemented and tested end-to-end against a real Postgres instance —
this covers every table in the design doc's schema. Next steps are less
about new CRUD surface area and more about: barcode/OCR/USDA integration,
auth hardening for real deployment, and actually standing this up on the
Pi over Tailscale.

## Making schema changes going forward

1. Edit the relevant model(s) in `app/models.py`
2. `poetry run alembic revision --autogenerate -m "describe the change"`
3. **Read the generated migration file** in `alembic/versions/` before
   applying — autogenerate is good but not infallible (e.g. it won't
   always detect a column rename correctly, may see it as drop+add)
4. `poetry run alembic upgrade head`

## What's deliberately NOT here yet

- Barcode/OCR/USDA integration code — dependencies are stubbed in
  `pyproject.toml` but commented out until we build those features
- Seed data for `physiological_guidelines` — the table exists, needs
  populating with actual reference values when we get to that feature
- Deployment to the actual Pi — this has been built and tested in a
  sandboxed dev environment; next step is moving it there over Tailscale