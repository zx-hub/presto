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

import io.prestosql.spi.security.BasicPrincipal;
import io.prestosql.spi.security.Identity;

import javax.ws.rs.container.ContainerRequestContext;

import java.util.Optional;

import static com.google.common.base.Strings.emptyToNull;
import static io.prestosql.client.PrestoHeaders.PRESTO_USER;
import static io.prestosql.server.security.BasicAuthCredentials.extractBasicAuthCredentials;

public class InsecureAuthenticator
        implements Authenticator
{
    @Override
    public Identity authenticate(ContainerRequestContext request)
            throws AuthenticationException
    {
        Optional<BasicAuthCredentials> basicAuthCredentials = extractBasicAuthCredentials(request);

        String user;
        if (basicAuthCredentials.isPresent()) {
            if (basicAuthCredentials.get().getPassword().isPresent()) {
                throw new AuthenticationException("Password not allowed for insecure authentication", BasicAuthCredentials.AUTHENTICATE_HEADER);
            }
            user = basicAuthCredentials.get().getUser();
        }
        else {
            user = emptyToNull(request.getHeaders().getFirst(PRESTO_USER));
        }

        if (user == null) {
            throw new AuthenticationException("Basic authentication or " + PRESTO_USER + " must be sent", BasicAuthCredentials.AUTHENTICATE_HEADER);
        }

        return Identity.forUser(user)
                .withPrincipal(new BasicPrincipal(user))
                .build();
    }
}
