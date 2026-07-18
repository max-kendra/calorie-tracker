from typing import Optional

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.auth import require_api_key
from app.config import settings
from app.schemas import UsdaFoodDetailOut, UsdaFoodSummaryOut, UsdaMacros
from app.usda import UsdaClient

router = APIRouter(
    prefix="/usda",
    tags=["usda"],
    dependencies=[Depends(require_api_key)],
)


def _get_client() -> UsdaClient:
    return UsdaClient(api_key=settings.usda_api_key)


def _usda_error_detail(e: Exception) -> str:
    """Distinguishes a 429 (rate limit) from other failures -- the
    default usda_api_key is the public DEMO_KEY, which is limited to
    30 requests/hour and 50/day AND SHARED across every DEMO_KEY user
    globally, not just this app -- trivially easy to exceed, and the
    resulting error otherwise looks identical to a genuine bug ("500's
    and 502's with usda", reported without an obvious cause). Get a
    free personal key at https://fdc.nal.usda.gov/api-key-signup and
    set USDA_API_KEY -- personal keys get a much higher limit (1000/
    hour) and aren't shared with other users."""
    if isinstance(e, httpx.HTTPStatusError) and e.response.status_code == 429:
        return (
            "USDA FoodData Central rate limit exceeded. If you're using the default "
            "DEMO_KEY, it's limited to 30 requests/hour and shared globally with every "
            "other DEMO_KEY user -- get a free personal key at "
            "https://fdc.nal.usda.gov/api-key-signup and set it as USDA_API_KEY."
        )
    return f"USDA FoodData Central request failed: {e}"


@router.get("/search", response_model=list[UsdaFoodSummaryOut])
def search_usda(
    query: str = Query(..., min_length=1),
    data_type: Optional[str] = Query(
        "Foundation,SR Legacy",
        description="Comma-separated FDC data types. Defaults to Foundation+SR Legacy "
        "(lab-analyzed, stable -- best for raw/whole ingredients). Pass 'Branded' "
        "explicitly if searching packaged products by name.",
    ),
    page_size: int = Query(10, le=50),
):
    """
    Search USDA FoodData Central. Results are NOT written to our DB --
    this is purely a lookup for the client to show a result list. Picking
    a result and creating an item from it happens via the normal
    POST /items call, with the user reviewing/editing the pre-filled
    macros first (same pattern as OCR and barcode scanning).
    """
    client = _get_client()
    data_types = [d.strip() for d in data_type.split(",")] if data_type else None

    try:
        results = client.search(query, data_types=data_types, page_size=page_size)
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=_usda_error_detail(e))

    return [
        UsdaFoodSummaryOut(
            fdc_id=r.fdc_id,
            description=r.description,
            data_type=r.data_type,
            brand_owner=r.brand_owner,
            macros=UsdaMacros(**r.macros),
        )
        for r in results
    ]


@router.get("/food/{fdc_id}", response_model=UsdaFoodDetailOut)
def get_usda_food(fdc_id: int):
    """
    Full detail for one food by FDC ID -- used once the user picks a
    search result, to get the complete normalized macro set for
    pre-filling the Add Item review form.
    """
    client = _get_client()

    try:
        result = client.get_food(fdc_id)
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=_usda_error_detail(e))

    return UsdaFoodDetailOut(
        fdc_id=result.fdc_id,
        description=result.description,
        data_type=result.data_type,
        macros=UsdaMacros(**result.macros),
    )