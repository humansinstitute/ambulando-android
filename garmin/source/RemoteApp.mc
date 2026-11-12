using Toybox.Application;
using Toybox.Communications;
using Toybox.Graphics;
using Toybox.WatchUi;
using Toybox.System;
using Toybox.Time;
using Toybox.Math;
using Toybox.Timer;
using Toybox.Lang;

class RemoteApp extends Application.AppBase {
    var session = null;
    var state = "idle";
    var detail = "Phone headset required";
    var revision = -1;
    var pending = null;
    var timer = null;
    function initialize() { AppBase.initialize(); }
    function onStart(args) {
        session = Application.Storage.getValue("session");
        if (session != null) { state = "waiting"; detail = "Checking phone status"; }
        Communications.registerForPhoneAppMessages(method(:received));
        timer = new Timer.Timer();
        timer.start(method(:poll), 5000, true);
    }
    function onStop(args) { if (timer != null) { timer.stop(); } }
    function poll() as Void {
        if (session == null || state == "sent" || state == "cancelled" || state == "error") { return; }
        if (pending != null) { send(pending); } else { send("status"); }
    }
    function getInitialView() { return [new RemoteView(), new RemoteDelegate()]; }
    function send(op) {
        if (session == null) {
            session = Time.now().value().toString() + "_" + Math.rand().toString();
            Application.Storage.setValue("session", session);
            revision = -1;
        }
        pending = op;
        detail = "Waiting for phone acknowledgement";
        Communications.transmit({"v"=>1, "s"=>session, "c"=>session + "_" + op, "op"=>op}, null, new RemoteListener());
        WatchUi.requestUpdate();
    }
    function received(message as Communications.PhoneAppMessage) as Void {
        var m = message.data;
        if (!(m instanceof Toybox.Lang.Dictionary) || m["v"] != 1 || m["s"] != session) { return; }
        if (m["r"] < revision) { return; }
        revision = m["r"];
        state = m["state"];
        detail = m["detail"];
        pending = null;
        WatchUi.requestUpdate();
    }
    function fresh() {
        session = null; revision = -1; state = "idle"; pending = null;
        Application.Storage.deleteValue("session");
        WatchUi.requestUpdate();
    }
}
class RemoteListener extends Communications.ConnectionListener {
    function initialize() { ConnectionListener.initialize(); }
    function onComplete() { /* Transport success is not recording success. */ }
    function onError() {
        var app = Application.getApp();
        app.detail = "Phone unavailable. Reopen Ambulando; Status to retry.";
        WatchUi.requestUpdate();
    }
}
class RemoteView extends WatchUi.View {
    function initialize() { View.initialize(); }
    function onUpdate(dc) {
        var a = Application.getApp();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK); dc.clear();
        var x = dc.getWidth()/2;
        dc.drawText(x, 65, Graphics.FONT_SMALL, "Ambulando Remote", Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(x, 110, Graphics.FONT_MEDIUM, a.state, Graphics.TEXT_JUSTIFY_CENTER);
        // Compact status plus menu avoids clipping long phone errors on the round screen.
        var text = a.detail;
        if (text.length() > 30) { text = text.substring(0, 30); }
        dc.drawText(x, 160, Graphics.FONT_XTINY, text, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(x, 220, Graphics.FONT_SMALL, "Tap for controls", Graphics.TEXT_JUSTIFY_CENTER);
    }
}
class RemoteDelegate extends WatchUi.BehaviorDelegate {
    function initialize() { BehaviorDelegate.initialize(); }
    function onSelect() { menu(); return true; }
    function onTap(evt) { menu(); return true; }
    function menu() {
        var a = Application.getApp();
        var m = new WatchUi.Menu2({:title=>"Recorder"});
        if (a.state == "idle") { m.addItem(new WatchUi.MenuItem("Start", null, :start, null)); }
        else {
            m.addItem(new WatchUi.MenuItem("Status", null, :status, null));
            if (a.state == "recording" || a.state == "waiting") {
                m.addItem(new WatchUi.MenuItem("Finish", null, :finish, null));
                m.addItem(new WatchUi.MenuItem("Cancel", null, :cancel, null));
            }
            if (a.state == "error") { m.addItem(new WatchUi.MenuItem("Retry send", null, :retry, null)); m.addItem(new WatchUi.MenuItem("Cancel draft", null, :cancel, null)); }
            if (a.state == "sent" || a.state == "cancelled" || a.state == "error") { m.addItem(new WatchUi.MenuItem("New recording", null, :fresh, null)); }
        }
        WatchUi.pushView(m, new RemoteMenu(), WatchUi.SLIDE_UP);
    }
}
class RemoteMenu extends WatchUi.Menu2InputDelegate {
    function initialize() { Menu2InputDelegate.initialize(); }
    function onSelect(item) {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        var a = Application.getApp();
        var id = item.getId();
        if (id == :fresh) { a.fresh(); }
        else {
            var op = "status";
            if (id == :start) { op = "start"; a.state = "waiting"; }
            if (id == :finish) { op = "finish"; }
            if (id == :cancel) { op = "cancel"; }
            if (id == :retry) { op = "retry"; }
            a.send(op);
        }
    }
}
