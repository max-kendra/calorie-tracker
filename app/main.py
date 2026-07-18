import logging
import os

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.config import settings
from app.routers import goals, items, logs, meal_plans, recipes, usda, user_profile

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
    description="Self-hosted meal tracker, recipe manager, and meal planner backend.",
    version="0.1.0",
)

app.include_router(items.router)
app.include_router(recipes.router)
app.include_router(logs.router)
app.include_router(meal_plans.router)
app.include_router(goals.router)
app.include_router(user_profile.router)
app.include_router(usda.router)

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