/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.hive.authentication;

import com.google.inject.Binder;
import com.google.inject.Module;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.prestosql.plugin.hive.authentication.HiveAuthenticationConfig.HdfsAuthenticationType;
import io.prestosql.plugin.hive.rubix.RubixEnabledConfig;

import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.configuration.ConditionalModule.installModuleIf;
import static io.prestosql.plugin.hive.authentication.AuthenticationModules.kerberosHdfsAuthenticationModule;
import static io.prestosql.plugin.hive.authentication.AuthenticationModules.kerberosImpersonatingHdfsAuthenticationModule;
import static io.prestosql.plugin.hive.authentication.AuthenticationModules.noHdfsAuthenticationModule;
import static io.prestosql.plugin.hive.authentication.AuthenticationModules.simpleImpersonatingHdfsAuthenticationModule;

public class HiveAuthenticationModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        bindAuthenticationModule(
                config -> noHdfsAuth(config) && !config.isHdfsImpersonationEnabled(),
                noHdfsAuthenticationModule());

        bindAuthenticationModule(
                config -> noHdfsAuth(config) && config.isHdfsImpersonationEnabled(),
                simpleImpersonatingHdfsAuthenticationModule());

        bindAuthenticationModule(
                config -> kerberosHdfsAuth(config) && !config.isHdfsImpersonationEnabled(),
                kerberosHdfsAuthenticationModule());

        bindAuthenticationModule(
                config -> kerberosHdfsAuth(config) && config.isHdfsImpersonationEnabled(),
                kerberosImpersonatingHdfsAuthenticationModule());

        checkArgument(
                !buildConfigObject(HiveAuthenticationConfig.class).isHdfsImpersonationEnabled() || !buildConfigObject(RubixEnabledConfig.class).isCacheEnabled(),
                "Hdfs impersonation is not compatible with Hive caching");
    }

    private void bindAuthenticationModule(Predicate<HiveAuthenticationConfig> predicate, Module module)
    {
        install(installModuleIf(HiveAuthenticationConfig.class, predicate, module));
    }

    private static boolean noHdfsAuth(HiveAuthenticationConfig config)
    {
        return config.getHdfsAuthenticationType() == HdfsAuthenticationType.NONE;
    }

    private static boolean kerberosHdfsAuth(HiveAuthenticationConfig config)
    {
        return config.getHdfsAuthenticationType() == HdfsAuthenticationType.KERBEROS;
    }
}
