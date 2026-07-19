"""add last_logged_at to recipes

Revision ID: d9f1a3b8e5c2
Revises: c4d8b1f6e2a7
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'd9f1a3b8e5c2'
down_revision: Union[str, Sequence[str], None] = 'c4d8b1f6e2a7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Same reasoning as items.last_logged_at (see that migration) -
    # backs "recently logged" ordering in the unfiltered recipe/meal
    # list, distinct from updated_at (catalog-edit recency). Set at
    # recipe creation time too, so a just-created recipe/meal shows at
    # the top immediately rather than waiting for its first log.
    op.add_column(
        "recipes",
        sa.Column("last_logged_at", sa.DateTime(timezone=True), nullable=True),
    )
    # Same backfill reasoning as items.last_logged_at.
    op.execute("UPDATE recipes SET last_logged_at = created_at WHERE last_logged_at IS NULL")


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column("recipes", "last_logged_at")