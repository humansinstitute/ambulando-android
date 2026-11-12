package com.garmin.android.apps.connectmobile.connectiq;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.garmin.android.connectiq.*;
import java.util.*;

/** Emulator-only external process implementing the real SDK Binder contract. */
public class ConnectIQService extends Service {
    public static Map<String, Object> lastReply;
    static IQDevice device() {
        IQDevice device = new IQDevice(71L, "Fixture connected watch");
        // Real known-device Parcelables can retain their constructor-default UNKNOWN.
        // Connectivity comes separately from getStatus, not this cached field.
        return device;
    }
    private static Object field(Map<?, ?> data, String key) {
        // MonkeyHash.toJava() retains wrapped dictionary keys and values.
        Object value = data.get(com.garmin.monkeybrains.serialization.MonkeyType.fromJava(key));
        return value instanceof com.garmin.monkeybrains.serialization.MonkeyType
            ? ((com.garmin.monkeybrains.serialization.MonkeyType<?>) value).toJava() : value;
    }
    private final IConnectIQService.Stub binder = new IConnectIQService.Stub() {
        public boolean openStore(String app) { return false; }
        public List<IQDevice> getConnectedDevices() { return Collections.singletonList(device()); }
        public List<IQDevice> getKnownDevices() { android.util.Log.i("GarminIpcFixture", "Known device cached status: " + device().getStatus()); return getConnectedDevices(); }
        public int getStatus(IQDevice device) { return IQDevice.IQDeviceStatus.CONNECTED.ordinal(); }
        public void getApplicationInfo(String a, String b, IQDevice d, String id) {}
        public void openApplication(String a, String b, IQDevice d, IQApp app) {}
        public void sendMessage(IQMessage m, IQDevice d, IQApp app) {
            try {
                for (com.garmin.monkeybrains.serialization.MonkeyType<?> value : com.garmin.monkeybrains.serialization.Serializer.deserialize(m.messageData)) {
                    Object payload = value.toJava();
                    if (payload instanceof Map) {
                        Map<?, ?> data = (Map<?, ?>) payload;
                        Map<String, Object> snapshot = new HashMap<>();
                        for (String key : Arrays.asList("v", "s", "r", "n", "b", "state", "detail")) snapshot.put(key, field(data, key));
                        lastReply = snapshot;
                        android.util.Log.i("GarminIpcFixture", "Phone reply device=" + d.getDeviceIdentifier() + " app=" + app.getApplicationId() + " session=" + field(data, "s") + " state=" + field(data, "state"));
                    }
                }
                Intent result = new Intent("com.garmin.android.connectiq.SEND_MESSAGE_STATUS").setPackage("studio.otherstuff.ambulando.android");
                result.putExtra("com.garmin.android.connectiq.EXTRA_REMOTE_DEVICE", d);
                result.putExtra("com.garmin.android.connectiq.EXTRA_APPLICATION_ID", app.getApplicationId());
                result.putExtra("com.garmin.android.connectiq.EXTRA_STATUS", 0);
                sendBroadcast(result);
            } catch (Exception e) { throw new IllegalArgumentException("Invalid phone payload", e); }
        }
        public void sendImage(IQMessage m, IQDevice d, IQApp app) {}
        public void registerApp(IQApp app, String a, String b) { android.util.Log.i("GarminIpcFixture", "Remote registration: " + app.getApplicationId()); }
        public void sendTransientMessage(IQMessage m, IQDevice d, IQApp app) {}
    };
    public IBinder onBind(Intent intent) { return binder; }
}
