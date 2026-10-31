package studio.otherstuff.ambulando.core.transport

/**
 * Who wrote a comment. The recorded creator's npub wins; when a response carried only `created_by_actor_id`, that id is
 * matched to a member of the same workspace. The signed-in account, the updater and mention targets never stand in
 * for the author, so a comment with no recorded creator stays unknown.
 */
fun CommentRecord.authorNpub(members: List<MemberRecord>): String? {
    senderNpub?.takeIf { it.isNotEmpty() }?.let { return it }
    val actorId = raw.string("created_by_actor_id")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return members.firstOrNull { it.workspaceId == workspaceId && it.actorId.lowercase() == actorId }?.npub
}
