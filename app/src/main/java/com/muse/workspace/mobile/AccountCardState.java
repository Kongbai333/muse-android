package com.muse.workspace.mobile;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small independent last-success fields. Failure/status is never a new observation. */
final class AccountCardState {
    private record Field(Object value,long observed) { }
    private final Map<String,Field> fields;
    private AccountCardState(Map<String,Field> fields){this.fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));}

    static AccountCardState read(Map<String,Object> raw,long now){
        AccountCardState empty=new AccountCardState(Collections.emptyMap());
        if(raw==null)return empty;
        if(raw.get("schema") instanceof Number n&&n.intValue()==2){
            Map<String,Field> restored=new LinkedHashMap<>();Map<String,Object> saved=object(raw.get("fields"));
            if(saved!=null)for(String key:saved.keySet()){
                Map<String,Object> item=object(saved.get(key));if(item!=null)put(restored,key,item.get("value"),time(item,now),now);
            }
            return new AccountCardState(restored);
        }
        Map<String,Object> legacy=new LinkedHashMap<>();legacy.put("quota",raw);
        return empty.merge(legacy,now);
    }

    AccountCardState merge(Map<String,Object> reply,long now){
        Map<String,Field> next=new LinkedHashMap<>(fields);
        if(reply==null)return new AccountCardState(next);
        Map<String,Object> quota=object(reply.get("quota"));
        if(quota!=null){
            long at=time(quota,now);String state=text(quota.get("state"),16);
            Double used=number(quota.get("used_percent"));
            if("unlimited".equals(state)||"known".equals(state)&&used!=null&&used<=100){
                Map<String,Object> usage=new LinkedHashMap<>();usage.put("state",state);usage.put("used_percent","unlimited".equals(state)?null:used);
                put(next,"usage",usage,at,now);
            }
            put(next,"plan",quota.get("plan_label"),at,now);
            put(next,"reset",quota.get("resets_at"),at,now);
            mergeExtra(next,object(quota.get("extra")),at,now);
        }
        Map<String,Object> plan=object(reply.get("plan"));if(plan!=null)put(next,"plan",plan.get("label"),time(plan,now),now);
        mergeExtra(next,object(reply.get("extra")),0,now);
        Map<String,Object> invite=object(reply.get("invite"));
        if(invite!=null){
            long at=time(invite,now);
            put(next,"invite_code",invite.get("code"),fieldTime(invite,"code",at),now);
            put(next,"invite_remaining",invite.get("remaining"),fieldTime(invite,"remaining",at),now);
            put(next,"invite_use_count",invite.get("use_count"),fieldTime(invite,"use_count",at),now);
            put(next,"invite_max_uses",invite.get("max_uses"),fieldTime(invite,"max_uses",at),now);
            put(next,"invite_redeemed",invite.get("redeemed"),fieldTime(invite,"redeemed",at),now);
            put(next,"invite_earned",invite.get("earned"),fieldTime(invite,"earned",at),now);
            Long uses=integer(invite.get("use_count")),maximum=integer(invite.get("max_uses"));
            long unavailableAt=fieldTime(invite,"code_unavailable",at);
            Field count=next.get("invite_use_count"),limit=next.get("invite_max_uses");
            if(Boolean.TRUE.equals(invite.get("code_unavailable"))&&uses!=null&&maximum!=null&&maximum>0&&uses.equals(maximum)
                &&count!=null&&limit!=null&&count.observed==unavailableAt&&limit.observed==unavailableAt&&uses.equals(count.value)&&maximum.equals(limit.value))
                put(next,"invite_code_unavailable",true,unavailableAt,now);
            else if(Boolean.FALSE.equals(invite.get("code_unavailable"))){
                Field code=next.get("invite_code");long restoredAt=fieldTime(invite,"code_unavailable",at);
                String incoming=text(invite.get("code"),16);
                if(code!=null&&code.observed==restoredAt&&incoming!=null&&code.value.equals(incoming.toUpperCase(Locale.ROOT)))
                    put(next,"invite_code_unavailable",false,restoredAt,now);
            }
        }
        return new AccountCardState(next);
    }

    private static void mergeExtra(Map<String,Field> next,Map<String,Object> extra,long fallback,long now){
        if(extra==null)return;long at=extra.containsKey("observed_at")?time(extra,now):fallback;
        Double remaining=number(extra.get("remaining")),total=number(extra.get("total"));
        if(remaining!=null&&total!=null&&remaining>total)return;
        put(next,"extra_remaining",remaining,at,now);put(next,"extra_total",total,at,now);put(next,"extra_display",extra.get("display_value"),at,now);
    }

    Map<String,Object> rows(){
        Map<String,Object> saved=new LinkedHashMap<>();
        for(var entry:fields.entrySet()){
            Object value=entry.getValue().value;
            if(value instanceof Map<?,?> map)value=new LinkedHashMap<>(map);
            Map<String,Object> field=new LinkedHashMap<>();field.put("value",value);field.put("observed_at",entry.getValue().observed);saved.put(entry.getKey(),field);
        }
        Map<String,Object> root=new LinkedHashMap<>();root.put("schema",2);root.put("fields",saved);return root;
    }

    AccountQuota quota(long now){
        Map<String,Object> usage=object(value("usage"));if(usage==null)return null;
        return AccountQuota.read(plan(),(String)usage.get("state"),number(usage.get("used_percent")),observed("usage"),longValue("reset"),
            number(value("extra_remaining")),number(value("extra_total")),extraText().isEmpty()?null:extraText(),now);
    }
    String plan(){Object value=value("plan");return value instanceof String?(String)value:"";}
    String extraText(){
        Object label=value("extra_display");Double remaining=number(value("extra_remaining")),total=number(value("extra_total"));
        if(label instanceof String&&observed("extra_display")>=observed("extra_remaining"))return (String)label;
        if(remaining==null)return "";
        return "剩余 "+decimal(remaining)+(total!=null&&total>=remaining?" / "+decimal(total):"");
    }
    String inviteCode(){
        if(Boolean.TRUE.equals(value("invite_code_unavailable"))&&observed("invite_code_unavailable")>=observed("invite_code"))return "";
        Object value=value("invite_code");return value instanceof String?(String)value:"";
    }
    boolean inviteExhausted(){Long used=inviteUseCount(),maximum=inviteMaxUses();return used!=null&&maximum!=null&&maximum>0&&used>=maximum;}
    Double extraRemaining(){return number(value("extra_remaining"));}
    Double extraTotal(){return number(value("extra_total"));}
    String extraDisplay(){Object value=value("extra_display");return value instanceof String?(String)value:"";}
    Long inviteRemaining(){return value("invite_remaining") instanceof Long n?n:null;}
    Long inviteUseCount(){return value("invite_use_count") instanceof Long n?n:null;}
    Long inviteMaxUses(){return value("invite_max_uses") instanceof Long n?n:null;}
    Long inviteRedeemed(){return value("invite_redeemed") instanceof Long n?n:null;}
    String inviteEarned(){Object value=value("invite_earned");return value instanceof String?(String)value:"";}
    long observed(String field){Field value=fields.get(field);return value==null?0:value.observed;}
    long lastSuccess(){long newest=0;for(Field field:fields.values())newest=Math.max(newest,field.observed);return newest;}
    private Object value(String key){Field field=fields.get(key);return field==null?null:field.value;}
    private long longValue(String key){Object value=value(key);return value instanceof Long n?n:0;}

    private static void put(Map<String,Field> fields,String key,Object value,long at,long now){
        if(at<=0||at>now+30000||fields.containsKey(key)&&fields.get(key).observed>=at)return;
        Object normalized=null;
        switch(key){
            case "usage"->{Map<String,Object> usage=object(value);if(usage!=null){String state=text(usage.get("state"),16);Double used=number(usage.get("used_percent"));
                if("unlimited".equals(state)||"known".equals(state)&&used!=null&&used<=100){Map<String,Object> copy=new LinkedHashMap<>();copy.put("state",state);copy.put("used_percent","unlimited".equals(state)?null:used);normalized=Collections.unmodifiableMap(copy);}}}
            case "plan"->normalized=text(value,32);
            case "reset"->{Long n=integer(value);if(n!=null&&n>0)normalized=n;}
            case "extra_remaining","extra_total"->normalized=number(value);
            case "extra_display"->normalized=text(value,96);
            case "invite_code"->{String code=text(value,16);if(code!=null&&code.toUpperCase(Locale.ROOT).matches("[A-Z0-9]{6,16}"))normalized=code.toUpperCase(Locale.ROOT);}
            case "invite_remaining","invite_use_count","invite_max_uses","invite_redeemed"->normalized=integer(value);
            case "invite_code_unavailable"->normalized=value instanceof Boolean?value:null;
            case "invite_earned"->normalized=text(value,96);
            default->{return;}
        }
        if(normalized!=null)fields.put(key,new Field(normalized,at));
    }
    private static String decimal(double n){return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();}
    private static String text(Object value,int max){if(!(value instanceof String s))return null;String clean=s.trim();return clean.isEmpty()||clean.length()>max||clean.chars().anyMatch(Character::isISOControl)?null:clean;}
    private static Double number(Object value){if(!(value instanceof Number n))return null;double d=n.doubleValue();return Double.isFinite(d)&&d>=0?d:null;}
    private static Long integer(Object value){Double n=number(value);return n!=null&&n<=9007199254740991L&&n==Math.floor(n)?n.longValue():null;}
    private static long fieldTime(Map<String,Object> data,String field,long fallback){String key=field+"_observed_at";if(!data.containsKey(key))return fallback;Long at=integer(data.get(key));return at==null?0:at;}
    private static long time(Map<String,Object> data,long now){Long at=integer(data.get("observed_at"));return at==null||at<=0||at>now+30000?0:at;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:null;}
}
