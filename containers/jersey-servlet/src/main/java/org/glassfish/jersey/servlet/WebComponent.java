/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.glassfish.jersey.internal.inject.AbstractModule;
import org.glassfish.jersey.internal.util.CommittingOutputStream;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.message.internal.Requests;
import org.glassfish.jersey.server.Application;
import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;

import org.glassfish.hk2.ComponentException;
import org.glassfish.hk2.Factory;
import org.glassfish.hk2.Module;

/**
 * An abstract Web component that may be extended by a Servlet and/or
 * Filter implementation, or encapsulated by a Servlet or Filter implementation.
 *
 * @author Paul Sandoz
 */
public class WebComponent {

    private static final Logger LOGGER = Logger.getLogger(WebComponent.class.getName());

    private class WebComponentModule extends AbstractModule {

        @Override
        protected void configure() {

            // TODO: implement proper factories.
            bind(HttpServletRequest.class).toFactory(new Factory<HttpServletRequest>() {

                @Override
                public HttpServletRequest get() throws ComponentException {
                    return null;
                }
            });

            bind(HttpServletResponse.class).toFactory(new Factory<HttpServletResponse>() {

                @Override
                public HttpServletResponse get() throws ComponentException {
                    return null;
                }
            });

            bind(ServletContext.class).toFactory(new Factory<ServletContext>() {

                @Override
                public ServletContext get() throws ComponentException {
                    return null;
                }
            });

            bind(ServletConfig.class).toFactory(new Factory<ServletConfig>() {

                @Override
                public ServletConfig get() throws ComponentException {
                    return null;
                }
            });

            bind(WebConfig.class).toFactory(new Factory<WebConfig>() {

                @Override
                public WebConfig get() throws ComponentException {
                    return null;
                }
            });

            bind(FilterConfig.class).toFactory(new Factory<FilterConfig>() {

                @Override
                public FilterConfig get() throws ComponentException {
                    return null;
                }
            });

        }
    }
    //
    private Application application;

    public WebComponent(final WebConfig webConfig) throws ServletException {
        this.application = Application.builder(createResourceConfig(webConfig, new WebComponentModule())).build();
    }

    public WebComponent(final ResourceConfig resourceConfig) throws ServletException {
        this.application = Application.builder(resourceConfig).build();
        this.application.addModules(new WebComponentModule());
    }

    /**
     * Dispatch client requests to a resource class.
     *
     * @param baseUri    the base URI of the request.
     * @param requestUri the URI of the request.
     * @param request    the {@link javax.servlet.http.HttpServletRequest} object that
     *                   contains the request the client made to
     *                   the Web component.
     * @param response   the {@link javax.servlet.http.HttpServletResponse} object that
     *                   contains the response the Web component returns
     *                   to the client.
     * @return the status code of the response.
     * @throws java.io.IOException      if an input or output error occurs
     *                          while the Web component is handling the
     *                          HTTP request.
     * @throws javax.servlet.ServletException if the HTTP request cannot
     *                          be handled.
     */
    public int service(URI baseUri, URI requestUri,
            final HttpServletRequest request,
            final HttpServletResponse response)
            throws ServletException, IOException {

        Request.RequestBuilder requestBuilder = Requests.from(baseUri, requestUri, request.getMethod());
        requestBuilder = addRequestHeaders(request, requestBuilder);
        requestBuilder = requestBuilder.entity(request.getInputStream());
        Request jaxRsRequest = requestBuilder.build();

        try {
            final ResponseWriter containerContext = new ResponseWriter(false, request, response);
            application.apply(jaxRsRequest, containerContext);

            return containerContext.cResponse.getStatus();
        } catch (Exception e) {
            // TODO: proper error handling.
            throw new ServletException(e);
        }

    }

    private ResourceConfig createResourceConfig(WebConfig config, Module... modules) throws ServletException {
        final Map<String, Object> initParams = getInitParams(config);

        return createResourceConfig(config, initParams, modules);
    }

    private ResourceConfig createResourceConfig(WebConfig config, Map<String, Object> initParams, Module... modules) throws ServletException {
        // check if the JAX-RS application config class property is present
        String JaxrsApplicationClassName = config.getInitParameter(ServerProperties.JAXRS_APPLICATION_CLASS);

        // If no resource config class property is present
        if (JaxrsApplicationClassName == null) {
            return getDefaultResourceConfig(initParams, config, modules);
        }

        try {
            Class<? extends javax.ws.rs.core.Application> JaxrsApplicationClass = ReflectionHelper.classForNameWithException(JaxrsApplicationClassName);

//            // TODO add support for WebAppResourceConfig
//            if (JaxrsApplicationClass == ClassPathResourceConfig.class) {
//                String[] paths = getPaths(config.getInitParameter(
//                        ClassPathResourceConfig.PROVIDER_CLASSPATH), config.getServletContext());
//                initParams.put(ClassPathResourceConfig.PROVIDER_CLASSPATH, paths);
//                return new ClassPathResourceConfig(initParams);
//            } else if (ResourceConfig.class.isAssignableFrom(JaxrsApplicationClass)) {
//                try {
//                    Constructor constructor = JaxrsApplicationClass.getConstructor(Map.class);
//                    if (ClassPathResourceConfig.class.isAssignableFrom(JaxrsApplicationClass)) {
//                        String[] paths = getPaths(config.getInitParameter(
//                                ClassPathResourceConfig.PROVIDER_CLASSPATH), config.getServletContext());
//                        initParams.put(ClassPathResourceConfig.PROVIDER_CLASSPATH, paths);
//                    }
//                    return (ResourceConfig) constructor.newInstance(initParams);
//                } catch (NoSuchMethodException ex) {
//                    // Pass through and try the default constructor
//                } catch (Exception e) {
//                    throw new ServletException(e);
//                }
//
//                // TODO
////                return new DeferredResourceConfig(JaxrsApplicationClass.asSubclass(ResourceConfig.class));
//                return null;
//            } else
            if (javax.ws.rs.core.Application.class.isAssignableFrom(JaxrsApplicationClass)) {
                return ResourceConfig.builder(JaxrsApplicationClass).addProperties(initParams).build();
            }
            // TODO
//                return new DeferredResourceConfig(JaxrsApplicationClass.asSubclass(javax.ws.rs.core.Application.class));
            return null;
//            } else {
//                String message = "Resource configuration class, " + resourceConfigClassName +
//                        ", is not a super class of " + javax.ws.rs.core.Application.class;
//                throw new ServletException(message);
//            }

//            return null;
        } catch (ClassNotFoundException e) {
            String message = "Resource configuration class, " + JaxrsApplicationClassName
                    + ", could not be loaded";
            throw new ServletException(message, e);
        }
    }

    private Request.RequestBuilder addRequestHeaders(HttpServletRequest request, Request.RequestBuilder builder) {
        for (Enumeration<String> names = request.getHeaderNames(); names.hasMoreElements();) {
            String name = names.nextElement();
            List<String> valueList = new LinkedList<String>();
            for (Enumeration<String> values = request.getHeaders(name); values.hasMoreElements();) {
                builder = builder.header(name, values.nextElement());
            }
        }

        return builder;
    }

    private Map<String, Object> getInitParams(WebConfig webConfig) {
        Map<String, Object> props = new HashMap<String, Object>();
        Enumeration names = webConfig.getInitParameterNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            props.put(name, webConfig.getInitParameter(name));
        }
        return props;
    }

    private String[] getPaths(String classpath, ServletContext context) throws ServletException {
        if (classpath == null) {
            String[] paths = {
                context.getRealPath("/WEB-INF/lib"),
                context.getRealPath("/WEB-INF/classes")
            };
            if (paths[0] == null && paths[1] == null) {
//                String message = "The default deployment configuration that scans for " +
//                        "classes in /WEB-INF/lib and /WEB-INF/classes is not supported " +
//                        "for the application server." +
//                        "Try using the package scanning configuration, see the JavaDoc for " +
//                        PackagesResourceConfig.class.getName() + " and the property " +
//                        PackagesResourceConfig.PROVIDER_PACKAGES + ".";
//                throw new ServletException(message);
            }
            return paths;
        } else {
            String[] virtualPaths = classpath.split(";");
            List<String> resourcePaths = new ArrayList<String>();
            for (String virtualPath : virtualPaths) {
                virtualPath = virtualPath.trim();
                if (virtualPath.length() == 0) {
                    continue;
                }
                String path = context.getRealPath(virtualPath);
                if (path != null) {
                    resourcePaths.add(path);
                }
            }
            if (resourcePaths.isEmpty()) {
//                String message = "None of the declared classpath locations, " +
//                        classpath +
//                        ", could be resolved. " +
//                        "This could be because the default deployment configuration that scans for " +
//                        "classes in classpath locations is not supported. " +
//                        "Try using the package scanning configuration, see the JavaDoc for " +
//                        PackagesResourceConfig.class.getName() + " and the property " +
//                        PackagesResourceConfig.PROVIDER_PACKAGES + ".";
//                throw new ServletException(message);
            }
            return resourcePaths.toArray(new String[resourcePaths.size()]);
        }
    }

    /**
     * Get the default resource configuration if one is not declared in the
     * web.xml.
     * <p/>
     * This implementation returns an instance of {@link ResourceConfig}
     * that scans in files and directories as declared by the
     * {@link ResourceConfig#PROVIDER_CLASSPATH} if present, otherwise
     * in the "WEB-INF/lib" and "WEB-INF/classes" directories.
     * <p/>
     * An inheriting class may override this method to supply a different
     * default resource configuration implementation.
     *
     * @param props the properties to pass to the resource configuration.
     * @param wc    the web configuration.
     * @param modules modules to pass to the {@link Application} configuration.
     * @return the default resource configuration.
     * @throws javax.servlet.ServletException in case of any issues with providing \
     *                                        the default resource configuration
     */
    protected ResourceConfig getDefaultResourceConfig(Map<String, Object> props,
            WebConfig wc,
            Module... modules) throws ServletException {

        final ResourceConfig.Builder resourceConfigBuilder = ResourceConfig.builder();
        resourceConfigBuilder.addProperties(props);
        resourceConfigBuilder.addModules(modules);

        String packages = wc.getInitParameter(
                ServerProperties.PROVIDER_PACKAGES);
        if (packages != null) {
            resourceConfigBuilder.packages(packages);
        }

        String classpath = wc.getInitParameter(
                ServerProperties.PROVIDER_CLASSPATH);
        if (classpath != null) {
            resourceConfigBuilder.files(classpath);
        } else {
            resourceConfigBuilder.addFinder(new WebAppResourcesScanner(wc.getServletContext()));
        }

        return resourceConfigBuilder.build();
    }

// TODO is this needed or can be removed?
//    private ResourceConfig getWebAppResourceConfig(Map<String, Object> props,
//            WebConfig webConfig) throws ServletException {
//
//        return ResourceConfig.builder().addFinder(new WebAppResourcesScanner(webConfig.getServletContext())).build();
//    }
    private final static class ResponseWriter implements ContainerResponseWriter {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AtomicBoolean isSuspended;
        private volatile AsyncContext asyncContext;
        private final OutputStream out;
        private final boolean useSetStatusOn404;
        private Response cResponse;
        private long contentLength;
        private final AtomicBoolean statusAndHeadersWritten;

        ResponseWriter(final boolean useSetStatusOn404, final HttpServletRequest request, final HttpServletResponse response) {
            this.useSetStatusOn404 = useSetStatusOn404;
            this.request = request;
            this.response = response;
            this.out = new CommittingOutputStream() {

                @Override
                protected void commit() throws IOException {
                    ResponseWriter.this.writeStatusAndHeaders();
                }

                @Override
                protected OutputStream getOutputStream() throws IOException {
                    return ResponseWriter.this.response.getOutputStream();
                }
            };

            this.isSuspended = new AtomicBoolean(false);
            this.statusAndHeadersWritten = new AtomicBoolean(false);
            this.asyncContext = null;
        }

        @Override
        public boolean resume() {
            return isSuspended.compareAndSet(true, false);
        }

        @Override
        public void suspend(final long timeOut, final TimeUnit timeUnit, final TimeoutHandler timeoutHandler)
                throws IllegalStateException {
            asyncContext = request.startAsync(request, response);
            isSuspended.set(true);
            asyncContext.setTimeout(timeUnit.toMillis(timeOut));
            asyncContext.addListener(new AsyncListener() {

                @Override
                public void onComplete(AsyncEvent event) throws IOException {
                    // TODO ?
                }

                @Override
                public void onTimeout(AsyncEvent event) throws IOException {
                    timeoutHandler.onTimeout(ResponseWriter.this);
                }

                @Override
                public void onError(AsyncEvent event) throws IOException {
                    // TODO ?
                }

                @Override
                public void onStartAsync(AsyncEvent event) throws IOException {
                    // TODO ?
                }
            });
        }

        @Override
        public OutputStream writeResponseStatusAndHeaders(long contentLength, Response cResponse) throws ContainerException {
            this.contentLength = contentLength;
            this.cResponse = cResponse;
            this.statusAndHeadersWritten.set(false);
            return out;
        }

        @Override
        public void close() {
            if (!statusAndHeadersWritten.compareAndSet(false, true)) {
                return;
            }

            try {
                // Note that the writing of headers MUST be performed before
                // the invocation of sendError as on some Servlet implementations
                // modification of the response headers will have no effect
                // after the invocation of sendError.
                writeHeaders();

                final int status = cResponse.getStatus();
                if (status >= 400) {
                    if (useSetStatusOn404 && status == 404) {
                        response.setStatus(status);
                    } else {
                        final String reason = cResponse.getStatusEnum().getReasonPhrase();
                        try {
                            if (reason == null || reason.isEmpty()) {
                                response.sendError(status);
                            } else {
                                response.sendError(status, reason);
                            }
                        } catch (IOException ex) {
                            throw new ContainerException(
                                    "I/O exception occured while sending [" + status + "] error response.", ex);
                        }
                    }
                } else {
                    response.setStatus(status);
                }
            } finally {
                if (asyncContext != null) {
                    asyncContext.complete();
                }
            }
        }

        @Override
        public void cancel() {
            if (!response.isCommitted()) {
                try {
                    response.reset();
                } catch (IllegalStateException ex) {
                    // a race condition externally commiting the response can still occur...
                    LOGGER.log(Level.FINER, "Unable to reset cancelled response.", ex);
                }
            }
        }

        private void writeStatusAndHeaders() {
            if (!statusAndHeadersWritten.compareAndSet(false, true)) {
                return;
            }

            writeHeaders();
            response.setStatus(cResponse.getStatus());
        }

        private void writeHeaders() {
            if (contentLength != -1 && contentLength < Integer.MAX_VALUE) {
                response.setContentLength((int) contentLength);
            }

            MultivaluedMap<String, String> headers = cResponse.getHeaders().asMap();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                for (String v : e.getValue()) {
                    response.addHeader(e.getKey(), v);
                }
            }
        }
    }
}
