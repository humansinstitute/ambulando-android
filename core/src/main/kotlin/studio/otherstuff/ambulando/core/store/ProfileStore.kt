package studio.otherstuff.ambulando.core.store

import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.profiles.DirectoryMember
import studio.otherstuff.ambulando.core.profiles.PeopleDirectory
import studio.otherstuff.ambulando.core.profiles.PersonDisplay
import studio.otherstuff.ambulando.core.profiles.ProfileRecord
import studio.otherstuff.ambulando.core.transport.MemberRecord
import studio.otherstuff.ambulando.core.transport.string
import java.time.Instant

// Public Nostr profiles (iOS ProfileStore.swift). Rows belong to a connection (origin + signer) and go with it on
// disconnect. A profile is kept only while someone in one of that connection's workspaces (member, agent, message
// sender or the signer) still refers to it.

object ProfileLimits {
    /** Most profiles kept per connection. */
    const val MAX_ROWS = 2000
    /** Most people considered in one refresh. */
    const val MAX_CANDIDATES = 500
}

private fun Instant.epochSeconds(): Double = epochSecond + nano / 1e9
private fun instantOf(seconds: Double): Instant = Instant.ofEpochSecond(seconds.toLong(), ((seconds - Math.floor(seconds)) * 1e9).toLong())

/**
 * Stores the outcome of one relay check: verified profiles in `found` replace older ones (NIP-01 order) and refresh
 * `checked_at`; keys in `checked` (pubkey → npub) without a profile keep any earlier profile, or become a `missing`
 * entry. `unlessOff`: an `app_state` key; when it reads "off" (the user turned profiles off meanwhile) nothing is
 * written. Checked in the same transaction, so a switch saved through this writer can never be overtaken by a late round.
 */
suspend fun StoreWriter.recordProfileCheck(
    connectionId: String, found: List<ProfileRecord>, checked: Map<String, String>, at: Instant, unlessOff: String? = null,
) = write {
    if (unlessOff != null && db.textValue("SELECT value FROM app_state WHERE key = ?", unlessOff) == "off") return@write
    val stamp = at.epochSeconds()
    var changed = false
    val stored = HashSet<String>()
    db.prepare("SELECT event_id, event_created_at, status FROM profiles WHERE connection_id = ? AND pubkey = ?").use { read ->
        db.prepare(
            """INSERT INTO profiles(connection_id, pubkey, npub, display_name, name, picture, nip05, event_id, event_created_at, status, checked_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(connection_id, pubkey) DO UPDATE SET npub=excluded.npub, display_name=excluded.display_name, name=excluded.name,
                 picture=excluded.picture, nip05=excluded.nip05, event_id=excluded.event_id, event_created_at=excluded.event_created_at,
                 status=excluded.status, checked_at=excluded.checked_at""",
        ).use { upsert ->
            db.prepare("UPDATE profiles SET checked_at = ? WHERE connection_id = ? AND pubkey = ?").use { touch ->
                for (record in found) {
                    if (checked[record.pubkey] == null) continue
                    val existing = read.query(connectionId, record.pubkey) {
                        ProfileRecord(record.pubkey, record.npub, eventId = it.text(0), eventCreatedAt = if (it.isNull(1)) null else it.long(1),
                            status = if (it.text(2) == ProfileRecord.Status.FOUND.wire) ProfileRecord.Status.FOUND else ProfileRecord.Status.MISSING,
                            checkedAt = at)
                    }.firstOrNull()
                    stored.add(record.pubkey)
                    if (existing != null && existing.status == ProfileRecord.Status.FOUND && !record.isNewer(existing)) {
                        touch.run(stamp, connectionId, record.pubkey)
                        continue
                    }
                    upsert.run(connectionId, record.pubkey, record.npub, record.displayName, record.name, record.picture, record.nip05, record.eventId,
                        record.eventCreatedAt, ProfileRecord.Status.FOUND.wire, stamp)
                    changed = true
                }
            }
        }
    }
    db.prepare(
        """INSERT INTO profiles(connection_id, pubkey, npub, status, checked_at) VALUES(?,?,?,?,?)
           ON CONFLICT(connection_id, pubkey) DO UPDATE SET checked_at = excluded.checked_at""",
    ).use { absent ->
        for ((pubkey, npub) in checked) if (pubkey !in stored) absent.run(connectionId, pubkey, npub, ProfileRecord.Status.MISSING.wire, stamp)
    }
    if (changed) emit(StoreTable.PROFILES)
}

/** Forgets profiles nobody in the connection's workspaces refers to any more, then the oldest checks beyond the cap. */
suspend fun StoreWriter.pruneProfiles(connectionId: String, maxRows: Int = ProfileLimits.MAX_ROWS) = write {
    if (pruneProfileRows(connectionId, maxRows) > 0) emit(StoreTable.PROFILES)
}

internal fun WriteTx.pruneProfileRows(connectionId: String, maxRows: Int = ProfileLimits.MAX_ROWS): Int {
    var removed = db.run(
        """DELETE FROM profiles WHERE connection_id = ?1
             AND npub NOT IN (SELECT signer_npub FROM connections WHERE id = ?1)
             AND npub NOT IN (SELECT npub FROM members WHERE workspace_id IN (SELECT workspace_id FROM workspaces WHERE connection_id = ?1))
             AND npub NOT IN (SELECT agent_npub FROM workspace_agents WHERE workspace_id IN (SELECT workspace_id FROM workspaces WHERE connection_id = ?1))
             AND npub NOT IN (SELECT sender_npub FROM messages WHERE sender_npub IS NOT NULL
                              AND workspace_id IN (SELECT workspace_id FROM workspaces WHERE connection_id = ?1))""",
        connectionId,
    )
    removed += db.run(
        """DELETE FROM profiles WHERE connection_id = ?1 AND pubkey NOT IN
             (SELECT pubkey FROM profiles WHERE connection_id = ?1 ORDER BY checked_at DESC LIMIT ?2)""",
        connectionId, maxRows,
    )
    return removed
}

/** Drops every cached profile of a connection. */
suspend fun StoreWriter.clearProfiles(connectionId: String) = write {
    db.run("DELETE FROM profiles WHERE connection_id = ?", connectionId)
    emit(StoreTable.PROFILES)
}

// MARK: Reader

private fun SqlConnection.profileRows(connectionId: String, limit: Int): List<ProfileRecord> = query(
    """SELECT pubkey, npub, display_name, name, picture, nip05, event_id, event_created_at, status, checked_at
       FROM profiles WHERE connection_id = ? ORDER BY checked_at DESC LIMIT ?""",
    connectionId, limit,
) {
    ProfileRecord(
        pubkey = it.text(0)!!, npub = it.text(1)!!, displayName = it.text(2), name = it.text(3), picture = it.text(4), nip05 = it.text(5),
        eventId = it.text(6), eventCreatedAt = if (it.isNull(7)) null else it.long(7),
        status = if (it.text(8) == ProfileRecord.Status.FOUND.wire) ProfileRecord.Status.FOUND else ProfileRecord.Status.MISSING,
        checkedAt = instantOf(it.double(9)),
    )
}

/** Cached profiles of a connection, most recently checked first (bounded). */
suspend fun StoreReader.profiles(connectionId: String, limit: Int = ProfileLimits.MAX_ROWS): List<ProfileRecord> = read { profileRows(connectionId, limit) }

/** People a workspace shows: the signer, members, agents and the most recent message senders (bounded, valid npubs). */
suspend fun StoreReader.profileCandidates(connectionId: String, workspaceId: String, limit: Int = ProfileLimits.MAX_CANDIDATES): List<String> = read {
    query(
        """SELECT npub FROM (
             SELECT signer_npub AS npub, 0 AS rank FROM connections WHERE id = ?1
             UNION ALL SELECT npub, 1 FROM members WHERE workspace_id = ?2
             UNION ALL SELECT agent_npub, 2 FROM workspace_agents WHERE workspace_id = ?2
             UNION ALL SELECT sender_npub, 3 FROM (SELECT sender_npub, MAX(created_at) AS latest FROM messages
                                                   WHERE workspace_id = ?2 AND sender_npub IS NOT NULL GROUP BY sender_npub
                                                   ORDER BY latest DESC LIMIT ?3))
           GROUP BY npub ORDER BY MIN(rank) LIMIT ?3""",
        connectionId, workspaceId, limit,
    ) { it.text(0) }.filterNotNull().filter { NostrKeys.isValidNpub(it) }
}

private fun SqlConnection.peopleOf(connectionId: String?, workspaceId: String): PeopleDirectory {
    val members = memberRows(workspaceId)
    val agents = LinkedHashMap<String, PeopleDirectory.AgentInfo>()
    query("SELECT agent_npub, display_name, raw FROM workspace_agents WHERE workspace_id = ? ORDER BY sort_order LIMIT 500", workspaceId) {
        Triple(it.text(0), it.text(1), it.text(2))
    }.forEach { (npub, name, raw) ->
        if (npub == null || npub in agents) return@forEach
        agents[npub] = PeopleDirectory.AgentInfo(name ?: "", parseRaw(raw).string("avatar_url"))
    }
    val profiles = connectionId?.let { profileRows(it, ProfileLimits.MAX_ROWS) } ?: emptyList()
    return PeopleDirectory(members.map { DirectoryMember(it.npub, it.displayName) }, agents, profiles)
}

/**
 * Everyone the workspace knows, resolved for display (see [PeopleDirectory]). Cheap enough to run with every chat
 * snapshot: three bounded, indexed reads.
 */
suspend fun StoreReader.people(connectionId: String?, workspaceId: String): PeopleDirectory = read { peopleOf(connectionId, workspaceId) }

/** [people] for the connection the workspace belongs to (screens that only know the workspace). */
suspend fun StoreReader.people(workspaceId: String): PeopleDirectory = read {
    peopleOf(textValue("SELECT connection_id FROM workspaces WHERE workspace_id = ?", workspaceId), workspaceId)
}

/**
 * The workspace's members with `displayName` set to the name the app shows (public profile first, see
 * [PeopleDirectory]), sorted by it: for pickers, mention suggestions and name maps, which read `label`.
 */
suspend fun StoreReader.directoryMembers(workspaceId: String): List<MemberRecord> = read {
    val people = peopleOf(textValue("SELECT connection_id FROM workspaces WHERE workspace_id = ?", workspaceId), workspaceId)
    memberRows(workspaceId).map { m ->
        val person = people.person(m.npub)
        m.copy(displayName = if (person.nameSource == PersonDisplay.NameSource.NPUB) null else person.name)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { m: MemberRecord -> m.label }.thenBy { it.npub })
}
