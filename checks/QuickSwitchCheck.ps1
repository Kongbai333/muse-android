param([string]$Java = (Join-Path $PSScriptRoot '../.build/jdk21/bin/java.exe'))

$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../app/src/main/java/com/muse/workspace/mobile/MainActivity.java') -Raw
$methods = foreach ($name in @('accountTrigger', 'switchQuickAccount')) {
    $method = [regex]::Match($source, "(?s)    private void $name\([^\r\n]*\) \{.*?\r?\n    \}").Value
    if (!$method) { throw "Missing MainActivity method: $name" }
    $method.Replace('android.view.HapticFeedbackConstants.LONG_PRESS', '1')
}

# Same quick-switch entry/selection contract, now reached from the native header.
# Execute production method bodies; Android event/storage boundaries are host doubles.
$harness = @'
import java.util.*;
public class QuickSwitchCheck {
    static final int STUDIO=0, WEB=1, PROXIES=2;
    interface LongClick { boolean click(View view); }
    static class View {
        boolean focusable; int haptics; LongClick longClick;
        void setFocusable(boolean value){focusable=value;}
        void setOnLongClickListener(LongClick listener){longClick=listener;}
        void performHapticFeedback(int value){haptics++;}
    }
    static class AccountStore {
        static class Account { final String id; boolean usable=true,exhausted; Account(String id){this.id=id;} }
        List<Account> entries=new ArrayList<>();
        List<Account> all(){return entries;}
        boolean usable(Account account,boolean profiles){return account.usable&&(profiles||account==entries.get(0));}
    }
    static class Browser { boolean transfer; Object page; boolean transferring(){return transfer;} Object view(){return page;} }
    static class Studio { String owner; Object running(){return owner==null?null:owner;} boolean accountInUse(String id){return id.equals(owner);} }
    final AccountStore accounts=new AccountStore(); final Browser browser=new Browser(); final Studio studio=new Studio();
    boolean multiProfile=true,allowSelect=true; int screen=STUDIO,menus,syncs,opens,notices; String openingQuickAccount="";
    AccountStore.Account selected; View menuAnchor;
    AccountStore.Account current(){return selected;}
    void showAccountMenu(View view){menus++;menuAnchor=view;}
    boolean exhausted(AccountStore.Account account){return account.exhausted;}
    boolean select(AccountStore.Account account){if(!allowSelect)return false;selected=account;return true;}
    void syncStudioDraft(){syncs++;} void showScreen(int next){screen=next;opens++;} void message(String text){notices++;}
__METHODS__
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static QuickSwitchCheck app(){QuickSwitchCheck a=new QuickSwitchCheck();a.accounts.entries.add(new AccountStore.Account("A"));a.accounts.entries.add(new AccountStore.Account("B"));a.selected=a.accounts.entries.get(0);return a;}
    public static void main(String[] args){
        for(int count:new int[]{0,1,2})for(int screen:new int[]{STUDIO,WEB}){
            QuickSwitchCheck a=new QuickSwitchCheck();a.screen=screen;for(int i=0;i<count;i++)a.accounts.entries.add(new AccountStore.Account("A"+i));
            View title=new View();a.accountTrigger(title);require(a.menus==0&&a.opens==0,"installing trigger is inert");
            require(title.longClick.click(title)&&title.focusable&&title.haptics==1,"long press is accessible, haptic and consumes the event");
            require(a.menus==1&&a.menuAnchor==title&&a.opens==0,"header opens only its anchored menu without selecting an account");
        }
        for(String blocked:new String[]{"missing","unusable","exhausted","transfer","selection","running"}){
            QuickSwitchCheck a=app();AccountStore.Account b=a.accounts.entries.get(1);
            switch(blocked){case "missing"->a.accounts.entries.remove(b);case "unusable"->b.usable=false;case "exhausted"->b.exhausted=true;case "transfer"->a.browser.transfer=true;case "selection"->a.allowSelect=false;case "running"->a.studio.owner="A";}
            a.switchQuickAccount(b);require(a.selected.id.equals("A")&&a.opens==0&&a.openingQuickAccount.isEmpty(),"blocked selection must not switch/open/store sidebar target: "+blocked);
        }
        QuickSwitchCheck a=app();AccountStore.Account b=a.accounts.entries.get(1);a.switchQuickAccount(b);
        require(a.selected==b&&a.opens==1&&a.screen==WEB&&a.openingQuickAccount.equals("B"),"successful selection opens the chosen web account and records the sidebar target");
        a.browser.page=new Object();a.switchQuickAccount(b);require(a.opens==1,"current open page is not reopened");
        a=app();a.studio.owner="B";a.switchQuickAccount(a.current());require(a.opens==0&&a.notices==1,"global A cannot interrupt a distinct running B even when A is already selected");
        System.out.println("Header quick switch: 6 entry scenarios, blocked selection guards and successful account/scroll target passed.");
    }
}
'@
$folder = Join-Path ([IO.Path]::GetTempPath()) ('muse-quick-switch-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $folder
try {
    $path = Join-Path $folder 'QuickSwitchCheck.java'
    [IO.File]::WriteAllText($path, $harness.Replace('__METHODS__', ($methods -join "`n")))
    & $Java $path
    if ($LASTEXITCODE -ne 0) { throw 'Header account switch contract failed.' }
} finally {
    Remove-Item -LiteralPath (Join-Path $folder 'QuickSwitchCheck.java') -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $folder -ErrorAction SilentlyContinue
}
