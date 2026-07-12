from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.auth import require_api_key
from app.database import get_db
from app.models import UserProfile
from app.schemas import UserProfileOut, UserProfileUpdate

router = APIRouter(
    prefix="/profile",
    tags=["profile"],
    dependencies=[Depends(require_api_key)],
)


def _get_or_create_profile(db: Session) -> UserProfile:
    """
    Single-user app -- there's exactly one profile row, created on first
    access if it doesn't exist yet, rather than requiring an explicit
    create step.
    """
    profile = db.query(UserProfile).first()
    if not profile:
        profile = UserProfile()
        db.add(profile)
        db.commit()
        db.refresh(profile)
    return profile


@router.get("", response_model=UserProfileOut)
def get_profile(db: Session = Depends(get_db)):
    return _get_or_create_profile(db)


@router.patch("", response_model=UserProfileOut)
def update_profile(payload: UserProfileUpdate, db: Session = Depends(get_db)):
    profile = _get_or_create_profile(db)

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(profile, field, value)

    db.commit()
    db.refresh(profile)
    return profile
