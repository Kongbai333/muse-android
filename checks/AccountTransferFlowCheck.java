package com.muse.workspace.mobile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class AccountTransferFlowCheck {
    private static int checks;
    private static final class Fake implements AccountTransferFlow.Backend {
        final List<String> events=new ArrayList<>();
        final List<Consumer<String>> routes=new ArrayList<>(),writes=new ArrayList<>();
        final List<AccountTransferFlow.Candidate> published=new ArrayList<>();
        int proxies,commits,rollbacks,ends;boolean failProxy,failCommit;
        public void begin(){events.add("begin");}
        public String createProxy(AccountTransferBundle.Proxy proxy)throws Exception{if(failProxy)throw new Exception("synthetic");events.add("proxy");return "local-proxy-"+(++proxies);}
        public void route(AccountTransferFlow.Candidate candidate,Consumer<String> done){events.add("route");routes.add(done);}
        public void cookies(AccountTransferFlow.Candidate candidate,Consumer<String> done){events.add("cookies+flush");writes.add(done);}
        public void commit(List<AccountTransferFlow.Candidate> candidates)throws Exception{events.add("commit");if(failCommit)throw new Exception("synthetic");commits++;published.addAll(candidates);}
        public void rollback(List<String> nodes,List<AccountTransferFlow.Candidate> candidates){events.add("rollback");rollbacks++;}
        public void end(){events.add("end");ends++;}
    }
    public static void main(String[] args)throws Exception{
        AccountTransferBundle one=AccountTransferBundle.parse(Files.readAllBytes(Path.of("checks/fixtures/account-transfer-v1/payload.json")));
        List<AccountTransferBundle.Account> rows=new ArrayList<>(one.accounts);
        AccountTransferBundle.Account a=one.accounts.get(0);
        rows.add(new AccountTransferBundle.Account("account-b","合成 B",a.mode,a.proxyRef,a.bypassPolicy,a.sourceForceProxy,a.fidelity,a.coverage,a.cookies));
        AccountTransferBundle two=new AccountTransferBundle(rows,one.proxies);
        Fake success=new Fake();List<String> answers=new ArrayList<>();AccountTransferFlow flow=new AccountTransferFlow(two,success,answers::add);flow.start();
        require(success.proxies==1&&success.commits==0,"one package proxy and no early publication");
        success.routes.get(0).accept(null);require(success.writes.size()==1&&success.commits==0,"route before Cookie callbacks");
        success.routes.get(0).accept(null);require(success.writes.size()==1,"duplicate route ignored");
        success.writes.get(0).accept(null);require(success.routes.size()==2&&success.commits==0,"second account still unpublished");
        success.writes.get(0).accept(null);require(success.routes.size()==2,"stale Cookie completion ignored");
        success.routes.get(1).accept(null);success.writes.get(1).accept(null);
        require(success.commits==1&&success.published.size()==2&&success.ends==1&&answers.size()==1&&answers.get(0)==null,"atomic success and single completion");
        require(success.published.get(0).proxyId.equals(success.published.get(1).proxyId),"shared node binding");
        require(!success.published.get(0).id.equals(a.ref)&&!success.published.get(0).id.equals(success.published.get(1).id),"new unique local account IDs");
        flow.cancel();require(success.rollbacks==0&&success.ends==1,"cancel after commit does not delete published data");
        for(boolean writing:new boolean[]{false,true}){
            Fake fake=new Fake();List<String> done=new ArrayList<>();AccountTransferFlow cancel=new AccountTransferFlow(one,fake,done::add);cancel.start();
            if(writing)fake.routes.get(0).accept(null);cancel.cancel();cancel.cancel();
            fake.routes.get(0).accept(null);if(writing)fake.writes.get(0).accept(null);
            require(fake.commits==0&&fake.rollbacks==1&&fake.ends==1&&done.size()==1&&"cancelled".equals(done.get(0)),"cancel/stale completion at route or Cookie");
        }
        for(String failure:new String[]{"route","cookies","commit","proxy"}){
            Fake fake=new Fake();fake.failProxy=failure.equals("proxy");fake.failCommit=failure.equals("commit");
            List<String> done=new ArrayList<>();AccountTransferFlow failed=new AccountTransferFlow(one,fake,done::add);failed.start();
            if(!failure.equals("proxy")){fake.routes.get(0).accept(failure.equals("route")?"route":null);if(!failure.equals("route"))fake.writes.get(0).accept(failure.equals("cookies")?"cookies":null);}
            require(fake.commits==0&&fake.rollbacks==1&&fake.ends==1&&done.size()==1&&done.get(0)!=null,"failure never publishes: "+failure);
        }
        Fake stale=new Fake();AccountTransferFlow halted=new AccountTransferFlow(two,stale,error->{});halted.start();stale.routes.get(0).accept(null);halted.cancel();
        stale.writes.get(0).accept(null);require(stale.routes.size()==1,"cancellation cannot schedule next account");
        System.out.println("Account transfer activation: "+checks+" checks passed; no Android/network.");
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
}
