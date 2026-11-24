package com.muse.workspace.mobile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Real model only, synthetic per-field observations; no Android preferences or network. */
public final class AccountCardStateCheck {
    private static int checks;
    private static final long NOW=1_800_000_000_000L;
    public static void main(String[] args){
        Map<String,Object> legacy=map("state","known","used_percent",37.5,"plan_label","SYNTHETIC Plus","observed_at",NOW-1000,"resets_at",NOW+60000,
            "extra",map("remaining",20.,"total",100.,"display_value",null));
        AccountCardState first=AccountCardState.read(legacy,NOW);
        require(first.quota(NOW).used()==37.5&&first.plan().equals("SYNTHETIC Plus"),"legacy cache migration");
        require(first.extraText().equals("剩余 20 / 100"),"extra pool independent from invitation uses");
        AccountCardState failed=first.merge(map("status","request_failed"),NOW+600000);
        require(failed.lastSuccess()==NOW-1000&&failed.quota(NOW+600000).used()==37.5,"transient failure preserves value and actual time");
        require(failed.quota(NOW+600000).weekly(NOW+600000).equals("旧周期 · 已用 38%")&&!failed.quota(NOW+600000).currentWeek(NOW+600000),"old cycle remains visible and not current (existing rounded label)");
        AccountCardState zero=failed.merge(map("quota",map("state","known","used_percent",0.,"observed_at",NOW+600001)),NOW+600001);
        require(zero.quota(NOW+600001).used()==0.,"new valid zero replaces old usage");
        require(zero.quota(NOW+600001).currentWeek(NOW+600001),"new success after old reset belongs to the new cycle");
        require(zero.plan().equals(first.plan())&&zero.observed("plan")==NOW-1000,"missing plan does not clear or refresh plan");
        require(zero.extraText().equals(first.extraText())&&zero.observed("extra_remaining")==NOW-1000,"missing extra preserves exact time");
        AccountCardState invite=zero.merge(map("invite",map("code","SYNTH6","remaining",0L,"redeemed",2L,"earned","10 Muse","observed_at",NOW+600002)),NOW+600002);
        require(invite.inviteCode().equals("SYNTH6")&&invite.inviteRemaining()==0L,"code and zero uses received without inventing quota");
        require(invite.quota(NOW+600002).used()==0.&&invite.observed("usage")==NOW+600001,"invitation success cannot refresh quota time");
        AccountCardState partial=invite.merge(map("invite",map("remaining",3L,"observed_at",NOW+600003)),NOW+600003);
        require(partial.inviteCode().equals("SYNTH6")&&partial.observed("invite_code")==NOW+600002&&partial.inviteRemaining()==3L,"partial invite preserves code/time");
        AccountCardState jsPartial=partial.merge(map("invite",map("code","SYNTH6","code_observed_at",NOW+600002,
            "remaining",0L,"remaining_observed_at",NOW+600004,"use_count",30L,"max_uses",30L,"code_unavailable",true,
            "code_unavailable_observed_at",NOW+600004,"observed_at",NOW+600004)),NOW+600004);
        require(jsPartial.inviteRemaining()==0L&&jsPartial.observed("invite_remaining")==NOW+600004,"reader zero has its own observation time");
        AccountCardState restarted=AccountCardState.read(jsPartial.rows(),NOW+600005).merge(map("invite",map("code","SYNTH6","observed_at",NOW+600005)),NOW+600005);
        require(restarted.observed("invite_code")==NOW+600002&&restarted.observed("usage")==NOW+600001&&restarted.inviteCode().isEmpty(),"new observer passive code cannot revive persisted unavailable code or rewrite original time");
        AccountCardState invalidTime=restarted.merge(map("invite",map("code","NEW777","code_observed_at",NOW+9999999,
            "remaining",1L,"remaining_observed_at",null,"code_unavailable",false,"observed_at",NOW+600005)),NOW+600005);
        AccountCardState recovered=restarted.merge(map("invite",map("code","NEW777","code_unavailable",false,"observed_at",NOW+600006)),NOW+600006);
        require(invalidTime.inviteCode().isEmpty()&&invalidTime.inviteRemaining()==0L&&recovered.inviteCode().equals("NEW777")&&recovered.observed("invite_code")==NOW+600006,"only explicit matching reader receipt restores unavailable code; invalid time cannot");
        AccountCardState unlimited=partial.merge(map("quota",map("state","unlimited","observed_at",NOW+600004)),NOW+600004);
        require(unlimited.quota(NOW+600004).used()==null&&unlimited.quota(NOW+600004).weekly(NOW+600004).contains("不限量"),"unlimited is valid even with stale reset metadata");
        AccountCardState old=unlimited.merge(map("quota",map("state","known","used_percent",99.,"observed_at",NOW)),NOW+600004);
        require(old.quota(NOW+600004).used()==null,"late older observation cannot overwrite new value");
        AccountCardState bad=old.merge(map("quota",map("state","known","used_percent",101.,"observed_at",NOW+600005)),NOW+600005);
        require(bad.lastSuccess()==old.lastSuccess(),"invalid percent does not create success");
        bad=old.merge(map("invite",map("code","bad\ncode","remaining",-1L,"code_unavailable",true,"observed_at",NOW+600005)),NOW+600005);
        require(bad.lastSuccess()==old.lastSuccess(),"invalid invite fields do not create success");
        bad=old.merge(map("quota",map("state","known","used_percent",80.,"observed_at",NOW+9999999)),NOW+600005);
        require(bad.observed("usage")==old.observed("usage"),"future clock invalid observation ignored");
        AccountCardState roundtrip=AccountCardState.read(old.rows(),NOW+600006);
        require(roundtrip.lastSuccess()==old.lastSuccess()&&roundtrip.inviteRemaining()==3L&&roundtrip.inviteCode().equals("SYNTH6"),"persisted field rows restore original values/times");
        require(AccountCardState.read(map(),NOW).quota(NOW)==null,"unknown has no synthetic zero");
        AccountCardState counters=partial.merge(map("invite",map("use_count",0L,"max_uses",30L,"observed_at",NOW+600004)),NOW+600004);
        Map<?,?> counterFields=(Map<?,?>)counters.rows().get("fields");
        require(counterFields.containsKey("invite_use_count")&&counterFields.containsKey("invite_max_uses"),"real invitation numerator and denominator are persisted independently");
        require(((Map<?,?>)counterFields.get("invite_use_count")).get("value").equals(0L),"observed zero numerator is a value");
        AccountCardState missing=counters.merge(map("invite",map("use_count",null,"max_uses",null,"observed_at",NOW+600005)),NOW+600005);
        require(missing.observed("invite_use_count")==NOW+600004&&missing.observed("invite_max_uses")==NOW+600004,"nullable counters retain original values and times");
        AccountCardState denominator=missing.merge(map("invite",map("max_uses",0L,"observed_at",NOW+600006)),NOW+600006);
        require(denominator.observed("invite_max_uses")==NOW+600006&&denominator.observed("invite_use_count")==NOW+600004,"zero denominator is real zero and only its own time changes");
        require(denominator.observed("usage")==partial.observed("usage")&&denominator.inviteRedeemed().equals(partial.inviteRedeemed()),"use_count never becomes quota or redeemed friends");
        AccountCardState restored=AccountCardState.read(denominator.rows(),NOW+600007);
        require(restored.rows().equals(denominator.rows()),"new optional counters and original observations restore from schema2");
        AccountCardState invalid=restored.merge(map("invite",map("use_count",-1L,"max_uses",9007199254740992L,"observed_at",NOW+600008)),NOW+600008);
        require(invalid.rows().equals(restored.rows()),"invalid or unsafe counters cannot replace known values");
        require(AccountCardState.read(partial.rows(),NOW+600007).observed("invite_max_uses")==0,"older cache never invents a denominator");
        System.out.println("Card field cache: "+checks+" checks passed; synthetic only, no native preferences/network.");
    }
    private static Map<String,Object> map(Object... values){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)r.put((String)values[i],values[i+1]);return r;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
