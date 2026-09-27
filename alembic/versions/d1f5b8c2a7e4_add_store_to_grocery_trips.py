"""add store to grocery trips

Revision ID: d1f5b8c2a7e4
Revises: b7e2c9a4f1d3
Create Date: 2026-09-27 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


revision: str = 'd1f5b8c2a7e4'
down_revision: Union[str, Sequence[str], None] = 'b7e2c9a4f1d3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Nullable - a trip with no store means "no particular store", a
    # valid catch-all (see design discussion: one store per trip, but
    # not REQUIRING one - only items carried by that store, or items
    # with no store assigned at all, can go into a trip that DOES have
    # one; a storeless trip accepts anything). ondelete=SET NULL -
    # deleting a store should fall the trip back to storeless rather
    # than deleting the trip itself.
    op.add_column(
        "grocery_trips",
        sa.Column("store_id", sa.Integer(), sa.ForeignKey("grocery_stores.id", ondelete="SET NULL"), nullable=True),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("grocery_trips", "store_id")