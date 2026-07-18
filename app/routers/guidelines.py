from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.auth import require_api_key
from app.database import get_db
from app.models import PhysiologicalGuideline
from app.schemas import PhysiologicalGuidelineOut

router = APIRouter(
    prefix="/guidelines",
    tags=["guidelines"],
    dependencies=[Depends(require_api_key)],
)


@router.get("", response_model=list[PhysiologicalGuidelineOut])
def list_guidelines(db: Session = Depends(get_db)):
    """
    Read-only -- these rows are seeded once via migration
    (824c17bcbba4_seed_physiological_guidelines) and not otherwise
    written to by the app, so there's no create/update/delete here.

    Currently used by the Home screen's threshold card (sodium/added
    sugar/saturated fat) to both compute the weekly ceiling and explain
    it via `basis`, rather than hardcoding those same population-level
    numbers a second time on the client.
    """
    return db.query(PhysiologicalGuideline).order_by(PhysiologicalGuideline.name).all()