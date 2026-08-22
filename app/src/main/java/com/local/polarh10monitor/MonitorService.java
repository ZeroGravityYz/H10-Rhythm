package com.local.polarh10monitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

@SuppressLint("MissingPermission")
public final class MonitorService extends Service implements EcgEngine.Listener, EventStore.Listener {
    public static final String ACTION_START="com.local.polarh10monitor.START",ACTION_STOP="com.local.polarh10monitor.STOP",ACTION_UPDATE="com.local.polarh10monitor.UPDATE",
            ACTION_FEEDBACK="com.local.polarh10monitor.FEEDBACK",ACTION_RESET_MODEL="com.local.polarh10monitor.RESET_MODEL";
    private static final UUID PMD_SERVICE=UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID PMD_CONTROL=UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID PMD_DATA=UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE=UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL=UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final byte[] START_ECG={(byte)0x02,0x00,0x00,0x01,(byte)0x82,0x00,0x01,0x01,0x0e,0x00};
    private static final byte[] START_ACC={(byte)0x02,0x02,0x00,0x01,0x32,0x00,0x01,0x01,0x10,0x00,0x02,0x01,0x08,0x00,0x04,0x01,0x03};
    private static final byte[] STOP_ECG={(byte)0x03,0x00};
    private static final String CH_MONITOR="monitor",CH_ALERT="alerts",CH_TECH="technical_v2";private static final int ID_ONGOING=10;

    private HandlerThread workerThread; private Handler worker,main; private BluetoothAdapter adapter;private BluetoothLeScanner scanner;private BluetoothGatt gatt;
    private BluetoothGattCharacteristic control,data;private int setupStep;private boolean scanning,running,streaming,accActive;private String status="Arrêté",deviceName="Polar H10";private int battery=-1;
    private EcgEngine engine;private MorphologyModel morphologyModel;private EventStore store;private PowerManager.WakeLock wakeLock;private long lastUi,lastData;private final float[] wave=new float[EcgEngine.FS*6];private int wavePos,waveCount,lastSavedModelCount;
    private long sessionStart;private int sessionBpmSum,sessionBpmCount,sessionMinBpm,sessionMaxBpm;

    @Override public void onCreate(){super.onCreate();createChannels();workerThread=new HandlerThread("H10-ECG");workerThread.start();worker=new Handler(workerThread.getLooper());main=new Handler(getMainLooper());morphologyModel=MorphologyModel.deserialize(getSharedPreferences("monitor",MODE_PRIVATE).getString("morphology_state_v1",""));rebuildFeedbackFromHistory();lastSavedModelCount=morphologyModel.normalCount();engine=new EcgEngine(this,morphologyModel);store=new EventStore(this,this);BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);adapter=bm.getAdapter();}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){String action=intent==null?ACTION_START:intent.getAction();if(ACTION_STOP.equals(action)){stopMonitoring();return START_NOT_STICKY;}if(ACTION_FEEDBACK.equals(action)){applyFeedback(intent);if(!running)stopSelf(startId);return START_NOT_STICKY;}if(ACTION_RESET_MODEL.equals(action)){morphologyModel.reset();persistModel();broadcast(true);if(!running)stopSelf(startId);return START_NOT_STICKY;}startForegroundNow();if(!running)startMonitoring();return START_STICKY;}

    private void applyFeedback(Intent intent){rebuildFeedbackFromHistory();persistModel();broadcast(true);}
    private void rebuildFeedbackFromHistory(){morphologyModel.clearFeedback();for(EventHistory.Record record:EventHistory.list(this)){if(record.morphology==null)continue;if("anomaly".equals(record.review))morphologyModel.confirmAnomaly(record.morphology);else if("normal".equals(record.review))morphologyModel.confirmNormal(record.morphology);else if("artifact".equals(record.review))morphologyModel.confirmArtifact(record.morphology);}}
    private void persistModel(){getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("morphology_state_v1",morphologyModel.serialize()).apply();lastSavedModelCount=morphologyModel.normalCount();}

    private void startForegroundNow(){Notification n=ongoingNotification("Initialisation…");if(Build.VERSION.SDK_INT>=29)startForeground(ID_ONGOING,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(ID_ONGOING,n);}
    private void startMonitoring(){running=true;streaming=accActive=false;engine.reset();store.startSession();sessionStart=System.currentTimeMillis();sessionBpmSum=sessionBpmCount=sessionMinBpm=sessionMaxBpm=0;PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PolarH10Monitor:ECG");wakeLock.setReferenceCounted(false);wakeLock.acquire(15*60_000L);status="Recherche de la H10…";broadcast(true);worker.post(this::connectKnownOrScan);worker.postDelayed(watchdog,10_000);}

    private boolean bleAllowed(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void connectKnownOrScan(){if(!running)return;if(adapter==null){technicalAlert("Bluetooth indisponible","Ce téléphone ne fournit pas d’adaptateur Bluetooth.");return;}if(!bleAllowed()){technicalAlert("Permission Bluetooth manquante","Ouvre l’application et autorise Appareils à proximité.");return;}if(!adapter.isEnabled()){technicalAlert("Bluetooth désactivé","Active le Bluetooth pour reprendre la surveillance.");scheduleReconnect(10_000);return;}String addr=getSharedPreferences("monitor",MODE_PRIVATE).getString("address",null);if(addr!=null){try{status="Reconnexion à "+deviceName+"…";gatt=adapter.getRemoteDevice(addr).connectGatt(this,true,gattCallback,BluetoothDeviceTransport());broadcast(true);return;}catch(Exception ignored){}}startScan();}
    private int BluetoothDeviceTransport(){return android.bluetooth.BluetoothDevice.TRANSPORT_LE;}

    private void startScan(){if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){technicalAlert("Permission de recherche manquante","Autorise Appareils à proximité.");return;}scanner=adapter.getBluetoothLeScanner();if(scanner==null){scheduleReconnect(5000);return;}status="Recherche de Polar H10…";scanning=true;ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();scanner.startScan(new ArrayList<>(),settings,scanCallback);main.postDelayed(()->{if(scanning){stopScan();technicalAlert("H10 introuvable","Porte la ceinture, humidifie les électrodes et rapproche le téléphone.");scheduleReconnect(5000);}},20_000);broadcast(true);}
    private void stopScan(){if(!scanning||scanner==null)return;scanning=false;try{if(Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED)scanner.stopScan(scanCallback);}catch(Exception ignored){}}
    private final ScanCallback scanCallback=new ScanCallback(){@Override public void onScanResult(int callbackType,ScanResult result){String name=null;try{name=result.getDevice().getName();}catch(SecurityException ignored){}if(name!=null&&name.toUpperCase(Locale.ROOT).startsWith("POLAR H10")){stopScan();deviceName=name;getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("address",result.getDevice().getAddress()).putString("name",name).apply();status="Connexion à "+name+"…";broadcast(true);gatt=result.getDevice().connectGatt(MonitorService.this,false,gattCallback,BluetoothDeviceTransport());}}@Override public void onScanFailed(int code){scanning=false;technicalAlert("Recherche Bluetooth impossible","Erreur Android "+code+". Coupe puis rallume le Bluetooth.");scheduleReconnect(5000);}};

    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int statusCode,int newState){worker.post(()->{if(!running)return;if(newState==BluetoothProfile.STATE_CONNECTED){status="H10 connectée — préparation ECG…";broadcast(true);if(!g.requestMtu(232))g.discoverServices();}else if(newState==BluetoothProfile.STATE_DISCONNECTED){streaming=false;status="H10 déconnectée — reconnexion…";broadcast(true);technicalAlert("Surveillance ECG interrompue","La H10 est déconnectée. Reconnexion automatique en cours.");closeGatt();scheduleReconnect(3000);}});}
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int statusCode){worker.post(g::discoverServices);}
        @Override public void onServicesDiscovered(BluetoothGatt g,int statusCode){worker.post(()->setupServices(g,statusCode));}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int statusCode){worker.post(()->continueSetup(g,statusCode));}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int statusCode){if(c.getUuid().equals(PMD_CONTROL)&&statusCode!=BluetoothGatt.GATT_SUCCESS){worker.post(()->{if(streaming){accActive=false;status="Surveillance ECG active • filtre mouvement indisponible";readBattery(g);broadcast(true);}else{technicalAlert("Commande ECG non transmise","Android a refusé la commande Bluetooth. Reconnexion en cours.");closeGatt();scheduleReconnect(3000);}});}}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){byte[]v=c.getValue();if(v!=null)handleCharacteristic(c.getUuid(),v);}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[]value){handleCharacteristic(c.getUuid(),value);}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int statusCode){byte[]v=c.getValue();if(v!=null)handleRead(c.getUuid(),v,statusCode);}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[]value,int statusCode){handleRead(c.getUuid(),value,statusCode);}
    };

    private void setupServices(BluetoothGatt g,int result){if(result!=BluetoothGatt.GATT_SUCCESS){technicalAlert("Services H10 indisponibles","La découverte GATT a échoué.");closeGatt();scheduleReconnect(3000);return;}BluetoothGattService s=g.getService(PMD_SERVICE);if(s==null){technicalAlert("ECG indisponible","Cette ceinture n’expose pas le service PMD ECG.");closeGatt();return;}control=s.getCharacteristic(PMD_CONTROL);data=s.getCharacteristic(PMD_DATA);if(control==null||data==null){technicalAlert("PMD incomplet","Caractéristiques ECG absentes.");closeGatt();return;}setupStep=1;enable(g,control,true);}
    private void continueSetup(BluetoothGatt g,int result){if(result!=BluetoothGatt.GATT_SUCCESS){technicalAlert("Configuration ECG échouée","Impossible d’activer les notifications PMD.");closeGatt();scheduleReconnect(3000);return;}if(setupStep==1){setupStep=2;enable(g,data,false);}else if(setupStep==2){setupStep=3;status="Commande de démarrage ECG…";if(!writeCharacteristic(g,control,START_ECG)){technicalAlert("Démarrage ECG impossible","Android n’a pas accepté la commande Bluetooth.");closeGatt();scheduleReconnect(3000);}broadcast(true);}}
    private void enable(BluetoothGatt g,BluetoothGattCharacteristic c,boolean indication){g.setCharacteristicNotification(c,true);BluetoothGattDescriptor d=c.getDescriptor(CCCD);if(d==null){technicalAlert("Configuration BLE impossible","Descripteur CCCD absent.");return;}byte[]v=indication?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(d,v);else{d.setValue(v);g.writeDescriptor(d);}}
    private boolean writeCharacteristic(BluetoothGatt g,BluetoothGattCharacteristic c,byte[]v){if(g==null||c==null)return false;if(Build.VERSION.SDK_INT>=33)return g.writeCharacteristic(c,v,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS;c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);c.setValue(v);return g.writeCharacteristic(c);}
    private void readBattery(BluetoothGatt g){BluetoothGattService bs=g.getService(BATTERY_SERVICE);if(bs!=null){BluetoothGattCharacteristic bc=bs.getCharacteristic(BATTERY_LEVEL);if(bc!=null)g.readCharacteristic(bc);}}
    private void handleRead(UUID uuid,byte[]v,int result){if(result==BluetoothGatt.GATT_SUCCESS&&uuid.equals(BATTERY_LEVEL)&&v.length>0){battery=v[0]&255;if(battery<=10)technicalAlert("Batterie H10 faible",battery+" % restants. Remplace prochainement la pile.");broadcast(true);}}
    private void handleCharacteristic(UUID uuid,byte[]v){if(uuid.equals(PMD_DATA))worker.post(()->decodePmd(v));else if(uuid.equals(PMD_CONTROL))worker.post(()->handleControl(v));}
    private void handleControl(byte[]v){if(v.length<4||(v[0]&255)!=0xf0)return;int op=v[1]&255,type=v[2]&255,result=v[3]&255;if(op==2&&type==0){if(result==0){streaming=true;status="ECG actif — activation du filtre mouvement…";lastData=SystemClock.elapsedRealtime();if(!writeCharacteristic(gatt,control,START_ACC)){status="Surveillance ECG active • filtre mouvement indisponible";readBattery(gatt);}updateOngoing();broadcast(true);}else{technicalAlert("Démarrage ECG refusé","Réponse PMD "+result+". Ferme les autres applications Polar puis réessaie.");closeGatt();scheduleReconnect(5000);}}else if(op==2&&type==2){accActive=result==0;status=accActive?"Surveillance active • mouvement H10 filtré":"Surveillance ECG active • filtre mouvement indisponible";readBattery(gatt);updateOngoing();broadcast(true);}}

    private void decodePmd(byte[]v){if(v.length<10)return;int type=v[0]&255;if(type==0)decodeEcg(v);else if(type==2)decodeAcc(v);}

    private void decodeEcg(byte[]v){if(!running||v.length<13||(v[0]&255)!=0||(v[9]&255)!=0)return;int count=(v.length-10)/3;if(count<=0)return;long arrival=System.currentTimeMillis();for(int i=0;i<count;i++){int o=10+i*3,val=(v[o]&255)|((v[o+1]&255)<<8)|((v[o+2]&255)<<16);if((val&0x800000)!=0)val|=0xff000000;long t=arrival-Math.round((count-1-i)*1000.0/EcgEngine.FS);store.onRawSample(val,t);float f=engine.push(val,t);wave[wavePos]=f;wavePos=(wavePos+1)%wave.length;if(waveCount<wave.length)waveCount++;}lastData=SystemClock.elapsedRealtime();int learned=morphologyModel.normalCount();if(learned!=lastSavedModelCount&&(learned%10==0||learned==MorphologyModel.BASELINE_TARGET))persistModel();if(lastData-lastUi>500){lastUi=lastData;trackSession(engine.snapshot());broadcast(true);updateOngoing();}}

    /** Décode les trames ACC brutes Polar (8/16/24 bits). Le flux 16 bits à 50 Hz est demandé au H10. */
    private void decodeAcc(byte[]v){
        if(!running)return;for(PolarAccDecoder.Sample sample:PolarAccDecoder.decode(v,System.currentTimeMillis(),50))engine.pushMotion(sample.x,sample.y,sample.z,sample.timestampMs);
    }

    @Override public void onEvent(EcgEngine.DetectionEvent event){store.beginEvent(event);alertEvent(event);broadcast(true);}
    @Override public void onReportReady(String name,String location){main.post(()->broadcast(true));}
    @Override public void onStoreError(String message){technicalAlert("Erreur d’enregistrement",message);}

    private void alertEvent(EcgEngine.DetectionEvent e){if(!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("alerts",true))return;NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);Notification n=baseBuilder(CH_ALERT).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Rythme inhabituel détecté").setContentText(e.title).setStyle(new Notification.BigTextStyle().bigText(e.title+"\nUn rapport avec le tracé est en cours de préparation.")).setAutoCancel(true).build();nm.notify((int)(1000+(e.timestampMs%100000)),n);}
    private void technicalAlert(String title,String text){main.post(()->{NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(20,baseBuilder(CH_TECH).setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build());});}
    private Notification ongoingNotification(String text){return baseBuilder(CH_MONITOR).setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("H10 Rhythm").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();}
    private Notification.Builder baseBuilder(String channel){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,channel).setContentIntent(pi).setShowWhen(true);}
    private void updateOngoing(){main.post(()->{EcgEngine.Snapshot s=engine.snapshot();String quality=s.motionActive?"mouvement filtré":s.signalGood?"signal exploitable":"signal perturbé";String text=streaming?(s.bpm>0?s.bpm+" bpm • "+quality:"ECG reçu • étalonnage") : status;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ID_ONGOING,ongoingNotification(text));});}
    private void createChannels(){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);NotificationChannel monitor=new NotificationChannel(CH_MONITOR,"Surveillance en cours",NotificationManager.IMPORTANCE_LOW);monitor.setDescription("Affiche l’état de la connexion à la ceinture.");NotificationChannel alert=new NotificationChannel(CH_ALERT,"Rythme inhabituel",NotificationManager.IMPORTANCE_HIGH);alert.enableVibration(true);alert.setVibrationPattern(new long[]{0,180,80,180});alert.setDescription("Avertit lorsqu’un passage mérite une vérification.");NotificationChannel tech=new NotificationChannel(CH_TECH,"Connexion et enregistrement",NotificationManager.IMPORTANCE_DEFAULT);tech.enableVibration(false);tech.setDescription("Avertit uniquement en cas de coupure ou d’erreur technique. Les rapports sont créés silencieusement.");nm.createNotificationChannel(monitor);nm.createNotificationChannel(alert);nm.createNotificationChannel(tech);}

    private void broadcast(boolean force){long now=SystemClock.elapsedRealtime();if(!force&&now-lastUi<450)return;Intent i=new Intent(ACTION_UPDATE).setPackage(getPackageName());EcgEngine.Snapshot s=engine.snapshot();i.putExtra("status",status);i.putExtra("running",running);i.putExtra("streaming",streaming);i.putExtra("accActive",accActive);i.putExtra("motionActive",s.motionActive);i.putExtra("device",deviceName);i.putExtra("battery",battery);i.putExtra("bpm",s.bpm);i.putExtra("beats",s.beats);i.putExtra("esv",s.esv);i.putExtra("esa",s.esa);i.putExtra("pauses",s.pauses);i.putExtra("af",s.af);i.putExtra("tachy",s.tachy);i.putExtra("brady",s.brady);i.putExtra("runs",s.runs);i.putExtra("events",s.events);i.putExtra("signalGood",s.signalGood);i.putExtra("samples",s.samples);i.putExtra("modelSamples",s.modelSamples);i.putExtra("confirmedExamples",s.confirmedExamples);i.putExtra("artifactExamples",s.artifactExamples);i.putExtra("artifactRejected",s.artifactRejected);i.putExtra("modelReady",s.modelReady);i.putExtra("morphologyScore",s.morphologyScore);i.putExtra("morphologyThreshold",s.morphologyThreshold);i.putExtra("rmssd",s.rmssdMs);i.putExtra("sdnn",s.sdnnMs);i.putExtra("signalQuality",s.signalQualityPercent);float[]ordered=new float[waveCount];int start=Math.floorMod(wavePos-waveCount,wave.length);for(int k=0;k<waveCount;k++)ordered[k]=wave[(start+k)%wave.length];i.putExtra("wave",ordered);sendBroadcast(i);}
    private void trackSession(EcgEngine.Snapshot s){if(s.bpm<=0)return;sessionBpmSum+=s.bpm;sessionBpmCount++;sessionMinBpm=sessionMinBpm==0?s.bpm:Math.min(sessionMinBpm,s.bpm);sessionMaxBpm=Math.max(sessionMaxBpm,s.bpm);}
    private void scheduleReconnect(long delay){if(running)worker.postDelayed(this::connectKnownOrScan,delay);}
    private final Runnable watchdog=new Runnable(){@Override public void run(){if(!running)return;if(wakeLock!=null&&!wakeLock.isHeld())wakeLock.acquire(15*60_000L);if(streaming&&lastData>0&&SystemClock.elapsedRealtime()-lastData>8_000){streaming=false;technicalAlert("Flux ECG interrompu","Aucun paquet ECG depuis 8 secondes. Reconnexion automatique.");closeGatt();scheduleReconnect(2_000);}worker.postDelayed(this,10_000);}};
    private void closeGatt(){if(gatt!=null){try{if(control!=null)writeCharacteristic(gatt,control,STOP_ECG);}catch(Exception ignored){}try{gatt.disconnect();gatt.close();}catch(Exception ignored){}gatt=null;control=data=null;accActive=false;}}
    private void stopMonitoring(){EcgEngine.Snapshot finalSnapshot=engine.snapshot();running=false;streaming=false;stopScan();worker.removeCallbacksAndMessages(null);closeGatt();store.stopSession();persistModel();int average=sessionBpmCount==0?0:Math.round(sessionBpmSum/(float)sessionBpmCount);SessionHistory.add(this,new SessionHistory.Record(sessionStart,System.currentTimeMillis(),average,sessionMinBpm,sessionMaxBpm,finalSnapshot.events,finalSnapshot.rmssdMs,finalSnapshot.sdnnMs,finalSnapshot.signalQualityPercent));if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();status="Surveillance arrêtée";broadcast(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){if(running)stopMonitoring();if(workerThread!=null)workerThread.quitSafely();super.onDestroy();}
}
