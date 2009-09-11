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

import org.hornetq.core.remoting.spi.HornetQBuffer;
import org.hornetq.utils.DataConstants;


/**
 * A SessionSendContinuationMessage
 *
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 * 
 * Created Dec 4, 2008 12:25:14 PM
 *
 *
 */
public class SessionSendContinuationMessage extends SessionContinuationMessage
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   private boolean requiresResponse;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   /**
    * @param type
    */
   public SessionSendContinuationMessage()
   {
      super(SESS_SEND_CONTINUATION);
   }

   /**
    * @param type
    * @param body
    * @param continues
    * @param requiresResponse
    */
   public SessionSendContinuationMessage(final byte[] body,                                         
                                         final boolean continues,
                                         final boolean requiresResponse)
   {
      super(SESS_SEND_CONTINUATION, body, continues);
      this.requiresResponse = requiresResponse;
   }


   // Public --------------------------------------------------------
   
   /**
    * @return the requiresResponse
    */
   public boolean isRequiresResponse()
   {
      return requiresResponse;
   }

   @Override
   public int getRequiredBufferSize()
   {
      return super.getRequiredBufferSize() + DataConstants.SIZE_BOOLEAN;
   }

   @Override
   public void encodeBody(final HornetQBuffer buffer)
   {
      super.encodeBody(buffer);
      buffer.writeBoolean(requiresResponse);
   }

   @Override
   public void decodeBody(final HornetQBuffer buffer)
   {
      super.decodeBody(buffer);
      requiresResponse = buffer.readBoolean();
   }


   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}