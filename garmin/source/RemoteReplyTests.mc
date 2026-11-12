using Toybox.Communications;
using Toybox.Test;
using Toybox.Time;

// A wire-decoded string is a distinct object even when its text matches storage.
function replyTestCopy(value) { return ("x" + value).substring(1, value.length() + 1); }
function replyTestMessage(session, state, revision) {
    var message = new Communications.PhoneAppMessage();
    message.data = {"v"=>1, "s"=>replyTestCopy(session), "r"=>revision,
        "state"=>replyTestCopy(state), "detail"=>"Local test"};
    return message;
}
(:test)
function wireSessionAndStatesApply(logger) {
    var app = new RemoteApp();
    app.session = Time.now().value().toString() + "_test";
    var copy = replyTestCopy(app.session);
    Test.assert(app.session.equals(copy));
    Test.assert(app.session != copy); // Reproduces the original identity-comparison bug.
    var states = ["waiting", "recording", "sending", "sent", "cancelled", "error"];
    for (var i = 0; i < states.size(); i += 1) {
        app.pending = "status";
        var message = replyTestMessage(app.session, states[i], i);
        message.data["c"] = replyTestCopy(app.session + "_status");
        app.received(message);
        Test.assert(app.rejects == 0);
        Test.assert(app.revision == i);
        Test.assert(app.state.equals(states[i]));
        Test.assert(app.pending == null);
    }
    return true;
}
(:test)
function wrongSessionStillRejected(logger) {
    var app = new RemoteApp();
    app.session = Time.now().value().toString() + "_test";
    app.received(replyTestMessage(app.session + "_other", "waiting", 1));
    Test.assert(app.rejects == 1);
    Test.assert(app.rejection.equals("D4 session"));
    Test.assert(app.revision == -1);
    return true;
}
(:test)
function wireHelloDoesNotClearPendingCommand(logger) {
    var app = new RemoteApp();
    app.session = "recording_session";
    app.pending = "start";
    app.received(replyTestMessage("hello", "hello", 0));
    Test.assert(app.rejects == 0);
    Test.assert(app.revision == -1);
    Test.assert(app.state.equals("idle"));
    Test.assert(app.pending.equals("start"));
    return true;
}
