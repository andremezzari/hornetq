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

package org.hornetq.jms.example;

import java.util.Map;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;

/**
 * A ExampleConfiguration
 *
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 */
public class ExampleConfiguration extends Configuration
{
   private final Map<String, ?> options;

   private final String loginModuleName;

   public ExampleConfiguration(final String loginModuleName, final Map<String, ?> options)
   {
      this.loginModuleName = loginModuleName;
      this.options = options;
   }

   @Override
   public AppConfigurationEntry[] getAppConfigurationEntry(final String name)
   {
      AppConfigurationEntry entry = new AppConfigurationEntry(loginModuleName,
                                                              AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                                              options);
      return new AppConfigurationEntry[] { entry };
   }

   @Override
   public void refresh()
   {
   }
}
