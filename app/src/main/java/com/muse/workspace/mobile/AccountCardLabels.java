package com.muse.workspace.mobile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Read-only label presentation. Unknowns and independently cached observations stay distinct. */
final class AccountCardLabels {
    static String extra(AccountCardState card) {
        return "额外 "+compact(consumed(card))+"/"+compact(card.extraTotal());
    }

    static String invitation(AccountCardState card) {
        String code=card.inviteCode();
        return (code.isEmpty()?"待获取":code)+" "+count(card.inviteUseCount())+"/"+count(card.inviteMaxUses()==null?30L:card.inviteMaxUses());
    }

    static String extraDetails(AccountCardState card) {
        return "已消耗："+number(consumed(card))+"\n按总量 − 剩余额度计算；来源缺失或矛盾时为未知"
            +"\n\n剩余额度："+number(card.extraRemaining())+time(card.observed("extra_remaining"))
            +"\n\n总量："+number(card.extraTotal())+time(card.observed("extra_total"))
            +"\n数值来源：topupBalance 为余量，topupTotal 为总量"
            +"\n\n原显示："+(card.extraDisplay().isEmpty()?"未知":card.extraDisplay())+time(card.observed("extra_display"));
    }

    static String inviteDetails(AccountCardState card) {
        return "邀请码："+(card.inviteCode().isEmpty()?"未知":card.inviteCode())+time(card.observed("invite_code"))
            +"\n\n已使用次数："+count(card.inviteUseCount())+time(card.observed("invite_use_count"))
            +"\n\n次数上限："+(card.inviteMaxUses()==null?"默认30（未获取到真实上限）":count(card.inviteMaxUses())+time(card.observed("invite_max_uses")))
            +"\n\n剩余次数："+count(card.inviteRemaining())+time(card.observed("invite_remaining"))
            +"\n\n已兑现好友："+count(card.inviteRedeemed())+time(card.observed("invite_redeemed"))
            +"\n\n已赚取："+(card.inviteEarned().isEmpty()?"未知":card.inviteEarned())+time(card.observed("invite_earned"));
    }

    private static Double consumed(AccountCardState card){
        Double total=card.extraTotal(),remaining=card.extraRemaining();
        if(total==null||remaining==null||remaining>total)return null;
        return BigDecimal.valueOf(total).subtract(BigDecimal.valueOf(remaining)).doubleValue();
    }

    static boolean cached(AccountCardState card,long now,long quotaObserved) {
        for(String field:new String[]{"extra_remaining","extra_total","extra_display","invite_code","invite_remaining","invite_use_count","invite_max_uses","invite_redeemed","invite_earned"}) {
            long at=card.observed(field);
            if(at>0 && (now-at>=300000 || at<quotaObserved))return true;
        }
        return false;
    }

    private static String count(Long n){return n==null?"—":n.toString();}
    private static String number(Double n){return n==null?"未知":BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();}
    private static String compact(Double n){
        if(n==null)return "—";
        BigDecimal value=BigDecimal.valueOf(n);
        if(n>=100000000)return value.divide(BigDecimal.valueOf(100000000),2,RoundingMode.DOWN).stripTrailingZeros().toPlainString()+"亿";
        if(n>=10000)return value.divide(BigDecimal.valueOf(10000),2,RoundingMode.DOWN).stripTrailingZeros().toPlainString()+"万";
        return value.stripTrailingZeros().toPlainString();
    }
    private static String time(long at){return at<=0?"\n成功时间：未知":"\n成功时间："+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.SIMPLIFIED_CHINESE).format(new Date(at));}
}
