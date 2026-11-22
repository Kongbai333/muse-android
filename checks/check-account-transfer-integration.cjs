const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const base=path.join(__dirname,'../app/src/main/java/com/muse/workspace/mobile');
const read=name=>fs.readFileSync(path.join(base,name),'utf8');
test('transfer core retains API 26 collection compatibility',()=>{
  for(const name of fs.readdirSync(base).filter(n=>/^AccountTransfer.*\.java$/.test(n))){
    assert.doesNotMatch(read(name),/\b(?:List|Set|Map)\.of\s*\(/,name+' must not call API 30 collection factories');
  }
});
test('Cookie adapter detects each capability and never creates a wrong export profile',()=>{
  const source=read('AccountTransferCookies.java');
  assert.match(source,/GET_COOKIE_INFO/);assert.match(source,/MULTI_PROFILE/);
  assert.match(source,/getProfile\(account\.profileName\(\)\)/);
  assert.match(source,/getOrCreateProfile\(candidate\.profileName\(\)\)/);
  assert.match(source,/accounts\.usable\(account,\s*false\)/);
  assert.match(source,/onReceiveValue|setCookie\(/);assert.match(source,/\.flush\(\)/);
  assert.doesNotMatch(source,/loadUrl\(|document\.cookie|Log\./);
});
test('foreground and background navigation share the transfer gate',()=>{
  const web=read('MuseWeb.java');
  assert.match(web,/boolean beginTransfer\(/);assert.match(web,/void endTransfer\(/);
  assert.match(web,/if\s*\(transferActive\)/);
  assert.match(web,/prepareTransferRoute\(/);
  const pump=web.slice(web.indexOf('private void pump()'),web.indexOf('private boolean ensureRegistered()'));
  assert.match(pump,/transferActive/);
  const accounts=read('AccountStore.java');assert.match(accounts,/activateImported\(/);
  assert.match(accounts,/commit\(\)/);
});
test('UI uses encrypted bounded file IO, transient secret input and candidate activation',()=>{
  const ui=read('AccountTransferUi.java');
  for(const marker of ['ACTION_CREATE_DOCUMENT','ACTION_OPEN_DOCUMENT','EXTRA_LOCAL_ONLY','MAX_FILE_BYTES','validatePassphrase','setSaveEnabled(false)','Arrays.fill','io.execute','new AccountTransferFlow','activateImported','beginTransfer','endTransfer','仅能按名值导出'])
    assert.ok(ui.includes(marker),'UI missing '+marker);
  assert.doesNotMatch(ui,/Log\.|System\.out|accounts\.add\(|putString\([^\n]*(?:passphrase|password)/);
  assert.ok(ui.includes('createdProfiles'),'rollback must track only profiles created by its own operation');
  const main=read('MainActivity.java'),sidebar=read('AccountSidebar.java');
  assert.ok(main.includes('transferUi.onActivityResult('),'native file result dispatch');
  assert.ok(main.includes('transferUi.close()'),'lifecycle cancellation');
  for(const action of ['importAccounts','exportAccounts','importCookieText'])assert.ok(sidebar.includes(action),'sidebar missing '+action);
});
test('workflow build request id is optional display metadata only',()=>{
  const workflow=fs.readFileSync(path.join(__dirname,'../.github/workflows/build.yml'),'utf8');
  assert.ok(/workflow_dispatch:\s+inputs:\s+build_request_id:/.test(workflow),'optional dispatch request id');
  assert.ok(workflow.includes('run-name: ${{ github.workflow }} / ${{ inputs.build_request_id || github.ref_name }}'),'request id visible in run-name');
  assert.equal((workflow.match(/inputs\.build_request_id/g)||[]).length,1,'input must never enter shell commands');
  assert.ok(/build_request_id:[\s\S]*?type: string[\s\S]*?required: false[\s\S]*?default: ''/.test(workflow),'optional string with empty default');
});
