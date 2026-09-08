# ---- Stage 1: build the web frontend ----
#
# Kept in the SAME Dockerfile/image as the backend rather than a
# separate Dockerfile/service (see design discussion: "would it make
# more sense to have separate docker files for these... but we only
# usually ever restart docker if we changed the backend anyway"). A
# second service would mean a second build context, a second deploy
# step, and - since the whole point of serving the built frontend from
# FastAPI's own StaticFiles mount was to keep everything on one origin
# (see web/README.md) - either a reverse proxy or careful port/path
# coordination to keep that true. None of that buys anything for a
# single-user app on one Pi with one person deploying it.
#
# The caching concern that split would have solved is already solved
# here by ordinary Docker layer caching: this stage's own COPY (below)
# only invalidates when web/package.json or web/src actually change,
# completely independent of the poetry/apt layers in Stage 2 - a
# backend-only change skips re-running `npm install`/`npm run build`
# entirely (this stage's layers are reused from cache), and a
# frontend-only change equally skips re-running poetry install. One
# `docker compose up -d --build` either way, same as always; Docker
# just doesn't redo work neither change actually affected.
FROM node:20-slim AS web-builder
WORKDIR /web

# Dependency manifests first, same reasoning as the poetry COPY below -
# `npm install` only reruns when these actually change.
COPY web/package.json web/package-lock.json* ./
RUN npm install

# Now the actual source, in a separate layer so editing src/ doesn't
# bust the npm install cache above.
COPY web/ ./
RUN npm run build
# Output lands in /web/dist - copied into the final image below.

# ---- Stage 2: the backend image (also serves the built frontend) ----
FROM python:3.11-slim

WORKDIR /app

# pyzbar is a Python wrapper around libzbar -- the actual barcode-decoding
# C library needs to be present at the OS level, not just pip-installed.
# OCR (app/ocr.py) runs on EasyOCR now instead of Tesseract -- EasyOCR is
# pure Python + PyTorch, so unlike pytesseract it does NOT need a system
# binary/language packs installed via apt (the tesseract-ocr-* packages
# that used to be here are gone). EasyOCR downloads its model weights on
# first run -- see docker-compose.yml's easyocr_models volume mount,
# which persists that download across rebuilds/recreates so it only
# happens once per Pi, not once per deploy. Still needs outbound network
# access the very first time (or bake the weights into the image ahead
# of time if this needs to run fully offline from day one).
# build-essential + cmake: zxing-cpp has no prebuilt wheel for ARM64
# (the Pi's architecture), so pip has to compile it from source, which
# needs a C/C++ compiler and cmake. Not needed on x86_64 where a
# prebuilt wheel exists, but harmless to always include for consistency
# across architectures. This does make the image noticeably larger and
# the build slower -- acceptable tradeoff for now, revisit with a
# multi-stage build (build tools in a builder stage, not the final
# image) if image size becomes a real problem.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libzbar0 \
    build-essential \
    cmake \
    && rm -rf /var/lib/apt/lists/*

# Install Poetry
RUN pip install --no-cache-dir poetry==1.8.3

# Copy dependency files first so Docker can cache this layer -- rebuilds
# only re-install deps when pyproject.toml/poetry.lock actually change,
# not on every code edit.
COPY pyproject.toml poetry.lock* ./

# --no-root: don't try to install this project itself as a package (it's
# not structured as one -- see pyproject.toml's package-mode = false)
# --only main: skip dev dependencies (pytest etc) in the production image
# Clearing poetry's package cache in this SAME RUN command matters on a
# space-constrained disk (e.g. a small SD card): Docker layers are
# append-only, so deleting cached files in a LATER step doesn't shrink a
# layer that already wrote those bytes in an earlier one. With torch/
# OpenCV-sized wheels passing through that cache, leaving it in place
# would otherwise permanently cost several hundred MB to ~1GB of image
# size for no benefit -- the cache only ever speeds up a second install
# inside the SAME container, which never happens here (this container's
# filesystem is thrown away and rebuilt from scratch every time
# pyproject.toml/poetry.lock change).
RUN poetry config virtualenvs.create false \
    && poetry install --no-root --only main --no-interaction --no-ansi \
    && poetry cache clear --all -n pypi \
    && rm -rf /root/.cache/pypoetry /root/.cache/pip

# Now copy the actual application code
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini ./

# Built frontend from Stage 1 - see web/README.md for the StaticFiles
# mount (and matching /api route-prefixing) app/main.py needs for this
# to actually get served. A plain COPY, not a volume mount: this is a
# build artifact baked into the image, not runtime user data, so it
# doesn't belong in docker-compose.yml's volumes: alongside media/
# easyocr_models/ocr_metrics (which ARE user data that needs to
# survive a rebuild).
COPY --from=web-builder /web/dist ./web/dist

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]