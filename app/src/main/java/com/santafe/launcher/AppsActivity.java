package com.santafe.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * قائمة التطبيقات بصفحتين:
 *  ١) الأساسية: ٨ تطبيقات تختارها بمربعات كبيرة (ضغطة طويلة: تغيير أو إزالة)
 *  ٢) كل البرامج المثبتة
 * التنقل بزرّي الصفحتين أو بالسحب يمينًا ويسارًا.
 * ويُفتح أيضًا «وضع اختيار» لخانة في البار السفلي (يعرض كل البرامج فقط).
 */
public class AppsActivity extends Activity {

    private static class App {
        String label; String pkg; String cls; android.graphics.drawable.Drawable icon;
        ComponentName cn() { return new ComponentName(pkg, cls); }
    }

    static final String EXTRA_PICK_SLOT = "pick_slot";
    static final String EXTRA_COMPONENT = "component";
    static final int FAVS = 8;

    /** تطبيقات تُقترح للصفحة الأساسية أول مرة، إن كانت مثبتة */
    private static final String[] SUGGESTED = {
            "com.google.android.apps.maps", "hudhud", "com.google.android.youtube", "com.spotify.music",
            "com.anghami", "com.whatsapp", "com.brave.browser", "com.android.chrome",
            "com.google.android.apps.youtube.music", "com.android.vending", "com.android.settings"
    };

    private final List<App> apps = new ArrayList<>();
    private boolean pickMode;
    private int assigning = -1; // خانة أساسية ننتظر اختيار تطبيقها
    private SharedPreferences prefs;
    private View favPage, grid, tabFavs, tabAll;
    private TextView pickTitle;
    private GestureDetector swipe;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        prefs = Lang.prefs(this);
        favPage = findViewById(R.id.fav_page);
        grid = findViewById(R.id.grid);
        tabFavs = findViewById(R.id.tab_favs);
        tabAll = findViewById(R.id.tab_all);
        pickTitle = findViewById(R.id.pick_title);
        findViewById(R.id.btn_home).setOnClickListener(v -> finish());
        tabFavs.setOnClickListener(v -> showPage(false));
        tabAll.setOnClickListener(v -> showPage(true));

        // وضع الاختيار: يُفتح من خانة في البار لاختيار تطبيقها
        pickMode = getIntent().hasExtra(EXTRA_PICK_SLOT);
        if (pickMode) {
            findViewById(R.id.tabs).setVisibility(View.GONE);
            pickTitle.setVisibility(View.VISIBLE);
            View clear = findViewById(R.id.btn_clear);
            clear.setVisibility(View.VISIBLE);
            clear.setOnClickListener(v -> { setResult(RESULT_OK, new Intent()); finish(); });
        }

        GridView g = (GridView) grid;
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                if (v == null) v = LayoutInflater.from(AppsActivity.this).inflate(R.layout.item_app, parent, false);
                App a = apps.get(p);
                ((ImageView) v.findViewById(R.id.app_icon)).setImageDrawable(a.icon);
                ((TextView) v.findViewById(R.id.app_label)).setText(a.label);
                return v;
            }
        };
        g.setAdapter(adapter);
        g.setOnItemClickListener((parent, view, p, id) -> onAppChosen(apps.get(p)));

        // السحب الأفقي بين الصفحتين
        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (pickMode || a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) < 120 || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                boolean next = rtl ? dx > 0 : dx < 0; // «التالي» في اتجاه القراءة
                showPage(next);
                return true;
            }
        });

        showPage(pickMode || prefs.getBoolean("apps_last_all", false));
        // الأيقونات تُحمَّل في الخلفية فتفتح الشاشة فورًا دون تعليق
        new Thread(() -> {
            List<App> loaded = loadApps();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
                if (!prefs.getBoolean("favs_init", false)) suggestFavs();
                buildFavs();
            });
        }, "apps-loader").start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (swipe != null && swipe.onTouchEvent(e)) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel); // لا يُعدّ السحب ضغطة على تطبيق
            cancel.recycle();
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (assigning >= 0) { assigning = -1; pickTitle.setVisibility(View.GONE); showPage(false); return; }
        super.onBackPressed();
    }

    private List<App> loadApps() {
        List<App> apps = new ArrayList<>();
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
        return apps;
    }

    private void showPage(boolean all) {
        if (assigning >= 0 && !all) { assigning = -1; pickTitle.setVisibility(View.GONE); }
        favPage.setVisibility(all ? View.GONE : View.VISIBLE);
        grid.setVisibility(all ? View.VISIBLE : View.GONE);
        tabFavs.setSelected(!all);
        tabAll.setSelected(all);
        if (!pickMode) prefs.edit().putBoolean("apps_last_all", all && assigning < 0).apply();
    }

    private void onAppChosen(App a) {
        if (pickMode) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_COMPONENT, a.cn().flattenToString()));
            finish();
            return;
        }
        if (assigning >= 0) {
            prefs.edit().putString("fav_" + assigning, a.cn().flattenToString()).apply();
            assigning = -1;
            pickTitle.setVisibility(View.GONE);
            buildFavs();
            showPage(false);
            return;
        }
        launch(a.cn());
    }

    private void launch(ComponentName cn) {
        if (!Safe.isLaunchable(this, cn)) { buildFavs(); return; }
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        finish();
    }

    // ───────── الصفحة الأساسية ─────────

    /** أول مرة: نملأ الخانات بالتطبيقات الشائعة المثبتة */
    private void suggestFavs() {
        int slot = 0;
        SharedPreferences.Editor ed = prefs.edit();
        for (String want : SUGGESTED) {
            if (slot >= FAVS) break;
            for (App a : apps) {
                boolean match = want.contains(".") ? a.pkg.equals(want) : a.pkg.toLowerCase(java.util.Locale.ROOT).contains(want);
                if (match) { ed.putString("fav_" + slot++, a.cn().flattenToString()); break; }
            }
        }
        ed.putBoolean("favs_init", true).apply();
    }

    private App find(ComponentName cn) {
        if (cn == null) return null;
        for (App a : apps) if (a.pkg.equals(cn.getPackageName()) && a.cls.equals(cn.getClassName())) return a;
        return null;
    }

    private void buildFavs() {
        LinearLayout r1 = findViewById(R.id.fav_row_1), r2 = findViewById(R.id.fav_row_2);
        r1.removeAllViews();
        r2.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < FAVS; i++) {
            final int slot = i;
            LinearLayout row = i < FAVS / 2 ? r1 : r2;
            View tile = inf.inflate(R.layout.item_fav, row, false);
            if (row.getChildCount() > 0) ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart((int) (12 * dp));
            ImageView icon = tile.findViewById(R.id.fav_icon);
            TextView label = tile.findViewById(R.id.fav_label);
            String saved = prefs.getString("fav_" + i, null);
            App a = find(saved == null ? null : ComponentName.unflattenFromString(saved));
            if (saved != null && a == null) prefs.edit().remove("fav_" + i).apply(); // حُذف التطبيق
            if (a != null) {
                icon.setImageDrawable(a.icon);
                label.setText(a.label);
                tile.setContentDescription(a.label);
                tile.setOnClickListener(v -> launch(a.cn()));
                tile.setOnLongClickListener(v -> { slotMenu(v, slot); return true; });
            } else {
                icon.setImageResource(R.drawable.ic_add);
                icon.setAlpha(0.6f);
                label.setText(R.string.dock_add);
                label.setTextColor(getColor(R.color.ink_faint));
                tile.setAlpha(0.75f);
                tile.setOnClickListener(v -> startAssign(slot));
            }
            row.addView(tile);
        }
    }

    private void slotMenu(View anchor, int slot) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.fav_change);
        m.getMenu().add(0, 2, 1, R.string.fav_remove);
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) startAssign(slot);
            else { prefs.edit().remove("fav_" + slot).apply(); buildFavs(); }
            return true;
        });
        m.show();
    }

    /** اختيار تطبيق لخانة: ننتقل لكل البرامج مع عنوان يوضح ذلك */
    private void startAssign(int slot) {
        assigning = slot;
        pickTitle.setText(R.string.fav_pick_title);
        pickTitle.setVisibility(View.VISIBLE);
        showPage(true);
        assigning = slot; // showPage(true) لا يلغيها
    }
}
