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

package org.hornetq.core.remoting.impl.wireformat;

import org.hornetq.core.paging.PagedMessage;
import org.hornetq.core.paging.impl.PagedMessageImpl;
import org.hornetq.core.remoting.spi.HornetQBuffer;
import org.hornetq.utils.DataConstants;

/**
 * A ReplicationPageWrite
 *
 * @author <mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class ReplicationPageWriteMessage extends PacketImpl
{

   int pageNumber;

   PagedMessage pagedMessage;

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   public ReplicationPageWriteMessage()
   {
      super(REPLICATION_PAGE_WRITE);
   }

   public ReplicationPageWriteMessage(final PagedMessage pagedMessage, final int pageNumber)
   {
      this();
      this.pageNumber = pageNumber;
      this.pagedMessage = pagedMessage;
   }

   // Public --------------------------------------------------------

   @Override
   public int getRequiredBufferSize()
   {
      return BASIC_PACKET_SIZE + DataConstants.SIZE_INT + pagedMessage.getEncodeSize();

   }

   @Override
   public void encodeBody(final HornetQBuffer buffer)
   {
      buffer.writeInt(pageNumber);
      pagedMessage.encode(buffer);
   }

   @Override
   public void decodeBody(final HornetQBuffer buffer)
   {
      pageNumber = buffer.readInt();
      pagedMessage = new PagedMessageImpl();
      pagedMessage.decode(buffer);
   }

   /**
    * @return the pageNumber
    */
   public int getPageNumber()
   {
      return pageNumber;
   }

   /**
    * @return the pagedMessage
    */
   public PagedMessage getPagedMessage()
   {
      return pagedMessage;
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}