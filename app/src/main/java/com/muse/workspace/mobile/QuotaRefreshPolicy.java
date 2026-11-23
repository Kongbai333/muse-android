package com.muse.workspace.mobile;

import java.util.HashMap;
import java.util.HashSet;

/** Monotonic-clock request budget only; never owns values or their observation timestamps. */
final class QuotaRefreshPolicy {
    private final HashMap<String,Long> nextAuto=new HashMap<>(),rateUntil=new HashMap<>();
    private final HashMap<String,Integer> failures=new HashMap<>();
    private final HashSet<String> authPaused=new HashSet<>(),automaticPaused=new HashSet<>();
    boolean allow(String id,boolean manual,long now){
        if(now<rateUntil.getOrDefault(id,0L))return false;
        if(manual){authPaused.remove(id);automaticPaused.remove(id);failures.remove(id);return true;}
        return !authPaused.contains(id)&&!automaticPaused.contains(id)&&now>=nextAuto.getOrDefault(id,0L);
    }
    void started(String id,long now){nextAuto.put(id,now+60000);}
    void finished(String id,String result,long delay,long now){
        nextAuto.put(id,now+(result.equals("ok")?300000:60000));
        switch(result){
            case "ok"->{failures.remove(id);automaticPaused.remove(id);}
            case "auth"->authPaused.add(id);
            case "rate"->{rateUntil.put(id,now+Math.min(3600000,Math.max(90000,delay)));automaticPaused.add(id);}
            case "forbidden"->automaticPaused.add(id);
            case "failure"->{int count=failures.getOrDefault(id,0)+1;failures.put(id,count);if(count>=3)automaticPaused.add(id);}
            default->{ /* Cancellation is not a failed server attempt. */ }
        }
    }
    void loginReady(String id){authPaused.remove(id);automaticPaused.remove(id);failures.remove(id);nextAuto.remove(id);}
    boolean paused(String id){return automaticPaused.contains(id);}
    void remove(String id){nextAuto.remove(id);rateUntil.remove(id);failures.remove(id);authPaused.remove(id);automaticPaused.remove(id);}
}
