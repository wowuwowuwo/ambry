package com.github.ambry.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;


/**
 * Netty specific implementation of {@link RestResponseHandler}.
 * <p/>
 * Used by implementations of {@link BlobStorageService} to return their response via Netty
 * <p/>
 * The implementation is thread safe but provides no ordering guarantees. This means that data sent in might or might
 * not be written to the channel (in case other threads close the channel).
 */
class NettyResponseHandler implements RestResponseHandler {

  private final ChannelHandlerContext ctx;
  private final HttpResponse responseMetadata;
  private final NettyMetrics nettyMetrics;
  private final ChannelWriteResultListener channelWriteResultListener;
  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final AtomicBoolean requestComplete = new AtomicBoolean(false);
  private final AtomicBoolean responseMetadataWritten = new AtomicBoolean(false);
  private final AtomicBoolean channelClosed = new AtomicBoolean(false);
  private final ReentrantLock responseMetadataChangeLock = new ReentrantLock();
  private final ReentrantLock channelWriteLock = new ReentrantLock();
  private ChannelFuture lastWriteFuture;

  public NettyResponseHandler(ChannelHandlerContext ctx, NettyMetrics nettyMetrics) {
    this.ctx = ctx;
    this.nettyMetrics = nettyMetrics;
    this.responseMetadata = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    channelWriteResultListener = new ChannelWriteResultListener(nettyMetrics);
    lastWriteFuture = ctx.newSucceededFuture();
    logger.trace("Instantiated NettyResponseHandler");
  }

  @Override
  public void addToResponseBody(byte[] data, boolean isLast)
      throws RestServiceException {
    if (!responseMetadataWritten.get()) {
      maybeWriteResponseMetadata(responseMetadata);
    }
    logger.trace("Adding {} bytes of data to response on channel {}", data.length, ctx.channel());
    ByteBuf buf = Unpooled.wrappedBuffer(data);
    HttpContent content;
    if (isLast) {
      logger.trace("Last part of the response for request on channel {} sent", ctx.channel());
      content = new DefaultLastHttpContent(buf);
    } else {
      content = new DefaultHttpContent(buf);
    }
    writeToChannel(content);
  }

  @Override
  public void flush() {
    if (!responseMetadataWritten.get()) {
      maybeWriteResponseMetadata(responseMetadata);
    }
    logger.trace("Flushing response data to channel {}", ctx.channel());
    // CAVEAT: It is possible that this flush might fail because the channel has been closed by an external thread with
    // a direct reference to the ChannelHandlerContext.
    ctx.flush();
  }

  @Override
  public void onRequestComplete(Throwable cause, boolean forceClose) {
    try {
      if (requestComplete.compareAndSet(false, true)) {
        logger.trace("Finished responding to current request on channel {}", ctx.channel());
        nettyMetrics.requestCompletionRate.mark();
        if (cause != null) {
          nettyMetrics.requestHandlingError.inc();
          logger.trace("Sending error response to client on channel {}", ctx.channel());
          ChannelFuture errorResponseWrite = maybeWriteResponseMetadata(generateErrorResponse(cause));
          if (errorResponseWrite.isDone() && !errorResponseWrite.isSuccess()) {
            logger.error("Swallowing write exception encountered while sending error response to client on channel {}",
                ctx.channel(), errorResponseWrite.cause());
            nettyMetrics.responseSendingError.inc();
            // close the connection anyway so that the client knows something went wrong.
          }
        }
        flush();
        close();
      }
    } catch (Exception e) {
      logger.error("Swallowing exception encountered during onRequestComplete tasks", e);
      nettyMetrics.responseHandlerRequestCompleteTasksError.inc();
    }
  }

  @Override
  public boolean isRequestComplete() {
    return requestComplete.get();
  }

  @Override
  public void setContentType(String type)
      throws RestServiceException {
    changeResponseHeader(HttpHeaders.Names.CONTENT_TYPE, type);
    logger.trace("Set content type to {} for response on channel {}",
        responseMetadata.headers().get(HttpHeaders.Names.CONTENT_TYPE), ctx.channel());
  }

  /**
   * Writes response metadata to the channel if not already written previously and channel is active.
   * <p/>
   * Other than Netty write failures, this operation can fail for three reasons: -
   * 1. Response metadata has already been written - results in a {@link RestServiceException}.
   * 2. Channel is inactive - results in a {@link RestServiceException}.
   * 3. Synchronize for response metadata write is interrupted- results in a {@link InterruptedException}.
   * In all three cases, a failed {@link ChannelFuture} wrapping the exact exception is returned.
   * @param responseMetadata The response metadata to be written.
   * @return A {@link ChannelFuture} that tracks the write operation if sanity checks succeeded. Else, a failed
   * {@link ChannelFuture} wrapping the exact exception.
   */
  private ChannelFuture maybeWriteResponseMetadata(HttpResponse responseMetadata) {
    try {
      responseMetadataChangeLock.lockInterruptibly();
      verifyResponseAlive();
      logger
          .trace("Sending response metadata with status {} on channel {}", responseMetadata.getStatus(), ctx.channel());
      responseMetadataWritten.set(true);
      return writeToChannel(responseMetadata);
    } catch (Exception e) {
      // specifically don't want this to throw Exceptions because the semantic "maybe" hints that it is possible that
      // the caller does not care whether this happens or not. If he does care, he will check the future returned.
      return ctx.newFailedFuture(e);
    } finally {
      if (channelWriteLock.isHeldByCurrentThread()) {
        channelWriteLock.unlock();
      }
    }
  }

  /**
   * Writes the provided {@link HttpObject} to the channel.
   * </p>
   * This function is thread safe but offers no ordering guarantees. The write can fail if synchronization to write to
   * channel is interrupted.
   * @param httpObject the {@link HttpObject} to be written.
   * @return A {@link ChannelFuture} that tracks the write operation.
   * @throws RestServiceException If the channel is not active.
   */
  private ChannelFuture writeToChannel(HttpObject httpObject)
      throws RestServiceException {
    try {
      channelWriteLock.lockInterruptibly();
      verifyChannelActive();
      // CAVEAT: This write may or may not succeed depending on whether the channel is open at actual write time.
      // While this class makes sure that close happens only after all writes of this class are complete, any external
      // thread that has a direct reference to the ChannelHandlerContext can close the channel at any time and we
      // might not have got in our write when the channel was requested to be closed.
      // CAVEAT: This write is thread-safe but there are no ordering guarantees (there cannot be).
      logger.trace("Writing to channel {}", ctx.channel());
      lastWriteFuture = channelWriteResultListener.trackWrite(ctx.write(httpObject));
      return lastWriteFuture;
    } catch (InterruptedException e) {
      nettyMetrics.channelWriteLockInterruptedError.inc();
      throw new RestServiceException("Internal channel write lock acquiring interrupted. Data not written to channel",
          e, RestServiceErrorCode.OperationInterrupted);
    } catch (RestServiceException e) {
      nettyMetrics.channelWriteAfterCloseError.inc();
      throw e;
    } finally {
      if (channelWriteLock.isHeldByCurrentThread()) {
        channelWriteLock.unlock();
      }
    }
  }

  /**
   * Changes the value of response headers after making sure that the response metadata is not already sent or is being
   * sent.
   * <p/>
   * The update can fail for two reasons: -
   * 1. Synchronization for response metadata write is interrupted - results in a {@link InterruptedException}. This is
   * wrapped in a {@link RestServiceException}.
   * 2. The response metadata was already sent or is being sent - results in a {@link RestServiceException} that is
   * thrown as is.
   * @param headerName The name of the header.
   * @param headerValue The intended value of the header.
   * @return The updated headers.
   * @throws RestServiceException if the response metadata is already sent or is being sent.
   */
  private HttpHeaders changeResponseHeader(String headerName, Object headerValue)
      throws RestServiceException {
    try {
      responseMetadataChangeLock.lockInterruptibly();
      verifyResponseAlive();
      logger.trace("Changing header {} to {} for channel {}", headerName, headerValue, ctx.channel());
      return responseMetadata.headers().set(headerName, headerValue);
    } catch (InterruptedException e) {
      nettyMetrics.responseMetadataWriteLockInterruptedError.inc();
      throw new RestServiceException("Internal metadata change lock acquiring interrupted. Response header not changed",
          e, RestServiceErrorCode.OperationInterrupted);
    } catch (RestServiceException e) {
      nettyMetrics.deadResponseAccessError.inc();
      throw e;
    } finally {
      if (channelWriteLock.isHeldByCurrentThread()) {
        channelWriteLock.unlock();
      }
    }
  }

  /**
   * Closes the channel. No further communication will be possible.
   * <p/>
   * Any pending writes (that are not already flushed) might be discarded.
   */
  private void close()
      throws RestServiceException {
    if (!channelClosed.get() && ctx.channel().isOpen()) {
      try {
        channelWriteLock.lockInterruptibly();
        channelClosed.set(true);
        // Waits for the last write operation performed by this class to succeed before closing.
        // This is NOT blocking.
        lastWriteFuture.addListener(ChannelFutureListener.CLOSE);
        logger.trace("Requested closing of channel {}", ctx.channel());
      } catch (InterruptedException e) {
        nettyMetrics.channelCloseLockInterruptedError.inc();
        throw new RestServiceException("Internal channel close lock acquiring interrupted. Did not close channel", e,
            RestServiceErrorCode.OperationInterrupted);
      } finally {
        if (channelWriteLock.isHeldByCurrentThread()) {
          channelWriteLock.unlock();
        }
      }
    }
  }

  /**
   * Verify state of responseMetadata so that we do not try to modify responseMetadata after it has been written to the
   * channel.
   * <p/>
   * Simply checks for invalid state transitions. No atomicity guarantees. If the caller requires atomicity, it is
   * their responsibility to ensure it.
   * @throws RestServiceException if response metadata has already been sent.
   */
  private void verifyResponseAlive()
      throws RestServiceException {
    if (responseMetadataWritten.get()) {
      throw new RestServiceException("Response metadata has already been written to channel. No more changes possible",
          RestServiceErrorCode.IllegalResponseMetadataStateTransition);
    }
  }

  /**
   * Verify that the channel is still active.
   * <p/>
   * Simply checks for invalid state transitions. No atomicity guarantees. If the caller requires atomicity, it is
   * their responsibility to ensure it.
   * @throws RestServiceException if channel has been already been closed.
   */
  private void verifyChannelActive()
      throws RestServiceException {
    if (channelClosed.get() || !(ctx.channel().isActive())) {
      throw new RestServiceException("Channel is closed and cannot accept operations",
          RestServiceErrorCode.ChannelAlreadyClosed);
    }
  }

  /**
   * Provided a cause, returns an error response with the right status and error message.
   * @param cause the cause of the error.
   */
  private FullHttpResponse generateErrorResponse(Throwable cause) {
    HttpResponseStatus status;
    StringBuilder errReason = new StringBuilder();
    if (cause != null && cause instanceof RestServiceException) {
      status = getHttpResponseStatus(((RestServiceException) cause).getErrorCode());
      if (status == HttpResponseStatus.BAD_REQUEST) {
        errReason.append(" (Reason - ").append(cause.getMessage()).append(")");
      }
    } else {
      status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
      nettyMetrics.unknownExceptionError.inc();
    }
    String fullMsg = "Failure: " + status + errReason;
    logger.trace("Constructed error response for the client - [{}]", fullMsg);
    FullHttpResponse response =
        new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.copiedBuffer(fullMsg, CharsetUtil.UTF_8));
    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");
    return response;
  }

  /**
   * Converts a {@link RestServiceErrorCode} into a {@link HttpResponseStatus}.
   * @param restServiceErrorCode {@link RestServiceErrorCode} that needs to be mapped to a {@link HttpResponseStatus}.
   * @return the {@link HttpResponseStatus} that maps to the {@link RestServiceErrorCode}.
   */
  private HttpResponseStatus getHttpResponseStatus(RestServiceErrorCode restServiceErrorCode) {
    RestServiceErrorCode errorCodeGroup = RestServiceErrorCode.getErrorCodeGroup(restServiceErrorCode);
    switch (errorCodeGroup) {
      case BadRequest:
        nettyMetrics.badRequestError.inc();
        return HttpResponseStatus.BAD_REQUEST;
      case InternalServerError:
        nettyMetrics.internalServerError.inc();
        return HttpResponseStatus.INTERNAL_SERVER_ERROR;
      default:
        nettyMetrics.unknownRestServiceExceptionError.inc();
        return HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }
  }
}

/**
 * Class that tracks multiple writes and takes actions on completion of those writes.
 * <p/>
 * Currently closes the connection on write failure.
 */
class ChannelWriteResultListener implements GenericFutureListener<ChannelFuture> {
  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final ConcurrentHashMap<ChannelFuture, Long> writeFutures = new ConcurrentHashMap<ChannelFuture, Long>();
  private final NettyMetrics nettyMetrics;

  public ChannelWriteResultListener(NettyMetrics nettyMetrics) {
    this.nettyMetrics = nettyMetrics;
    logger.trace("ChannelWriteResultListener instantiated");
  }

  /**
   * Adds the received write future to the list of futures being tracked and requests a callback after the future
   * finishes.
   * @param writeFuture the write {@link ChannelFuture} that needs to be tracked.
   * @return the write {@link ChannelFuture} that was submitted to be tracked.
   */
  public ChannelFuture trackWrite(ChannelFuture writeFuture) {
    Long writeStartTime = System.currentTimeMillis();
    Long prevStartTime = writeFutures.putIfAbsent(writeFuture, writeStartTime);
    if (prevStartTime == null) {
      writeFuture.addListener(this);
    } else {
      logger.warn("Discarding duplicate write tracking request for ChannelFuture. Prev write time {}. Current time {}",
          prevStartTime, writeStartTime);
      nettyMetrics.channelWriteFutureAlreadyExistsError.inc();
    }
    return writeFuture;
  }

  /**
   * Callback for when the operation represented by the {@link ChannelFuture} is done.
   * @param future the {@link ChannelFuture} whose operation finished.
   */
  @Override
  public void operationComplete(ChannelFuture future) {
    Long writeStartTime = writeFutures.remove(future);
    if (writeStartTime != null) {
      if (!future.isSuccess()) {
        future.channel().close();
        logger.error("Write on channel {} failed due to exception. Closed channel", future.channel(), future.cause());
        nettyMetrics.channelWriteError.inc();
      } else {
        // TODO: track small, medium, large and huge writes.
        nettyMetrics.channelWriteLatencyInMs.update(System.currentTimeMillis() - writeStartTime);
      }
    } else {
      logger.warn("Received operationComplete callback for ChannelFuture not found in tracking map for channel {}",
          future.channel());
      nettyMetrics.channelWriteFutureNotFoundError.inc();
    }
  }
}