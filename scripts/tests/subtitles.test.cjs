const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const asset = path.join(root, 'app/src/main/assets/embedded-subtitles.js');
function source() {
  if (fs.existsSync(asset)) return fs.readFileSync(asset, 'utf8');
  const old = fs.readFileSync(path.join(root, 'app/src/main/java/top/rootu/lampa/browser/Cefrium.kt'), 'utf8');
  return old.slice(old.indexOf('if (!window.__lampaNativeSubsInstalled)'), old.indexOf('var bridge = new Proxy'));
}
function harness(position = 10) {
  const requests = [], ticks = [], events = {};
  let menu, selection;
  const text = { textContent: '', innerHTML: '', style: {} };
  const box = { style: {}, classList: {remove() {}}, querySelector() {return text;} };
  const video = {currentTime: position, textTracks: [], audioTracks: [], addEventListener(name, fn) {events[name] = fn;}};
  const context = {
    console: {log(){},warn(){},error(){}},
    document: {querySelector() {return box;}},
    setInterval(fn) {ticks.push(fn);}, setTimeout(fn) {fn();}, clearTimeout() {},
    cefriumQuery(q) {requests.push(JSON.parse(q.request));},
    Lampa: {
      Lang: {translate(key) {return key === 'player_subs' ? 'Субтитры' : key;}},
      PlayerVideo: {video() {return video;}, listener: {follow(name, cb) {events['listener:'+name] = cb;}}},
      Player: {playdata() {return {url: 'http://localhost/synthetic.mkv'};}},
      PlayerPanel: {setSubs(items) {menu = items;}},
      Select: {show(params) {selection = params;}}
    }
  };
  context.window = context;
  vm.runInNewContext(source(), context);
  const tick = () => ticks.forEach(fn => fn());
  tick();
  return {context, video, requests, events, text, tick, menu:()=>menu, selection:()=>selection};
}
test('selection at the start sends the native track request', () => {
  const h = harness(0);
  h.context.Lampa.PlayerPanel.setSubs([{index:0, language:'rus', ghost:true}]);
  h.menu()[0].mode = 'showing';
  assert.equal(h.requests.find(x=>x.type==='subs-select')?.ordinal, 0);
  assert.equal(h.menu()[0].ghost, false);
});
test('switching and re-enabling a track at the same playback time starts reading again', () => {
  const h = harness();
  h.context.Lampa.PlayerPanel.setSubs([{index:0}, {index:1}]);
  h.menu()[0].mode='showing';
  h.menu().forEach(x=>{x.mode='disabled';});
  h.menu()[1].mode='showing';
  h.menu()[1].mode='disabled';
  h.menu()[1].mode='showing';
  assert.deepEqual(h.requests.filter(x=>x.type==='subs-select').map(x=>x.ordinal), [0,1,1]);
});
test('subtitle menus created internally by Lampa still reach the reader', () => {
  const h = harness();
  const items=[{index:-1}, {index:2, language:'rus', ghost:true, extra:{track_num:5}}];
  Object.defineProperty(items[1], 'mode', {get() {}, set() {}});
  h.context.Lampa.Select.show({title:'Субтитры',items,onSelect(item) {items.forEach(x=>{x.mode='disabled';}); item.mode='showing';}});
  const params=h.selection();
  params.onSelect(params.items[1]);
  assert.equal(h.requests.find(x=>x.type==='subs-select')?.ordinal, 2);
  assert.equal(params.items[1].extra.track_num, 5);
  assert.equal(params.items[1].ghost, false);
});
test('non-configurable plugin modes are replaced inside the array retained by Lampa', () => {
  const h = harness(0);
  const extra = {track_num:5};
  let originalModeCalls = 0, callbackItem;
  const original = {index:2, language:'rus', label:'Full', ghost:true, extra,
    onSelect(item) {callbackItem = item;}};
  // tracks.js defines this accessor without configurable:true.
  Object.defineProperty(original, 'mode', {get() {}, set() {originalModeCalls++;}});
  Object.defineProperty(original, 'metadata', {value:'keep non-enumerable properties'});
  const items = [{index:-1}, original];
  h.context.Lampa.PlayerPanel.setSubs(items);
  assert.equal(h.menu(), items);
  // Option.setSubtitles retains this array, and its onSelect closure iterates it.
  h.context.Lampa.Select.show({title:'Субтитры', items, onSelect(item) {
    items.forEach(x => {x.mode='disabled'; x.selected=false;});
    item.mode='showing'; item.selected=true;
    if (item.onSelect) item.onSelect(item);
  }});
  const params = h.selection();
  params.onSelect(params.items[1]);
  assert.equal(h.requests.find(x=>x.type==='subs-select')?.ordinal, 2);
  assert.equal(items[1].ghost, false);
  assert.equal(items[1].extra, extra);
  assert.equal(items[1].metadata, 'keep non-enumerable properties');
  assert.equal(callbackItem, items[1]);
  assert.equal(originalModeCalls, 0);
  params.onSelect(params.items[0]);
  assert.equal(h.requests.at(-1).type, 'subs-stop');
});

test('the internal subs event updates the retained array for non-configurable plugin modes', () => {
  const h = harness();
  const item = {index:1, ghost:true};
  Object.defineProperty(item, 'mode', {get() {}, set() {}});
  const items = [item];
  h.events['listener:subs']({subs:items});
  items[0].mode = 'showing';
  assert.equal(h.requests.find(x=>x.type==='subs-select')?.ordinal, 1);
});

test('native WebVTT tracks keep their original handlers', () => {
  const h = harness();
  let mode='disabled';
  const native={index:0, get mode(){return mode;}, set mode(v){mode=v;}};
  h.video.textTracks=[native];
  h.context.Lampa.PlayerPanel.setSubs([native]);
  h.menu()[0].mode='showing';
  assert.equal(mode,'showing');
  assert.equal(h.requests.filter(x=>x.type==='subs-select').length,0);
});

test('external subtitle URLs retain their loader and overlay when switching from embedded subtitles', () => {
  const h = harness();
  const loads = [];
  const external = {index:1, url:'http://localhost/external.srt'};
  const load = value => {
    if (value === 'showing') {
      loads.push(external.url);
      h.text.textContent = 'External subtitle';
    }
  };
  Object.defineProperty(external, 'mode', {set:load});
  const items = [{index:0}, external];
  h.context.Lampa.PlayerPanel.setSubs(items);
  items[0].mode = 'showing';
  items.forEach(x=>{x.mode='disabled';});
  items[1].mode = 'showing';
  h.events.timeupdate(); h.tick();
  assert.equal(items[1], external);
  assert.equal(Object.getOwnPropertyDescriptor(external, 'mode').set, load);
  assert.deepEqual(loads, [external.url]);
  assert.equal(h.text.textContent, 'External subtitle');
  assert.equal(h.requests.filter(x=>x.type==='subs-select').length, 1);
  assert.equal(h.requests.filter(x=>x.type==='subs-stop').length, 1);
});

test('stale cues are ignored after seeking and re-enabling the same track', () => {
  const h = harness();
  h.context.Lampa.PlayerPanel.setSubs([{index:0}]);
  const item = h.menu()[0];
  item.mode = 'showing';
  const firstSession = h.requests.at(-1).session;
  const deliver = (session, text) => h.context.__lampaNativeSubs.onNative(JSON.stringify({
    type:'cues', ordinal:0, session, cues:[[0,60000,text]]
  }));
  deliver(firstSession, 'Before seek');
  assert.equal(h.text.textContent, 'Before seek');
  h.video.currentTime = 30; h.events.seeking();
  const seekSession = h.requests.at(-1).session;
  assert.equal(h.text.textContent, '');
  deliver(firstSession, 'Stale before seek');
  assert.equal(h.text.textContent, '');
  deliver(seekSession, 'After seek');
  assert.equal(h.text.textContent, 'After seek');
  item.mode = 'disabled'; item.mode = 'showing';
  deliver(seekSession, 'Stale before re-enable');
  assert.equal(h.text.textContent, '');
  deliver(h.requests.at(-1).session, 'Current session');
  assert.equal(h.text.textContent, 'Current session');
});
test('delivered text uses the playback clock and is never interpreted as markup', () => {
  const h = harness(10);
  h.context.Lampa.PlayerPanel.setSubs([{index:0}]);
  h.menu()[0].mode='showing';
  h.context.__lampaNativeSubs.onNative(JSON.stringify({type:'cues',ordinal:0,cues:[[9000,11000,'Hello\n<img src=x onerror=alert(1)>']]}));
  assert.equal(h.text.textContent,'Hello\n<img src=x onerror=alert(1)>');
  h.video.currentTime=12; h.events.timeupdate();
  assert.equal(h.text.textContent,'');
});
