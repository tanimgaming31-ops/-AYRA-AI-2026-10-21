package com.ayra.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.database.Cursor;
import android.content.IntentFilter;
import android.util.Base64;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_MIC=401, REQ_CAMERA_PERMISSION=402, REQ_NOTIFY=403;
    private static final int REQ_IMAGE=101, REQ_CAMERA=102, REQ_CONTACT=103, REQ_TEXT=104, REQ_PDF=105, REQ_BACKUP=106;
    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady=false;
    private String torchId=null;
    private boolean torchOn=false;
    private String queuedAction=null, queuedPayload="";
    private Uri capturedImage=null;
    private String pendingBackup="";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0A0608);
        getWindow().setNavigationBarColor(0xFF060405);
        getWindow().getDecorView().setSystemUiVisibility(0);
        createNotificationChannel();
        web = new WebView(this);
        web.setBackgroundColor(0xFF070407);
        WebSettings ws=web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u=request.getUrl();
                if("appassets.androidplatform.net".equals(u.getHost()) && "https".equals(u.getScheme()))return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW,u)); }catch(Exception e){notice("Cannot open link: "+e.getMessage());}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(),"Native");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        tts=new TextToSpeech(this,status->{ttsReady=status==TextToSpeech.SUCCESS; if(ttsReady){tts.setLanguage(Locale.getDefault());tts.setSpeechRate(.96f);}});
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){js("onTtsStart");}
            @Override public void onDone(String id){js("onTtsEnd");}
            @Override public void onError(String id){js("onTtsEnd");}
        });
    }
    private void createNotificationChannel(){ if(Build.VERSION.SDK_INT>=26){ NotificationChannel ch=new NotificationChannel("ayra_reminders","AYRA Reminders", NotificationManager.IMPORTANCE_HIGH); ch.setDescription("Reminders scheduled by AYRA");getSystemService(NotificationManager.class).createNotificationChannel(ch);}}
    private String quoted(String str){return JSONObject.quote(str==null?"":str);}
    private void js(String method,String... args){
        StringBuilder code=new StringBuilder("window.AYRA&&window.AYRA.").append(method).append('(');
        for(int i=0;i<args.length;i++){if(i>0)code.append(',');code.append(quoted(args[i]));}
        code.append(")"); main.post(()->{if(web!=null)web.evaluateJavascript(code.toString(),null);});
    }
    private void jsAI(String id,boolean ok,String response){
        String code="window.AYRA&&window.AYRA.onAIResponse("+quoted(id)+","+ok+","+quoted(response)+")";
        main.post(()->{if(web!=null)web.evaluateJavascript(code,null);});
    }
    private void notice(String text){js("onNativeResult","info",text);}
    private void result(String action,String message){js("onNativeResult",action,message);}
    private class Bridge {
        @JavascriptInterface public void execute(String action,String payload){ main.post(()->perform(action,payload));}
        @JavascriptInterface public void sendToGemini(String key,String model,String raw,String req){
            new Thread(()->{try{jsAI(req,true,gemini(key,model,new JSONObject(raw)));}catch(Exception e){jsAI(req,false,friendly(e));}},"GeminiNetwork").start();
        }
        @JavascriptInterface public void sendToJarvis(String url,String model,String key,String raw,String req){
            new Thread(()->{try{jsAI(req,true,jarvis(url,model,key,new JSONObject(raw)));}catch(Exception e){jsAI(req,false,friendly(e));}},"OpenJarvisNetwork").start();
        }
        @JavascriptInterface public void testGemini(String key,String model,String req){
            new Thread(()->{try{JSONObject obj=new JSONObject().put("system","Reply with one word: connected");obj.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("text","Reply connected")));String reply=gemini(key,model,obj);jsAI(req,true,reply.substring(0,Math.min(150,reply.length())));}catch(Exception e){jsAI(req,false,friendly(e));}},"GeminiTest").start();
        }
        @JavascriptInterface public void testJarvis(String root,String key,String req){
            new Thread(()->{try{String u=apiRoot(root)+"/health";String r=request("GET",u,null,key);jsAI(req,true,r.length()>200?r.substring(0,200):r);}catch(Exception e){jsAI(req,false,friendly(e));}},"JarvisTest").start();
        }
        @JavascriptInterface public void exportBackup(String json){main.post(()->{pendingBackup=json;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"ayra-backup.json");startActivityForResult(i,REQ_BACKUP);});}
    }
    private String friendly(Exception e){return e.getMessage()!=null?e.getMessage():e.getClass().getSimpleName();}
    private String apiRoot(String input) throws Exception {
        String value=input.trim().replaceAll("/+$","");URL u=new URL(value);
        if(!u.getProtocol().equals("https")&&!u.getProtocol().equals("http"))throw new IOException("Use an http:// or https:// server URL");
        if(u.getHost().isEmpty())throw new IOException("Invalid server address");
        if(value.endsWith("/v1"))return value.substring(0,value.length()-3);
        return value;
    }
    private String request(String method,String url,JSONObject body,String bearer) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        try{
            c.setRequestMethod(method);c.setConnectTimeout(16000);c.setReadTimeout(60000);
            c.setRequestProperty("Accept","application/json");
            if(bearer!=null&&!bearer.isEmpty())c.setRequestProperty("Authorization","Bearer "+bearer);
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();InputStream stream=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            if(stream==null)throw new IOException("HTTP "+status+" (empty response)");
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] tmp=new byte[4096];int n;
            try(InputStream in=stream){while((n=in.read(tmp))!=-1){if(b.size()>3_000_000)throw new IOException("Response too large");b.write(tmp,0,n);}}
            String output=b.toString("UTF-8");
            if(status<200||status>=300)throw new IOException("HTTP "+status+": "+output.substring(0,Math.min(380,output.length())));
            return output;
        }finally{c.disconnect();}
    }
    private String gemini(String key,String model,JSONObject payload) throws Exception {
        if(key.isEmpty())throw new IOException("Gemini API key missing");
        if(!model.matches("[a-zA-Z0-9._-]{3,100}"))throw new IOException("Invalid Gemini model name");
        String sys=payload.optString("system");JSONArray input=payload.optJSONArray("messages");if(input==null)throw new IOException("No input messages");
        JSONObject body=new JSONObject();body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",sys))));
        JSONArray contents=new JSONArray();JSONObject image=payload.optJSONObject("image");
        for(int i=0;i<input.length();i++){
            JSONObject x=input.getJSONObject(i);String role=x.optString("role").equals("assistant")?"model":"user";
            JSONArray parts=new JSONArray().put(new JSONObject().put("text",x.optString("text")));
            if(i==input.length()-1&&image!=null)parts.put(new JSONObject().put("inline_data",new JSONObject().put("mime_type",image.optString("mimeType","image/jpeg")).put("data",image.optString("data"))));
            contents.put(new JSONObject().put("role",role).put("parts",parts));
        }
        body.put("contents",contents);
        HttpURLConnection c=(HttpURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent").openConnection();
        try{
            c.setRequestMethod("POST");c.setConnectTimeout(18000);c.setReadTimeout(60000);
            c.setRequestProperty("x-goog-api-key",key);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setDoOutput(true);
            try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            int status=c.getResponseCode();InputStream stream=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            if(stream==null)throw new IOException("Gemini HTTP "+status);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] tmp=new byte[4096];int n;
            try(InputStream in=stream){while((n=in.read(tmp))!=-1){if(out.size()>3_000_000)throw new IOException("Gemini response too large");out.write(tmp,0,n);}}
            String response=out.toString("UTF-8");
            if(status<200||status>=300)throw new IOException("Gemini HTTP "+status+": "+response.substring(0,Math.min(350,response.length())));
            JSONObject obj=new JSONObject(response);JSONArray choices=obj.optJSONArray("candidates");
            if(choices==null||choices.length()==0)throw new IOException("Gemini returned no reply (possibly blocked). "+response.substring(0,Math.min(150,response.length())));
            JSONArray parts=choices.getJSONObject(0).getJSONObject("content").getJSONArray("parts");StringBuilder text=new StringBuilder();
            for(int i=0;i<parts.length();i++)if(parts.getJSONObject(i).has("text"))text.append(parts.getJSONObject(i).optString("text"));
            if(text.length()==0)throw new IOException("Gemini returned an empty response");return text.toString();
        }finally{c.disconnect();}
    }
    private String jarvis(String root,String model,String key,JSONObject payload) throws Exception {
        if(model.trim().isEmpty())throw new IOException("Set the model name configured in OpenJarvis");
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",payload.optString("system")));
        JSONArray list=payload.optJSONArray("messages");if(list==null)throw new IOException("No messages provided");
        for(int i=0;i<list.length();i++){JSONObject m=list.getJSONObject(i);messages.put(new JSONObject().put("role",m.optString("role")).put("content",m.optString("text")));}
        JSONObject obj=new JSONObject().put("model",model.trim()).put("messages",messages).put("stream",false);
        JSONObject output=new JSONObject(request("POST",apiRoot(root)+"/v1/chat/completions",obj,key));
        JSONArray choices=output.optJSONArray("choices");if(choices==null||choices.length()==0)throw new IOException("OpenJarvis returned no choices");
        String answer=choices.getJSONObject(0).getJSONObject("message").optString("content");
        if(answer.isEmpty())throw new IOException("OpenJarvis returned an empty message");return answer;
    }
    private void perform(String a,String p){
        switch(a){
            case "startListening":mic(p);break;
            case "stopListening":stopListening();break;
            case "stopAudio":stopListening();if(tts!=null)tts.stop();js("onTtsEnd");break;
            case "speak":speak(p);break;
            case "youtube":openPackage("com.google.android.youtube","https://www.youtube.com");break;
            case "whatsapp":openPackage("com.whatsapp","https://www.whatsapp.com");break;
            case "messenger":openPackage("com.facebook.orca","https://www.messenger.com");break;
            case "imo":openPackage("com.imo.android.imoim","https://imo.im");break;
            case "instagram":openPackage("com.instagram.android","https://www.instagram.com");break;
            case "camera":checkPermissionThen(Manifest.permission.CAMERA,REQ_CAMERA_PERMISSION,"camera",p);break;
            case "pickImage":pickMime("image/*",REQ_IMAGE);break;
            case "pickText":pickMime("text/plain",REQ_TEXT);break;
            case "openPdf":pickMime("application/pdf",REQ_PDF);break;
            case "torch":checkPermissionThen(Manifest.permission.CAMERA,REQ_CAMERA_PERMISSION,"torch",p);break;
            case "wifi":launch(new Intent(Settings.ACTION_WIFI_SETTINGS));result("info","Opened Wi-Fi settings. Android does not allow silently toggling Wi-Fi here.");break;
            case "contacts":launchForResult(new Intent(Intent.ACTION_PICK,android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI),REQ_CONTACT);break;
            case "alarm":openAlarm(p);break;
            case "notifications":readNotifications();break;
            case "notificationsAccess":launch(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));break;
            case "settings":case "permissions":launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));result("permissions","Android permission settings opened.");break;
            case "battery":battery();break;
            case "reminder":checkPermissionThen(Build.VERSION.SDK_INT>=33?Manifest.permission.POST_NOTIFICATIONS:null,REQ_NOTIFY,"reminder",p);break;
            default:notice("Not supported: "+a);
        }
    }
    private void checkPermissionThen(String perm,int code,String action,String payload){
        if(perm==null||checkSelfPermission(perm)==PackageManager.PERMISSION_GRANTED){runPermissionAction(action,payload);return;}
        queuedAction=action;queuedPayload=payload;requestPermissions(new String[]{perm},code);
    }
    private void runPermissionAction(String a,String p){switch(a){case "camera":openCamera();break;case "torch":flashlight(p);break;case "reminder":scheduleReminder(p);break;}}
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
        super.onRequestPermissionsResult(code,perms,grants);
        boolean allowed=grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED;
        if(code==REQ_MIC){if(allowed)startMic(queuedPayload);else js("onSpeechError","Microphone permission denied. Enable it in Android Settings.");}
        else if(code==REQ_CAMERA_PERMISSION||code==REQ_NOTIFY){if(allowed)runPermissionAction(queuedAction,queuedPayload);else result("permissions","Permission denied; action not performed.");}
        queuedAction=null;queuedPayload="";
    }
    private void mic(String lang){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){queuedPayload=lang;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;}startMic(lang);}
    private void startMic(String lang){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){js("onSpeechError","No Android speech recognition service found. Install/enable speech recognition.");return;}
        if(tts!=null)tts.stop();stopListening();
        recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener(){
            @Override public void onReadyForSpeech(Bundle extras){js("onSpeechStart");}
            @Override public void onBeginningOfSpeech(){}
            @Override public void onRmsChanged(float rmsdB){}
            @Override public void onBufferReceived(byte[] b){}
            @Override public void onEndOfSpeech(){js("onSpeechEnd");}
            @Override public void onError(int error){String detail="Speech recognition error "+error; if(error==SpeechRecognizer.ERROR_NO_MATCH)detail="No speech recognized. Try again.";if(error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)detail="Microphone access blocked.";js("onSpeechError",detail);}
            @Override public void onResults(Bundle results){java.util.ArrayList<String> words=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(words!=null&&!words.isEmpty())js("onSpeechResult",words.get(0));else js("onSpeechError","No speech recognized.");}
            @Override public void onPartialResults(Bundle partialResults){}
            @Override public void onEvent(int eventType,Bundle params){}
        });
        Intent in=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);in.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        in.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);if(lang!=null&&!lang.isEmpty())in.putExtra(RecognizerIntent.EXTRA_LANGUAGE,lang);
        try{recognizer.startListening(in);}catch(Exception e){js("onSpeechError","Microphone not started: "+e.getMessage());}
    }
    private void stopListening(){if(recognizer!=null){try{recognizer.cancel();recognizer.destroy();}catch(Exception ignored){}recognizer=null;}js("onSpeechEnd");}
    private void speak(String value){if(!ttsReady){result("info","Phone text-to-speech engine not ready.");return;}stopListening();String msg=value.length()>3000?value.substring(0,3000):value;
        tts.speak(msg,TextToSpeech.QUEUE_FLUSH,null,"ayra_"+System.currentTimeMillis());}
    private void launch(Intent i){try{startActivity(i);}catch(Exception e){result("info","Cannot open: "+friendly(e));}}
    private void launchForResult(Intent i,int req){try{startActivityForResult(i,req);}catch(Exception e){result("info","Cannot open: "+friendly(e));}}
    private void openPackage(String pkg,String fallback){try{Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i!=null){startActivity(i);return;}}catch(Exception ignored){}
        launch(new Intent(Intent.ACTION_VIEW,Uri.parse(fallback)));}
    private void flashlight(String state){try{CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE);if(torchId==null){for(String id:cm.getCameraIdList()){CameraCharacteristics c=cm.getCameraCharacteristics(id);Boolean has=c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);if(Boolean.TRUE.equals(has)){torchId=id;break;}}}if(torchId==null){result("torch","No usable flashlight found");return;}torchOn="on".equals(state);cm.setTorchMode(torchId,torchOn);result("torch",torchOn?"Flashlight ON":"Flashlight OFF");}catch(Exception e){result("torch","Flashlight error: "+friendly(e));}}
    private void pickMime(String mime,int requestCode){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);launchForResult(i,requestCode);}
    private void openCamera(){try{ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"AYRA_"+System.currentTimeMillis()+".jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/AYRA");
        capturedImage=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(capturedImage==null)throw new IOException("Could not create camera output file");
        Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);i.putExtra(MediaStore.EXTRA_OUTPUT,capturedImage);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);launchForResult(i,REQ_CAMERA);
    }catch(Exception e){result("info","Camera error: "+friendly(e));}}
    private void openAlarm(String p){try{String[] parts=p.replace('.',':').split(":");int h=Integer.parseInt(parts[0]),m=Integer.parseInt(parts[1]);if(h<0||h>23||m<0||m>59)throw new Exception("Time must be HH:MM");Intent i=new Intent(AlarmClock.ACTION_SET_ALARM);i.putExtra(AlarmClock.EXTRA_HOUR,h);i.putExtra(AlarmClock.EXTRA_MINUTES,m);i.putExtra(AlarmClock.EXTRA_MESSAGE,"AYRA alarm");i.putExtra(AlarmClock.EXTRA_SKIP_UI,false);launch(i);result("alarm","Opened Android alarm confirmation for "+p);}catch(Exception e){result("alarm","Alarm error: "+friendly(e));}}
    private void readNotifications(){String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");if(enabled==null||!enabled.contains(getPackageName())){result("notifications","Notification access not enabled. Tap Notification Access in AYRA Settings.");return;}String s=AyraNotificationListener.latest();result("notifications",s.isEmpty()?"Access enabled, but no new notifications observed since listener started.":s);}
    private void battery(){Intent i=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(i==null){result("battery","Battery status unavailable");return;}int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);result("battery","Battery "+(scale>0?level*100/scale:level)+"%");}
    private void scheduleReminder(String raw){try{JSONObject o=new JSONObject(raw);String text=o.getString("text").trim();long at=o.getLong("time");if(text.isEmpty()||at<System.currentTimeMillis())throw new Exception("Select a future date and message");AlarmManager mgr=(AlarmManager)getSystemService(ALARM_SERVICE);
        Intent intent=new Intent(this,ReminderReceiver.class);intent.putExtra("message",text.substring(0,Math.min(200,text.length())));int id=(int)(System.currentTimeMillis()&0x7fffffff);intent.putExtra("notificationId",id);
        PendingIntent pi=PendingIntent.getBroadcast(this,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);
        result("reminder","Reminder scheduled around "+new java.text.SimpleDateFormat("dd MMM yyyy HH:mm",Locale.getDefault()).format(new java.util.Date(at))+" (inexact; Android may delay it; lost after reboot)");
    }catch(Exception e){result("reminder","Scheduling failed: "+friendly(e));}}
    @Override protected void onActivityResult(int request,int resultCode,Intent data){super.onActivityResult(request,resultCode,data);
        if(request==REQ_CAMERA){if(resultCode==RESULT_OK&&capturedImage!=null)sendImage(capturedImage);else if(capturedImage!=null)try{getContentResolver().delete(capturedImage,null,null);}catch(Exception ignored){}capturedImage=null;return;}
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            if(request==REQ_IMAGE)sendImage(uri);
            else if(request==REQ_CONTACT){try(Cursor c=getContentResolver().query(uri,new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},null,null,null)){if(c!=null&&c.moveToFirst()){String number=c.getString(0);launch(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(number))));}else result("info","Phone number missing");}}
            else if(request==REQ_PDF){Intent open=new Intent(Intent.ACTION_VIEW);open.setDataAndType(uri,"application/pdf");open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);launch(open);}
            else if(request==REQ_TEXT){try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Text file unreadable");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))>0){out.write(b,0,n);if(out.size()>15000)break;}js("onTextSelected",out.toString("UTF-8"));}}
            else if(request==REQ_BACKUP){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Cannot write backup");out.write(pendingBackup.getBytes(StandardCharsets.UTF_8));}pendingBackup="";result("info","AYRA backup saved to selected location.");}
        }catch(Exception e){result("info","File error: "+friendly(e));}
    }
    private void sendImage(Uri uri){new Thread(()->{try{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,o);}
        int scale=1;while(o.outWidth/scale>1200||o.outHeight/scale>1200)scale*=2;o=new BitmapFactory.Options();o.inSampleSize=scale;
        Bitmap bitmap;try(InputStream in=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,o);}if(bitmap==null)throw new IOException("Cannot decode image");
        ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,72,out);bitmap.recycle();String b64=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
        js("onImageSelected",b64,"image/jpeg");
    }catch(Exception e){result("info","Image error: "+friendly(e));}},"ImagePrepare").start();}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){stopListening();if(tts!=null){tts.stop();tts.shutdown();}if(torchOn){try{((CameraManager)getSystemService(CAMERA_SERVICE)).setTorchMode(torchId,false);}catch(Exception ignored){}}if(web!=null){web.destroy();web=null;}super.onDestroy();}
}
