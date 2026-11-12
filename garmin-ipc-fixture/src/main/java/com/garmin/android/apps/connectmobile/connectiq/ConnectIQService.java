package com.garmin.android.apps.connectmobile.connectiq;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.garmin.android.connectiq.*;
import java.util.*;

/** Emulator-only external process implementing the real SDK Binder contract. */
public class ConnectIQService extends Service {
    static IQDevice device() {
        IQDevice device = new IQDevice(71L, "Fixture connected watch");
        device.setStatus(IQDevice.IQDeviceStatus.CONNECTED);
        return device;
    }
    private final IConnectIQService.Stub binder = new IConnectIQService.Stub() {
        public boolean openStore(String app) { return false; }
        public List<IQDevice> getConnectedDevices() { return Collections.singletonList(device()); }
        public List<IQDevice> getKnownDevices() { return getConnectedDevices(); }
        public int getStatus(IQDevice device) { return IQDevice.IQDeviceStatus.CONNECTED.ordinal(); }
        public void getApplicationInfo(String a, String b, IQDevice d, String id) {}
        public void openApplication(String a, String b, IQDevice d, IQApp app) {}
        public void sendMessage(IQMessage m, IQDevice d, IQApp app) {}
        public void sendImage(IQMessage m, IQDevice d, IQApp app) {}
        public void registerApp(IQApp app, String a, String b) {}
        public void sendTransientMessage(IQMessage m, IQDevice d, IQApp app) {}
    };
    public IBinder onBind(Intent intent) { return binder; }
}
