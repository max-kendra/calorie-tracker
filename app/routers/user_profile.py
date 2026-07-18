import uuid
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from sqlalchemy.orm import Session

from app.auth import require_api_key
from app.config import settings
from app.database import get_db
from app.models import UserProfile
from app.schemas import (
    KcalGoalCalculationResult,
    UserProfileOut,
    UserProfileUpdate,
)

router = APIRouter(
    prefix="/profile",
    tags=["profile"],
    dependencies=[Depends(require_api_key)],
)

# Standard Harris-Benedict/Mifflin-St Jeor activity multipliers, widely
# used alongside Mifflin-St Jeor in clinical/dietetics practice (see
# design doc sourcing notes below).
_ACTIVITY_MULTIPLIERS = {
    "sedentary": Decimal("1.2"),
    "light": Decimal("1.375"),
    "moderate": Decimal("1.55"),
    "active": Decimal("1.725"),
    "very_active": Decimal("1.9"),
}

# Standard ~1 lb/week deficit or surplus - a widely-used default, not a
# personalized prescription. "maintain" applies no adjustment.
_GOAL_ADJUSTMENT_KCAL = {
    "lose": Decimal("-500"),
    "maintain": Decimal("0"),
    "gain": Decimal("500"),
}

# Safety floor - never recommend below this, regardless of the formula's
# raw output. This is a general safety heuristic, not a substitute for
# individualized medical/dietetic guidance.
MIN_RECOMMENDED_KCAL = Decimal("1500")


def _round_to_nearest(value: Decimal, nearest: int) --> int:
    """Rounds to the nearest multiple of `nearest` (e.g. nearest=50 turns
    1636 into 1650). Used for recommended_kcal/kcal_low/kcal_high so the
    output is a number people can actually track against, not false
    precision from a formula that's only accurate to ~10% anyway."""
    n = Decimal(nearest)
    return int((value / n).to_integral_value(rounding=ROUND_HALF_UP) * n)


def _get_or_create_profile(db: Session) --> UserProfile:
    """
    Single-user app - there's exactly one profile row, created on first
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


@router.post("/picture", response_model=UserProfileOut)
async def upload_profile_picture(image: UploadFile = File(...), db: Session = Depends(get_db)):
    """
    Saves a (client-cropped) profile picture and sets it as the current
    profile's `profile_pic_path` in one step - unlike scan-product-photo
    (which just saves+returns a path for the CALLER to decide what to do
    with, since it's mid-flow in Add Item and the item doesn't exist yet
    to attach it to), there's exactly one profile row in this app (see
    _get_or_create_profile), so there's no ambiguity about what this
    image belongs to - saving it IS the update.

    Same storage convention as scan-product-photo: written under
    settings.media_dir, served back at /media/<filename> per the
    StaticFiles mount in app/main.py.
    """
    image_bytes = await image.read()

    filename = f"{uuid.uuid4().hex}.jpg"
    media_dir = Path(settings.media_dir)
    media_dir.mkdir(parents=True, exist_ok=True)
    (media_dir / filename).write_bytes(image_bytes)
    image_path = f"{settings.media_dir}/{filename}"

    profile = _get_or_create_profile(db)
    profile.profile_pic_path = image_path
    db.commit()
    db.refresh(profile)
    return profile


@router.post("/calculate-kcal-goal", response_model=KcalGoalCalculationResult)
def calculate_kcal_goal(db: Session = Depends(get_db)):
    """
    Calculates a recommended daily calorie target using the Mifflin-St
    Jeor equation (BMR), an activity multiplier (TDEE), and a standard
    goal-based adjustment - with a 1500 kcal/day safety floor. Also
    returns a kcal_low/kcal_high range reflecting the formula's own ~10%
    accuracy margin, rather than presenting a single number as if it were
    precise to the calorie - see KcalGoalCalculationResult's docstring.
    All inputs (height, age, weight, activity_level, goal_type) are read
    from the stored profile - this endpoint takes no request body, so
    the profile must be filled in first (see Profile screen's gating
    behavior in the Android app).

    Mifflin-St Jeor (1990), endorsed by the Academy of Nutrition and
    Dietetics, is the most validated BMR formula for the general adult
    population (predicts measured BMR within 10% for ~82% of adults,
    per Frankenfield et al. 2005):
        Men:   BMR = 10*weight_kg + 6.25*height_cm - 5*age + 5
        Women: BMR = 10*weight_kg + 6.25*height_cm - 5*age - 161

    IMPORTANT HONESTY NOTE: the formula's male/female constants were
    derived from studies using binary sex, not hormone status directly.
    We use `primary_hormone` as a pragmatic proxy (testosterone --> "male"
    constant, estrogen --> "female" constant) since that's the field this
    app collects instead of sex (see design doc) - the constants do
    largely track body-composition differences correlated with hormonal
    profile, but this substitution itself hasn't been separately
    clinically validated. This field is optional and can be left unset
    (a former third "other" option was removed - no profile had it
    set, and it behaved identically to unset anyway); for unset, we
    average the two constants as a reasonable fallback, not a
    rigorously derived value.

    This is a general-purpose estimate, not personalized medical advice.
    """
    profile = _get_or_create_profile(db)

    missing = []
    if profile.height_cm is None:
        missing.append("height_cm")
    if profile.age is None:
        missing.append("age")
    if profile.weight_kg is None:
        missing.append("weight_kg")
    if profile.activity_level is None:
        missing.append("activity_level")
    if profile.goal_type is None:
        missing.append("goal_type")
    if missing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Profile is missing required field(s) for this calculation: {', '.join(missing)}",
        )

    weight_kg = profile.weight_kg
    height_cm = profile.height_cm
    age = Decimal(profile.age)

    base = Decimal(10) * weight_kg + Decimal("6.25") * height_cm - Decimal(5) * age

    if profile.primary_hormone == "testosterone":
        bmr = base + Decimal(5)
    elif profile.primary_hormone == "estrogen":
        bmr = base - Decimal(161)
    else:
        # Unset - average of the two constants, a pragmatic fallback
        # rather than a separately validated figure (see function
        # docstring). Used to also handle a since-removed "other"
        # option, which behaved identically to unset anyway.
        bmr = base + (Decimal(5) + Decimal(-161)) / 2

    multiplier = _ACTIVITY_MULTIPLIERS[profile.activity_level]
    tdee = bmr * multiplier

    adjustment = _GOAL_ADJUSTMENT_KCAL[profile.goal_type]
    raw_recommended = tdee + adjustment

    # Range reflects Mifflin-St Jeor's own known accuracy margin (~10%,
    # per Frankenfield et al. 2005) - computed from the RAW (unfloored)
    # estimate so the range stays centered on the true calculation, not
    # skewed upward by an already-floor-adjusted midpoint. Both bounds
    # still individually respect the safety floor.
    floor_applied = raw_recommended < MIN_RECOMMENDED_KCAL
    recommended_kcal = max(raw_recommended, MIN_RECOMMENDED_KCAL)
    kcal_low = max(raw_recommended * Decimal("0.9"), MIN_RECOMMENDED_KCAL)
    kcal_high = max(raw_recommended * Decimal("1.1"), MIN_RECOMMENDED_KCAL)

    return KcalGoalCalculationResult(
        bmr=_round_to_nearest(bmr, 1),
        tdee=_round_to_nearest(tdee, 1),
        recommended_kcal=_round_to_nearest(recommended_kcal, 25),
        kcal_low=_round_to_nearest(kcal_low, 25),
        kcal_high=_round_to_nearest(kcal_high, 25),
        floor_applied=floor_applied,
    )