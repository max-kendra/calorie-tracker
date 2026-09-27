"""add grocery stores and item<->store many-to-many

Revision ID: f1b7d3a9c2e6
Revises: a2f9c6e1b8d4
Create Date: 2026-09-26 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'f1b7d3a9c2e6'
down_revision: Union[str, Sequence[str], None] = 'a2f9c6e1b8d4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None
# NOTE: down_revision assumes a2f9c6e1b8d4 (the LoggedRecipeIngredient
# macro-widening migration) is still your actual current head - verify
# with `alembic heads` before running this if anything else has landed
# since then that I don't have visibility into.


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        "grocery_stores",
        sa.Column("id", sa.Integer(), primary_key=True),
        # Unique, not just indexed - the "create a new store inline"
        # flow (see design discussion) should fail loudly on a typo'd
        # duplicate ("Netto" vs "netto" aside - case-sensitivity is a
        # separate, smaller concern) rather than silently creating a
        # second store with the same name that items then get split
        # across arbitrarily.
        sa.Column("name", sa.String(), nullable=False, unique=True),
    )

    # Pure link table - no columns beyond the two foreign keys, no
    # surrogate id of its own, because there's no data that belongs to
    # the ASSOCIATION itself (unlike, say, RecipeIngredient, which has
    # its own quantity/serving_size_id - a fact about that particular
    # ingredient-in-that-recipe, not about the item or the recipe
    # alone). Composite primary key naturally prevents the same
    # item/store pair from ever being linked twice.
    #
    # ondelete="CASCADE" on both sides: deleting an item should clean up
    # its store associations automatically rather than leaving orphaned
    # rows behind, and deleting a store (not exposed yet, but plausible
    # later - "merge these two duplicate stores" or similar) should do
    # the same rather than erroring out or leaving items pointing at a
    # store that no longer exists.
    op.create_table(
        "item_grocery_stores",
        sa.Column("item_id", sa.Integer(), sa.ForeignKey("items.item_id", ondelete="CASCADE"), primary_key=True),
        sa.Column(
            "grocery_store_id", sa.Integer(), sa.ForeignKey("grocery_stores.id", ondelete="CASCADE"), primary_key=True
        ),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_table("item_grocery_stores")
    op.drop_table("grocery_stores")