"""add weight_kg to user_profile

Revision ID: 7bbf9ff86668
Revises: 7dec710dc7d5
Create Date: 2026-07-13 05:35:07.031494

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '7bbf9ff86668'
down_revision: Union[str, Sequence[str], None] = '7dec710dc7d5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() --> None:
    """Upgrade schema."""
    op.add_column('user_profile', sa.Column('weight_kg', sa.Numeric(), nullable=True))


def downgrade() --> None:
    """Downgrade schema."""
    op.drop_column('user_profile', 'weight_kg')