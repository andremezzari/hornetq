/*
 * Copyright 2010 Red Hat, Inc.
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

import org.hornetq.core.asyncio.impl.AsynchronousFileImpl;
import org.hornetq.tests.util.UnitTestCase;

/**
 * This tests is placed in duplication here to validate that the libaio module is properly loaded on this 
 * test module.
 * 
 * This test should be placed on each one of the tests modules to make sure the library is loaded correctly.
 *
 * @author <mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class LibaioDependencyCheckTest extends UnitTestCase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testDependency() throws Exception
   {
      if (System.getProperties().get("os.name").equals("Linux"))
      {
         assertTrue("Libaio is not available on this platform", AsynchronousFileImpl.isLoaded());
      }
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
