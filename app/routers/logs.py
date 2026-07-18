from datetime import date as date_type
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import desc, func
from sqlalchemy.orm import Session, joinedload

from app.auth import require_api_key
from app.database import get_db
from app.models import Item, Log, Recipe, ServingSize
from app.nutrition import ceil_int, compute_item_totals, compute_recipe_totals_for_quantity, RawTotals
from app.schemas import (
    DailySummary,
    ExtendedNutritionTotals,
    ItemOut,
    LogCreate,
    LogFromMealRequest,
    LogOut,
    LogUpdate,
    MealType,
    NutritionTotals,
)

router = APIRouter(
    prefix="/logs",
    tags=["logs"],
    dependencies=[Depends(require_api_key)],
)


def _validate_and_compute(payload, db: Session) -> tuple[RawTotals, Optional[str], Optional[str], Optional[str]]:
    """
    Shared validation + macro computation for both logs and meal_plans.
    Returns (totals, item_name, recipe_name, image_path) for convenience/
    display -- image_path denormalized onto LogOut so the client can show
    a thumbnail without a separate lookup per row.
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
        return totals, item.name, None, item.image_path

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
        return totals, None, recipe.name, recipe.image_path


def _log_to_out(
    log: Log, item_name: Optional[str], recipe_name: Optional[str], image_path: Optional[str] = None
) -> LogOut:
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
        image_path=image_path,
    )


@router.post("", response_model=LogOut, status_code=status.HTTP_201_CREATED)
def create_log(payload: LogCreate, db: Session = Depends(get_db)):
    """
    Computes and SNAPSHOTS macros at write time. This is deliberate: if the
    source item/recipe is edited later, this log's numbers must not change
    -- historical days/weekly summaries reflect what was actually counted
    at the time (see design doc).
    """
    totals, item_name, recipe_name, image_path = _validate_and_compute(payload, db)

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
        sugar_g_logged=totals.sugar_g,
        saturated_fat_g_logged=totals.saturated_fat_g,
        sodium_mg_logged=totals.sodium_mg,
    )
    db.add(log)
    db.commit()
    db.refresh(log)
    return _log_to_out(log, item_name, recipe_name, image_path)


@router.post("/from-meal", response_model=list[LogOut], status_code=status.HTTP_201_CREATED)
def create_logs_from_meal(payload: LogFromMealRequest, db: Session = Depends(get_db)):
    """
    Expands a saved "meal" into one log PER INGREDIENT, rather than a
    single log referencing recipe_id the way an actual recipe logs (see
    POST /logs with recipe_id set, still used for real recipes). This is
    the functional distinction between the two: a recipe stays one
    atomic log entry; a meal's ingredients land individually, each fully
    editable/removable afterward -- same as if the user had added each
    item to this meal one at a time (see design doc).

    Only valid for recipe_type="meal" -- rejects actual recipes, which
    should keep logging the normal atomic way.
    """
    recipe = (
        db.query(Recipe)
        .options(joinedload(Recipe.ingredients))
        .filter(Recipe.recipe_id == payload.recipe_id)
        .first()
    )
    if not recipe:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Meal not found")
    if recipe.recipe_type != "meal":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="This is a recipe, not a meal -- log it with POST /logs and recipe_id instead",
        )
    if not recipe.ingredients:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="This meal has no ingredients")

    # Look up all the ingredient items in one query rather than one
    # query per ingredient in the loop below.
    item_ids = [ing.item_id for ing in recipe.ingredients]
    items_by_id = {item.item_id: item for item in db.query(Item).filter(Item.item_id.in_(item_ids)).all()}

    created: list[tuple[Log, str, Optional[str]]] = []
    for ingredient in recipe.ingredients:
        item = items_by_id.get(ingredient.item_id)
        if not item:
            # The ingredient's item was deleted since this meal was
            # saved -- skip it rather than fail the whole request over
            # one missing item.
            continue

        totals = compute_item_totals(item, ingredient.quantity_g, None)
        log = Log(
            date=payload.date,
            meal_type=payload.meal_type,
            item_id=item.item_id,
            recipe_id=None,
            serving_size_id=None,
            quantity=ingredient.quantity_g,
            kcal_logged=totals.kcal,
            protein_g_logged=totals.protein_g,
            carbs_g_logged=totals.carbs_g,
            fat_g_logged=totals.fat_g,
            fiber_g_logged=totals.fiber_g,
            sugar_g_logged=totals.sugar_g,
            saturated_fat_g_logged=totals.saturated_fat_g,
            sodium_mg_logged=totals.sodium_mg,
        )
        db.add(log)
        created.append((log, item.name, item.image_path))

    if not created:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="None of this meal's ingredient items exist anymore",
        )

    db.commit()
    result = []
    for log, item_name, image_path in created:
        db.refresh(log)
        result.append(_log_to_out(log, item_name, None, image_path))
    return result


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
    item_images = {
        i.item_id: i.image_path for i in db.query(Item).filter(Item.item_id.in_(item_ids)).all()
    } if item_ids else {}
    recipe_images = {
        r.recipe_id: r.image_path for r in db.query(Recipe).filter(Recipe.recipe_id.in_(recipe_ids)).all()
    } if recipe_ids else {}

    return [
        _log_to_out(
            l,
            item_names.get(l.item_id),
            recipe_names.get(l.recipe_id),
            item_images.get(l.item_id) or recipe_images.get(l.recipe_id)
        ) for l in logs
    ]


@router.get("/recent-items", response_model=list[ItemOut])
def recent_items(
    meal_type: Optional[MealType] = Query(None, description="Narrow to items logged for this meal type"),
    limit: int = Query(20, le=50),
    db: Session = Depends(get_db),
):
    """
    Items sorted by most recently LOGGED (not most recently added to the
    catalog) -- backs the Add Item sheet's default "Saved" view, so the
    things you actually eat regularly float to the top rather than
    whatever you happened to create first.

    Recipe-based logs are excluded here -- this returns Items only, not
    Recipes -- kept simple for the first version of this endpoint;
    revisit if recipes need to show up in "recently logged" too.
    """
    last_logged_subq = (
        db.query(Log.item_id, func.max(Log.logged_at).label("last_logged"))
        .filter(Log.item_id.isnot(None))
    )
    if meal_type:
        last_logged_subq = last_logged_subq.filter(Log.meal_type == meal_type)
    last_logged_subq = (
        last_logged_subq.group_by(Log.item_id)
        .order_by(desc("last_logged"))
        .limit(limit)
        .subquery()
    )

    items = (
        db.query(Item)
        .join(last_logged_subq, Item.item_id == last_logged_subq.c.item_id)
        .order_by(desc(last_logged_subq.c.last_logged))
        .all()
    )
    return items


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
            l.date,
            {
                "kcal": zero,
                "protein_g": zero,
                "carbs_g": zero,
                "fat_g": zero,
                "fiber_g": zero,
                "sugar_g": zero,
                "saturated_fat_g": zero,
                "sodium_mg": zero,
            },
        )
        d["kcal"] += l.kcal_logged
        d["protein_g"] += l.protein_g_logged
        d["carbs_g"] += l.carbs_g_logged
        d["fat_g"] += l.fat_g_logged
        d["fiber_g"] += l.fiber_g_logged
        d["sugar_g"] += l.sugar_g_logged
        d["saturated_fat_g"] += l.saturated_fat_g_logged
        d["sodium_mg"] += l.sodium_mg_logged

    return [
        DailySummary(
            date=d,
            totals=ExtendedNutritionTotals(
                kcal=ceil_int(totals["kcal"]),
                protein_g=ceil_int(totals["protein_g"]),
                carbs_g=ceil_int(totals["carbs_g"]),
                fat_g=ceil_int(totals["fat_g"]),
                fiber_g=ceil_int(totals["fiber_g"]),
                sugar_g=ceil_int(totals["sugar_g"]),
                saturated_fat_g=ceil_int(totals["saturated_fat_g"]),
                sodium_mg=ceil_int(totals["sodium_mg"]),
            ),
        )
        for d, totals in sorted(by_date.items())
    ]


@router.patch("/{log_id}", response_model=LogOut)
def update_log(log_id: int, payload: LogUpdate, db: Session = Depends(get_db)):
    """
    Quantity-only edit -- item_id/recipe_id/date/meal_type stay fixed
    (see LogUpdate's doc comment). Re-runs the same snapshot computation
    create_log does, so the log's macros stay internally consistent with
    its new quantity rather than going stale.
    """
    log = db.query(Log).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")

    if payload.quantity is None and payload.serving_size_id is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Provide at least one of quantity or serving_size_id",
        )

    merged = LogCreate(
        date=log.date,
        meal_type=log.meal_type,
        item_id=log.item_id,
        recipe_id=log.recipe_id,
        serving_size_id=payload.serving_size_id if payload.serving_size_id is not None else log.serving_size_id,
        quantity=payload.quantity if payload.quantity is not None else log.quantity,
    )
    totals, item_name, recipe_name, image_path = _validate_and_compute(merged, db)

    log.serving_size_id = merged.serving_size_id
    log.quantity = merged.quantity
    log.kcal_logged = totals.kcal
    log.protein_g_logged = totals.protein_g
    log.carbs_g_logged = totals.carbs_g
    log.fat_g_logged = totals.fat_g
    log.fiber_g_logged = totals.fiber_g
    log.sugar_g_logged = totals.sugar_g
    log.saturated_fat_g_logged = totals.saturated_fat_g
    log.sodium_mg_logged = totals.sodium_mg
    db.commit()
    db.refresh(log)
    return _log_to_out(log, item_name, recipe_name, image_path)


@router.delete("/{log_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_log(log_id: int, db: Session = Depends(get_db)):
    log = db.query(Log).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")
    db.delete(log)
    db.commit()
    return None


@router.get("/{log_id}", response_model=LogOut)
def get_log(log_id: int, db: Session = Depends(get_db)):
    # Registered LAST among the GET routes -- this is a catch-all path
    # param, and FastAPI matches routes in registration order, so it
    # must come after every fixed-path GET (/recent-items,
    # /summary/daily, and the bare "" list route) or it'll shadow them,
    # trying to parse e.g. "recent-items" as an int and 422ing instead
    # of ever reaching those handlers.
    log = db.query(Log).filter(Log.id == log_id).first()
    if not log:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Log not found")

    item_name = log.item_id and db.query(Item.name).filter(Item.item_id == log.item_id).scalar()
    recipe_name = log.recipe_id and db.query(Recipe.name).filter(Recipe.recipe_id == log.recipe_id).scalar()
    image_path = (
        log.item_id and db.query(Item.image_path).filter(Item.item_id == log.item_id).scalar()
        or log.recipe_id and db.query(Recipe.image_path).filter(Recipe.recipe_id == log.recipe_id).scalar()
    )
    return _log_to_out(log, item_name, recipe_name, image_path)