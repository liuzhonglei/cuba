/*
 * Copyright (c) 2008-2017 Haulmont.
 *
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

package com.haulmont.cuba.core.sys.remoting.staticselector;

import com.haulmont.bali.util.Preconditions;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.SecurityContext;
import com.haulmont.cuba.core.sys.remoting.ServerSelector;
import com.haulmont.cuba.security.global.ClientBasedSession;
import com.haulmont.cuba.security.global.UserSession;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Implementation of the {@link ServerSelector} interface working with static list of cluster members.
 */
public class StaticServerSelector implements ServerSelector {

    private Logger log = LoggerFactory.getLogger(StaticServerSelector.class);

    private String servletPath = "remoting";
    private String baseUrl;
    private Consumer<List<String>> serverSorter;

    private List<String> urls = new ArrayList<>();
    private Set<String> failedUrls = new CopyOnWriteArraySet<>();

    private ThreadLocal<List<String>> lastNoSessionUrls = new ThreadLocal<>();

    private static final String SESSION_ATTR = StaticServerSelector.class.getName() + ".lastSessionUrls";

    private static class Context {
        private List<String> urls;
        private String lastUrl;

        @Override
        public String toString() {
            return "Context@" + Integer.toHexString(hashCode()) + "{" +
                    "urls=" + urls +
                    ", lastUrl='" + lastUrl + '\'' +
                    '}';
        }
    }

    public String getServletPath() {
        return servletPath;
    }

    public void setServletPath(String servletPath) {
        this.servletPath = servletPath;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setServerSorter(Consumer<List<String>> serverSorter) {
        this.serverSorter = serverSorter;
    }

    public void init() {
        if (baseUrl == null)
            throw new IllegalStateException("baseUrl is null");
        String[] strings = baseUrl.split("[,;]");
        for (String string : strings) {
            if (!StringUtils.isBlank(string)) {
                urls.add(string + "/" + servletPath);
            }
        }
        log.debug("Server URLs: {}", urls);
    }

    @Override
    public Object initContext() {
        List<String> sessionUrls;
        boolean isNewSession = false;
        UserSession userSession = getUserSession();
        if (userSession == null) {
            sessionUrls = sortUrls();
            lastNoSessionUrls.set(sessionUrls);
        } else {
            //noinspection unchecked
            sessionUrls = userSession.getLocalAttribute(SESSION_ATTR);
            if (sessionUrls == null) {
                sessionUrls = lastNoSessionUrls.get();
                isNewSession = true;
            }
            if (sessionUrls == null)
                sessionUrls = sortUrls();
            userSession.setLocalAttribute(SESSION_ATTR, sessionUrls);
            lastNoSessionUrls.remove();
        }

        Context ctx = new Context();
        ctx.urls = new ArrayList<>(sessionUrls.size());
        if (isNewSession) {
            // each new session retries access to all servers - this way we can find out if some failed server is back online
            ctx.urls.addAll(sessionUrls);
        } else {
            // the session is not new or there is no session
            // first add all successful
            for (String url : sessionUrls) {
                if (!failedUrls.contains(url))
                    ctx.urls.add(url);
            }
            // then add all failed
            for (String url : sessionUrls) {
                if (failedUrls.contains(url))
                    ctx.urls.add(url);
            }
        }
        log.trace("Context initialized: {} ", ctx);
        return ctx;
    }

    private List<String> sortUrls() {
        List<String> list = new ArrayList<>(urls);
        if (serverSorter != null) {
            serverSorter.accept(list);
        }
        return list;
    }

    @Override
    @Nullable
    public String getUrl(Object context) {
        Context ctx = (Context) context;

        if (ctx.urls.isEmpty())
            return null;
        else {
            ctx.lastUrl = ctx.urls.get(0);
            return ctx.lastUrl;
        }
    }

    @Override
    public void success(Object context) {
        Context ctx = (Context) context;
        Preconditions.checkNotNullArgument(ctx.lastUrl, "lastUrl is null");
        log.trace("Success: {}", ctx);

        failedUrls.remove(ctx.lastUrl);
    }

    @Override
    public void fail(Object context) {
        Context ctx = (Context) context;
        Preconditions.checkNotNullArgument(ctx.lastUrl, "lastUrl is null");
        log.trace("Fail: {}", ctx);

        failedUrls.add(ctx.lastUrl);
        ctx.urls.remove(ctx.lastUrl);
    }

    @Nullable
    protected UserSession getUserSession() {
        SecurityContext securityContext = AppContext.getSecurityContext();
        if (securityContext == null)
            return null;

        UserSession session = securityContext.getSession();
        if (session == null
                || (session instanceof ClientBasedSession && ((ClientBasedSession) session).hasRequestScopedInfo()))
            return null;

        return session;
    }
}
