from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session, joinedload

from app.auth import require_api_key
from app.database import get_db
from app.models import GroceryListEntry, GroceryStore, GroceryTrip, Item
from app.schemas import (
    GroceryListEntryCreate,
    GroceryListEntryOut,
    GroceryListEntryUpdate,
    GroceryTripCreate,
    GroceryTripOut,
    GroceryTripUpdate,
)

router = APIRouter(
    prefix="/grocery-lists",
    tags=["grocery-lists"],
    dependencies=[Depends(require_api_key)],
)


def _entry_to_out(entry: GroceryListEntry) -> GroceryListEntryOut:
    is_placeholder = entry.item_id is None
    return GroceryListEntryOut(
        id=entry.id,
        item_id=entry.item_id,
        # A placeholder's "name" IS its placeholder text - both cases
        # always resolve to something displayable, so the client never
        # needs to branch on which kind of entry this is just to show
        # something.
        item_name=entry.placeholder_name if is_placeholder else entry.item.name,
        is_placeholder=is_placeholder,
        grocery_stores=entry.item.grocery_stores if entry.item else [],
        trip_id=entry.trip_id,
        quantity=entry.quantity,
    )


def _check_trip_compatibility(item: Optional[Item], trip: GroceryTrip) -> None:
    """A trip with no store assigned accepts anything (see design
    discussion: one store per trip, but not REQUIRING one). A trip WITH
    a store only accepts items that are store-agnostic (carry no
    stores at all) or that explicitly carry that trip's store - this is
    the actual guardrail the earlier any-store-with-a-filter design was
    missing entirely. A placeholder (item is None - see design
    discussion, the hot dog buns example) has no store of its own to
    conflict with, so it's always compatible with any trip."""
    if item is None or trip.store_id is None:
        return
    item_store_ids = {s.id for s in item.grocery_stores}
    if item_store_ids and trip.store_id not in item_store_ids:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"This item isn't carried by this trip's store (item_id={item.item_id})",
        )


# ---- Trips ----


@router.get("/trips", response_model=list[GroceryTripOut])
def list_trips(db: Session = Depends(get_db)):
    """All planned trips, soonest first."""
    return db.query(GroceryTrip).order_by(GroceryTrip.date).all()


@router.post("/trips", response_model=GroceryTripOut, status_code=status.HTTP_201_CREATED)
def create_trip(payload: GroceryTripCreate, db: Session = Depends(get_db)):
    if payload.store_id is not None:
        store = db.query(GroceryStore).filter(GroceryStore.id == payload.store_id).first()
        if not store:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Store not found")
    trip = GroceryTrip(date=payload.date, label=payload.label, store_id=payload.store_id)
    db.add(trip)
    db.commit()
    db.refresh(trip)
    return trip


@router.patch("/trips/{trip_id}", response_model=GroceryTripOut)
def update_trip(trip_id: int, payload: GroceryTripUpdate, db: Session = Depends(get_db)):
    trip = db.query(GroceryTrip).filter(GroceryTrip.id == trip_id).first()
    if not trip:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Trip not found")
    updates = payload.model_dump(exclude_unset=True)
    if "store_id" in updates and updates["store_id"] is not None:
        store = db.query(GroceryStore).filter(GroceryStore.id == updates["store_id"]).first()
        if not store:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Store not found")
        # Changing a trip's store could strand entries already in it
        # that aren't compatible with the NEW store - re-check each one
        # rather than leave a silently-inconsistent trip behind.
        for entry in trip.entries:
            _check_trip_compatibility(entry.item, GroceryTrip(store_id=updates["store_id"]))
    for field, value in updates.items():
        setattr(trip, field, value)
    db.commit()
    db.refresh(trip)
    return trip


@router.delete("/trips/{trip_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_trip(trip_id: int, db: Session = Depends(get_db)):
    """Deleting a trip drops its entries back into the unassigned pool
    (trip_id -> NULL, via the FK's ondelete=SET NULL - see the
    migration) rather than deleting them - the items still need buying,
    only the trip planning goes away."""
    trip = db.query(GroceryTrip).filter(GroceryTrip.id == trip_id).first()
    if not trip:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Trip not found")
    db.delete(trip)
    db.commit()
    return None


# ---- List entries ----


@router.get("/entries", response_model=list[GroceryListEntryOut])
def list_entries(db: Session = Depends(get_db)):
    """Every entry, pool and trip-assigned alike - the client does the
    grouping (by store for the pool, by trip for planned ones)."""
    entries = (
        db.query(GroceryListEntry)
        .options(joinedload(GroceryListEntry.item))
        .all()
    )
    return [_entry_to_out(e) for e in entries]


@router.post("/entries", response_model=GroceryListEntryOut, status_code=status.HTTP_201_CREATED)
def create_entry(payload: GroceryListEntryCreate, db: Session = Depends(get_db)):
    """Adds something to the grocery list - either a real catalog item
    (the + icon on a logged item's row) or a placeholder with just a
    plain name (see design discussion: the hot dog buns example -
    knowing you need something before you've found/scanned the actual
    product). Exactly one of item_id/placeholder_name, never both,
    never neither."""
    if (payload.item_id is None) == (payload.placeholder_name is None):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Provide exactly one of item_id or placeholder_name",
        )

    item = None
    if payload.item_id is not None:
        item = db.query(Item).filter(Item.item_id == payload.item_id).first()
        if not item:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")

    if payload.trip_id is not None:
        trip = db.query(GroceryTrip).filter(GroceryTrip.id == payload.trip_id).first()
        if not trip:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Trip not found")
        _check_trip_compatibility(item, trip)

    entry = GroceryListEntry(
        item_id=payload.item_id,
        placeholder_name=payload.placeholder_name,
        trip_id=payload.trip_id,
        quantity=payload.quantity,
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return _entry_to_out(entry)


@router.patch("/entries/{entry_id}", response_model=GroceryListEntryOut)
def update_entry(entry_id: int, payload: GroceryListEntryUpdate, db: Session = Depends(get_db)):
    """Moves an entry into a trip, or back to the unassigned pool
    (trip_id: null) - the drag-and-drop action - updates its quantity
    text, and/or RESOLVES a placeholder into a real item by setting
    item_id here (see design discussion) - which also clears
    placeholder_name on the same row, since an entry is never both at
    once. There's deliberately no separate "resolve" endpoint - editing
    item_id on the existing entry IS the resolve action."""
    entry = db.query(GroceryListEntry).filter(GroceryListEntry.id == entry_id).first()
    if not entry:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Entry not found")

    updates = payload.model_dump(exclude_unset=True)

    new_item: Optional[Item] = entry.item
    if "item_id" in updates and updates["item_id"] is not None:
        new_item = db.query(Item).filter(Item.item_id == updates["item_id"]).first()
        if not new_item:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")

    effective_trip_id = updates.get("trip_id", entry.trip_id)
    if effective_trip_id is not None:
        trip = db.query(GroceryTrip).filter(GroceryTrip.id == effective_trip_id).first()
        if not trip:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Trip not found")
        _check_trip_compatibility(new_item, trip)

    if "item_id" in updates and updates["item_id"] is not None:
        entry.placeholder_name = None

    for field, value in updates.items():
        setattr(entry, field, value)
    db.commit()
    db.refresh(entry)
    return _entry_to_out(entry)


@router.delete("/entries/{entry_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_entry(entry_id: int, db: Session = Depends(get_db)):
    """Checking an item off - removes it from the list entirely (see
    design discussion: plain delete, not a "purchased" flag - simpler,
    and nothing yet needs a purchase history)."""
    entry = db.query(GroceryListEntry).filter(GroceryListEntry.id == entry_id).first()
    if not entry:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Entry not found")
    db.delete(entry)
    db.commit()
    return None