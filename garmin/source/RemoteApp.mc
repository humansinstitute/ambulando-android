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
    var connection = "Not connected";
    var rx = 0;
    var rejects = 0;
    var rejection = "none";
    var lastAck = 0;
    var phoneBuild = 0;
    var tries = 0;
    var probeTries = 0;
    var commandAt = 0;
    var revision = -1;
    var pending = null;
    var timer = null;
    function initialize() { AppBase.initialize(); }
    function onStart(args) {
        session = Application.Storage.getValue("session");
        if (session != null) { state = "waiting"; detail = "Checking phone status"; }
        Communications.registerForPhoneAppMessages(method(:received));
        timer = new Timer.Timer();
        timer.start(method(:poll), 7000, true);
        probe();
    }
    function onStop(args) { if (timer != null) { timer.stop(); } }
    function probe() {
        if (probeTries >= 3) { return; }
        probeTries += 1;
        Communications.transmit({"v"=>1, "op"=>"hello", "b"=>38}, null, new RemoteListener());
    }
    function poll() as Void {
        if (lastAck == 0) { probe(); }
        if (pending != null && tries < 3) { transmitCommand(); }
        else if (pending != null && System.getTimer() - commandAt >= 21000) {
            connection = "Reply timed out; Status retries";
        }
        WatchUi.requestUpdate();
    }
    function getInitialView() { return [new RemoteView(), new RemoteDelegate()]; }
    function send(op) {
        if (session == null) {
            session = Time.now().value().toString() + "_" + Math.rand().toString();
            Application.Storage.setValue("session", session);
            revision = -1;
        }
        pending = op;
        tries = 0;
        commandAt = System.getTimer();
        transmitCommand();
    }
    function transmitCommand() {
        tries += 1;
        Communications.transmit({"v"=>1, "s"=>session, "c"=>session + "_" + pending, "op"=>pending, "b"=>38}, null, new RemoteListener());
        WatchUi.requestUpdate();
    }
    function reject(code) {
        rejects += 1; rejection = code; WatchUi.requestUpdate();
    }
    function received(message as Communications.PhoneAppMessage) as Void {
        rx += 1;
        var m = message.data;
        if (!(m instanceof Lang.Dictionary)) { reject("D1 shape"); return; }
        if (!m.hasKey("v") || m["v"] != 1) { reject("D2 version"); return; }
        if (!m.hasKey("s") || !(m["s"] instanceof Lang.String) || !m.hasKey("r") || !(m["r"] instanceof Lang.Number) || m["r"] < 0 ||
            !m.hasKey("state") || !(m["state"] instanceof Lang.String) || !m.hasKey("detail") || !(m["detail"] instanceof Lang.String)) { reject("D3 fields"); return; }
        var hello = m["s"] == "hello" && m["state"] == "hello" && m["r"] == 0;
        if (!hello && m["s"] != session) { reject("D4 session"); return; }
        if (!hello && m["r"] < revision) { reject("D5 revision"); return; }
        var st = m["state"];
        if (!hello && st != "waiting" && st != "recording" && st != "sending" && st != "sent" && st != "cancelled" && st != "error") { reject("D6 state"); return; }
        if (m.hasKey("n") && (!(m["n"] instanceof Lang.String) || m["n"].length() > 64 || !m.hasKey("b") || !(m["b"] instanceof Lang.Number))) { reject("D7 nonce"); return; }
        lastAck = System.getTimer();
        if (m.hasKey("b") && m["b"] instanceof Lang.Number) { phoneBuild = m["b"]; }
        connection = phoneBuild >= 38 ? "Phone replied / receipt sent" : "Legacy phone / no receipt";
        if (!hello) {
            revision = m["r"]; state = st; detail = m["detail"];
            // Only an application state reply clears a user command, never hello or transport success.
            if (pending == null || !m.hasKey("c") || m["c"] == session + "_" + pending) { pending = null; }
        }
        if (m.hasKey("n")) {
            // Repeat a receipt for a bounded phone resend: the previous uplink may have been lost.
            // Receipts never elicit phone replies, so this cannot create an ACK loop.
            Communications.transmit({"v"=>1, "op"=>"receipt", "s"=>m["s"], "r"=>m["r"], "n"=>m["n"], "b"=>38}, null, new ReceiptListener());
        }
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
        app.connection = "Not connected / TX failed";
        WatchUi.requestUpdate();
    }
}
class ReceiptListener extends Communications.ConnectionListener {
    function initialize() { ConnectionListener.initialize(); }
    function onComplete() { }
    function onError() { Application.getApp().connection = "Phone replied / receipt TX failed"; WatchUi.requestUpdate(); }
}
class RemoteView extends WatchUi.View {
    function initialize() { View.initialize(); }
    function onUpdate(dc) {
        var a = Application.getApp();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK); dc.clear();
        var x = dc.getWidth()/2;
        dc.drawText(x, 65, Graphics.FONT_SMALL, "Ambulando Remote", Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(x, 90, Graphics.FONT_XTINY, a.connection, Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(x, 120, Graphics.FONT_MEDIUM, a.state, Graphics.TEXT_JUSTIFY_CENTER);
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
        m.addItem(new WatchUi.MenuItem("Probe phone", null, :probe, null));
        m.addItem(new WatchUi.MenuItem("Diagnostics", null, :diagnostics, null));
        WatchUi.pushView(m, new RemoteMenu(), WatchUi.SLIDE_UP);
    }
}
class RemoteMenu extends WatchUi.Menu2InputDelegate {
    function initialize() { Menu2InputDelegate.initialize(); }
    function onSelect(item) {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        var a = Application.getApp();
        var id = item.getId();
        if (id == :probe) { a.probeTries = 0; a.probe(); }
        else if (id == :diagnostics) {
            var age = a.lastAck == 0 ? "never" : ((System.getTimer() - a.lastAck)/1000).toString() + "s";
            var menu = new WatchUi.Menu2({:title=>"Connection diagnostics"});
            menu.addItem(new WatchUi.MenuItem("Watch 38 / Phone " + a.phoneBuild, null, :none, null));
            menu.addItem(new WatchUi.MenuItem("RX " + a.rx + " / rejected " + a.rejects, null, :none, null));
            menu.addItem(new WatchUi.MenuItem(a.rejection, "Last reply " + age + " / r " + a.revision, :none, null));
            menu.addItem(new WatchUi.MenuItem("Phone detail", a.detail, :none, null));
            WatchUi.pushView(menu, new DiagnosticsMenu(), WatchUi.SLIDE_UP);
        }
        else if (id == :fresh) { a.fresh(); }
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

class DiagnosticsMenu extends WatchUi.Menu2InputDelegate {
    function initialize() { Menu2InputDelegate.initialize(); }
    function onSelect(item) { WatchUi.popView(WatchUi.SLIDE_DOWN); }
}
