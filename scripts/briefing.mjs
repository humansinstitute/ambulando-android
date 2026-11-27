#!/usr/bin/env bun
// App-owned briefings over generic NStack Files. No private keys or source content in output.
import path from 'node:path';
import os from 'node:os';
import {readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import * as lib from './briefing-lib.mjs';
import * as ops from './briefing-ops.mjs';
const [command,...args]=process.argv.slice(2),o={};
for(let i=0;i<args.length;i+=2){if(!args[i].startsWith('--')||args[i+1]===undefined)throw new Error('Expected --option value');o[args[i].slice(2)]=args[i+1];}
const need=(...keys)=>{for(const k of keys)if(!o[k])throw new Error(`--${k} is required`);};
try {
  need('workspace','owner');
  const autopilot=process.env.AUTOPILOT_REPO||path.join(os.homedir(),'code/wm/autopilot');
  const context=JSON.parse(execFileSync('bun',[path.join(autopilot,'clis/wingman.ts'),'flightdeck','context','--json'],{encoding:'utf8'}));
  if(context.workspace?.workspaceId!==o.workspace)throw new Error('Workspace differs from current broker context.');
  const tower=o.tower||context.workspace.backendBaseUrl;
  const {brokerSigner,towerClient}=await import(path.join(process.env.AMBULANDO_SOL||path.join(os.homedir(),'code/ambulando/sol'),'scripts/html-artifact/tower.mjs'));
  const c=towerClient({baseUrl:tower,workspaceId:o.workspace,sign:brokerSigner()});c.root=`/api/v4/flightdeck-pg/workspaces/${o.workspace}`;
  const me=await c.request('GET',`${c.root}/me`);
  if(me.identity?.workspace_owner_npub!==o.owner)throw new Error('Owner differs from verified workspace owner.');
  let space=await ops.discover(c,o.workspace,o.owner);
  if(command==='discover') console.log(JSON.stringify({state:space.state,channel_id:space.channel?.id,file_id:space.file?.id,author_npub:space.author}));
  else if(command==='setup') {
    need('scope','author');
    if(context.bot?.npub!==o.author)throw new Error('Setup must run as the named author.');
    if(space.state==='needs_setup') {
      const members=await ops.listAll(c,`${c.root}/members`,'members');
      const actors=members.map(m=>m.actor??m),owner=actors.find(a=>a.npub===o.owner),author=actors.find(a=>a.npub===o.author);
      if(!owner||!author)throw new Error('Owner/author membership not verified.');
      const scope=(await ops.listAll(c,`${c.root}/scopes`,'scopes')).find(s=>s.id===o.scope&&s.kind!=='dm'&&lib.active(s));
      if(!scope)throw new Error('An accessible non-DM scope is required.');
      // An uncertain create is never automatically replayed. Rerun discover to reconcile first.
      await c.request('POST',`${c.root}/scopes/${o.scope}/channels`,{name:'Briefings',kind:'channel',description:'Personal morning briefings for the owner and their author.',metadata:{system_key:lib.SYSTEM_KEY,...lib.metadata(o.owner,o.author)},grants:[{principal_type:'actor',principal_id:owner.actor_id??owner.id,access_level:'manage'},{principal_type:'actor',principal_id:author.actor_id??author.id,access_level:'manage'}]});
      space=await ops.discover(c,o.workspace,o.owner);
    }
    if(space.author!==o.author)throw new Error('Briefing author mismatch.');
    if(space.state==='needs_file') {
      const bytes=Buffer.from(lib.canonical(lib.empty(o.workspace,o.owner,o.author)));
      const objectId=await c.uploadObject({bytes,contentType:lib.MIME,fileName:'Daily Briefing.briefing.json'});
      const latest=await ops.discover(c,o.workspace,o.owner);
      if(latest.state!=='needs_file'||latest.channel.id!==space.channel.id)throw new Error('Setup changed; reconcile discovery.');
      await c.createFile(space.channel.id,{storage_object_id:objectId,display_name:'Daily Briefing.briefing.json',metadata:lib.metadata(o.owner,o.author)});
      space=await ops.discover(c,o.workspace,o.owner);
    }
    if(space.state!=='ready')throw new Error('Setup could not be confirmed.');
    const read=await ops.read(c,o.workspace,o.owner,o.author,space.file.id,space.channel.id);
    console.log(JSON.stringify({state:'ready',channel_id:space.channel.id,file_id:read.file.id,version_id:read.versionId,readback:true}));
  } else if(command==='publish') {
    need('file','date','entry');
    if(space.state!=='ready'||context.bot?.npub!==space.author)throw new Error('Publication must run as the configured author.');
    console.log(JSON.stringify(await ops.publish(c,o.workspace,o.owner,o.file,o.date,JSON.parse(await readFile(o.entry,'utf8')),new Date(),space)));
  } else if(command==='readback') {
    need('file');if(space.state!=='ready'||space.file.id!==o.file)throw new Error('Canonical File is unavailable.');
    const d=await ops.read(c,o.workspace,o.owner,space.author,o.file,space.channel.id);
    console.log(JSON.stringify({file_id:d.file.id,version_id:d.versionId,dates:Object.keys(d.document.entries).sort(),readback:true}));
  } else throw new Error('Usage: briefing.mjs discover|setup|publish|readback --workspace UUID --owner npub … (docs/daily-briefing.md)');
} catch(e){console.error(e.message);process.exitCode=1;}
