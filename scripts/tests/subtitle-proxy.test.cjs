const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const asset=path.resolve(__dirname,'../../app/src/main/assets/subtitle-proxy.js');
function harness() {
 const calls=[],requests=[],ticks=[],choices=[];
 const data={url:'http://torr.test/stream/a.mkv?link=test&index=1&preload'};
 const player={url:function(){calls.push({self:this,args:[...arguments]});},destroy(){calls.push({destroy:true});}};
 const c={URL,console:{warn(){},log(){}},setInterval(f){ticks.push(f);},clearInterval(){},
 setTimeout(){return 1;},clearTimeout(){},cefriumQuery(q){requests.push(q);},
 Lampa:{PlayerVideo:player,Player:{playdata(){return data;},play(item){player.destroy();player.url(item.url);calls.push({ready:true,videoCreated:calls.some(c=>c.args)});}},Select:{show(p){choices.push(p);}},Noty:{show(){}}}};
 c.window=c; vm.runInNewContext(fs.readFileSync(asset,'utf8'),c);
 return {c,calls,requests,data,player,ticks,choices,reply(i=0,token='one'){
  const q=requests.filter(q=>JSON.parse(q.request).type==='proxy-register')[i],p=JSON.parse(q.request);
  q.onSuccess(JSON.stringify({sourceId:i+1,requestId:p.requestId,originalUrl:p.url,playbackUrl:'http://127.0.0.1:8899/'+token+'.mkv'}));
 }};
}
const url='http://torr.test/stream/a.mkv?link=test&index=1&play';
test('defers original video load and keeps original metadata',()=>{
 const h=harness();h.player.url(url,true);assert.equal(h.calls.length,0);h.reply();
 assert.equal(h.calls.length,1);assert.equal(h.calls[0].args[0],'http://127.0.0.1:8899/one.mkv');
 assert.equal(h.calls[0].args[1],true);assert.equal(h.calls[0].self,h.player);
 assert.equal(h.data.url.endsWith('&preload'),true);
 assert.equal(h.c.__lampaSubtitleProxy.originalUrl(h.calls[0].args[0]),url);
});
test('late registration cannot start old film or survive destroy',()=>{
 const h=harness();h.player.url(url);h.player.url(url+'&movie=2');h.reply(0);assert.equal(h.calls.length,0);
 h.reply(1,'two');assert.equal(h.calls.length,1);
 h.player.url(url+'&movie=3');h.player.destroy();h.reply(2,'three');assert.equal(h.calls.filter(x=>!x.destroy).length,1);
});
test('preload stat HLS and unrelated video pass through',()=>{
 for(const src of [url.replace('&play','&preload'),url.replace('&play','&stat'),'http://torr.test/a.m3u8','https://other.test/a.mp4']){
  const h=harness();h.player.url(src);assert.equal(h.calls.length,1);assert.equal(h.calls[0].args[0],src);
  assert.equal(h.requests.filter(q=>JSON.parse(q.request).type==='proxy-register').length,0);
 }
});
test('bridge failure offers explicit direct playback and ignores stale choice',()=>{
 const h=harness();h.player.url(url);h.requests.find(q=>JSON.parse(q.request).type==='proxy-register').onFailure(500,'failed');
 assert.equal(h.calls.length,0);assert.equal(h.choices.length,1);
 h.choices[0].onSelect(h.choices[0].items[0]);assert.equal(h.calls[0].args[0],url);
});
test('repeated hook and wrapper changes do not recursively register',()=>{
 const h=harness();h.ticks.forEach(f=>f());h.ticks.forEach(f=>f());
 const original=h.player.url;h.player.url=function(){return original.apply(this,arguments);};
 h.ticks.forEach(f=>f());h.player.url(url);h.reply();assert.equal(h.calls.length,1);
 assert.equal(h.requests.filter(q=>JSON.parse(q.request).type==='proxy-register').length,1);
});
test('registering before Player.play preserves synchronous ready listeners',()=>{
 const h=harness();h.c.Lampa.Player.play({url});assert.equal(h.calls.length,0);h.reply();
 assert.equal(h.calls.find(c=>c.ready).videoCreated,true);
 assert.equal(h.calls.find(c=>c.args).args[0],'http://127.0.0.1:8899/one.mkv');
 assert.equal(h.requests.filter(q=>JSON.parse(q.request).type==='proxy-register').length,1);
});
test('returning to active A supersedes pending B and registers a valid A token',()=>{
 const h=harness();h.player.url(url);h.reply(0,'a');
 h.player.url(url+'&b');h.player.url(url);h.reply(1,'b');
 assert.equal(h.calls.filter(x=>x.args).length,1);
 h.reply(2,'a-new');assert.equal(h.calls.at(-1).args[0],'http://127.0.0.1:8899/a-new.mkv');
});
test('noneligible playback intent cancels an earlier registration immediately',()=>{
 const h=harness();h.c.Lampa.Player.play({url});
 // An external loader need not touch PlayerVideo at all.
 const wrapped=h.c.Lampa.Player.play;
 h.player.destroy=()=>{};h.player.url=()=>{};
 wrapped.call(h.c.Lampa.Player,{url:'https://external.test/a.mp4'});
 h.reply();assert.equal(h.calls.filter(x=>x.ready).length,1);
 assert.ok(h.requests.some(q=>JSON.parse(q.request).type==='proxy-stop'));
});
test('document lifecycle is announced once while repeated injection keeps playback',()=>{
 const h=harness();h.player.url(url);h.reply();
 vm.runInNewContext(fs.readFileSync(asset,'utf8'),h.c);
 assert.equal(h.requests.filter(q=>JSON.parse(q.request).type==='proxy-page').length,1);
 assert.equal(h.c.__lampaSubtitleProxy.originalUrl('http://127.0.0.1:8899/one.mkv'),url);
});
