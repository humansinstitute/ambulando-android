package studio.otherstuff.ambulando.core.management

import studio.otherstuff.ambulando.core.nostr.NostrKeys
import studio.otherstuff.ambulando.core.profiles.PeopleDirectory
import studio.otherstuff.ambulando.core.profiles.PersonDisplay
import studio.otherstuff.ambulando.core.profiles.PersonNamePolicy

/** Transient labels only: raw fields, actor ids and edit-form starting values stay unchanged. */
fun AdministrationRow.withPersonNames(people: PeopleDirectory?): AdministrationRow {
    val npub = fields["npub"]?.takeIf(NostrKeys::isValidNpub)
    val person = npub?.let { people?.person(it) }
    val label = if (PersonNamePolicy.usable(title) == null && person?.nameSource == PersonDisplay.NameSource.NOSTR) person.name else title
    return copy(title = label, children = children.map { it.withPersonNames(people) })
}
