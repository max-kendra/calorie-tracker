"""
Barcode decoding from an uploaded image.

Tries pyzbar first, falls back to zxing-cpp if that finds nothing.
This ordering is based on empirical testing (see README) -- pyzbar
handled rotated barcodes better (correctly decoded up to ~40 degrees of
rotation where zxing-cpp gave up past ~25-30 degrees), while zxing-cpp
succeeded on a low-contrast/noisy image that pyzbar completely missed.
Neither is strictly better -- trying both covers more real-world photo
conditions than either alone.

IMPORTANT -- tested against 6 real photos of actual product packaging:
2 decoded correctly, 2 failed safely (returned nothing), and 2 returned
a WRONG barcode value with no indication anything was off. One of those
wrong values happened to pass its own EAN-13 checksum, meaning checksum
validation alone CANNOT be relied on to catch misdecodes -- it's a cheap
first filter, not a guarantee. The client MUST show the decoded barcode
number to the user for visual confirmation against the physical package
before proceeding to match/create an item -- silently trusting a decoded
value is not safe based on real-world testing.
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
    checksum_valid: bool | None  # None if not a checksummed format we check


def _ean13_checksum_valid(barcode: str) -> bool:
    if len(barcode) != 13 or not barcode.isdigit():
        return False
    digits = [int(d) for d in barcode[:12]]
    total = sum(d * (3 if i % 2 else 1) for i, d in enumerate(digits))
    expected = (10 - total % 10) % 10
    return expected == int(barcode[-1])


def _upc_a_checksum_valid(barcode: str) -> bool:
    if len(barcode) != 12 or not barcode.isdigit():
        return False
    digits = [int(d) for d in barcode[:11]]
    total = sum(d * (3 if i % 2 == 0 else 1) for i, d in enumerate(digits))
    expected = (10 - total % 10) % 10
    return expected == int(barcode[-1])


def _check_checksum(barcode: str) -> bool | None:
    """Returns True/False if this looks like a format we can checksum
    (EAN-13, UPC-A), or None if it's some other format/length we don't
    validate. A False here means definitely garbled -- reject outright.
    A True here is NOT proof of correctness (see module docstring)."""
    if len(barcode) == 13:
        return _ean13_checksum_valid(barcode)
    if len(barcode) == 12:
        return _upc_a_checksum_valid(barcode)
    return None


def _decode_with(results, decoder_name: str) -> BarcodeDecodeResult | None:
    for barcode, fmt in results:
        checksum_valid = _check_checksum(barcode)
        if checksum_valid is False:
            # Definitely garbled for a format we can check -- skip this
            # result rather than returning known-bad data.
            continue
        return BarcodeDecodeResult(
            barcode=barcode, format=fmt, decoder_used=decoder_name, checksum_valid=checksum_valid
        )
    return None


def decode_barcode_from_image_bytes(image_bytes: bytes) -> BarcodeDecodeResult | None:
    """
    Returns the first barcode found with a passing (or unchecked) checksum,
    or None if nothing usable was found. Caller should fall back to manual
    barcode entry in that case, AND regardless of whether a value is
    returned, the client should show `barcode` to the user for visual
    confirmation before using it -- see module docstring for why.
    """
    image = Image.open(BytesIO(image_bytes))

    pyzbar_raw = pyzbar.decode(image)
    pyzbar_pairs = [(r.data.decode("utf-8"), r.type) for r in pyzbar_raw]
    result = _decode_with(pyzbar_pairs, "pyzbar")
    if result:
        return result

    zxing_raw = zxingcpp.read_barcodes(image)
    zxing_pairs = [(r.text, str(r.format)) for r in zxing_raw]
    result = _decode_with(zxing_pairs, "zxing-cpp")
    if result:
        return result

    return None