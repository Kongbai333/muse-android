const { test } = require('node:test');
const assert = require('node:assert/strict');
const adapter = require('../app/src/main/assets/muse-observer.js');
const sample = (usage) => ({ subscriptionType: 'PAID', usage });
// Browser fixtures use a real EventTarget, matching the window interface that
// the production observer uses for pointer, keyboard and wheel idle tracking.
// Earlier quota/avatar fixtures explicitly disable the independent invitation
// reader; its default automatic path is exercised in the dedicated tests below.
function withBrowserEvents(context) {
  const events = new EventTarget();
  for (const method of ['addEventListener', 'removeEventListener', 'dispatchEvent']) {
    context[method] = events[method].bind(events);
  }
  return context;
}

test('subscription percentages and resets preserve their real units', () => {
  const q = adapter.parseSubscription(sample({ state:'AVAILABLE', percentUsed:28, resetsAt:2000000000 }));
  assert.equal(q.state,'known');
  assert.equal(q.used_percent,28);
  assert.equal(q.resets_at,2000000000000);
  assert.equal(adapter.parseSubscription({subscription:sample({state:'AVAILABLE',percent_used:0})}).used_percent,0);
});
test('plan label follows explicit subscription metadata instead of defaulting to free',()=>{
  assert.equal(adapter.planLabel({subscriptionType:'FREE'}),'免费版');
  assert.equal(adapter.planLabel({subscriptionType:'PAID',tier:{name:'Pro'}}),'Pro');
  assert.equal(adapter.planLabel({}),'套餐');
});
test('avatar capture uses only explicit account media and produces a bounded frame',()=>{
  const image={naturalWidth:128,naturalHeight:128,getBoundingClientRect:()=>({width:48,height:48}),closest:()=>null};
  let calls=0;
  const root={getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'}),document:{
    querySelectorAll:selector=>selector.includes('data-hatch-avatar-host')?[image]:[],
    createElement:()=>({width:0,height:0,getContext:()=>({drawImage:()=>calls++}),toDataURL:()=> 'data:image/png;base64,AAAA'})}};
  assert.equal(adapter.captureAvatar(root),'data:image/png;base64,AAAA');assert.equal(calls,1);
  image.closest=()=>({});assert.equal(adapter.captureAvatar(root),null);
});
test('unreadable or tainted avatar media falls back without fetching arbitrary images',()=>{
  const image={naturalWidth:128,naturalHeight:128,getBoundingClientRect:()=>({width:48,height:48}),closest:()=>null};
  const root={getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'}),document:{querySelectorAll:()=>[image],createElement:()=>({getContext:()=>({drawImage:()=>{throw Error('tainted');}})})}};
  assert.equal(adapter.captureAvatar(root),null);
  root.document.querySelectorAll=()=>[];assert.equal(adapter.captureAvatar(root),null);
});
test('avatar is retried promptly when account media loads after the ready document',()=>{
  const vm=require('node:vm'),fs=require('node:fs');let loaded=false;
  const media={naturalWidth:128,naturalHeight:128,getBoundingClientRect:()=>({width:48,height:48}),closest:()=>null};
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,
    location:new URL('https://muse.ai/'),
    document:{readyState:'complete',body:{innerText:'Ready app',querySelectorAll:()=>[]},querySelectorAll:selector=>loaded && selector.includes('data-hatch-avatar-host')?[media]:[],
      createElement:()=>({getContext:()=>({drawImage:()=>{}}),toDataURL:()=> 'data:image/png;base64,AAAA'})},
    fetch:async()=>{throw Error('unexpected request');},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  assert.equal(context.__museDesktopObserver.snapshot(false,true,false).avatar_png,null);
  loaded=true;assert.equal(context.__museDesktopObserver.snapshot(false,true,false).avatar_png,'data:image/png;base64,AAAA');
});
test('extra invitation and topup pool is separate from the subscription percentage', () => {
  const value={...sample({state:'AVAILABLE',percentUsed:28}),topupBalance:600,topupTotal:1000,topupRowValueLabel:'剩余 600 积分'};
  const result=adapter.parseSubscription(value);
  assert.equal(result.used_percent,28);
  assert.deepEqual(result.extra,{remaining:600,total:1000,display_value:'剩余 600 积分'});
  const snake=adapter.parseSubscription({...sample({state:'UNLIMITED'}),topup_balance:0,topup_total:200});
  assert.equal(snake.extra.remaining,0);
  assert.equal(snake.extra.total,200);
  assert.equal(adapter.parseSubscription(sample({state:'AVAILABLE',percentUsed:28})).extra,undefined);
});
test('extra pool preserves a display-only value and rejects invalid or contradictory numbers', () => {
  const base=sample({state:'AVAILABLE',percentUsed:28});
  assert.deepEqual(adapter.parseSubscription({...base,topupRowValueLabel:'已使用 12%'}).extra,{remaining:null,total:null,display_value:'已使用 12%'});
  assert.deepEqual(adapter.parseSubscription({...base,topupBalance:400}).extra,{remaining:400,total:null,display_value:null});
  for(const fields of [
    {topupBalance:-1,topupTotal:100}, {topupBalance:101,topupTotal:100},
    {topupBalance:NaN}, {topupBalance:'50'}, {topupTotal:-1},
    {topupBalance:20,topup_balance:30}, {topupTotal:0,topupBalance:1},
  ]) assert.equal(adapter.parseSubscription({...base,...fields}).extra,undefined);
  assert.equal(adapter.parseSubscription({...base,topupBalance:0,topupTotal:0}).extra.remaining,0);
});
test('invalid, conflicting and unrelated usage cannot become a quota ring', () => {
  for (const usage of [
    {state:'AVAILABLE',percentUsed:-1}, {state:'AVAILABLE',percentUsed:101},
    {state:'AVAILABLE',percentUsed:'50'}, {state:'AVAILABLE',percentUsed:NaN},
    {state:'AVAILABLE',percentUsed:10,percent_used:30},
    {state:'AVAILABLE',percentUsed:30,quotaStatus:'DEPLETED'},
  ]) assert.equal(adapter.parseSubscription(sample(usage)),null);
  assert.equal(adapter.parseSubscription({usage:{percentUsed:30},messages:['private']}),null);
  assert.equal(adapter.parseSubscription({subscriptionType:'PAID',usage:{state:'AVAILABLE'}}),null);
});
test('exhausted and unlimited are distinct from unknown', () => {
  assert.equal(adapter.parseSubscription(sample({quotaStatus:'DEPLETED'})).used_percent,100);
  assert.deepEqual(adapter.parseSubscription(sample({state:'UNLIMITED'})),{state:'unlimited',resets_at:null,plan_label:'订阅版'});
  assert.equal(adapter.parseSubscription(null),null);
});
test('RSC subscription replies are parsed without forwarding identity or page data', () => {
  const payload='0:{"a":"$@1"}\n1:'+JSON.stringify({subscription:sample({state:'AVAILABLE',percentUsed:64}),email:'private@example.test',messages:['private']})+'\n';
  const result=adapter.parseResponse(payload);
  assert.equal(result.used_percent,64);
  assert.equal(JSON.stringify(result).includes('private'),false);
  assert.equal(adapter.parseResponse('x'.repeat(600001)),null);
  assert.equal(adapter.parseResponse('1:{"messages":["used 100%"]}'),null);
});
test('only an explicitly named read-only subscription action is discovered', () => {
  const id='a'.repeat(40);
  assert.equal(adapter.discoverAction(`const x=(0,e.createServerReference)("${id}",e.callServer,void 0,e.maps,"fetchSubscriptionAction");`),id);
  assert.equal(adapter.discoverAction(`createServerReference("${id}",x,"sendMessageAction")`),null);
  assert.equal(adapter.discoverAction('fetchSubscriptionAction'),null);
});
test('only the observed settings preload module is eligible for lazy loading', () => {
  assert.equal(adapter.discoverPreload('x.A(12345).then(({preloadHatchSettingsData:e})=>e)'),12345);
  assert.equal(adapter.discoverPreload('x.A(12345).then(({deleteAccount:e})=>e)'),null);
});
test('origin restrictions reject lookalikes, credentials, unusual ports and HTTP', () => {
  assert.equal(adapter.trusted('https://muse.ai/chat'),true);
  assert.equal(adapter.trusted('https://www.muse.ai/'),true);
  for(const url of ['http://muse.ai/','https://muse.ai.evil.test/','https://muse.ai:444/','https://agent@muse.ai/']) assert.equal(adapter.trusted(url),false);
});
test('empty/loading documents and missing stylesheets are not ready', () => {
  const context={location:{href:'https://muse.ai/'},document:{readyState:'complete',body:{innerText:'Ready content',querySelectorAll:()=>[]},querySelectorAll:()=>[]},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  assert.equal(adapter.contentReady(context),true);
  context.document.readyState='loading';
  assert.equal(adapter.contentReady(context),false);
  context.document.readyState='complete';
  context.document.querySelectorAll=()=>[{sheet:null,disabled:false}];
  assert.equal(adapter.contentReady(context),false);
  context.document.querySelectorAll=()=>[];
  context.document.body.innerText='';
  assert.equal(adapter.contentReady(context),false);
});
test('the browser observer returns a serializable snapshot while lookup is pending', () => {
  const vm=require('node:vm');
  const fs=require('node:fs');
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,
    location:new URL('https://muse.ai/'),
    document:{readyState:'complete',body:{innerText:'Visible app',querySelectorAll:()=>[]},querySelectorAll:()=>[]},
    fetch:async()=>{throw new Error('unexpected request');},
    getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  const snapshot=JSON.parse(JSON.stringify(context.__museDesktopObserver.snapshot(false,true,false)));
  assert.equal(snapshot.ready,true);
  assert.equal(snapshot.quota,null);
  assert.equal(snapshot.url,'https://muse.ai/');
  assert.equal(snapshot.document_state,'complete');
});
test('a subscription reply from before logout cannot refill a new login session', async () => {
  const vm=require('node:vm');
  const fs=require('node:fs');
  let release,started,requests=0;
  const requestStarted=new Promise(resolve=>started=resolve);
  const id='b'.repeat(40);
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,
    location:new URL('https://muse.ai/'),
    document:{readyState:'complete',body:{innerText:'Visible app',querySelectorAll:()=>[]},querySelectorAll:selector=>selector==='script[src]'?[{src:'https://muse.ai/_next/static/test.js'}]:[]},
    fetch:async(url,options)=>{
      if(String(url).endsWith('.js')) return new Response(`createServerReference("${id}",x,"fetchSubscriptionAction")`);
      if (++requests > 1) return new Response('{}');
      started(); return new Promise(resolve=>release=resolve);
    },getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  context.__museDesktopObserver.snapshot(true,true,false);
  await requestStarted;
  context.location=new URL('https://muse.ai/login');
  context.__museDesktopObserver.snapshot(false,true,false);
  context.location=new URL('https://muse.ai/');
  context.__museDesktopObserver.snapshot(false,true,false);
  release(new Response(JSON.stringify(sample({state:'AVAILABLE',percentUsed:28}))));
  await new Promise(resolve=>setImmediate(resolve));
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(context.__museDesktopObserver.snapshot(false,true,false).quota,null);
});
test('lazy settings loading discovers and calls only the named subscription reader', async () => {
  const vm=require('node:vm'),fs=require('node:fs');
  const scripts=[{src:'https://muse.ai/_next/static/app.js'}];
  let registered,loaderCalls=0,lookups=0;
  const id='c'.repeat(40);
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,
    location:new URL('https://muse.ai/'),
    document:{readyState:'complete',body:{innerText:'Visible app',querySelectorAll:()=>[]},querySelectorAll:selector=>selector==='script[src]'?scripts:[]},
    fetch:async(url,options)=>{
      if(String(url).endsWith('/app.js')) return new Response('x.A(12345).then(({preloadHatchSettingsData:e})=>e)');
      if(String(url).endsWith('/settings.js')) return new Response(`createServerReference("${id}",x,"fetchSubscriptionAction")`);
      lookups++;assert.equal(options.headers['Next-Action'],id);
      assert.equal(options.body,'[{"includeAgreement":true}]');
      return new Response(JSON.stringify(sample({state:'AVAILABLE',percentUsed:46})));
    },getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  context.TURBOPACK={push(chunk){
    if(typeof chunk[2]==='function') registered=chunk[2];
    else registered({A:async moduleId=>{assert.equal(moduleId,12345);loaderCalls++;scripts.push({src:'https://muse.ai/_next/static/settings.js'});}});
  }};
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  context.__museDesktopObserver.snapshot(true,true,false);
  for(let i=0;i<8;i++) await new Promise(resolve=>setImmediate(resolve));
  assert.equal(context.__museDesktopObserver.snapshot(false,true,false).quota.used_percent,46);
  assert.equal(loaderCalls,1);assert.equal(lookups,1);
});

test('avatar clip records changing frames with a bounded local timer and clears on logout', () => {
  const vm=require('node:vm'),fs=require('node:fs');
  let tick,cleared=false,frame=0,requests=0;
  const media={naturalWidth:72,naturalHeight:72,getBoundingClientRect:()=>({width:40,height:40}),closest:()=>null};
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,
    setInterval:callback=>{tick=callback;return 1;},clearInterval:()=>{cleared=true;},
    location:new URL('https://muse.ai/'),
    document:{readyState:'complete',body:{innerText:'Visible app',querySelectorAll:()=>[]},
      querySelectorAll:selector=>selector.includes('avatar-host]')?[media]:[],
      createElement:()=>({getContext:()=>({drawImage(){}}),toDataURL:()=>`data:image/png;base64,${++frame}`})},
    fetch:async()=>{requests++;throw new Error('no network required for avatar');},
    getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  context.__museDesktopObserver.snapshot(false,true,false);
  assert.equal(typeof tick,'function');
  for(let i=0;i<60;i++)tick();
  const clip=context.__museDesktopObserver.snapshot(false,true,false).avatar_animation;
  assert.equal(clip.frames.length,48);
  assert.equal(clip.frame_ms,125);
  assert.notEqual(clip.frames[0],clip.frames[47]);
  assert.equal(cleared,true);
  assert.equal(requests,0);
  assert.equal(context.__museDesktopObserver.snapshot(false,true,false).avatar_animation,null,'finished clip is sent once');
  context.__museDesktopObserver.snapshot(true,true,false);
  for(let i=0;i<48;i++)tick();
  const updated=context.__museDesktopObserver.snapshot(false,true,false).avatar_animation;
  assert.ok(updated,'manual refresh records a fresh avatar instead of keeping the first clip forever');
  assert.notEqual(updated.frames[0],clip.frames[0]);
  context.location=new URL('https://muse.ai/login');
  assert.equal(context.__museDesktopObserver.snapshot(false,true,false).avatar_png,null);
  assert.equal(context.__museDesktopObserver.snapshot(false,true,false).avatar_animation,null);
});

test('avatar recording stops when a document leaves the trusted Muse origin', () => {
  const vm=require('node:vm'),fs=require('node:fs');let tick,cleared=false;
  const media={naturalWidth:72,naturalHeight:72,getBoundingClientRect:()=>({width:40,height:40}),closest:()=>null};
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,
    setInterval:callback=>{tick=callback;return 1;},clearInterval:()=>{cleared=true;},location:new URL('https://muse.ai/'),
    document:{readyState:'complete',body:{innerText:'Visible app',querySelectorAll:()=>[]},querySelectorAll:s=>s.includes('avatar-host]')?[media]:[],
      createElement:()=>({getContext:()=>({drawImage(){}}),toDataURL:()=>`data:image/png;base64,AA==`})},
    fetch:async()=>{throw new Error('no requests');},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  context.__museDesktopObserver.snapshot(false,true,false);
  context.location=new URL('https://example.test/');tick();
  assert.equal(cleared,true);
  assert.equal(context.__museDesktopObserver.snapshot(false,true,false).avatar_animation,null);
});

test('card: partial subscription fields survive unknown usage without pretending a quota',()=>{
  const parts=adapter.parseCardResponse(JSON.stringify({subscription:{subscriptionType:'PAID',topupBalance:0,topupTotal:5,usage:{state:'UNKNOWN'}}}));
  assert.equal(parts.quota,null);assert.equal(parts.plan.label,'订阅版');assert.equal(parts.extra.remaining,0);
});
test('card: public invitation model keeps explicit uses_remaining separate and never guesses max_uses zero',()=>{
  const item={invite_state:'EXHAUSTED',code:'SYNTH6',max_uses:0,use_count:7,uses_remaining:0};
  assert.equal(adapter.parseInviteModel(item).remaining,0);
  assert.equal(adapter.parseInviteModel({...item,uses_remaining:null}).remaining,undefined);
  assert.equal(adapter.parseInviteModel({topupBalance:9}),null);
});
test('card: retry policy is finite and honors rate-limit cooldown rather than treating forbidden as logout',()=>{
  assert.equal(adapter.retryDelay('forbidden',1),null);
  assert.equal(adapter.retryDelay('request_failed',3),null);
  assert.ok(adapter.retryDelay('not_found',1)>0);
  assert.ok(adapter.retryDelay('rate_limited',1,120)>=120000);
});
test('card: passive invite refresh never clicks the site reveal control',async()=>{
  const vm=require('node:vm'),fs=require('node:fs');let clicks=0;
  const button={innerText:'Invite',getAttribute:()=>'',getBoundingClientRect:()=>({width:40,height:30}),click:()=>{clicks++;}};
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout:fn=>{queueMicrotask(fn);return 1;},clearTimeout(){},
    location:new URL('https://muse.ai/'),document:{readyState:'complete',body:{innerText:'SYNTHETIC app',querySelectorAll:()=>[]},querySelectorAll:s=>s==='button,[role="button"],a'?[button]:[]},
    fetch:async()=>{throw Error('no request allowed');},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  await context.__museDesktopObserver.refreshInvite();assert.equal(clicks,0,'cannot turn reveal/claim/generation into background reads');
});
test('card: 403 retains the last subscription observation and has a distinct status',async()=>{
  const vm=require('node:vm'),fs=require('node:fs');let status=200,clock=1800000000000;
  class Clock extends Date { static now(){return clock;} }
  const id='d'.repeat(40);
  const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,Date:Clock,
    location:new URL('https://muse.ai/'),document:{readyState:'complete',body:{innerText:'SYNTHETIC app',querySelectorAll:()=>[]},querySelectorAll:s=>s==='script[src]'?[{src:'https://muse.ai/_next/static/synthetic.js'}]:[]},
    fetch:async url=>String(url).endsWith('.js')?new Response(`createServerReference("${id}",x,"fetchSubscriptionAction")`):new Response(status===200?JSON.stringify(sample({state:'AVAILABLE',percentUsed:21})):'{}',{status}),
    getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  context.window=context;context.top=context;
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
  context.__museDesktopObserver.snapshot(true,false,false);for(let i=0;i<8;i++)await new Promise(setImmediate);
  const before=context.__museDesktopObserver.snapshot(false,false,false).quota;
  assert.equal(before.used_percent,21);status=403;clock+=1000;context.__museDesktopObserver.snapshot(true,false,false);
  for(let i=0;i<8;i++)await new Promise(setImmediate);
  const after=context.__museDesktopObserver.snapshot(false,false,false);
  assert.equal(after.quota.used_percent,21);assert.equal(after.quota.observed_at,before.observed_at);assert.equal(after.quota_status,'forbidden');
});
test('card: a password challenge outside a login route keeps cached fields without calling it verified logout',async()=>{
 const vm=require('node:vm'),fs=require('node:fs');let challenge=false,requests=0;const id='e'.repeat(40);
 const input={getBoundingClientRect:()=>({width:30,height:30})};
 const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,location:new URL('https://muse.ai/'),
  document:{readyState:'complete',body:{innerText:'SYNTHETIC app',querySelectorAll:()=>[]},querySelectorAll:s=>s==='script[src]'?[{src:'https://muse.ai/_next/static/synthetic.js'}]:challenge&&s.startsWith('input[type=')?[input]:[]},
  fetch:async url=>{if(String(url).endsWith('.js'))return new Response(`createServerReference("${id}",x,"fetchSubscriptionAction")`);requests++;return new Response(JSON.stringify(sample({state:'AVAILABLE',percentUsed:12})));},
  getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};context.window=context;context.top=context;
 vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
 context.__museDesktopObserver.snapshot(true,false,false);for(let i=0;i<8;i++)await new Promise(setImmediate);
 challenge=true;const pending=context.__museDesktopObserver.snapshot(true,false,false);
 assert.equal(pending.quota.used_percent,12);assert.equal(pending.quota_status,'authentication_pending');assert.equal(requests,1);
 context.location=new URL('https://muse.ai/login');const logged=context.__museDesktopObserver.snapshot(false,false,false);assert.equal(logged.quota,null);assert.equal(logged.quota_status,'logged_out');
});
test('card: passive invitation parsing never reads password or hidden input values',()=>{
 const body={},input={type:'password',get value(){throw Error('SYNTHETIC sensitive input must not be read');}};
 const box={innerText:'邀请好友 还可使用 3 次',parentElement:body,getBoundingClientRect:()=>({width:160,height:80}),querySelectorAll:s=>s==='input'?[input]:[]};
 const root={document:{body,evaluate:()=>({snapshotLength:1,snapshotItem:()=>box})},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
 for(const type of ['password','hidden','email','tel']){input.type=type;assert.equal(adapter.parseInvite(root),null);}
 box.querySelectorAll=s=>s==='input'?[{type:'text',value:'SYNTH6'}]:[];
 assert.equal(adapter.parseInvite(root).code,'SYNTH6','ordinary user-opened site dialog is still readable');
});
test('card: a failed passive HTTP response cannot refresh values or successful observation time',async()=>{
 const vm=require('node:vm'),fs=require('node:fs');let status=200,clock=1800000000000;class Clock extends Date{static now(){return clock;}}
 const context={URL,Headers,AbortController,TextDecoder,Uint8Array,setTimeout,clearTimeout,Date:Clock,location:new URL('https://muse.ai/'),
  document:{readyState:'complete',body:{innerText:'SYNTHETIC app',querySelectorAll:()=>[]},querySelectorAll:()=>[]},
  fetch:async()=>new Response(JSON.stringify(sample({state:'AVAILABLE',percentUsed:status===200?17:88})),{status}),getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
 context.window=context;context.top=context;vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
 await context.fetch('https://muse.ai/api/subscription');for(let i=0;i<8;i++)await new Promise(setImmediate);
 const before=context.__museDesktopObserver.snapshot(false,false,false).quota;assert.equal(before.used_percent,17);
 status=503;clock+=1000;await context.fetch('https://muse.ai/api/subscription');for(let i=0;i<8;i++)await new Promise(setImmediate);
 const after=context.__museDesktopObserver.snapshot(false,false,false).quota;assert.equal(after.used_percent,17);assert.equal(after.observed_at,before.observed_at);
});

// Real observer in a fresh synthetic page. No request leaves this fake fetch.
const invitePayload=(fields={})=>({invite_state:'eligible',code:'SYNTH6',max_uses:5,use_count:1,uses_remaining:4,...fields});
const inviteResponse=(fields={},status=200,headers={})=>new Response(JSON.stringify(fields),{status,headers:{'Content-Type':'application/json',...headers}});
const settleInvite=async()=>{for(let i=0;i<8;i++)await new Promise(setImmediate);};
function invitePage(reply=()=>inviteResponse(invitePayload()),fakeTimers=false){
 const vm=require('node:vm'),fs=require('node:fs');let now=1800000000000,challenge=false,clicks=0;
 const calls=[],timers=new Map();let timerId=0;class Clock extends Date{static now(){return now;}}
 const input={getBoundingClientRect:()=>({width:30,height:30})};
 const context={URL,Headers,AbortController,TextDecoder,Uint8Array,Date:Clock,
  setTimeout:fakeTimers?(fn,delay)=>{const id=++timerId;timers.set(id,{fn,delay});return id;}:setTimeout,
  clearTimeout:fakeTimers?id=>timers.delete(id):clearTimeout,
  location:new URL('https://muse.ai/'),
  document:{readyState:'complete',body:{innerText:'SYNTHETIC signed-in app',querySelectorAll:()=>[]},
   querySelectorAll:s=>s.startsWith('input[type=')&&challenge?[input]:s==='button,[role="button"],a'?[{click(){clicks++;throw Error('no click');}}]:[]},
  fetch:async(url,options={})=>{
   const target=new URL(typeof url==='string'?url:url.url,context.location.href);
   if(target.pathname==='/api/subscription')return inviteResponse(sample({state:'AVAILABLE',percentUsed:17}));
   assert.equal(target.pathname,'/api/hatch/invite','only the fixed read endpoint is allowed');
   calls.push({url:target.href,options});return reply(calls.length,options);
  },getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
 context.window=context;context.top=context;
 vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/muse-observer.js'),'utf8'),withBrowserEvents(context));
 return {context,calls,timers,observer:context.__museDesktopObserver,clock:()=>now,advance:ms=>{now+=ms;},
  challenge:value=>{challenge=value;},clicks:()=>clicks};
}
test('invite reader: ready snapshot automatically GETs without an open panel, POST or click',async()=>{
 const p=invitePage();p.observer.snapshot(false,false);await settleInvite();
 assert.equal(p.calls.length,1,'first ready snapshot must automatically read the hidden model');
 const {url,options}=p.calls[0];assert.equal(url,'https://muse.ai/api/hatch/invite');
 assert.equal(options.method,'GET');assert.equal(options.credentials,'same-origin');assert.equal(options.cache,'no-store');assert.equal(options.redirect,'error');
 assert.equal('body' in options,false);assert.equal(new Headers(options.headers).get('Cookie'),null);
 const result=p.observer.snapshot(false,false);assert.equal(result.invite.code,'SYNTH6');assert.equal(result.invite.remaining,4);assert.equal(result.invite_status,'ok');assert.equal(p.clicks(),0);
 assert.equal(result.invite.redeemed,undefined);assert.equal(result.invite.earned,undefined);
});
test('invite reader: single flight and success interval are independent of forced quota refresh',async()=>{
 let release;const p=invitePage(n=>n===1?new Promise(r=>release=r):inviteResponse(invitePayload()));
 p.observer.snapshot(false,false);for(let i=0;i<10;i++)p.observer.snapshot(true,false);
 await settleInvite();assert.equal(p.calls.length,1);release(inviteResponse(invitePayload()));await settleInvite();
 p.advance(299999);p.observer.snapshot(true,false);await settleInvite();assert.equal(p.calls.length,1);
 p.advance(1);p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,2);
});
test('invite reader: zero and partial values retain independent old field times, never infer redeemed',async()=>{
 const p=invitePage(n=>inviteResponse(invitePayload(n===1?{}:{code:null,uses_remaining:0,use_count:5})));
 p.observer.snapshot(false,false);await settleInvite();const first=p.observer.snapshot(false,false).invite;
 p.advance(300000);p.observer.snapshot(false,false);await settleInvite();const second=p.observer.snapshot(false,false).invite;
 assert.equal(second.code,'SYNTH6');assert.equal(second.code_observed_at,first.code_observed_at);
 assert.equal(second.code_unavailable,true);assert.equal(second.code_unavailable_observed_at,p.clock());
 assert.equal(second.remaining,0);assert.equal(second.remaining_observed_at,p.clock());
 assert.equal(second.redeemed,undefined);assert.equal(second.earned,undefined);
});
test('invite reader: a valid nullable model stays unknown and never auto-generates',async()=>{
 const p=invitePage(()=>inviteResponse(invitePayload({code:null,max_uses:null,use_count:null,uses_remaining:null})));
 p.observer.snapshot(false,false);await settleInvite();const result=p.observer.snapshot(false,false);
 assert.equal(p.calls.length,1);assert.equal(result.invite_status,'ok');assert.equal(result.invite,null);
 p.advance(10000);p.observer.snapshot(true,false);await settleInvite();assert.equal(p.calls.length,1);
});
for(const status of [401,403])test(`invite reader: HTTP${status} pauses only its own automatic budget and preserves quota`,async()=>{
 const p=invitePage(n=>n===1?inviteResponse(invitePayload()):inviteResponse({},status));
 await p.context.fetch('https://muse.ai/api/subscription');await settleInvite();p.observer.snapshot(false,false);await settleInvite();
 const before=p.observer.snapshot(false,false);p.advance(300000);p.observer.snapshot(false,false);await settleInvite();
 let after=p.observer.snapshot(false,false);assert.equal(after.invite_status,status===401?'logged_out':'forbidden');
 assert.equal(after.quota.used_percent,17);assert.equal(after.quota.observed_at,before.quota.observed_at);
 assert.equal(after.invite.code_observed_at,before.invite.code_observed_at);assert.equal(after.invite.remaining_observed_at,before.invite.remaining_observed_at);
 p.advance(21600000);for(let i=0;i<8;i++)p.observer.snapshot(true,false);await settleInvite();assert.equal(p.calls.length,2,'quota force cannot reset invite auth/403 pause');
 await p.observer.refreshInvite(true);assert.equal(p.calls.length,3,'explicit manual refresh may retry');
});
test('invite reader: repeated manual auth retries are separated by at least thirty seconds',async()=>{
 const p=invitePage(()=>inviteResponse({},401));p.observer.snapshot(false,false);await settleInvite();
 await p.observer.refreshInvite(true);assert.equal(p.calls.length,1);
 p.advance(29999);await p.observer.refreshInvite(true);assert.equal(p.calls.length,1);
 p.advance(1);await p.observer.refreshInvite(true);assert.equal(p.calls.length,2);
});
test('invite reader:429 hard cooldown survives manual/quota force and expires automatically',async()=>{
 const p=invitePage(n=>n===1?inviteResponse({},429,{'Retry-After':'600'}):inviteResponse(invitePayload()));
 p.observer.snapshot(false,false);await settleInvite();assert.equal(p.observer.snapshot(false,false).invite_status,'rate_limited');
 p.advance(599999);p.observer.snapshot(true,false);await p.observer.refreshInvite(true);assert.equal(p.calls.length,1);
 p.advance(1);p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,2);assert.equal(p.observer.snapshot(false,false).invite.remaining,4);
});
test('invite reader:429 minimum, cap and HTTP-date remain hard bounded windows',async()=>{
 for(const [value,delay] of [['0',300000],['999999',3600000],['invalid',300000],[new Date(1800000600000).toUTCString(),600000]]){
  const p=invitePage(n=>n===1?inviteResponse({},429,{'Retry-After':value}):inviteResponse(invitePayload()));
  p.observer.snapshot(false,false);await settleInvite();p.advance(delay-1);await p.observer.refreshInvite(true);assert.equal(p.calls.length,1);
  p.advance(1);p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,2);
 }
});
test('invite reader: short transport retries are bounded and successful quota cannot clear them',async()=>{
 const p=invitePage(n=>{if(n<=3)throw Error('SYNTHETIC network failure');return inviteResponse(invitePayload());});
 p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,1);
 p.advance(1999);p.observer.snapshot(true,false);await settleInvite();assert.equal(p.calls.length,1);
 p.advance(1);p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,2);
 p.advance(9999);p.observer.snapshot(true,false);await settleInvite();assert.equal(p.calls.length,2);
 p.advance(1);p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,3);
 await p.context.fetch('https://muse.ai/api/subscription');await settleInvite();
 p.advance(89999);p.observer.snapshot(true,false);await settleInvite();assert.equal(p.calls.length,3);
 p.advance(1);p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,4);
 assert.equal(p.observer.snapshot(false,false).quota.used_percent,17);
});
test('invite reader: old route response is discarded even when a new request finishes first',async()=>{
 let release;const p=invitePage(n=>n===1?new Promise(r=>release=r):inviteResponse(invitePayload({code:'NEW777',uses_remaining:0})));
 p.observer.snapshot(false,false);await settleInvite();p.context.location=new URL('https://muse.ai/chat/new');
 p.observer.snapshot(false,false);await settleInvite();release(inviteResponse(invitePayload({code:'OLD666'})));await settleInvite();
 const result=p.observer.snapshot(false,false);assert.equal(result.invite.code,'NEW777');assert.equal(result.invite.remaining,0);assert.equal(p.calls[0].options.signal.aborted,true);
});
test('invite reader: login/challenge epoch invalidates a late response and login recovery rereads',async()=>{
 let release;const p=invitePage(n=>n===1?new Promise(r=>release=r):inviteResponse(invitePayload({code:'NEW777'})));
 p.observer.snapshot(false,false);await settleInvite();p.challenge(true);p.observer.snapshot(false,false);
 release(inviteResponse(invitePayload({code:'OLD666'})));await settleInvite();assert.equal(p.observer.snapshot(false,false).invite,null);
 p.challenge(false);p.observer.snapshot(false,false);await settleInvite();assert.equal(p.observer.snapshot(false,false).invite.code,'NEW777');
 p.context.location=new URL('https://muse.ai/login');assert.equal(p.observer.snapshot(false,false).invite,null);
});
test('invite reader: inactive native budget, untrusted/loading documents and login pages never GET',async()=>{
 const p=invitePage();p.observer.snapshot(false,false,false);await settleInvite();assert.equal(p.calls.length,0);
 p.context.document.readyState='loading';p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,0);
 p.context.document.readyState='complete';p.context.location=new URL('https://untrusted.invalid/');p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,0);
 p.context.location=new URL('https://muse.ai/login');p.observer.snapshot(false,false);await settleInvite();assert.equal(p.calls.length,0);
});
test('invite reader: oversized or malformed success cannot update any old observation',async()=>{
 for(const invalid of [()=>new Response('x'.repeat(600001),{headers:{'Content-Type':'application/json'}}),()=>inviteResponse({code:'INVALID',max_uses:-1,use_count:0}),()=>new Response('<html>challenge</html>',{headers:{'Content-Type':'text/html'}}),()=>inviteResponse(invitePayload({code:'BAD666'}),503)]){
  const p=invitePage(n=>n===1?inviteResponse(invitePayload()):invalid());p.observer.snapshot(false,false);await settleInvite();
  const before=p.observer.snapshot(false,false).invite;p.advance(300000);p.observer.snapshot(false,false);await settleInvite();
  const after=p.observer.snapshot(false,false).invite;assert.equal(after.code,before.code);assert.equal(after.observed_at,before.observed_at);
 }
});
test('invite reader: request timeout is bounded even if a synthetic transport ignores abort',async()=>{
 const p=invitePage(()=>new Promise(()=>{}),true);p.observer.snapshot(false,false);await settleInvite();
 const timer=[...p.timers.values()].find(t=>t.delay===10000);assert.ok(timer,'fixed reader owns a ten-second deadline');timer.fn();await settleInvite();
 assert.equal(p.calls[0].options.signal.aborted,true);assert.equal(p.observer.snapshot(false,false).invite_status,'retry_wait');
});
test('card polish: invitation parser forwards real nullable numerator/denominator without inferring infinity',()=>{
 const zero=adapter.parseInviteModel(invitePayload({use_count:0,max_uses:0,uses_remaining:null}));
 assert.equal(zero.use_count,0);assert.equal(zero.max_uses,0);assert.equal(zero.remaining,undefined);assert.equal(zero.redeemed,undefined);
 const unknown=adapter.parseInviteModel(invitePayload({use_count:null,max_uses:null}));assert.equal(unknown.use_count,undefined);assert.equal(unknown.max_uses,undefined);
 assert.equal(adapter.parseInviteModel(invitePayload({use_count:31,max_uses:30})),null);
 const missing=invitePayload({use_count:30,max_uses:30});delete missing.code;
 assert.equal(adapter.parseInviteModel(missing,true).code_unavailable,true);
 assert.equal(adapter.parseInviteModel({...missing,max_uses:null},true),null);
});
test('card polish: invitation counter times merge independently and a partial response never refreshes the old numerator',async()=>{
 const p=invitePage(n=>inviteResponse(invitePayload(n===1?{}:{code:null,use_count:null,max_uses:9,uses_remaining:null})));
 await p.context.fetch('https://muse.ai/api/subscription');await settleInvite();p.observer.snapshot(false,false);await settleInvite();
 const before=p.observer.snapshot(false,false);assert.equal(before.invite.use_count,1);assert.equal(before.invite.max_uses,5);
 p.advance(300000);p.observer.snapshot(false,false);await settleInvite();const after=p.observer.snapshot(false,false);
 assert.equal(after.invite.use_count,1);assert.equal(after.invite.use_count_observed_at,before.invite.use_count_observed_at);
 assert.equal(after.invite.max_uses,9);assert.equal(after.invite.max_uses_observed_at,p.clock());
 assert.equal(after.invite.code_observed_at,before.invite.code_observed_at);assert.equal(after.quota.observed_at,before.quota.observed_at);
});
