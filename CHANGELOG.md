## 0.9.80 (108)

- Open Reminders immediately from saved data, with your assigned New, Ready and In progress tasks across the current workspace. Note to Self comes first, followed by a divider and other tasks.
- Search people and agents by name when assigning a task. Show avatars and initials, keep selection separate from Save, and support keyboard selection and cancellation.
- Reflect status, reassignment and access changes in the list, preserve failed-write rollback, and return to Reminders when closing task detail.

## 0.9.79 (107)

- Chief of Staff schedule setup now runs in the workspace owner's Autopilot space when a delegated manager saves, changes or removes the assignment. The owner keeps using their own space.
- Delegated-manager provenance uses only the verified workspace owner identity or the server's `can_manage_workspace` authority; role labels and group names never count.
- Keep unknown fields in the Chief of Staff assignment when it is saved again, so newer clients' settings are not lost. Private cohort fields are refused in the shared assignment.

## 0.9.78 (106)

- Put Home, Goals, Reminders and Loops together in each workspace sidebar. Goals combines accessible goals across the workspace; Reminders reuses your private Note to self board. Loops is a labelled preview of regular delegated work.
- Find whiteboards and other File artefacts from chat @ autocomplete without first visiting Files, including files beyond the first page. Keep native File references through selection, sending and opening.
- Select an artefact before the first chat message, keep the chosen revision and draft while switching views, and insert its native reference with Tag. Exact artefact context is saved with Send; agent receipt requires compatible server support.
- Add native Chief of Staff assignment controls and a topic reader with explicit restricted, setup and unsupported-server states. These screens require configured server capabilities; source inclusion does not activate a running agent loop.

## 0.9.77 (105)

- Save a chat or selected message with **Remind me** as a Ready task assigned to you on your existing private Note to self board. Keep the chat title, message formatting and a link back to the source.
- Open that board from the pinned **Reminders** shortcut above the channel list. Failed saves keep the same request for retry and show success only after the server confirms persistence.
- Open source-message references through an authenticated read and scroll to the selected message, including messages outside a thread.

## 0.9.76 (104)

- Stop showing "Attachments refresh failed. Reconnect and Retry." when returning to a thread with attachments. A refresh interrupted by leaving the screen is no longer reported as a failure, and the warning clears once a later refresh succeeds.
- A real refresh failure now names its cause instead of assuming a lost connection. Recovery clears only the refresh warning, never an editor, save or attachment failure. Drafts, attachments and Send safeguards are unchanged.

## 0.9.75 (103)

- Open HTML reports with private images without rejecting the rendered image loader as changed saved source. Saved-source byte and hash checks remain enforced.
- Show unavailable revision status and Retry when an HTML read fails.

## 0.9.74 (102)

- Restore private Daily Briefing reads when an authorized author has a custom permission bundle. Validate exact identities and explicit permissions rather than access-level labels.
- Keep saved dated briefings available through history while rejecting incomplete access lists, extra readers and public storage. The publishing helper preserves prior days across retries and concurrent saves.

## 0.9.73 (101)

- Support normalized PostgreSQL Goals when the workspace activates the new storage contract, keeping existing Goal IDs, saved mentions, discussions, pins and checkpoint order.
- Retain cached Goals during migration or a temporary storage outage. Readiness for loading Goals stays independent of readiness for saving.
- Recover uncertain Goal saves from the exact durable request so retrying a create after reopening does not add a duplicate.

## 0.9.70 (98)

- Start a goal discussion in its saved default channel, or choose an accessible channel explicitly. New chats retain their goal reference; uncertain starts are recovered without creating a duplicate.
- Pin key original Docs and Files separately from associated outputs. Docs and artefacts created inside a goal discussion retain native identities and reference that goal.
- Refresh the scoped Deck inbox when channel navigation finishes loading, so existing discussions do not need a channel detour.

## 0.9.69 (97)

- Mention personal goals from chat, task comments and document comments. Browse your Goals home with `@goal:` or type a multiword goal title, then open the saved reference in the native goal view.
- Show loading and read failures separately from an empty goal search, and discard superseded results. Large homes still require complete server pagination; incomplete lists are reported rather than silently cut off.

## 0.9.68 (96)

- Open existing Goals during workspace preparation: read independently of write readiness, restore verified older downloaded content, and hydrate automatically when reads resume.

## 0.9.67 (95)

- Home’s Daily Briefing reads your private saved morning overview: progress, changes, attention and suggested focus, with the author, Perth publication time and useful record links.
- Browse saved days without substituting today’s goal status for historical analysis. Pending, missing and unavailable briefings are explicit; a previous briefing keeps its date.
- Use existing private channels and immutable File versions with strict owner/author grants and verified content. The companion agent publisher preserves earlier days, rejects conflicting duplicates and verifies publication readback. Goals permissions and records stay unchanged.

## 0.9.66 (94)

- Open saved Goals and previously verified napplet code and data from durable local content while the app refreshes in the background. Keep usable content visible when offline or a temporary refresh fails.
- Cover File napplets and installed v3 apps while retaining their existing sandbox, trust, access and version checks. New saved data can be reloaded without silently replacing unsaved edits; saving still uses each app's online permission and revision contract.
- Replace the workspace-confirmation waiting message with normal first-load and reconnect states when content has not yet been saved on the device.

## 0.9.64 (92)

## 0.9.65 (93)

- Browser Nostr approvals offer Allow once, Always allow for this site, and Deny.
- Remember encryption, decryption and each signing kind independently for the exact site and account; revoke them in the browser menu.
- Preserve lock, document and account authorization checks while remembered permissions survive restart.


- Use one compact mobile conversation header, with Back, thread identity and Artefacts directly accessible. Pin, Add to goal and global navigation actions move into the labelled thread menu.
- In a thread’s HTML, whiteboard and document views, keep one navigation bar with Artefacts and Discuss this. Move secondary versions and comment actions into menus; preserve editor Save, drafts, historical revisions and shared attachment behavior. Tablet layouts remain unchanged.

## 0.9.63 (91)

- An artefact attached to a conversation that can no longer be opened (for example after it was updated elsewhere, or while Tower or the network fails) no longer freezes that conversation. Back, Send and the rest of the app keep working, including after closing and reopening Ambulando, and your drafts are kept.
- The artefact shows why it could not be opened, with Retry; messages you send meanwhile leave it out and say so ("Sent without …"). Unsaved edits in an artefact are still saved, or Send stops and keeps them, as before.
- Includes 0.9.62 (90): Goals on Home, keyboard-friendly goal forms and the shared goal focus order.

## 0.9.62 (90)

- Home is its own screen again, separate from Deck: your goals, a Daily Briefing and your latest chats. Opening Ambulando without a scope or channel chosen lands there; choosing a scope or channel opens Deck as before.
- The three goals in focus show their description, dates, checkpoints reached and progress dots, with a quieter peek at the next and "Show all" for the rest. Drag a goal or use its menu to change which goals are in focus; the order syncs with the Mac app.
- The Daily Briefing lists only what your saved goals say for the day (next checkpoints, dated checkpoints, start and target dates). It never speaks for an agent or schedules anything; agent briefings are not available yet.
- The goal and checkpoint forms now have labels above clear, left-aligned fields with a visible cursor, open with the first field ready to type, and work with a keyboard: Enter moves on (or starts a new line in a description), Tab and Shift+Tab move between fields, Esc cancels and Ctrl+Enter saves. Save explains what is missing instead of failing after you press it.

## 0.9.61 (89)

- Share a thread's attached Docs, whiteboards and HTML across supported clients, while keeping each device's editor, historical revision and viewport independent.
- Open attachments from the icon beside Pin; remove the bottom switch so it no longer covers the chat. Remove from thread changes only the association and preserves source records and local recovery.
- Preserve concurrent metadata changes and detached drafts, require explicit source Save before publishing retained dirty recovery, and retain closed historical views beyond the open-tab limit.

## 0.9.60 (88)

- Add personal Goals from Home: a name, description and optional start and target dates, an ordered editable checkpoint timeline you can mark reached, and links to existing chats, tasks, Docs and Files from any channel you can read.
- Complete, reopen, archive and restore a goal. Goals are saved as versioned Files in a private Goals channel shared only with you and the agents you choose, so an agent's change and yours are both kept and a stale save is refused rather than overwritten.
- Goals that other apps wrote with unexpected fields are shown as stored and never erased by a later save; deleted goal files are hidden.

## 0.9.56 (84)

- Restore Chat by default while respecting saved visibility choices, with a compact conversation inbox across Home, scope and channel.
- Search cached conversation titles and replies using exact words, quoted phrases and title/body fields; open the matching message and see history download coverage.
- Keep threads in a compact full-screen view with channel, participants, existing actions and the anchored reply composer.

## 0.9.55 (83)

- Keep workspace settings, scope/channel configuration and full-screen form controls clear of Android status bars, display cutouts, navigation bars and the keyboard.
- Apply each safe-area inset once, including nested configuration pages and sheets.

## 0.9.54 (82)

- Fix voice recording startup crashes when recording is cancelled before service delivery or its notification channel is disabled.
- Prepare the foreground notification before validating startup, without opening the microphone; keep permission failures, retained audio and notification Stop safe.

## 0.9.53 (81)

- Pay the setup deposit by Lightning as well as card when your NStack offers it: the invoice, amount in sats and expiry come from NStack, a payment counts only when NStack records it, and setup never starts a workspace or agent by itself.
- A setup keeps one payment method until NStack has finished with that payment, so a lost connection, restart or second phone can never start a second payment. A new phone resumes the payment NStack already holds, and "Use a different payment method" asks NStack to settle it first.

## 0.9.52 (80)

- Add guided hosted-workspace setup with an invitation, using the identity already on this device (Connect and Settings › Hosted workspace).
- Review before anything is sent; progress and retry keys survive restarts; payment counts only when NStack confirms it.
- Card payments, Lightning and funding limits follow what your NStack reports it can do now; when card is off you see "Card payments are unavailable right now. Your setup is saved; try again later or contact your inviter."
- A chosen agent is shown as "chosen but not running" and stays off until you send its first request.
- New identities must be backed up to your NStack, locked with a password you choose (no reset if lost), before any payment. Recover an identity on a new phone with the backup username and password.
- Add credit to your hosted account after setup (Settings › Hosted workspace) by card or Lightning: no new workspace, invitation or agent start. A payment that is under way keeps its amount and method until NStack reports it finished, so a lost connection can never start a second payment; credit appears only when NStack confirms it.

## 0.9.51 (79)

- Restore the previously approved Android navigation: icons above labels, original sizing and responsive button widths.
- Correct the unintended inline Android navigation in 0.9.50; retain Home, selection, routing and horizontal scrolling.

## 0.9.50 (78)

- Place small navigation icons beside their names in a compact single-line row.
- Keep full labels at larger text sizes, horizontal access on narrow phones, and comfortable touch targets.

## 0.9.49 (77)

- Choose an Android microphone or System default before recording; see the requested input and actual Android route separately.
- Show a live captured-input level with a calm silence state and microphone privacy warning.
- Keep the microphone choice locked during capture so choosing another device cannot discard the current clip.

## 0.9.47 (75)

- Hide Chat from navigation by default; restore its existing position with Settings › Sections › Show Chat in navigation.
- Keep Deck, open conversations and explicit conversation links working, and save the choice on this device across restarts and updates.

## 0.9.46 (74)

- Scroll the entire bottom navigation strip, including a leftmost globe that opens Home across accessible scopes and channels in the current workspace.
- Use larger controls with a partial next button on phones and comfortable fixed widths on wider screens.
- Reset navigation strip position when changing screens or reloading, while retaining it during live updates and optional-section changes.
- Highlight workspace Home at the unfiltered Deck root; explicitly leave a locked scoped view when using the globe.

## 0.9.45 (73)

- Load busy-channel agent history in resumable background pages without treating a valid backlog as a malformed server response.
- Keep current activity responsive while older lifecycle and missed commentary pages catch up, retaining offline history across interruptions.

## 0.9.44 (72)

- Show agent activity as a compact disclosure after the newest replies, with a bounded live preview.
- Hide completed commentary by default and reset expanded history on completion while preserving retained updates.
- Keep activity scrolling tied to the reader’s position and preserve explicit history expansion during row recycling.

## 0.9.43 (71)

- Pause app discovery and host authority checks while hidden or backgrounded.
- Preserve HTML recovery drafts through close/save races and keep retained artefacts readable without granting server access.
- Restore saved File download/share controls for rich artefacts and improve search labels and Deck touch targets.
- Resolve board comment authors and workspace member labels from exact cached profiles without changing saved identities.
- Wait for validated workspace readiness before report discovery and keep setup conservative after failures.

## 0.9.42 (70)

- Show current conversation agent status, latest public updates and expandable ordered history beside the triggering message.
- Retain completed public history and recover missed updates after reconnect, with exact conversation/turn isolation and server lease expiry.
- Keep Agents and Deck working badges tied to the current live turn; expire cached Deck badges offline without a new database event.
- Clarify the composer’s coloured destination marker as “Workspace:” independently of agent status.
- Resolve direct-message names from exact participant profiles across conversation headers, the composer, Deck cards and pins while preserving targets and drafts.

## 0.9.41 (69)

- Give whiteboards, ordinary HTML and System Flows a compact toolbar with an ellipsis menu for versions, imported-source details and original-source access.
- Remove permanent secondary action/source strips and clean whiteboard version chips, retaining primary editing/save controls, drafts and contextual read-only/conflict information.
- Use the same focused editors from Files and Artefacts, with Back returning to the originating list.

## 0.9.38 (66)

- Add controlled document/working-brief, whiteboard, interactive HTML and System Flow starters, with maintained styles for rich artefacts.
- Edit typed System Flow nodes and relationships in a focused native editor with a diagram overview, durable drafts, immutable history and conflict protection.
- Preserve complete schema 1–3 flow sources and linked scenes; show migration provenance and original-source links for richer legacy editing and feedback.
- Render compatible pending embedded whiteboard images after scene loading, without rewriting saved source on open.
- Keep HTML sandbox restrictions and existing NStack File storage and permissions. Forms and unsupported legacy functions still require their original editor.

## 0.9.37 (65)

- Add a generic v2 File napplet runtime: Apps installs existing code Files through personal pins, uses manifest titles/icons and versioned capabilities, and checks for updates on the same File without a per-app build. Private JSON uses bounded canonical CAS; changed code/access prompts for separate v2 approval. Existing v1 tracker/report and saved data remain compatible.

- Add Workspace Report to Apps and its direct workspace route, reusing a private Note to self code File.
- Show authorized channel activity, current inventory, task states, coverage and ranking remainder through a separate read-only capability.
- Remember approval for unchanged verified report code and workspace access; clear aggregates when the host loses authority or backgrounds.
- Require the connected Tower’s report API. Missing routes, offline state and invalid snapshots show unavailable instead of invented totals.
- Retain the Artefacts creation improvements from build 64 and the existing Daily Metrics behavior.

## 0.9.36 (64)

- Make the prominent Artefacts + offer Document, Whiteboard and HTML together, with one creation action.
- Keep creation in the selected channel or scope and show pending rich artefacts with retry status.
- Preserve document storage, whiteboard drawing and version history, and isolated HTML source editing.

## 0.9.35 (63)

- Improve Daily Metrics phone layout with larger answer buttons, clearer dates and save status, and suitable number keyboards.
- Offer an explicit Update app action for the recognized original tracker code, preserving saved answers, history and File bindings.
- Ask for approval before running updated code; protect pending saves and unsaved edits, and leave customized trackers unchanged.

## 0.9.34 (62)

- Remember explicit napplet approval on this device for unchanged verified code and bound access.
- Check current permissions on every open; ask again for changed code or access while ordinary tracker edits preserve approval.
- Add Forget approval with unsaved-edit confirmation, leaving saved tracker data intact.

## 0.9.33 (61)

- Find Daily Metrics in Apps before setting up a tracker, then open it directly without visiting Artefacts.
- Reuse existing private tracker Files and answers, including trackers created from Artefacts; choose when several exist.
- Offer explicit empty setup only after a complete successful search finds no tracker; keep existing data and lifecycle safeguards.

## 0.9.32 (60)

- Create an empty daily tracker in Note to self, with its HTML code and JSON records saved as separate NStack Files.
- Run the same daily yes/no and number tracker bundle as the browser, with explicit code trust, private File binding and conflict-checked saves.
- Keep ordinary HTML isolated; the tracker receives no signing key, arbitrary request API or direct network access.

## 0.9.31 (59)

- Focus whiteboards opened from Files or Artefacts on one native Back/title/status/options toolbar, with more space for drawing.
- Restore app navigation on exit and retain the existing local draft and list navigation behavior.

## 0.9.30 (58)

- Recover the consented Garmin watch listener when returning to Ambulando after the listener stops, without restarting audio or replaying a Start.
- End interrupted watch requests cleanly so a lost wrist receipt cannot block future recordings; keep interrupted audio for review.
- Show current watch readiness separately from the last request and distinguish listener and consent failures in diagnostics.

## 0.9.29 (57)

- Show the Excalidraw drawing and toolbar when opening a whiteboard from Files or Artefacts.
- Give embedded editors a full viewport so percentage-height pages cannot collapse inside the native layout.

## 0.9.28 (56)

- Find documents, whiteboards and HTML together in the channel’s Artefacts section, with title and type filters.
- Create whiteboard and HTML Files, pin them, and open them directly without a canvas.
- Reuse the shared Excalidraw editor and saved versions; view HTML in the isolated viewer and edit its source with durable drafts and stale-save protection.
- Keep the Artefacts channel and filters when opening an item and returning to the list. Existing documents and canvases remain available.

## 0.9.27 (55)

- Channel Inbox menus now offer Channel options for the current available channel, alongside the existing mark-as-read actions.
- Android Back returns to Deck after opening a chat there, preserving its card, filters and scroll position.
- Chat-list navigation retains its existing Back behavior.

## 0.9.25 (53)

- After a successful explicit QR verification of the already active workspace, retry only its failed pins, attention preference and notification reads. Keep each warning until its read succeeds.
- Fence recovery to the original account, connection, workspace and proof context; preserve unrelated failures and pending preference edits without sending them.
- Add a fixed pins category to the bounded local active-failure diagnostics.

## 0.9.24 (52)

- Add bounded, process-local fixed job categories for failed active workspace status observations to diagnose a warning that persists after healthy reads.
- Diagnostic release only; recovery and warning behavior are unchanged.

## 0.9.23 (51)

- After explicit workspace verification, retry a previously failed connection listing and clear its warning only when that read succeeds.
- Preserve unrelated connection errors and reject verification completed after an account or workspace context change.

## 0.9.22 (50)

- Preserve trusted pending native approval in memory so explicit workspace confirmation can recover after a background connection check.
- Revalidate approval ownership and connection binding before a single UI handoff; discard stale or canceled approval without replaying requests.
- Show the current connection transport accurately in Settings.

## 0.9.21 (49)

- Distinguish queued uploads waiting for connection validation from active sending, and show safe connection validation and grant guidance.
- Avoid reusing idle shared FIPS connections while retaining active streams and ordinary HTTPS pooling.
- Keep shared connection requests within the grant-owned cancellation boundary, including calls waiting for DNS before a socket exists.
- Add bounded, process-local categorical observations for write admission, validation, shared connection checks and upload stages without recording request or user data.

## 0.9.20 (48)

- Trust separately enrolled stable and nightly Nostr Browser providers using exact package, authority and current release certificate checks.
- Require one trusted provider to be installed; explain how to resolve ambiguity without switching providers or transport.
- Invalidate existing transport clients and close their I/O when the bound provider changes, requiring a restart and fresh connection verification.

## 0.9.19 (47)

- Preview signed shared workspace QR connections, verify the exact backend and workspace, and retain app-specific access checks.
- Reuse the consented shared Android FIPS VPN with peer pinning and fail-closed transport, including cancellation when access or the network changes.
- Reject noncanonical FIPS host aliases before ordinary HTTP, streams or file networking.

## 0.9.18 (46)

- Make message URLs, named Markdown links and internal document links clearly clickable with accessible theme-aware blue text and underlines.
- Preserve existing navigation, text selection, mention chips, inline code and formatting.

## 0.9.16 (44)

- Support confirmed Nostr signer handoffs, explicit JavaScript prompts and leave-page dialogs, and fullscreen content with Back cleanup.
- Stream HTTPS downloads up to 1 GiB and page-created exports up to 256 MiB, including programmatic blobs and an explicit POST/bearer response export API.
- Add per-page Android-backed camera, microphone and location consent, capture inputs and bounded folder uploads with relative paths.
- Restore allowed GET tabs and selection after a cold browser launch without replaying POSTs or restoring signing grants.
- Release cancelled sync slots even when cancellation happens before their IO work starts.

## 0.9.15 (43)

- Open channels selected from either Android sidebar in Deck view while retaining explicit links to conversations and records.
- Add workspace Settings switches for the item types in Unread and Mentions, synced through private account preferences across Android, web, iPhone and Mac.
- Keep hidden items unread, preserve all-record navigation, and retain offline switch choices with conflict-safe synchronization.

## 0.9.14 (42)

- Give browser pages a definite viewport so full-height private dashboards and nested frames remain visible.
- Preserve browser cookies, local notes, native Nostr consent and isolated website data.

## 0.9.13 (41)

- Support NIP-44 v2 self and peer encryption/decryption in the embedded browser using the active unlocked account.
- Ask for each encryption/decryption operation and discard results after account, lock, document or permission changes.
- Preserve existing signing permissions, private workspace indexes, app identity and stored data.

## 0.9.12 (40)

- Keep opener-written blank popups visible, preserve native popup/opener and POST behavior, return to the opener when closing, and refuse excess popups without evicting existing tabs.
- Accept browser-valid HTTPS query characters without weakening origin checks; retain normal redirect/history handling and the shared isolated browser session.
- Show main-page network, HTTP and certificate failures with retry guidance while keeping TLS validation and HTTPS-only navigation. Honor mobile viewport metadata.

## 0.9.11 (39)

- Prepare the configured Firebase Messaging release while preserving current discovery, voice and Garmin behavior. Actual push availability also requires matching Tower configuration, Android permission and workspace opt-in.

## 0.9.10 (38)

- Add a clean Ambulando Voice status/prompt screen with separate setup, an editable default instruction included with watch audio, and prompt pinning through retained drafts and retries. The Android app remains Ambulando.

- Add explicit, destination-specific watch control consent and a persistent Bluetooth listener with a Stop action, preserving saved setup and retained audio.
- Fresh wrist Start verifies an application nonce receipt, opens the real recorder over the lock screen with one-time Android screen-opening access, then automatically starts a verified Bluetooth microphone after the screen is resumed and focused.
- Keep recording and the original conversation's delivery active when the screen sleeps; Finish retains deterministic upload/message replay protection and waits for server acknowledgement.
- Reject stale requests after setup/restart; recheck permissions and consent before capture. Expose connection receipts and counters in secondary diagnostics.
- Preserve the watch39 value-based String validation fix, canonical Garmin IPC types, existing association, and the legacy explicit phone recording path when automatic watch control is off.

## 0.9.9 (37)

- Replace the empty optional watch-device scan with phone confirmation; preserve existing Garmin pairing and valid saved Android associations.
- Open a visible recorder with an explicit Start button, permission retry guidance and clear Bluetooth microphone errors.
- Keep Start pending when notifications or automatic opening are unavailable; expose saved request state and resend the latest phone reply.
- Distinguish Garmin Connect accepting a reply from confirmed wrist receipt; preserve terminal replay protection and retained audio.

## 0.9.8 (36)

- Fix Garmin setup when paired-watch entries have unknown cached status; query current connectivity separately and allow a known watch to be saved while offline.
- Validate changed or unavailable destinations and watches with clear selection errors; preserve the saved binding and retained drafts during connection loss.
- Wait for SDK readiness, renew registration on explicit reconnect, and distinguish local phone readiness from actual watch-message receipt with timeout/retry guidance.
- Keep manual Tower entry below system bars and make both its Back button and Android Back return to discovered Towers.

## 0.9.7 (35)

- Discover compatible private cross-app Tower indexes after sign-in/unlock; choose a Tower, then explicitly enable workspaces.
- Publish native private workspace connection indexes after verified selection, with exact relay readback and durable retry.
- Preserve existing multiple-Tower partitions, enabled workspace preferences, drafts and queued work; retain manual HTTPS entry.

## 0.9.6 (34)

- Fix Garmin incoming-message crashes in minified releases by preserving cross-process Parcelable identities and preparing the SDK receiver classloader.
- Accept Garmin's canonical uppercase recorder app UUID while rejecting other apps and devices; ignore malformed SDK messages without clearing setup.
- Move one-time watch setup out of chat into Settings, with searchable workspace, destination and connected-watch pickers.
- Keep the saved destination across restarts and navigation, expose permissions/reconnect/optional screen access/change/disable controls, and retain failed drafts when configuration changes.

## 0.9.5 (33)

- Add Garmin recorder companion integration with explicitly bound watch and original conversation.
- Require verified Bluetooth microphone routing for watch recordings; retain stopped audio on failure and send through the existing authenticated outbox.
- Expose waiting, recording, sending, server-confirmed sent and error status with persistent session replay protection.
- Offer phone confirmation and optional companion association for a visible recording screen; physical watch/headset compatibility still requires device verification.
- Include Vivoactive 6 Monkey C source and reproducible packaging instructions; Garmin device profiles and store submission require the developer's account.

## 0.9.4 (32)

- Accept keyboard image commits and clipboard/context-menu image Paste through private attachment uploads.
- Keep image read grants until staging finishes and clean up cancelled late reads.
- Edit chat messages in the main composer with mentions, attachment/paste controls, Save and Cancel.
- Restore reply drafts and selection after edits, isolate cancelled uploads, and show queued edit retry/conflict controls.

## 0.9.3 (31)

- Send voice recordings directly as private message attachments, avoiding a conflicting File registration.
- Retain audio until acknowledgement and recover saved new-thread sends with an explicit Retry.
- Recover prior rejected audio through its verified original channel File, preserving signature and request identity.
- Show sanitized message endpoint, HTTP status and error code for rejected sends.

## 0.9.2 (30)

- Put photo/file attachments, clipboard attachments and microphone recording in one anchored + menu above the always-visible @ control.
- Remove the idle voice row; retain recording, review, retry and cancellation controls with the original destination.
- Use 48dp composer controls and preserve typed drafts while opening or dismissing the menu.
- Keep the editor visible above the keyboard on compact screens; scroll suggestions and recording details in the remaining space.

## 0.9.1 (29)

- Show cached kind0 profile names in generated DM headers, sidebar labels, chat composer and related channel labels; refresh them when profiles arrive or change.
- Use neutral labels when identity names are unavailable, while retaining custom conversation titles, exact participants, navigation, history and drafts.
- Retain the native voice recorder introduced in 0.9.0; real-account upload and physical-device recording remain for field review.

## 0.9.0 (28)

- Record native AAC voice messages, with elapsed time, stop, playback, send and cancel in channel chat.
- Continue recording in a microphone foreground service when navigating, switching apps or turning the screen off, with a visible notification and Stop action.
- Keep the original account/channel/thread destination and stopped audio for retry; reuse private Tower storage, channel Files and the durable chat outbox without clearing typed drafts.
- Recover stopped recording state after relaunch; interrupted, unfinished M4A recordings may be unrecoverable. Android force-stop ends recording.

## 0.8.7 (27)

- Add explicit Save to Files for attachments, including APKs and generic binaries, using Android’s document picker independently of Preview and Share.
- Preserve the suggested filename and checksum-verified bytes; report save cancellation and failures without opening or installing APKs.
- Correct APK MIME and infer known types when metadata is generic, retaining existing preview and sharing actions.

## 0.8.6 (26)

- Keep workspace selection clear of system bars and display cutouts, with Done fixed above the scrolling list.
- Preserve workspace enable/colour changes and local content when dismissing the chooser.

## 0.8.4 (24) — push candidate, configuration required

- Add optional Google-free UnifiedPush setup, Android notification permission/channel handling and per-workspace device enrollment.
- Validate encrypted push hints against stored connections and fresh inbox access; show generic alerts and revalidate taps after unlock.
- Add device opt-out, endpoint renewal, lease/revocation handling and bounded event-triggered delivery follow-up.
- Document exact phone/server setup and the optional read-only credential required for locked/cold alerts.
- Requires matching configured Tower support and real provider/device verification before a working-push release claim.

## 0.8.3 (23)

- Create a new Nostr identity directly on the phone and immediately save it in the PIN-protected, Android Keystore-backed vault.
- Explain device-only recovery limits and new-identity workspace invitations before creation; preserve existing import and account storage.

## 0.8.1 (21)

- Conversations include inherited branch history while preserving canonical ownership and attachment metadata.
- Inherited rows are labelled and read only; nested branches use the conversation being viewed.
- Load earlier messages in 50-row steps without a fixed history ceiling.
- Includes the existing document reading/menu/comment layout refinements.

# 0.8.0 — Android build 20

Visual and tablet parity with the iPhone, iPad and Mac apps.

- **Chat and Deck** are drawn from the Swift components: message cards with avatar, name, time and sync dot, the overflow menu, reaction pills,
  reply bar and day separators; a filled composer; tinted Deck inbox cards with kind icon, kicker, time and unread edge, icon filters,
  search and Hello app cards. Wide windows keep an 860dp reading width.
- **Tablet shell**: a window 760dp or wider keeps the sections and the Home / scopes / channels list in a permanent sidebar (the menu button
  folds it away); narrower windows keep the drawer and bottom bar. Resizing keeps each section's state.
- **New thread** in Deck (floating + and the Inbox +): pick a channel (preselected from the context), write the first message in the channel's
  own composer and the thread Tower creates for exactly that send opens; a replayed request never duplicates it, and rotation keeps it.
- The composer starts two lines tall with text at the top; Canvas and Pinned rows follow the Swift rows.
- Typing in the text-attachment editor no longer re-splits the page being edited under the keyboard.

Not in this build: Android OS push (Tower has no Android registration contract). Tested with unit tests and a synthetic emulator
account, not a live Tower, a physical phone or real accounts; a few emulator tests still fail intermittently on this host (docs/parity-matrix.md).

# 0.7.1 — Android build 19

Stabilisation of chat, document saving and the text editor after 0.7.0.

- An open message edit or delete dialog no longer disappears, taking the typed edit with it, when its message scrolls out of the list or the
  keyboard resizes it; the dialogs close if access to the conversation is removed. A sent message is kept in view while the keyboard moves
  the list.
- A Save or Takeover pressed while the app is checking for a newer document version now waits (up to 15 seconds) instead of being ignored;
  if the check outlasts that, you are told and nothing changes. A newer version still never replaces your draft.
- Typing or pasting into the text-attachment editor on a page that has to be split no longer inserts the same text twice.

Not fixed: a few emulator tests of chat taps, task counts and canvas touch input still fail intermittently on this host with causes not yet
found (docs/parity-matrix.md). Tested with unit tests and a synthetic emulator account, not a live Tower, a physical phone or real accounts.

# 0.7.0 — Android build 18

Manage Workspace. A new **Manage workspace** entry in the sidebar (and in each workspace's own menu when several are enabled)
opens native screens for the workspace's name, address, description and a public avatar image (shown with an explicit consent that
it becomes publicly readable, metadata removed); deleting a workspace asks for its typed name. Members can be added and renamed;
groups can be created, nested and detached; a person outside the workspace can be enrolled into a group in one atomic step that never
changes an existing role. Channel access can be granted, changed and revoked, a new channel can be created and shared only with the
people and groups you tick, and scope roles are managed under the scope's edit lease and revision (a changed revision is reported,
never overwritten). App delegations, installation reconcile/revoke, managed-app activation and publishing installations (add, edit,
disable, revoke, rotate the public key) are included, as is a staged new-scope setup with the product template.

Every change is a confirmed online request and nothing is queued. Before each write the app re-reads your access: an administrator, or
exactly the grant you hold on that channel, scope or installation, with revoked and expired delegations refused; a saved list is never
permission. Managed-app activation signs only to the HTTPS Autopilot origin you type and confirm, only for its catalog read and its one
claim; redirects and any other path are refused, and the saved intent is tied to that origin and workspace so a retry never creates a
second installation. Entries are kept after a refusal or a lost connection, and an old draft cannot undo someone else's change.

Found in independent review before release: after adding a publishing installation the open form kept its generated id, so editing and
saving again would have overwritten it (it now starts over with a new id); the channel access screen and the write now make the same
decision about group-derived authority; deleting a workspace needs a non-blank name; the runtime's own guard on the two activation
paths also refuses http, credentials, fragments and Tower's app header; people are searchable by full public key; a holder of
`workspace.manage` is offered the scope and channel controls, as on the iPhone.

Not in this build: group rename/delete, removing or promoting a member and app uninstall (no supported contract, as on the iPhone), a
delegation with more than one app or destination (the iPhone is the same), group-derived authority for a non-administrator who cannot
read the group directory, and Android push. Tested against Tower stand-ins and a synthetic emulator account, not a live Tower, a
physical phone or real accounts. A few emulator chat and task-list tests are intermittent on this host, and behave the same on the
previous source; see docs/workspace-administration.md and docs/parity-matrix.md.

# 0.6.0 — Android build 17

Several workspaces, on one Tower or more, can now be enabled together. Each has its own storage partition, drafts, outbox,
cursors and sync, so identical workspace ids on two Towers (or identities) can never mix; a shared, bounded scheduler rotates
between them. A chooser grouped by Tower enables or pauses workspaces, sets a colour, and adds another Tower with the same
unlocked identity. The sidebar stacks the enabled workspaces under shared Unread & Active rows that keep their source
workspace; the composer says which workspace it is working in; the header takes a faint tint. Each workspace's section,
open records, context, drafts and queued sends are remembered separately across switching, locking and process death.
Pausing keeps everything and stops only that workspace; resuming revalidates access before anything queued is sent; losing
access closes writes and keeps what was already received, readable and labelled. Disconnecting a Tower moves its partitions to a
private recovery folder the app does not read. The first run after the upgrade keeps the remembered workspace (plus any with
unsent work) enabled. Before release, runtime lifecycle races were exercised with held requests: a workspace closed by
Disconnect can no longer be restarted by a late enable, and a workspace paused and resumed quickly is no longer left
unvalidated by a cancelled predecessor. Not in this build: workspace/group/grant administration and Android push.
Tested against Tower stand-ins and a synthetic emulator account, not a live Tower, a physical phone or real accounts;
see docs/multi-workspace-storage.md and docs/parity-matrix.md.

# 0.5.1 — Android build 16

Tasks. A subtask list that was once complete no longer keeps showing an exact count while its refresh is unfinished. If a
refresh fails, stops at its page budget or is cut off by closing the app, the last confirmed children stay visible, the
count carries a `+`, and the note says whether the list is refreshing, stopped part-way or could not be refreshed. A
child that a completed read removed does not come back, and a finished refresh restores the exact count.

Not in this build: several workspaces at once, workspace/group/grant administration and Android push. Tested against a Tower
stand-in and a synthetic emulator account, not a live Tower or a physical phone. See docs/parity-matrix.md.

# 0.5.0 — Android build 15

Tasks. A task's subtask lists are now kept on the device per parent, so what you saw stays visible offline and after the
app is closed. A list is replaced only when a full read of Tower's list finishes: a read that fails or stops part-way keeps
the last confirmed list and resumes where it stopped. New subtasks you create, tasks that move to another parent and tasks
that are deleted update the lists immediately. The count is exact only after a complete read; a trailing `+` and a plain-language
note say when the list is not loaded yet, saved from an earlier read, partly read, read offline, or limited because Tower hides
tasks you cannot see (hidden tasks are never counted). Each list has Refresh and re-reads when opened, expanded and about once
a minute while the screen stays open.

Fixes. Closing the local store while a save was still running could crash the app's database layer; it now waits.

Not in this build: several workspaces at once, workspace/group/grant administration and Android push. This package has been
tested against a Tower stand-in and a synthetic emulator account, not a live Tower or a physical phone. See
docs/parity-matrix.md.

# 0.4.0 — Android build 14

Documents. Android now keeps your unsaved page work when someone else changes the same page, and lets you review, merge or
accept their version without losing yours. Nothing is replaced or published without your choice. A newer shared version
opens Review changes with a line diff, Accept latest (asks first and keeps your copy under Preserved drafts), and Merge my
changes over a verified common saved base, with Use local / Use latest for each overlap and an editable preview. Edit merged
result makes a local draft that only an explicit Save publishes, even after the app is closed or its notes are lost.
Drafts survive offline, relaunch, locking and lost access, and a save whose answer was lost is recognised on retry without a
second version. Take over editing… (confirmed, after your changes are kept as a Tower recovery) works across people;
Versions chooses a stored previous version against the head you reviewed. Type [[ or use Link to search pages (this
channel first, every scope on request) and Create and link. Save keeps the editor open, and changed pages save by
themselves each minute, as on iPhone.

Not in this build: several workspaces at once, workspace/group/grant administration, durable task hierarchy tables and
Android push. This package has been tested against a Tower stand-in and a synthetic emulator account, not a live Tower with
a second real editor. See docs/parity-matrix.md and docs/document-reconciliation.md.

# 0.3.0 — Android build 13

Brings Android up to the current iPhone and Mac behaviour for chat, tasks, documents, search and management.

Fixes. Reactions are now sent by Tower's names (thumbs up, heart, …) and the picker offers the web's seven; earlier builds
sent the emoji character, which Tower does not accept. Editing a sent message keeps its @mentions (it used to drop them)
and offers the same @ lookahead as the composer.

Chat. Rename a conversation from its menu (a rename made elsewhere is reported, never overwritten). A bare @ lists the
people and agents you tagged most recently first.

Tasks. A task shows its parent, its subtasks with status, descendants that expand in place, whether the list is complete,
and Add subtask. Task and document comments offer @ and send structured mentions.

Documents. Settings can open documents in Edit by default. Comments show author photos, replies and the quoted selection.
Comment on selected text from the editor. Links between documents open the page; Linked from lists the saved pages that
link to a document. Move a document to another channel after reading what changes.

Search groups results by kind, shows where each lives, and opens files. Notifications offer Undo after Dismiss.
Management controls your role cannot use are hidden, and People and access shows members, groups and a channel's grants
(read only). The section and records you had open return after Android ends the app, and the sidebar refreshes every
minute while the app is in front.

Not in this build: several workspaces at once, workspace/group/grant administration, [[ create-and-link, taking over
another editor's document lease, three-way draft merge and Android push. See docs/parity-matrix.md.

# 0.2.5 — Android build 12

Pinned cards return to a compact resource icon, single-line title and independent Unpin button. Hold the main card
content to drag before or after another pin, including the end of the list. Insertion feedback appears only during a
drag. Move actions remain available through accessibility and Alt+Up/Down/Home/End on the focused card, without
permanent arrangement controls. Existing durable ordering, version checks and synchronization are unchanged.

# 0.2.4 — Android build 11

Tap blank composer padding to focus the editor without replacing a saved draft or intercepting attachment/send controls.
Personal mixed pins now have drag handles, keyboard buttons and accessibility move actions. Moves use Tower's existing
versioned relative order endpoint; another device's conflicting change remains visible and a move never re-pins an item.
Applicable record toolbars follow Search, Notifications, Pin, Browser, Avatar.

The isolated browser now offers Android document selection for file inputs and a native save picker for HTTPS downloads,
HTML download links and page-created blob/data files. Files are limited to 16 MB. Navigation and tab lifecycle changes
cancel pending selection. HTTPS downloads use only the browser profile's destination cookies, never Tower signing headers.
Camera/microphone/location, local-file navigation and FIPS remain unavailable. See the browser file-flow documentation
for tested behavior and remaining limitations.

# 0.2.3 — Android build 10

Threads now have a header menu beside Pin and Close. Copy the source message or its canonical FD Ref, turn the
conversation into a ready task with Get it done, open the latest Autopilot session in the HTTPS browser, or delete your
own source message after confirmation. Missing or deleted source messages never make the menu act on a later reply.
Message menus also offer Copy, FD Ref, Get it done, the applicable Autopilot link and Branch from here.
Branches keep the sending agent as the recipient when replies name nobody. Threads with no replies have an explicit
Open row, so selectable text never hides the way into a conversation.

Get it done includes output choice, assignee, extra instructions, a bounded latest-thread excerpt and source links.
The existing durable task sender now preserves thread and source metadata on send and retry. Sending while reading
older messages returns to the newest message. Build 9's composer, image viewer and canvas drawing behavior is retained.

Notification settings now describe the Android boundary accurately: the shared inbox is supported, while Android OS
alerts still need a supported provider and registration contract. iPhone APNs support does not enable Android alerts.

# 0.2.2 — Android build 9

Canvas drawings can now be edited on Android, as on the web. In Edit mode, choose Text, Rectangle, Ellipse, Diamond,
Arrow, Line or Frame, then tap to place one at its default size or drag to draw it. Tap a drawing to select it. Drag it to
move it, drag its corner to resize it, or drag either end of an arrow or line to reshape it; a bend is kept. Its options
let you:

- edit text and choose a text size;
- change the line style and the start and end arrowheads;
- duplicate, bring forward, send backward or delete.

Group… picks two or more items, drawings or windows, and frames them as a group; removing the group frame ungroups them.
Duplicates are placed 24 points down and right, and Bring forward and Send backward move one step at a time, both
matching the web.

# 0.2.1 — Android build 8

Chat and canvas fixes from overnight feedback. Each channel has one Messages list. Posts that started a conversation
show the reply count and latest activity, and tapping it opens the thread, so no conversation is hidden. Conversations
open at the newest message without scrolling, follow new messages while you are at the bottom, and keep your place when
you have scrolled back. Tapping an image in a message, or an image attachment, opens it full screen; pinch or double-tap
to zoom. Share is now a button inside that view, so a tap no longer opens the image as a file.

The composer's attach and mention buttons are stacked on the left, so the text field is wider. A pasted or picked image
goes in at the cursor as editable text, which becomes `![name](storage://…)` once uploaded, so you can move it within the
message. Images already placed in the message are not shown again as attachments.

Typing `@` opens a search over this device's data. It finds people and agents, documents, tasks, chat threads, channels
and scopes by name, and `doc:`, `task:`, `thread:`, `person:`, `agent:`, `channel:` or `scope:` narrows it to one type.
Choosing a result inserts the same tag the web uses, highlighted in the composer; only people and agents are sent as
structured mentions. Document and task titles are searched through a new local index, built once on upgrade.

A canvas now fills the screen: the app's top bar and section bar are hidden, and exit, the canvas title, Fit and Edit sit
at the bottom, above the keyboard and navigation bar. In Edit mode you can:

- add any kind of item from a searchable, filterable list, or create a new whiteboard in a channel;
- drag an item's handle to move it, or its corner to resize it;
- open, duplicate, bring forward, send backward, set an exact position and size, or remove an item.

# 0.2.0 — Android build 7

Notification inbox corrected for the final Tower contract. The inbox now shows only what this app may show: a category you
turn off here is hidden here without changing what your other Ambulando apps show, and read state stays shared across them.
The category switches in Settings change this app only. If you change them on another device, this app notices, reloads
the inbox and shows the latest choices; if two devices change the same setting at once, nothing is overwritten: the newest
choice is shown with a short note and you choose again. Build 7 supersedes build 6 (same version name).

# 0.2.0 — Android build 6

Adds the shared notification inbox. A bell in the top bar opens your Tower notifications with an unread badge; filter by
Inbox, Unread, Archived or category; mark read or unread, archive or dismiss; mark all read for what you have seen; tap
through to the thread, task or document. Saved notifications stay readable offline and changes you make offline are sent
when the connection returns. If something changed while you were looking (new activity, or another device), your tap is
not applied and the item shows as unread again. Items you can no longer access show no text. Settings adds per-category
inbox choices and says plainly what is not available: alerts, phone push, quiet hours and per-device settings need Tower
support and a push provider this build does not have. The inbox is empty from when your Tower activates it; older
activity is not imported. Older Towers without the inbox show a clear "not available" message.

# 0.1.3 — Android build 5

Large pasted text becomes an exact UTF-8 text attachment while preserving the composer and selection. Open draft
attachments to rename or edit them in bounded pages. Sent text attachments support cached reading, copying, downloading,
new versions and earlier history while preserving the original message attachment. Local drafts and unfinished sent
edits survive offline use and relaunch; conflicts keep your edit with immediate resolution actions. Keep on device
retains unsent edits until you explicitly save to Tower. Filename updates resume separately
from accepted text versions. Android clipboard size limits apply to Copy; Download preserves the full file.

# 0.1.2 — Android build 4

Recover transient read failures with bounded retries and fresh request signatures. Refresh on network return,
clear recovered Inbox warnings, and keep cached complete snapshots on failed refresh. Settings adds simple connection
status, Retry, and explicit export/clear of bounded private diagnostics. Diagnostics exclude content and identifiers;
see docs/privacy.md for retention and sharing. No automatic upload is added.

# 0.1.1 — Android build 3

Exclude private development revision metadata from the packaged APK. Application behavior is unchanged from build 2.

# 0.1.0 — Android build 2

Initial native Kotlin Android release. Adds identity/PIN/Keystore storage, native shared navigation, chat and durable
signed outbox, tasks, rich-document reading and bundled lossless editing, files/search/Deck/pins, canvas/task-tree/board
surfaces, Context/agents/Toolbox, isolated Nostr browser and scope/channel management.

Downloaded history remains read-only after access removal; server deletion tombstones still delete. Photos are
re-encoded without metadata, and backups/device transfer are excluded. See docs/parity-matrix.md for feature evidence,
unsupported features and validation limits. This release has synthetic-emulator and host read-only contract evidence;
authenticated live in-app member use remains for field review.
