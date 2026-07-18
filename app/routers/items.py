import logging
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile, status
from sqlalchemy import or_
from sqlalchemy.orm import Session

from app.auth import require_api_key
from app.barcode import decode_barcode_from_image_bytes
from app.config import settings
from app.database import get_db
from app.models import Item, ServingSize
from app.ocr import extract_label_from_image
from app.ocr_metrics import record_ocr_scan
from app.schemas import (
    BarcodeScanResult,
    ItemCreate,
    ItemOut,
    ItemUpdate,
    ItemType,
    OcrMacros,
    OcrScanResult,
    ProductPhotoScanResult,
)

router = APIRouter(
    prefix="/items",
    tags=["items"],
    dependencies=[Depends(require_api_key)],
)

logger = logging.getLogger(__name__)


@router.post("", response_model=ItemOut, status_code=status.HTTP_201_CREATED)
def create_item(payload: ItemCreate, db: Session = Depends(get_db)):
    if payload.barcode:
        existing = db.query(Item).filter(Item.barcode == payload.barcode).first()
        if existing:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"An item with barcode {payload.barcode} already exists (item_id={existing.item_id})",
            )

    item = Item(**payload.model_dump())
    db.add(item)
    db.commit()
    db.refresh(item)
    return item


@router.get("/{item_id}", response_model=ItemOut)
def get_item(item_id: int, db: Session = Depends(get_db)):
    item = db.query(Item).filter(Item.item_id == item_id).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")
    return item


@router.post("/scan-barcode", response_model=BarcodeScanResult)
async def scan_barcode(image: UploadFile = File(...), db: Session = Depends(get_db)):
    """
    Upload a photo of a barcode - tries pyzbar first, falls back to
    zxing-cpp if that finds nothing (see app/barcode.py for why both).
    If the decoded barcode matches an existing item, that item is
    returned. If the barcode decodes but doesn't match anything, `item`
    is null and the client should pre-fill the Add Item form with the
    decoded barcode. If nothing could be decoded at all, both `barcode`
    and `item` are null - client should offer manual barcode entry.
    """
    image_bytes = await image.read()

    result = decode_barcode_from_image_bytes(image_bytes)
    if result is None:
        return BarcodeScanResult(barcode=None, decoder_used=None, checksum_valid=None, item=None)

    item = db.query(Item).filter(Item.barcode == result.barcode).first()

    return BarcodeScanResult(
        barcode=result.barcode,
        decoder_used=result.decoder_used,
        checksum_valid=result.checksum_valid,
        item=item,
    )


@router.post("/scan-product-photo", response_model=ProductPhotoScanResult)
async def scan_product_photo(image: UploadFile = File(...)):
    """
    Second step of the Add Item flow (between barcode scan and nutrition
    label scan) - upload a (client-cropped) photo of the product
    package itself, saved to disk under `settings.media_dir` and
    returned as `image_path` for the client to carry through into the
    eventual POST /items call, so the photo gets attached to the item
    (e.g. shown later in My Foods).

    Purely visual now - no OCR runs against this endpoint at all. It
    used to also run OCR here for a best-effort name/brand guess
    (guess_name_and_brand), but that guessing was unreliable in general
    and especially hopeless for non-Latin packaging (e.g. Japanese/
    Korean/Chinese), since EasyOCR here only knows Latin-script
    languages. The Add Item flow now asks the user for name/brand
    directly instead (see design discussion) - the client has never
    read guessed_name/guessed_brand since that redesign, making this
    endpoint's OCR pass pure wasted work. Worse: it was also the source
    of confusing OCR errors showing up in the logs for a step that
    doesn't OCR anything from the client's point of view (see design
    discussion: "I wasn't even OCR'ing anything... I thought we took the
    OCR out from there?") - removing it fixes both the waste and the
    confusion at once.

    Like scan-label, this NEVER writes to our DB directly - saving the
    image file to disk is the one exception (needed so we have something
    for `image_path` to point at), but no Item row is touched.
    """
    image_bytes = await image.read()

    # Save first, with a random filename - collisions are practically
    # impossible with uuid4, and using the original client filename
    # would risk path-traversal or overwrite issues if we ever pass it
    # through unsanitized. Always .jpg since the client always sends a
    # JPEG-compressed crop (see Android AddItemScreen.kt: easycrop's
    # `.compress(Bitmap.CompressFormat.JPEG, ...)`), regardless of what
    # format the original photo was in before cropping.
    filename = f"{uuid.uuid4().hex}.jpg"
    media_dir = Path(settings.media_dir)
    media_dir.mkdir(parents=True, exist_ok=True)
    (media_dir / filename).write_bytes(image_bytes)
    # Relative path matching how app/main.py mounts StaticFiles at
    # /media - e.g. "media/<filename>" is reachable at GET /media/<filename>.
    image_path = f"{settings.media_dir}/{filename}"

    return ProductPhotoScanResult(
        image_path=image_path,
        raw_text="",
        guessed_name=None,
        guessed_brand=None,
    )


@router.post("/scan-label", response_model=OcrScanResult)
async def scan_label(image: UploadFile = File(...)):
    """
    Upload a photo of a nutrition label - OCR extracts serving size +
    macros, best-effort, in whichever of our 9 supported languages it
    detects. NEVER writes to our DB - returns a draft for the client to
    pre-fill the Add Item form, which the user reviews/corrects before
    actually saving via POST /items. See app/ocr.py for what's been
    tested and what hasn't (Danish/German/English verified against real
    Tesseract OCR, not yet re-verified against EasyOCR output since the
    engine switch; other languages use standard EU label vocabulary but
    aren't yet verified against real OCR output of either engine).
    """
    image_bytes = await image.read()

    # Same reasoning as scan_product_photo's try/except - OCR failing
    # (EasyOCR/PyTorch issues, an unreadable/corrupted image, memory,
    # etc.) should degrade to an empty draft the user fills in manually,
    # not 500 the whole request. This endpoint never had that handling
    # at all (unlike scan_product_photo, which was fixed earlier) -
    # given this is the actual OCR-heavy step (reading a dense nutrition
    # table, not just guessing a product name), it's the more likely
    # place for OCR failures to actually surface in practice.
    try:
        with record_ocr_scan("tesseract", "scan_label", len(image_bytes)) as metrics:
            result = extract_label_from_image(image_bytes)
            metrics["detected_language"] = result.detected_language
            metrics["per_100g_confirmed"] = result.per_100g_confirmed
            metrics["macros_found"] = bool(result.macros)
            metrics["raw_text_length"] = len(result.raw_text)
        # Logged on EVERY call now, not just a crash - a clean label
        # that still fails to parse (no exception, just insufficient/
        # garbled recognized text or a language that didn't hit the
        # keyword-match threshold) previously left zero trace anywhere,
        # making "why did this fail" undebuggable. This shows exactly
        # what EasyOCR recognized and what we made of it.
        logger.info(
            "scan_label OCR result: detected_language=%s per_100g_confirmed=%s macros=%s raw_text=%r",
            result.detected_language,
            result.per_100g_confirmed,
            result.macros,
            result.raw_text,
        )
        return OcrScanResult(
            raw_text=result.raw_text,
            detected_language=result.detected_language,
            per_100g_confirmed=result.per_100g_confirmed,
            macros=OcrMacros(**result.macros),
        )
    except Exception:
        logger.exception("OCR failed during scan_label")
        return OcrScanResult(
            raw_text="",
            detected_language=None,
            per_100g_confirmed=False,
            macros=OcrMacros(),
        )


@router.get("/barcode/{barcode}", response_model=ItemOut)
def get_item_by_barcode(barcode: str, db: Session = Depends(get_db)):
    """Used by the barcode-scan add flow — look up an existing item before
    falling through to 'not found, create a new one'."""
    item = db.query(Item).filter(Item.barcode == barcode).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No item with that barcode")
    return item


@router.get("", response_model=list[ItemOut])
def list_items(
    q: Optional[str] = Query(None, description="Search by name or brand"),
    type: Optional[ItemType] = Query(None, description="Filter by 'product' or 'ingredient'"),
    limit: int = Query(50, le=200),
    offset: int = Query(0),
    db: Session = Depends(get_db),
):
    """Backs the My Foods search/list — filterable by type, matches the
    Product/Ingredient tabs in the design doc."""
    query = db.query(Item)

    if q:
        like = f"%{q}%"
        query = query.filter(or_(Item.name.ilike(like), Item.brand.ilike(like)))

    if type:
        query = query.filter(Item.type == type)

    return query.order_by(Item.updated_at.desc()).offset(offset).limit(limit).all()


@router.patch("/{item_id}", response_model=ItemOut)
def update_item(item_id: int, payload: ItemUpdate, db: Session = Depends(get_db)):
    """
    Partial update — e.g. correcting kcal_100g after a typo. Any recipe
    referencing this item picks up the change automatically next time its
    totals are computed (recipe_ingredients stores item_id + quantity, never
    a copy of the macros). Past `logs` are unaffected — they snapshot
    macros at log time and stay frozen (see design doc).
    """
    item = db.query(Item).filter(Item.item_id == item_id).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")

    updates = payload.model_dump(exclude_unset=True)

    if "barcode" in updates and updates["barcode"]:
        existing = (
            db.query(Item)
            .filter(Item.barcode == updates["barcode"], Item.item_id != item_id)
            .first()
        )
        if existing:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"Barcode already used by item_id={existing.item_id}",
            )

    for field, value in updates.items():
        setattr(item, field, value)

    db.commit()
    db.refresh(item)
    return item


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_item(item_id: int, db: Session = Depends(get_db)):
    item = db.query(Item).filter(Item.item_id == item_id).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")

    # Note: FK constraints (no ON DELETE CASCADE configured on
    # recipe_ingredients/logs/meal_plans --> items) will reject this if the
    # item is still referenced anywhere — that's deliberate for now, so we
    # don't silently orphan historical logs. Revisit if this becomes
    # annoying in practice.
    db.delete(item)
    db.commit()
    return None


@router.post("/{item_id}/serving-sizes", response_model=ItemOut, status_code=status.HTTP_201_CREATED)
def add_serving_size(item_id: int, name: str, weight_g: float, db: Session = Depends(get_db)):
    item = db.query(Item).filter(Item.item_id == item_id).first()
    if not item:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")

    serving = ServingSize(item_id=item_id, name=name, weight_g=weight_g)
    db.add(serving)
    db.commit()
    db.refresh(item)
    return item


@router.patch("/{item_id}/serving-sizes/{serving_id}", response_model=ItemOut)
def update_serving_size(
    item_id: int,
    serving_id: int,
    name: Optional[str] = None,
    weight_g: Optional[float] = None,
    db: Session = Depends(get_db),
):
    """E.g. correcting a serving size after realizing it was measured wrong,
    or refining an OCR-derived 'label serving'."""
    serving = (
        db.query(ServingSize)
        .filter(ServingSize.id == serving_id, ServingSize.item_id == item_id)
        .first()
    )
    if not serving:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Serving size not found")

    if name is not None:
        serving.name = name
    if weight_g is not None:
        serving.weight_g = weight_g

    db.commit()
    item = db.query(Item).filter(Item.item_id == item_id).first()
    return item


@router.delete("/{item_id}/serving-sizes/{serving_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_serving_size(item_id: int, serving_id: int, db: Session = Depends(get_db)):
    serving = (
        db.query(ServingSize)
        .filter(ServingSize.id == serving_id, ServingSize.item_id == item_id)
        .first()
    )
    if not serving:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Serving size not found")

    # Note: logs/meal_plans referencing this serving_size_id will block the
    # delete via FK constraint — same deliberate protection as item deletes.
    db.delete(serving)
    db.commit()
    return None