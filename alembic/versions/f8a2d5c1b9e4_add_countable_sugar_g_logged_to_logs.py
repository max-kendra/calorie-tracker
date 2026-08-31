"""add countable_sugar_g_logged to logs

Revision ID: f8a2d5c1b9e4
Revises: e2b6c9a4f7d1
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'f8a2d5c1b9e4'
down_revision: Union[str, Sequence[str], None] = 'e2b6c9a4f7d1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Sugar contribution EXCLUDING items whose origin is 'usda_import'
    # (see design discussion: raw USDA ingredients like bananas were
    # inflating the "top sugar source" tracking card with naturally-
    # occurring fruit sugar, which isn't what added-sugar dietary
    # guidance is actually about - that guidance targets added/free
    # sugars specifically, not sugar that comes packaged with fiber in
    # whole foods). Frozen at write time, same discipline as every
    # other *_logged column - the weekly summary sums this instead of
    # the raw sugar_g_logged, so a later edit to an item/recipe (or
    # this exclusion rule itself) never retroactively changes a past
    # day's total.
    #
    # This is a heuristic, not a true added-vs-total-sugar distinction:
    # packaged foods only ever give us "carbs, of which sugars" with no
    # further breakdown, so origin (USDA raw ingredient vs a
    # scanned/manual product) is the best proxy available. A raw
    # ingredient that genuinely IS added sugar in practice (honey,
    # syrup) is expected to be logged as a scanned product instead, not
    # a USDA import, per that discussion.
    op.add_column(
        "logs",
        sa.Column("countable_sugar_g_logged", sa.Numeric(), nullable=False, server_default="0"),
    )
    # Best-effort backfill for existing rows: for item-based logs, use
    # the accurate answer (0 if the item's origin is usda_import, else
    # the existing sugar_g_logged as a reasonable approximation - we
    # don't have a historical per-100g sugar figure separate from what's
    # already frozen there). Recipe-based logs are left equal to
    # sugar_g_logged (assume non-USDA-sourced) since we have no
    # historical per-ingredient-origin breakdown to recompute against -
    # a minor, one-time imprecision for pre-existing data only.
    op.execute(
        """
        UPDATE logs
        SET countable_sugar_g_logged = CASE
            WHEN logs.item_id IS NOT NULL AND items.origin = 'usda_import' THEN 0
            ELSE logs.sugar_g_logged
        END
        FROM items
        WHERE logs.item_id = items.item_id
        """
    )
    op.execute(
        "UPDATE logs SET countable_sugar_g_logged = sugar_g_logged WHERE item_id IS NULL"
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("logs", "countable_sugar_g_logged")