/*  Title:      mcp/src/mcp_connection_runtime.scala

Assembles the application, transport, connection kernel, and schedulers for one
logical MCP client. Runs the one-shot serve loop and coordinates connection
shutdown, transport resource release, and backend teardown.
*/

package isabelle.mcp

import isabelle.{Exn, Path, Progress, Time, error, quote}
import isabelle.mcp.application.{McpApplication, McpOutputPolicy}
import isabelle.mcp.connection._
import isabelle.mcp.control.{DeadlineScheduler, ScheduledDeadlineScheduler}
import isabelle.mcp.transport.{DataPlane, StdioDataPlane}


private[mcp] final class ConnectionRuntime private (
  val connection: ConnectionKernel,
  progress: Progress,
  onShutdown: () => Unit
) {
  private var serving = false

  private def claimConnection(): Unit = synchronized {
    if (serving) error("MCP connection runtime serves exactly one logical client")
    serving = true
  }

  def serve(): Unit = {
    claimConnection()
    var failure: Option[Throwable] = None
    try {
      var finished = false
      while (!finished && connection.phase != ConnectionLifecycle.Closing &&
          connection.phase != ConnectionLifecycle.Closed) {
        connection.receiveAndExecute() match {
          case None => finished = true
          case Some(_) => ()
        }
      }
    }
    catch {
      case exn: Throwable if !Exn.is_interrupt(exn) =>
        progress.echo_error_message("mcp_server: connection failed: " + Exn.message(exn))
        failure = Some(exn)
    }
    finally {
      /* The kernel owns the one terminal deadline and response-write barrier.
         Backend teardown is deliberately sequenced afterward. */
      val policy = connection.policy
      val shutdownDrain =
        Time.seconds(ConnectionPolicy.ShutdownDrain.seconds(policy.timing.shutdownDrain))
      val drain = connection.drainAndClose()
      if (!drain.drained)
        progress.echo_warning(
          "mcp_server: shutting down with requests still in flight " +
          "(waited " + shutdownDrain.message + "; cancelled " + drain.cancelled.length +
          "; raise mcp_shutdown_drain to wait longer)")
      progress.echo("Shutting down ...")
      try connection.dataPlane.close()
      finally onShutdown()
    }
    failure.foreach(exn => throw exn)
  }
}


private[mcp] object ConnectionRuntime {
  type InvariantPolicyFactory = (() => Unit) => RequestRegistry.InvariantViolationPolicy

  def isabelleApplication(
    readiness: () => McpApplication.Readiness,
    sessionName: String,
    sessionDirs: List[Path],
    theory: String,
    outputPolicy: McpOutputPolicy
  ): McpApplication =
    McpApplication.isabelle(readiness, sessionName, sessionDirs, theory, outputPolicy)

  /* This is the only production selection of concrete protocol, scheduler,
     deadline, application, and invariant-policy adapters. */
  def stdio(
    readiness: () => McpApplication.Readiness,
    progress: Progress,
    sessionName: String,
    sessionDirs: List[Path],
    theory: String,
    installChangedSender: (String => Unit) => Unit,
    onShutdown: () => Unit,
    policy: ConnectionPolicy,
    serverInfo: ConnectionKernel.ServerInfo,
    outputPolicy: McpOutputPolicy,
    dataPlaneResources: StdioDataPlane.Resources = StdioDataPlane.Resources.default
  ): ConnectionRuntime =
    production(readiness, StdioDataPlane.standard(policy.input, dataPlaneResources),
      progress, sessionName, sessionDirs, theory, installChangedSender, onShutdown,
      policy, serverInfo, outputPolicy)

  def streams(
    readiness: () => McpApplication.Readiness,
    input: java.io.InputStream,
    output: java.io.OutputStream,
    progress: Progress,
    sessionName: String,
    sessionDirs: List[Path],
    theory: String,
    installChangedSender: (String => Unit) => Unit,
    onShutdown: () => Unit,
    policy: ConnectionPolicy,
    serverInfo: ConnectionKernel.ServerInfo,
    outputPolicy: McpOutputPolicy,
    dataPlaneResources: StdioDataPlane.Resources = StdioDataPlane.Resources.default
  ): ConnectionRuntime =
    production(readiness, StdioDataPlane.open(input, output, policy.input, dataPlaneResources),
      progress, sessionName, sessionDirs, theory, installChangedSender, onShutdown,
      policy, serverInfo, outputPolicy)

  private def production(
    readiness: () => McpApplication.Readiness,
    dataPlane: DataPlane,
    progress: Progress,
    sessionName: String,
    sessionDirs: List[Path],
    theory: String,
    installChangedSender: (String => Unit) => Unit,
    onShutdown: () => Unit,
    policy: ConnectionPolicy,
    serverInfo: ConnectionKernel.ServerInfo,
    outputPolicy: McpOutputPolicy
  ): ConnectionRuntime = {
    val application = isabelleApplication(readiness, sessionName, sessionDirs, theory, outputPolicy)
    val rules = new Mcp2025RevisionRules
    val scheduler = new BoundedConcurrentScheduler(
      ConnectionPolicy.MaxInFlight.value(policy.admission.maxInFlight), "mcp-worker")
    val deadlines = new ScheduledDeadlineScheduler("mcp-deadline")
    val invariantPolicy: InvariantPolicyFactory = close =>
      new RequestRegistry.InvariantViolationPolicy.LogAndClose(
        close = close,
        log = message => progress.echo_error_message("mcp_server: " + message))

    compose(policy, dataPlane, rules, scheduler, deadlines, application,
      invariantPolicy, progress, installChangedSender, onShutdown, serverInfo)
  }

  /* Package-visible test composition uses the same root with deterministic
     ports.  Callers supply ports as already-constructed narrow interfaces;
     the runtime does not branch on their concrete implementations. */
  def compose(
    policy: ConnectionPolicy,
    dataPlane: DataPlane,
    revisionRules: RevisionRules,
    scheduler: RequestScheduler,
    deadlineScheduler: DeadlineScheduler,
    application: McpApplication,
    invariantPolicy: InvariantPolicyFactory,
    progress: Progress,
    installChangedSender: (String => Unit) => Unit,
    onShutdown: () => Unit,
    serverInfo: ConnectionKernel.ServerInfo
  ): ConnectionRuntime = {
    lazy val kernel: ConnectionKernel = {
      val registry = new RequestRegistry(invariantPolicy(() => kernel.close()))
      ConnectionKernel(
        policy = policy,
        dataPlane = dataPlane,
        revisionRules = revisionRules,
        scheduler = scheduler,
        deadlineScheduler = deadlineScheduler,
        registry = registry,
        application = application,
        serverInfo = serverInfo)
    }
    val connection = kernel

    installChangedSender { what =>
      ConnectionKernel.ListChanged.fromBackend(what) match {
        case Some(change) => connection.listChanged(change)
        case None =>
          progress.echo_warning(
            "mcp_server: ignoring unknown list_changed kind " + quote(what))
      }
    }

    new ConnectionRuntime(connection, progress, onShutdown)
  }
}
