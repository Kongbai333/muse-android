param([string]$Java = (Join-Path $PSScriptRoot '../.build/jdk21/bin/java.exe'))

$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../app/src/main/java/com/muse/workspace/mobile/MainActivity.java') -Raw
$methods = foreach ($name in @('updateQuickSwitchVisibility', 'showQuickSwitch')) {
    $method = [regex]::Match($source, "(?s)    private void $name\(\) \{.*?\r?\n    \}").Value
    if (!$method) { throw "Missing MainActivity method: $name" }
    $method
}

# Execute the activity's actual method bodies; replace only Android UI/storage boundaries.
$harness = @'
import java.util.*;
public class QuickSwitchCheck {
    static final int STUDIO = 0, WEB = 1, PROXIES = 2;
    static class View {
        static final int VISIBLE = 0, GONE = 8;
        int visibility = -1;
        void setVisibility(int value) { visibility = value; }
    }
    static class Sidebar {
        boolean open, sessions = true;
        boolean isOpen() { return open; }
        void showSessions(boolean value) { sessions = value; }
    }
    static class AccountStore {
        static class Account {
            String name;
            Account(String value) { name = value; }
        }
        List<Account> entries = new ArrayList<>();
        List<Account> all() { return entries; }
        boolean usable(Account account, boolean multiProfile) {
            return multiProfile || account == entries.get(0);
        }
    }
    interface Click { void select(Object dialog, int which); }
    static class MobileUi {
        QuickSwitchCheck owner;
        MobileUi(QuickSwitchCheck value) { owner = value; }
        static MobileUi dialog(QuickSwitchCheck value) { return new MobileUi(value); }
        MobileUi setTitle(String title) { return this; }
        MobileUi setItems(String[] labels, Click listener) {
            owner.choices = labels; owner.onSelect = listener; return this;
        }
        void show() { owner.dialogShown = true; }
    }
    View quickSwitch = new View();
    Sidebar sidebar = new Sidebar();
    AccountStore accounts = new AccountStore();
    boolean multiProfile = true, dialogShown;
    int screen = STUDIO;
    Object videoView;
    String[] choices;
    Click onSelect;
    AccountStore.Account switched;
    AccountStore.Account current() { return accounts.entries.isEmpty() ? null : accounts.entries.get(0); }
    void openSidebar() { sidebar.open = true; }
    void switchQuickAccount(AccountStore.Account account) { switched = account; }
    // Since 0.1.15 the switcher is a menu anchored to the floating avatar; record its choices and the pick.
    void showAccountMenu(List<AccountStore.Account> available) {
        dialogShown = true;
        choices = available.stream().map(account -> account.name).toArray(String[]::new);
        onSelect = (dialog, which) -> switchQuickAccount(available.get(which));
    }
__METHODS__
    static int failures;
    static void require(boolean condition, String message) {
        if (!condition) { failures++; System.err.println("FAIL: " + message); }
    }
    public static void main(String[] args) {
        for (boolean profiles : new boolean[]{false, true}) {
            for (int count : new int[]{0, 1, 2}) {
                QuickSwitchCheck app = new QuickSwitchCheck();
                app.multiProfile = profiles;
                for (int i = 0; i < count; i++) app.accounts.entries.add(new AccountStore.Account("Account " + i));
                String context = "profiles=" + profiles + ", accounts=" + count;
                for (int screen : new int[]{STUDIO, WEB, PROXIES}) {
                    for (boolean video : new boolean[]{false, true}) {
                        for (boolean sidebar : new boolean[]{false, true}) {
                            app.screen = screen; app.videoView = video ? new Object() : null; app.sidebar.open = sidebar;
                            app.updateQuickSwitchVisibility();
                            boolean visible = app.quickSwitch.visibility == View.VISIBLE;
                            require(visible == (screen != PROXIES && !video && !sidebar),
                                "Floating icon visibility: " + context + ", screen=" + screen + ", video=" + video + ", sidebar=" + sidebar);
                        }
                    }
                }
                app.screen = STUDIO; app.videoView = null; app.sidebar.open = false;
                app.showQuickSwitch();
                if (count > 0) {
                    // Since 0.1.16 any account opens the switcher menu (unusable ones are greyed out in it).
                    require(app.dialogShown && !app.sidebar.open && app.choices.length == count,
                        "Any account must open the switcher menu listing every account: " + context);
                    if (profiles && count > 1 && app.onSelect != null) {
                        app.onSelect.select(null, 1);
                        require(app.switched == app.accounts.entries.get(1), "Selected account must reach the switch handler");
                    }
                } else {
                    require(app.sidebar.open && !app.sidebar.sessions && !app.dialogShown,
                        "No account must open the accounts sidebar: " + context);
                }
            }
        }
        if (failures != 0) throw new AssertionError(failures + " floating account button checks failed");
        System.out.println("Floating account button: 72 visibility cases and 6 click scenarios passed.");
    }
}
'@
$folder = Join-Path ([IO.Path]::GetTempPath()) ('muse-quick-switch-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $folder
try {
    $path = Join-Path $folder 'QuickSwitchCheck.java'
    [IO.File]::WriteAllText($path, $harness.Replace('__METHODS__', ($methods -join "`n")))
    & $Java $path
    if ($LASTEXITCODE -ne 0) { throw 'Floating account button regression check failed.' }
} finally {
    Remove-Item -LiteralPath (Join-Path $folder 'QuickSwitchCheck.java') -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $folder -ErrorAction SilentlyContinue
}
