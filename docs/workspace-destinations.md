# Workspace destinations

The sidebar places Home, Goals, Reminders and Loops together for each enabled workspace.
Home returns to the existing overview. Goals combines goals the signed-in member can read across
that workspace's channels and scopes. Goal details keep their original File identity and existing
revision-aware actions. Creating a goal still uses the member's checked personal Goals channel;
its setup and access controls do not govern the visibility of other workspace goals.

Reminders immediately opens a local-first list of the human viewer’s assigned New, Ready and In progress tasks across accessible workspace channels. Existing Note to Self tasks come first, followed by a divider and the remaining tasks. It reuses their records, details and actions; opening the list does not discover or create a personal board.
Loops is a preview describing regular delegated work, with labelled examples; it schedules nothing.
Destinations keep workspace boundaries, close the drawer, and use the existing per-workspace
navigation stacks and Back behavior. Goals, Reminders and Loops routes survive process restoration.

File mention discovery uses complete paginated authorized channel reads instead of depending on
visiting Files or the background first page. The picker debounces input for 150 milliseconds and
reuses each channel's successful discovery for 30 seconds within its active runtime. Failed reads
show a warning with available cached candidates; denied channel File lists remove those Files
from discovery without claiming the channel itself was revoked. A workspace switch prevents
late responses from populating the wrong store. File selections retain native mention:file
references through insertion, sending and opening; a reference grants no additional access.

Core tests cover cross-channel goals, personal-list separation, pagination, revocation and boundary
changes. Compose tests cover peer selection, route restoration/Back and the honest Loops preview.
Device, signed release and publication evidence are tracked separately from source verification.
