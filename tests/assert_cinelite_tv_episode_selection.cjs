/* Check the exact embedded Kotlin WebView JS used for TV details.
 * The fixtures model VisionQuest's real title and Episode 6 label, but
 * deliberately contain no media URL, auth token, cookies or secret headers. */
const fs=require('node:fs');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const browserCode=fs.readFileSync(
  'CineLite/src/main/kotlin/com/cinelite/CineLiteBrowser.kt','utf8');
const tv=browserCode.indexOf('suspend fun seriesEpisodes(');
assert.ok(tv>=0,'TV browser entry point absent');
function embedded(marker){
  const start=browserCode.indexOf(marker,tv);
  assert.ok(start>=0,'Missing native script: '+marker);
  const jsStart=browserCode.indexOf('"""',start)+3;
  const jsEnd=browserCode.indexOf('})()"""',jsStart);
  assert.ok(jsEnd>jsStart,'Unterminated native script: '+marker);
  return browserCode.slice(jsStart,jsEnd+'})()'.length);
}
const selectScript=embedded('val selectScript = """(function(){')
  .replaceAll('$safeQuery', JSON.stringify('VisionQuest'))
  .replaceAll('$id','213375');
const episodesScript=embedded('val parse = """(function(){');
assert.doesNotThrow(()=>new Function('return '+selectScript+';'),
  'Invalid Kotlin TV selection JS');
assert.doesNotThrow(()=>new Function('return '+episodesScript+';'),
  'Invalid Kotlin TV episode JS');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 try{
  const page=await browser.newPage();
  // When Fibers are missing in a production DOM, a unique exact title works.
  await page.setContent('<div class="cursor-pointer" onclick="window.selected=1">'+
    '<h3>VisionQuest</h3></div>');
  assert.equal(await page.evaluate(selectScript),true,
    'Native WebView must open a unique matching series, not return "unreleased"');
  assert.equal(await page.evaluate(()=>window.selected),1);
  // Ambiguous title must not click the wrong show without identity metadata.
  await page.setContent('<div class="cursor-pointer" onclick="window.selected=1">'+
    '<h3>VisionQuest</h3></div>'+
    '<div class="cursor-pointer" onclick="window.selected=2">'+
    '<h3>VisionQuest</h3></div>');
  assert.equal(await page.evaluate(selectScript),false,
    'Never guess between two titles without verified site identity');
  // An actual annotated React card must resolve an otherwise ambiguous title.
  await page.setContent(
    '<div class="cursor-pointer" data-cinelite-id="1" onclick="window.selected=1">'+
    '<h3>VisionQuest</h3></div>'+
    '<div class="group cursor-pointer" data-cinelite-id="213375" '+
    'onclick="window.selected=2"><h3>VisionQuest</h3></div>');
  assert.equal(await page.evaluate(selectScript),true);
  assert.equal(await page.evaluate(()=>window.selected),2);
  // A real episode marker, not a fabricated count, produces actual episode 6.
  await page.setContent('<button aria-expanded="false">Season 1</button>'+
    '<div class="group cursor-pointer"><span>EPISODE 6</span>'+
    '<h4>Deadlock</h4></div>');
  const result=JSON.parse(await page.evaluate(episodesScript));
  assert.equal(result.season,1);
  assert.deepEqual(result.episodes,[{number:6,title:'Deadlock'}]);
  console.log('CINELITE_TV_EPISODES_CONTRACT_PASS',JSON.stringify({
    uniqueTitleFallback:true,ambiguousTitleRejected:true,
    verifiedIdentityPriority:true,observedEpisodeSix:true
  }));
 }finally{await browser.close()}
})().catch(e=>{console.error('CINELITE_TV_EPISODES_CONTRACT_FAILED',
 e.message.split('\n')[0]);process.exitCode=1});
