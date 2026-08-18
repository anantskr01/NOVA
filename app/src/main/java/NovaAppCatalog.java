package com.aircontrol;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Discovers launchable apps dynamically instead of maintaining a hard-coded list. */
public final class NovaAppCatalog {
    private final Context context;
    private final PackageManager pm;

    public NovaAppCatalog(Context context) {
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    public ResolveInfo resolve(String spokenName) {
        if (spokenName == null || spokenName.trim().isEmpty()) return null;
        String query = spokenName.trim().toLowerCase(Locale.ROOT);
        List<ResolveInfo> apps = getLaunchableApps();
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo info : apps) {
            String label = String.valueOf(info.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = info.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(query)) score = 100;
            else if (label.startsWith(query)) score = 80;
            else if (label.contains(query)) score = 60;
            else if (pkg.contains(query)) score = 40;
            if (score > bestScore) {
                bestScore = score;
                best = info;
            }
        }
        return best;
    }

    public Intent launchIntent(ResolveInfo info) {
        if (info == null || info.activityInfo == null) return null;
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public String launchableSummary(int max) {
        List<ResolveInfo> apps = getLaunchableApps();
        StringBuilder out = new StringBuilder();
        int count = Math.min(max, apps.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append("\n");
            out.append("• ").append(apps.get(i).loadLabel(pm));
        }
        return count == 0 ? "No launchable apps were found." : out.toString();
    }

    public List<ResolveInfo> getLaunchableApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> result = pm.queryIntentActivities(launcher, 0);
        if (result == null) result = new ArrayList<>();
        Collections.sort(result, Comparator.comparing(info -> String.valueOf(info.loadLabel(pm)).toLowerCase(Locale.ROOT)));
        return result;
    }
}
