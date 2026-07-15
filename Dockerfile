FROM python:3.11-slim

WORKDIR /app

# pyzbar is a Python wrapper around libzbar -- the actual barcode-decoding
# C library needs to be present at the OS level, not just pip-installed.
# OCR (app/ocr.py) runs on EasyOCR now instead of Tesseract -- EasyOCR is
# pure Python + PyTorch, so unlike pytesseract it does NOT need a system
# binary/language packs installed via apt (the tesseract-ocr-* packages
# that used to be here are gone). EasyOCR does download its model weights
# on first run instead, so the container needs outbound network access
# the first time it starts (or bake the weights into the image ahead of
# time if this needs to run fully offline).
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
RUN poetry config virtualenvs.create false \
    && poetry install --no-root --only main --no-interaction --no-ansi

# Now copy the actual application code
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini ./

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]