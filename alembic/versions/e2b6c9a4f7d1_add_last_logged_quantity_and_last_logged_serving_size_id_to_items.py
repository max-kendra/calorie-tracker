"""add last_logged_quantity and last_logged_serving_size_id to items

Revision ID: e2b6c9a4f7d1
Revises: d9f1a3b8e5c2
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'e2b6c9a4f7d1'
down_revision: Union[str, Sequence[str], None] = 'd9f1a3b8e5c2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Remembers the quantity/serving an item was last logged with, on
    # the item itself, persisted server-side -- previously this only
    # lived in an in-memory map inside MealDetailViewModel
    # (lastLoggedAmounts), scoped to a single ViewModel instance. Since
    # every meal (even lunch vs dinner on the same day) gets its own
    # fresh ViewModel instance via navigation, that memory never
    # actually survived crossing a meal boundary at all (see design
    # discussion: "if i logged 12g of something for lunch and then go
    # to log dinner, it's 100g again"). Storing it on the Item row
    # itself makes it durable across meals, days, and app restarts.
    op.add_column(
        "items",
        sa.Column("last_logged_quantity", sa.Numeric(), nullable=True),
    )
    op.add_column(
        "items",
        sa.Column(
            "last_logged_serving_size_id",
            sa.Integer(),
            sa.ForeignKey("serving_sizes.id"),
            nullable=True,
        ),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("items", "last_logged_serving_size_id")
    op.drop_column("items", "last_logged_quantity")