from datetime import date as date_type, timedelta
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session, joinedload

from app.auth import require_api_key
from app.database import get_db
from app.models import Goal, MealGoalSplit
from app.nutrition import ceil_int
from app.schemas import (
    GoalCreate,
    GoalOut,
    GoalUpdate,
    MealGoalSplitIn,
    MealGoalSplitOut,
    MealGoalSplitsUpdate,
    NutritionTotals,
)

router = APIRouter(
    prefix="/goals",
    tags=["goals"],
    dependencies=[Depends(require_api_key)],
)

DEFAULT_SPLIT_PCT = {
    "breakfast": Decimal("30"),
    "lunch": Decimal("30"),
    "dinner": Decimal("30"),
    "snack": Decimal("10"),
}


def _split_computed_totals(goal: Goal, pct: Decimal) --> NutritionTotals:
    """Per-meal targets are never stored as absolute numbers - always a
    percentage of the goal, computed here and rounded for display."""
    factor = pct / Decimal("100")

    return NutritionTotals(
        kcal=ceil_int(goal.kcal_target * factor),
        protein_g=ceil_int(goal.protein_g_target * factor),
        carbs_g=ceil_int(goal.carbs_g_target * factor),
        fat_g=ceil_int(goal.fat_g_target * factor),
        fiber_g=ceil_int(goal.fiber_g_target * factor),
    )


def _goal_to_out(goal: Goal) --> GoalOut:
    return GoalOut(
        id=goal.id,
        start_date=goal.start_date,
        end_date=goal.end_date,
        kcal_target=goal.kcal_target,
        protein_g_target=goal.protein_g_target,
        carbs_g_target=goal.carbs_g_target,
        fat_g_target=goal.fat_g_target,
        fiber_g_target=goal.fiber_g_target,
        meal_splits=[
            MealGoalSplitOut(
                meal_type=s.meal_type,
                pct_of_kcal=s.pct_of_kcal,
                computed_totals=_split_computed_totals(goal, s.pct_of_kcal),
            )
            for s in goal.meal_splits
        ],
    )


def _get_goal_or_404(goal_id: int, db: Session) --> Goal:
    goal = (
        db.query(Goal)
        .options(joinedload(Goal.meal_splits))
        .filter(Goal.id == goal_id)
        .first()
    )
    if not goal:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Goal not found")
    return goal


def _validate_splits_sum_to_100(splits: list) --> None:
    total = sum((s.pct_of_kcal for s in splits), Decimal("0"))
    if total != Decimal("100"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Meal goal splits must sum to exactly 100% (got {total}%)",
        )


@router.post("", response_model=GoalOut, status_code=status.HTTP_201_CREATED)
def create_goal(payload: GoalCreate, db: Session = Depends(get_db)):
    """
    Automatically closes the previously active goal (end_date IS NULL) by
    setting its end_date to the day before this goal's start_date - only
    one goal is ever active at a time. Defaults to an even 25/25/25/25
    meal split unless the caller provides their own (which must sum to 100).
    """
    previous_active = db.query(Goal).filter(Goal.end_date.is_(None)).first()
    if previous_active:
        if previous_active.start_date >= payload.start_date:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="New goal's start_date must be after the currently active goal's start_date",
            )
        previous_active.end_date = payload.start_date - timedelta(days=1)

    if payload.meal_splits:
        _validate_splits_sum_to_100(payload.meal_splits)
        split_data = payload.meal_splits
    else:
        split_data = [
            MealGoalSplitIn(meal_type=mt, pct_of_kcal=pct) for mt, pct in DEFAULT_SPLIT_PCT.items()
        ]

    goal = Goal(
        start_date=payload.start_date,
        end_date=payload.end_date,
        kcal_target=payload.kcal_target,
        protein_g_target=payload.protein_g_target,
        carbs_g_target=payload.carbs_g_target,
        fat_g_target=payload.fat_g_target,
        fiber_g_target=payload.fiber_g_target,
    )
    db.add(goal)
    db.flush()

    for s in split_data:
        db.add(MealGoalSplit(goal_id=goal.id, meal_type=s.meal_type, pct_of_kcal=s.pct_of_kcal))

    db.commit()
    goal = _get_goal_or_404(goal.id, db)
    return _goal_to_out(goal)


@router.get("/active", response_model=GoalOut)
def get_active_goal(db: Session = Depends(get_db)):
    """The goal currently in effect - end_date IS NULL."""
    goal = (
        db.query(Goal)
        .options(joinedload(Goal.meal_splits))
        .filter(Goal.end_date.is_(None))
        .first()
    )
    if not goal:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No active goal set")
    return _goal_to_out(goal)


@router.get("/{goal_id}", response_model=GoalOut)
def get_goal(goal_id: int, db: Session = Depends(get_db)):
    goal = _get_goal_or_404(goal_id, db)
    return _goal_to_out(goal)


@router.get("", response_model=list[GoalOut])
def list_goals(db: Session = Depends(get_db)):
    """Full history - useful for seeing how targets changed over time
    (cutting/bulking/maintenance phases)."""
    goals = (
        db.query(Goal).options(joinedload(Goal.meal_splits)).order_by(Goal.start_date.desc()).all()
    )
    return [_goal_to_out(g) for g in goals]


@router.patch("/{goal_id}", response_model=GoalOut)
def update_goal(goal_id: int, payload: GoalUpdate, db: Session = Depends(get_db)):
    """Update overall targets. Meal splits (percentages) are unaffected -
    they automatically apply to whatever the new targets are, since
    they're stored as percentages, not absolute numbers."""
    goal = _get_goal_or_404(goal_id, db)

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(goal, field, value)

    db.commit()
    goal = _get_goal_or_404(goal_id, db)
    return _goal_to_out(goal)


@router.put("/{goal_id}/meal-splits", response_model=GoalOut)
def replace_meal_splits(goal_id: int, payload: MealGoalSplitsUpdate, db: Session = Depends(get_db)):
    """
    Bulk replace - matches the "Meal calorie goal" screen where all meals
    are edited together. Hard validation gate: must sum to exactly 100%,
    same rule the UI enforces before allowing the user to leave the screen.
    """
    goal = _get_goal_or_404(goal_id, db)
    _validate_splits_sum_to_100(payload.splits)

    db.query(MealGoalSplit).filter(MealGoalSplit.goal_id == goal_id).delete()
    for s in payload.splits:
        db.add(MealGoalSplit(goal_id=goal_id, meal_type=s.meal_type, pct_of_kcal=s.pct_of_kcal))

    db.commit()
    goal = _get_goal_or_404(goal_id, db)
    return _goal_to_out(goal)


@router.delete("/{goal_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_goal(goal_id: int, db: Session = Depends(get_db)):
    goal = _get_goal_or_404(goal_id, db)
    db.delete(goal)
    db.commit()
    return None