'use strict';
/* Batch 7: 25 projects, 26 code workspace, 27 artifacts. Backend does not exist yet: designed empty states, never a broken screen. */
const plan=(p,ic,a,items)=>{UI.empty(p,ic,a,T('விரைவில் வரும்','Coming soon'));UI.sec(p,T('வரவிருப்பவை','Planned'));items.forEach(x=>UI.row(p,x[0],UI.pill(t('soon')),x[1]))};
Pg.def('projects',()=>t('projects'),p=>plan(p,'▦',T('திட்டங்கள் இல்லை','No projects yet'),[[T('கோப்புகள்','Files'),''],[T('உரையாடல்கள்','Chats'),''],[T('அறிவுறுத்தல்','Instructions'),''],[t('memory'),'']]));
Pg.def('code',()=>t('code'),p=>plan(p,'⌘',T('Workspace காலி','Workspace is empty'),[[T('கோப்பு மரம்','File tree'),''],['Editor',T('படிப்பு முதலில்','Read-first')],['Diff / Run','']]));
Pg.def('artifacts',()=>t('creations'),p=>plan(p,'✦',T('படைப்புகள் இல்லை','No creations yet'),[[T('அட்டை வலை','Card grid'),''],[T('வடிகட்டி & தேடல்','Filter & search'),''],[T('பதிப்பு வரலாறு','Version history'),'']]));
