//! Sizes of the prepared stations-and-addresses files published in the `extras`
//! release, by region, from its manifest of 2026-11-15. Written by
//! tools/bundles/publish.py; do not edit. Sorted by region ID.

/// (region ID, bytes)
pub const PREPARED_EXTRAS: &[(&str, u64)] = &[
    ("ad", 262144),
    ("fj-e", 278528),
    ("jp-shikoku", 2084864),
    ("us-il", 36147200),
    ("us-ri", 2805760),
];
