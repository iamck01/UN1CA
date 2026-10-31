package io.mesalabs.rezoss.nudge;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Signature-protected RezossAI IPC; uses the same V73 queue as Samsung requests. */
public final class LocalInferenceService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<IBinder,Job> jobs=new HashMap<>();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private TextBackend backend;
    private Messenger endpoint;
    private final class Job implements Runnable,TextBackend.Control,IBinder.DeathRecipient {
        final Messenger callback;final int uid;final String input;final boolean summary,recall;final long deadline;
        volatile String cancellation;volatile String requestId;volatile Thread thread;
        Job(Message m){callback=m.replyTo;uid=m.sendingUid;Bundle b=m.getData();input=b.getString("input","");summary=b.getBoolean("summary");recall="recall".equals(b.getString("task"));deadline=Math.min(b.getLong("deadline",0),SystemClock.elapsedRealtime()+(recall?8000:(summary?75000:45000)));}
        public boolean cancelled(){return cancellation!=null;}
        public long deadline(){return deadline;}
        public void request(String id){requestId=id;if(id!=null&&cancelled())backend.cancel(id);}
        synchronized void cancel(String reason){if(cancellation!=null)return;cancellation=reason;if(requestId!=null)backend.cancel(requestId);if(thread!=null)thread.interrupt();}
        public void binderDied(){main.post(()->cancel("client_died"));}
        public void run(){
            thread=Thread.currentThread();long started=SystemClock.elapsedRealtime();String value=null,status="unavailable";
            try{if(!cancelled()){value=recall?backend.generateRecall(input,this):backend.generateLocal(input,summary,this);status="generated";}}
            catch(Exception e){
                if(SystemClock.elapsedRealtime()>=deadline)status="deadline";
                else if(e instanceof IOException && "deadline".equals(e.getMessage()))status="deadline";
                else if(e instanceof IOException && e.getMessage()!=null && e.getMessage().matches("[A-Za-z_0-9]{1,48}"))status=e.getMessage();
                else if(e instanceof IllegalArgumentException && e.getMessage()!=null && e.getMessage().matches("[A-Za-z_]{1,48}"))status=e.getMessage();
                else status="inference_failed";
                // Never log prompt/output text or provider response bodies.
                Log.i("RezossV73Local","request failure="+status+" exception="+e.getClass().getSimpleName()
                        +" elapsedMs="+(SystemClock.elapsedRealtime()-started));
            }
            finally{
                thread=null;Thread.interrupted();
                if(cancellation!=null){value=null;status=cancellation;}
                final String result=value,reason=status;
                main.post(()->{jobs.remove(callback.getBinder());try{callback.getBinder().unlinkToDeath(this,0);}catch(NoSuchElementException ignored){}respond(callback,result,reason);});
            }
        }
    }
    @Override public void onCreate(){
        super.onCreate();TextBackend.create(this);backend=TextBackend.local(this);
        endpoint=new Messenger(new Handler(Looper.getMainLooper()){
            @Override public void handleMessage(Message message){
                if(message.replyTo==null)return;
                try{
                    boolean summaryCaller=trusted(message.sendingUid,"io.mesalabs.unica.localsummary");
                    boolean recallCaller=trusted(message.sendingUid,"com.samsung.android.ssco");
                    if(!summaryCaller&&!recallCaller)throw new SecurityException("caller_denied");
                    IBinder key=message.replyTo.getBinder();
                    if(message.what==2){Job job=jobs.get(key);if(job!=null&&job.uid==message.sendingUid)job.cancel("client_cancelled");return;}
                    if(message.what!=1)return;
                    Bundle request=message.getData();boolean recall="recall".equals(request.getString("task"));
                    if(recall){
                        if(!recallCaller||request.getBoolean("summary"))throw new SecurityException("task_denied");
                    }else if(!summaryCaller||request.containsKey("task"))throw new SecurityException("task_denied");
                    if(backend==null||!backend.configured()){respond(message.replyTo,null,"api_unconfigured");return;}
                    if(!jobs.isEmpty()){respond(message.replyTo,null,"busy");return;}
                    Job job=new Job(message);
                    if(job.input.isEmpty()||job.input.length()>12000||job.deadline<=SystemClock.elapsedRealtime()){respond(message.replyTo,null,"invalid_or_expired");return;}
                    String blocked=ThermalGuard.blocked(LocalInferenceService.this);
                    if(blocked!=null){respond(message.replyTo,null,blocked);return;}
                    key.linkToDeath(job,0);jobs.put(key,job);
                    try{backend.submit(job);}catch(RejectedExecutionException busy){jobs.remove(key);key.unlinkToDeath(job,0);respond(message.replyTo,null,"busy");}
                }catch(Exception e){respond(message.replyTo,null,"caller_or_request_denied");}
            }
        });
        watchdog.scheduleAtFixedRate(()->main.post(()->{for(Job job:jobs.values()){String blocked=ThermalGuard.blocked(this);if(blocked==null&&SystemClock.elapsedRealtime()>=job.deadline)blocked="deadline";if(blocked!=null)job.cancel(blocked);}}),500,500,TimeUnit.MILLISECONDS);
    }
    private boolean trusted(int uid,String caller){
        try{return uid==getPackageManager().getApplicationInfo(caller,0).uid&&ApiProviderClient.trusted(this,caller);}
        catch(android.content.pm.PackageManager.NameNotFoundException ignored){return false;}
    }
    private static void respond(Messenger callback,String value,String status){
        Message response=Message.obtain(null,1);Bundle b=new Bundle();b.putString("content",value);b.putString("status",status);response.setData(b);
        try{callback.send(response);}catch(RemoteException ignored){}
        Log.i("RezossV73Local","request status="+status);
    }
    @Override public IBinder onBind(Intent intent){return endpoint.getBinder();}
    @Override public void onDestroy(){for(Job job:jobs.values())job.cancel("service_stopped");watchdog.shutdownNow();TextBackend.close(this);super.onDestroy();}
}
