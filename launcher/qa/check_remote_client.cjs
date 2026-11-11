// Offline regression checks: no network requests or projector inputs.
const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const path=require('node:path');

async function check(){
  const timers=new Set(),calls=[],textCalls=[],order=[],results=[];let active=0,maximum=0,latency=45,failText=false;
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const later=(callback,ms)=>{const id=setTimeout(()=>{timers.delete(id);callback();},ms);timers.add(id);return id;};
  class Element{
    constructor(id,repeat=false){this.id=id;this.dataset={key:id};this.value='';this.tagName='BUTTON';this.listeners={};this.attributes={};this.repeat=repeat;this.classList={add(){},remove(){}};}
    addEventListener(type,handler){(this.listeners[type]??=[]).push(handler);}
    setAttribute(name,value){this.attributes[name]=value;}
    hasAttribute(name){return name==='data-repeat'&&this.repeat;}
    setPointerCapture(){} blur(){this.focused=false;} focus(){this.focused=true;}
    fire(type,extra={}){const event={button:0,pointerId:1,target:this,preventDefault(){},...extra};for(const handler of this.listeners[type]||[])handler(event);}
  }
  const ids=new Map(),buttons=['up','down','left','right','ok','back','home','menu','play_pause','vol_down','vol_up','mute','delete','enter'].map(key=>new Element(key,['up','down','left','right','vol_down','vol_up','delete'].includes(key)));
  const get=id=>{if(!ids.has(id))ids.set(id,new Element(id));return ids.get(id);};
  get('keyboard-panel').hidden=true;get('remote-text').tagName='INPUT';
  const context={
    document:{getElementById:get,querySelectorAll:()=>buttons,querySelector:()=>get('theme-color'),documentElement:{dataset:{}},addEventListener(){},hidden:false},
    window:{addEventListener(){}},navigator:{vibrate(){}},
    localStorage:{getItem:()=> 'test-session',setItem(){},removeItem(){}},
    performance:{now:()=>Date.now()},AbortController,
    setTimeout:later,clearTimeout:id=>{clearTimeout(id);timers.delete(id);},
    fetch:async(url,options)=>{
      if(url==='/api/status')return {ok:true,json:async()=>({ready:true})};
      const data=JSON.parse(options.body);order.push(url);
      if(url==='/api/text')textCalls.push(data.text);else {assert.equal(url,'/api/key');calls.push(data.key);}
      maximum=Math.max(maximum,++active);await wait(latency);active--;
      if(url==='/api/text'&&failText){failText=false;return {ok:false,status:503,json:async()=>({error:'not confirmed'})};}
      return {ok:true,json:async()=>({ok:true})};
    },
  };
  try{
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../assets/remote/remote.js'),'utf8'),context);
    await wait(5);
    const button=key=>buttons.find(b=>b.id===key);
    const tap=key=>{const b=button(key);b.fire('pointerdown');b.fire('pointerup');b.fire('click',{detail:1});};
    button('back').fire('pointerdown');assert.deepEqual(calls,['back']);results.push('pointer press sends before finger release');
    button('back').fire('pointerup');button('back').fire('click',{detail:1});
    tap('menu');tap('ok');await wait(180);
    assert.deepEqual(calls,['back','menu','ok']);assert.equal(maximum,1);results.push('rapid taps delivered once, in order');
    button('home').fire('click',{detail:0});await wait(80);
    assert.deepEqual(calls,['back','menu','ok','home']);results.push('keyboard activation remains available');
    latency=800;tap('left');tap('right');await wait(880);
    assert.deepEqual(calls,['back','menu','ok','home','left']);results.push('stalled connection does not replay stale taps');
    latency=15;
    get('keyboard-toggle').fire('click');assert.equal(get('keyboard-panel').hidden,false);assert.equal(get('remote-text').focused,true);
    assert.equal(get('keyboard-toggle').attributes['aria-expanded'],'true');results.push('keyboard button opens and focuses phone input synchronously');
    const literal='rice %s; $() ` \\ " = &';get('remote-text').value=literal;get('text-form').fire('submit');tap('enter');await wait(70);
    assert.deepEqual(textCalls,[literal]);assert.equal(get('remote-text').value,'');assert.deepEqual(order.slice(-2),['/api/text','/api/key']);assert.equal(calls.at(-1),'enter');assert.equal(maximum,1);
    results.push('literal text and Enter are serialized without shell expansion');
    get('remote-text').value='Привет';get('text-form').fire('submit');await wait(25);assert.equal(textCalls.length,1);assert.equal(get('remote-text').value,'Привет');results.push('unsupported alphabet is rejected without partial typing');
    get('remote-text').value='keep this draft';failText=true;get('text-form').fire('submit');await wait(80);assert.equal(get('remote-text').value,'keep this draft');assert.equal(textCalls.length,2);await wait(80);assert.equal(textCalls.length,2);results.push('failed text keeps draft and is never retried automatically');
    get('remote-text').value='sent';get('text-form').fire('submit');get('remote-text').value='next draft';await wait(50);assert.equal(get('remote-text').value,'next draft');results.push('acknowledgement preserves edits made while sending');
    return {passed:results.length,checks:results,projector_inputs_sent:false};
  }finally{for(const id of timers)clearTimeout(id);}
}
module.exports={check};
if(require.main===module)check().then(result=>console.log(JSON.stringify(result,null,2))).catch(error=>{console.error(error);process.exitCode=1;});
