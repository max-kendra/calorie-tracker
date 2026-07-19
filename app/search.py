"""
Shared search-matching helper for name/brand lookups across items and
recipes -- previously each endpoint did a single ILIKE '%q%' against one
or two columns, which is an exact contiguous-substring match. That fails
in two common ways a person doesn't expect:
  - typing "pancakes" finds nothing if the stored name is "Pancake"
    (singular), or vice versa -- neither string contains the other as a
    substring
  - typing "choc bar" finds nothing for an item named "Chocolate Protein
    Bar", since "choc bar" isn't a contiguous substring of that name

Split the query into words and require EVERY word to match somewhere (so
multi-word queries act like an AND of per-word searches, not one rigid
phrase). For each word/column pair, match if EITHER:
  - the word is a plain substring (ILIKE) - the fast, always-correct
    path for exact/partial matches, kept as a guaranteed fallback
  - Postgres's pg_trgm similarity() scores it as similar enough - this
    is what actually catches "pancakes" vs "Pancake", typos, and other
    near-misses that aren't literal substrings, without needing manual
    stemming rules for every case

Requires the pg_trgm extension (see migration
f3a8c1d9e0b2_enable_pg_trgm_and_add_trigram_indexes) - installed there
along with GIN trigram indexes on the searched columns, so this stays
fast as the catalog grows rather than falling back to a sequential scan
the way plain ILIKE always has to for a leading-wildcard pattern.
"""
from sqlalchemy import and_, func, or_
from sqlalchemy.sql.elements import ColumnElement

# Below this, similarity() starts treating unrelated words as "similar
# enough" too often (short strings share trigrams more easily by
# chance) - 0.25 was picked by trying it against real food names/typos
# and is a reasonable middle ground, not a formally derived constant.
SIMILARITY_THRESHOLD = 0.25


def multi_column_search_filter(query_text: str, *columns: ColumnElement) -> ColumnElement | None:
    """
    Returns a filter expression requiring every word in `query_text` to
    match (substring OR trigram-similar) at least one of `columns`.
    Returns None if query_text has no words (caller should skip
    filtering entirely in that case, same as the old `if q:` check did).
    """
    words = query_text.split()
    if not words:
        return None

    word_conditions = []
    for word in words:
        like = f"%{word}%"
        column_conditions = []
        for column in columns:
            column_conditions.append(column.ilike(like))
            column_conditions.append(func.similarity(column, word) > SIMILARITY_THRESHOLD)
        word_conditions.append(or_(*column_conditions))

    return and_(*word_conditions)