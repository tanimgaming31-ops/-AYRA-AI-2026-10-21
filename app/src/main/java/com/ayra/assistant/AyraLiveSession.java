package com.ayra.assistant;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Foreground-only Gemini Live audio conversation. No background service or wake-word. */
public final class AyraLiveSession {
    public interface Events {
        void state(String value);
        void transcription(String who, String message);
        void error(String value);
    }

    private final Events events;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ArrayBlockingQueue<byte[]> playback = new ArrayBlockingQueue<>(80);
    private volatile AudioRecord recorder;
    private volatile AudioTrack track;
    private volatile AcousticEchoCanceler echo;
    private volatile WebSocket socket;
    private volatile Thread micThread;
    private volatile Thread playThread;
    private volatile OkHttpClient client;
    private volatile boolean ready;

    public AyraLiveSession(Events events) { this.events = events; }

    public void start(String apiKey, String instruction) {
        if(apiKey == null || apiKey.trim().isEmpty()) {
            events.error("Add your Gemini API key in Settings first."); return;
        }
        if(!running.compareAndSet(false, true)) return;
        ready = false;
        events.state("connecting");
        client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build();
        try {
            // TLS encrypts the API key in the websocket path; never log the URL/key.
            String endpoint = "wss://generativelanguage.googleapis.com/ws/"
                    + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
                    + "?key=" + URLEncoder.encode(apiKey.trim(), "UTF-8");
            Request request = new Request.Builder().url(endpoint).build();
            socket = client.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    if(!running.get()) { ws.close(1000,"Closed"); return; }
                    try {
                        JSONObject config = new JSONObject()
                                .put("responseModalities", new JSONArray().put("AUDIO"))
                                .put("speechConfig", new JSONObject().put("voiceConfig",
                                        new JSONObject().put("prebuiltVoiceConfig",
                                                new JSONObject().put("voiceName","Kore"))));
                        JSONObject setup = new JSONObject()
                                .put("model", "models/gemini-3.8-live")
                                .put("generationConfig", config)
                                .put("inputAudioTranscription",new JSONObject())
                                .put("outputAudioTranscription",new JSONObject())
                                .put("systemInstruction",new JSONObject().put("parts",
                                        new JSONArray().put(new JSONObject().put("text",instruction))));
                        ws.send(new JSONObject().put("setup",setup).toString());
                    }catch(Exception e){events.error("Live setup: "+safe(e)); stop();}
                }
                @Override public void onMessage(WebSocket ws, String message) {
                    if(!running.get()) return;
                    try { receive(new JSONObject(message)); }
                    catch(Exception e){ events.error("Live response: "+safe(e)); stop(); }
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                    if(!running.get()) return;
                    String reason = response == null ? safe(t) : ("HTTP "+response.code()+": "+safe(t));
                    events.error("Live connection failed: "+reason);
                    stop();
                }
                @Override public void onClosed(WebSocket ws, int code, String reason) {
                    if(running.get()){ events.error("Live disconnected ("+code+"): "+reason); stop(); }
                }
            });
        }catch(Exception e){ events.error("Could not connect to Gemini Live: "+safe(e));stop(); }
    }

    private void receive(JSONObject obj) throws Exception {
        if(obj.has("setupComplete") && !ready) {
            ready=true;
            events.state("listening");
            beginPlayback();
            beginMic();
        }
        JSONObject server=obj.optJSONObject("serverContent");
        if(server==null) return;
        JSONObject input=server.optJSONObject("inputTranscription");
        if(input!=null) {
            String s=input.optString("text","");
            if(!s.isEmpty()) events.transcription("user",s);
        }
        JSONObject output=server.optJSONObject("outputTranscription");
        if(output!=null) {
            String s=output.optString("text","");
            if(!s.isEmpty()) events.transcription("assistant",s);
        }
        if(server.optBoolean("interrupted")) {
            playback.clear();
            AudioTrack t=track;
            if(t!=null) {try {t.pause();t.flush();t.play();}catch(Exception ignored){}}
            events.state("listening");
        }
        JSONObject turn=server.optJSONObject("modelTurn");
        JSONArray parts=turn==null?null:turn.optJSONArray("parts");
        if(parts!=null) for(int i=0;i<parts.length();i++){
            JSONObject part=parts.optJSONObject(i);
            JSONObject audio=part==null?null:part.optJSONObject("inlineData");
            if(audio!=null && audio.optString("mimeType","").startsWith("audio/pcm")){
                byte[] pcm=Base64.decode(audio.getString("data"),Base64.DEFAULT);
                if(pcm.length>0) {
                    if(!playback.offer(pcm)){ playback.poll();playback.offer(pcm); }
                    events.state("speaking");
                }
            }
        }
        if(server.optBoolean("turnComplete")) events.state("listening");
    }

    private void beginMic(){
        micThread=new Thread(()->{
            AudioRecord mic=null;
            try {
                final int rate=16000;
                int minimum=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new IOException("Microphone format not supported");
                int bufferSize=Math.max(minimum*2,6400);
                mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,
                        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bufferSize);
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Microphone unavailable");
                recorder=mic;
                if(AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler e=AcousticEchoCanceler.create(mic.getAudioSessionId());
                    if(e!=null){echo=e;e.setEnabled(true);}
                }
                mic.startRecording();
                byte[] bytes=new byte[3200]; // 100 ms of 16 kHz mono 16-bit PCM
                while(running.get()&&ready&&!Thread.currentThread().isInterrupted()){
                    int n=mic.read(bytes,0,bytes.length);
                    if(n<=0) { if(n<0)throw new IOException("Microphone read error "+n); continue; }
                    WebSocket ws=socket;
                    if(ws!=null){
                        JSONObject data=new JSONObject().put("data",
                                Base64.encodeToString(bytes,0,n,Base64.NO_WRAP))
                                .put("mimeType","audio/pcm;rate=16000");
                        JSONObject frame=new JSONObject().put("realtimeInput",
                                new JSONObject().put("audio",data));
                        if(ws.queueSize()<2_000_000)ws.send(frame.toString());
                    }
                }
            }catch(Exception e){if(running.get()){events.error("Live microphone: "+safe(e));stop();}}
            finally{
                AcousticEchoCanceler effect=echo;echo=null;
                if(effect!=null)try{effect.release();}catch(Exception ignored){}
                if(mic!=null){
                    try{ if(mic.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)mic.stop(); }catch(Exception ignored){}
                    mic.release();
                }
                recorder=null;
            }
        },"AyraLiveMicrophone");
        micThread.start();
    }

    private void beginPlayback(){
        playThread=new Thread(()->{
            AudioTrack audio=null;
            try {
                int min=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0)throw new IOException("24 kHz playback not supported");
                audio=new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(Math.max(min*2,9600)).build();
                if(audio.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("Audio player unavailable");
                track=audio; audio.play();
                while(running.get() && !Thread.currentThread().isInterrupted()) {
                    byte[] chunk=playback.poll(250,TimeUnit.MILLISECONDS);
                    if(chunk==null)continue;
                    int at=0;
                    while(running.get()&&at<chunk.length){
                        int wrote=audio.write(chunk,at,chunk.length-at);
                        if(wrote<=0)throw new IOException("Audio output error "+wrote);
                        at+=wrote;
                    }
                }
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            catch(Exception e){ if(running.get()){events.error("Live playback: "+safe(e));stop();} }
            finally{
                if(audio!=null){
                    try{audio.pause();audio.flush();audio.release();}catch(Exception ignored){}
                }
                track=null;
            }
        },"AyraLivePlayback");
        playThread.start();
    }

    public void stop() {
        if(!running.getAndSet(false))return;
        ready=false;
        WebSocket ws=socket;socket=null;
        if(ws!=null) {try{ws.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}");ws.close(1000,"User stopped");}catch(Exception ignored){}}
        AudioRecord rec=recorder;
        if(rec!=null)try{rec.stop();}catch(Exception ignored){}
        Thread t=micThread;if(t!=null)t.interrupt();
        Thread p=playThread;if(p!=null)p.interrupt();
        playback.clear();
        OkHttpClient http=client;client=null;
        if(http!=null)http.dispatcher().executorService().shutdown();
        events.state("stopped");
    }
    private static String safe(Throwable t) {
        String v=t==null?"unknown":t.getMessage();
        return v==null?t.getClass().getSimpleName():v;
    }
}
