/*
 * Copyright 2015-2018 _floragunn_ GmbH
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.security.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.security.auditlog.AuditLog;
import org.opensearch.security.filter.SecurityRequestChannel;
import org.opensearch.security.http.XFFResolver;
import org.opensearch.security.support.ConfigConstants;
import org.opensearch.security.user.User;
import org.opensearch.threadpool.ThreadPool;

public class UserInjector {

    protected final Logger log = LogManager.getLogger(UserInjector.class);

    private final ThreadPool threadPool;
    private final AuditLog auditLog;
    private final XFFResolver xffResolver;
    private final Boolean injectUserEnabled;

    public UserInjector(Settings settings, ThreadPool threadPool, AuditLog auditLog, XFFResolver xffResolver) {
        this.threadPool = threadPool;
        this.auditLog = auditLog;
        this.xffResolver = xffResolver;
        this.injectUserEnabled = settings.getAsBoolean(ConfigConstants.SECURITY_UNSUPPORTED_INJECT_USER_ENABLED, false);

    }

    public Result getInjectedUser() {
        if (!injectUserEnabled) {
            return null;
        }

        String injectedUserString = threadPool.getThreadContext().getTransient(ConfigConstants.OPENDISTRO_SECURITY_INJECTED_USER);

        if (log.isDebugEnabled()) {
            log.debug("Injected user string: {}", injectedUserString);
        }

        if (Strings.isNullOrEmpty(injectedUserString)) {
            return null;
        }
        // username|role1,role2|remoteIP:port|attributeKey,attributeValue,attributeKey,attributeValue, ...|requestedTenant
        String[] parts = injectedUserString.split("\\|");

        if (parts.length == 0) {
            log.error("User string malformed, could not extract parts. User string was '{}.' User injection failed.", injectedUserString);
            return null;
        }

        // username
        String userName = parts[0];
        if (Strings.isNullOrEmpty(userName)) {
            log.error("Username must not be null, user string was '{}.' User injection failed.", injectedUserString);
            return null;
        }

        // backend roles
        ImmutableSet<String> backendRoles = ImmutableSet.of();
        if (parts.length > 1 && !Strings.isNullOrEmpty(parts[1])) {
            if (parts[1].length() > 0) {
                backendRoles = ImmutableSet.copyOf(Arrays.asList(parts[1].split(",")));
            }
        }

        // custom attributes
        ImmutableMap<String, String> customAttributes = ImmutableMap.of();
        if (parts.length > 3 && !Strings.isNullOrEmpty(parts[3])) {
            customAttributes = mapFromArray((parts[3].split(",")));
            if (customAttributes == null) {
                log.error("Could not parse custom attributes {}, user injection failed.", parts[3]);
                return null;
            }
        }

        // requested tenant
        String requestedTenant = null;
        if (parts.length > 4 && !Strings.isNullOrEmpty(parts[4])) {
            requestedTenant = parts[4];
        }

        // remote IP - we can set it only once, so we do it last. If non is given,
        // BackendRegistry/XFFResolver will do the job
        TransportAddress transportAddress = null;
        if (parts.length > 2 && !Strings.isNullOrEmpty(parts[2])) {
            try {
                transportAddress = parseTransportAddress(parts[2]);
            } catch (UnknownHostException | IllegalArgumentException e) {
                log.error("Cannot parse remote IP or port: {}, user injection failed.", parts[2], e);
                return null;
            }
        }

        User injectedUser = new User(userName, backendRoles, ImmutableSet.of(), requestedTenant, customAttributes, true);

        if (log.isTraceEnabled()) {
            log.trace("Injected user object:{} ", injectedUser.toString());
        }

        return new Result(injectedUser, transportAddress);
    }

    public TransportAddress parseTransportAddress(String addr) throws UnknownHostException, IllegalArgumentException {
        int lastColonIndex = addr.lastIndexOf(':');
        if (lastColonIndex == -1) {
            throw new IllegalArgumentException("Remote address must have format ip:port");
        }

        String ip = addr.substring(0, lastColonIndex);
        String portString = addr.substring(lastColonIndex + 1);

        InetAddress iAdress = InetAddress.getByName(ip);
        int port = Integer.parseInt(portString);

        return new TransportAddress(iAdress, port);
    }

    boolean injectUser(SecurityRequestChannel request) {
        Result injectedUser = getInjectedUser();
        if (injectedUser == null) {
            return false;
        }

        // Set remote address into the thread context
        if (injectedUser.getTransportAddress() != null) {
            threadPool.getThreadContext()
                .putTransient(ConfigConstants.OPENDISTRO_SECURITY_REMOTE_ADDRESS, injectedUser.getTransportAddress());
        } else {
            threadPool.getThreadContext().putTransient(ConfigConstants.OPENDISTRO_SECURITY_REMOTE_ADDRESS, xffResolver.resolve(request));
        }

        threadPool.getThreadContext().putTransient(ConfigConstants.OPENDISTRO_SECURITY_USER, injectedUser.getUser());
        auditLog.logSucceededLogin(injectedUser.getUser().getName(), true, null, request);

        return true;
    }

    protected ImmutableMap<String, String> mapFromArray(String... keyValues) {
        if (keyValues == null) {
            return ImmutableMap.of();
        }
        if (keyValues.length % 2 != 0) {
            log.error("Expected even number of key/value pairs, got {}.", Arrays.toString(keyValues));
            return null;
        }
        Map<String, String> map = new HashMap<>();

        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return ImmutableMap.copyOf(map);
    }

    public static class Result {
        private final User user;
        private final TransportAddress transportAddress;

        public Result(User user, TransportAddress transportAddress) {
            this.user = user;
            this.transportAddress = transportAddress;
        }

        public User getUser() {
            return user;
        }

        public TransportAddress getTransportAddress() {
            return transportAddress;
        }
    }

}
