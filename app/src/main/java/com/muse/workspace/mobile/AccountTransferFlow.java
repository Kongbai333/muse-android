package com.muse.workspace.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Atomic publication boundary; backend callbacks may be late, repeated or cancelled. */
final class AccountTransferFlow {
    static final class Candidate {
        final String id=UUID.randomUUID().toString();
        final String proxyId;
        final AccountTransferBundle.Account source;
        Candidate(AccountTransferBundle.Account source,String proxyId){this.source=source;this.proxyId=proxyId;}
    }
    interface Backend {
        void begin() throws Exception;
        String createProxy(AccountTransferBundle.Proxy proxy) throws Exception;
        void route(Candidate candidate,Consumer<String> done) throws Exception;
        /** Must complete only after every native Cookie callback succeeds and flush completes. */
        void cookies(Candidate candidate,Consumer<String> done) throws Exception;
        void commit(List<Candidate> candidates) throws Exception;
        void rollback(List<String> createdProxyIds,List<Candidate> candidates);
        void end();
    }
    private enum State { NEW, ROUTE, COOKIES, COMMIT, DONE }
    private final AccountTransferBundle bundle;
    private final Backend backend;
    private final Consumer<String> done;
    private final Map<String,String> proxyIds=new LinkedHashMap<>();
    private final List<Candidate> candidates=new ArrayList<>();
    private State state=State.NEW;
    private boolean begun;
    private long generation;
    private int next;

    AccountTransferFlow(AccountTransferBundle bundle,Backend backend,Consumer<String> done){this.bundle=bundle;this.backend=backend;this.done=done;}

    synchronized void start(){
        if(state!=State.NEW)return;
        try{
            // Validate expiry before any proxy persistence, profile creation or route change.
            long now=System.currentTimeMillis()/1000;
            for(AccountTransferBundle.Account account:bundle.accounts)for(AccountTransferBundle.Cookie cookie:account.cookies)cookie.setCookie(now);
            backend.begin();begun=true;
            for(AccountTransferBundle.Proxy proxy:bundle.proxies){
                String local=backend.createProxy(proxy);
                if(local==null||local.isEmpty()||proxyIds.containsValue(local))throw new IllegalStateException();
                proxyIds.put(proxy.ref,local);
            }
            next();
        }catch(AccountTransferCrypto.TransferException error){fail(error.code);}
        catch(Exception error){fail("prepare");}
    }

    synchronized void cancel(){if(state!=State.DONE)fail("cancelled");}

    private void next(){
        if(state==State.DONE)return;
        if(next>=bundle.accounts.size()){
            state=State.COMMIT;generation++;
            try{backend.commit(new ArrayList<>(candidates));finish(null);}
            catch(Exception error){fail("storage");}
            return;
        }
        AccountTransferBundle.Account source=bundle.accounts.get(next++);
        Candidate candidate=new Candidate(source,source.proxyRef==null?null:proxyIds.get(source.proxyRef));
        if(source.mode.equals("proxy")&&candidate.proxyId==null){fail("route");return;}
        candidates.add(candidate);state=State.ROUTE;long token=++generation;
        try{backend.route(candidate,error->routed(candidate,token,error));}
        catch(Exception error){fail("route");}
    }

    private synchronized void routed(Candidate candidate,long token,String error){
        if(state!=State.ROUTE||token!=generation)return;
        if(error!=null){fail("route");return;}
        state=State.COOKIES;long writeToken=++generation;
        try{backend.cookies(candidate,answer->written(writeToken,answer));}
        catch(Exception failure){fail("cookies");}
    }
    private synchronized void written(long token,String error){
        if(state!=State.COOKIES||token!=generation)return;
        if(error!=null){fail(error);return;}
        next();
    }
    private void fail(String code){
        if(state==State.DONE)return;
        state=State.DONE;generation++;
        if(begun){try{backend.rollback(new ArrayList<>(proxyIds.values()),new ArrayList<>(candidates));}catch(RuntimeException ignored){} }
        release();done.accept(code);
    }
    private void finish(String error){state=State.DONE;generation++;release();done.accept(error);}
    private void release(){if(begun){begun=false;try{backend.end();}catch(RuntimeException ignored){}}}
}
