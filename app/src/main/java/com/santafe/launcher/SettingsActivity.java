package com.santafe.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * إعدادات المشغّل: لغة الواجهة، تطبيق الخرائط لزر «موقعي»، إظهار البار السفلي أو إخفاؤه، حجب الإعلانات، إعدادات أندرويد،
 * وإضافة أدوات شريط السائق أو إزالتها (الترتيب في الشريط يتبع ترتيب القائمة).
 */
public class SettingsActivity extends Activity {

    private final List<String> chosen = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context base) { super.attachBaseContext(Lang.wrap(base)); }

    /** تطبيق الخرائط لزر «موقعي»: "maps" (افتراضي) أو "hudhud" */
    static String navApp(Context c) {
        return "hudhud".equals(Lang.prefs(c).getString("nav_app", "maps")) ? "hudhud" : "maps";
    }

    /** هل البار السفلي ظاهر؟ ظاهر افتراضيًا */
    static boolean dockVisible(Context c) {
        return Lang.prefs(c).getBoolean("show_dock", true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        chosen.addAll(Tools.enabled(this));

        // اللغة: نحفظ ثم نعيد رسم الشاشة باللغة الجديدة
        View ar = findViewById(R.id.lang_ar), en = findViewById(R.id.lang_en);
        ar.setSelected(Lang.isArabic(this));
        en.setSelected(!Lang.isArabic(this));
        ar.setOnClickListener(v -> setLang("ar"));
        en.setOnClickListener(v -> setLang("en"));

        // تطبيق الخرائط الذي يفتحه زر «موقعي»
        View navMaps = findViewById(R.id.nav_maps), navHud = findViewById(R.id.nav_hudhud);
        Runnable paintNav = () -> {
            boolean hud = "hudhud".equals(navApp(this));
            navMaps.setSelected(!hud);
            navHud.setSelected(hud);
        };
        paintNav.run();
        navMaps.setOnClickListener(v -> { Lang.prefs(this).edit().putString("nav_app", "maps").apply(); paintNav.run(); });
        navHud.setOnClickListener(v -> { Lang.prefs(this).edit().putString("nav_app", "hudhud").apply(); paintNav.run(); });

        // البار السفلي
        Switch sw = findViewById(R.id.switch_dock);
        sw.setChecked(dockVisible(this));
        findViewById(R.id.row_dock).setOnClickListener(v -> {
            boolean show = !dockVisible(this);
            Lang.prefs(this).edit().putBoolean("show_dock", show).apply();
            sw.setChecked(show);
        });

        findViewById(R.id.row_adblock).setOnClickListener(v -> AdBlock.setup(this));

        findViewById(R.id.row_system).setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        });

        // أدوات شريط السائق
        GridView grid = findViewById(R.id.tools_grid);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return Tools.ALL.length; }
            @Override public Object getItem(int p) { return Tools.ALL[p]; }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                if (v == null) v = LayoutInflater.from(SettingsActivity.this).inflate(R.layout.item_tool, parent, false);
                String k = Tools.ALL[p];
                boolean on = chosen.contains(k);
                ImageView icon = v.findViewById(R.id.t_icon);
                icon.setImageResource(Tools.icon(k));
                icon.setAlpha(on ? 1f : 0.45f);
                ((TextView) v.findViewById(R.id.t_name)).setText(Tools.label(k));
                ((TextView) v.findViewById(R.id.t_desc)).setText(Tools.desc(k));
                ImageView check = v.findViewById(R.id.t_check);
                check.setBackgroundResource(on ? R.drawable.check_on_bg : R.drawable.check_off_bg);
                check.setImageResource(on ? R.drawable.ic_check : 0);
                return v;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, p, id) -> {
            String k = Tools.ALL[p];
            if (chosen.contains(k)) {
                if (chosen.size() == 1) { Toast.makeText(this, R.string.tools_min, Toast.LENGTH_SHORT).show(); return; }
                chosen.remove(k);
            } else {
                if (chosen.size() >= Tools.MAX) { Toast.makeText(this, R.string.tools_max, Toast.LENGTH_SHORT).show(); return; }
                chosen.add(k);
            }
            adapter.notifyDataSetChanged();
            saveTools();
        });

        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // نحدّث حالة الحجب عند الرجوع من إعدادات الشبكة
        boolean on = AdBlock.isOn(this);
        ((ImageView) findViewById(R.id.adblock_icon)).setColorFilter(getColor(on ? R.color.green : R.color.ink_soft));
        TextView st = findViewById(R.id.adblock_state);
        st.setText(on ? R.string.set_adblock_on : R.string.set_adblock_off);
        st.setTextColor(getColor(on ? R.color.green : R.color.ink_faint));
    }

    private void setLang(String lang) {
        if (lang.equals(Lang.get(this))) return;
        Lang.prefs(this).edit().putString("lang", lang).apply();
        recreate(); // الرئيسية تتحدث بنفسها عند الرجوع إليها
    }

    private void saveTools() {
        List<String> ordered = new ArrayList<>();
        for (String k : Tools.ALL) if (chosen.contains(k)) ordered.add(k);
        Tools.save(this, ordered);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTools();
    }
}
