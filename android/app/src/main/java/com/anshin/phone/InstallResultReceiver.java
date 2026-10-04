package com.anshin.phone;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.Build;
public final class InstallResultReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent){
  if(!"com.anshin.phone.INSTALL_RESULT".equals(intent.getAction()))return;
  int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
  // 回调自带本次恢复会话的绑定信息，避免与并发或历史恢复请求错配。
  String restoreId=intent.getStringExtra("restoreId"),name=intent.getStringExtra("packageName");
  MainActivity activity=MainActivity.active;
  if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
   Intent confirm=intent.getParcelableExtra(Intent.EXTRA_INTENT);
   if(activity!=null&&confirm!=null){activity.runOnUiThread(()->activity.startActivity(confirm));}
   else context.getSharedPreferences("anshin",Context.MODE_PRIVATE).edit().putString("restoreError","恢复安装需要手机前台确认，请回到安心手机后重试。").apply();
   return;
  }
  SharedPreferences prefs=context.getSharedPreferences("anshin",Context.MODE_PRIVATE);
  if(restoreId==null||name==null||!PackagePolicy.validName(name)){prefs.edit().putString("restoreError","安装回调缺少有效绑定信息，请重新检查恢复状态。").apply();return;}
  if(activity!=null)activity.installResult(restoreId,name,status,intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
  else{
   // 手机助手不在前台：暂存终态，回到前台后补记处理，不静默丢弃。
   try{prefs.edit().putString("deferredInstallResult",new org.json.JSONObject().put("id",restoreId).put("packageName",name).put("status",status).put("message",intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)==null?"":intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)).toString()).apply();}
   catch(org.json.JSONException e){prefs.edit().putString("restoreError","安装结果暂存失败，请重新检查恢复状态。").apply();}
  }
 }
}
