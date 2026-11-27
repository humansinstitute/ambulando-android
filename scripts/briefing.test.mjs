import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import * as lib from './briefing-lib.mjs';
import {publish,read,listAll} from './briefing-ops.mjs';
const w='11111111-1111-4111-8111-111111111111',ch='22222222-2222-4222-8222-222222222222',f='33333333-3333-4333-8333-333333333333';
const fixture=JSON.parse(readFileSync(new URL('../core/src/test/resources/briefing/v1.json',import.meta.url)));
const owner=fixture.owner_npub,author=fixture.author_npub;
const entry={generated_at:'2026-11-26T21:30:00Z',paragraphs:['Changes and focus.'],links:[]},now=new Date('2026-11-27T00:00:00Z');
const members=[{actor:{id:'o',npub:owner,kind:'human'}},{actor:{id:'a',npub:author,kind:'agent'}}],grants=[{principal_type:'actor',principal_id:'o',access_level:'manage'},{principal_type:'actor',principal_id:'a',access_level:'manage'}];
function client({conflict=false,uncertain=false}={}) {
  let d=lib.empty(w,owner,author),version=1,writes=0; const versions=new Map(),objects=new Map();
  const bytes=()=>Buffer.from(lib.canonical(d)); versions.set('v1',bytes());
  const file=()=>({id:f,workspace_id:w,channel_id:ch,content_type:lib.MIME,metadata:lib.metadata(owner,author),current_version_id:`v${version}`,object:{storage_object:{is_public:false,size_bytes:bytes().length,sha256_hex:lib.sha(bytes())}}});
  return {root:'/w',get document(){return d;},get writes(){return writes;},
    getFile:async()=>({file:file()}),readVersionBytes:async(_f,v)=>versions.get(v),
    uploadObject:async({bytes})=>{const id=`o${objects.size}`;objects.set(id,bytes);return id;},
    createVersion:async(_f,b)=>{
      if(conflict){conflict=false;d.extra={preserved:true};version++;versions.set(`v${version}`,bytes());throw Object.assign(new Error('stale'),{status:409});}
      assert.equal(b.base_version_id,`v${version}`);d=lib.parse(objects.get(b.storage_object_id));version++;writes++;versions.set(`v${version}`,bytes());
      if(uncertain){uncertain=false;throw new Error('response lost');}return {version:{id:`v${version}`}};
    },
    request:async(_m,p)=>{
      if(p.startsWith('/w/scopes?'))return {scopes:[{id:'s',kind:'scope'}]};
      if(p.startsWith('/w/scopes/s/channels?'))return {channels:[{id:ch,scope_id:'s',workspace_id:w,kind:'channel',metadata:{system_key:lib.SYSTEM_KEY,...lib.metadata(owner,author)}}]};
      if(p.startsWith('/w/members?'))return {members};
      if(p===`/w/channels/${ch}`)return {channel:{id:ch,workspace_id:w,kind:'channel',metadata:{system_key:lib.SYSTEM_KEY,...lib.metadata(owner,author)}}};
      if(p.endsWith('/grants'))return {grants};
      if(p.startsWith(`/w/channels/${ch}/files?`))return {files:[file()]};
      throw new Error('Unexpected '+p);
    }};
}
test('Perth midnight and scheduled boundary use fixed zone',()=>{
 assert.equal(lib.dayAt(new Date('2026-11-26T15:59:59Z')),'2026-11-26');assert.equal(lib.dayAt(new Date('2026-11-26T16:00:00Z')),'2026-11-27');
 assert.throws(()=>lib.insert(lib.empty(w,owner,author),'2026-11-26',entry,now));
 assert.throws(()=>lib.validateEntry('2026-11-26',entry));
});
test('append immutable day, identical retry no-op, conflicting retry refused',()=>{
 const original=lib.empty(w,owner,author);const first=lib.insert(original,'2026-11-27',entry,now);
 assert.deepEqual(original.entries,{});assert.equal(lib.insert(first.document,'2026-11-27',entry,now).changed,false);
 assert.throws(()=>lib.insert(first.document,'2026-11-27',{...entry,paragraphs:['Different']},now));
});
test('owner isolation rejects groups, other humans and missing author',()=>{
 assert.deepEqual(lib.verifyGrants(grants,members,owner,author),{ownerId:'o',authorId:'a'});
 for(const bad of [[...grants,{principal_type:'group',principal_id:'g',access_level:'read'}],[...grants,{principal_type:'actor',principal_id:'other',access_level:'read'}],grants.slice(0,1)])assert.throws(()=>lib.verifyGrants(bad,members,owner,author));
});
test('CAS conflict preserves concurrent unknown fields and exact readback',async()=>{
 const c=client({conflict:true});const r=await publish(c,w,owner,f,'2026-11-27',entry,now);
 assert.equal(r.readback,true);assert.equal(c.writes,1);assert.deepEqual(c.document.extra,{preserved:true});
 assert.equal((await publish(c,w,owner,f,'2026-11-27',entry,now)).changed,false);assert.equal(c.writes,1);
});
test('uncertain success reconciles without duplicate version',async()=>{
 const c=client({uncertain:true});assert.equal((await publish(c,w,owner,f,'2026-11-27',entry,now)).readback,true);assert.equal(c.writes,1);
});
test('exact current File read rejects wrong owner and corrupted bytes',async()=>{
 const c=client();await assert.rejects(read(c,w,author,owner,f,ch));c.readVersionBytes=async()=>Buffer.from('{}');await assert.rejects(read(c,w,owner,author,f,ch));
});
test('incomplete discovery fails rather than becoming no briefing',async()=>{
 await assert.rejects(listAll({request:async()=>({files:Array(200).fill({})})},'/files','files'));
 await assert.rejects(listAll({request:async()=>({files:[],complete:false})},'/files','files'));
});
test('source without known channel cannot trigger a source content read',async()=>{
 const c=client();await assert.rejects(publish(c,w,owner,f,'2026-11-27',{...entry,links:[{type:'task',record_id:w,label:'Task'}]},now),/known channel/);assert.equal(c.writes,0);
});

test('live person alias requires stored actor and exact actor identity',()=>{
 const actual=grants.map(g=>({...g,principal_type:'person',stored_principal_type:'actor',principal:{type:'person',actor_id:g.principal_id}}));
 assert.deepEqual(lib.verifyGrants(actual,members,owner,author),{ownerId:'o',authorId:'a'});
 assert.throws(()=>lib.verifyGrants(actual.map(g=>({...g,stored_principal_type:'group'})),members,owner,author));
});
test('Notes to Self source is refused before target read',async()=>{
 const c=client(),request=c.request;let targetRead=false;
 c.request=async(m,p)=>{
   if(p===`/w/channels/${w}`)return {channel:{id:w,workspace_id:w,kind:'dm',participant_npubs:[owner]}};
   if(p===`/w/tasks/${w}`){targetRead=true;throw new Error('must never read');}
   return request(m,p);
 };
 await assert.rejects(publish(c,w,owner,f,'2026-11-27',{...entry,links:[{type:'task',record_id:w,channel_id:w,label:'Private'}]},now),/Notes to Self/);
 assert.equal(targetRead,false);assert.equal(c.writes,0);
});
test('grant loss after upload prevents version publication',async()=>{
 const c=client(),request=c.request,upload=c.uploadObject;let revoked=false;
 c.uploadObject=async b=>{const id=await upload(b);revoked=true;return id;};
 c.request=async(m,p)=>p.endsWith('/grants')&&revoked?{grants:[...grants,{principal_type:'group',principal_id:'g',access_level:'read'}]}:request(m,p);
 await assert.rejects(publish(c,w,owner,f,'2026-11-27',entry,now),/groups/);assert.equal(c.writes,0);
});
test('rate limit is not blindly retried',async()=>{
 const c=client();let attempts=0;c.createVersion=async()=>{attempts++;throw Object.assign(new Error('retry later'),{status:429});};
 await assert.rejects(publish(c,w,owner,f,'2026-11-27',entry,now),/retry later/);assert.equal(attempts,1);
});
test('two repeated transport failures reconcile then stop without third write',async()=>{
 const c=client();let attempts=0;c.createVersion=async()=>{attempts++;throw new Error('offline');};
 await assert.rejects(publish(c,w,owner,f,'2026-11-27',entry,now),/two attempts/);assert.equal(attempts,2);
});
