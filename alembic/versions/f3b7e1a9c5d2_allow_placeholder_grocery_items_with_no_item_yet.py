"""allow placeholder grocery entries with no item yet

Revision ID: f3b7e1a9c5d2
Revises: e6a9c3d7f2b1
Create Date: 2026-09-27 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = 'f3b7e1a9c5d2'
down_revision: Union[str, Sequence[str], None] = 'e6a9c3d7f2b1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.alter_column("grocery_list_entries", "item_id", nullable=True)
    # Only meaningful when item_id IS NULL - a plain free-text label
    # for "I want to buy this, but haven't found/scanned the actual
    # product yet" (see design discussion: the hot dog buns example).
    # Mutually exclusive with item_id at the application level - an
    # entry is EITHER a real catalog item OR a placeholder, never both,
    # never neither.
    op.add_column("grocery_list_entries", sa.Column("placeholder_name", sa.String(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("grocery_list_entries", "placeholder_name")
    op.alter_column("grocery_list_entries", "item_id", nullable=False)