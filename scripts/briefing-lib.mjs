import { createHash } from 'node:crypto';
export const MIME = 'application/vnd.ambulando.briefing+json';
export const SYSTEM_KEY = 'ambulando.briefings';
export const ZONE = 'Australia/Perth';
export const MAX_BYTES = 4 * 1024 * 1024;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const npub = /^npub1[023456789acdefghjklmnpqrstuvwxyz]{58}$/;
export const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export const canonical = v => v === null || typeof v !== 'object' ? JSON.stringify(v) : Array.isArray(v) ? `[${v.map(canonical).join(',')}]` : `{${Object.keys(v).sort().map(k => `${JSON.stringify(k)}:${canonical(v[k])}`).join(',')}}`;
export const dayAt = (now = new Date()) => new Intl.DateTimeFormat('en-CA', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
const object = v => v && typeof v === 'object' && !Array.isArray(v);
const text = (v, max) => typeof v === 'string' && v.trim().length > 0 && v.length <= max;
export function date(v) { if (typeof v !== 'string' || !/^\d{4}-\d\d-\d\d$/.test(v) || !Number.isFinite(Date.parse(`${v}T00:00:00Z`)) || new Date(`${v}T00:00:00Z`).toISOString().slice(0,10) !== v) throw new Error('Invalid briefing date.'); return v; }
export function validateEntry(day, e) {
  date(day);
  if (!object(e) || !Array.isArray(e.paragraphs) || e.paragraphs.length < 1 || e.paragraphs.length > 2 || !e.paragraphs.every(p => text(p,4000))) throw new Error('A briefing needs one or two short paragraphs.');
  if (typeof e.generated_at !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/.test(e.generated_at) || !Number.isFinite(Date.parse(e.generated_at)) || dayAt(new Date(e.generated_at)) !== day) throw new Error('Briefing timestamp must belong to its Perth date.');
  date(e.generated_at.slice(0,10));
  if (!Array.isArray(e.links) || e.links.length > 20 || !e.links.every(l => object(l) && ['task','thread','doc','file'].includes(l.type) && uuid.test(l.record_id) && text(l.label,160) && (l.channel_id === undefined || uuid.test(l.channel_id)) && (l.type !== 'thread' || uuid.test(l.channel_id)))) throw new Error('Invalid briefing source links.');
  if (e.coverage_note !== undefined && !text(e.coverage_note,1000)) throw new Error('Invalid briefing coverage note.');
  return e;
}
export function validate(d) {
  if (!object(d) || d.schema !== 'ambulando.briefing' || d.schema_version !== 1 || !uuid.test(d.workspace_id) || !npub.test(d.owner_npub) || !npub.test(d.author_npub) || d.owner_npub === d.author_npub || d.timezone !== ZONE || !object(d.entries) || Object.keys(d.entries).length > 3660) throw new Error('Unsupported or invalid briefing document.');
  for (const [day,e] of Object.entries(d.entries)) validateEntry(day,e);
  if (Buffer.byteLength(canonical(d)) > MAX_BYTES) throw new Error('Briefing history needs maintenance; nothing was removed.');
  return d;
}
export function parse(bytes) { if (bytes.byteLength > MAX_BYTES) throw new Error('Briefing is too large.'); return validate(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes))); }
export function empty(workspace, owner, author) { return validate({schema:'ambulando.briefing',schema_version:1,workspace_id:workspace,owner_npub:owner,author_npub:author,timezone:ZONE,entries:{}}); }
export const metadata = (owner,author) => ({briefing:{schema_version:1,owner_npub:owner,author_npub:author}});
export const active = r => r && r.record_state !== 'deleted' && r.record_state !== 'archived' && !r.archived_at && !r.deleted_at && !r.access_removed;
export const isChannel = (r,owner) => active(r) && r.kind === 'channel' && r.metadata?.system_key === SYSTEM_KEY && r.metadata?.briefing?.schema_version === 1 && r.metadata.briefing.owner_npub === owner;
export const isFile = (r,owner,author) => active(r) && r.content_type?.split(';')[0].trim().toLowerCase() === MIME && r.metadata?.briefing?.schema_version === 1 && r.metadata.briefing.owner_npub === owner && r.metadata.briefing.author_npub === author;
export function verifyGrants(grants,members,owner,author) {
  if (!Array.isArray(grants) || !Array.isArray(members)) throw new Error('Private briefing grants could not be verified.');
  const actors = members.map(m => m.actor ?? m);
  const o = actors.find(a => a.npub === owner);
  const a = actors.find(a => a.npub === author);
  if (!o || !a || owner === author) throw new Error('Briefing owner/author is not a current owner/author pair.');
  const ownerId = o.actor_id ?? o.id, authorId = a.actor_id ?? a.id;
  let own=false, agent=false;
  for (const g of grants) {
    const p=g.principal ?? {}; const id=p.actor_id ?? p.id ?? g.principal_id;
    if (!(g.principal_type === 'actor' || g.principal_type === 'person' && g.stored_principal_type === 'actor') || p.type === 'group' || g.stored_principal_type === 'group') throw new Error('Briefings must not be shared with groups.');
    if(id === ownerId && g.access_level === 'manage') own=true;
    else if(id === authorId && ['contribute','manage'].includes(g.access_level)) agent=true;
    else throw new Error('Briefings must be private to their owner and author.');
  }
  if(!own || !agent) throw new Error('Briefing owner and author grants are missing.');
  return {ownerId,authorId};
}
export function insert(d,day,entry,now = new Date()) {
  validate(d); validateEntry(day,entry);
  if (day !== dayAt(now) || Date.parse(entry.generated_at) > now.getTime()) throw new Error('Publish only today’s Perth briefing with a nonfuture timestamp.');
  if(Object.hasOwn(d.entries,day)) {
    if(canonical(d.entries[day]) === canonical(entry)) return {document:d,changed:false};
    throw new Error('A different briefing already exists for this day; history was preserved.');
  }
  return {document:validate({...d,entries:{...d.entries,[day]:entry}}),changed:true};
}
