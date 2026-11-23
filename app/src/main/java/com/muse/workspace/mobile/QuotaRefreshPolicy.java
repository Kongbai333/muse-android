package com.muse.workspace.mobile;

import java.util.HashMap;
import java.util.HashSet;

/** Monotonic-clock request budget only; never owns values or their observation timestamps. */
final class QuotaRefreshPolicy {
    static boolean confirmedLogin(boolean unauthorized,boolean loaded,String url){return StudioDraft.trustedUrl(url)&&(unauthorized||loaded&&StudioDraft.loginPage(url));}
    static long httpRetryAfter(String value,long now){
        if(value==null)return 90000;
        try{long seconds=Long.parseLong(value.trim());return seconds<0?90000:Math.max(90000,Math.min(3600,seconds)*1000);}
        catch(NumberFormatException error){try{return Math.min(3600000,Math.max(90000,java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now));}catch(RuntimeException ignored){return 90000;}}
    }
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
        // end() may report cancellation after a rate result; never shorten its reservation.
        nextAuto.merge(id,now+60000,Math::max);
        switch(result){
            case "ok"->{failures.remove(id);automaticPaused.remove(id);nextAuto.put(id,now+300000);}
            case "auth"->authPaused.add(id);
            case "rate"->rateUntil.merge(id,now+Math.min(3600000,Math.max(90000,delay)),Math::max);
            case "forbidden"->automaticPaused.add(id);
            case "failure"->{
                int count=Math.min(5,failures.getOrDefault(id,0)+1);failures.put(id,count);
                // After three transient failures, reserve one recovery attempt per15/30/60min.
                nextAuto.put(id,now+(count<3?60000:900000L<<(count-3)));
            }
            default->{ /* Cancellation is not a failed server attempt. */ }
        }
    }
    void loginReady(String id){authPaused.remove(id);automaticPaused.remove(id);failures.remove(id);nextAuto.remove(id);}
    boolean paused(String id){return automaticPaused.contains(id);}
    void remove(String id){nextAuto.remove(id);rateUntil.remove(id);failures.remove(id);authPaused.remove(id);automaticPaused.remove(id);}
}
