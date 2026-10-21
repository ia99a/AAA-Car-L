package com.santafe.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** شبكة بسيطة لكل التطبيقات المثبتة (الإعدادات، الموسيقى، البلوتوث...) */
public class AppsActivity extends Activity {

    private static class App {
        String label; String pkg; String cls; android.graphics.drawable.Drawable icon;
    }

    static final String EXTRA_PICK_SLOT = "pick_slot";
    static final String EXTRA_COMPONENT = "component";

    private final List<App> apps = new ArrayList<>();
    private boolean pickMode;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        findViewById(R.id.btn_home).setOnClickListener(v -> finish());

        // وضع الاختيار: يُفتح من خانة في البار لاختيار تطبيقها
        pickMode = getIntent().hasExtra(EXTRA_PICK_SLOT);
        if (pickMode) {
            findViewById(R.id.pick_title).setVisibility(View.VISIBLE);
            View clear = findViewById(R.id.btn_clear);
            clear.setVisibility(View.VISIBLE);
            clear.setOnClickListener(v -> { setResult(RESULT_OK, new Intent()); finish(); });
        }

        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            App a = new App();
            a.label = String.valueOf(r.loadLabel(pm));
            a.pkg = r.activityInfo.packageName;
            a.cls = r.activityInfo.name;
            a.icon = r.loadIcon(pm);
            apps.add(a);
        }
        final Collator c = Collator.getInstance();
        Collections.sort(apps, (x, y) -> c.compare(x.label, y.label));

        GridView grid = findViewById(R.id.grid);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                if (v == null) v = LayoutInflater.from(AppsActivity.this)
                        .inflate(R.layout.item_app, parent, false);
                App a = apps.get(p);
                ((ImageView) v.findViewById(R.id.app_icon)).setImageDrawable(a.icon);
                ((TextView) v.findViewById(R.id.app_label)).setText(a.label);
                return v;
            }
        });
        grid.setOnItemClickListener((parent, view, p, id) -> {
            App a = apps.get(p);
            if (pickMode) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_COMPONENT,
                        new android.content.ComponentName(a.pkg, a.cls).flattenToString()));
                finish();
                return;
            }
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(a.pkg, a.cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
            finish();
        });
    }
}
