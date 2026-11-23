const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const base=path.join(__dirname,'../app/src/main/java/com/muse/workspace/mobile');const read=n=>fs.readFileSync(path.join(base,n),'utf8');
test('card native cache persists independent rows and receives every allowed group',()=>{
 const s=read('QuotaSync.java');assert.match(s,/AccountCardState card\(/);assert.match(s,/\.merge\(jsonMap\(result/);assert.match(s,/new JSONObject\(.*\.rows\(\)\)/);
 for(const field of ['invite:o.invite','plan:o.plan','extra:o.extra'])assert.ok(s.includes(field),field);
 assert.doesNotMatch(s,/putString\(probe\.account,\s*reading\.toString\(\)/);
});
test('card native probes serialize evaluation and cancel stale owners',()=>{
 const s=read('QuotaSync.java');assert.match(s,/if \(active != probe\) return/);assert.match(s,/probe\.evaluating/);const policy=read('QuotaRefreshPolicy.java');assert.ok(policy.includes('rateUntil')&&policy.includes('authPaused')&&policy.includes('automaticPaused'));
 assert.doesNotMatch(s,/HttpURLConnection|CookieManager|setProxyOverride/);
});
test('card current account pulse has foreground, generation and route gates',()=>{
 const s=read('MainActivity.java');for(const marker of ['quotaPulse','onResume()','onPause()','browser.transferring()','loginReady(','quotaHandler.removeCallbacks(quotaPulse)'])assert.ok(s.includes(marker),marker);
 const proxy=s.slice(s.indexOf('private void proxyChanged('),s.indexOf('/** 检测各账号的代理出口'));
 assert.ok(proxy.includes('quotas.cancel()'),'proxy revision invalidates old probe');
});
test('card retains stale ticks and identifies cached cycle/time separately from live values',()=>{
 const s=read('QuotaCard.java');assert.ok(s.includes('AccountCardState'));assert.ok(s.includes('setAlpha('));assert.ok(s.includes('未更新'));assert.ok(s.includes('邀请码'));assert.ok(s.includes('额外额度'));
 assert.doesNotMatch(s,/Double used = current \? data\.used\(\) : null/);
});
