from datetime import date as date_type
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session, joinedload

from app.auth import require_api_key
from app.database import get_db
from app.models import Item, Log, Recipe, ServingSize
from app.nutrition import ceil_int, compute_item_totals, compute_recipe_totals_for_quantity, RawTotals
from app.schemas import DailySummary, LogCreate, LogOut, MealType, NutritionTotals

router = APIRouter(
    prefix="/logs",
    tags=["logs"],
    dependencies=[Depends(require_api_key)],
)


def _validate_and_compute(payload, db: Session) -> tuple[RawTotals, Optional[str], Optional[str]]:
    """
    Shared validation + macro computation for both logs and meal_plans.
    Returns (totals, item_name, recipe_name) for convenience/display.
    """
    if (payload.item_id is None) == (payload.recipe_id is None):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Exactly one of item_id or recipe_id must be set",
        )

    if payload.item_id is not None:
        item = db.query(Item).filter(Item.item_id == payload.item_id).first()
        if not item:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown item_id")

        serving = None
        if payload.serving_size_id is not None:
            serving = (
                db.query(ServingSize)
                .filter(ServingSize.id == payload.serving_size_id, ServingSize.item_id == item.item_id)
                .first()
            )
            if not serving:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="serving_size_id does not belong to the given item_id",
                )

        totals = compute_item_totals(item, payload.quantity, serving)
        return totals, item.name, None

    else:
        recipe = (
            db.query(Recipe)
            .options(joinedload(Recipe.ingredients))
            .filter(Recipe.recipe_id == payload.recipe_id)
            .first()
        )
        if not recipe:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unknown recipe_id")

        totals = compute_recipe_totals_for_quantity(recipe, payload.quantity)
        return totals, None, recipe.name


def _log_to_out(log: Log, item_name: Optional[str], recipe_name: Optional[str]) -> LogOut:
    """The DB stores kcal_logged etc as precise Decimal (frozen at write
    time). Rounded UP to int here, at the display boundary only."""
    return LogOut(
        id=log.id,
        date=log.date,
        meal_type=log.meal_type,
        item_id=log.item_id,
        recipe_id=log.recipe_id,
        serving_size_id=log.serving_size_id,
        quantity=log.quantity,
        logged_at=log.logged_at,
        kcal_logged=ceil_int(log.kcal_logged),
        protein_g_logged=ceil_int(log.protein_g_logged),
        carbs_g_logged=ceil_int(log.carbs_g_logged),
        fat_g_logged=ceil_int(log.fat_g_logged),
        fiber_g_logged=ceil_int(log.fiber_g_logged),
        item_name=item_name,
        recipe_name=recipe_name,
    )


@router.post("", response_model=LogOut, status_code=status.HTTP_201_CREATED)
def create_log(payload: LogCreate, db: Session = Depends(get_db)):
    """
    Computes and SNAPSHOTS macros at write time. This is deliberate: if the
    source item/recipe is edited later, this log's numbers must not change
    -- historical days/weekly summaries reflect what was actually counted
    at the time (see design doc).
    """
    totals, item_name, recipe_name = _validate_and_compute(payload, db)

    log = Log(
        date=payload.date,
        meal_type=payload.meal_type,
        item_id=payload.item_id,
        recipe_id=payload.recipe_id,
        serving_size_id=payload.serving_size_id,
        quantity=payload.quantity,
        kcal_logged=totals.kcal,
        protein_g_logged=totals.protein_g,
        carbs_g_logged=totals.carbs_g,
        fat_g_logged=totals.fat_g,
        fiber_g_logged=totals.fiber_g,
    )
    db.add(log)
    db.commit()
    db.refresh(log)
    return _log_to_out(log, item_name, recipe_name)


@router.get("/{log_id}", response_model=LogOut)
def get_log(log_id: int, db: Session = Depends(get_db)):
    log = db.query(Log).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")

    item_name = log.item_id and db.query(Item.name).filter(Item.item_id == log.item_id).scalar()
    recipe_name = log.recipe_id and db.query(Recipe.name).filter(Recipe.recipe_id == log.recipe_id).scalar()
    return _log_to_out(log, item_name, recipe_name)


@router.get("", response_model=list[LogOut])
def list_logs(
    date: Optional[date_type] = Query(None, description="Single day (Journal view)"),
    start_date: Optional[date_type] = Query(None),
    end_date: Optional[date_type] = Query(None),
    meal_type: Optional[MealType] = Query(None),
    db: Session = Depends(get_db),
):
    """Backs the Journal screen -- a single `date` gives one day's log,
    start/end gives a range for weekly summaries."""
    query = db.query(Log)

    if date:
        query = query.filter(Log.date == date)
    else:
        if start_date:
            query = query.filter(Log.date >= start_date)
        if end_date:
            query = query.filter(Log.date <= end_date)

    if meal_type:
        query = query.filter(Log.meal_type == meal_type)

    logs = query.order_by(Log.date, Log.logged_at).all()

    item_ids = {l.item_id for l in logs if l.item_id}
    recipe_ids = {l.recipe_id for l in logs if l.recipe_id}
    item_names = {
        i.item_id: i.name for i in db.query(Item).filter(Item.item_id.in_(item_ids)).all()
    } if item_ids else {}
    recipe_names = {
        r.recipe_id: r.name for r in db.query(Recipe).filter(Recipe.recipe_id.in_(recipe_ids)).all()
    } if recipe_ids else {}

    return [
        _log_to_out(l, item_names.get(l.item_id), recipe_names.get(l.recipe_id)) for l in logs
    ]


@router.get("/summary/daily", response_model=list[DailySummary])
def daily_summary(
    start_date: date_type = Query(...),
    end_date: date_type = Query(...),
    db: Session = Depends(get_db),
):
    """
    Weekly/daily summary view -- sums the FROZEN kcal_logged/etc columns
    grouped by date, not a live recomputation. This is what makes past
    summaries stable even if items/recipes are edited afterward.
    """
    logs = (
        db.query(Log)
        .filter(Log.date >= start_date, Log.date <= end_date)
        .order_by(Log.date)
        .all()
    )

    zero = Decimal("0")
    by_date: dict[date_type, dict[str, Decimal]] = {}
    for l in logs:
        d = by_date.setdefault(
            l.date, {"kcal": zero, "protein_g": zero, "carbs_g": zero, "fat_g": zero, "fiber_g": zero}
        )
        d["kcal"] += l.kcal_logged
        d["protein_g"] += l.protein_g_logged
        d["carbs_g"] += l.carbs_g_logged
        d["fat_g"] += l.fat_g_logged
        d["fiber_g"] += l.fiber_g_logged

    return [
        DailySummary(
            date=d,
            totals=NutritionTotals(
                kcal=ceil_int(totals["kcal"]),
                protein_g=ceil_int(totals["protein_g"]),
                carbs_g=ceil_int(totals["carbs_g"]),
                fat_g=ceil_int(totals["fat_g"]),
                fiber_g=ceil_int(totals["fiber_g"]),
            ),
        )
        for d, totals in sorted(by_date.items())
    ]


@router.delete("/{log_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_log(log_id: int, db: Session = Depends(get_db)):
    log = db.query(Log).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")
    db.delete(log)
    db.commit()
    return None