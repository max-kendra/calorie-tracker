"""add last_logged_at to items

Revision ID: c4d8b1f6e2a7
Revises: b7e2f4a9c1d3
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'c4d8b1f6e2a7'
down_revision: Union[str, Sequence[str], None] = 'b7e2f4a9c1d3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Backs "recently logged" ordering in the search/recent item list
    # (see design discussion: previously ordered by updated_at, which is
    # catalog-edit recency, not logging recency -- an item you log every
    # day but never edit would sink to the bottom while something you
    # tweaked once today stayed on top). Set at item creation time too
    # (not just on first log), so a just-created item shows at the top
    # immediately rather than waiting for its first log.
    op.add_column(
        "items",
        sa.Column("last_logged_at", sa.DateTime(timezone=True), nullable=True),
    )
    # Backfill existing rows with created_at so pre-existing items don't
    # all sort as "never logged" (NULL) beneath every future item -- an
    # imperfect approximation (not actually when they were last logged),
    # but a reasonable one-time default that keeps existing ordering
    # roughly sensible until each item is actually logged again.
    op.execute("UPDATE items SET last_logged_at = created_at WHERE last_logged_at IS NULL")


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("items", "last_logged_at")