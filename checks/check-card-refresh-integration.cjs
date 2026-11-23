const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const base=path.join(__dirname,'../app/src/main/java/com/muse/workspace/mobile');const read=n=>fs.readFileSync(path.join(base,n),'utf8');
require('./check-account-menu-layout.cjs');
require('./check-account-card-polish.cjs');
test('card native cache persists independent rows and receives every allowed group',()=>{
 const s=read('QuotaSync.java');assert.match(s,/AccountCardState card\(/);assert.match(s,/\.merge\(jsonMap\(result/);assert.match(s,/new JSONObject\(.*\.rows\(\)\)/);
 for(const field of ['invite:o.invite','plan:o.plan','extra:o.extra'])assert.ok(s.includes(field),field);
 assert.doesNotMatch(s,/putString\(probe\.account,\s*reading\.toString\(\)/);
});
test('card native probes serialize evaluation and cancel stale owners',()=>{
 const s=read('QuotaSync.java');assert.match(s,/if \(active != probe\) return/);assert.match(s,/probe\.evaluating/);const policy=read('QuotaRefreshPolicy.java');assert.ok(policy.includes('rateUntil')&&policy.includes('authPaused')&&policy.includes('automaticPaused'));
 assert.doesNotMatch(s,/HttpURLConnection|CookieManager|setProxyOverride/);
});
test('card current account pulse has foreground, generation and route gates',()=>{
 const s=read('MainActivity.java');for(const marker of ['quotaPulse','onResume()','onPause()','browser.transferring()','loginReady(','quotaHandler.removeCallbacks(quotaPulse)'])assert.ok(s.includes(marker),marker);
 assert.ok(s.includes('quotas.loginRequired(account.id)'),'explicit foreground login route invalidates the old profile reading');
 assert.ok(read('QuotaSync.java').includes('authentication_pending'),'a challenge cannot be labeled quota success');
 const proxy=s.slice(s.indexOf('private void proxyChanged('),s.indexOf('/** 检测各账号的代理出口'));
 assert.ok(proxy.includes('quotas.cancel()'),'proxy revision invalidates old probe');
});
test('card retains stale ticks and identifies cached cycle/time separately from live values',()=>{
 const s=read('QuotaCard.java');assert.ok(s.includes('AccountCardState'));assert.ok(s.includes('setAlpha('));assert.ok(s.includes('未更新'));assert.ok(s.includes('邀请码'));assert.ok(s.includes('额外额度'));
 assert.doesNotMatch(s,/Double used = current \? data\.used\(\) : null/);
});
test('invitation native terminal receipts and actual end preserve separate budgets and stale-owner guards',()=>{
 const {spawnSync}=require('node:child_process');
 function method(signature){const source=read('QuotaSync.java'),start=source.indexOf(signature);assert.ok(start>=0,signature);let level=0,quote='',escaped=false;
  for(let i=source.indexOf('{',start);i<source.length;i++){const c=source[i];if(quote){if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c===quote)quote='';continue;}
   if(c==='"'||c==="'"){quote=c;continue;}if(c==='{')level++;if(c==='}'&&--level===0)return source.slice(start,i+1);}throw Error('unclosed method');}
 const out=path.resolve(__dirname,'../app/build/inviteSyncChecks');fs.mkdirSync(out,{recursive:true});
 const source=`package com.muse.workspace.mobile;import java.util.*;
 public final class InviteSyncHarness{
  static final class SystemClock{static long now=100000;static long elapsedRealtime(){return now;}}
  static final class JSONObject{final String status;final long delay;JSONObject(String status,long delay){this.status=status;this.delay=delay;}String optString(String key){return status;}long optLong(String key,long fallback){return delay;}}
  static final class Page{int closed;void close(){closed++;}}
  static final class Probe{final String account;final boolean readInvite;boolean inviteFinished;final Page page=new Page();Probe(String id,boolean read){account=id;readInvite=read;}}
  final QuotaRefreshPolicy policy=new QuotaRefreshPolicy(),invitePolicy=new QuotaRefreshPolicy();final Map<String,String> states=new HashMap<>();final HashMap<String,Long> inviteManualAfter=new HashMap<>();Probe active;
  void state(Probe probe,String message){states.put(probe.account,message);}
  ${method('private void receiveInvite(')}
  ${method('private boolean mayReadInvite(')}
  ${method('private void end(')}
  static int checks;static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
  Probe start(String id){active=new Probe(id,true);invitePolicy.started(id,SystemClock.now);return active;}
  public static void main(String[] args){
   InviteSyncHarness h=new InviteSyncHarness();Probe a=h.start("SYNTHETIC_A");h.receiveInvite(a,new JSONObject("rate_limited",600000));h.end(a,"额度已同步");
   require(h.policy.allow(a.account,false,400000),"quota success has its ordinary interval");
   require(!h.invitePolicy.allow(a.account,false,699999)&&!h.invitePolicy.allow(a.account,true,699999),"quota success cannot clear invitation hard rate limit");
   require(h.invitePolicy.allow(a.account,false,700000),"invitation cooldown still expires automatically");
   Probe b=h.start("SYNTHETIC_B");h.receiveInvite(a,new JSONObject("ok",0));require(!b.inviteFinished,"old owner cannot complete a new probe");
   h.receiveInvite(b,new JSONObject("logged_out",0));h.end(b,"额度已同步");
   require(h.states.get(b.account).equals("额度已同步")&&h.policy.allow(b.account,false,400000),"invitation401 cannot relabel successful quota");
   require(!h.mayReadInvite(b.account,true,129999)&&h.mayReadInvite(b.account,true,130000),"manual auth intent is rate bounded across native WorkPages");
   h.invitePolicy.finished(b.account,"auth",0,100000);
   require(!h.invitePolicy.allow(b.account,false,21700000),"invitation401 alone pauses its own budget");h.invitePolicy.loginReady(b.account);require(h.invitePolicy.allow(b.account,false,21700000),"confirmed login resumes invitation");
   Probe c=h.start("SYNTHETIC_C");h.receiveInvite(c,new JSONObject("forbidden",0));h.end(c,"额度已同步");require(!h.invitePolicy.allow(c.account,false,21700000),"quota success cannot clear invitation403");
   Probe d=h.start("SYNTHETIC_D");h.receiveInvite(d,new JSONObject("retry_wait",2000));require(!d.inviteFinished,"short invitation backoff remains pending within probe");h.receiveInvite(d,new JSONObject("ok",300000));h.end(d,"未更新 · 未取得有效数据");
   require(!h.invitePolicy.allow(d.account,false,399999)&&h.invitePolicy.allow(d.account,false,400000),"failed quota cannot reset successful invitation interval");
   Probe pending=h.start("SYNTHETIC_PENDING");h.end(pending,"额度已同步");require(h.states.get(pending.account).equals("额度已同步")&&h.invitePolicy.allow(pending.account,false,160000),"bounded close keeps quota success and budgets unfinished invitation separately");
   for(int i=0;i<3;i++){Probe cancel=h.start("SYNTHETIC_CANCEL");h.end(cancel,"未更新 · 已暂停");SystemClock.now+=60000;}
   require(h.invitePolicy.allow("SYNTHETIC_CANCEL",false,SystemClock.now),"lifecycle cancellation is not a server failure");
   Probe off=new Probe("SYNTHETIC_OFF",false);h.active=off;h.receiveInvite(off,new JSONObject("forbidden",0));require(!off.inviteFinished&&h.invitePolicy.allow(off.account,false,SystemClock.now),"disabled invitation cannot consume another budget");
   System.out.println("Actual invitation native receive/end boundaries: "+checks+" synthetic checks passed; no Android page or network.");
  }
 }`;
 const file=path.join(out,'InviteSyncHarness.java');fs.writeFileSync(file,source);
 const executable=name=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',name+(process.platform==='win32'?'.exe':'')):name;
 let result=spawnSync(executable('javac'),['-encoding','UTF-8','-d',out,path.join(base,'StudioDraft.java'),path.join(base,'QuotaRefreshPolicy.java'),file],{encoding:'utf8'});assert.equal(result.status,0,result.stderr);
 result=spawnSync(executable('java'),['-cp',out,'com.muse.workspace.mobile.InviteSyncHarness'],{encoding:'utf8'});assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
});
test('sidebar transfer choice is inert until selection and preserves all three existing flow callbacks',()=>{
 const {spawnSync}=require('node:child_process'),source=read('AccountSidebar.java'),signature='private void showTransferChoices()';
 const start=source.indexOf(signature);assert.ok(start>=0,'single import entry needs a choice dialog');let level=0,quote='',escaped=false,body;
 for(let i=source.indexOf('{',start);i<source.length;i++){const c=source[i];if(quote){if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c===quote)quote='';continue;}
  if(c==='"'||c==="'"){quote=c;continue;}if(c==='{')level++;if(c==='}'&&--level===0){body=source.slice(start,i+1);break;}}assert.ok(body);
 const out=path.resolve(__dirname,'../app/build/sidebarChoiceChecks');fs.mkdirSync(out,{recursive:true});
 const java=`package com.muse.workspace.mobile;import java.util.*;
 public final class SidebarChoiceHarness {
  interface Choice {void selected(Dialog dialog,int index);}
  static final class Dialog {boolean dismissed;String[] labels;Choice selected;void dismiss(){dismissed=true;}void cancel(){dismiss();}void choose(int index){selected.selected(this,index);}}
  static final class Builder {final Dialog dialog=new Dialog();Builder setTitle(String title){return this;}Builder setItems(String[] labels,Choice choice){dialog.labels=labels;dialog.selected=choice;return this;}Builder setNegativeButton(String label,Object listener){if(listener!=null)throw new AssertionError("cancel must not start a flow");return this;}Dialog show(){MobileUi.shown=dialog;return dialog;}}
  static final class MobileUi {static Dialog shown;static Builder dialog(Object context){return new Builder();}}
  static final class Host {int packages,cookies,outbound;void importAccounts(){if(!MobileUi.shown.dismissed)throw new AssertionError("dismiss before package flow");packages++;}void importCookieText(){if(!MobileUi.shown.dismissed)throw new AssertionError("dismiss before Cookie flow");cookies++;}void exportAccounts(){if(!MobileUi.shown.dismissed)throw new AssertionError("dismiss before export flow");outbound++;}}
  final Host host=new Host();Object getContext(){return this;}
  ${body}
  static int checks;static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
  public static void main(String[] args){SidebarChoiceHarness h=new SidebarChoiceHarness();h.showTransferChoices();require(h.host.packages==0&&h.host.cookies==0&&h.host.outbound==0,"showing dialog is inert");
   require(Arrays.equals(MobileUi.shown.labels,new String[]{"加密账号包","Cookie文本","账号及静态代理导出"}),"all three existing forms remain offered");
   MobileUi.shown.cancel();require(h.host.packages==0&&h.host.cookies==0&&h.host.outbound==0,"cancel/back/outside do not select a flow");
   h.showTransferChoices();MobileUi.shown.choose(0);require(h.host.packages==1&&h.host.cookies==0&&h.host.outbound==0,"package option selects only the original package flow");
   h.showTransferChoices();MobileUi.shown.choose(1);require(h.host.packages==1&&h.host.cookies==1&&h.host.outbound==0,"Cookie option selects only the original text flow");
   h.showTransferChoices();MobileUi.shown.choose(2);require(h.host.packages==1&&h.host.cookies==1&&h.host.outbound==1,"export option selects only the original static-proxy export flow");
   h.showTransferChoices();MobileUi.shown.choose(-1);require(h.host.packages==1&&h.host.cookies==1&&h.host.outbound==1,"invalid selection does not start any flow");
   System.out.println("Actual sidebar import choice: "+checks+" checks passed; native dialog replaced, no files/accounts/network.");}
 }`;
 const file=path.join(out,'SidebarChoiceHarness.java');fs.writeFileSync(file,java);const executable=name=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',name+(process.platform==='win32'?'.exe':'')):name;
 let result=spawnSync(executable('javac'),['-encoding','UTF-8','-d',out,file],{encoding:'utf8'});assert.equal(result.status,0,result.stderr);
 result=spawnSync(executable('java'),['-cp',out,'com.muse.workspace.mobile.SidebarChoiceHarness'],{encoding:'utf8'});assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
});
