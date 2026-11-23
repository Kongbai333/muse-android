const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const base=path.join(__dirname,'../app/src/main/java/com/muse/workspace/mobile');const read=n=>fs.readFileSync(path.join(base,n),'utf8');
test('updates use standalone bounded HTTPS without Muse account state',()=>{
 const http=read('AppUpdateHttp.java');assert.ok(http.includes('setInstanceFollowRedirects(false)'));assert.ok(http.includes('redirects<=5'));assert.ok(http.includes('allowedUrl'));
 assert.doesNotMatch(http,/CookieManager|ProxySettings|ProxySecrets|Authorization|access_token/);
 const ui=read('AppUpdateUi.java');for(const word of ['io.execute','AppUpdateHttp','cancel','进度','稍后'])assert.ok(ui.includes(word),word);
});
test('updates have startup and explicit native settings entry with lifecycle cancellation',()=>{
 const main=read('MainActivity.java'),sidebar=read('AccountSidebar.java');assert.ok(main.includes('AppUpdateUi'));assert.ok(main.includes('checkForUpdates()'));
 for(const word of ['updater.resume()','updater.pause()','updater.close()','updater.onActivityResult'])assert.ok(main.includes(word),word);
 assert.ok(sidebar.includes('检查更新')&&sidebar.includes('host::checkForUpdates'));
});
test('updates validate APK identity before an explicitly user-confirmed private install session',()=>{
 const s=read('AppUpdateInstaller.java');for(const word of ['validateIdentity','getPackageArchiveInfo','GET_SIGNING_CERTIFICATES','GET_SIGNATURES','canRequestPackageInstalls','USER_ACTION_REQUIRED','FLAG_MUTABLE','session.fsync','session.commit'])assert.ok(s.includes(word),word);
 assert.doesNotMatch(s,/\.uninstall\(|ACTION_DELETE|\.exec\(|setInstallerPackageName/);
});
test('updates handle only their recorded install status and preserve system permission/confirmation',()=>{
 const s=read('AppUpdateInstaller.java'),ui=read('AppUpdateUi.java');for(const word of ['EXTRA_SESSION_ID','STATUS_PENDING_USER_ACTION','STATUS_FAILURE_ABORTED','abandonSession'])assert.ok(s.includes(word),word);
 assert.ok(ui.includes('ACTION_MANAGE_UNKNOWN_APP_SOURCES'));assert.ok(ui.includes('startActivity('));
 const manifest=fs.readFileSync(path.join(__dirname,'../app/src/main/AndroidManifest.xml'),'utf8');assert.ok(manifest.includes('REQUEST_INSTALL_PACKAGES'));
 assert.ok(/receiver[\s\S]*?AppUpdateInstallReceiver[\s\S]*?android:exported="false"/.test(manifest),'private explicit receiver');
});
