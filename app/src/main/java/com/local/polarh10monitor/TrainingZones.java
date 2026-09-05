package com.local.polarh10monitor;
import android.app.*;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.*;
/** Optional externally-established zones. Changes apply to the next session. */
public final class TrainingZones {
    public static void show(Activity a){SharedPreferences settings=a.getSharedPreferences("settings",0);LinearLayout form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(24,12,24,12);TextView help=new TextView(a);help.setText("Les zones automatiques sont des estimations liées à l’âge et à la FC au repos, pas une mesure de tes seuils. Elles sont suspendues si tu déclares un traitement influençant la FC.\n\nSi tu connais tes seuils avec ton professionnel, tu peux les renseigner ici. Ces valeurs ne changent jamais les alertes ECG. Application à la prochaine session.");form.addView(help);EditText moderate=new EditText(a),vigorous=new EditText(a);moderate.setHint("Début de zone modérée (bpm)");vigorous.setHint("Début de zone soutenue (bpm)");for(EditText e:new EditText[]{moderate,vigorous}){e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setSingleLine(true);form.addView(e);}int m=settings.getInt("zoneModerate",0),v=settings.getInt("zoneVigorous",0);if(m>0)moderate.setText(String.valueOf(m));if(v>0)vigorous.setText(String.valueOf(v));ScrollView scroll=new ScrollView(a);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Zones d’activité").setView(scroll).setNegativeButton("Annuler",null).setNeutralButton("Automatique",(d,w)->settings.edit().remove("zoneModerate").remove("zoneVigorous").apply()).setPositiveButton("Enregistrer",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(b->{try{int lower=Integer.parseInt(moderate.getText().toString()),upper=Integer.parseInt(vigorous.getText().toString());if(lower<60||upper<=lower||upper>220)throw new IllegalArgumentException();settings.edit().putInt("zoneModerate",lower).putInt("zoneVigorous",upper).apply();dialog.dismiss();}catch(Exception e){vigorous.setError("Entre deux seuils croissants entre 60 et 220 bpm.");}}));dialog.show();
    }
}
