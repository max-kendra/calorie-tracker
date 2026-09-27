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
6. Run the API: `poetry run uvicorn app.main:app -reload -host 0.0.0.0 -port 8000`
7. Visit `http://localhost:8000/docs` for interactive API docs (Swagger UI)
   — click "Authorize" and enter your API key to try authenticated endpoints.

## Deployment

Images are built on a dev machine and shipped to the Pi as a file - the Pi never builds anything itself.

### Building & shipping

From the repo root (with `calorie-tracker` and the nested `calorie-tracker-web` both up to date):

```powershell
.\build-and-deploy.ps1
```

This builds the image for `linux/arm64`, ships it to the Pi over `scp`, loads it into the Pi's Docker, syncs `docker-compose.yml`, and restarts the `api` container.

Migrations don't run automatically. After a deploy that includes one:

```bash
ssh adytum.local "cd /mnt/data/calorie-tracker && docker compose exec api alembic upgrade head"
```

Check `alembic current` (applied to this database) vs. `alembic heads` (what exists in the image) if something seems off.

### What the Pi needs

Just two files, under `/mnt/data/calorie-tracker/`:

- **`docker-compose.yml`** - synced fresh on every deploy.
- **`.env`** - manual, permanent, never touched by the deploy script.

```
/mnt/data/calorie-tracker/
├── docker-compose.yml
├── .env
├── deploy-tmp/          - scratch space for the image transfer, empty
│                           between deploys
├── media/                - item/recipe photos
├── ocr_metrics/           - OCR accuracy history
└── easyocr_models/        - downloaded model weights, re-fetched
                             automatically if missing
```

`/mnt/data/db-backups/` and `/mnt/data/media-backups/` are siblings of this folder, not nested inside it. Postgres dumps hourly (14 days / 4 weeks / 6 months retention); `media/` archives daily (30-day flat retention).