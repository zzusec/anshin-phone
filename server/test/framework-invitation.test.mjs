import test from 'node:test';
import assert from 'node:assert/strict';
import { SessionStore } from '../src/store.mjs';
function item(packageName){return {packageName,label:'系统框架',category:'core',removable:false,system:true,versionName:'9'};}
test('实际Android9华为框架清单可以创建、领取和批准邀请',()=>{
 const s=new SessionStore(); const p=s.createSession({deviceName:'Android9模拟手机',inventory:[item('android'),item('androidhwext'),{packageName:'org.example.game',label:'测试游戏',category:'user',removable:true,system:false}]},'http://localhost:8787');
 const g=s.claim({inviteToken:p.inviteToken,childName:'测试家人'});s.getDevice(p.sessionId,p.deviceToken);s.approve(p.sessionId,p.deviceToken,{approve:true});
 const list=s.getGuest(p.sessionId,g.guestToken).inventory;assert.equal(list.length,3);assert.equal(list[1].packageName,'androidhwext');
 for(const name of ['android','androidhwext'])assert.throws(()=>s.createCommand(p.sessionId,g.guestToken,{action:'uninstall',packages:[name],acknowledgeDataLoss:true}));
 assert.equal(s.createCommand(p.sessionId,g.guestToken,{action:'uninstall',packages:['org.example.game'],acknowledgeDataLoss:true}).status,'pending');
});
test('框架不能伪造成第三方可删，任意单段包名仍被拒绝',()=>{
 for(const entry of [{...item('android'),category:'user',removable:true},{...item('androidhwext'),removable:true},item('unknownframework')])assert.throws(()=>new SessionStore().createSession({deviceName:'test',inventory:[entry]},'http://localhost:8787'));
});
