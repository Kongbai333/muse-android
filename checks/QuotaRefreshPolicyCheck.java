package com.muse.workspace.mobile;
public final class QuotaRefreshPolicyCheck {
    private static final String A="SYNTHETIC_A",B="SYNTHETIC_B";
    private static int checks;
    public static void main(String[] args){
        if(args.length==0||"rate".equals(args[0]))rateRecovery();
        if(args.length==0||"failure".equals(args[0]))failureRecovery();
        if(args.length==0)protectedPauses();
        System.out.println("Quota retry/auth policy: "+checks+" synthetic checks passed; no timer or network.");
    }
    private static void rateRecovery(){
        QuotaRefreshPolicy p=new QuotaRefreshPolicy();long now=100000;
        require(p.allow(A,false,now),"initial automatic read");p.started(A,now);
        p.finished(A,"rate",120000,now);p.finished(A,"cancel",0,now+1); // QuotaSync.end follows rate.
        require(!p.allow(A,false,now+119999),"automatic cannot bypass rate cooldown");
        require(!p.allow(A,true,now+119999),"manual cannot bypass rate cooldown");
        require(p.allow(A,false,now+120000),"automatic read resumes exactly at rate expiry");
        p.started(A,now+120000);require(!p.allow(A,false,now+120001),"recovery reserves a single attempt");
        p.finished(A,"rate",180000,now+120000);p.finished(A,"cancel",0,now+120001);
        require(!p.allow(A,false,now+299999),"second429 starts a fresh hard cooldown");
        require(p.allow(A,false,now+300000),"second cooldown also expires automatically");
        require(p.allow(A,false,now+21600000),"no permanent automatic pause six hours later");
        p.finished(A,"rate",3600000,now+21600000);p.finished(A,"rate",90000,now+21600001);
        require(!p.allow(A,true,now+25199999),"shorter repeated notification cannot shorten rate limit");
        p.loginReady(A);require(!p.allow(A,false,now+25199999),"loginReady cannot bypass rate cooldown");
        require(p.allow(A,false,now+25200000),"long rate limit ends within its bounded window");
    }
    private static void failureRecovery(){
        QuotaRefreshPolicy p=new QuotaRefreshPolicy();long now=100000;
        for(int attempt=0;attempt<3;attempt++){
            require(p.allow(A,false,now),"failure attempt is budgeted");p.started(A,now);
            p.finished(A,"failure",0,now);
            require(!p.allow(A,false,now+59999),"ordinary failure cannot retry within a minute");
            now+=60000;
        }
        // Third failure finished at220000; one recovery slot opens fifteen minutes later.
        require(!p.allow(A,false,1119999),"third failure waits fifteen minutes");
        require(p.allow(A,false,1120000),"third failure recovers automatically after backoff");
        p.started(A,1120000);require(!p.allow(A,false,1120001),"automatic recovery consumes its slot");
        p.finished(A,"failure",0,1120000);p.finished(A,"cancel",0,1120001);
        require(!p.allow(A,false,2919999),"fourth failure waits thirty minutes despite cancel");
        require(p.allow(A,false,2920000),"fourth failure has a finite recovery slot");p.started(A,2920000);
        p.finished(A,"failure",0,2920000);
        require(!p.allow(A,false,6519999),"fifth failure waits one hour");
        require(p.allow(A,false,6520000),"one-hour backoff ends automatically");p.started(A,6520000);
        p.finished(A,"failure",0,6520000);
        require(!p.allow(A,false,10119999),"ongoing failures remain bounded to one attempt per hour");
        require(p.allow(A,false,10120000),"backoff is capped rather than permanent");p.started(A,10120000);
        p.finished(A,"ok",0,10120000);
        require(!p.allow(A,false,10419999),"successful recovery restores the normal freshness interval");
        require(p.allow(A,false,10420000),"normal automatic schedule resumes after success");p.started(A,10420000);
        p.finished(A,"failure",0,10420000);
        require(p.allow(A,false,10480000),"success resets the ordinary failure budget");
        p.finished(A,"failure",0,10480000);p.finished(A,"failure",0,10540000);
        require(p.allow(A,true,10540001),"explicit manual intent can reset ordinary backoff");p.started(A,10540001);
        p.finished(A,"failure",0,10540001);
        require(p.allow(A,false,10600001),"manual retry starts a fresh failure budget");
    }
    private static void protectedPauses(){
        QuotaRefreshPolicy p=new QuotaRefreshPolicy();long now=100000;
        p.finished(A,"auth",0,now);require(!p.allow(A,false,now+21600000),"auth still requires login or manual intent");
        p.loginReady(A);require(p.allow(A,false,now+21600000),"trusted login transition resumes auth pause");
        p.finished(A,"forbidden",0,now);require(!p.allow(A,false,now+21600000),"403 is still paused after six hours");
        require(p.paused(A),"403 still offers an explicit user retry");
        require(p.allow(B,false,now),"account budgets are isolated");
        p.finished(A,"rate",120000,now);require(!p.allow(A,true,now+119999),"manual403 retry still respects a rate limit");
        require(!p.allow(A,false,now+120000),"rate expiry does not clear an independent403 pause");
        require(p.allow(A,true,now+120000),"explicit user retry can clear403 after cooldown");
        p.finished(A,"auth",0,now);p.finished(A,"rate",120000,now);
        require(!p.allow(A,false,now+21600000),"rate expiry does not clear an independent auth pause");
        p.remove(A);require(p.allow(A,false,now),"removed account has no orphan policy state");
        require(!QuotaRefreshPolicy.confirmedLogin(false,false,"https://muse.ai/login"),"loading login redirect is not verified logout");
        require(!QuotaRefreshPolicy.confirmedLogin(false,true,"https://untrusted.invalid/login"),"foreign challenge cannot clear Muse cache");
        require(QuotaRefreshPolicy.confirmedLogin(false,true,"https://muse.ai/login"),"completed trusted login page is explicit");
        require(QuotaRefreshPolicy.confirmedLogin(true,false,"https://muse.ai/"),"current trusted main-frame401 is explicit");
        require(QuotaRefreshPolicy.httpRetryAfter("120",1800000000000L)==120000,"main-frame429 respects numeric Retry-After");
    }
    private static void require(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
}
