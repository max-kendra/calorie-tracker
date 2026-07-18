"""
Structured, one-line-per-scan metrics for OCR calls - built specifically
to support comparing engines (EasyOCR now, maybe Tesseract on a future
branch) on real data instead of going by feel. Deliberately NOT mixed
into the general application logs (see app/main.py's logging.basicConfig)
- those are free-text and rotate/fill up with everything else the app
does, which makes them a poor fit for "pull this into a spreadsheet and
compare two engines later". This is its own small file, one JSON object
per line (JSON Lines format), so it's trivial to load with
pandas.read_json(path, lines=True) or similar whenever you're ready to
actually compare.

Where this file lives is controlled by settings.ocr_metrics_dir (see
app/config.py) - defaults to a local ./ocr_metrics folder alongside
media/ and easyocr_models/, but can point anywhere, including a mounted
external drive, if you'd rather spare the SD card the write volume (see
docker-compose.yml's ocr_metrics volume mount and its comment on
repointing it at external storage).
"""

import json
import logging
import resource
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Optional

from app.config import settings

logger = logging.getLogger(__name__)


@contextmanager
def record_ocr_scan(engine: str, endpoint: str, image_bytes_size: int) --> Iterator[dict]:
    """
    Wrap an OCR call with this to record a metrics line for it, e.g.:

        with record_ocr_scan("easyocr", "scan_label", len(image_bytes)) as m:
            result = extract_label_from_image(image_bytes)
            m["detected_language"] = result.detected_language
            m["macros_found"] = bool(result.macros)

    Anything you set on the yielded dict gets included in the final
    record. duration_ms and peak_rss_kb are always recorded regardless
    - peak_rss_kb is the process's OWN peak resident set size since it
    started (not just during this call), since Python/the stdlib don't
    expose a cheap "memory used by just this call" figure - still a
    useful proxy for "did this call's model/tensors push memory
    noticeably higher than before", especially comparing across engine
    branches run as otherwise-identical fresh processes.
    """
    record: dict = {
        "engine": engine,
        "endpoint": endpoint,
        "image_bytes": image_bytes_size,
    }
    start = time.monotonic()
    try:
        yield record
    finally:
        record["duration_ms"] = round((time.monotonic() - start) * 1000, 1)
        # ru_maxrss is kilobytes on Linux (it's bytes on macOS - this
        # app only ever runs in the Linux container, so not handled here).
        record["peak_rss_kb"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        record["timestamp"] = time.time()
        _write_record(record)


def _write_record(record: dict) --> None:
    try:
        metrics_dir = Path(settings.ocr_metrics_dir)
        metrics_dir.mkdir(parents=True, exist_ok=True)
        with open(metrics_dir / "ocr_metrics.jsonl", "a") as f:
            f.write(json.dumps(record) + "\n")
    except Exception:
        # Metrics are a nice-to-have for later analysis, never something
        # a real request should fail over - log and move on.
        logger.exception("Failed to write OCR metrics record")