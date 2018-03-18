package asyncpg;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class Connection implements AutoCloseable {
  public static final Logger log = Logger.getLogger(Connection.class.getName());

  public static CompletableFuture<Startup> init(Config config) {
    log.log(Level.FINE, "Connecting to {0}", config.hostname);
    return config.connector.get().thenApply(io -> new Startup(config, io));
  }

  public static CompletableFuture<QueryReadyConnection.AutoCommit> authed(Config config) {
    return init(config).thenCompose(Startup::auth);
  }

  protected final Context ctx;

  protected Connection(Context ctx) { this.ctx = ctx; }

  @Override
  public void close() { terminate(); }

  protected CompletableFuture<Void> sendTerminate() {
    ctx.buf.clear();
    ctx.writeByte((byte) 'X').writeLengthIntBegin().writeLengthIntEnd();
    ctx.buf.flip();
    return writeFrontendMessage();
  }

  public CompletableFuture<Void> terminate() { return sendTerminate().whenComplete((__, ___) -> ctx.io.close()); }

  // Good for use on handle
  protected <@Nullable T> CompletableFuture<T> terminate(T ret, @Nullable Throwable ex) {
    return terminate().handle((__, termEx) -> {
      if (ex != null && termEx != null) log.log(Level.WARNING, "Failed terminating", termEx);
      Throwable rethrow = ex == null ? termEx : ex;
      if (rethrow != null) {
        if (ex instanceof RuntimeException) throw (RuntimeException) ex;
        throw new RuntimeException(ex);
      }
      return ret;
    });
  }

  // Good for use on handle
  public <T> CompletableFuture<T> terminated(@Nullable CompletableFuture<T> ret, @Nullable Throwable ex) {
    if (ret != null) return terminated(ret);
    return terminate(ret, ex).thenCompose(Function.identity());
  }

  public <T> CompletableFuture<T> terminated(CompletableFuture<T> fut) {
    return fut.handle(this::terminate).thenCompose(Function.identity());
  }

  public Subscribable<Subscribable.Notice> notices() { return ctx.noticeSubscribable; }
  public Subscribable<Subscribable.Notification> notifications() { return ctx.notificationSubscribable; }
  public Subscribable<Subscribable.ParameterStatus> parameterStatuses() { return ctx.parameterStatusSubscribable; }

  public Map<String, String> getRuntimeParameters() { return ctx.runtimeParameters; }

  protected CompletableFuture<Void> readBackendMessage() {
    return readBackendMessage(ctx.config.defaultTimeout, ctx.config.defaultTimeoutUnit);
  }

  // The resulting buffer is only valid until next read/write
  protected CompletableFuture<Void> readBackendMessage(long timeout, TimeUnit timeoutUnit) {
    ctx.buf.clear();
    // We need 5 bytes to get the type and size
    ctx.writeEnsureCapacity(5).limit(5);
    return ctx.io.readFull(ctx.buf, timeout, timeoutUnit).thenCompose(__ -> {
      // Now that we have the size, make sure we have enough capacity to fulfill it and reset the limit
      int messageSize = ctx.buf.getInt(1);
      if (log.isLoggable(Level.FINER))
        log.log(Level.FINER, "{0} Read message header of type {1} with size {2}",
            new Object[] { ctx, (char) ctx.buf.get(0), messageSize });
      ctx.writeEnsureCapacity(messageSize).limit(1 + messageSize);
      // Fill it with the rest of the message and flip it for use
      return ctx.io.readFull(ctx.buf, timeout, timeoutUnit).thenRun(() -> ctx.buf.flip());
    });
  }

  protected CompletableFuture<Void> writeFrontendMessage() {
    return writeFrontendMessage(ctx.config.defaultTimeout, ctx.config.defaultTimeoutUnit);
  }

  protected CompletableFuture<Void> writeFrontendMessage(long timeout, TimeUnit timeoutUnit) {
    return ctx.io.writeFull(ctx.buf, timeout, timeoutUnit).thenRun(() -> ctx.buf.clear());
  }

  // Returns null if not handled here
  protected @Nullable CompletableFuture<Void> handleGeneralResponse() {
    char typ = (char) ctx.buf.get(0);
    switch (typ) {
      // Notification Response
      case 'A':
        ctx.buf.position(5);
        return notifications().publish(new Subscribable.Notification(ctx.buf.getInt(), ctx.bufReadString(), ctx.bufReadString()));
      // ErrorMessage
      case 'E':
      // NoticeResponse
      case 'N':
        // Throw error or handle notice after skipping the length
        ctx.buf.position(5);
        Map<Byte, String> fields = new HashMap<>();
        while (true) {
          byte b = ctx.buf.get();
          if (b == 0) break;
          fields.put(b, ctx.bufReadString());
        }
        Subscribable.Notice notice = new Subscribable.Notice(fields);
        // Throw on error, send to subscriber on normal notice
        if (typ == 'E') throw new DriverException.FromServer(notice);
        return notices().publish(notice);
      // ParameterStatus
      case 'S':
        // Handle status after skipping length
        ctx.buf.position(5);
        Subscribable.ParameterStatus status =
            new Subscribable.ParameterStatus(ctx.bufReadString(), ctx.bufReadString());
        ctx.runtimeParameters.put(status.parameter, status.value);
        return parameterStatuses().publish(status);
      default: return null;
    }
  }

  protected CompletableFuture<Void> readNonGeneralBackendMessage() {
    return readBackendMessage().thenCompose(__ -> {
      CompletableFuture<Void> generalHandled = handleGeneralResponse();
      if (generalHandled == null) return CompletableFuture.completedFuture(null);
      return generalHandled.thenCompose(___ -> readNonGeneralBackendMessage());
    });
  }

  // Assumes buffer is at the status position
  protected void updateReadyForQueryTransactionStatus() {
    char status = (char) ctx.buf.get();
    switch (status) {
      case 'I':
        ctx.lastTransactionStatus = QueryReadyConnection.TransactionStatus.IDLE;
        break;
      case 'T':
        ctx.lastTransactionStatus = QueryReadyConnection.TransactionStatus.IN_TRANSACTION;
        break;
      case 'E':
        ctx.lastTransactionStatus = QueryReadyConnection.TransactionStatus.FAILED_TRANSACTION;
        break;
      default: throw new IllegalArgumentException("Unrecognized transaction status: " + status);
    }
  }

  public static class Context extends BufWriter.Simple<Context> {
    public final Config config;
    // Note, this is replaced when going SSL
    public ConnectionIo io;
    protected final Subscribable<Subscribable.Notice> noticeSubscribable = new Subscribable<>();
    protected final Subscribable<Subscribable.Notification> notificationSubscribable = new Subscribable<>();
    protected final Subscribable<Subscribable.ParameterStatus> parameterStatusSubscribable = new Subscribable<>();
    protected @Nullable Integer processId;
    protected @Nullable Integer secretKey;
    protected final Map<String, String> runtimeParameters = new HashMap<>();
    protected QueryReadyConnection.@Nullable TransactionStatus lastTransactionStatus;

    @SuppressWarnings("initialization")
    public Context(Config config, ConnectionIo io) {
      super(config.directBuffer, config.bufferStep);
      this.config = config;
      this.io = io;
      // Add the notice log if we are logging em
      if (config.logNotices) noticeSubscribable.subscribe(n -> {
        n.log(log, this);
        return CompletableFuture.completedFuture(null);
      });
    }

    public String bufReadString() {
      int indexOfZero = buf.position();
      while (buf.get(indexOfZero) != 0) indexOfZero++;
      // Temporarily put the limit for decoding
      int prevLimit = buf.limit();
      buf.limit(indexOfZero);
      String ret = Util.stringFromByteBuffer(buf);
      buf.limit(prevLimit);
      // Read the zero
      buf.position(buf.position() + 1);
      return ret;
    }

    // Mostly just for the benefit of loggers
    @Override
    public String toString() {
      return "[" + config.username + "@" + config.hostname + ":" + config.port + "->" +
          io.getLocalPort() + "/" + config.database + "]";
    }
  }

  public static class Startup extends Connection {
    protected Startup(Config config, ConnectionIo io) {
      super(new Context(config, io));
    }

    public CompletableFuture<QueryReadyConnection.AutoCommit> auth() {
      CompletableFuture<?> sslComplete;
      if (ctx.config.ssl == null || ctx.config.ssl) sslComplete = startSsl(ctx.config.ssl != null);
      else sslComplete = CompletableFuture.completedFuture(null);
      return sslComplete.thenCompose(__ -> doAuth());
    }

    protected CompletableFuture<Void> startSsl(boolean required) {
      log.log(Level.FINE, "{0} Starting SSL", ctx);
      // Send SSLRequest
      ctx.buf.clear();
      ctx.writeInt(8).writeInt(80877103);
      ctx.buf.flip();
      return writeFrontendMessage().thenCompose(__ -> {
        // Just a single byte of 'S' or 'N' (yes or no)
        ctx.buf.clear().limit(1);
        return ctx.io.readFull(ctx.buf, ctx.config.defaultTimeout, ctx.config.defaultTimeoutUnit).thenCompose(___ -> {
          char response = (char) ctx.buf.get(0);
          if (response == 'N') {
            if (required) throw new DriverException.ServerSslNotSupported();
            log.log(Level.INFO, "{0} SSL not supported by server, continuing unencrypted", ctx);
            return CompletableFuture.completedFuture(null);
          }
          if (response != 'S') throw new IllegalArgumentException("Unrecognized SSL response char: " + response);
          return ctx.config.sslWrapper.apply(ctx.io).thenApply(sslIo -> {
            ctx.io = sslIo;
            return null;
          });
        });
      });
    }

    protected CompletableFuture<QueryReadyConnection.AutoCommit> doAuth() {
      log.log(Level.FINE, "{0} Authenticating", ctx);
      // Send startup message
      ctx.buf.clear();
      ctx.writeLengthIntBegin().writeInt(ctx.config.protocolVersion).writeCString("user").
          writeCString(ctx.config.username);
      if (ctx.config.database != null) ctx.writeCString("database").writeCString(ctx.config.database);
      if (ctx.config.additionalStartupParams != null)
        ctx.config.additionalStartupParams.forEach((k, v) -> ctx.writeCString(k).writeCString(v));
      ctx.writeByte((byte) 0).writeLengthIntEnd();
      ctx.buf.flip();
      return writeFrontendMessage().thenCompose(__ -> readAuthResponse());
    }

    protected CompletableFuture<QueryReadyConnection.AutoCommit> readAuthResponse() {
      return readNonGeneralBackendMessage().thenCompose(__ -> {
        char typ = (char) ctx.buf.get();
        if (typ != 'R') throw new IllegalArgumentException("Unrecognized auth response message: " + typ);
        // Skip the length, grab the auth type
        ctx.buf.position(5);
        int authType = ctx.buf.getInt();
        if (log.isLoggable(Level.FINE))
          log.log(Level.FINE, "{0} Got auth response of type {1}", new Object[] { ctx, authType });
        switch (authType) {
          // AuthenticationOk
          case 0: return readPostAuthResponse();
          // AuthenticationCleartextPassword
          case 3: return sendClearTextPassword();
          // AuthenticationMD5Password
          case 5: return sendMd5Password(ctx.buf.get(), ctx.buf.get(), ctx.buf.get(), ctx.buf.get());
          // Other...
          default: throw new IllegalArgumentException("Unrecognized auth response type: " + authType);
        }
      });
    }

    protected CompletableFuture<QueryReadyConnection.AutoCommit> sendClearTextPassword() {
      if (ctx.config.password == null) throw new IllegalStateException("Password requested, none provided");
      ctx.buf.clear();
      ctx.writeByte((byte) 'p').writeLengthIntBegin().writeCString(ctx.config.password).writeLengthIntEnd();
      ctx.buf.flip();
      return writeFrontendMessage().thenCompose(__ -> readAuthResponse());
    }

    protected CompletableFuture<QueryReadyConnection.AutoCommit> sendMd5Password(byte... salt) {
      if (ctx.config.password == null) throw new IllegalStateException("Password requested, none provided");
      // "md5" + md5(md5(password + username) + random-salt))
      ctx.buf.clear();
      ctx.writeByte((byte) 'p').writeLengthIntBegin().
          writeByte((byte) 'm').writeByte((byte) 'd').writeByte((byte) '5');
      MessageDigest md5;
      try {
        md5 = MessageDigest.getInstance("MD5");
      } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
      byte[] hash = Util.md5Hex(md5,
          Util.md5Hex(md5, ctx.config.password.getBytes(StandardCharsets.UTF_8),
              ctx.config.username.getBytes(StandardCharsets.UTF_8)),
          salt);
      ctx.writeBytes(hash).writeByte((byte) 0).writeLengthIntEnd();
      ctx.buf.flip();
      return writeFrontendMessage().thenCompose(__ -> readAuthResponse());
    }

    protected CompletableFuture<QueryReadyConnection.AutoCommit> readPostAuthResponse() {
      return readNonGeneralBackendMessage().thenCompose(__ -> {
        char typ = (char) ctx.buf.get();
        switch (typ) {
          // BackendKeyData
          case 'K':
            ctx.buf.position(5);
            ctx.processId = ctx.buf.getInt();
            ctx.secretKey = ctx.buf.getInt();
            return readPostAuthResponse();
          // ReadyForQuery
          case 'Z':
            ctx.buf.position(5);
            updateReadyForQueryTransactionStatus();
            return CompletableFuture.completedFuture(new QueryReadyConnection.AutoCommit(ctx));
          // TODO: NegotiateProtocolVersion
          default: throw new IllegalArgumentException("Unrecognized post-auth response type: " + typ);
        }
      });
    }

    protected CompletableFuture<Void> cancelOther(int processId, int secretKey) {
      // Send the cancel request and just close the connection
      ctx.buf.clear();
      ctx.writeInt(16).writeInt(80877102).writeInt(processId).writeInt(secretKey);
      ctx.buf.flip();
      return writeFrontendMessage().whenComplete((__, ___) -> ctx.io.close());
    }
  }

  public static abstract class Started extends Connection {
    // True when inside a query or something similar
    protected boolean invalid;

    protected Started(Context ctx) { super(ctx); }

    public @Nullable Integer getProcessId() { return ctx.processId; }
    public @Nullable Integer getSecretKey() { return ctx.secretKey; }

    // Tick for general messages, errors when timeout reached (java.nio.channels.InterruptedByTimeoutException) or
    // if a non-general message is sent
    public CompletableFuture<Void> unsolicitedMessageTick(long timeout, TimeUnit timeoutUnit) {
      return readBackendMessage(timeout, timeoutUnit).thenCompose(__ -> {
        CompletableFuture<Void> generalHandled = handleGeneralResponse();
        if (generalHandled != null) return generalHandled;
        throw new DriverException.NonGeneralMessageOnTick((char) ctx.buf.get(0));
      });
    }

    protected void assertValid() {
      if (invalid) throw new IllegalStateException(
          "Attempting to reuse a connection in an invalid state. Did you forget a 'done' somewhere?");
    }
  }
}
