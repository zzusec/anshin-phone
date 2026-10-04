import test from 'node:test'; import assert from 'node:assert/strict';
import {plan,removable,escapeHtml,validBaseUrl,summarize,taskLabel} from '../src/model.js';
const items=[{packageName:'com.example.game',category:'user',removable:true},{packageName:'com.android.phone',category:'core',removable:true},{packageName:'com.vendor.unknown',category:'unknown',removable:true}];
test('清理名单保护核心与未确认包，不依赖可删标记一个字段',()=>{assert.equal(removable(items[1]),false);assert.equal(removable(items[2]),false);assert.throws(()=>plan(items,['com.android.phone']));assert.throws(()=>plan(items,['com.vendor.unknown']));assert.equal(plan(items,['com.example.game']).length,1)});
test('拒绝不存在、重复和空的清理名单',()=>{for(const names of [[],['missing'],['com.example.game','com.example.game']])assert.throws(()=>plan(items,names))});
test('标签不成为HTML或脚本',()=>assert.equal(escapeHtml('<img src=x onerror="bad">'), '&lt;img src=x onerror=&quot;bad&quot;&gt;'));
test('只接受HTTPS服务或明确本机HTTP，拒绝账号/路径/fragment',()=>{assert.equal(validBaseUrl('https://example.com/'),'https://example.com');assert.equal(validBaseUrl('http://127.0.0.1:8787'),'http://127.0.0.1:8787');for(const url of ['http://example.com','https://u:p@example.com','https://example.com/#token','https://example.com/path','javascript:bad','https://example.com/?x=1'])assert.throws(()=>validBaseUrl(url))});
test('检查结果不是安全分数',()=>assert.deepEqual(summarize(items),{removable:1,core:1,unknown:1}));
test('任务等待确认不展示已完成',()=>{assert.equal(taskLabel('needs-confirmation'),'需要手机点确认');assert.notEqual(taskLabel('running'),'已逐项核验')});

test('恢复列表仅包含有备份的已卸载条目，不把普通成功历史当可恢复',async()=>{const {recoverableApps}=await import('../src/model.js');const items=[{packageName:'org.example.one',restorable:true,backupAvailable:true},{packageName:'org.example.two',restorable:true,backupAvailable:false},{packageName:'org.example.three',restorable:false,backupAvailable:true},{packageName:'org.example.one',restorable:true,backupAvailable:true}];assert.deepEqual(recoverableApps(items).map(x=>x.packageName),['org.example.one']);assert.deepEqual(recoverableApps(null),[]);});
