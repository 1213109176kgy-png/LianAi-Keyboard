# Screenshot knowledge and Markdown

User-requested behavior: screenshot replies participate in the selected knowledge document just like text replies. Only explicit no-selection bypasses reference data. Documents remain plain Markdown files with a rendered reading mode and lossless raw editing mode.

Screenshot pipeline: existing vision route extracts context and preliminary replies; knowledge retrieval uses recognized message/scenario/analysis/replies; the existing reply text route produces final replies with bounded reference passages. Neither official nor custom vision requires a backend contract change. Missing read/model configuration fails visibly. Stale results are rejected on selection/session/settings/document changes. No-selection retains the single vision request.

Retrieval falls back to opening guidance when lexical overlap is absent. Render with native Markwon (headings, emphasis, lists, quotes, code, tables, task lists), no HTML plugin/network images. Existing documents open in reading mode; edit mode preserves raw source and reading renders current unsaved text in a background worker. List snippets render Markdown too.

Verification: unit retrieval budget/scope/fallback checks; device request-body tests for screenshot final pass and no-selection bypass; Markdown span and unsaved-preview/source preservation checks; production signed build and device visual check.
