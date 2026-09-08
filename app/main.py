import logging
import os
import threading

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app import ocr
from app.config import settings
from app.routers import goals, guidelines, items, logs, recipes, usda, user_profile

# Without this, nothing below INFO level (including the new OCR-result
# logging added to scan_label -- see app/routers/items.py) ever actually
# appears in `docker compose logs`. Python's logging module only has a
# "handler of last resort" that writes WARNING+ to stderr when nothing
# else is configured -- that's why existing logger.exception() calls
# (ERROR level) were visible but a plain logger.info() call would have
# been silently dropped. This makes INFO the effective floor everywhere
# in the app, not just here.
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(
    title="Meal Tracker API",
    description="Self-hosted meal tracker and recipe manager backend.",
    version="0.1.0",
)

app.include_router(items.router)
app.include_router(recipes.router)
app.include_router(logs.router)
app.include_router(goals.router)
app.include_router(guidelines.router)
app.include_router(user_profile.router)
app.include_router(usda.router)

# Same routers, mounted a SECOND time under /api - not a redirect, two
# real sets of routes sharing the same handler functions (FastAPI
# supports including one APIRouter more than once with different
# prefixes). The Android app keeps hitting the plain paths above
# exactly as it always has; the web frontend (web/src/api/client.ts)
# is written to call everything under /api/... so that in dev, Vite's
# proxy (web/vite.config.ts) can forward just that one prefix without
# needing CORS set up here, and in production the built static files
# and the API end up on the same origin with no environment-specific
# base URL needed on the frontend at all - see web/README.md for the
# full reasoning.
app.include_router(items.router, prefix="/api")
app.include_router(recipes.router, prefix="/api")
app.include_router(logs.router, prefix="/api")
app.include_router(goals.router, prefix="/api")
app.include_router(guidelines.router, prefix="/api")
app.include_router(user_profile.router, prefix="/api")
app.include_router(usda.router, prefix="/api")

# Serves product photos saved by POST /items/scan-product-photo back out
# at /media/<filename> -- e.g. an Item's image_path of "media/abc123.jpg"
# (as returned by that endpoint) is reachable at GET /media/abc123.jpg.
# Created here (not left to be created lazily on first upload) so the
# mount doesn't fail if the directory doesn't exist yet on a fresh
# deploy.
os.makedirs(settings.media_dir, exist_ok=True)
app.mount("/media", StaticFiles(directory=settings.media_dir), name="media")


@app.get("/health", tags=["meta"])
def health_check():
    """Unauthenticated — just confirms the API process is up."""
    return {"status": "ok"}


@app.on_event("startup")
def _warm_up_ocr():
    """
    Forces EasyOCR's Reader construction (including its one-time model
    download on a fresh container/volume) to happen NOW, at startup,
    instead of on whichever live request happens to be first to touch
    OCR -- see app/ocr.py's warm_up() docstring for the full story. That
    was the actual bug behind "we get a timeout right after the
    download log line, but the process runs anyway" (design
    discussion) -- the slow part only happens once per container
    lifetime, and it was always some real user's first scan attempt
    that unluckily landed on it.

    Runs in a background thread rather than blocking startup -- this
    can take 20-40s on a fresh container (model download + torch/
    dataloader init, per real deploy logs), and blocking here would
    delay every other endpoint (items, logs, USDA, etc.) from becoming
    available for that whole time too, not just OCR. The only remaining
    edge case is a user trying to scan something within the first
    ~30s of a fresh deploy, before this finishes -- much narrower than
    before, where EVERY container's first scan attempt (whenever that
    happened to be) hit the slow path.
    """
    threading.Thread(target=ocr.warm_up, daemon=True).start()


# Serves the built web frontend (web/dist, produced by the Dockerfile's
# web-builder stage - see that file and web/README.md) at the same
# origin and port as the API itself. MUST be registered last: Starlette
# resolves routes in registration order, and a mount at "/" is a
# catch-all that would otherwise shadow /health, /media, and every
# /api/... route registered above it if it came first.
#
# Guarded rather than assumed present: the Docker image always has
# web/dist (the multi-stage build produces it before this ever runs -
# see Dockerfile), but running the backend directly via `poetry run
# uvicorn` for local API-only development won't, unless `npm run
# build` has been run by hand in web/ first. Skipping the mount in
# that case means local backend dev still works with a plain log line
# instead of a crash on startup; the API itself (including the /api/...
# duplicate routes above) is unaffected either way.
_web_dist = "web/dist"
if os.path.isdir(_web_dist):
    app.mount("/", StaticFiles(directory=_web_dist, html=True), name="web")
else:
    logging.getLogger(__name__).info(
        "%s not found - web frontend not mounted (this is expected for local "
        "backend-only dev; the Docker image always has it built - see Dockerfile)",
        _web_dist,
    )