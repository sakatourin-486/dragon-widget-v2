package dev.sakatourin.dragon;
import android.app.*;import android.os.*;import android.content.*;import android.net.Uri;import android.provider.Settings;import android.graphics.Color;import android.widget.*;
public class MainActivity extends Activity {
 EditText url,key; public void onCreate(Bundle b){super.onCreate(b);LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(32,48,32,24);l.setBackgroundColor(Color.rgb(248,246,252));setContentView(l);
 TextView t=new TextView(this);t.setText("SaKaTouRin\n龙娘悬浮窗");t.setTextSize(24);l.addView(t);
 TextView hint=new TextView(this);hint.setText("轻点龙娘依次看五小时额度、周额度，再点收起。\n点气泡左上角可打开菜单，查看 Token、当前任务或进度。\n拖动角色移动，长按切换打字形态。\n通知栏点关闭可退出。\n请在 Termux 启动新版 quota_bridge.py，并填写本地配对密钥。已有连接可直接沿用。");l.addView(hint);
 url=new EditText(this);url.setSingleLine();url.setHint("数据源地址");url.setText(getSharedPreferences("config",0).getString("url","http://127.0.0.1:8765/quota"));l.addView(url);
 key=new EditText(this);key.setSingleLine();key.setHint("Termux 显示的配对密钥");key.setInputType(129);key.setText(getSharedPreferences("config",0).getString("key",""));l.addView(key);
 Button save=new Button(this);save.setText("保存数据源");l.addView(save);save.setOnClickListener(v->{String u=url.getText().toString().trim();if(!u.isEmpty()&&!DataSource.isAllowed(u)){Toast.makeText(this,"请填写本地数据源或 HTTPS 地址",0).show();return;}getSharedPreferences("config",0).edit().putString("url",u).putString("key",key.getText().toString().trim()).apply();Toast.makeText(this,"已保存",0).show();});
 Button permission=new Button(this);permission.setText("① 允许显示悬浮窗");l.addView(permission);permission.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
 Button start=new Button(this);start.setText("② 显示龙娘");l.addView(start);start.setOnClickListener(v->{if(!Settings.canDrawOverlays(this)){Toast.makeText(this,"请先允许悬浮窗权限",0).show();return;}startForegroundService(new Intent(this,DragonService.class));});
 Button stop=new Button(this);stop.setText("隐藏龙娘");l.addView(stop);stop.setOnClickListener(v->stopService(new Intent(this,DragonService.class)));
 }
}
