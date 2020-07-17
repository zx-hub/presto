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
package io.prestosql.server.security;

import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.util.Modules;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.airlift.discovery.server.DynamicAnnouncementResource;
import io.airlift.discovery.server.ServiceResource;
import io.airlift.discovery.store.StoreResource;
import io.airlift.http.server.HttpServerConfig;
import io.airlift.jmx.MBeanResource;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.inject.multibindings.MapBinder.newMapBinder;
import static io.airlift.configuration.ConditionalModule.installModuleIf;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;
import static io.prestosql.server.security.ResourceSecurityBinder.resourceSecurityBinder;
import static java.util.Locale.ENGLISH;

public class ServerSecurityModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        binder.bind(AuthenticationFilter.class);
        jaxrsBinder(binder).bind(ResourceSecurityDynamicFeature.class);

        resourceSecurityBinder(binder)
                .managementReadResource(ServiceResource.class)
                .managementReadResource(MBeanResource.class)
                .internalOnlyResource(DynamicAnnouncementResource.class)
                .internalOnlyResource(StoreResource.class);

        binder.bind(PasswordAuthenticatorManager.class).in(Scopes.SINGLETON);
        binder.bind(CertificateAuthenticatorManager.class).in(Scopes.SINGLETON);

        insecureHttpAuthenticationDefaults();

        authenticatorBinder(binder); // create empty map binder

        installAuthenticator("certificate", CertificateAuthenticator.class, CertificateConfig.class);
        installAuthenticator("kerberos", KerberosAuthenticator.class, KerberosConfig.class);
        installAuthenticator("password", PasswordAuthenticator.class, PasswordAuthenticatorConfig.class);
        installAuthenticator("jwt", JsonWebTokenAuthenticator.class, JsonWebTokenConfig.class);

        install(authenticatorModule("insecure", InsecureAuthenticator.class, unused -> {}));
    }

    @Provides
    public List<Authenticator> getAuthenticatorList(SecurityConfig config, Map<String, Authenticator> authenticators)
    {
        return authenticationTypes(config).stream()
                .map(type -> {
                    Authenticator authenticator = authenticators.get(type);
                    if (authenticator == null) {
                        throw new RuntimeException("Unknown authenticator type: " + type);
                    }
                    return authenticator;
                })
                .collect(toImmutableList());
    }

    public static Module authenticatorModule(String name, Class<? extends Authenticator> clazz, Module module)
    {
        checkArgument(name.toLowerCase(ENGLISH).equals(name), "name is not lower case: %s", name);
        Module authModule = binder -> authenticatorBinder(binder).addBinding(name).to(clazz).in(Scopes.SINGLETON);
        return installModuleIf(
                SecurityConfig.class,
                config -> authenticationTypes(config).contains(name),
                Modules.combine(module, authModule));
    }

    private void installAuthenticator(String name, Class<? extends Authenticator> authenticator, Class<?> config)
    {
        install(authenticatorModule(name, authenticator, binder -> configBinder(binder).bindConfig(config)));
    }

    private static MapBinder<String, Authenticator> authenticatorBinder(Binder binder)
    {
        return newMapBinder(binder, String.class, Authenticator.class);
    }

    private static List<String> authenticationTypes(SecurityConfig config)
    {
        return config.getAuthenticationTypes().stream()
                .map(type -> type.toLowerCase(ENGLISH))
                .collect(toImmutableList());
    }

    private void insecureHttpAuthenticationDefaults()
    {
        HttpServerConfig httpServerConfig = buildConfigObject(HttpServerConfig.class);
        SecurityConfig securityConfig = buildConfigObject(SecurityConfig.class);
        // if secure https authentication is enabled, disable insecure authentication over http
        if ((httpServerConfig.isHttpsEnabled() || httpServerConfig.isProcessForwarded()) &&
                !securityConfig.getAuthenticationTypes().equals(ImmutableList.of("insecure"))) {
            install(binder -> configBinder(binder).bindConfigDefaults(SecurityConfig.class, config -> config.setInsecureAuthenticationOverHttpAllowed(false)));
        }
    }
}
