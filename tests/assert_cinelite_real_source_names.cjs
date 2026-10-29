/* Regression for the names users really see on CineLite's 14-agent
 * VisionQuest menu. Synthetic DOM is intentional; it tests the EXACT
 * WebView sourceRows function, not whether all 14 streams are playable. */
const fs=require('node:fs');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const kotlin=fs.readFileSync(
  'CineLite/src/main/kotlin/com/cinelite/CineLiteMultiSourceBrowser.kt','utf8');
const start=kotlin.indexOf('function sourceRows(panel){');
const end=kotlin.indexOf('function harvestSubtitles(){',start);
assert.ok(start>=0&&end>start,'sourceRows JavaScript missing');
const script=kotlin.slice(start,end);
assert.doesNotThrow(()=>new Function('panel',
  'const label=e=>(e?.textContent||"").replace(/\\s+/g," ").trim();'+
  'const visible=e=>!!e&&e.getClientRects().length>0;'+
  'const skip=/^(?:exit|cast|play|pause|settings|audio & subs|subtitles|stream sources|anime servers|movie servers|tv servers|download|close|sub|dub)$/i;'+
  script+';return sourceRows(panel).map(x=>x.name);'));
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  try{
    const page=await browser.newPage();
    const names=['Clove','Viper','Jett','Neon','Cypher','Harbor','Breach',
      'Astra','Brimstone','Sova','Omen','Sage','Gekko','Fade'];
    const row=name=>'<div class="group cursor-pointer">'+
      '<span class="text-sm">'+name+'</span>'+
      '<span class="text-xs">4K</span><span>0</span></div>';
    await page.setContent('<div id="panel" class="fixed">'+
      '<h2>Stream Sources</h2><span>14 Agents</span>'+
      '<button role="tab">Network (14)</button>'+
      '<button role="tab">Addons (0)</button>'+
      '<div>Active Stream Agents</div>'+
      names.map(row).join('')+
      '</div>');
    const actual=await page.evaluate(([script])=>{
      const fn=new Function('panel',
        'const label=e=>(e?.textContent||"").replace(/\\s+/g," ").trim();'+
        'const visible=e=>!!e&&e.getClientRects().length>0;'+
        'const skip=/^(?:exit|cast|play|pause|settings|audio & subs|subtitles|stream sources|anime servers|movie servers|tv servers|download|close|sub|dub)$/i;'+
        script+';return sourceRows(panel).map(x=>x.name);');
      return fn(document.getElementById('panel'));
    },[script]);
    assert.deepEqual(actual,names);
    assert.ok(!actual.some(x=>/^\(?\d+\)?$/.test(x)));
    console.log('CINELITE_REAL_SOURCE_NAMES_PASS',JSON.stringify({
      actualServerCount:actual.length,
      rejectedAddonCounter:true,
      rejectedNetworkCounter:true
    }));
  }finally{await browser.close();}
})().catch(e=>{console.error('CINELITE_REAL_SOURCE_NAMES_FAILED',e.message);process.exitCode=1;});
