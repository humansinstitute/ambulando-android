# Direct-message display names

Generated DM titles (`DM`, `DM: …` or a stored public key) resolve from the exact stored participants and the viewer's
persisted identity. They use the cached kind0 `display_name`, then `name`, then a usable existing directory name.
When no usable name exists, a complete validated participant npub is the fallback. Missing or invalid participant
information renders as “Direct message · identity unavailable”. A one-participant conversation with the viewer is “Note to self”.
Custom conversation titles remain unchanged. Key-shaped names, including names embedding an npub or a 64-digit
hex public key, are not ordinary identity labels. Explicit identity details still show the exact public key.

The header, sidebar, composer, channel lists, Deck Inbox heading/activity labels/channel pins and related
document/file/search labels resolve display text without
rewriting channel records or participant identities. Profile-store changes refresh these displays. Composer state,
chat target, persisted drafts, history, routing and account boundaries remain keyed by their original identifiers.
Public profiles decorate existing people; they do not establish membership or authority.

Regression coverage includes cached non-member profile precedence, valid-npub fallback, malformed and unrelated
identity rejection, custom titles and exact participants. `DeckProfileInvalidationTest` checks connection-wide profile
notifications; `DmProfileNamesTest` exercises profile arrival and renaming in the header, sidebar, composer, Deck Inbox
heading, activity labels and an existing channel pin, preserving the typed draft and pin destination. Run the latter
on a dedicated emulator with synthetic sample data; it does not establish live Tower/relay acceptance.
