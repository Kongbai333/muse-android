// Execute the exact lifecycle/cancel method bodies with UI/OS boundaries replaced, not a device test.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),base=path.join(root,'app/src/main/java/com/muse/workspace/mobile');
const ui=fs.readFileSync(path.join(base,'AppUpdateUi.java'),'utf8');
function method(signature){const start=ui.indexOf(signature);assert.ok(start>=0,signature);let level=0,quote='',escape=false;
 for(let i=ui.indexOf('{',start);i<ui.length;i++){let c=ui[i];if(quote){if(escape)escape=false;else if(c==='\\')escape=true;else if(c===quote)quote='';continue;}if(c==='"'||c==="'"){quote=c;continue;}if(c==='{')level++;if(c==='}'&&--level===0)return ui.slice(start,i+1);}throw Error('unclosed method');}
assert.ok(ui.includes('operation.phase.equals("permission_ready")')&&ui.includes('permissionOrStage(operation)'),'resume must continue only the deferred permission result');
const source=`package com.muse.workspace.mobile;
import java.util.*;import java.io.*;import java.nio.file.*;import java.util.concurrent.atomic.AtomicBoolean;
public final class AppUpdateUiHarness {
 static final int UNKNOWN_SOURCE=630;static final AtomicBoolean WORKING=new AtomicBoolean();static File folder;static int checks;
 static final class Intent{} static final class Http{boolean cancelled;void cancel(){cancelled=true;}}
 static final class Op{final AtomicBoolean cancelled=new AtomicBoolean(),cleanupQueued=new AtomicBoolean(),finished=new AtomicBoolean();String phase="permission";int session=-1;Http http=new Http();}
 static final class Queue{final List<Runnable> tasks=new ArrayList<>();void execute(Runnable r){tasks.add(r);}void post(Runnable r){tasks.add(r);}void run(){for(Runnable r:new ArrayList<>(tasks)){tasks.remove(r);r.run();}}}
 static final class Installer{boolean permitted=true;int cancelled;boolean permitted(){return permitted;}void cancelPending()throws AppUpdateRelease.Failure{cancelled++;}static File directory(Object activity){return folder;}}
 static final class Host{void notice(String text){}}
 final Queue io=new Queue(),main=new Queue();final Installer installer=new Installer();final Host host=new Host();final Object activity=new Object();Op operation;boolean foreground,closed;int stages;
 void permissionOrStage(Op op){stages++;}void hideProgress(Op op){}static String message(String code){return code;}
 ${method('boolean onActivityResult(')}
 ${method('private void end(')}
 ${method('private void cancel(')}
 static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
 public static void main(String[] args)throws Exception{
  folder=Files.createTempDirectory(Path.of(args[0]),"SYNTHETIC-ui-update-").toFile();Path keep=folder.toPath().resolve("SYNTHETIC-user-data.txt");Files.writeString(keep,"SYNTHETIC keep");
  AppUpdateUiHarness permission=new AppUpdateUiHarness();permission.operation=new Op();
  require(permission.onActivityResult(UNKNOWN_SOURCE,0,new Intent()),"permission result consumed");
  require(permission.operation.phase.equals("permission_ready")&&permission.stages==0&&!permission.operation.cancelled.get(),"callback before onResume is deferred, not canceled");
  permission.operation.phase="permission";permission.foreground=true;permission.onActivityResult(UNKNOWN_SOURCE,0,new Intent());require(permission.stages==1,"already foreground may stage");
  require(!permission.onActivityResult(123,0,new Intent()),"unrelated result not consumed");
  AppUpdateUiHarness denied=new AppUpdateUiHarness();denied.operation=new Op();denied.installer.permitted=false;denied.onActivityResult(UNKNOWN_SOURCE,0,new Intent());require(denied.stages==0&&denied.operation.cancelled.get(),"denied permission cannot stage");denied.io.run();denied.main.run();
  AppUpdateUiHarness cancel=new AppUpdateUiHarness();Op old=new Op();cancel.operation=old;WORKING.set(true);Files.writeString(folder.toPath().resolve("download.part"),"SYNTHETIC partial");
  cancel.cancel(old,null);cancel.cancel(old,null);require(old.http.cancelled&&cancel.io.tasks.size()==1,"cancel disconnects once and queues one cleanup");
  require(WORKING.get()&&Files.exists(folder.toPath().resolve("download.part")),"global ownership is retained until worker cleanup");cancel.io.run();
  require(WORKING.get()&&!Files.exists(folder.toPath().resolve("download.part")),"cleanup precedes main ownership release");cancel.main.run();require(!WORKING.get()&&cancel.operation==null,"main completion releases ownership");
  Op next=new Op();cancel.operation=next;WORKING.set(true);cancel.end(old);cancel.cancel(old,null);require(WORKING.get()&&cancel.operation==next&&cancel.io.tasks.isEmpty(),"late old callbacks cannot release or cancel a newer operation");
  next.session=7;cancel.cancel(next,null);cancel.io.run();cancel.main.run();require(cancel.installer.cancelled==1,"own staged session is abandoned on cancel");
  require(Files.readString(keep).equals("SYNTHETIC keep"),"no user/parent data cleanup");Files.delete(keep);Files.delete(folder.toPath());
  System.out.println("Actual updater lifecycle/cancel bodies: "+checks+" checks passed; fake UI/installer, real owned-file cleanup.");
 }
}
final class AppUpdateInstaller {static File directory(Object activity){return AppUpdateUiHarness.folder;}}
`;
const out=path.join(root,'app/build/updateUiChecks');fs.mkdirSync(out,{recursive:true});const file=path.join(out,'AppUpdateUiHarness.java');fs.writeFileSync(file,source,'utf8');
const jdk=process.env.JAVA_HOME;assert.ok(jdk,'JAVA_HOME must be JDK21');const ext=process.platform==='win32'?'.exe':'';
for(const [tool,args] of [['javac',['-encoding','UTF-8','-d',out,path.join(base,'AppUpdateRelease.java'),path.join(base,'AppUpdateDownload.java'),file]],['java',['-cp',out,'com.muse.workspace.mobile.AppUpdateUiHarness',out]]]){
 const r=spawnSync(path.join(jdk,'bin',tool+ext),args,{cwd:root,encoding:'utf8'});if(r.stdout)process.stdout.write(r.stdout);if(r.stderr)process.stderr.write(r.stderr);assert.equal(r.status,0,tool+' updater lifecycle check');
}
