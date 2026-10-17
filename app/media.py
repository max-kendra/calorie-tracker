import logging
import uuid
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageOps

from app.config import settings

logger = logging.getLogger(__name__)

MAX_EDGE_PX = 1200
JPEG_QUALITY = 82


def save_compressed_jpeg(image_bytes: bytes) -> str:
    """Saves an uploaded photo under settings.media_dir as a downscaled
    JPEG and returns its relative path ("media/<uuid>.jpg"). Falls back
    to storing the original bytes if the upload can't be decoded or the
    recompressed version isn't actually smaller."""
    data = image_bytes
    try:
        with Image.open(BytesIO(image_bytes)) as img:
            img = ImageOps.exif_transpose(img)
            if img.mode != "RGB":
                img = img.convert("RGB")
            img.thumbnail((MAX_EDGE_PX, MAX_EDGE_PX), Image.LANCZOS)
            out = BytesIO()
            img.save(out, format="JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True)
            if out.tell() < len(image_bytes):
                data = out.getvalue()
    except Exception:
        logger.warning("Could not recompress uploaded image, storing original", exc_info=True)

    filename = f"{uuid.uuid4().hex}.jpg"
    media_dir = Path(settings.media_dir)
    media_dir.mkdir(parents=True, exist_ok=True)
    (media_dir / filename).write_bytes(data)
    return f"{settings.media_dir}/{filename}"