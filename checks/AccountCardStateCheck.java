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
        AccountCardState unlimited=partial.merge(map("quota",map("state","unlimited","observed_at",NOW+600004)),NOW+600004);
        require(unlimited.quota(NOW+600004).used()==null&&unlimited.quota(NOW+600004).weekly(NOW+600004).contains("不限量"),"unlimited is valid even with stale reset metadata");
        AccountCardState old=unlimited.merge(map("quota",map("state","known","used_percent",99.,"observed_at",NOW)),NOW+600004);
        require(old.quota(NOW+600004).used()==null,"late older observation cannot overwrite new value");
        AccountCardState bad=old.merge(map("quota",map("state","known","used_percent",101.,"observed_at",NOW+600005)),NOW+600005);
        require(bad.lastSuccess()==old.lastSuccess(),"invalid percent does not create success");
        bad=old.merge(map("invite",map("code","bad\ncode","remaining",-1L,"observed_at",NOW+600005)),NOW+600005);
        require(bad.lastSuccess()==old.lastSuccess(),"invalid invite fields do not create success");
        bad=old.merge(map("quota",map("state","known","used_percent",80.,"observed_at",NOW+9999999)),NOW+600005);
        require(bad.observed("usage")==old.observed("usage"),"future clock invalid observation ignored");
        AccountCardState roundtrip=AccountCardState.read(old.rows(),NOW+600006);
        require(roundtrip.lastSuccess()==old.lastSuccess()&&roundtrip.inviteRemaining()==3L&&roundtrip.inviteCode().equals("SYNTH6"),"persisted field rows restore original values/times");
        require(AccountCardState.read(map(),NOW).quota(NOW)==null,"unknown has no synthetic zero");
        System.out.println("Card field cache: "+checks+" checks passed; synthetic only, no native preferences/network.");
    }
    private static Map<String,Object> map(Object... values){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)r.put((String)values[i],values[i+1]);return r;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
