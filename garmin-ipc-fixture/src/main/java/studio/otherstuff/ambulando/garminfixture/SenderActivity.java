package studio.otherstuff.ambulando.garminfixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.garmin.android.connectiq.*;
import com.garmin.monkeybrains.serialization.Serializer;
import java.util.*;

/** adb start --es session release_probe --es op start [--el device 72] [--es app ...]. */
public class SenderActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (getIntent().getBooleanExtra("verify", false)) {
                ClassLoader loader = createPackageContext("studio.otherstuff.ambulando.android", CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY).getClassLoader();
                android.os.Parcelable[] values = { new IQDevice(71L, "Canonical probe"), new IQApp("4edbc3d41f924958b53a29734ba5a821"), new IQMessage(new byte[]{1}, "fixture", "fixture") };
                for (android.os.Parcelable value : values) {
                    String name = value.getClass().getName();
                    Class<?> type = loader.loadClass(name);
                    if (type == value.getClass()) throw new AssertionError("Probe resolved fixture instead of installed release: " + name);
                    android.os.Parcelable.Creator<?> creator = (android.os.Parcelable.Creator<?>) type.getField("CREATOR").get(null);
                    android.os.Parcel parcel = android.os.Parcel.obtain();
                    try {
                        value.writeToParcel(parcel, 0); parcel.setDataPosition(0);
                        if (!name.equals(creator.createFromParcel(parcel).getClass().getName())) throw new AssertionError(name);
                    } finally { parcel.recycle(); }
                    android.util.Log.i("GarminIpcFixture", "Canonical Parcelable/CREATOR verified: " + name);
                }
            }
            String session = getIntent().getStringExtra("session");
            String op = getIntent().getStringExtra("op");
            Map<String, Object> command = new HashMap<>();
            command.put("v", 1); command.put("s", session); command.put("op", op); command.put("c", session + "_" + op);
            if (getIntent().getBooleanExtra("echoReply", false)) {
                Map<String, Object> reply = com.garmin.android.apps.connectmobile.connectiq.ConnectIQService.lastReply;
                if (reply == null) throw new IllegalStateException("No phone reply received");
                command = new HashMap<>(reply);
                command.put("op", "receipt"); command.put("b", 38);
                String bad = getIntent().getStringExtra("badReceipt");
                if ("nonce".equals(bad)) command.put("n", "stale");
                if ("revision".equals(bad)) command.put("r", -1);
                if ("session".equals(bad)) command.put("s", "stale");
                if ("build".equals(bad)) command.put("b", 37);
            }
            Intent message = new Intent("com.garmin.android.connectiq.INCOMING_MESSAGE").setPackage("studio.otherstuff.ambulando.android");
            if (getIntent().getBooleanExtra("malformed", false)) {
                message.putExtra("com.garmin.android.connectiq.EXTRA_REMOTE_DEVICE", new IQApp("wrong-type"));
            } else {
                message.putExtra("com.garmin.android.connectiq.EXTRA_REMOTE_DEVICE", new IQDevice(getIntent().getLongExtra("device", 71L), "External fixture watch"));
            }
            String app = getIntent().getStringExtra("app");
            message.putExtra("com.garmin.android.connectiq.EXTRA_REMOTE_APPLICATION", new IQApp(app == null ? "4edbc3d41f924958b53a29734ba5a821" : app));
            message.putExtra("com.garmin.android.connectiq.EXTRA_PAYLOAD", Serializer.serialize(command));
            sendBroadcast(message);
        } catch (Exception e) { throw new RuntimeException(e); }
        finish();
    }
}
