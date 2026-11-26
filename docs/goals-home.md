# Goals on Home

Home (`AppSection.HOME`, `HomeScreen.kt`) is the workspace's personal landing surface. Deck remains a separate section.

## Layout

- Phone: one column — welcome and launchers, Goals, Daily Briefing, Latest.
- Wide window (≥ 840 dp): Goals beside a 380 dp column holding Daily Briefing and Latest.

## Goals

- Active goals in the shared focus order (`GoalFocus.order`): status, chosen rank, then target date and name.
- The first three are full cards: name, two lines of description, start / checkpoints reached / target, and checkpoint
  dots (filled = reached, ringed = next, open = later; eight at most, then `+N`).
- The fourth is a quieter peek (62% opacity, one line, still tappable; accessibility says "Out of focus").
- Further goals appear in place with "Show all N goals". There is no nested scroll.
- Tapping a card opens the existing goal detail on Home's stack, so Back returns to Home.
- Reorder by long-press drag, the card's menu (Move to top / up / down), accessibility actions or Alt+Up/Down.

### Focus order (`focus_rank`)

Optional `focus_rank` on each goal, kept among the goal's unknown fields so older clients preserve it. The contract is
shared with the Mac app and `scripts/goals.mjs`; `core/src/test/resources/goals/focus-order-v1.json` pins it:

- Alphabet and bounds.
- Greenspan fractional keys.
- Comparator.
- Move plans and rebalance.
- Canonical bytes.

`GoalsController.move` saves a plan one write at a time, top first. Each write is that goal's own expectation-checked
`SetFocusRank`. The first refusal stops the move, reloads, and reports "Saved n of m order changes. …". Every partial
result is a valid order. No backend or record type was added.

## Daily Briefing

`GoalBriefing.facts` uses the viewer's saved active goals only, with the same semantics as the Mac app.

Today shows:

- the next checkpoint of each goal in focus
- start and target dates falling today
- checkpoints dated today, marked "reached now" or "not reached yet"
- past-dated checkpoints that are not reached

Another day shows only what is dated that day, under a note that reached is the status now. The stepper stops at
today. Empty days say so.

Rules:

- No agent voice.
- No inferred progress.
- No reminders or scheduling.
- The footer always reads "From your saved goals. Agent briefings aren't available yet."

## Latest

`LatestChats.load` returns the five most recently active conversations, one card per conversation. It reads the same
conversation index as Chat. It only includes channels whose channel, scope and workspace are still accessible; deleted
and removed records are excluded. Opening a card keeps the thread on Home's stack.

## Goal and checkpoint forms

The forms use the app's full-screen sheet (Cancel · title · Create/Save above the keyboard):

- Labels sit above bordered, left-aligned fields.
- Cursor, selection and focus ring use the primary colour.
- The first field is focused with the keyboard shown.
- Enter in a one-line field moves to the next field; in a description it adds a line.
- Tab and Shift+Tab move between fields and date boxes.
- Esc cancels.
- Ctrl/Cmd+Enter saves.
- Save stays disabled with the reason shown (`GoalForm`) until the values can be saved.

## Validation

- Core: `GoalFocusTests`, `GoalHomeTests`, `GoalsControllerTests`, `LatestChatsTest`.
- JVM Compose: `GoalFormUiTest`, `HomeUiTest`, `HomeRoutingTest`.
- Synthetic emulator: `GoalsHomeFlowTest` sends real key events and records screenshots via the `shotPrefix`
  argument. It runs at phone, small phone with 1.3× text, and 2560×1600 sizes.
