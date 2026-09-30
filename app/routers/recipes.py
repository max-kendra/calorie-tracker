from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func
from sqlalchemy.orm import Session, joinedload

from app.auth import require_api_key
from app.database import get_db
from app.models import Item, Recipe, RecipeIngredient, RecipeStep
from app.nutrition import ceil_int, compute_item_totals, compute_recipe_totals, to_display_extended
from app.search import multi_column_search_filter, relevance_rank
from app.schemas import (
    RecipeCreate,
    RecipeIngredientCreate,
    RecipeOut,
    RecipeStepCreate,
    RecipeStepOut,
    RecipeStepUpdate,
    RecipeType,
    RecipeUpdate,
)

router = APIRouter(
    prefix="/recipes",
    tags=["recipes"],
    dependencies=[Depends(require_api_key)],
)


def _ingredient_out_fields(ri: RecipeIngredient) -> dict:
    """
    Builds the dict RecipeIngredientOut is constructed from for one
    recipe ingredient - full macro breakdown, not just kcal (see design
    discussion: "can we also include this information in the ui when
    creating/editing recipes"). compute_item_totals already has to
    compute every macro to produce kcal, so keeping the rest is free -
    same "already computed, only kcal was ever kept" pattern
    LoggedRecipeIngredient had before it was widened (see that
    migration), just here on the live-recipe side, where there's no
    historical-gap concern at all since this is recomputed fresh every
    time, never frozen.
    """
    totals = compute_item_totals(ri.item, ri.quantity, ri.serving_size)
    return {
        "item_id": ri.item_id,
        "serving_size_id": ri.serving_size_id,
        "quantity": ri.quantity,
        "item_name": ri.item.name,
        "serving_size_name": ri.serving_size.name if ri.serving_size else None,
        "serving_size_weight_g": ri.serving_size.weight_g if ri.serving_size else None,
        "image_path": ri.item.image_path,
        "kcal": ceil_int(totals.kcal),
        "protein_g": ceil_int(totals.protein_g),
        "carbs_g": ceil_int(totals.carbs_g),
        "fat_g": ceil_int(totals.fat_g),
        "fiber_g": ceil_int(totals.fiber_g),
        "sugar_g": ceil_int(totals.sugar_g),
        "countable_sugar_g": ceil_int(totals.countable_sugar_g),
        "saturated_fat_g": ceil_int(totals.saturated_fat_g),
        "sodium_mg": ceil_int(totals.sodium_mg),
    }


def _build_recipe_out(recipe: Recipe) -> RecipeOut:
    totals = compute_recipe_totals(recipe)  # RawTotals, precise
    servings = recipe.servings or Decimal("1")
    per_serving = totals / servings  # still RawTotals, precise

    return RecipeOut(
        recipe_id=recipe.recipe_id,
        name=recipe.name,
        recipe_type=recipe.recipe_type,
        source_url=recipe.source_url,
        image_path=recipe.image_path,
        servings=recipe.servings,
        created_at=recipe.created_at,
        updated_at=recipe.updated_at,
        last_logged_at=recipe.last_logged_at,
        ingredients=[_ingredient_out_fields(ri) for ri in recipe.ingredients],
        # No custom mapping function needed here, unlike ingredients
        # above - RecipeStepOut is a plain passthrough of real columns
        # (no computed fields like kcal to resolve), so from_attributes
        # handles the raw ORM objects directly.
        steps=recipe.steps,
        totals=to_display_extended(totals),
        totals_per_serving=to_display_extended(per_serving),
    )


def _get_recipe_or_404(recipe_id: int, db: Session) -> Recipe:
    recipe = (
        db.query(Recipe)
        .options(joinedload(Recipe.ingredients).joinedload(RecipeIngredient.item))
        .filter(Recipe.recipe_id == recipe_id)
        .first()
    )
    if not recipe:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    return recipe


def _validate_items_exist(item_ids: list[int], db: Session):
    found = db.query(Item.item_id).filter(Item.item_id.in_(item_ids)).all()
    found_ids = {row[0] for row in found}
    missing = set(item_ids) - found_ids
    if missing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unknown item_id(s): {sorted(missing)}",
        )


@router.post("", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
def create_recipe(payload: RecipeCreate, db: Session = Depends(get_db)):
    if payload.ingredients:
        _validate_items_exist([i.item_id for i in payload.ingredients], db)

    recipe = Recipe(
        name=payload.name,
        recipe_type=payload.recipe_type,
        source_url=payload.source_url,
        image_path=payload.image_path,
        servings=payload.servings,
        last_logged_at=func.now(),
    )
    db.add(recipe)
    db.flush()  # get recipe_id before inserting ingredients

    for ing in payload.ingredients:
        db.add(RecipeIngredient(
            recipe_id=recipe.recipe_id,
            item_id=ing.item_id,
            serving_size_id=ing.serving_size_id,
            quantity=ing.quantity,
        ))

    db.commit()
    recipe = _get_recipe_or_404(recipe.recipe_id, db)
    return _build_recipe_out(recipe)


@router.get("/{recipe_id}", response_model=RecipeOut)
def get_recipe(recipe_id: int, db: Session = Depends(get_db)):
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.get("", response_model=list[RecipeOut])
def list_recipes(
    q: Optional[str] = Query(None, description="Search by name"),
    recipe_type: Optional[RecipeType] = Query(None, description="Filter by 'recipe' or 'meal'"),
    limit: int = Query(50, le=200),
    offset: int = Query(0),
    db: Session = Depends(get_db),
):
    """Backs the My Foods Recipe/Meal tabs, filtered by recipe_type."""
    query = db.query(Recipe).options(
        joinedload(Recipe.ingredients).joinedload(RecipeIngredient.item)
    )

    if q:
        search_filter = multi_column_search_filter(q, Recipe.name)
        if search_filter is not None:
            query = query.filter(search_filter)
    if recipe_type:
        query = query.filter(Recipe.recipe_type == recipe_type)

    if q:
        # Same relevance-then-recency ordering as list_items - see
        # relevance_rank's own doc comment.
        query = query.order_by(relevance_rank(q, Recipe.name), Recipe.last_logged_at.desc().nullslast())
    else:
        query = query.order_by(Recipe.last_logged_at.desc().nullslast())

    recipes = query.offset(offset).limit(limit).all()
    return [_build_recipe_out(r) for r in recipes]


@router.patch("/{recipe_id}", response_model=RecipeOut)
def update_recipe(recipe_id: int, payload: RecipeUpdate, db: Session = Depends(get_db)):
    recipe = _get_recipe_or_404(recipe_id, db)

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(recipe, field, value)

    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.delete("/{recipe_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_recipe(recipe_id: int, db: Session = Depends(get_db)):
    recipe = _get_recipe_or_404(recipe_id, db)
    # FK constraint blocks this if any `logs` row still references this
    # recipe — deliberate, same protection as item deletes.
    db.delete(recipe)
    db.commit()
    return None


@router.post("/{recipe_id}/ingredients", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
def add_ingredient(recipe_id: int, payload: RecipeIngredientCreate, db: Session = Depends(get_db)):
    _get_recipe_or_404(recipe_id, db)  # 404 check
    _validate_items_exist([payload.item_id], db)

    existing = (
        db.query(RecipeIngredient)
        .filter(RecipeIngredient.recipe_id == recipe_id, RecipeIngredient.item_id == payload.item_id)
        .first()
    )
    if existing:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This item is already an ingredient in this recipe — use PATCH to change its quantity",
        )

    db.add(RecipeIngredient(
        recipe_id=recipe_id,
        item_id=payload.item_id,
        serving_size_id=payload.serving_size_id,
        quantity=payload.quantity,
    ))
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.patch("/{recipe_id}/ingredients/{item_id}", response_model=RecipeOut)
def update_ingredient_quantity(
    recipe_id: int,
    item_id: int,
    quantity: Decimal,
    serving_size_id: Optional[int] = None,
    db: Session = Depends(get_db),
):
    ri = (
        db.query(RecipeIngredient)
        .filter(RecipeIngredient.recipe_id == recipe_id, RecipeIngredient.item_id == item_id)
        .first()
    )
    if not ri:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Ingredient not in this recipe")

    ri.quantity = quantity
    ri.serving_size_id = serving_size_id
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.delete("/{recipe_id}/ingredients/{item_id}", response_model=RecipeOut)
def remove_ingredient(recipe_id: int, item_id: int, db: Session = Depends(get_db)):
    ri = (
        db.query(RecipeIngredient)
        .filter(RecipeIngredient.recipe_id == recipe_id, RecipeIngredient.item_id == item_id)
        .first()
    )
    if not ri:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Ingredient not in this recipe")

    db.delete(ri)
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)

@router.post("/{recipe_id}/steps", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
def add_step(recipe_id: int, payload: RecipeStepCreate, db: Session = Depends(get_db)):
    """Appends a new step to the end - step_number is assigned
    automatically (highest existing + 1, or 1 if there are none yet),
    not something the caller picks directly."""
    _get_recipe_or_404(recipe_id, db)
    max_step_number = (
        db.query(RecipeStep.step_number)
        .filter(RecipeStep.recipe_id == recipe_id)
        .order_by(RecipeStep.step_number.desc())
        .first()
    )
    next_step_number = (max_step_number[0] + 1) if max_step_number else 1

    db.add(RecipeStep(recipe_id=recipe_id, step_number=next_step_number, text=payload.text, timer_seconds=payload.timer_seconds))
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.patch("/{recipe_id}/steps/{step_id}", response_model=RecipeOut)
def update_step(recipe_id: int, step_id: int, payload: RecipeStepUpdate, db: Session = Depends(get_db)):
    """Edits a step's text/timer only - not its position. Reordering
    isn't supported yet (not asked for this round); deleting and
    re-adding is the current workaround if you need to move something."""
    step = db.query(RecipeStep).filter(RecipeStep.recipe_id == recipe_id, RecipeStep.id == step_id).first()
    if not step:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Step not found in this recipe")

    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(step, field, value)
    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)


@router.delete("/{recipe_id}/steps/{step_id}", response_model=RecipeOut)
def remove_step(recipe_id: int, step_id: int, db: Session = Depends(get_db)):
    """Removes a step and renumbers whatever's left to stay contiguous
    (1, 2, 3...) rather than leaving a gap where it used to be - gaps
    wouldn't break ordering (ORDER BY still works fine), but would look
    like a bug to anyone reading "Step 1, Step 2, Step 4"."""
    step = db.query(RecipeStep).filter(RecipeStep.recipe_id == recipe_id, RecipeStep.id == step_id).first()
    if not step:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Step not found in this recipe")

    db.delete(step)
    db.flush()

    remaining = (
        db.query(RecipeStep)
        .filter(RecipeStep.recipe_id == recipe_id)
        .order_by(RecipeStep.step_number)
        .all()
    )
    for i, s in enumerate(remaining, start=1):
        s.step_number = i

    db.commit()
    recipe = _get_recipe_or_404(recipe_id, db)
    return _build_recipe_out(recipe)