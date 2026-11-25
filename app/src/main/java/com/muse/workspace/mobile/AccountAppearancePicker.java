package com.muse.workspace.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import static com.muse.workspace.mobile.MobileUi.*;

/** 编辑草稿内选择纯色：只预览，不写偏好，不创建网页或刷新配额。 */
final class AccountAppearancePicker {
    final LinearLayout preview, palette;
    private final Activity activity;
    private final MobileUi ui;
    private final AccountStore.Account account;
    private final ProxyStore proxies;
    private final QuotaSync quotas;
    private AccountCardState snapshot;
    private String quotaState;
    private Bitmap avatar;
    private final List<View> swatches = new ArrayList<>();
    private final List<View> choices = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final TextView selectedLabel;
    private AccountAppearance.Skin selected;
    private String name, proxyId;
    private boolean routeEdited;

    AccountAppearancePicker(Activity activity, MobileUi ui, AccountStore.Account account, ProxyStore proxies, QuotaSync quotas) {
        this.activity=activity;this.ui=ui;this.account=account;this.proxies=proxies;this.quotas=quotas;
        selected=AccountAppearance.read(activity,account==null?null:account.id);
        name=account==null?"":account.name;proxyId=account==null?"":account.proxyId();
        snapshot=account==null?AccountCardState.read(null,System.currentTimeMillis()):quotas.card(account.id);
        quotaState=account==null?"待同步":quotas.state(account.id);
        avatar=account==null?null:quotas.avatar(account.id);
        preview=ui.column();preview.setClipToOutline(true);
        palette=ui.column();selectedLabel=ui.text("",13,TEXT2,true);ui.block(palette,selectedLabel,0);
        LinearLayout row=null;
        for(int i=0;i<AccountAppearance.ALL.length;i++){
            if(i%4==0){row=ui.row();row.setGravity(Gravity.TOP);ui.block(palette,row,8);}
            AccountAppearance.Skin skin=AccountAppearance.ALL[i];
            LinearLayout tile=ui.column();
            View sample=new View(activity);sample.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(sample,new LinearLayout.LayoutParams(-1,ui.dp(32)));
            TextView label=ui.text(skin.name,12,TEXT2,false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ui.block(tile,label,3);
            tile.setFocusable(true);tile.setOnClickListener(ignored->{selected=skin;paintSelection();paintPreview();});
            LinearLayout.LayoutParams position=new LinearLayout.LayoutParams(0,-2,1);if(i%4!=0)position.setMarginStart(ui.dp(7));
            row.addView(tile,position);swatches.add(sample);choices.add(tile);labels.add(label);
            if(i==AccountAppearance.ALL.length-1)for(int j=i%4+1;j<4;j++){View spacer=new View(activity);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,1,1);p.setMarginStart(ui.dp(7));row.addView(spacer,p);}
        }
        paintSelection();paintPreview();
    }

    String selectedId(){return selected.id;}

    void update(String name,String proxyId,boolean routeEdited){this.name=name;this.proxyId=proxyId;this.routeEdited=routeEdited;paintPreview();}

    void refreshQuota(){
        if(account==null)return;
        snapshot=quotas.card(account.id);quotaState=quotas.state(account.id);avatar=quotas.avatar(account.id);paintPreview();
    }

    private void paintSelection(){
        selectedLabel.setText("卡片颜色 · "+selected.name);
        for(int i=0;i<choices.size();i++){
            AccountAppearance.Skin skin=AccountAppearance.ALL[i];boolean on=skin.id.equals(selected.id);
            swatches.get(i).setBackground(skin.surface(activity,on,5));choices.get(i).setSelected(on);
            labels.get(i).setText((on?"✓ ":"")+skin.name);labels.get(i).setTextColor(on?INK:TEXT2);
            choices.get(i).setContentDescription(skin.name+(on?"，已选中":"，选择此卡面"));
        }
    }

    private void paintPreview(){
        preview.removeAllViews();preview.setPadding(ui.dp(13),ui.dp(10),ui.dp(13),ui.dp(12));
        preview.setBackground(selected.surface(activity,false,19));
        String shown=name.trim().isEmpty()?"账号名称":name.trim();
        FrameLayout portrait=new FrameLayout(activity);
        TextView fallback=ui.avatar(shown,account==null?"new-account-preview":account.id,30);
        portrait.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        if(avatar!=null){ImageView image=new ImageView(activity);image.setImageBitmap(avatar);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(ui.shape(RAISED,15,0));image.setClipToOutline(true);portrait.addView(image,new FrameLayout.LayoutParams(-1,-1));}
        LinearLayout words=ui.column();
        TextView title=AccountCardTypography.name(ui,selected,shown);
        TextView badge=ui.tag(snapshot.plan().isEmpty()?"待同步":snapshot.plan());selected.badge(badge,ui);
        ui.block(words,new AccountNameLine(activity,title,badge,ui),0);
        ProxyProfile profile=proxies.find(proxyId);
        String route=profile!=null?profile.name:!proxyId.isEmpty()?"未配置":!routeEdited&&account!=null&&account.hasProxy()&&account.proxyId().isEmpty()?account.networkLabel(proxies):"直连";
        ui.block(words,AccountCardTop.proxyLine(activity,ui,selected,route),4);
        ui.block(preview,new AccountCardTop(activity,ui,portrait,words,null,null,true),0);
        QuotaCard quota=new QuotaCard(activity,ui,value->badge.setText(value),()->{},selected);
        quota.update(snapshot,quotaState);ui.block(preview,quota.view,10);
        preview.setContentDescription("卡面预览，"+selected.name+"，"+shown+"，代理 "+route+"，周额度 "+quota.weeklyText());
    }
}
