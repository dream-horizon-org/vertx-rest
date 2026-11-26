package com.dream11.rest.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggerFilterTest {

  @Test
  void logsUtf8AndNormalizesNewlinesWithoutConsumingTheBody() throws IOException {
    assertBody("Gr\u00fc\u00dfe \ud83d\ude00\nnext\r\nline", "Gr\u00fc\u00dfe \ud83d\ude00 next\r line");
  }

  @Test
  void handlesEmptyAndLargeBodies() throws IOException {
    assertBody("", "");
    assertBody("\u20ac".repeat(10000), "\u20ac".repeat(10000));
  }

  @Test
  void replacesMalformedUtf8() throws IOException {
    byte[] bytes = {(byte) 0xc3, 0x28, (byte) 0xe2, (byte) 0x82};
    TrackingStream stream = new TrackingStream(bytes);
    assertTrue(logBody(stream, true).contains("Body : \ufffd(\ufffd, Headers"));
    assertArrayEquals(bytes, stream.readAllBytes());
    assertFalse(stream.closed);
  }

  @Test
  void preservesAbsentEntityAndNullStreamBehavior() throws IOException {
    TrackingStream stream = new TrackingStream(new byte[] {65});
    assertTrue(logBody(stream, false).contains("Body : {}, Headers"));
    assertEquals(0, stream.resets);
    assertEquals(65, stream.read());
    assertTrue(logBody(null, true).contains("Body : {}, Headers"));
  }

  @Test
  void propagatesReadFailures() {
    InputStream stream = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("read failed");
      }
    };
    assertEquals("read failed", assertThrows(IOException.class, () -> logBody(stream, true)).getMessage());
  }

  @Test
  void preservesResetFailure() {
    InputStream stream = new InputStream() {
      @Override
      public int read() {
        return -1;
      }
    };
    assertThrows(IOException.class, () -> logBody(stream, true));
  }

  private void assertBody(String body, String expected) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    TrackingStream stream = new TrackingStream(bytes);
    assertTrue(logBody(stream, true).contains("Body : " + expected + ", Headers"));
    assertEquals(1, stream.resets);
    assertArrayEquals(bytes, stream.readAllBytes());
    assertFalse(stream.closed);
  }

  private String logBody(InputStream stream, boolean hasEntity) throws IOException {
    UriInfo uri = (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(), new Class<?>[] {UriInfo.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getPath": return "example";
            case "getRequestUri": return URI.create("http://localhost/example");
            case "getPathParameters":
            case "getQueryParameters": return new MultivaluedHashMap<>();
            default: throw new UnsupportedOperationException(method.getName());
          }
        });
    ContainerRequestContext context = (ContainerRequestContext) Proxy.newProxyInstance(
        ContainerRequestContext.class.getClassLoader(), new Class<?>[] {ContainerRequestContext.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "setProperty": return null;
            case "getMethod": return "POST";
            case "getUriInfo": return uri;
            case "hasEntity": return hasEntity;
            case "getEntityStream": return stream;
            case "getHeaders": return new MultivaluedHashMap<>();
            default: throw new UnsupportedOperationException(method.getName());
          }
        });
    Logger logger = (Logger) LoggerFactory.getLogger(LoggerFilter.class);
    Level previous = logger.getLevel();
    boolean additive = logger.isAdditive();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    try {
      new LoggerFilter().filter(context);
      return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
      logger.setAdditive(additive);
      appender.stop();
    }
  }

  private static class TrackingStream extends ByteArrayInputStream {
    private int resets;
    private boolean closed;

    TrackingStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public synchronized void reset() {
      resets++;
      super.reset();
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
