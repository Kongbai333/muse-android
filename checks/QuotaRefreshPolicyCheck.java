package com.muse.workspace.mobile;
public final class QuotaRefreshPolicyCheck {
    public static void main(String[] args){
        QuotaRefreshPolicy p=new QuotaRefreshPolicy();long now=100000;
        require(p.allow("SYNTHETIC_A",false,now),"initial auto read");p.started("SYNTHETIC_A",now);
        p.finished("SYNTHETIC_A","failure",0,now);require(!p.allow("SYNTHETIC_A",false,now+59999),"failure cooldown");
        require(p.allow("SYNTHETIC_A",false,now+60000),"finite recovery attempt");
        p.finished("SYNTHETIC_A","failure",0,now+60000);p.finished("SYNTHETIC_A","failure",0,now+120000);
        require(!p.allow("SYNTHETIC_A",false,now+9000000),"three failures stop automatic retries");
        require(p.allow("SYNTHETIC_A",true,now+9000000),"explicit user retry resets failure budget");
        p.finished("SYNTHETIC_A","rate",120000,now+9000000);
        require(!p.allow("SYNTHETIC_A",true,now+9000001),"manual action cannot bypass rate cooldown");
        require(!p.allow("SYNTHETIC_A",false,now+9900000),"rate error cannot create endless automatic reads");
        require(p.allow("SYNTHETIC_A",true,now+9900000),"manual retry allowed after cooldown");
        p.finished("SYNTHETIC_A","auth",0,now+9900000);require(!p.allow("SYNTHETIC_A",false,now+19900000),"auth requires login or manual intent");
        p.loginReady("SYNTHETIC_A");require(p.allow("SYNTHETIC_A",false,now+19900000),"trusted login transition resumes");
        require(p.allow("SYNTHETIC_B",false,now),"account budgets are isolated");
        p.finished("SYNTHETIC_A","forbidden",0,now);require(!p.allow("SYNTHETIC_A",false,now+9900000),"403 is paused, not cache deletion or infinite retry");
        p.remove("SYNTHETIC_A");require(p.allow("SYNTHETIC_A",false,now),"removed account has no orphan policy state");
        System.out.println("Quota retry policy: 13 synthetic checks passed; no timer or network.");
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
