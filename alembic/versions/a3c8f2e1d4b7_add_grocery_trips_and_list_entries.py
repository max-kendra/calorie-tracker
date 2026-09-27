"""add grocery trips and grocery list entries

Revision ID: a3c8f2e1d4b7
Revises: f1b7d3a9c2e6
Create Date: 2026-09-27 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = 'a3c8f2e1d4b7'
down_revision: Union[str, Sequence[str], None] = 'f1b7d3a9c2e6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        "grocery_trips",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("date", sa.Date(), nullable=False),
        # Optional - "Thursday run" vs just showing the date is plenty
        # most of the time.
        sa.Column("label", sa.String(), nullable=True),
    )

    op.create_table(
        "grocery_list_entries",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("item_id", sa.Integer(), sa.ForeignKey("items.item_id", ondelete="CASCADE"), nullable=False),
        # NULL = still in the unassigned pool, not yet planned for a
        # specific trip - see design discussion (the Thursday/Sunday
        # banana example). ondelete="SET NULL", not CASCADE - deleting a
        # trip should drop entries back into the pool (the item still
        # needs buying, the trip planning just went away), not delete
        # the fact that you need to buy it at all.
        sa.Column(
            "trip_id", sa.Integer(), sa.ForeignKey("grocery_trips.id", ondelete="SET NULL"), nullable=True
        ),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_table("grocery_list_entries")
    op.drop_table("grocery_trips")