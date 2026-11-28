// Execute the real header popup / account row builders with host View boundaries.
// This checks account/state/bounds arithmetic, not Android measurement or screenshots.
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
test('header account menu preserves compact layouts, cached state and selection guards',()=>{
 const base=path.resolve(__dirname,'../app/src/main/java/com/muse/workspace/mobile');
 function method(file,signature){const source=fs.readFileSync(path.join(base,file),'utf8'),start=source.indexOf(signature);assert.ok(start>=0,signature);let level=0,quote='',escape=false,line=false,block=false;
  for(let i=source.indexOf('{',start);i<source.length;i++){const c=source[i],n=source[i+1];if(line){if(c==='\n')line=false;continue;}if(block){if(c==='*'&&n==='/'){block=false;i++;}continue;}if(quote){if(escape)escape=false;else if(c==='\\')escape=true;else if(c===quote)quote='';continue;}if(c==='/'&&n==='/'){line=true;i++;continue;}if(c==='/'&&n==='*'){block=true;i++;continue;}if(c==='"'||c==="'"){quote=c;continue;}if(c==='{')level++;if(c==='}'&&--level===0)return source.slice(start,i+1).replaceAll('android.widget.PopupWindow','PopupWindow').replaceAll('android.graphics.Rect','Rect');}throw Error('unclosed method');}
 const menu=method('MainActivity.java','private void showAccountMenu(');
 const choices=['static boolean exhausted(','static int tone(','static String percent(','static String cachedLabel(','static List<AccountStore.Account> sorted(','static View row('].map(s=>method('AccountChoices.java',s)).join('\n').replaceAll('MobileUi ui','Ui ui');
 const out=path.resolve(__dirname,'../app/build/accountMenuLayoutChecks');fs.mkdirSync(out,{recursive:true});
 const java=`package com.muse.workspace.mobile;import java.util.*;import java.util.function.*;
 class Context{}
 public final class AccountMenuLayoutHarness extends Context {
  static final int INK=1,TEXT2=2,MUTED=3,RAISED=4,LINE=5,SURFACE=6,LINE_STRONG=7,ACTIVE_CARD=8,MODE_PRIVATE=0;
  static final String PREFS="muse-ui";static int quotaColor(Double n){return n==null?MUTED:1000+(int)Math.round(n);}
  static final class Color{static final int TRANSPARENT=0;}static final class Gravity{static final int CENTER=1,CENTER_VERTICAL=2,TOP=4,LEFT=8;}
  static final class R{static final class drawable{static final int ic_check=1;}}
  static class Rect{int bottom;}
  static class ViewGroup {static class LayoutParams{static final int WRAP_CONTENT=-2;int width,height,topMargin,start;float weight;LayoutParams(int w,int h){width=w;height=h;}LayoutParams(int w,int h,float f){this(w,h);weight=f;}void setMarginStart(int n){start=n;}}}
  interface Click{void click(View v);}
  static class View {
   final List<View> children=new ArrayList<>();ViewGroup.LayoutParams lp=new ViewGroup.LayoutParams(-1,-2);int minHeight,measuredHeight,measuredWidth,width=320,height=640,padLeft,padRight,padTop,padBottom,x,y;boolean enabled=true,selected,vertical,attached=true;float alpha=1;String text="",description="",tooltip="";Runnable click;
   View(){}View(Object c){}void setPadding(int a,int b,int c,int d){padLeft=a;padTop=b;padRight=c;padBottom=d;}void setMinimumHeight(int n){minHeight=n;}void setBackground(Object o){}void setAlpha(float n){alpha=n;}void setEnabled(boolean n){enabled=n;}void setSelected(boolean n){selected=n;}void setContentDescription(CharSequence s){description=s.toString();}CharSequence getContentDescription(){return description;}void setTooltipText(CharSequence s){tooltip=s.toString();}void setGravity(int n){}void setMaxWidth(int n){}
   int getWidth(){return width;}int getHeight(){return height;}int getPaddingLeft(){return padLeft;}int getPaddingRight(){return padRight;}int getPaddingBottom(){return padBottom;}Object getWindowToken(){return this;}boolean isAttachedToWindow(){return attached;}void getLocationInWindow(int[] out){out[0]=x;out[1]=y;}void getWindowVisibleDisplayFrame(Rect r){r.bottom=y+height;}
   void addView(View v){children.add(v);}void addView(View v,ViewGroup.LayoutParams p){v.lp=p;children.add(v);}int naturalHeight(){int n=children.isEmpty()?14:0;for(View c:children){int h=c.lp.height>=0?c.lp.height:c.naturalHeight();n=vertical?n+h+c.lp.topMargin:Math.max(n,h+c.lp.topMargin);}return Math.max(minHeight,n+padTop+padBottom);}
   void measure(int w,int h){measuredWidth=w>>>2;int n=naturalHeight();measuredHeight=(h&3)==MeasureSpec.AT_MOST?Math.min(h>>>2,n):(h&3)==MeasureSpec.EXACTLY?h>>>2:n;}int getMeasuredHeight(){return measuredHeight;}
   static final class MeasureSpec{static final int EXACTLY=1,AT_MOST=2,UNSPECIFIED=0;static int makeMeasureSpec(int n,int m){return(n<<2)|m;}}
  }
  static class LinearLayout extends View{static class LayoutParams extends ViewGroup.LayoutParams{LayoutParams(int w,int h){super(w,h);}LayoutParams(int w,int h,float f){super(w,h,f);}}}
  static class ScrollView extends View{ScrollView(Object c){vertical=true;}void setVerticalScrollBarEnabled(boolean b){}static class LayoutParams extends ViewGroup.LayoutParams{LayoutParams(int w,int h){super(w,h);}}}
  static class TextView extends View{TextView(String value){text=value;}}
  static class AccountFace extends View{AccountFace(Context c,Ui u,AccountStore.Account a,int s,boolean on,boolean usable,AccountQuota q,Object bitmap){minHeight=s;}}
  static class Ui {
   int dp(float n){return Math.round(n);}int rowHeight(int n){return n;}LinearLayout column(){LinearLayout v=new LinearLayout();v.vertical=true;return v;}LinearLayout row(){return new LinearLayout();}LinearLayout nameRow(){return row();}
   TextView text(String s,float size,int color,boolean bold){return new TextView(s);}TextView meta(String s,float size,int color){return new TextView(s);}TextView metaStrong(String s,float size,int color){return new TextView(s);}TextView single(TextView v){return v;}View iconView(int i,int color,int size){return new View();}Object shape(int a,int b,int c){return new Object();}
   void touch(View v,int a,int b,int c,Runnable action){v.click=action;}void block(LinearLayout p,View v,int top){var lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=top;p.addView(v,lp);}
  }
  static class PopupWindow {
   static final int INPUT_METHOD_NOT_NEEDED=2;final View content;int width,height,x,y;boolean outside,showing;Runnable dismissed;
   PopupWindow(View v,int w,int h,boolean focus){content=v;width=w;height=h;}void setBackgroundDrawable(Object d){}void setElevation(int n){}void setOutsideTouchable(boolean b){outside=b;}void setInputMethodMode(int n){}void setOnDismissListener(Runnable r){dismissed=r;}void setHeight(int h){height=h;}
   void showAtLocation(View root,int gravity,int left,int top){x=left;y=top;showing=true;}void dismiss(){showing=false;if(dismissed!=null)dismissed.run();}
  }
  static class AccountStore {static class Account{final String id,name;boolean usable=true;Account(String id){this.id=id;name="SYNTHETIC "+id;}String proxyId(){return "";}String networkLabel(ProxyStore p){return "系统网络";}}final List<Account> list=new ArrayList<>();List<Account> all(){return list;}boolean usable(Account a,boolean profiles){return a.usable;}}
  static class ProxyProfile{String name,scheme;}static class ProxyStore{ProxyProfile find(String id){return null;}}
  static class QuotaSync{final Map<String,AccountQuota> data=new HashMap<>();AccountQuota value(String id){return data.get(id);}Object avatar(String id){return null;}Card card(String id){return new Card();}static class Card{String plan(){return "";}}}
  static class Browser{final Set<String> parked=new HashSet<>();boolean isParked(String id){return parked.contains(id);}int parkedCount(){return parked.size();}int parkCapacity(){return 3;}}
  static class Preferences{Map<String,Boolean> values=new HashMap<>();boolean getBoolean(String key,boolean fallback){return values.getOrDefault(key,fallback);}Preferences edit(){return this;}Preferences putBoolean(String key,boolean value){values.put(key,value);return this;}void apply(){}}
  final Ui ui=new Ui();final View root=new View();final AccountStore accounts=new AccountStore();final ProxyStore proxies=new ProxyStore();final QuotaSync quotas=new QuotaSync();final Browser browser=new Browser();final Preferences preferences=new Preferences();
  boolean closing,multiProfile=true;int topInset=24,switches;PopupWindow accountMenu;AccountStore.Account selected,chosen;
  boolean isFinishing(){return false;}boolean isDestroyed(){return false;}Preferences getSharedPreferences(String key,int mode){return preferences;}AccountStore.Account current(){return selected;}
  void dismissAccountMenu(){if(accountMenu!=null)accountMenu.dismiss();accountMenu=null;}void switchQuickAccount(AccountStore.Account account){chosen=account;switches++;}
  static final class AccountChoices{${choices}}
  ${menu}
  static int checks;static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
  static List<View> cells(View v){List<View> found=new ArrayList<>();if(v.description.startsWith("SYNTHETIC "))found.add(v);else for(View c:v.children)found.addAll(cells(c));return found;}
  static View text(View v,String value){if(v.text.equals(value))return v;for(View c:v.children){View r=text(c,value);if(r!=null)return r;}return null;}
  static String words(View v){String s=v.text;for(View c:v.children)s+="|"+words(c);return s;}
  static View anchor(){View a=new View();a.x=40;a.y=28;a.width=150;a.height=34;return a;}
  static AccountMenuLayoutHarness app(int count){AccountMenuLayoutHarness a=new AccountMenuLayoutHarness();for(int i=0;i<count;i++)a.accounts.list.add(new AccountStore.Account("A"+i));if(count>0)a.selected=a.accounts.list.get(0);return a;}
  public static void main(String[] args){
   for(boolean grid:new boolean[]{false,true})for(int count:new int[]{0,1,2,3,4,7}){
    var a=app(count);a.preferences.putBoolean("header-account-grid",grid);a.showAccountMenu(anchor());PopupWindow p=a.accountMenu;require(p!=null&&cells(p.content).size()==count,"each account appears once in either layout");
    View scroll=p.content.children.get(1),list=scroll.children.get(0);require(scroll instanceof ScrollView,"only account body scrolls");require(list.children.size()==(count+(grid?1:0))/(grid?2:1),"single/two-column wrapping");
    for(View row:list.children){require(row.children.size()==(grid?2:1),"last grid row retains an empty slot");for(View cell:row.children)require(cell.lp.width==0&&cell.lp.weight==1&&cell.lp.height==(grid?42:40),"compact equal-width slots");}
    require(a.switches==0&&text(p.content,"添加账号")==null&&text(p.content,"管理账号与额度")==null,"opening is read-only and has no management actions");
   }
   var a=app(7);long now=System.currentTimeMillis();a.quotas.data.put("A0",new AccountQuota("",0.,now,now+100000,""));a.quotas.data.put("A1",new AccountQuota("",12.,now-600000,now-100000,""));a.quotas.data.put("A2",new AccountQuota("",null,now-600000,now+100000,""));a.quotas.data.put("A3",new AccountQuota("",100.,now,now+100000,""));a.accounts.list.get(4).usable=false;a.browser.parked.add("A1");a.showAccountMenu(anchor());
   List<View> rows=cells(a.accountMenu.content);View old=rows.stream().filter(v->v.description.startsWith("SYNTHETIC A1，")).findFirst().orElseThrow();View unlimited=rows.stream().filter(v->v.description.startsWith("SYNTHETIC A2，")).findFirst().orElseThrow();
   require(words(old).contains("旧周期")&&words(old).contains("12%"),"old percentage is visible and labeled");require(words(unlimited).contains("上次")&&words(unlimited).contains("不限"),"cached unlimited is distinct from unknown");require(words(a.accountMenu.content).contains("后台保留 1 / 3 个网页"),"footer uses real parked count and capacity");
   for(View row:rows)if(row.description.startsWith("SYNTHETIC A3，")||row.description.startsWith("SYNTHETIC A4，"))require(!row.enabled&&row.click==null,"exhausted/unavailable cannot select");
   text(a.accountMenu.content,"切双列").click.run();require(a.preferences.getBoolean("header-account-grid",false)&&!a.preferences.getBoolean("account-deck-grid",false)&&a.switches==0,"popup layout preference is independent and inert");
   rows=cells(a.accountMenu.content);old=rows.stream().filter(v->v.description.startsWith("SYNTHETIC A1，")).findFirst().orElseThrow();require(words(old).contains("旧周期")&&words(old).contains("12%"),"grid retains both cached state and value");old.click.run();require(a.chosen.id.equals("A1")&&a.accountMenu==null,"selection dismisses and reaches real switch boundary");
   for(int width:new int[]{320,360})for(int height:new int[]{240,640})for(int y:new int[]{0,600}){var h=app(20);h.root.width=width;h.root.height=height;h.root.padBottom=16;View anchor=anchor();anchor.x=width-30;anchor.y=y;h.showAccountMenu(anchor);PopupWindow p=h.accountMenu;if(height-Math.max(28,y+40)-24<92){require(p==null,"insufficient space does not create an offscreen window");continue;}require(p!=null&&p.x>=8&&p.x+p.width<=width-8&&p.y>=28&&p.y+p.height<=height-24,"popup clamped to available window/insets");require(p.height<=490&&p.outside,"height cap and outside dismissal retained");}
   System.out.println("Actual header popup/row builders: "+checks+" account/state/layout/bounds checks passed; NOT native rendering.");
  }
 }`;
 const file=path.join(out,'AccountMenuLayoutHarness.java');fs.writeFileSync(file,java,'utf8');const exe=n=>path.join(process.env.JAVA_HOME,'bin',n+(process.platform==='win32'?'.exe':''));
 for(const [tool,args]of [['javac',['-encoding','UTF-8','-d',out,path.join(base,'AccountQuota.java'),file]],['java',['-cp',out,'com.muse.workspace.mobile.AccountMenuLayoutHarness']]]){const r=spawnSync(exe(tool),args,{encoding:'utf8'});if(r.stdout)process.stdout.write(r.stdout);assert.equal(r.status,0,r.stderr);}
});
