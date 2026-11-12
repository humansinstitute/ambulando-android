using Toybox.Test;

// Exercise actual touch -> command dispatch without starting communications,
// recording audio, touching storage or requiring a companion phone.
class RemoteControlTestApp extends RemoteApp {
    function initialize() { RemoteApp.initialize(); }
    var sentOp = null;
    var sends = 0;
    var freshened = false;
    function send(op) { sentOp = op; pending = op; sends += 1; }
    function fresh() { freshened = true; session = null; revision = -1; state = "idle"; pending = null; }
}
(:test)
function recordStopDiscardTouchContract(logger) {
    var app = new RemoteControlTestApp();
    var w = 390; var h = 390;
    remotePerform(app, remoteTouch(app.state, app.pending, 195, 199, w, h));
    Test.assert(app.sentOp.equals("start"));
    Test.assert(app.state.equals("waiting"));
    // A phone reply, not the button, establishes recording.
    app.state = replyTestCopy("recording"); app.pending = null;
    Test.assert(remoteTouch(app.state, app.pending, 300, 199, w, h) == :none);
    Test.assert(remoteTouch(app.state, app.pending, 332, 199, w, h) == :cancel);
    remotePerform(app, remoteTouch(app.state, app.pending, 175, 199, w, h));
    Test.assert(app.sentOp.equals("finish"));
    Test.assert(app.sends == 2);
    // Stop is immediately one-shot, including before a delayed phone reply.
    remotePerform(app, remoteTouch(app.state, app.pending, 175, 199, w, h));
    remotePerform(app, remoteTouch(app.state, app.pending, 332, 199, w, h));
    Test.assert(app.sends == 2);
    app.state = replyTestCopy("sending"); app.pending = null;
    Test.assert(remoteTouch(app.state, app.pending, 332, 199, w, h) == :none);
    Test.assert(remoteTouch(app.state, app.pending, 195, 199, w, h) == :none);
    return true;
}
(:test)
function discardAndNewRecordingKeepProtocol(logger) {
    var app = new RemoteControlTestApp();
    app.state = replyTestCopy("recording");
    remotePerform(app, remoteTouch(app.state, app.pending, 332, 199, 390, 390));
    Test.assert(app.sentOp.equals("cancel"));
    Test.assert(!remoteCanDiscard(app.state, app.pending));
    app.state = replyTestCopy("sent"); app.pending = null;
    remotePerform(app, remoteTouch(app.state, app.pending, 195, 199, 390, 390));
    Test.assert(app.freshened);
    Test.assert(app.sentOp.equals("start"));
    Test.assert(app.state.equals("waiting"));
    return true;
}
(:test)
function recoveryAndSettingsDoNotStartRecording(logger) {
    var app = new RemoteControlTestApp();
    app.state = replyTestCopy("error");
    Test.assert(remoteTouch(app.state, app.pending, 195, 199, 390, 390) == :options);
    Test.assert(remoteTouch("idle", null, 195, 320, 390, 390) == :options);
    Test.assert(remoteTouch("idle", null, 10, 10, 390, 390) == :none);
    remotePerform(app, :options);
    Test.assert(app.sends == 0);
    // In every state the Stop/Record disk and bin target have a non-touchable gap.
    Test.assert(!remoteInside(300, 199, remoteMainX("recording", 390), 390 * 0.51, 390 * 0.19));
    Test.assert(!remoteInside(300, 199, 390 * 0.85, 390 * 0.51, 390 * 0.065));
    return true;
}
(:test)
function retainedSessionRecoversWithoutNewCapture(logger) {
    var app = new RemoteControlTestApp();
    app.session = "retained_session";
    app.state = "waiting";
    app.restoreSession();
    Test.assert(app.sentOp.equals("status"));
    Test.assert(!app.freshened);
    Test.assert(app.session.equals("retained_session"));
    var states = ["recording", "sending", "error", "sent", "cancelled"];
    for (var i = 0; i < states.size(); i += 1) {
        app.received(replyTestMessage(app.session, states[i], i));
        Test.assert(app.state.equals(states[i]));
        Test.assert(app.sends == 1);
    }
    var idle = new RemoteControlTestApp();
    idle.restoreSession();
    Test.assert(idle.sends == 0);
    Test.assert(remotePrimary(idle.state) == :record);
    return true;
}
(:test)
function timedOutFinishRetriesFinishWithoutDiscard(logger) {
    var app = new RemoteControlTestApp();
    app.session = "retained_session"; app.state = "recording";
    app.pending = "finish"; app.timedOut = true;
    var screen = remoteUiState(app);
    Test.assert(screen.equals("offline"));
    Test.assert(remoteTouch(screen, app.pending, 332, 199, 390, 390) == :none);
    remotePerform(app, remoteTouch(screen, app.pending, 195, 199, 390, 390));
    Test.assert(app.sentOp.equals("finish"));
    Test.assert(!app.freshened);
    return true;
}
