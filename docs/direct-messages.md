# Direct-message display names

Generated DM titles (`DM`, `DM: …` or a stored public key) resolve from the exact stored participants and the viewer's
persisted identity. They use the cached kind0 `display_name`, then `name`, then a usable existing directory name.
Missing or unusable person names render as “Unknown person”; missing participant information renders as
“Direct message · identity unavailable”. A one-participant conversation with the viewer is “Note to self”.
Custom conversation titles remain unchanged. Key-shaped names, including names embedding an npub or a 64-digit
hex public key, are not ordinary identity labels. Explicit identity details still show the exact public key.

The header, sidebar, composer, channel lists and related document/file/search labels resolve display text without
rewriting channel records or participant identities. Profile-store changes refresh these displays. Composer state,
chat target, persisted drafts, history, routing and account boundaries remain keyed by their original identifiers.
Public profiles decorate existing people; they do not establish membership or authority.

Regression coverage includes profile name precedence and neutral fallback, custom titles and exact participants,
and a dedicated-emulator flow that observes profile arrival and updates while retaining a typed DM draft.
