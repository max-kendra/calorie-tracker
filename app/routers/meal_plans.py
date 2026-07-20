from datetime import date as date_type
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.auth import require_api_key
from app.database import get_db
from app.models import Item, Log, MealPlan, Recipe
from app.routers.logs import _validate_and_compute
from app.nutrition import to_display
from app.schemas import (
    CommitRange,
    CommitResult,
    MealPlanCreate,
    MealPlanOut,
    MealPlanUpdate,
    MealType,
)

router = APIRouter(
    prefix="/meal-plans",
    tags=["meal_plans"],
    dependencies=[Depends(require_api_key)],
)


def _plan_to_out(plan: MealPlan, db: Session) -> MealPlanOut:
    """
    NOT snapshotted - totals are computed fresh on every read, so a
    planned meal always reflects current item/recipe data right up until
    it's committed (see design doc: meal_plans stays live-reference,
    unlike logs which freeze at write time).
    """
    totals, item_name, recipe_name, _, _, _ = _validate_and_compute(plan, db)
    return MealPlanOut(
        id=plan.id,
        date=plan.date,
        meal_type=plan.meal_type,
        item_id=plan.item_id,
        recipe_id=plan.recipe_id,
        serving_size_id=plan.serving_size_id,
        quantity=plan.quantity,
        computed_totals=to_display(totals),
        item_name=item_name,
        recipe_name=recipe_name,
    )


@router.post("", response_model=MealPlanOut, status_code=status.HTTP_201_CREATED)
def create_meal_plan(payload: MealPlanCreate, db: Session = Depends(get_db)):
    # Validate up front (reuses the same logic logs use) so we don't save
    # a plan referencing something nonexistent, even though we don't
    # snapshot macros here.
    _validate_and_compute(payload, db)

    plan = MealPlan(
        date=payload.date,
        meal_type=payload.meal_type,
        item_id=payload.item_id,
        recipe_id=payload.recipe_id,
        serving_size_id=payload.serving_size_id,
        quantity=payload.quantity,
    )
    db.add(plan)
    db.commit()
    db.refresh(plan)
    return _plan_to_out(plan, db)


@router.get("/{plan_id}", response_model=MealPlanOut)
def get_meal_plan(plan_id: int, db: Session = Depends(get_db)):
    plan = db.query(MealPlan).filter(MealPlan.id == plan_id).first()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Meal plan not found")
    return _plan_to_out(plan, db)


@router.get("", response_model=list[MealPlanOut])
def list_meal_plans(
    date: Optional[date_type] = Query(None),
    start_date: Optional[date_type] = Query(None),
    end_date: Optional[date_type] = Query(None),
    meal_type: Optional[MealType] = Query(None),
    db: Session = Depends(get_db),
):
    query = db.query(MealPlan)

    if date:
        query = query.filter(MealPlan.date == date)
    else:
        if start_date:
            query = query.filter(MealPlan.date >= start_date)
        if end_date:
            query = query.filter(MealPlan.date <= end_date)

    if meal_type:
        query = query.filter(MealPlan.meal_type == meal_type)

    plans = query.order_by(MealPlan.date).all()
    return [_plan_to_out(p, db) for p in plans]


@router.patch("/{plan_id}", response_model=MealPlanOut)
def update_meal_plan(plan_id: int, payload: MealPlanUpdate, db: Session = Depends(get_db)):
    plan = db.query(MealPlan).filter(MealPlan.id == plan_id).first()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Meal plan not found")

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(plan, field, value)

    db.commit()
    db.refresh(plan)
    return _plan_to_out(plan, db)


@router.delete("/{plan_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_meal_plan(plan_id: int, db: Session = Depends(get_db)):
    plan = db.query(MealPlan).filter(MealPlan.id == plan_id).first()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Meal plan not found")
    db.delete(plan)
    db.commit()
    return None


@router.post("/commit", response_model=CommitResult)
def commit_meal_plans(payload: CommitRange, db: Session = Depends(get_db)):
    """
    The "single button way to add everything from a day/range/week to the
    tracker" action. For every meal_plan in [start_date, end_date]:
    compute its macros NOW and freeze them into a new `logs` row (this is
    the exact moment a planned meal becomes a historical fact). The
    source meal_plan row is then deleted - committing is one-directional;
    re-planning the same meal for a future date means creating a new plan.
    """
    plans = (
        db.query(MealPlan)
        .filter(MealPlan.date >= payload.start_date, MealPlan.date <= payload.end_date)
        .all()
    )

    log_ids = []
    for plan in plans:
        totals, _, _, _, _, _ = _validate_and_compute(plan, db)

        log = Log(
            date=plan.date,
            meal_type=plan.meal_type,
            item_id=plan.item_id,
            recipe_id=plan.recipe_id,
            serving_size_id=plan.serving_size_id,
            quantity=plan.quantity,
            kcal_logged=totals.kcal,
            protein_g_logged=totals.protein_g,
            carbs_g_logged=totals.carbs_g,
            fat_g_logged=totals.fat_g,
            fiber_g_logged=totals.fiber_g,
            sugar_g_logged=totals.sugar_g,
            countable_sugar_g_logged=totals.countable_sugar_g,
            saturated_fat_g_logged=totals.saturated_fat_g,
            sodium_mg_logged=totals.sodium_mg,
        )
        db.add(log)
        db.flush()
        log_ids.append(log.id)
        db.delete(plan)

    db.commit()
    return CommitResult(committed_count=len(log_ids), log_ids=log_ids)