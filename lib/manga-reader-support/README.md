# Manga reading policies

Pure Kotlin window, viewport-budget, spread, chapter-transition and anchor policies. NG retains chapter ownership, persistent progress and Compose rendering. Policies never read NG globals or delete files.

Zero disables background work. Chapter windows clamp to valid chapters; image budgets count original pages rather than spread slots. Spread pairing stays within one chapter and leaves markers/odd tails alone. Transition requests lock until matching content revision succeeds or fails; progress is committed only after the target content is ready.

Run `:lib:manga-reader-support:test`. UI/device/performance validation is separate from deterministic policy tests.

Empty volume headings are skipped without changing progress; traversal stops after 32 headings or the catalog boundary. Previous-chapter landing uses the actual image count of the eventual target. Reset/failure discards the pending transition.
