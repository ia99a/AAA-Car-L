package com.santafe.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/** السرعة الحالية من GPS السيارة، بلا إنترنت. تحديث كل ثانية، وتتوقف حين تكون الشاشة الرئيسية في الخلفية. */
final class GpsSpeed implements LocationListener {

    interface Listener { void onSpeed(double kmh); }

    private final Context ctx;
    private final Listener listener;
    private LocationManager lm;

    GpsSpeed(Context c, Listener l) { ctx = c.getApplicationContext(); listener = l; }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    void start() {
        if (!hasPermission(ctx)) return;
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (Exception ignored) { }
    }

    void stop() {
        if (lm != null) try { lm.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override public void onLocationChanged(Location l) {
        if (l != null) listener.onSpeed(l.hasSpeed() ? l.getSpeed() * 3.6 : 0);
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }
}
