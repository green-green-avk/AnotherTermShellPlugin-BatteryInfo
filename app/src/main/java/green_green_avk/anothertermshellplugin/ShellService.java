package green_green_avk.anothertermshellplugin;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import green_green_avk.anothertermshellplugin_batteryinfo.R;
import green_green_avk.anothertermshellpluginutils.BaseShellService;
import green_green_avk.anothertermshellpluginutils.ExecutionContext;
import green_green_avk.anothertermshellpluginutils.MainThreadHelper;
import green_green_avk.anothertermshellpluginutils.Protocol;
import green_green_avk.anothertermshellpluginutils.Utils;

public final class ShellService extends BaseShellService {

    private String getIntStr(final int v, @ArrayRes final int resId) {
        final String[] strs =
                this.getResources().getStringArray(resId);
        try {
            return strs[v];
        } catch (final IndexOutOfBoundsException e) {
            return strs[0];
        }
    }

    private String getFlagsStr(final int v, @ArrayRes final int resId) {
        final String[] strs =
                this.getResources().getStringArray(resId);
        final StringBuilder r = new StringBuilder();
        for (int i = 0; i < strs.length; i++) {
            if ((v & (1 << i)) != 0) {
                if (i != 0) r.append(", ");
                r.append(strs[i]);
            }
        }
        return r.toString();
    }

    private static final class BatteryInfo {
        private boolean present = false;
        private int plugged;
        private int level; // %
        private int status;
        private int health;
        private int temperature; // 0.1 C
        private int voltage;
        private String technology;
        private int current_now = 0;
        private int current_average = 0;
        private int charge_counter = -1;
        private long energy_counter = -1;
    }

    private static BatteryInfo getBatteryInfo(@NonNull final Context context) {
        final BatteryInfo r = new BatteryInfo();
        final Intent batteryStatus = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) return null;
        r.plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (!(r.present = batteryStatus.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)))
            return r;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final BatteryManager bm = (BatteryManager) context.getSystemService(BATTERY_SERVICE);
            r.level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            r.current_now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            r.current_average = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            r.charge_counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            r.energy_counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                r.status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
            } else {
                r.status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            }
        } else {
            final int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale < 1) r.level = -1;
            else r.level = (int) ((level / (double) scale) * 100);
            r.status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        }
        r.health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
        r.temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        r.voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        r.technology = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        return r;
    }

    @Override
    protected int onExec(@NonNull final ExecutionContext execCtx,
                         @NonNull final byte[][] args, @NonNull final ParcelFileDescriptor[] fds) {
        final OutputStream stderr = new FileOutputStream(fds[2].getFileDescriptor());
        final OutputStream stdout = new FileOutputStream(fds[1].getFileDescriptor());
        try {
            final BatteryInfo r;
            try {
                r = MainThreadHelper.run(() -> getBatteryInfo(ShellService.this));
            } catch (final ExecutionException e) {
                Utils.write(stderr, e.getMessage() + "\n");
                return 1;
            } catch (final InterruptedException e) {
                Utils.write(stderr, e.getMessage() + "\n");
                return 1;
            }
            if (r == null) {
                Utils.write(stderr, "Can't get battery info\n");
                return 1;
            }
            Utils.write(stdout, String.format(Locale.ROOT,
                    getString(R.string.msg_external_power_source_s) + "\n",
                    getFlagsStr(r.plugged, R.array.msg_battery_plugged)
            ));
            if (!r.present) {
                Utils.write(stdout, getString(R.string.msg_battery_not_present) + "\n");
                return 0;
            }
            Utils.write(stdout, String.format(Locale.ROOT,
                    "%d%% / %s / %s / %d.%d °C / %d %sV / %s\n",
                    r.level,
                    getIntStr(r.status, R.array.msg_battery_status),
                    getIntStr(r.health, R.array.msg_battery_health),
                    r.temperature / 10, r.temperature % 10,
                    // https://stackoverflow.com/questions/24500795/android-battery-voltage-unit-discrepancies
                    r.voltage, r.voltage < 500 ? "" : "m",
                    r.technology
            ));
            if (r.energy_counter >= 0)
                Utils.write(stdout, String.format(Locale.ROOT,
                        "%d µA / %d µA / %d µA·h / %d nW·h\n",
                        r.current_now,
                        r.current_average,
                        r.charge_counter,
                        r.energy_counter
                ));
            return 0;
        } finally {
            try {
                stdout.close();
            } catch (final IOException ignored) {
            }
            try {
                stderr.close();
            } catch (final IOException ignored) {
            }
        }
    }

    @Override
    protected Bundle onMeta() {
        final Bundle b = new Bundle();
        b.putInt(Protocol.META_KEY_INFO_RES_ID, R.string.desc_plugin);
        b.putInt(Protocol.META_KEY_INFO_RES_TYPE, Protocol.STRING_CONTENT_TYPE_XML_AT);
        return b;
    }
}
