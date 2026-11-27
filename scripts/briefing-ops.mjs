import * as lib from './briefing-lib.mjs';
export async function listAll(c,path,key) {
  const rows=[],seen=new Set(); let cursor;
  for(let n=0;n<100;n++) {
    const b=await c.request('GET',`${path}${path.includes('?')?'&':'?'}limit=200${cursor?`&cursor=${encodeURIComponent(cursor)}`:''}`);
    if(!Array.isArray(b?.[key]) || b.partial===true || b.complete===false) throw new Error(`Incomplete ${key} discovery.`);
    rows.push(...b[key]); cursor=b.next_cursor;
    if(!cursor) { if(b[key].length>=200 || b.has_more) throw new Error(`Incomplete ${key} discovery.`); return rows; }
    if(seen.has(cursor)||!b[key].length) throw new Error(`Invalid ${key} cursor.`); seen.add(cursor);
  }
  throw new Error(`Too many ${key} pages.`);
}
export async function discover(c,workspace,owner) {
  const found=[];
  for(const scope of await listAll(c,`${c.root}/scopes`,'scopes')) {
    if(scope.kind==='dm' || !lib.active(scope)) continue;
    for(const ch of await listAll(c,`${c.root}/scopes/${scope.id}/channels`,'channels')) if(lib.isChannel(ch,owner)) { if(ch.workspace_id!==workspace || ch.scope_id!==scope.id) throw new Error('Briefing channel boundary mismatch.'); found.push(ch); }
  }
  if(found.length>1) throw new Error('Multiple briefing channels need reconciliation.');
  if(!found.length) return {state:'needs_setup'};
  const channel=found[0],author=channel.metadata.briefing.author_npub;
  const members=await listAll(c,`${c.root}/members`,'members');
  const acl=await c.request('GET',`${c.root}/channels/${channel.id}/grants`);
  if(acl.partial===true || acl.complete===false || acl.has_more || acl.next_cursor) throw new Error('Incomplete briefing grants.');
  const {grants}=acl;
  lib.verifyGrants(grants,members,owner,author);
  const files=(await listAll(c,`${c.root}/channels/${channel.id}/files`,'files')).filter(f=>lib.isFile(f,owner,author));
  if(files.length>1) throw new Error('Multiple briefing Files need reconciliation.');
  if(files.some(f=>f.workspace_id!==workspace||f.channel_id!==channel.id)) throw new Error('Briefing File boundary mismatch.');
  return {state:files.length?'ready':'needs_file',channel,author,file:files[0]};
}
/** Exact pinned destination check avoids rescanning every workspace channel around each publication attempt. */
export async function verifyDestination(c,workspace,owner,space) {
  const {channel}=await c.request('GET',`${c.root}/channels/${space.channel.id}`);
  if(!lib.isChannel(channel,owner)||channel.workspace_id!==workspace||channel.id!==space.channel.id||channel.metadata.briefing.author_npub!==space.author) throw new Error('Briefing destination changed.');
  const members=await listAll(c,`${c.root}/members`,'members');
  const acl=await c.request('GET',`${c.root}/channels/${channel.id}/grants`);
  if(acl.partial===true || acl.complete===false || acl.has_more || acl.next_cursor) throw new Error('Incomplete briefing grants.');
  const {grants}=acl;
  lib.verifyGrants(grants,members,owner,space.author);
}
export async function read(c,workspace,owner,author,fileId,channelId) {
  const {file}=await c.getFile(fileId);
  if(!lib.isFile(file,owner,author)||file.id!==fileId||file.workspace_id!==workspace||file.channel_id!==channelId) throw new Error('Invalid briefing File boundary.');
  const versionId=file.current_version_id,meta=file.object?.storage_object;
  if(!versionId||!meta?.sha256_hex||!Number.isSafeInteger(meta.size_bytes)||meta.size_bytes>lib.MAX_BYTES||meta.is_public!==false) throw new Error('Unverified briefing version.');
  const bytes=await c.readVersionBytes(fileId,versionId);
  if(bytes.length!==meta.size_bytes||lib.sha(bytes)!==meta.sha256_hex.toLowerCase()) throw new Error('Briefing digest mismatch.');
  const document=lib.parse(bytes);
  if(document.workspace_id!==workspace||document.owner_npub!==owner||document.author_npub!==author) throw new Error('Briefing identity mismatch.');
  return {file,versionId,document};
}
async function verifyLinks(c,workspace,entry) {
  const sources={task:['tasks','task'],thread:['threads','thread'],doc:['docs','doc'],file:['files','file']};
  for(const l of entry.links) {
    if(!l.channel_id) throw new Error('Publication links need a known channel_id so source privacy can be checked before reading.');
    const {channel}=await c.request('GET',`${c.root}/channels/${l.channel_id}`);
    if(!channel||channel.workspace_id!==workspace||!lib.active(channel)||channel.kind==='dm'&&(!Array.isArray(channel.participant_npubs)||new Set(channel.participant_npubs).size<2)) throw new Error('Notes to Self or unavailable channels cannot be briefing sources.');
    const [path,key]=sources[l.type];
    const b=await c.request('GET',`${c.root}/${path}/${l.record_id}`),r=b?.[key];
    if(!r||r.id!==l.record_id||r.workspace_id!==workspace||!lib.active(r)||l.channel_id!==r.channel_id) throw new Error('A briefing link is unavailable to this author.');
  }
}
export async function publish(c,workspace,owner,fileId,day,entry,now=new Date(),knownSpace=null) {
  const space=knownSpace??await discover(c,workspace,owner);
  if(space.state!=='ready'||space.file.id!==fileId) throw new Error('Canonical briefing File is unavailable.');
  let last, repeated=0, previousFailure;
  for(let attempt=0;attempt<4;attempt++) {
    await verifyDestination(c,workspace,owner,space);
    const head=await read(c,workspace,owner,space.author,fileId,space.channel.id);
    const next=lib.insert(head.document,day,entry,now);
    if(!next.changed) return {file_id:fileId,version_id:head.versionId,changed:false,readback:true};
    if(repeated>=2) throw new Error('Repeated publication failure; stopped after two attempts and reconciled current content.');
    await verifyLinks(c,workspace,entry);
    const bytes=Buffer.from(lib.canonical(next.document));
    const objectId=await c.uploadObject({bytes,contentType:lib.MIME,fileName:'Daily Briefing.briefing.json'});
    // Recheck destination immediately before attaching private bytes.
    await verifyDestination(c,workspace,owner,space);
    let saved;
    try { saved=await c.createVersion(fileId,{base_version_id:head.versionId,storage_object_id:objectId,client_mutation_id:crypto.randomUUID()}); }
    catch(e) {
      last=e;
      // No blind retry: the next iteration discovers, checks ACL and reconciles current content first.
      if(e.status===409) continue;
      if(e.status===undefined || e.status>=500) {
        const failure=`${e.status??'transport'}:${e.code??e.message}`;
        repeated=failure===previousFailure?repeated+1:1;previousFailure=failure;
        continue;
      }
      throw e;
    }
    const versionId=saved.version?.id;
    if(!versionId) throw new Error('Publication outcome uncertain; reconcile before retrying.');
    const exact=await c.readVersionBytes(fileId,versionId);
    if(lib.sha(exact)!==lib.sha(bytes)) throw new Error('Published briefing readback mismatch.');
    const current=await read(c,workspace,owner,space.author,fileId,space.channel.id);
    if(lib.canonical(current.document.entries[day])!==lib.canonical(entry)) throw new Error('Current briefing changed after publication.');
    await verifyDestination(c,workspace,owner,space);
    return {file_id:fileId,version_id:versionId,changed:true,readback:true,sha256:lib.sha(exact)};
  }
  throw new Error(`Publication could not be confirmed; reconcile before retrying. ${last?.message??''}`);
}
