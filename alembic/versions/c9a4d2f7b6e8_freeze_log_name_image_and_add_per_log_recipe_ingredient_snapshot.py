"""freeze log name/image and add per-log recipe ingredient snapshot

Revision ID: c9a4d2f7b6e8
Revises: d5a8c2f7b9e1
Create Date: 2026-07-29 00:00:00.000000

"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision: str = 'c9a4d2f7b6e8'
down_revision: Union[str, Sequence[str], None] = 'd5a8c2f7b9e1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Same snapshot-at-write-time discipline as kcal_logged/protein_g_logged/
    # etc (see Log's own docstring) - item_name/recipe_name/image_path were
    # the one part of a log that stayed LIVE, resolved fresh against the
    # current Item/Recipe row on every read instead of being frozen at
    # create_log time. That meant renaming a recipe retroactively changed
    # the label on every past log referencing it, even though the macros
    # underneath were (correctly) still whatever was true when each one was
    # logged - two logs of the same recipe under two different names/recipes
    # ended up looking identical. These three columns close that gap.
    op.add_column("logs", sa.Column("item_name_logged", sa.String(), nullable=True))
    op.add_column("logs", sa.Column("recipe_name_logged", sa.String(), nullable=True))
    op.add_column("logs", sa.Column("image_path_logged", sa.String(), nullable=True))
    # Distinguishes "this recipe log's ingredients is empty because the
    # recipe genuinely had none" from "empty because this log predates
    # ingredient snapshotting" - both look like an empty list otherwise.
    # False for every pre-existing row (none of them have a snapshot to
    # point to); every log created from here on sets this True at write
    # time, recipe or item alike.
    op.add_column(
        "logs", sa.Column("has_ingredient_snapshot", sa.Boolean(), nullable=False, server_default="false")
    )

    # Best-effort backfill for existing rows: we have no historical name to
    # recover, so this stamps in whatever the item/recipe is CURRENTLY
    # called - a one-time approximation for pre-existing data only. Every
    # log created from here on gets its true value frozen at create_log
    # time. NULL values are treated as "no frozen name available yet" by
    # the API layer, which falls back to a live lookup for those rows only.
    op.execute(
        """
        UPDATE logs
        SET item_name_logged = items.name, image_path_logged = items.image_path
        FROM items
        WHERE logs.item_id = items.item_id
        """
    )
    op.execute(
        """
        UPDATE logs
        SET recipe_name_logged = recipes.name, image_path_logged = recipes.image_path
        FROM recipes
        WHERE logs.recipe_id = recipes.recipe_id
        """
    )

    # Per-log snapshot of what a RECIPE log actually consisted of at the
    # moment it was logged - item, quantity/serving, and the resolved
    # grams+kcal contribution, all frozen. Previously nothing captured
    # this at all: recipe_ingredients (the recipe's CURRENT composition)
    # is fully live/mutable with no history of its own, so editing a
    # recipe's ingredients after logging it silently erased any record of
    # what was actually used in past instances - only the log's aggregate
    # totals survived. This is the recipe-log equivalent of item_id/
    # serving_size_id/quantity on `logs` itself, just one level deeper.
    #
    # item_id/serving_size_id are kept (nullable, ON DELETE SET NULL) for
    # traceability/re-logging convenience, same reasoning as logs.item_id/
    # recipe_id - but display should always prefer the frozen
    # item_name_logged etc, never re-resolve through these ids live.
    #
    # No backfill for existing recipe logs: unlike names (which we can at
    # least approximate from current data), the historical ingredient
    # LIST is genuinely gone for logs created before this migration - the
    # only place it ever lived was the live, now-since-overwritten
    # recipe_ingredients table. Past logs simply get zero snapshot rows;
    # every recipe log created after this migration gets one.
    op.create_table(
        "logged_recipe_ingredients",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("log_id", sa.Integer(), sa.ForeignKey("logs.id", ondelete="CASCADE"), nullable=False),
        sa.Column("item_id", sa.Integer(), sa.ForeignKey("items.item_id", ondelete="SET NULL"), nullable=True),
        sa.Column("item_name_logged", sa.String(), nullable=False),
        sa.Column(
            "serving_size_id", sa.Integer(), sa.ForeignKey("serving_sizes.id", ondelete="SET NULL"), nullable=True
        ),
        sa.Column("serving_size_name_logged", sa.String(), nullable=True),
        sa.Column("serving_size_weight_g_logged", sa.Numeric(), nullable=True),
        # Same dual semantics as logs.quantity/recipe_ingredients.quantity:
        # grams directly if no serving, else a multiplier of
        # serving_size_weight_g_logged.
        sa.Column("quantity", sa.Numeric(), nullable=False),
        # Resolved actual grams for this ingredient AT THE LOGGED
        # quantity of the recipe as a whole (i.e. already scaled by
        # however many servings were consumed) - frozen so the client
        # can display it directly without redoing serving-size math
        # against data that may no longer exist unchanged.
        sa.Column("grams_logged", sa.Numeric(), nullable=False),
        sa.Column("kcal_logged", sa.Numeric(), nullable=False),
    )
    op.create_index(
        "ix_logged_recipe_ingredients_log_id", "logged_recipe_ingredients", ["log_id"]
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index("ix_logged_recipe_ingredients_log_id", table_name="logged_recipe_ingredients")
    op.drop_table("logged_recipe_ingredients")
    op.drop_column("logs", "has_ingredient_snapshot")
    op.drop_column("logs", "image_path_logged")
    op.drop_column("logs", "recipe_name_logged")
    op.drop_column("logs", "item_name_logged")