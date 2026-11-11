'use strict';
const $=id=>document.getElementById(id);
const storage='ver0-remote-session';
const themeStorage='ver0-remote-theme';
let pageTheme='lavender';try{if(localStorage.getItem(themeStorage)==='oled')pageTheme='oled';}catch(_){}
function paintTheme(){
  document.documentElement.dataset.theme=pageTheme;
  $('theme-toggle').textContent=pageTheme==='oled'?'тема: OLED':'тема: лаванда';
  const meta=document.querySelector('meta[name="theme-color"]');if(meta)meta.setAttribute('content',pageTheme==='oled'?'#000000':'#0B0B10');
}
$('theme-toggle').addEventListener('click',()=>{pageTheme=pageTheme==='oled'?'lavender':'oled';try{localStorage.setItem(themeStorage,pageTheme);}catch(_){}paintTheme();});
paintTheme();
let token='';try{token=localStorage.getItem(storage)||'';}catch(_){}
let online=false,busy=false,hold=null,pressed=null,statusTimer=null,textPending=false,composing=false;
const pending=[];
function connection(state,text){$('connection').dataset.state=state;$('connection-text').textContent=text;}
function view(paired){$('pairing').hidden=paired;$('controls').hidden=!paired;$('disconnect').hidden=!paired;if(!paired){$('keyboard-panel').hidden=true;$('keyboard-toggle').setAttribute('aria-expanded','false');$('remote-text').value='';$('remote-text').blur();}}
function remember(value){token=value;try{value?localStorage.setItem(storage,value):localStorage.removeItem(storage);}catch(_){}view(!!value);}
function textControls(){$('keyboard-toggle').disabled=!online;$('remote-text').disabled=!online;$('text-submit').disabled=!online||textPending;}
function clearPending(){if(pending.some(command=>command.text!==undefined)){$('text-message').textContent='Текст не отправлен. Проверьте связь.';textPending=false;}pending.length=0;textControls();}
function available(value){online=value;if(!value){clearPending();endHold();}document.querySelectorAll('[data-key]').forEach(button=>button.disabled=!value);textControls();}
async function api(path,data,authenticated=true){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),3500);
  try{
    const headers={};if(authenticated&&token)headers.Authorization='Bearer '+token;
    if(data!==undefined)headers['Content-Type']='application/json';
    const response=await fetch(path,{method:data===undefined?'GET':'POST',headers,body:data===undefined?undefined:JSON.stringify(data),cache:'no-store',signal:controller.signal});
    const result=await response.json();
    if(!response.ok){const error=new Error(result.error||'Не удалось выполнить команду');error.status=response.status;throw error;}return result;
  }finally{clearTimeout(timer);}
}
async function status(){
  clearTimeout(statusTimer);
  if(!token){available(false);connection('wait','ожидание кода');return;}
  try{const state=await api('/api/status');available(state.ready);connection(state.ready?'online':'wait',state.ready?'соединено':'подготовка пульта');}
  catch(error){available(false);if(error.status===401){remember('');connection('wait','ожидание кода');}else connection('error','нет связи');}
  if(token)statusTimer=setTimeout(status,5000);
}
$('pair-form').addEventListener('submit',async event=>{
  event.preventDefault();const pin=$('pin').value.replace(/\D/g,'');if(pin.length!==6)return;
  $('pair-submit').disabled=true;$('pair-error').textContent='';
  try{const paired=await api('/api/pair',{pin},false);remember(paired.token);$('pin').value='';await status();}
  catch(error){$('pair-error').textContent=error.status?error.message:'Проектор не отвечает. Проверьте Wi-Fi.';}
  finally{$('pair-submit').disabled=false;}
});
$('pin').addEventListener('input',()=>{$('pin').value=$('pin').value.replace(/\D/g,'').slice(0,6);});
$('disconnect').addEventListener('click',async()=>{endHold();try{await api('/api/logout',{});}catch(_){}remember('');available(false);connection('wait','ожидание кода');});
$('keyboard-toggle').addEventListener('click',()=>{
  if(!online)return;
  const open=$('keyboard-panel').hidden;$('keyboard-panel').hidden=!open;$('keyboard-toggle').setAttribute('aria-expanded',String(open));
  if(open){endHold();$('remote-text').focus();}else $('remote-text').blur();
});
$('remote-text').addEventListener('compositionstart',()=>{composing=true;});
$('remote-text').addEventListener('compositionend',()=>{composing=false;});
$('text-form').addEventListener('submit',event=>{
  event.preventDefault();if(!online||textPending||composing)return;
  const text=$('remote-text').value;
  if(!text.length||text.length>256||/[^\x20-\x7e]/.test(text)){$('text-message').textContent='Пока доступны латиница, цифры и знаки — до 256 символов.';return;}
  if(pending.length>=8){$('text-message').textContent='Подождите выполнения предыдущей команды.';return;}
  endHold();textPending=true;textControls();$('text-message').textContent='Ввод…';
  $('remote-text').focus();pending.push({text,until:performance.now()+5000});drain();
});
function send(key,repeat=false){
  if(!online||pending.length>=8||(repeat&&(busy||pending.length)))return;
  pending.push({key,until:performance.now()+700});drain();
}
async function drain(){
  if(busy||!online)return;
  while(pending.length&&pending[0].until<performance.now()){const stale=pending.shift();if(stale.text!==undefined){textPending=false;textControls();$('text-message').textContent='Текст не отправлен. Повторите ввод.';}}
  if(!pending.length)return;
  const command=pending.shift(),typing=command.text!==undefined;busy=true;$('controls').setAttribute('aria-busy','true');
  if(navigator.vibrate)navigator.vibrate(8);
  try{
    await api(typing?'/api/text':'/api/key',typing?{text:command.text}:{key:command.key});
    if(typing){$('text-message').textContent='Текст введён';if($('remote-text').value===command.text)$('remote-text').value='';}
    else $('key-message').textContent='стрелки и Enter тоже работают';
  }
  catch(error){clearPending();$(typing?'text-message':'key-message').textContent=error.status?error.message:typing?'Ввод не подтверждён. Проверьте поле на проекторе.':'Нет связи с проектором';if(error.status===401){remember('');available(false);}else if(!error.status)available(false);}
  finally{if(typing){textPending=false;textControls();}busy=false;$('controls').setAttribute('aria-busy','false');if(pending.length)drain();}
}
function endHold(){clearTimeout(hold);hold=null;if(pressed)pressed.classList.remove('pressed');pressed=null;}
document.querySelectorAll('[data-key]').forEach(button=>{
  button.addEventListener('contextmenu',event=>event.preventDefault());
  // Pointer presses send immediately, not on finger release. Click is kept
  // only for keyboard/accessibility activation, avoiding duplicate taps.
  button.addEventListener('click',event=>{if(event.detail===0)send(button.dataset.key);if(event.detail>0)button.blur();});
  button.addEventListener('pointerdown',event=>{
    if(button.disabled||event.button!==0)return;
    endHold();pressed=button;button.classList.add('pressed');
    event.preventDefault();button.setPointerCapture(event.pointerId);send(button.dataset.key);
    if(!button.hasAttribute('data-repeat'))return;
    const repeat=()=>{if(pressed!==button)return;send(button.dataset.key,true);hold=setTimeout(repeat,140);};hold=setTimeout(repeat,400);
  });
  button.addEventListener('pointerup',endHold);button.addEventListener('pointercancel',endHold);button.addEventListener('lostpointercapture',endHold);
});
document.addEventListener('pointerup',endHold);window.addEventListener('blur',endHold);
document.addEventListener('visibilitychange',()=>{endHold();if(!document.hidden)status();});
document.addEventListener('keydown',event=>{
  if(!online||event.ctrlKey||event.altKey||event.metaKey||['INPUT','TEXTAREA'].includes(event.target.tagName))return;
  if(event.target.tagName==='BUTTON'&&(event.key==='Enter'||event.key===' '))return;
  const map={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right',Enter:'ok',Escape:'back',Backspace:'back',Home:'home',' ':'play_pause','-':'vol_down','+':'vol_up'};
  const key=map[event.key];if(!key)return;event.preventDefault();if(event.repeat&&!['up','down','left','right','vol_down','vol_up'].includes(key))return;send(key,event.repeat);
});
view(!!token);available(false);status();
