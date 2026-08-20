package com.local.polarh10monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int PERMISSIONS=42;
    private static final int BG=0xff071018,CARD=0xff101c28,CARD_ALT=0xff142331,LINE=0xff223548,
            TEXT=0xfff4f7fa,MUTED=0xff8fa1b3,TEAL=0xff35d4b5,BLUE=0xff65a9ff,AMBER=0xffffbd66,RED=0xffff667d;

    private LinearLayout pageHost,homePage,historyPage,settingsPage,historyList,nav;
    private TextView status,statusDot,device,bpm,battery,signal,today,modelState,sessionSummary,homeTab,historyTab,settingsTab;
    private ProgressBar modelProgress;
    private Button mainAction;
    private EcgCanvasView ecg;
    private boolean pendingStart,running,streaming;
    private int currentPage,lastEventCount=-1;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){update(i);}};

    @Override protected void onCreate(Bundle state){super.onCreate(state);buildUi();showPage(0);pageHost.post(()->showWelcome(false));}

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart(){super.onStart();IntentFilter filter=new IntentFilter(MonitorService.ACTION_UPDATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,filter);refreshHistory();}
    @Override protected void onStop(){try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onStop();}

    private void buildUi(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        pageHost=new LinearLayout(this);pageHost.setOrientation(LinearLayout.VERTICAL);
        buildPages();buildNavigation();root.addView(nav,new LinearLayout.LayoutParams(-1,dp(108)));root.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);root.requestApplyInsets();
    }

    private void buildPages(){homePage=buildHome();historyPage=buildHistory();settingsPage=buildSettings();}

    private LinearLayout buildHome(){
        LinearLayout body=page("AUJOURD’HUI","Votre rythme, simplement.");
        LinearLayout hero=card();LinearLayout statusRow=row();status=text("Surveillance arrêtée",14,TEXT,true);device=text("Aucune ceinture connectée",12,MUTED,false);
        LinearLayout statusTexts=new LinearLayout(this);statusTexts.setOrientation(LinearLayout.VERTICAL);statusTexts.addView(status);statusTexts.addView(device,lp(-1,-2,0,3,0,0));
        statusDot=text("●",20,MUTED,true);statusDot.setContentDescription("État de la surveillance");statusRow.addView(statusDot,lp(dp(28),-2,0,0,4,0));statusRow.addView(statusTexts,new LinearLayout.LayoutParams(0,-2,1));hero.addView(statusRow);
        bpm=text("--",58,TEXT,true);bpm.setGravity(Gravity.CENTER_HORIZONTAL);hero.addView(bpm,lp(-1,-2,0,18,0,0));TextView bpmLabel=text("battements par minute",13,MUTED,false);bpmLabel.setGravity(Gravity.CENTER_HORIZONTAL);hero.addView(bpmLabel);
        mainAction=button("Démarrer la surveillance",TEAL,0xff06251f);hero.addView(mainAction,lp(-1,dp(54),0,20,0,0));mainAction.setOnClickListener(v->{if(running)stopMonitor();else requestAndStart();});body.addView(hero,lp(-1,-2,0,16,0,12));

        LinearLayout quick=row();battery=miniCard(quick,"Ceinture","-- %",BLUE);signal=miniCard(quick,"Signal","En attente",TEAL);today=miniCard(quick,"Aujourd’hui",EventHistory.countToday(this)+" passage(s)",AMBER);body.addView(quick,lp(-1,-2,0,0,0,14));
        LinearLayout chart=card();LinearLayout chartHead=row();TextView chartTitle=text("Tracé en direct",18,TEXT,true);TextView live=text("130 Hz  •  60 FPS",11,TEAL,true);live.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);chartHead.addView(chartTitle,new LinearLayout.LayoutParams(0,-2,1));chartHead.addView(live);chart.addView(chartHead);ecg=new EcgCanvasView(this);chart.addView(ecg,lp(-1,dp(260),0,14,0,0));body.addView(chart,lp(-1,-2,0,0,0,14));

        LinearLayout personal=card();personal.addView(text("Analyse personnalisée",18,TEXT,true));MorphologyModel savedModel=MorphologyModel.deserialize(getSharedPreferences("monitor",MODE_PRIVATE).getString("morphology_state_v1",""));modelState=text(savedModel.isReady()?"Modèle prêt • "+savedModel.normalCount()+" battements de référence":"Apprentissage • "+savedModel.normalCount()+" / "+MorphologyModel.BASELINE_TARGET+" battements propres",13,MUTED,false);personal.addView(modelState,lp(-1,-2,0,8,0,10));modelProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);modelProgress.setMax(MorphologyModel.BASELINE_TARGET);modelProgress.setProgress(savedModel.normalCount());modelProgress.setProgressTintList(ColorStateList.valueOf(TEAL));modelProgress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));personal.addView(modelProgress,lp(-1,dp(7),0,0,0,10));personal.addView(text("Le modèle apprend uniquement la forme habituelle de tes battements. Les règles de rythme restent indépendantes.",12,MUTED,false));body.addView(personal,lp(-1,-2,0,0,0,14));

        LinearLayout watched=card();watched.addView(text("Ce que l’application surveille",18,TEXT,true));watched.addView(feature("Battements en avance","Recherche une rupture nette par rapport à ton rythme habituel."));watched.addView(feature("Pauses inhabituelles","Signale une absence prolongée de battement sur un signal propre."));watched.addView(feature("Rythme anormalement rapide, lent ou irrégulier","Conserve le passage complet pour pouvoir le vérifier ensuite."));body.addView(watched,lp(-1,-2,0,0,0,14));
        sessionSummary=text("Aucun passage détecté pendant cette session.",12,MUTED,false);body.addView(sessionSummary,lp(-1,-2,4,0,4,12));return body;
    }

    private LinearLayout buildHistory(){
        LinearLayout body=page("HISTORIQUE","Les passages enregistrés, au même endroit.");LinearLayout info=card();info.addView(text("Chaque détection conserve le contexte",17,TEXT,true));info.addView(text("Le rapport contient le tracé, 60 secondes avant et jusqu’à 120 secondes après le passage, ainsi que les données brutes.",13,MUTED,false),lp(-1,-2,0,7,0,0));body.addView(info,lp(-1,-2,0,16,0,14));historyList=new LinearLayout(this);historyList.setOrientation(LinearLayout.VERTICAL);body.addView(historyList);Button allFiles=outlineButton("Ouvrir le dossier des rapports");allFiles.setOnClickListener(v->openReportsFolder());body.addView(allFiles,lp(-1,dp(50),0,4,0,16));return body;
    }

    private LinearLayout buildSettings(){
        LinearLayout body=page("RÉGLAGES","Seulement les options qui comptent.");SharedPreferences prefs=getSharedPreferences("settings",MODE_PRIVATE);
        LinearLayout alertsCard=card();alertsCard.addView(text("Alertes",18,TEXT,true));CheckBox alerts=new CheckBox(this);alerts.setText("Me prévenir lorsqu’un passage mérite une vérification");alerts.setTextColor(TEXT);alerts.setTextSize(13);alerts.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{TEAL,MUTED}));alerts.setChecked(prefs.getBoolean("alerts",true));alerts.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean("alerts",checked).apply());alertsCard.addView(alerts,lp(-1,-2,0,8,0,5));Button notifications=outlineButton("Son et vibration des notifications");notifications.setOnClickListener(v->openNotificationSettings());alertsCard.addView(notifications,lp(-1,dp(48),0,5,0,0));body.addView(alertsCard,lp(-1,-2,0,16,0,14));
        LinearLayout background=card();background.addView(text("Surveillance écran éteint",18,TEXT,true));background.addView(text("Android doit autoriser l’application à fonctionner sans restriction pour éviter les coupures.",13,MUTED,false),lp(-1,-2,0,7,0,10));Button batterySettings=outlineButton("Autoriser le fonctionnement continu");batterySettings.setOnClickListener(v->openBatterySettings());background.addView(batterySettings,lp(-1,dp(48),0,0,0,0));body.addView(background,lp(-1,-2,0,0,0,14));
        LinearLayout model=card();model.addView(text("Modèle personnel",18,TEXT,true));model.addView(text("L’étalonnage reste enregistré uniquement sur ce téléphone. Tu peux le recommencer après avoir changé la position de la ceinture ou d’utilisateur.",13,MUTED,false),lp(-1,-2,0,7,0,10));Button reset=outlineButton("Recommencer l’apprentissage");reset.setTextColor(RED);reset.setOnClickListener(v->confirmModelReset());model.addView(reset,lp(-1,dp(48),0,0,0,0));body.addView(model,lp(-1,-2,0,0,0,14));
        LinearLayout privacy=card();privacy.addView(text("Données privées par défaut",18,TEXT,true));privacy.addView(text("L’analyse se fait sur le téléphone. Aucun compte, aucune publicité et aucun transfert automatique vers Internet. Les rapports restent dans Documents/PolarH10Monitor.",13,MUTED,false),lp(-1,-2,0,7,0,10));Button guide=outlineButton("Revoir le guide de démarrage");guide.setOnClickListener(v->showWelcome(true));privacy.addView(guide,lp(-1,dp(48),0,0,0,0));body.addView(privacy,lp(-1,-2,0,0,0,14));TextView author=text("H10 Rhythm 2.1  •  Développé par Mattéo Leroy\nCompatible Polar H10 — projet indépendant, non affilié à Polar Electro.",11,MUTED,false);author.setGravity(Gravity.CENTER);body.addView(author,lp(-1,-2,0,8,0,16));TextView warning=text("Cette application expérimentale ne fournit pas de diagnostic et ne remplace pas un avis médical. En cas de douleur thoracique, malaise, essoufflement important ou perte de connaissance, contacte immédiatement les urgences.",11,0xff738698,false);warning.setGravity(Gravity.CENTER);body.addView(warning,lp(-1,-2,4,0,4,20));return body;
    }

    private void buildNavigation(){nav=new LinearLayout(this);nav.setOrientation(LinearLayout.VERTICAL);nav.setPadding(dp(16),dp(8),dp(16),dp(8));nav.setBackgroundColor(0xff0b1620);LinearLayout brand=row();TextView mark=text("⌁",25,TEAL,true);mark.setGravity(Gravity.CENTER);mark.setBackground(round(0xff123a34,0xff1e6658,12));brand.addView(mark,lp(dp(40),dp(40),0,0,10,0));LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.addView(text("H10 Rhythm",18,TEXT,true));names.addView(text("Suivi ECG personnel",11,MUTED,false));brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));nav.addView(brand,lp(-1,dp(44),0,0,0,5));LinearLayout tabs=row();tabs.setPadding(dp(3),dp(3),dp(3),dp(3));tabs.setBackground(round(0xff08121b,LINE,15));homeTab=navItem("Aujourd’hui");historyTab=navItem("Historique");settingsTab=navItem("Réglages");tabs.addView(homeTab,new LinearLayout.LayoutParams(0,dp(43),1));tabs.addView(historyTab,new LinearLayout.LayoutParams(0,dp(43),1));tabs.addView(settingsTab,new LinearLayout.LayoutParams(0,dp(43),1));nav.addView(tabs);homeTab.setOnClickListener(v->showPage(0));historyTab.setOnClickListener(v->showPage(1));settingsTab.setOnClickListener(v->showPage(2));}

    private void showPage(int page){currentPage=page;pageHost.removeAllViews();LinearLayout selected=page==0?homePage:page==1?historyPage:settingsPage;ViewGroup previous=(ViewGroup)selected.getParent();if(previous!=null)previous.removeView(selected);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.addView(selected);pageHost.addView(scroll,new LinearLayout.LayoutParams(-1,-1));TextView[] items={homeTab,historyTab,settingsTab};for(int i=0;i<items.length;i++){boolean active=i==page;items[i].setTextColor(active?TEXT:MUTED);items[i].setBackground(active?round(0xff1a3040,0xff315269,12):round(0x00000000,0x00000000,12));}if(page==1)refreshHistory();}

    private void refreshHistory(){if(historyList==null)return;historyList.removeAllViews();List<EventHistory.Record> records=EventHistory.list(this);today.setText(EventHistory.countToday(this)+" passage(s)");if(records.isEmpty()){LinearLayout empty=card();TextView icon=text("♡",38,TEAL,false);icon.setGravity(Gravity.CENTER);empty.addView(icon);TextView title=text("Rien à signaler pour le moment",17,TEXT,true);title.setGravity(Gravity.CENTER);empty.addView(title,lp(-1,-2,0,7,0,4));TextView sub=text("Les passages inhabituels apparaîtront ici avec leur rapport.",13,MUTED,false);sub.setGravity(Gravity.CENTER);empty.addView(sub);historyList.addView(empty,lp(-1,-2,0,0,0,14));return;}for(EventHistory.Record record:records)historyList.addView(historyCard(record),lp(-1,-2,0,0,0,12));}

    private View historyCard(EventHistory.Record record){
        LinearLayout card=card();LinearLayout top=row();TextView title=text(publicTitle(record.type,record.title),16,TEXT,true);TextView badge=text(record.ready?"RAPPORT PRÊT":"EN COURS",10,record.ready?TEAL:AMBER,true);badge.setGravity(Gravity.CENTER);badge.setPadding(dp(9),dp(5),dp(9),dp(5));badge.setBackground(round(record.ready?0xff123a34:0xff3a2d18,record.ready?0xff1e6658:0xff6c5128,30));top.addView(title,new LinearLayout.LayoutParams(0,-2,1));top.addView(badge);card.addView(top);card.addView(text(new SimpleDateFormat("EEEE d MMMM • HH:mm:ss",Locale.FRANCE).format(new Date(record.timestampMs)),12,MUTED,false),lp(-1,-2,0,7,0,0));String review="anomaly".equals(record.review)?"Confirmé par toi":"normal".equals(record.review)?"Marqué comme normal":"artifact".equals(record.review)?"Marqué comme artefact":"À vérifier";TextView reviewView=text(review,12,"anomaly".equals(record.review)?RED:"normal".equals(record.review)?TEAL:AMBER,true);card.addView(reviewView,lp(-1,-2,0,6,0,10));if(record.ready){Button report=button("Voir le rapport PDF",BLUE,0xff07182d);report.setOnClickListener(v->openReport(record));card.addView(report,lp(-1,dp(46),0,0,0,9));}LinearLayout actions=row();Button real=smallButton("Anomalie réelle");Button normal=smallButton("Rythme normal");Button artifact=smallButton("Artefact");actions.addView(real,new LinearLayout.LayoutParams(0,dp(42),1));actions.addView(normal,new LinearLayout.LayoutParams(0,dp(42),1));actions.addView(artifact,new LinearLayout.LayoutParams(0,dp(42),1));card.addView(actions);real.setOnClickListener(v->sendFeedback(record,"anomaly"));normal.setOnClickListener(v->sendFeedback(record,"normal"));artifact.setOnClickListener(v->sendFeedback(record,"artifact"));return card;
    }

    private void sendFeedback(EventHistory.Record record,String label){EventHistory.review(this,record.id,label);if(record.morphology!=null&&!"artifact".equals(label)){Intent intent=new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_FEEDBACK);intent.putExtra("label",label);intent.putExtra("morphology",record.morphology);startService(intent);}Toast.makeText(this,"Merci, ton choix a été enregistré.",Toast.LENGTH_SHORT).show();refreshHistory();}
    private void openReport(EventHistory.Record record){Uri uri=EventHistory.findPdf(this,record.id);if(uri==null){Toast.makeText(this,"Le PDF n’est pas encore disponible.",Toast.LENGTH_LONG).show();return;}Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(intent);}catch(Exception e){Toast.makeText(this,"Installe un lecteur PDF pour ouvrir le rapport.",Toast.LENGTH_LONG).show();}}
    private void openReportsFolder(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");try{startActivity(intent);Toast.makeText(this,"Ouvre Documents puis PolarH10Monitor.",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Dossier : Documents/PolarH10Monitor",Toast.LENGTH_LONG).show();}}

    private void requestAndStart(){ArrayList<String> need=new ArrayList<>();if(Build.VERSION.SDK_INT>=31){if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.BLUETOOTH_SCAN);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.BLUETOOTH_CONNECT);}else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)need.add(Manifest.permission.POST_NOTIFICATIONS);if(!need.isEmpty()){pendingStart=true;requestPermissions(need.toArray(new String[0]),PERMISSIONS);}else startMonitor();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==PERMISSIONS&&pendingStart){pendingStart=false;boolean ble=true;for(int i=0;i<permissions.length;i++)if((permissions[i].contains("BLUETOOTH")||permissions[i].contains("LOCATION"))&&results[i]!=PackageManager.PERMISSION_GRANTED)ble=false;if(ble)startMonitor();else Toast.makeText(this,"La permission Bluetooth est nécessaire pour trouver la ceinture.",Toast.LENGTH_LONG).show();}}
    private void startMonitor(){startForegroundService(new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_START));status.setText("Connexion en cours…");}
    private void stopMonitor(){startService(new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_STOP));}

    private void update(Intent intent){running=intent.getBooleanExtra("running",false);streaming=intent.getBooleanExtra("streaming",false);String state=intent.getStringExtra("status");status.setText(friendlyStatus(state,running,streaming));statusDot.setTextColor(streaming?TEAL:running?AMBER:MUTED);device.setText(streaming?intent.getStringExtra("device")+" connectée":running?"Connexion automatique en cours":"Aucune ceinture connectée");int heartRate=intent.getIntExtra("bpm",0);bpm.setText(heartRate>0?String.valueOf(heartRate):"--");int batteryLevel=intent.getIntExtra("battery",-1);battery.setText(batteryLevel>=0?batteryLevel+" %":"-- %");signal.setText(streaming?(intent.getBooleanExtra("signalGood",false)?"Bon":"À stabiliser"):"En attente");int learned=intent.getIntExtra("modelSamples",0);boolean ready=intent.getBooleanExtra("modelReady",false);int confirmed=intent.getIntExtra("confirmedExamples",0);modelProgress.setProgress(Math.min(learned,MorphologyModel.BASELINE_TARGET));modelState.setText(ready?"Modèle prêt • "+learned+" battements de référence"+(confirmed>0?" • "+confirmed+" exemple(s) confirmé(s)":""):"Apprentissage en cours • "+learned+" / "+MorphologyModel.BASELINE_TARGET+" battements propres");int count=intent.getIntExtra("events",0);sessionSummary.setText(count==0?"Aucun passage détecté pendant cette session.":count+" passage(s) enregistré(s) pendant cette session.");mainAction.setText(running?"Arrêter la surveillance":"Démarrer la surveillance");mainAction.setBackground(ripple(running?0xff2c1b25:TEAL,running?0xff663244:TEAL,16));mainAction.setTextColor(running?0xffff8fa1:0xff06251f);float[] wave=intent.getFloatArrayExtra("wave");if(wave!=null)ecg.setSamples(wave,intent.getLongExtra("samples",0));if(count!=lastEventCount){lastEventCount=count;refreshHistory();}}

    private String friendlyStatus(String raw,boolean running,boolean streaming){if(streaming)return "Surveillance active";if(!running)return "Surveillance arrêtée";if(raw==null)return "Connexion en cours…";if(raw.contains("Recherche")||raw.contains("Reconnexion"))return "Recherche de la ceinture…";if(raw.contains("préparation")||raw.contains("Commande"))return "Préparation du signal…";return raw;}
    private String publicTitle(String type,String fallback){switch(type){case"ESV":return"Battement ventriculaire possible";case"ESA":return"Battement auriculaire possible";case"PAUSE":return"Pause inhabituelle";case"IRREGULAR":return"Rythme très irrégulier";case"TACHY":case"REGULAR_TACHY":return"Rythme rapide prolongé";case"BRADY":return"Rythme lent prolongé";case"COUPLET":case"WIDE_RUN":return"Plusieurs battements inhabituels";case"BIGEMINY":case"TRIGEMINY":return"Répétition inhabituelle";default:return fallback==null||fallback.isEmpty()?"Passage à vérifier":fallback;}}
    private void openBatterySettings(){try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));Toast.makeText(this,"Ouvre Batterie puis sélectionne Sans restriction.",Toast.LENGTH_LONG).show();}catch(Exception ignored){}}
    private void openNotificationSettings(){startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
    private void confirmModelReset(){new AlertDialog.Builder(this).setTitle("Recommencer l’apprentissage ?").setMessage("Le profil de forme appris sur ce téléphone sera supprimé. L’historique et les rapports seront conservés.").setNegativeButton("Annuler",null).setPositiveButton("Recommencer",(dialog,which)->{startService(new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_RESET_MODEL));modelProgress.setProgress(0);modelState.setText("L’apprentissage recommencera avec un signal propre.");Toast.makeText(this,"Modèle personnel réinitialisé.",Toast.LENGTH_SHORT).show();}).show();}

    private void showWelcome(boolean force){SharedPreferences prefs=getSharedPreferences("settings",MODE_PRIVATE);if(!force&&prefs.getBoolean("welcome_v21",false))return;Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(24),dp(24),dp(22));content.setBackground(round(CARD,0xff315269,24));TextView logo=text("⌁",34,TEAL,true);logo.setGravity(Gravity.CENTER);logo.setBackground(round(0xff123a34,0xff1e6658,18));content.addView(logo,lp(dp(58),dp(58),0,0,0,18));content.addView(text("Bienvenue dans H10 Rhythm",25,TEXT,true));content.addView(text("Un suivi ECG personnel conçu pour rester lisible, même quand l’analyse devient complexe.",14,MUTED,false),lp(-1,-2,0,7,0,12));content.addView(feature("Connecte ta Polar H10","L’application retrouve ensuite automatiquement ta ceinture."));content.addView(feature("Laisse le modèle apprendre","500 battements propres construisent ton profil personnel."));content.addView(feature("Vérifie seulement l’essentiel","Les passages inhabituels et leurs rapports apparaissent dans Historique."));TextView safety=text("Outil expérimental : les résultats ne constituent pas un diagnostic médical.",11,AMBER,false);safety.setPadding(0,dp(16),0,dp(12));content.addView(safety);Button begin=button(force?"Fermer":"Commencer",TEAL,0xff06251f);content.addView(begin,lp(-1,dp(52),0,0,0,0));dialog.setContentView(content);Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(0x00000000));window.setDimAmount(.78f);window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setLayout(-1,-2);}begin.setOnClickListener(v->{prefs.edit().putBoolean("welcome_v21",true).apply();dialog.dismiss();});dialog.setOnShowListener(ignored->{Window shown=dialog.getWindow();if(shown!=null)shown.setLayout(getResources().getDisplayMetrics().widthPixels-dp(28),-2);});dialog.show();}

    private LinearLayout page(String eyebrow,String subtitle){LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(20),dp(16),dp(30));body.setBackgroundColor(BG);body.addView(text(eyebrow,12,TEAL,true));body.addView(text(subtitle,27,TEXT,true),lp(-1,-2,0,5,0,0));return body;}
    private LinearLayout card(){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(round(CARD,LINE,18));card.setElevation(dp(2));return card;}
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private TextView miniCard(LinearLayout parent,String label,String value,int color){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(12),dp(10),dp(12));box.setBackground(round(CARD_ALT,LINE,15));TextView l=text(label,10,MUTED,true),v=text(value,14,color,true);v.setSingleLine(true);box.addView(l);box.addView(v,lp(-1,-2,0,5,0,0));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(72),1);params.setMargins(dp(3),0,dp(3),0);parent.addView(box,params);return v;}
    private View feature(String title,String detail){LinearLayout item=row();TextView icon=text("✓",16,TEAL,true);item.addView(icon,lp(dp(25),-2,0,0,6,0));LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.addView(text(title,14,TEXT,true));words.addView(text(detail,12,MUTED,false),lp(-1,-2,0,3,0,0));item.addView(words,new LinearLayout.LayoutParams(0,-2,1));item.setPadding(0,dp(13),0,0);return item;}
    private TextView navItem(String label){TextView item=text(label,12,MUTED,true);item.setGravity(Gravity.CENTER);item.setBackground(round(0x00000000,0x00000000,14));return item;}
    private TextView text(String value,float sp,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(sp);view.setTextColor(color);view.setLineSpacing(0,1.12f);if(bold)view.setTypeface(Typeface.create("sans",Typeface.BOLD));return view;}
    private Button button(String label,int fill,int textColor){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(13);button.setTextColor(textColor);button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);button.setBackground(ripple(fill,fill,16));button.setStateListAnimator(null);return button;}
    private Button outlineButton(String label){return button(label,CARD_ALT,TEXT);}
    private Button smallButton(String label){Button button=button(label,CARD_ALT,MUTED);button.setTextSize(10);button.setPadding(dp(2),0,dp(2),0);return button;}
    private GradientDrawable round(int fill,int stroke,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);drawable.setCornerRadius(dp(radius));drawable.setStroke(dp(1),stroke);return drawable;}
    private RippleDrawable ripple(int fill,int stroke,int radius){return new RippleDrawable(ColorStateList.valueOf(0x33ffffff),round(fill,stroke,radius),null);}
    private LinearLayout.LayoutParams lp(int width,int height,int left,int top,int right,int bottom){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(width,height);params.setMargins(dp(left),dp(top),dp(right),dp(bottom));return params;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
