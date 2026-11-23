// Actual method bodies with host OS/View boundaries. Never Android rendering evidence.
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const base=path.resolve(__dirname,'../app/src/main/java/com/muse/workspace/mobile');
function method(file,signature){const s=fs.readFileSync(path.join(base,file),'utf8'),start=s.indexOf(signature);assert.ok(start>=0,signature);let level=0,quote='',escape=false,line=false,block=false;
 for(let i=s.indexOf('{',start);i<s.length;i++){const c=s[i],n=s[i+1];if(line){if(c==='\n')line=false;continue;}if(block){if(c==='*'&&n==='/'){block=false;i++;}continue;}if(quote){if(escape)escape=false;else if(c==='\\')escape=true;else if(c===quote)quote='';continue;}if(c==='/'&&n==='/'){line=true;i++;continue;}if(c==='/'&&n==='*'){block=true;i++;continue;}if(c==='"'||c==="'"){quote=c;continue;}if(c==='{')level++;if(c==='}'&&--level===0)return s.slice(start,i+1);}throw Error(signature);}
function javaCheck(name,body,files=[]){const out=path.resolve(__dirname,'../app/build/cardPolishBoundaryChecks');fs.mkdirSync(out,{recursive:true});const file=path.join(out,name+'.java');fs.writeFileSync(file,'package com.muse.workspace.mobile;import java.util.*;import java.util.function.*;\n'+body);
 const exe=n=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',n+(process.platform==='win32'?'.exe':'')):n;
 let r=spawnSync(exe('javac'),['-encoding','UTF-8','-d',out,...files.map(n=>path.join(base,n)),file],{encoding:'utf8'});assert.equal(r.status,0,r.stderr);
 r=spawnSync(exe('java'),['-cp',out,'com.muse.workspace.mobile.'+name],{encoding:'utf8'});assert.equal(r.status,0,r.stderr);process.stdout.write(r.stdout);}

test('polish: actual TickBar paint is green only for normal filled usage, with day/night contrast',()=>{
 const draw=method('MobileUi.java','@Override protected void onDraw(');
 javaCheck('PolishTickHarness',`class TickBase{protected void onDraw(PolishTickHarness.Canvas c){}int getWidth(){return 200;}int getHeight(){return 10;}}
 public final class PolishTickHarness extends TickBase{
  static final int LINE=1,LINE_STRONG=2,INK=3,ACCENT=4;static int PROGRESS;final Paint paint=new Paint();Double used;boolean known;int dp(float n){return Math.round(n);}
  static final class Paint{int color;void setColor(int n){color=n;}}static final class Canvas{List<Integer> colors=new ArrayList<>();void drawRoundRect(float a,float b,float c,float d,float e,float f,Paint p){colors.add(p.color);}}
  ${draw}
  static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public static void main(String[] args){for(int green:new int[]{0xFF16803D,0xFF4ADE80}){PROGRESS=green;PolishTickHarness h=new PolishTickHarness();h.known=true;h.used=25.;Canvas c=new Canvas();h.onDraw(c);require(c.colors.size()==20,"twenty segments retained");for(int i=0;i<20;i++)require(c.colors.get(i)==(i<5?green:LINE_STRONG),"normal measured filled segment must use dedicated green");h.used=85.;c=new Canvas();h.onDraw(c);require(c.colors.get(0)==ACCENT,"real high usage keeps warning accent");h.used=0.;c=new Canvas();h.onDraw(c);require(c.colors.stream().allMatch(x->x==LINE_STRONG),"known0 is empty neutral track");h.used=null;c=new Canvas();h.onDraw(c);require(c.colors.stream().allMatch(x->x==LINE_STRONG),"unlimited stays neutral");h.known=false;c=new Canvas();h.onDraw(c);require(c.colors.stream().allMatch(x->x==LINE),"unknown stays neutral");}System.out.println("Actual TickBar semantic paint branches passed; no Canvas rendering.");}
 }`);
 const color=(theme,name)=>{const s=fs.readFileSync(path.resolve(base,'../../../../../res',theme,'colors.xml'),'utf8');const m=new RegExp('name="'+name+'">#([A-Fa-f0-9]{8})').exec(s);assert.ok(m,name);const n=parseInt(m[1],16);return[(n>>>16)&255,(n>>>8)&255,n&255,((n>>>24)&255)/255];};
 const luminance=c=>c.slice(0,3).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4;}).reduce((s,v,i)=>s+v*[.2126,.7152,.0722][i],0);
 const contrast=(a,b)=>{let x=luminance(a),y=luminance(b);return(Math.max(x,y)+.05)/(Math.min(x,y)+.05);};
 for(const theme of ['values','values-night']){const green=color(theme,'muse_progress'),bg=color(theme,'muse_bg'),raised=color(theme,'muse_raised'),line=color(theme,'muse_line_strong');const track=line.map((v,i)=>i===3?1:v*line[3]+bg[i]*(1-line[3]));assert.ok(green[1]>green[0]&&green[1]>green[2],'semantic green');for(const surface of [bg,raised,track])assert.ok(contrast(green,surface)>=3,'nontext progress contrast');console.log(theme+' progress contrast over BG/raised/track: '+[bg,raised,track].map(s=>contrast(green,s).toFixed(2)).join('/'));}
});

test('polish: actual preference migration persists three modes and mode icons independently of resolved night',()=>{
 const names=['static ThemeMode themeMode(','static void applyNightMode(','static void setThemeMode(','static int themeIcon(','static String themeDescription('];
 javaCheck('PolishThemePrefsHarness',`public final class PolishThemePrefsHarness{
  static final String PREFS="muse-ui",THEME_KEY="theme-darkroom",THEME_MODE_KEY="theme-mode";static boolean NIGHT;
  static final class R{static final class drawable{static final int ic_theme_light=1,ic_theme=2,ic_theme_system=3;}}
  static final class SharedPreferences{Map<String,Object> data=new HashMap<>();boolean contains(String k){return data.containsKey(k);}String getString(String k,String fallback){return data.get(k) instanceof String s?s:fallback;}boolean getBoolean(String k,boolean fallback){return data.get(k) instanceof Boolean b?b:fallback;}Editor edit(){return new Editor(this);}static final class Editor{SharedPreferences p;Editor(SharedPreferences p){this.p=p;}Editor putString(String k,String v){p.data.put(k,v);return this;}Editor remove(String k){p.data.remove(k);return this;}void apply(){}}}
  static final class Context{static final int MODE_PRIVATE=0;SharedPreferences prefs;Context(SharedPreferences p){prefs=p;}SharedPreferences getSharedPreferences(String n,int mode){return prefs;}}
  static final class AppCompatDelegate{static final int MODE_NIGHT_FOLLOW_SYSTEM=-1,MODE_NIGHT_NO=1,MODE_NIGHT_YES=2;static int mode=-100;static int getDefaultNightMode(){return mode;}static void setDefaultNightMode(int m){mode=m;}}
  ${names.map(n=>method('MobileUi.java',n)).join('\n')}
  static int checks;static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
  public static void main(String[] args){for(boolean old:new boolean[]{false,true}){SharedPreferences p=new SharedPreferences();p.data.put(THEME_KEY,old);p.data.put("other","SYNTHETIC keep");Context c=new Context(p);require(themeMode(c)==(old?ThemeMode.DARK:ThemeMode.LIGHT),"explicit old boolean migrates exactly");require(!p.contains(THEME_KEY)&&p.getString(THEME_MODE_KEY,"").equals(old?"dark":"light"),"string persisted and old key removed only");require(p.data.get("other").equals("SYNTHETIC keep"),"other preferences retained");}
   SharedPreferences p=new SharedPreferences();Context c=new Context(p);require(themeMode(c)==ThemeMode.SYSTEM,"no old setting defaults follow system");applyNightMode(c);require(AppCompatDelegate.mode==-1,"system maps to framework follow system");
   setThemeMode(c,ThemeMode.LIGHT);require(AppCompatDelegate.mode==1&&themeIcon(c)==R.drawable.ic_theme_light,"light persists and uses sun");
   setThemeMode(c,ThemeMode.DARK);NIGHT=true;require(AppCompatDelegate.mode==2&&themeIcon(c)==R.drawable.ic_theme,"dark persists and uses moon");
   Context restart=new Context(p);require(themeMode(restart)==ThemeMode.DARK,"restart reads explicit mode");
   setThemeMode(c,ThemeMode.SYSTEM);require(themeIcon(c)==R.drawable.ic_theme_system&&themeDescription(c).contains("黑夜"),"same actual dark color still changes selected mode icon");NIGHT=false;require(themeDescription(c).contains("白天")&&themeMode(c)==ThemeMode.SYSTEM,"system color change does not overwrite mode");
   p.data.put(THEME_MODE_KEY,"invalid");p.data.put(THEME_KEY,false);require(themeMode(c)==ThemeMode.LIGHT,"invalid new preference retains a valid legacy explicit choice");
   System.out.println("Actual theme preferences/icons: "+checks+" host persistence/mapping checks passed.");}
 }`,['ThemeMode.java']);
});

test('polish: actual four-cell toolbar is equal sized and retains session creation and transfer selection',()=>{
 const bar=method('AccountSidebar.java','private View settingsBar('),icon=method('AccountSidebar.java','private View settingsIcon('),section=method('AccountSidebar.java','private void updateSection('),choice=method('AccountSidebar.java','private void showTransferChoices(');
 javaCheck('PolishToolbarHarness',`public final class PolishToolbarHarness{
  static int INK=1,TEXT2=2,ACCENT=3,ACCENT_SOFT=4;static final int VISIBLE=0,GONE=8;
  static final class Color{static final int TRANSPARENT=0;}static class Context{}static final class R{static final class drawable{static final int ic_route=1,ic_theme_light=2,ic_theme=3,ic_theme_system=4,ic_web_refresh=5,ic_transfer=6;}}
  static class View{List<View> children=new ArrayList<>();Object lp;Runnable click;String text="",description="";int padding,minimumWidth,height,icon,color,scale;boolean selected;void setVisibility(int x){}void setSelected(boolean v){selected=v;}void setMinimumWidth(int n){minimumWidth=n;}void setBackground(Object x){}void setLayoutParams(Object p){lp=p;}void setContentDescription(String s){description=s;}void setTooltipText(String s){}void addView(View v){children.add(v);}void addView(View v,Object p){v.lp=p;children.add(v);}void removeAllViews(){children.clear();}}
  static class ImageView extends View{static final class ScaleType{static final int FIT_CENTER=1;}}static final class ImageButton extends ImageView{void setScaleType(int s){scale=s;}}
  static final class LinearLayout extends View{static final class LayoutParams{int width,height;float weight;LayoutParams(int w,int h){width=w;height=h;}LayoutParams(int w,int h,float f){this(w,h);weight=f;}}}
  static final class Tabs{void select(int n){}void setLabel(int n,String s){}}
  interface Choice{void click(Dialog d,int n);}static final class Dialog{String[] labels;Choice choice;boolean dismissed;void dismiss(){dismissed=true;}}static final class Builder{Dialog d=new Dialog();Builder setTitle(String t){return this;}Builder setItems(String[] a,Choice c){d.labels=a;d.choice=c;return this;}Builder setNegativeButton(String t,Object r){if(r!=null)throw new AssertionError();return this;}void show(){MobileUi.dialog=d;}}
  static final class MobileUi{static ThemeMode mode=ThemeMode.SYSTEM;static boolean NIGHT;static Dialog dialog;static ThemeMode themeMode(Context c){return mode;}static Builder dialog(Context c){return new Builder();}
   ${method('MobileUi.java','static int themeIcon(')}
   ${method('MobileUi.java','static String themeDescription(')}
   int dp(float x){return Math.round(x);}LinearLayout row(){return new LinearLayout();}View primary(String t,Runnable r){View v=new View();v.text=t;v.click=r;return v;}View note(String t,int n){View v=new View();v.text=t;return v;}ImageButton iconButton(int i,String label,int tint,Runnable r){ImageButton v=new ImageButton();v.icon=i;v.color=tint;v.description=label;v.click=r;return v;}void pad(View v,int x,int y){if(x!=y)throw new AssertionError();v.padding=x;}Object touchable(int a,int b,int c){return new Object();}}
  static final class Host{int create,edit,proxy,theme,update,pkg,cookie,export;void createStudioSession(){create++;}void editAccount(Object ignored){edit++;}void openProxyManager(){proxy++;}void toggleTheme(){theme++;}void checkForUpdates(){update++;}void importAccounts(){pkg++;}void importCookieText(){cookie++;}void exportAccounts(){export++;}}
  static final class Proxies{List<String> all(){return new ArrayList<>();}}
  final MobileUi ui=new MobileUi();final Host host=new Host();final Proxies proxies=new Proxies();final Tabs tabs=new Tabs();final View sessionScroll=new View(),accountScroll=new View();final LinearLayout footer=new LinearLayout(),navigation=new LinearLayout();boolean sessionsShown,picking,canAddAccount=true,proxyActive;int sessionCount,accountCount;Context getContext(){return new Context();}View deleteAction(){return new View();}
  ${section}
  ${bar}
  ${icon}
  ${choice}
  static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public static void main(String[] args){PolishToolbarHarness h=new PolishToolbarHarness();h.updateSection();require(h.footer.children.size()==1,"no old import/export row");require(h.navigation.children.size()==1,"no separate studio navigation");View row=h.navigation.children.get(0);require(row.children.size()==4,"exactly four settings actions");for(View v:row.children){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.lp;require(p.width==0&&p.weight==1&&p.height==48&&v.minimumWidth==48&&v.padding==14&&v.scale==ImageView.ScaleType.FIT_CENTER,"equal hit areas/padding and20dp square icon regions");}
   row.children.get(0).click.run();row.children.get(1).click.run();row.children.get(2).click.run();row.children.get(3).click.run();require(h.host.proxy==1&&h.host.theme==1&&h.host.update==1&&h.host.pkg==0&&h.host.export==0,"original callbacks and transfer selection only");require(MobileUi.dialog.labels.length==3,"three transfer operations remain");
   MobileUi.mode=ThemeMode.LIGHT;h.updateSection();require(h.navigation.children.get(0).children.get(1).icon==R.drawable.ic_theme_light,"sun in light mode");MobileUi.mode=ThemeMode.DARK;h.updateSection();require(h.navigation.children.get(0).children.get(1).icon==R.drawable.ic_theme,"moon in dark mode");MobileUi.mode=ThemeMode.SYSTEM;MobileUi.NIGHT=true;TEXT2=12;h.updateSection();View system=h.navigation.children.get(0).children.get(1);require(system.icon==R.drawable.ic_theme_system&&system.color==12&&system.description.contains("黑夜"),"system glyph and current configuration tint/description refresh");
   h.sessionsShown=true;h.updateSection();require(h.footer.children.get(0).text.equals("新建会话"),"session creation retained");h.footer.children.get(0).click.run();require(h.host.create==1,"original create callback retained");System.out.println("Actual toolbar construction/callback/theme-icon checks passed; NOT native rendering.");}
 }`,['ThemeMode.java']);
});

test('polish: theme selection and effective-configuration callbacks preserve state without recreate',()=>{
 const select=method('MainActivity.java','private void selectThemeMode('),choose=method('MainActivity.java','@Override public void toggleTheme(');
 const config=method('MainActivity.java','@Override public void onConfigurationChanged(').replaceAll('@androidx.annotation.NonNull ','').replaceAll('android.content.res.Configuration','Configuration');
 javaCheck('PolishThemeControllerHarness',`class PolishThemeBase{
  final PolishThemeControllerHarness.Resources resources=new PolishThemeControllerHarness.Resources();boolean systemNight=true;
  public void toggleTheme(){}public void onConfigurationChanged(PolishThemeControllerHarness.Configuration c){systemNight=(c.uiMode&48)==32;applyPolicy();}
  void applyPolicy(){resources.configuration.uiMode=PolishThemeControllerHarness.MobileUi.mode==ThemeMode.SYSTEM?(systemNight?32:16):PolishThemeControllerHarness.MobileUi.mode==ThemeMode.DARK?32:16;}
  PolishThemeControllerHarness.Resources getResources(){return resources;}
 }
 public final class PolishThemeControllerHarness extends PolishThemeBase{
  static final class Configuration{static final int UI_MODE_NIGHT_MASK=48,UI_MODE_NIGHT_YES=32;int uiMode;Configuration(boolean night){uiMode=night?32:16;}}
  static final class Resources{Configuration configuration=new Configuration(true);Configuration getConfiguration(){return configuration;}}
  static final class Root{List<Runnable> tasks=new ArrayList<>();void post(Runnable r){tasks.add(r);}void run(){List<Runnable> copy=new ArrayList<>(tasks);tasks.clear();for(Runnable r:copy)r.run();}}
  static final class Handler{void removeCallbacks(Runnable r){}}static final class Studio{final Object running=new Object();int flushes;void flushSessions(){flushes++;}}static final class Sidebar{boolean open=true,sessions=false;boolean isOpen(){return open;}boolean showingSessions(){return sessions;}}
  interface Choice{void choose(Dialog d,int i);}static final class Dialog{String[] labels;int checked;Choice choice;boolean dismissed;void dismiss(){dismissed=true;}}
  static final class Builder{Dialog d=new Dialog();Builder setTitle(String s){return this;}Builder setSingleChoiceItems(String[] labels,int checked,Choice choice){d.labels=labels;d.checked=checked;d.choice=choice;return this;}Builder setNegativeButton(String s,Object r){if(r!=null)throw new AssertionError();return this;}void show(){MobileUi.dialog=d;}}
  static final class MobileUi{static ThemeMode mode=ThemeMode.DARK;static boolean NIGHT=true;static Dialog dialog;static ThemeMode themeMode(PolishThemeControllerHarness h){return mode;}static void setThemeMode(PolishThemeControllerHarness h,ThemeMode m){mode=m;}static Builder dialog(PolishThemeControllerHarness h){return new Builder();}}
  final Root root=new Root();final Handler draftHandler=new Handler();final Runnable persistComposerHeight=()->{};final Studio studio=new Studio();final Sidebar sidebar=new Sidebar();final Object browser=new Object(),accountProxy=new Object();boolean ready=true,closing,themeReopenSidebar,themeShowSessions=true,themeNight=true;int themeGeneration,themeApplied,applied,syncs;
  void syncStudioDraft(){syncs++;}boolean isFinishing(){return false;}boolean isDestroyed(){return false;}void applyTheme(){themeApplied=themeGeneration;applied++;MobileUi.NIGHT=themeNight=(resources.configuration.uiMode&48)==32;}
  Delegate getDelegate(){return new Delegate(this);}static final class Delegate{PolishThemeControllerHarness h;Delegate(PolishThemeControllerHarness h){this.h=h;}boolean applyDayNight(){int before=h.resources.configuration.uiMode;h.applyPolicy();boolean changed=before!=h.resources.configuration.uiMode;if(changed)h.onConfigurationChanged(new Configuration(h.systemNight));return changed;}}
  ${select}
  ${choose}
  ${config}
  static int checks;static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
  public static void main(String[] args){PolishThemeControllerHarness h=new PolishThemeControllerHarness();Object page=h.browser,proxy=h.accountProxy,task=h.studio.running;h.toggleTheme();Dialog d=MobileUi.dialog;
   require(Arrays.equals(d.labels,new String[]{"白天","黑夜","跟随系统"})&&d.checked==1,"native selector exposes all modes and persisted selection");require(h.studio.flushes==0&&MobileUi.mode==ThemeMode.DARK,"opening chooser is inert");d.dismiss();require(h.applied==0,"cancel is inert");
   h.toggleTheme();d=MobileUi.dialog;d.choice.choose(d,2);require(d.dismissed&&MobileUi.mode==ThemeMode.SYSTEM,"choice dismisses and persists mode");require(h.themeReopenSidebar&&!h.themeShowSessions,"current account tab/open state captured before rebind");h.root.run();require(h.applied==1&&MobileUi.NIGHT,"same actual dark color still refreshes mode icon once");require(h.browser==page&&h.accountProxy==proxy&&h.studio.running==task,"controller retains page, proxy and running job identities");
   MobileUi.NIGHT=false;h.onConfigurationChanged(new Configuration(false));require(h.applied==2&&!MobileUi.NIGHT&&MobileUi.mode==ThemeMode.SYSTEM,"follow-system compares rendered color even if another loader already changed global NIGHT");
   MobileUi.mode=ThemeMode.LIGHT;MobileUi.NIGHT=h.themeNight=false;h.resources.configuration.uiMode=16;int count=h.applied;h.onConfigurationChanged(new Configuration(true));require(h.applied==count&&MobileUi.mode==ThemeMode.LIGHT,"manual light ignores an opposing raw system flag");
   MobileUi.mode=ThemeMode.DARK;MobileUi.NIGHT=h.themeNight=true;h.resources.configuration.uiMode=32;h.onConfigurationChanged(new Configuration(false));require(h.applied==count&&MobileUi.mode==ThemeMode.DARK,"manual dark ignores an opposing raw system flag");
   int flushes=h.studio.flushes;h.selectThemeMode(ThemeMode.DARK);require(h.studio.flushes==flushes,"choosing existing mode is a no-op");
   h.selectThemeMode(ThemeMode.LIGHT);h.selectThemeMode(ThemeMode.SYSTEM);h.root.run();require(h.applied==count+1&&h.themeApplied==h.themeGeneration,"obsolete queued mode cannot duplicate/revert newer selection");
   h.ready=false;count=h.applied;h.onConfigurationChanged(new Configuration(true));require(h.applied==count,"pre-interface callback does not rebuild missing views");
   h.ready=true;h.toggleTheme();d=MobileUi.dialog;ThemeMode previous=MobileUi.mode;d.choice.choose(d,99);require(MobileUi.mode==previous,"invalid selection has no persistence side effect");
   System.out.println("Actual theme controller: "+checks+" host mode/config/state checks passed; no framework/WebView runtime.");}
 }`,['ThemeMode.java']);
});

test('polish: label details are read-only native message dialogs with inert close',()=>{
 javaCheck('PolishLabelDialogHarness',`public final class PolishLabelDialogHarness{
  final Object context=new Object();static final class Builder{String title,text;Builder setTitle(String s){title=s;return this;}Builder setMessage(String s){text=s;return this;}Builder setPositiveButton(String s,Object listener){if(listener!=null)throw new AssertionError("close must be inert");return this;}void show(){MobileUi.shown=this;}}static final class MobileUi{static Builder shown;static Builder dialog(Object c){return new Builder();}}
  ${method('QuotaCard.java','private void showLabelDetails(')}
  public static void main(String[] args){PolishLabelDialogHarness h=new PolishLabelDialogHarness();h.showLabelDetails("邀请信息","SYNTH6\\n剩余0次");if(!MobileUi.shown.text.contains("SYNTH6")||!MobileUi.shown.text.contains("0"))throw new AssertionError("full text retained");System.out.println("Actual read-only label dialog boundary passed; no file/network/account operation.");}
 }`);
});

test('values-copy: actual card update is inert until click, copies only full code, and handles unavailable clipboard honestly',()=>{
 const source=fs.readFileSync(path.join(base,'QuotaCard.java'),'utf8');
 const copy=source.includes('private void copyInviteCode(')?method('QuotaCard.java','private void copyInviteCode('):'';
 javaCheck('CardValuesCopyHarness',`import java.text.SimpleDateFormat;
 public final class CardValuesCopyHarness{
  static final int INK=1,ACCENT=2;static final class Gravity{static final int END=1;}
  interface Click{void run(View v);}interface LongClick{boolean run(View v);}
  static class View{static final int GONE=8,VISIBLE=0;Click click;LongClick longClick;String description="";void setAlpha(float n){}void setContentDescription(String s){description=s;}void setOnClickListener(Click c){click=c;}void setOnLongClickListener(LongClick c){longClick=c;}}
  static final class TextView extends View{String text="",tooltip="";void setText(String s){text=s;}String getText(){return text;}void setTooltipText(CharSequence s){tooltip=s.toString();}void setTextColor(int n){}void setVisibility(int n){}}
  static final class LinearLayout extends View{}
  static final class ClipData{String label,text;static ClipData newPlainText(CharSequence label,CharSequence text){ClipData c=new ClipData();c.label=label.toString();c.text=text.toString();return c;}}
  static final class ClipboardManager{int writes;String written="UNCHANGED SYNTHETIC";boolean fail;void setPrimaryClip(ClipData data){if(fail)throw new SecurityException("synthetic unavailable");writes++;written=data.text;}}
  static final class Context{ClipboardManager clipboard=new ClipboardManager();int requests;<T>T getSystemService(Class<T> type){requests++;if(type!=ClipboardManager.class)throw new AssertionError("unexpected service");return type.cast(clipboard);}}
  static final class Toast{static final int LENGTH_SHORT=0;static String shown="";String text;Toast(String s){text=s;}static Toast makeText(Context c,String s,int duration){return new Toast(s);}void show(){shown=text;}}
  static final class Builder{String title,text;Builder setTitle(String s){title=s;return this;}Builder setMessage(String s){text=s;return this;}Builder setPositiveButton(String s,Object listener){if(listener!=null)throw new AssertionError("close must be inert");return this;}void show(){MobileUi.shown=this;}}
  static final class MobileUi{static Builder shown;static Builder dialog(Context c){return new Builder();}static void setTicks(View v,Double used,boolean known){}}
  final Context context=new Context();final TextView week=new TextView(),reset=new TextView(),extraLabel=new TextView(),inviteLabel=new TextView(),note=new TextView();final View ticks=new View();final LinearLayout view=new LinearLayout();final Consumer<String> planChanged=plan->{};
  ${method('QuotaCard.java','void update(')}
  ${method('QuotaCard.java','private void showLabelDetails(')}
  ${copy}
  static int checks;static void require(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
  static Map<String,Object> map(Object... v){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
  static AccountCardState card(String code,long now){Map<String,Object> invite=map("code",code,"use_count",1L,"max_uses",30L,"observed_at",now);return AccountCardState.read(map(),now).merge(map("invite",invite),now);}
  public static void main(String[] args){CardValuesCopyHarness h=new CardValuesCopyHarness();long now=System.currentTimeMillis();String full="SYNTHETICCODE123";h.update(card(full,now),"额度已同步");ClipboardManager clipboard=h.context.clipboard;
   require(clipboard.writes==0&&h.context.requests==0,"rendering never writes or reads clipboard");
   h.inviteLabel.click.run(h.inviteLabel);require(clipboard.writes==1&&clipboard.written.equals(full),"tap must copy exact full code, not rendered label/ratio/prefix");
   require(h.inviteLabel.text.equals("SYNTHETICCODE123 1/30"),"actual rendered label uses real code and ratio");
   require(Toast.shown.contains("已复制"),"success follows actual completed clipboard write");require(MobileUi.shown==null,"normal tap copies directly rather than opening details");
   require(h.inviteLabel.description.contains(full)&&h.inviteLabel.description.contains("复制"),"full code and copy semantics remain accessible");
   require(h.inviteLabel.longClick!=null&&h.inviteLabel.longClick.run(h.inviteLabel)&&MobileUi.shown.text.contains(full),"long press retains full read-only details");require(clipboard.writes==1,"opening details does not copy again");
   h.update(card("",now),"未更新");Toast.shown="";h.inviteLabel.click.run(h.inviteLabel);require(clipboard.writes==1&&clipboard.written.equals(full)&&!Toast.shown.contains("已复制"),"unknown code leaves clipboard untouched without success claim");
   h.update(card(full,now),"额度已同步");h.context.clipboard=null;Toast.shown="";h.inviteLabel.click.run(h.inviteLabel);require(!Toast.shown.contains("已复制")&&clipboard.writes==1,"missing service cannot claim success or change clip");
   h.context.clipboard=clipboard;clipboard.fail=true;Toast.shown="";h.inviteLabel.click.run(h.inviteLabel);require(!Toast.shown.contains("已复制")&&clipboard.writes==1&&clipboard.written.equals(full),"system write failure is caught and cannot claim success");
   clipboard.fail=false;AccountCardState other=card("SECOND7",now);h.update(other,"额度已同步");require(clipboard.writes==1,"new account rendering does not copy automatically");h.inviteLabel.click.run(h.inviteLabel);require(clipboard.writes==2&&clipboard.written.equals("SECOND7"),"rebound label copies current account code only");
   System.out.println("Actual card/clipboard boundary: "+checks+" synthetic checks passed; no real clipboard or Android rendering.");}
 }`,['AccountCardState.java','AccountQuota.java','AccountCardLabels.java']);
});
