package com.anshin.phone;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class RemovalHistoryTest {
 private JSONObject entry(String pkg,String action,String status,boolean restorable,long time)throws Exception{return new JSONObject().put("packageName",pkg).put("action",action).put("status",status).put("restorable",restorable).put("time",time);}
 @Test public void onlySuccessfulRecordedUninstallsAreRestoreCandidates()throws Exception{
  JSONArray h=new JSONArray().put(entry("org.example.one","uninstall","succeeded",true,1)).put(entry("org.example.two","uninstall","failed",false,2)).put(entry("org.example.three","restore","succeeded",true,3));
  assertEquals(1,RemovalHistory.latestRemovals(h).size());assertEquals("org.example.one",RemovalHistory.latestRemovals(h).get(0).getString("packageName"));
 }
 @Test public void repeatedRemovalProducesOneLatestEntryAndRestoringDoesNotInventRemoval()throws Exception{
  JSONArray h=new JSONArray().put(entry("org.example.one","uninstall","succeeded",true,1)).put(entry("org.example.one","restore","failed",false,2)).put(entry("org.example.one","uninstall","succeeded",true,3));
  assertEquals(1,RemovalHistory.latestRemovals(h).size());assertEquals(3,RemovalHistory.latestRemovals(h).get(0).getLong("time"));
 }
 @Test public void legacyUninstallRecordRemainsRecoverableButMalformedCoreNameDoesNot()throws Exception{
  JSONObject old=new JSONObject().put("packageName","org.example.old").put("status","succeeded").put("restorable",true);
  assertEquals(1,RemovalHistory.latestRemovals(new JSONArray().put(old).put(entry("android","uninstall","succeeded",true,1))).size());
 }
}
