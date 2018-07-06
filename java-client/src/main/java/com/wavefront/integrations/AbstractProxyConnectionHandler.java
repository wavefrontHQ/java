package com.wavefront.integrations;

import com.wavefront.metrics.ReconnectingSocket;

import java.io.IOException;
import java.net.InetSocketAddress;

import javax.net.SocketFactory;

/**
 * Abstract base class for sending data to a Wavefront proxy.
 *
 * @author Clement Pang (clement@wavefront.com).
 * @author Vikram Raman (vikram@wavefront.com).
 */
public abstract class AbstractProxyConnectionHandler implements WavefrontConnectionHandler {

  private final InetSocketAddress address;
  private final SocketFactory socketFactory;
  private volatile ReconnectingSocket reconnectingSocket;

  protected AbstractProxyConnectionHandler(InetSocketAddress address, SocketFactory socketFactory) {
    this.address = address;
    this.socketFactory = socketFactory;
    this.reconnectingSocket = null;
  }

  @Override
  public synchronized void connect() throws IllegalStateException, IOException {
    if (reconnectingSocket != null) {
      throw new IllegalStateException("Already connected");
    }
    try {
      reconnectingSocket = new ReconnectingSocket(address.getHostName(), address.getPort(), socketFactory);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean isConnected() {
    return reconnectingSocket != null;
  }

  @Override
  public void flush() throws IOException {
    if (reconnectingSocket != null) {
      reconnectingSocket.flush();
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (reconnectingSocket != null) {
      reconnectingSocket.close();
      reconnectingSocket = null;
    }
  }

  /**
   * Sends the given data to the Wavefront proxy.
   *
   * @param lineData line data in a Wavefront supported format
   * @throws Exception If there was failure sending the data
   */
  protected void sendData(String lineData) throws Exception {
    reconnectingSocket.write(lineData);
  }
}
