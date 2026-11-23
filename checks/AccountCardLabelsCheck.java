package com.muse.workspace.mobile;
import java.util.*;
public final class AccountCardLabelsCheck {
    private static final long NOW=1800000000000L;
    private static int checks;
    private static Map<String,Object> map(Object... v){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    public static void main(String[] args){
        AccountCardState empty=AccountCardState.read(map(),NOW);
        require(AccountCardLabels.extra(empty).equals("额外 —")&&AccountCardLabels.invitation(empty).equals("邀请码 —/—"),"unknown labels do not invent0 or denominator30");
        AccountCardState card=empty.merge(map("quota",map("state","known","used_percent",25.,"observed_at",NOW,
            "extra",map("remaining",0.,"total",1000000000.)),"invite",map("code","SYNTH6","use_count",1L,"max_uses",30L,"remaining",29L,"observed_at",NOW)),NOW);
        Map<String,Object> saved=card.rows();
        require(AccountCardLabels.extra(card).equals("额外 0/10亿"),"compact label uses actual numeric extra units and valid0");
        require(AccountCardLabels.invitation(card).equals("邀请码 1/30"),"ratio is actual use_count/max_uses");
        require(AccountCardLabels.inviteDetails(card).contains("SYNTH6")&&AccountCardLabels.inviteDetails(card).contains("29"),"real code and explicit remaining retained in read-only details");
        require(!AccountCardLabels.cached(card,NOW,NOW),"fresh fields do not claim cached labels");
        require(AccountCardLabels.cached(card,NOW+600000,NOW),"stale state disclosed outside label text");
        require(!AccountCardLabels.extra(card).startsWith("旧")&&!AccountCardLabels.invitation(card).contains("旧"),"no old prefix on either label");
        require(card.rows().equals(saved),"presentation never changes original cache/time");
        AccountCardState zero=empty.merge(map("invite",map("use_count",0L,"max_uses",0L,"remaining",0L,"observed_at",NOW)),NOW);
        require(AccountCardLabels.invitation(zero).equals("邀请码 0/0"),"real zero denominator stays0 rather than infinity");
        AccountCardState partial=empty.merge(map("invite",map("use_count",2L,"observed_at",NOW)),NOW);
        require(AccountCardLabels.invitation(partial).equals("邀请码 2/—"),"missing denominator stays unknown");
        AccountCardState units=empty.merge(map("extra",map("remaining",1250000.,"total",250000000.,"observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(units).equals("额外 125万/2.5亿"),"large real amounts compact with explicit units");
        require(AccountCardLabels.extraDetails(units).contains("1250000")&&AccountCardLabels.extraDetails(units).contains("250000000"),"unrounded full values remain accessible");
        AccountCardState display=empty.merge(map("extra",map("display_value","SYNTHETIC balance","observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(display).contains("SYNTHETIC balance"),"display-only upstream value is not guessed into numeric tokens");
        AccountCardState passive=card.merge(map("invite",map("redeemed",2L,"earned","10 Muse","observed_at",NOW+1)),NOW+1);
        require(AccountCardLabels.inviteDetails(passive).contains("已兑现好友：2")&&AccountCardLabels.inviteDetails(passive).contains("已赚取：10 Muse"),"real passive auxiliary fields preserved in details");
        System.out.println("Account labels: "+checks+" real-model synthetic checks passed.");
    }
}
