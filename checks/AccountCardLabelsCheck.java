package com.muse.workspace.mobile;
import java.util.*;
public final class AccountCardLabelsCheck {
    private static final long NOW=1800000000000L;
    private static int checks,failures;
    private static Map<String,Object> map(Object... v){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
    private static void require(boolean ok,String why){checks++;if(!ok){failures++;System.err.println("FAIL: "+why);}}
    public static void main(String[] args){
        AccountCardState empty=AccountCardState.read(map(),NOW);
        require(AccountCardLabels.extra(empty).equals("额外 —/—"),"unknown extra never invents used0 or total");
        require(AccountCardLabels.invitation(empty).equals("待获取 —/30"),"missing code is explicit, unknown use stays unknown, user-authorized default30");
        AccountCardState card=empty.merge(map("quota",map("state","known","used_percent",25.,"observed_at",NOW,
            "extra",map("remaining",0.,"total",1000000000.)),"invite",map("code","SYNTH6","use_count",1L,"max_uses",30L,"remaining",29L,"observed_at",NOW)),NOW);
        Map<String,Object> saved=card.rows();
        require(AccountCardLabels.extra(card).equals("额外 10亿/10亿"),"remaining0 means fully consumed, not used0");
        require(AccountCardLabels.invitation(card).equals("SYNTH6 1/30"),"real code and real use_count/max_uses share the label");
        require(AccountCardLabels.inviteDetails(card).contains("SYNTH6")&&AccountCardLabels.inviteDetails(card).contains("29"),"real code and explicit remaining retained in read-only details");
        require(!AccountCardLabels.cached(card,NOW,NOW),"fresh fields do not claim cached labels");
        require(AccountCardLabels.cached(card,NOW+600000,NOW),"stale state disclosed outside label text");
        require(!AccountCardLabels.extra(card).startsWith("旧")&&!AccountCardLabels.invitation(card).contains("旧"),"no old prefix on either label");
        require(card.rows().equals(saved),"presentation never changes original cache/time");
        AccountCardState zero=empty.merge(map("invite",map("use_count",0L,"max_uses",0L,"remaining",0L,"observed_at",NOW)),NOW);
        require(AccountCardLabels.invitation(zero).equals("待获取 0/0"),"real zero denominator stays0 rather than default30 or infinity");
        AccountCardState partial=empty.merge(map("invite",map("use_count",2L,"observed_at",NOW)),NOW);
        require(AccountCardLabels.invitation(partial).equals("待获取 2/30"),"only missing denominator uses user-authorized default30");
        require(AccountCardLabels.inviteDetails(partial).contains("默认30")&&partial.inviteMaxUses()==null&&partial.observed("invite_max_uses")==0,"default30 is disclosed without persisting a fictitious successful field");
        AccountCardState units=empty.merge(map("extra",map("remaining",1250000.,"total",250000000.,"observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(units).equals("额外 2.48亿/2.5亿"),"large real consumed amount derives from total minus remaining");
        require(AccountCardLabels.extraDetails(units).contains("1250000")&&AccountCardLabels.extraDetails(units).contains("250000000"),"unrounded full values remain accessible");
        AccountCardState display=empty.merge(map("extra",map("display_value","SYNTHETIC balance","observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(display).equals("额外 —/—")&&AccountCardLabels.extraDetails(display).contains("SYNTHETIC balance"),"display-only upstream text cannot become consumed amount and stays in details");
        AccountCardState unused=empty.merge(map("extra",map("remaining",1000000000.,"total",1000000000.,"observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(unused).equals("额外 0/10亿"),"unused balance equals total, consumed0/10亿");
        AccountCardState usedPart=empty.merge(map("extra",map("remaining",750.,"total",1000.,"observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(usedPart).equals("额外 250/1000"),"partial consumed is total minus remaining");
        require(AccountCardLabels.extraDetails(usedPart).contains("已消耗：250")&&AccountCardLabels.extraDetails(usedPart).contains("剩余额度：750")&&AccountCardLabels.extraDetails(usedPart).contains("总量：1000"),"details distinguish derived consumed, source balance and source total");
        AccountCardState missingRemaining=empty.merge(map("extra",map("total",1000.,"observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(missingRemaining).equals("额外 —/1000"),"missing remaining never becomes consumed0");
        AccountCardState missingTotal=empty.merge(map("extra",map("remaining",750.,"observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(missingTotal).equals("额外 —/—"),"missing total cannot imply consumed equals remaining");
        AccountCardState conflicting=AccountCardState.read(map("schema",2,"fields",map("extra_remaining",map("value",1100.,"observed_at",NOW),"extra_total",map("value",1000.,"observed_at",NOW))),NOW);
        require(AccountCardLabels.extra(conflicting).equals("额外 —/1000")&&AccountCardLabels.extraDetails(conflicting).contains("已消耗：未知"),"contradictory cached fields never yield negative or invented0");
        AccountCardState decimals=empty.merge(map("extra",map("remaining",0.1,"total",0.3,"observed_at",NOW)),NOW);
        require(AccountCardLabels.extra(decimals).equals("额外 0.2/0.3"),"decimal subtraction avoids binary display artifacts");
        AccountCardState freshDisplay=unused.merge(map("extra",map("display_value","SYNTHETIC newer balance text","observed_at",NOW+1)),NOW+1);
        require(AccountCardLabels.extra(freshDisplay).equals("额外 0/10亿"),"newer display-only text never replaces correctly derived consumed");
        AccountCardState codeOnly=empty.merge(map("invite",map("code","SYNTH6","use_count",1L,"observed_at",NOW)),NOW);
        require(AccountCardLabels.invitation(codeOnly).equals("SYNTH6 1/30"),"known code and use with absent maximum default to30");
        AccountCardState actualMax=codeOnly.merge(map("invite",map("max_uses",50L,"observed_at",NOW+1)),NOW+1);
        require(AccountCardLabels.invitation(actualMax).equals("SYNTH6 1/50"),"actual valid maximum supersedes presentation default");
        AccountCardState actualZero=codeOnly.merge(map("invite",map("max_uses",0L,"observed_at",NOW+1)),NOW+1);
        require(AccountCardLabels.invitation(actualZero).equals("SYNTH6 1/0")&&!AccountCardLabels.inviteDetails(actualZero).contains("默认30"),"explicit maximum0 is never defaulted");
        AccountCardState unknownUsed=empty.merge(map("invite",map("code","SYNTH6","observed_at",NOW)),NOW);
        require(AccountCardLabels.invitation(unknownUsed).equals("SYNTH6 —/30"),"missing numerator remains unknown");
        AccountCardState longCode=empty.merge(map("invite",map("code","SYNTHETICCODE123","use_count",1L,"max_uses",30L,"observed_at",NOW)),NOW);
        require(AccountCardLabels.invitation(longCode).equals("SYNTHETICCODE123 1/30"),"label model retains full real code, visual truncation belongs to TextView only");
        AccountCardState passive=card.merge(map("invite",map("redeemed",2L,"earned","10 Muse","observed_at",NOW+1)),NOW+1);
        require(AccountCardLabels.inviteDetails(passive).contains("已兑现好友：2")&&AccountCardLabels.inviteDetails(passive).contains("已赚取：10 Muse"),"real passive auxiliary fields preserved in details");
        if(failures>0)throw new AssertionError(failures+"/"+checks+" account label checks failed");
        System.out.println("Account labels: "+checks+" real-model synthetic checks passed.");
    }
}
