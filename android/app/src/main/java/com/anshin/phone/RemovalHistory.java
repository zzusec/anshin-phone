package com.anshin.phone;
import java.util.*;
import org.json.*;

/** Recoverable removals are successful uninstall events, not every successful historical action. */
public final class RemovalHistory {
 public static List<JSONObject> latestRemovals(JSONArray history)throws JSONException{
  Map<String,JSONObject> latest=new LinkedHashMap<>();
  for(int i=history.length()-1;i>=0;i--){
   JSONObject entry=history.getJSONObject(i);String name=entry.optString("packageName");
   if(!PackagePolicy.validName(name)||!"succeeded".equals(entry.optString("status"))||!entry.optBoolean("restorable"))continue;
   if(!"uninstall".equals(entry.optString("action","uninstall")))continue;
   if(!latest.containsKey(name))latest.put(name,entry);
  }
  return new ArrayList<>(latest.values());
 }
 private RemovalHistory(){}
}
