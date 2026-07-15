"""add weight goal to user_profile

Revision ID: a1c3f9e2b4d7
Revises: 2aa290a24ec5
Create Date: 2026-07-15 19:10:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a1c3f9e2b4d7'
down_revision: Union[str, Sequence[str], None] = '2aa290a24ec5'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('user_profile', sa.Column('starting_weight_kg', sa.Numeric(), nullable=True))
    op.add_column('user_profile', sa.Column('goal_weight_kg', sa.Numeric(), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('user_profile', 'goal_weight_kg')
    op.drop_column('user_profile', 'starting_weight_kg')