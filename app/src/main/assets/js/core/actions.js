'use strict';
/* OS action gate: allowlist, safety mode, trusted calls. Ported from Build 107 callYunaCloudRemote. */
const SAFE=new Set('OPEN_APP OPEN_CAMERA CAMERA_SHUTTER DEEP_LINK_SEARCH TYPE_SEARCH GLOBAL_BACK GLOBAL_HOME GLOBAL_RECENTS CLEAR_RECENTS CLEAR_ALL_APPS GLOBAL_NOTIFICATIONS OPEN_NOTIFICATIONS GLOBAL_QUICK_SETTINGS OPEN_QUICK_SETTINGS TOGGLE_WIFI TOGGLE_DATA TOGGLE_BT TOGGLE_HOTSPOT TOGGLE_LOCATION TOGGLE_AIRPLANE TOGGLE_ROTATE TOGGLE_DND TORCH TORCH_ON TORCH_OFF BRIGHTNESS_UP BRIGHTNESS_DOWN BRIGHTNESS_AUTO VOLUME_UP VOLUME_DOWN VOLUME_MUTE VOLUME_MAX MEDIA_NEXT MEDIA_PLAY_PAUSE SCROLL_DOWN SCROLL_UP LOCK_SCREEN SCREENSHOT NET_PANEL DIAL SPEAKER_ON SPEAKER_OFF WHO_IS_CALLING SET_ALARM SET_TIMER SET_REMINDER ADD_CALENDAR_EVENT OPEN_MAPS_NAV GET_BATTERY_TIME YT_SKIP_AD YT_FIRST_VIDEO YT_FORWARD_10 YT_FULLSCREEN NOTE LOADS VISION'.split(' '));
const RESTRICTED=new Set(['CLEAR_RECENTS','CLEAR_ALL_APPS','LOCK_SCREEN','WA_REPLY']);
const ALIAS={OPEN_NOTIFICATIONS:'GLOBAL_NOTIFICATIONS',OPEN_QUICK_SETTINGS:'GLOBAL_QUICK_SETTINGS',CLEAR_ALL_APPS:'CLEAR_RECENTS'};
const Act={
 mode(){return ls.g('yuna_safety_mode')==='AUTO'?'AUTO':'CONFIRM'},
 trusted(){return UI.json('yuna_trusted_calls',{})},
 label(a,tg){return a+(tg?' · '+tg:'')},
 one(it){let a=String(it.action||'').toUpperCase(),tg=String(it.target||'');a=ALIAS[a]||a;
  if(a==='OPEN_APP_DYNAMIC'){let n=tg.trim();if(/^(gpay|ஜிபே)$/i.test(n))n='Google Pay';
   if(/youtube/i.test(n)&&B()&&B().startFloatingOverlay){call('startFloatingOverlay');setTimeout(()=>call('openAppByName',n),150);return T('YouTube + மிதக்கும் YUNA','YouTube + floating YUNA')}
   let r=call('openAppByName',n);if(/not found/i.test(String(r))&&n==='Google Pay')r=call('openAppByName','GPay');return r==null?'':String(r)}
  if(!SAFE.has(a))return T('தடுக்கப்பட்டது: ','Blocked: ')+a;
  if(a==='DIAL'){const m=Act.trusted(),k=Object.keys(m).find(x=>x.toLowerCase()===tg.toLowerCase());if(k)tg=m[k]}
  const r=call('executeAction',a,tg);return r==null?Act.label(a,tg):String(r)},
 /* returns text note; first action immediate, rest staggered; restricted/DIAL need confirm unless AUTO */
 run(list,done){list=(Array.isArray(list)?list:[]).slice(0,4);const notes=[];if(!list.length)return notes;
  const gated=it=>{const a=String(it.action||'').toUpperCase();return RESTRICTED.has(a)||a==='DIAL'};
  const go=(it,i)=>{if(gated(it)&&Act.mode()!=='AUTO'){UI.ask(T('இதைச் செய்யவா? ','Run this? ')+Act.label(it.action,it.target),()=>{const o=Act.one(it);done&&done(o)},T('செய்','Run'));return T('உறுதிப்படுத்தல் தேவை: ','Needs confirmation: ')+Act.label(it.action,it.target)}return Act.one(it)};
  notes.push(go(list[0],0));
  list.slice(1).forEach((it,i)=>setTimeout(()=>{if(gated(it)&&Act.mode()!=='AUTO')return;const o=Act.one(it);done&&done(o)},700*(i+1)));
  return notes}
};
