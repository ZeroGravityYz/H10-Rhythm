package com.local.polarh10monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Debounced, paged unified history. Loading and filtering never parse the whole history on the UI thread. */
public final class TimelineView extends LinearLayout {
    public interface Open {void event(EventHistory.Record event);}
    private final Activity activity;private final Open open;private final LinearLayout rows;private final EditText search;private final TextView status;
    private final Handler handler=new Handler();private final ExecutorService worker=Executors.newSingleThreadExecutor();private int revision,limit=50;private String kind="*";private boolean favorites;private long since,until=Long.MAX_VALUE;
    private final Map<String,JSONObject> selected=new HashMap<>();
    public TimelineView(Activity a,Open open){super(a);activity=a;this.open=open;setOrientation(VERTICAL);
        search=new EditText(a);search.setTextColor(0xfff0f5fa);search.setHintTextColor(0xff9aafc0);search.setHint("Rechercher une activité, un passage…");search.setSingleLine(true);addView(search);
        LinearLayout filters=new LinearLayout(a);Spinner type=new Spinner(a);String[] names={"Tout","Passages ECG","Bilans","Séances déclarées","Sessions H10"};String[] kinds={"*","event","morning","workout","session"};type.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,names));filters.addView(type,new LayoutParams(0,dp(56),1));Button star=button("☆ Favoris");filters.addView(star);addView(filters);
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){kind=kinds[pos];limit=50;refresh();}});star.setOnClickListener(v->{favorites=!favorites;star.setText(favorites?"★ Favoris":"☆ Favoris");refresh();});
        LinearLayout dates=new LinearLayout(a);for(int days:new int[]{7,30,90,0}){Button b=button(days==0?"Tout":days+" j");dates.addView(b,new LayoutParams(0,dp(52),1));b.setMinWidth(0);b.setPadding(0,0,0,0);b.setOnClickListener(v->{since=days==0?0:System.currentTimeMillis()-days*86400000L;until=Long.MAX_VALUE;refresh();});}Button date=button("Date");dates.addView(date,new LayoutParams(0,dp(52),1));date.setMinWidth(0);date.setPadding(0,0,0,0);date.setOnClickListener(v->{Calendar c=Calendar.getInstance();new DatePickerDialog(a,(p,y,m,d)->{c.set(y,m,d,0,0,0);c.set(Calendar.MILLISECOND,0);since=c.getTimeInMillis();c.add(Calendar.DATE,1);until=c.getTimeInMillis();refresh();},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();});addView(dates);
        status=label("");addView(status);rows=new LinearLayout(a);rows.setOrientation(LinearLayout.VERTICAL);addView(rows);Button more=button("Afficher plus");more.setOnClickListener(v->{limit+=50;refresh();});addView(more);Button delete=button("Supprimer la sélection");delete.setOnClickListener(v->deleteSelection());addView(delete);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int st,int before,int count){handler.removeCallbacks(delayed);handler.postDelayed(delayed,180);}});
    }
    private final Runnable delayed=this::refresh;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private Button button(String s){Button b=new Button(activity);b.setText(s);b.setAllCaps(false);b.setTextSize(12);return b;}
    private TextView label(String s){TextView t=new TextView(activity);t.setText(s);t.setTextColor(0xffcfdae3);t.setTextSize(13);t.setPadding(8,16,8,16);return t;}
    public void refresh(){if(rows==null||worker.isShutdown())return;int request=++revision;String k=kind,q=search.getText().toString();boolean f=favorites;long from=since,to=until;int count=limit;
        worker.execute(()->{List<JSONObject> values=LocalRepository.get(activity).timeline(k,q,from,to,f,count);activity.runOnUiThread(()->{if(activity.isDestroyed()||request!=revision)return;rows.removeAllViews();status.setText(values.size()+" résultat(s) affiché(s) · "+selected.size()+" sélectionné(s)");if(values.isEmpty())rows.addView(label("Aucune donnée pour ces filtres. Les nouveaux bilans et passages apparaîtront ici."));for(JSONObject o:values)row(o);});});}
    private void row(JSONObject o){String k=o.optString("_kind"),id=o.optString("_id"),key=k+":"+id;LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(16,12,16,12);card.setBackgroundColor(0xff142532);LayoutParams margin=new LayoutParams(-1,-2);margin.bottomMargin=16;rows.addView(card,margin);
        String title=k.equals("event")?o.optString("title"):k.equals("morning")?"Bilan au calme":k.equals("workout")?o.optString("sport")+" · "+o.optInt("minutes")+" min":"Session H10";
        CheckBox select=new CheckBox(activity);select.setText(title);select.setTextColor(0xffeff5fa);select.setChecked(selected.containsKey(key));select.setOnCheckedChangeListener((v,value)->{if(value)selected.put(key,o);else selected.remove(key);status.setText(selected.size()+" élément(s) sélectionné(s)");});card.addView(select);
        long time=o.optLong("time",o.optLong("start"));card.addView(label(new SimpleDateFormat("EEEE d MMMM · HH:mm",Locale.FRANCE).format(new Date(time))));
        LinearLayout actions=new LinearLayout(activity);Button view=button("Consulter"),favorite=button(o.optBoolean("favorite")?"★":"☆");actions.addView(view,new LayoutParams(0,dp(52),1));actions.addView(favorite);card.addView(actions);
        favorite.setContentDescription("Ajouter ou retirer des favoris");favorite.setOnClickListener(v->{LocalRepository.get(activity).favorite(k,id,!o.optBoolean("favorite"));refresh();});
        view.setOnClickListener(v->{if(k.equals("event")){open.event(EventHistory.Record.fromJson(o));return;}String detail=k.equals("morning")?String.format(Locale.FRANCE,"FC au repos %.0f bpm\nRMSSD %.0f ms · SDNN %.0f ms\nSignal %.0f %% · %d intervalles\n%s",o.optDouble("hr"),o.optDouble("rmssd"),o.optDouble("sdnn"),o.optDouble("quality"),o.optInt("nn"),o.optBoolean("continuityVerified")?"Continuité vérifiée":"Ancienne mesure : continuité non vérifiée"):k.equals("workout")?"Effort perçu : "+o.optInt("rpe")+"/10\nSéance "+(o.optBoolean("completed")?"terminée":"interrompue"):"FC moyenne : "+o.optInt("avg")+" bpm\nSignal : "+Math.round(o.optDouble("quality"))+" %\nLes interruptions ne sont pas des mesures cardiaques.";new AlertDialog.Builder(activity).setTitle(title).setMessage(detail).setPositiveButton("Fermer",null).show();});
    }
    private void deleteSelection(){if(selected.isEmpty())return;ArrayList<JSONObject> targets=new ArrayList<>(selected.values());new AlertDialog.Builder(activity).setTitle("Supprimer "+targets.size()+" élément(s) ?").setMessage("Les rapports sélectionnés et leurs signaux seront effacés. Les exemples appris sont conservés. Supprimer une séance ou un bilan invalide les prévisions associées.").setNegativeButton("Annuler",null).setPositiveButton("Supprimer",(d,w)->worker.execute(()->{int failures=0;for(JSONObject o:targets){String k=o.optString("_kind"),id=o.optString("_id");if(k.equals("event")){if(!EventHistory.deleteOne(activity,id)){failures++;continue;}}else{LocalRepository.get(activity).delete(k,id);LocalRepository.get(activity).clear("forecast");}}int failed=failures;activity.runOnUiThread(()->{selected.clear();refresh();Toast.makeText(activity,failed==0?"Sélection supprimée":"Certains fichiers n’ont pas pu être supprimés.",Toast.LENGTH_LONG).show();});})).show();}
    public void close(){handler.removeCallbacks(delayed);worker.shutdown();}
}
