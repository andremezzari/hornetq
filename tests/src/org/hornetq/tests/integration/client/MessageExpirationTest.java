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

package org.hornetq.tests.integration.client;

import static org.hornetq.tests.util.RandomUtil.randomSimpleString;

import org.hornetq.core.client.ClientConsumer;
import org.hornetq.core.client.ClientMessage;
import org.hornetq.core.client.ClientProducer;
import org.hornetq.core.client.ClientSession;
import org.hornetq.core.client.ClientSessionFactory;
import org.hornetq.core.message.impl.MessageImpl;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.Queue;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.utils.SimpleString;

/**
 * A MessageExpirationTest
 *
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 *
 *
 */
public class MessageExpirationTest extends ServiceTestBase
{

   // Constants -----------------------------------------------------

   private static final int EXPIRATION = 1000;

   // Attributes ----------------------------------------------------

   private HornetQServer server;

   private ClientSession session;
   
   private ClientSessionFactory sf;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testMessageExpiredWithoutExpiryAddress() throws Exception
   {
      SimpleString address = randomSimpleString();
      SimpleString queue = randomSimpleString();

      session.createQueue(address, queue, false);

      ClientProducer producer = session.createProducer(address);
      ClientMessage message = session.createClientMessage(false);
      message.setExpiration(System.currentTimeMillis() + EXPIRATION);
      producer.send(message);

      Thread.sleep(EXPIRATION * 2);

      session.start();

      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message2 = consumer.receive(500);
      assertNull(message2);

      consumer.close();
      session.deleteQueue(queue);
   }
   
   public void testMessageExpirationOnServer() throws Exception
   {
      SimpleString address = randomSimpleString();
      SimpleString queue = randomSimpleString();

      session.createQueue(address, queue, false);

      ClientProducer producer = session.createProducer(address);
      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message = session.createClientMessage(false);
      message.setExpiration(System.currentTimeMillis() + EXPIRATION);
      producer.send(message);

      Thread.sleep(EXPIRATION * 2);
           
      session.start();
      
      Thread.sleep(500);
      
      assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getDeliveringCount());
      assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getMessageCount());

      
      ClientMessage message2 = consumer.receive(500);
      assertNull(message2);

      consumer.close();
      session.deleteQueue(queue);
   }
   
   public void testMessageExpirationOnClient() throws Exception
   {
      SimpleString address = randomSimpleString();
      SimpleString queue = randomSimpleString();

      session.createQueue(address, queue, false);

      ClientProducer producer = session.createProducer(address);
      ClientMessage message = session.createClientMessage(false);
      message.setExpiration(System.currentTimeMillis() + EXPIRATION);
      producer.send(message);
     
      session.start();
      
      Thread.sleep(EXPIRATION * 2);

      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message2 = consumer.receive(500);
      assertNull(message2);
                 
      assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getDeliveringCount());
      assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getMessageCount());

      consumer.close();
      session.deleteQueue(queue);
   }

   public void testMessageExpiredWithExpiryAddress() throws Exception
   {
      SimpleString address = randomSimpleString();
      SimpleString queue = randomSimpleString();
      final SimpleString expiryAddress = randomSimpleString();
      SimpleString expiryQueue = randomSimpleString();
      
      server.getAddressSettingsRepository().addMatch(address.toString(), new AddressSettings()
      {
         @Override
         public SimpleString getExpiryAddress()
         {
            return expiryAddress;
         }
      });

      session.createQueue(address, queue, false);
      session.createQueue(expiryAddress, expiryQueue, false);
      

      ClientProducer producer = session.createProducer(address);
      ClientMessage message = session.createClientMessage(false);
      message.setExpiration(System.currentTimeMillis() + EXPIRATION);
      producer.send(message);

      Thread.sleep(EXPIRATION * 2);

      session.start();

      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message2 = consumer.receive(500);
      assertNull(message2);

      ClientConsumer expiryConsumer = session.createConsumer(expiryQueue);
      ClientMessage expiredMessage = expiryConsumer.receive(500);
      assertNotNull(expiredMessage);
      assertNotNull(expiredMessage.getProperty(MessageImpl.HDR_ACTUAL_EXPIRY_TIME));
      assertEquals(address, expiredMessage.getProperty(MessageImpl.HDR_ORIGINAL_DESTINATION));
      consumer.close();
      expiryConsumer.close();
      session.deleteQueue(queue);
      session.deleteQueue(expiryQueue);
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();

      server = createServer(false);
      server.start();

      sf = createInVMFactory();
      session = sf.createSession(false, true, true);
   }

   @Override
   protected void tearDown() throws Exception
   {
      sf.close();
      
      session.close();

      server.stop();
      
      session = null;
      
      server = null;
      
      sf = null;

      super.tearDown();
   }

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}