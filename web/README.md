# Meal Tracker - Web

A browser-based viewer/editor for the same backend the Android app
talks to. Default view is a Google-Calendar-style week grid: 7 day
columns, each split into color-coded meal sections (matching the
Android app's own breakfast/lunch/dinner/snack colors exactly), with
macro totals rolled up at the meal, day, and week level.

## What's here so far

- Week view (`src/pages/WeekView.tsx`) - fully wired to the real API,
  no mock data.
- API client with the same `X-API-Key` shared-secret auth the Android
  app uses (`src/api/client.ts`), key entered once and stored in
  `localStorage` (`src/components/ApiKeyGate.tsx`).
- TypeScript types mirroring the backend's Pydantic schemas exactly
  (`src/api/types.ts`).

## Not yet built

- Item creation (USDA search / barcode number entry / barcode image
  upload) - the backend endpoints for all three already exist
  (`POST /items`, `GET /items/barcode/{barcode}`,
  `POST /items/scan-barcode`), this just needs the UI screens.
- Editing an existing log inline from the week view (currently
  read-only display).
- Day view / single-day drill-down.

## Setup

```bash
npm install
```

Create `.env.local` if you need to point dev at a backend that isn't
on `localhost:8000`:

```
VITE_DEV_API_TARGET=http://your-pi-tailscale-address:8000
```

## Dev

```bash
npm run dev
```

Vite's dev server proxies `/api/*` to the real backend (see
`vite.config.ts`), so there's no CORS setup needed on the FastAPI side
just for local development.

## Build & deploy

```bash
npm run build
```

Produces static files in `dist/`. The simplest deployment, matching
how the Android app's backend already runs (Docker Compose on the
Pi), is to serve these directly from FastAPI rather than running a
second Node process:

```python
# in app/main.py, near the other route registrations
from fastapi.staticfiles import StaticFiles

app.mount("/", StaticFiles(directory="web/dist", html=True), name="web")
```

With that mount, the built frontend and the API are on the exact same
origin. To keep `src/api/client.ts`'s `/api` prefix working identically
in both dev and prod (rather than juggling two different base URLs),
mount the existing routers under `/api` in production too, e.g. in
`app/main.py`:

```python
app.include_router(items.router, prefix="/api")
app.include_router(recipes.router, prefix="/api")
app.include_router(logs.router, prefix="/api")
app.include_router(goals.router, prefix="/api")
app.include_router(meal_plans.router, prefix="/api")
app.include_router(usda.router, prefix="/api")
# ...and mount the static files last, so /api/* is matched first
app.mount("/", StaticFiles(directory="web/dist", html=True), name="web")
```

This is additive - the Android app keeps hitting the same unprefixed
paths it always has (`/logs`, `/goals`, ...), FastAPI just also serves
the identical routes under `/api/...` for the web client, so
`src/api/client.ts` never needs an environment-specific base URL at
all.
