package green_green_avk.anothertermshellplugin_batteryinfo;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import green_green_avk.anothertermshellpluginutils.BaseShellService;
import green_green_avk.anothertermshellpluginutils.ExecutionContext;
import green_green_avk.anothertermshellpluginutils.MainThreadHelper;
import green_green_avk.anothertermshellpluginutils.Protocol;
import green_green_avk.anothertermshellpluginutils.Utils;

public final class ShellService extends BaseShellService {

    private String getStrStatus(final int v) {
        final String[] strStatus =
                this.getResources().getStringArray(R.array.msg_battery_status);
        try {
            return strStatus[v];
        } catch (final IndexOutOfBoundsException e) {
            return strStatus[0];
        }
    }

    private static final class BatteryInfo {
        private int level; // %
        private int status;
        private int temperature;
        private String technology;
    }

    private static BatteryInfo getBatteryInfo(@NonNull final Context context) {
        final BatteryInfo r = new BatteryInfo();
        final Intent batteryStatus = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) return null;
        if (Build.VERSION.SDK_INT >= 26) {
            final BatteryManager bm = (BatteryManager) context.getSystemService(BATTERY_SERVICE);
            r.level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            r.status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        } else {
            final int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale < 1) r.level = -1;
            else r.level = (int) ((level / (double) scale) * 100);
            r.status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        }
        r.temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        r.technology = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        return r;
    }

    @Override
    protected int onExec(@NonNull final ExecutionContext execCtx,
                         @NonNull final byte[][] args, @NonNull final ParcelFileDescriptor[] fds) {
        final OutputStream stderr = new FileOutputStream(fds[2].getFileDescriptor());
        final OutputStream stdout = new FileOutputStream(fds[1].getFileDescriptor());
        if (!execCtx.verify(BuildConfig.DEBUG ?
                BaseShellService.trustedClientsDebug : BaseShellService.trustedClients)) {
            Utils.write(stderr, "Access denied\n");
            return -1;
        }
        final BatteryInfo r;
        try {
            r = MainThreadHelper.run(new Callable<BatteryInfo>() {
                @Override
                public BatteryInfo call() {
                    return getBatteryInfo(ShellService.this);
                }
            });
        } catch (final ExecutionException e) {
            Utils.write(stderr, e.getMessage() + "\n");
            return -1;
        } catch (final InterruptedException e) {
            Utils.write(stderr, e.getMessage() + "\n");
            return -1;
        }
        if (r == null) {
            Utils.write(stderr, "Can't get battery info\n");
            return -1;
        }
        Utils.write(stdout, String.format(Locale.ROOT, "%d%% / %s / %d.%d °C / %s\n",
                r.level,
                getStrStatus(r.status),
                r.temperature / 10, r.temperature % 10,
                r.technology
        ));
        return 0;
    }

    @Override
    protected Bundle onMeta() {
        final Bundle b = new Bundle();
        b.putInt(Protocol.META_KEY_INFO_RES_ID, R.string.desc_plugin);
        b.putInt(Protocol.META_KEY_INFO_RES_TYPE, Protocol.STRING_CONTENT_TYPE_XML_AT);
        return b;
    }
}
