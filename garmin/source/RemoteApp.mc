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
    var timedOut = false;
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
        restoreSession();
    }
    // Status recovers a retained session; it never starts a new recording.
    function restoreSession() { if (session != null) { send("status"); } }
    function onStop(args) { if (timer != null) { timer.stop(); } }
    function probe() {
        if (probeTries >= 3) { return; }
        probeTries += 1;
        Communications.transmit({"v"=>1, "op"=>"hello", "b"=>40}, null, new RemoteListener());
    }
    function poll() as Void {
        if (lastAck == 0) { probe(); }
        if (pending != null && tries < 3) { transmitCommand(); }
        else if (pending != null && System.getTimer() - commandAt >= 21000) {
            timedOut = true;
            connection = "Phone reply timed out; retry";
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
        timedOut = false;
        tries = 0;
        commandAt = System.getTimer();
        transmitCommand();
    }
    function transmitCommand() {
        tries += 1;
        Communications.transmit({"v"=>1, "s"=>session, "c"=>session + "_" + pending, "op"=>pending, "b"=>40}, null, new RemoteListener());
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
        var hello = m["s"].equals("hello") && m["state"].equals("hello") && m["r"] == 0;
        if (!hello && !m["s"].equals(session)) { reject("D4 session"); return; }
        if (!hello && m["r"] < revision) { reject("D5 revision"); return; }
        var st = m["state"];
        if (!hello && !st.equals("waiting") && !st.equals("recording") && !st.equals("sending") && !st.equals("sent") && !st.equals("cancelled") && !st.equals("error")) { reject("D6 state"); return; }
        if (m.hasKey("n") && (!(m["n"] instanceof Lang.String) || m["n"].length() > 64 || !m.hasKey("b") || !(m["b"] instanceof Lang.Number))) { reject("D7 nonce"); return; }
        lastAck = System.getTimer();
        if (m.hasKey("b") && m["b"] instanceof Lang.Number) { phoneBuild = m["b"]; }
        connection = phoneBuild >= 38 ? "Phone replied / receipt sent" : "Legacy phone / no receipt";
        if (!hello) {
            revision = m["r"]; state = st; detail = m["detail"];
            // Only an application state reply clears a user command, never hello or transport success.
            if (pending == null || !m.hasKey("c") || (session + "_" + pending).equals(m["c"])) { pending = null; timedOut = false; }
        }
        if (m.hasKey("n")) {
            // Repeat a receipt for a bounded phone resend: the previous uplink may have been lost.
            // Receipts never elicit phone replies, so this cannot create an ACK loop.
            Communications.transmit({"v"=>1, "op"=>"receipt", "s"=>m["s"], "r"=>m["r"], "n"=>m["n"], "b"=>40}, null, new ReceiptListener());
        }
        WatchUi.requestUpdate();
    }
    function fresh() {
        session = null; revision = -1; state = "idle"; pending = null; timedOut = false;
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
// The same geometry drives drawing and touch dispatch, including the clear gap
// between Stop and Discard. Protocol operations remain start / finish / cancel.
function remoteUiState(app) { return app.timedOut ? "offline" : app.state; }
function remotePrimary(state) {
    if (state.equals("offline")) { return :retryControl; }
    if (state.equals("idle") || state.equals("sent") || state.equals("cancelled")) { return :record; }
    if (state.equals("recording")) { return :finish; }
    if (state.equals("error")) { return :options; }
    return :none;
}
function remoteCanDiscard(state, pending) {
    return !"finish".equals(pending) && !"cancel".equals(pending) &&
        (state.equals("waiting") || state.equals("recording") || state.equals("error"));
}
function remoteMainX(state, width) {
    return state.equals("recording") || state.equals("waiting") ? width * 0.45 : width * 0.5;
}
function remoteInside(x, y, cx, cy, radius) {
    var dx = x - cx; var dy = y - cy;
    return dx * dx + dy * dy <= radius * radius;
}
function remoteTouch(state, pending, x, y, width, height) {
    if (remoteInside(x, y, width * 0.5, height * 0.82, width * 0.065)) { return :options; }
    if (remoteCanDiscard(state, pending) && remoteInside(x, y, width * 0.85, height * 0.51, width * 0.065)) { return :cancel; }
    if (remoteInside(x, y, remoteMainX(state, width), height * 0.51, width * 0.19)) {
        // A pending Finish must never become a second Finish or a late Discard.
        if (!state.equals("offline") && ("finish".equals(pending) || "cancel".equals(pending))) { return :none; }
        return remotePrimary(state);
    }
    return :none;
}
function remotePerform(app, action) {
    if (action == :retryControl) { app.send(app.pending == null ? "status" : app.pending); return; }
    if (action == :none || action == :options || "finish".equals(app.pending) || "cancel".equals(app.pending)) { return; }
    if (action == :record) {
        if (!app.state.equals("idle")) { app.fresh(); }
        app.state = "waiting"; app.send("start");
    } else if (action == :finish) { app.send("finish"); }
    else if (action == :cancel) { app.send("cancel"); }
}
class RemoteView extends WatchUi.View {
    var animation = null;
    var frame = 0;
    function initialize() { View.initialize(); }
    function onShow() {
        animation = new Timer.Timer();
        animation.start(method(:animate), 450, true);
    }
    function onHide() { if (animation != null) { animation.stop(); animation = null; } }
    function animate() as Void {
        if (Application.getApp().state.equals("recording")) { frame = (frame + 1) % 4; WatchUi.requestUpdate(); }
    }
    function gear(dc, x, y, radius) {
        dc.setColor(0xAAAAAA, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(2);
        dc.drawCircle(x, y, radius * 0.55);
        dc.drawCircle(x, y, radius * 0.2);
        for (var i = 0; i < 8; i += 1) {
            var angle = i * Math.PI / 4;
            dc.drawLine(x + Math.cos(angle) * radius * 0.55, y + Math.sin(angle) * radius * 0.55,
                x + Math.cos(angle) * radius * 0.84, y + Math.sin(angle) * radius * 0.84);
        }
        dc.setPenWidth(1);
    }
    function trash(dc, x, y) {
        dc.setColor(0xBBBBBB, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(2);
        dc.drawRectangle(x - 7, y - 6, 14, 17);
        dc.drawLine(x - 10, y - 10, x + 10, y - 10);
        dc.drawLine(x - 4, y - 14, x + 4, y - 14);
        dc.drawLine(x - 3, y - 2, x - 3, y + 7);
        dc.drawLine(x + 3, y - 2, x + 3, y + 7);
        dc.setPenWidth(1);
    }
    function onUpdate(dc) {
        var a = Application.getApp();
        var w = dc.getWidth(); var h = dc.getHeight(); var x = w / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK); dc.clear();
        dc.drawText(x, h * 0.12, Graphics.FONT_SMALL, "Ambulando Voice", Graphics.TEXT_JUSTIFY_CENTER);
        var uiState = remoteUiState(a);
        var active = a.state.equals("recording");
        var finishing = "finish".equals(a.pending);
        var cancelling = "cancel".equals(a.pending);
        var caption = "Bluetooth headset";
        if (active) { caption = "Recording"; }
        if (a.state.equals("waiting")) { caption = "Connecting to phone"; }
        if (a.state.equals("sending") || finishing) { caption = "Sending to conversation"; }
        if (a.state.equals("sent")) { caption = "Voice message sent"; }
        if (a.state.equals("cancelled")) { caption = "Recording discarded"; }
        if (a.state.equals("error")) { caption = "Check recording options"; }
        if (cancelling) { caption = "Discarding recording"; }
        if (a.timedOut) { caption = "Phone reply timed out"; }
        dc.setColor(active ? 0xFF565C : 0xAAAAAA, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, h * 0.23, Graphics.FONT_XTINY, caption, Graphics.TEXT_JUSTIFY_CENTER);
        if (active && !finishing && !cancelling && !a.timedOut) {
            for (var i = 0; i < 4; i += 1) { dc.fillCircle(x - 18 + i * 12, h * 0.31, i == frame ? 3 : 2); }
        }
        var mainX = remoteMainX(uiState, w); var mainY = h * 0.51; var radius = w * 0.19;
        var label = "Record";
        if (a.state.equals("waiting")) { label = "Waiting"; }
        if (active) { label = "Stop"; }
        if (a.state.equals("sending") || finishing) { label = "Sending"; }
        if (a.state.equals("error")) { label = "Options"; }
        if (cancelling) { label = "Discarding"; }
        if (a.timedOut) { label = "Retry"; }
        var enabled = a.timedOut || (remotePrimary(a.state) != :none && !finishing && !cancelling);
        dc.setColor(enabled ? 0xDF2838 : 0x303035, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(mainX, mainY, radius);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(mainX, mainY - dc.getFontHeight(Graphics.FONT_MEDIUM) / 2,
            Graphics.FONT_MEDIUM, label, Graphics.TEXT_JUSTIFY_CENTER);
        if (remoteCanDiscard(uiState, a.pending)) {
            var discardX = w * 0.85;
            dc.setColor(0x242428, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(discardX, mainY, w * 0.065);
            trash(dc, discardX, mainY);
        }
        gear(dc, x, h * 0.82, w * 0.06);
    }
}
class RemoteDelegate extends WatchUi.BehaviorDelegate {
    function initialize() { BehaviorDelegate.initialize(); }
    function onSelect() { act(remotePrimary(remoteUiState(Application.getApp()))); return true; }
    function onMenu() { menu(); return true; }
    function onTap(evt) {
        var a = Application.getApp();
        var point = evt.getCoordinates();
        var settings = System.getDeviceSettings();
        act(remoteTouch(remoteUiState(a), a.pending, point[0], point[1], settings.screenWidth, settings.screenHeight));
        return true;
    }
    function act(action) {
        if (action == :options) { menu(); return; }
        remotePerform(Application.getApp(), action);
    }
    function menu() {
        var a = Application.getApp();
        var m = new WatchUi.Menu2({:title=>"Voice options"});
        m.addItem(new WatchUi.MenuItem("Check status", null, :status, null));
        if (a.state.equals("error")) {
            m.addItem(new WatchUi.MenuItem("Retry send", "Keeps original destination", :retry, null));
            m.addItem(new WatchUi.MenuItem("Discard recording", null, :cancel, null));
        }
        if (a.state.equals("sent") || a.state.equals("cancelled")) {
            m.addItem(new WatchUi.MenuItem("New recording", null, :fresh, null));
        }
        m.addItem(new WatchUi.MenuItem("Check phone setup", "Enable watch control on phone", :probe, null));
        m.addItem(new WatchUi.MenuItem("Diagnostics", null, :diagnostics, null));
        WatchUi.pushView(m, new RemoteMenu(), WatchUi.SLIDE_UP);
    }
}
class RemoteMenu extends WatchUi.Menu2InputDelegate {
    function initialize() { Menu2InputDelegate.initialize(); }
    function onSelect(item) {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
        var a = Application.getApp(); var id = item.getId();
        if (id == :probe) { a.probeTries = 0; a.probe(); }
        else if (id == :diagnostics) {
            var age = a.lastAck == 0 ? "never" : ((System.getTimer() - a.lastAck)/1000).toString() + "s";
            var menu = new WatchUi.Menu2({:title=>"Voice diagnostics"});
            menu.addItem(new WatchUi.MenuItem("Watch 40 / Phone " + a.phoneBuild, null, :none, null));
            menu.addItem(new WatchUi.MenuItem("RX " + a.rx + " / rejected " + a.rejects, null, :none, null));
            menu.addItem(new WatchUi.MenuItem(a.rejection, "Last reply " + age + " / r " + a.revision, :none, null));
            menu.addItem(new WatchUi.MenuItem("Phone detail", a.detail, :none, null));
            WatchUi.pushView(menu, new DiagnosticsMenu(), WatchUi.SLIDE_UP);
        } else if (id == :fresh) { a.fresh(); }
        else {
            if ("finish".equals(a.pending) || "cancel".equals(a.pending)) { return; }
            var op = "status";
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
