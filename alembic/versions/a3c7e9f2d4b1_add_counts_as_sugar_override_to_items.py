"""add counts_as_added_sugar override to items

Revision ID: a3c7e9f2d4b1
Revises: f8a2d5c1b9e4
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'a3c7e9f2d4b1'
down_revision: Union[str, Sequence[str], None] = 'f8a2d5c1b9e4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Manual override for the origin-based "countable sugar" heuristic
    # (see design discussion: "my third highest ranking added sugar
    # source is frozen berry mix... this is silly"). The origin
    # heuristic (raw USDA ingredient = not added sugar, everything else
    # = counts) breaks for a whole food that still happens to be sold
    # as a scanned/barcoded product (frozen fruit, dried fruit, etc) --
    # there's no reliable automatic way to detect this (no ingredients
    # list to check for "added sugar" as a listed ingredient), so this
    # is a per-item manual escape hatch instead. NULL = use the origin
    # heuristic (unchanged default behavior); True/False = force count/
    # exclude regardless of origin.
    op.add_column(
        "items",
        sa.Column("counts_as_added_sugar", sa.Boolean(), nullable=True),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("items", "counts_as_added_sugar")