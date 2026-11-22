// Independent Node JSON writer/parser cross-check for the closed Java integer-only JSON codec.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process');const root=path.resolve(__dirname,'..');
const out=path.join(root,'app/build/transferJsonChecks');fs.mkdirSync(out,{recursive:true});
let seed=20261122;const next=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed;};
const strings=['SYNTHETIC','中文🔐','cafe\u0301','quote"slash\\/','\n\t\r\b\f','line\u2028separator','percent%2F%25+==',''];
function value(depth){const kind=next()%(depth?7:5);if(kind===0)return null;if(kind===1)return !!(next()%2);if(kind===2)return (next()%2?1:-1)*(next()%1000000000+1);if(kind===3)return strings[next()%strings.length];if(kind===4)return strings[(next()+3)%strings.length];if(kind===5)return [value(depth-1),value(depth-1)];return {['key-'+next()]:value(depth-1),nested:value(depth-1)};}
const expected=Array.from({length:96},()=>value(4));const input=path.join(out,'node-generated.json'),resultFile=path.join(out,'java-roundtrip.json');fs.writeFileSync(input,JSON.stringify(expected),'utf8');
const jdk=process.env.JAVA_HOME;assert.ok(jdk,'Set JAVA_HOME to JDK21');const ext=process.platform==='win32'?'.exe':'';const base=path.join(root,'app/src/main/java/com/muse/workspace/mobile');
const sources=['AccountTransferCrypto.java','AccountTransferJson.java'].map(n=>path.join(base,n)).concat(path.join(root,'checks/transfer-json/AccountTransferJsonCrossCheck.java'));
for(const [exe,args] of [['javac',['-encoding','UTF-8','-d',out,...sources]],['java',['-cp',out,'com.muse.workspace.mobile.AccountTransferJsonCrossCheck',input,resultFile]]]){
 const result=spawnSync(path.join(jdk,'bin',exe+ext),args,{cwd:root,encoding:'utf8'});if(result.stdout)process.stdout.write(result.stdout);if(result.stderr)process.stderr.write(result.stderr);assert.equal(result.status,0,exe+' JSON cross-check failed');
}
assert.deepEqual(JSON.parse(fs.readFileSync(resultFile,'utf8')),expected);
console.log('Independent Node↔Java strict JSON: 96 generated semantic roundtrips plus invalid UTF-8/BOM rejection; synthetic only.');
