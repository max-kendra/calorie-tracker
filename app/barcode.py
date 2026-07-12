"""
Barcode decoding from an uploaded image.

Tries pyzbar first, falls back to zxing-cpp if that finds nothing.
This ordering is based on empirical testing (see README) -- pyzbar
handled rotated barcodes better (correctly decoded up to ~40 degrees of
rotation where zxing-cpp gave up past ~25-30 degrees), while zxing-cpp
succeeded on a low-contrast/noisy image that pyzbar completely missed.
Neither is strictly better -- trying both covers more real-world photo
conditions than either alone.
"""

from dataclasses import dataclass
from io import BytesIO

import pyzbar.pyzbar as pyzbar
import zxingcpp
from PIL import Image


@dataclass
class BarcodeDecodeResult:
    barcode: str
    format: str
    decoder_used: str  # 'pyzbar' | 'zxing-cpp'


def decode_barcode_from_image_bytes(image_bytes: bytes) -> BarcodeDecodeResult | None:
    """
    Returns the first barcode found, or None if neither decoder finds
    anything (caller should fall back to manual barcode entry in that case
    -- some real-world photos genuinely aren't decodable, e.g. very
    aggressive blur/rotation/low-light combinations).
    """
    image = Image.open(BytesIO(image_bytes))

    pyzbar_results = pyzbar.decode(image)
    if pyzbar_results:
        r = pyzbar_results[0]
        return BarcodeDecodeResult(
            barcode=r.data.decode("utf-8"), format=r.type, decoder_used="pyzbar"
        )

    zxing_results = zxingcpp.read_barcodes(image)
    if zxing_results:
        r = zxing_results[0]
        return BarcodeDecodeResult(
            barcode=r.text, format=str(r.format), decoder_used="zxing-cpp"
        )

    return None
