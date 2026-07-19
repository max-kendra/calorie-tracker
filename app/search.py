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

This fixes both without a new DB extension/index: split the query into
words, require EVERY word to match somewhere (so multi-word queries act
like an AND of per-word searches, not one rigid phrase), and for each
word also try a simple singular/plural variant (strip or add a trailing
"s"). This is deliberately simple, not a real stemmer or fuzzy-match --
it won't catch genuinely irregular plurals ("berries"/"berry") or typos.
If that turns out to matter in practice, the next step up would be
Postgres's pg_trgm extension for real fuzzy matching, which is a bigger
change (new extension + index) than this warrants right now.
"""
from sqlalchemy import and_, or_
from sqlalchemy.sql.elements import ColumnElement


def multi_column_search_filter(query_text: str, *columns: ColumnElement) -> ColumnElement | None:
    """
    Returns a filter expression requiring every word in `query_text` to
    match (case-insensitively, substring) at least one of `columns`,
    trying both the word as typed and a simple singular/plural variant.
    Returns None if query_text has no words (caller should skip
    filtering entirely in that case, same as the old `if q:` check did).
    """
    words = query_text.split()
    if not words:
        return None

    word_conditions = []
    for word in words:
        variants = {word}
        if word.endswith("s") and len(word) > 1:
            variants.add(word[:-1])
        else:
            variants.add(word + "s")

        column_conditions = [
            column.ilike(f"%{variant}%") for variant in variants for column in columns
        ]
        word_conditions.append(or_(*column_conditions))

    return and_(*word_conditions)