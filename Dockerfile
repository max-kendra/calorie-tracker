FROM python:3.11-slim

WORKDIR /app

# pyzbar is a Python wrapper around libzbar - the actual barcode-decoding
# C library needs to be present at the OS level, not just pip-installed.
#
# OCR (app/ocr.py) runs on Tesseract again (via pytesseract) on this
# branch - see app/ocr.py's module docstring for the EasyOCR/Tesseract
# back-and-forth story. Unlike EasyOCR (pure Python + PyTorch, no system
# packages needed), Tesseract needs its own binary AND one traineddata
# package PER LANGUAGE, installed via apt below - these must match
# app/ocr.py's _TESSERACT_LANGS exactly, or a language listed there will
# fail at OCR time with no matching data installed. All of this is baked
# into the image at BUILD time (unlike EasyOCR's model weights, which
# downloaded at RUNTIME on first use) - no volume mount needed for this,
# no first-request download/timeout risk, and it works fully offline
# from the moment the container starts.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libzbar0 \
    build-essential \
    cmake \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-dan \
    tesseract-ocr-deu \
    tesseract-ocr-swe \
    tesseract-ocr-fin \
    tesseract-ocr-nor \
    tesseract-ocr-spa \
    tesseract-ocr-slk \
    tesseract-ocr-ces \
    tesseract-ocr-pol \
    && rm -rf /var/lib/apt/lists/*

# Install Poetry
RUN pip install --no-cache-dir poetry==1.8.3

# Copy dependency files first so Docker can cache this layer - rebuilds
# only re-install deps when pyproject.toml/poetry.lock actually change,
# not on every code edit.
COPY pyproject.toml poetry.lock* ./

# --no-root: don't try to install this project itself as a package (it's
# not structured as one - see pyproject.toml's package-mode = false)
# --only main: skip dev dependencies (pytest etc) in the production image
# Clearing poetry's package cache in this SAME RUN command matters on a
# space-constrained disk (e.g. a small SD card): Docker layers are
# append-only, so deleting cached files in a LATER step doesn't shrink a
# layer that already wrote those bytes in an earlier one. Less critical
# on THIS branch than the EasyOCR one (pytesseract/pillow are small -
# no torch/OpenCV-sized wheels passing through here), but still cheap
# and correct to keep doing regardless.
RUN poetry config virtualenvs.create false \
    && poetry install --no-root --only main --no-interaction --no-ansi \
    && poetry cache clear --all -n pypi \
    && rm -rf /root/.cache/pypoetry /root/.cache/pip

# Now copy the actual application code
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini ./

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]