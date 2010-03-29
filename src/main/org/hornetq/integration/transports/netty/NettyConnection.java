/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.integration.transports.netty;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.HornetQBuffers;
import org.hornetq.core.buffers.impl.ChannelBufferWrapper;
import org.hornetq.core.logging.Logger;
import org.hornetq.spi.core.protocol.ProtocolType;
import org.hornetq.spi.core.remoting.Connection;
import org.hornetq.spi.core.remoting.ConnectionLifeCycleListener;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.handler.ssl.SslHandler;

/**
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 * @author <a href="mailto:ataylor@redhat.com">Andy Taylor</a>
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * 
 * @version <tt>$Revision$</tt>
 */
public class NettyConnection implements Connection
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(NettyConnection.class);

   private static final int BATCHING_BUFFER_SIZE = 8192;

   // Attributes ----------------------------------------------------

   private final Channel channel;

   private boolean closed;

   private final ConnectionLifeCycleListener listener;

   private final boolean batchingEnabled;
   
   private HornetQBuffer batchBuffer;
   
   private final Object writeLock = new Object();

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   public NettyConnection(final Channel channel, final ConnectionLifeCycleListener listener, boolean batchingEnabled)
   {
      this.channel = channel;

      this.listener = listener;

      this.batchingEnabled = batchingEnabled;
      
      listener.connectionCreated(this, ProtocolType.CORE);
   }

   // Public --------------------------------------------------------

   // Connection implementation ----------------------------

   public synchronized void close()
   {
      if (closed)
      {
         return;
      }

      SslHandler sslHandler = (SslHandler)channel.getPipeline().get("ssl");
      if (sslHandler != null)
      {
         try
         {
            ChannelFuture sslCloseFuture = sslHandler.close(channel);

            if (!sslCloseFuture.awaitUninterruptibly(10000))
            {
               NettyConnection.log.warn("Timed out waiting for ssl close future to complete");
            }
         }
         catch (Throwable t)
         {
            // ignore
         }
      }

      ChannelFuture closeFuture = channel.close();

      if (!closeFuture.awaitUninterruptibly(10000))
      {
         NettyConnection.log.warn("Timed out waiting for channel to close");
      }

      closed = true;

      listener.connectionDestroyed(getID());
   }

   public HornetQBuffer createBuffer(final int size)
   {
      return new ChannelBufferWrapper(ChannelBuffers.dynamicBuffer(size));
   }

   public Object getID()
   {
      return channel.getId();
   }

   // This is called periodically to flush the batch buffer
   public void checkFlushBatchBuffer()
   {
      synchronized (writeLock)
      {
         if (!batchingEnabled)
         {
            return;
         }

         if (batchBuffer != null && batchBuffer.readable())
         {
            channel.write(batchBuffer.channelBuffer());

            batchBuffer = HornetQBuffers.dynamicBuffer(BATCHING_BUFFER_SIZE);
         }
      }
   }

   public void write(final HornetQBuffer buffer)
   {
      write(buffer, false, false);
   }

   public void write(HornetQBuffer buffer, final boolean flush, final boolean batched)
   {
      synchronized (writeLock)
      {
         if (batchBuffer == null && batchingEnabled && batched && !flush)
         {
            // Lazily create batch buffer

            batchBuffer = HornetQBuffers.dynamicBuffer(BATCHING_BUFFER_SIZE);
         }

         if (batchBuffer != null)
         {
            batchBuffer.writeBytes(buffer, 0, buffer.writerIndex());

            if (batchBuffer.writerIndex() >= BATCHING_BUFFER_SIZE || !batched || flush)
            {
               // If the batch buffer is full or it's flush param or not batched then flush the buffer

               buffer = batchBuffer;
            }
            else
            {
               return;
            }

            if (!batched || flush)
            {
               batchBuffer = null;
            }
            else
            {
               // Create a new buffer

               batchBuffer = HornetQBuffers.dynamicBuffer(BATCHING_BUFFER_SIZE);
            }
         }

         ChannelFuture future = channel.write(buffer.channelBuffer());

         if (flush)
         {
            while (true)
            {
               try
               {
                  boolean ok = future.await(10000);

                  if (!ok)
                  {
                     NettyConnection.log.warn("Timed out waiting for packet to be flushed");
                  }

                  break;
               }
               catch (InterruptedException ignore)
               {
               }
            }
         }
      }
   }

   public String getRemoteAddress()
   {
      return channel.getRemoteAddress().toString();
   }

   // Public --------------------------------------------------------

   @Override
   public String toString()
   {
      return super.toString() + "[local= " + channel.getLocalAddress() + ", remote=" + channel.getRemoteAddress() + "]";
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
