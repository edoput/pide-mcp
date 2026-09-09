(* Fresh-process regression fixture for MCP_Cancellation.drain.
   It deliberately closes admission, so it must not run while constructing
   the MCP-Tools-Tests heap that later backs a live bridge session. *)

fun assert_test message test = if test then () else error message;

val _ =
  let
    val group = Future.new_group NONE;
    val _ = MCP_Cancellation.register "drain-route" group;
    val _ = assert_test "initial drain did not acknowledge no active calls"
      (MCP_Cancellation.drain "drain-one" = []);
    val _ = assert_test "finished call was not marked non-cancelled"
      (MCP_Cancellation.finish "drain-route" = SOME false);
    val _ = assert_test "finished call disappeared before cleanup"
      (MCP_Cancellation.member "drain-route");
    val _ = assert_test "second drain did not acknowledge no active calls"
      (MCP_Cancellation.drain "drain-two" = []);
    val _ = assert_test "cleanup did not retain drain acknowledgements"
      (MCP_Cancellation.cleanup "drain-route" = ["drain-one", "drain-two"]);
    val _ = assert_test "cleanup retained a completed call"
      (not (MCP_Cancellation.member "drain-route"));
    val _ = assert_test "empty drain did not acknowledge its owner"
      (MCP_Cancellation.drain "drain-empty" = ["drain-empty"]);
    val rejected = Exn.capture_body
      (fn () => MCP_Cancellation.register "post-drain-route" (Future.new_group NONE));
    val _ = assert_test "drain left bridge admission open" (Exn.is_exn rejected);
    val _ = assert_test "rejected post-drain call was retained"
      (not (MCP_Cancellation.member "post-drain-route"));
  in () end;
