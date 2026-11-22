// Execute the actual narrow AccountStore transaction method bodies, replacing OS storage only.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const base=path.join(root,'app/src/main/java/com/muse/workspace/mobile');
const out=path.join(root,'app/build/transferStoreChecks');fs.mkdirSync(out,{recursive:true});
function method(file,signature){
  const source=fs.readFileSync(path.join(base,file),'utf8');const start=source.indexOf(signature);
  assert.ok(start>=0,'method not found: '+signature);let level=0,quote='',escaped=false;
  for(let i=source.indexOf('{',start);i<source.length;i++){
    const c=source[i];if(quote){if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c===quote)quote='';continue;}
    if(c==='"'||c==="'"){quote=c;continue;}if(c==='{')level++;if(c==='}'&&--level===0)return source.slice(start,i+1);
  }throw Error('unclosed method');
}
const candidate=method('AccountStore.java','static Account candidate(');
const activate=method('AccountStore.java','void activateImported(');
const cleanName=method('ProxyStore.java','private static String cleanName(');
const insert=method('ProxyStore.java','private String insert(');
const harness=`package com.muse.workspace.mobile;
import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.security.GeneralSecurityException;
public final class AccountTransferStoreHarness {
 static final class Account {final String id;String name,proxyId;Boolean transferSourceForceProxy;Account(String id,String name){this.id=id;this.name=name;}}
 final ArrayList<Account> accounts=new ArrayList<>();String selected;int writes,failures;List<String> persisted,cache;String persistedSelected,cacheSelected;
 static final class ProxyProfile{final String id;ProxyProfile(String id,String name,ProxySettings settings,String sealed){this.id=id;}}
 final ArrayList<ProxyProfile> profiles=new ArrayList<>();int nodeWrites;
 private ProxyProfile find(String id){for(ProxyProfile p:profiles)if(p.id.equals(id))return p;return null;}
 private boolean persist(){nodeWrites++;return true;}
 private static String sealCredentials(String id,ProxySettings settings){return "SYNTHETIC_SEALED_"+id;}
 ${candidate}
 ${activate}
 ${cleanName}
 ${insert}
 private Editor saveAccounts(){writes++;return new Editor(new ArrayList<>(accounts),selected);}
 private final class Editor {final List<String> snapshot=new ArrayList<>();final String choice;Editor(List<Account> items,String choice){for(Account a:items)snapshot.add(a.id);this.choice=choice;}boolean commit(){cache=snapshot;cacheSelected=choice;if(failures-->0)return false;persisted=snapshot;persistedSelected=choice;return true;}}
 private AccountTransferStoreHarness(){Account old=new Account(UUID.randomUUID().toString(),"SYNTHETIC old");accounts.add(old);selected=old.id;persisted=new ArrayList<>(Arrays.asList(old.id));cache=persisted;persistedSelected=cacheSelected=selected;}
 private static int checks;private static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
 public static void main(String[] args)throws Exception{
  AccountTransferBundle.Account source=AccountTransferBundle.fromCookieHeader("hatch_sess=SYNTHETIC==%25+","SYNTHETIC new");
  AccountTransferFlow.Candidate imported=new AccountTransferFlow.Candidate(source,"local-node-only");Account first=candidate(imported);
  require(first.id.equals(imported.id)&&first.proxyId.equals("local-node-only"),"actual factory maps new ID and local proxy");
  AccountTransferStoreHarness success=new AccountTransferStoreHarness();require(success.accounts.size()==1,"candidate is not automatically published");success.activateImported(Arrays.asList(first));
  require(success.accounts.size()==2&&success.selected.equals(first.id)&&success.writes==1,"one atomic publication, then selection");
  require(success.persisted.size()==2&&success.persistedSelected.equals(first.id),"persisted selection consistent");
  AccountTransferStoreHarness fail=new AccountTransferStoreHarness();String old=fail.selected;fail.failures=1;
  try{fail.activateImported(Arrays.asList(first));throw new AssertionError("save failure accepted");}catch(IOException expected){checks++;}
  require(fail.accounts.size()==1&&fail.selected.equals(old)&&fail.writes==2,"failed commit restores list and selected");
  require(fail.cache.size()==1&&fail.persisted.size()==1&&fail.cacheSelected.equals(old),"failed preferences cache restored");
  int before=success.writes;try{success.activateImported(Arrays.asList(first));throw new AssertionError("duplicate ID accepted");}catch(IOException expected){checks++;}
  require(success.accounts.size()==2&&success.writes==before,"duplicate ID rejected before mutation");
  String longName="SYNTHETIC-"+"x".repeat(50);require(cleanName(longName).equals(longName),"valid imported proxy name remains editable");
  try{cleanName("bad\\nname");throw new AssertionError("control name accepted");}catch(IOException expected){checks++;}
  ProxySettings settings=ProxySettings.create("http","proxy.example.invalid","8080","SYNTHETIC_USER","SYNTHETIC_ONLY");
  success.insert("synthetic-node","SYNTHETIC node",settings);
  try{success.insert("synthetic-node","SYNTHETIC duplicate",settings);throw new AssertionError("node ID collision accepted");}catch(IOException expected){checks++;}
  require(success.profiles.size()==1&&success.nodeWrites==1,"node collision cannot alter an existing node");
  System.out.println("Actual store transaction method bodies: "+checks+" checks passed; fake storage, synthetic only.");
 }
}`;
const source=path.join(out,'AccountTransferStoreHarness.java');fs.writeFileSync(source,harness,'utf8');
const jdk=process.env.JAVA_HOME;assert.ok(jdk,'Set JAVA_HOME to JDK21');const suffix=process.platform==='win32'?'.exe':'';
const core=['AccountTransferCrypto.java','AccountTransferJson.java','AccountTransferBundle.java','AccountTransferCookieText.java','AccountTransferFlow.java','ProxySettings.java'].map(n=>path.join(base,n));
for(const [exe,args] of [['javac',['-encoding','UTF-8','-d',out,...core,source]],['java',['-cp',out,'com.muse.workspace.mobile.AccountTransferStoreHarness']]]){
 const result=spawnSync(path.join(jdk,'bin',exe+suffix),args,{cwd:root,encoding:'utf8'});if(result.stdout)process.stdout.write(result.stdout);if(result.stderr)process.stderr.write(result.stderr);assert.equal(result.status,0,exe+' store harness failed');
}
assert.ok(fs.readFileSync(path.join(base,'AccountEditor.java'),'utf8').includes('account.name.length()'),'editing imported account name must not truncate it');
assert.ok(fs.readFileSync(path.join(base,'ProxyEditor.java'),'utf8').includes('existing.name.length()'),'editing imported proxy name must not truncate it');
