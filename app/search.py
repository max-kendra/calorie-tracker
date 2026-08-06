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
from sqlalchemy import and_, case, func, or_
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


def relevance_rank(query_text: str, *columns: ColumnElement) -> ColumnElement:
    """
    Ranks rows by how well they match `query_text`, for ORDER BY -
    higher is more relevant. Meant to be used alongside
    multi_column_search_filter, which already narrows the WHERE clause
    down via the same GIN trigram indexes this module's docstring
    describes - by the time this runs, it's only scoring the (already
    small, already-matching) filtered result set, not the whole table,
    so this stays cheap without needing a KNN-capable GiST index for
    the ORDER BY itself.

    Blends two signals:
      - A coarse tier on `columns[0]` (the "primary" identity column -
        e.g. an item's name over its brand, since name is what a
        person is actually recognizing): exact match > starts-with >
        plain substring > no tier match at all. This is what makes
        "banana" rank an item literally named "Banana" above "Banana
        Bread" or "Frozen Banana Chunks", which pure recency or pure
        trigram similarity alone wouldn't reliably do.
      - pg_trgm similarity() across ALL given columns (GREATEST of
        each) as a fine-grained tiebreaker within a tier, and as the
        only signal at all for matches that only hit a secondary
        column (e.g. an item found by brand, not name) or only matched
        via trigram similarity rather than a literal substring (see
        multi_column_search_filter's own docstring on why that path
        exists - typos, singular/plural, etc).

    Callers should still add a final .desc().nullslast() recency
    tiebreaker after this for ties (e.g. two exact-name matches),
    same as the existing unfiltered "recent" ordering already uses.
    """
    primary = columns[0]
    tier = case(
        (func.lower(primary) == query_text.lower(), 3),
        (primary.ilike(f"{query_text}%"), 2),
        (primary.ilike(f"%{query_text}%"), 1),
        else_=0,
    )
    similarity_expr = func.similarity(primary, query_text)
    for column in columns[1:]:
        similarity_expr = func.greatest(similarity_expr, func.similarity(column, query_text))

    return (tier + similarity_expr).desc()