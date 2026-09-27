from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.auth import require_api_key
from app.database import get_db
from app.models import GroceryStore
from app.schemas import GroceryStoreCreate, GroceryStoreOut

router = APIRouter(
    prefix="/grocery-stores",
    tags=["grocery-stores"],
    dependencies=[Depends(require_api_key)],
)


@router.get("", response_model=list[GroceryStoreOut])
def list_grocery_stores(db: Session = Depends(get_db)):
    """All grocery stores, alphabetical - backs the checkbox picker
    shown when creating/editing an item (see design discussion)."""
    return db.query(GroceryStore).order_by(GroceryStore.name).all()


@router.post("", response_model=GroceryStoreOut, status_code=status.HTTP_201_CREATED)
def create_grocery_store(payload: GroceryStoreCreate, db: Session = Depends(get_db)):
    """Backs the "create a new store inline, check its box" flow in the
    item form - a plain create. Name uniqueness is enforced at the DB
    level too (see the migration), this just gives a clean 409 instead
    of a raw integrity-error 500 when someone tries to create a
    duplicate (e.g. a race between two tabs, or just re-submitting)."""
    existing = db.query(GroceryStore).filter(GroceryStore.name == payload.name).first()
    if existing is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="A store with this name already exists")
    store = GroceryStore(name=payload.name)
    db.add(store)
    db.commit()
    db.refresh(store)
    return store