/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.rest;

import static java.lang.String.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.io.CharStreams;
import org.apache.iceberg.rest.HTTPRequest.HTTPMethod;
import org.apache.iceberg.rest.RESTCatalogAdapter.Route;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RESTCatalogServlet provides a servlet implementation used in combination with a
 * RESTCatalogAdaptor to proxy the REST Spec to any Catalog implementation.
 */
public class RESTCatalogServlet extends HttpServlet {
  private static final Logger LOG = LoggerFactory.getLogger(RESTCatalogServlet.class);

  private final RESTCatalogAdapter restCatalogAdapter;
  private final Map<String, String> responseHeaders =
      ImmutableMap.of(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());

  public RESTCatalogServlet(RESTCatalogAdapter restCatalogAdapter) {
    this.restCatalogAdapter = restCatalogAdapter;
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    execute(ServletRequestContext.from(request), response);
  }

  @Override
  protected void doHead(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    execute(ServletRequestContext.from(request), response);
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    execute(ServletRequestContext.from(request), response);
  }

  @Override
  protected void doDelete(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    execute(ServletRequestContext.from(request), response);
  }

  protected void execute(ServletRequestContext context, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    responseHeaders.forEach(response::setHeader);

    if (context.error().isPresent()) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      RESTObjectMapper.mapper().writeValue(response.getWriter(), context.error().get());
      return;
    }

    try {

      HTTPRequest request =
          restCatalogAdapter.buildRequest(
              context.method(),
              context.path(),
              context.queryParams(),
              context.headers(),
              context.body());
      // Zuoru: add log to print request body
      if (context.body() != null) {
        LOG.error("Zuoru: " + context.body().toString());
      }
      Object responseBody =
          restCatalogAdapter.execute(
              request, context.route().responseClass(), handle(response), h -> {});

      if (responseBody != null) {
        RESTObjectMapper.mapper().writeValue(response.getWriter(), responseBody);
      }
    } catch (RESTException e) {
      LOG.error("Error processing REST request", e);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } catch (Exception e) {
      LOG.error("Unexpected exception when processing REST request", e);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  protected Consumer<ErrorResponse> handle(HttpServletResponse response) {
    return (errorResponse) -> {
      response.setStatus(errorResponse.code());
      try {
        RESTObjectMapper.mapper().writeValue(response.getWriter(), errorResponse);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  public static class ServletRequestContext {
    private HTTPMethod method;
    private Route route;
    private String path;
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private Object body;

    private ErrorResponse errorResponse;

    private ServletRequestContext(ErrorResponse errorResponse) {
      this.errorResponse = errorResponse;
    }

    private ServletRequestContext(
        HTTPMethod method,
        Route route,
        String path,
        Map<String, String> headers,
        Map<String, String> queryParams,
        Object body) {
      this.method = method;
      this.route = route;
      this.path = path;
      this.headers = headers;
      this.queryParams = queryParams;
      this.body = body;
    }

    // Zuoru: add an inner class to cache servlet request
    public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
      private final byte[] cachedBody;

      public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
        super(request);
        this.cachedBody = cachedBody;
      }

      @Override
      public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(this.cachedBody);
      }

      @Override
      public BufferedReader getReader() throws UnsupportedEncodingException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        return new BufferedReader(
            new InputStreamReader(byteArrayInputStream, getCharacterEncoding()));
      }

      private static class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream byteArrayInputStream;

        CachedBodyServletInputStream(byte[] cachedBody) {
          this.byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        }

        @Override
        public int read() {
          return byteArrayInputStream.read();
        }

        @Override
        public boolean isFinished() {
          return byteArrayInputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          /* 不实现 */
        }
      }
    }

    // Zuoru: function to print JSON pretty
    private static void logPrettyJson(String jsonString) throws JsonProcessingException {
      if (!jsonString.isEmpty()) {
        ObjectMapper mapper = new ObjectMapper();
        Object jsonObject = mapper.readValue(jsonString, Object.class);
        String prettyJsonString =
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        LOG.error("Zuoru: format JSON:\n{}", prettyJsonString);
      }
    }

    // Zuoru: function to convert HttpServletRequest to JSON body
    private static HttpServletRequest cacheRequestJson(HttpServletRequest request) {
      StringBuilder jsonBuilder = new StringBuilder();
      byte[] cachedBody;

      try (BufferedReader reader = request.getReader()) {
        String line;
        while ((line = reader.readLine()) != null) {
          jsonBuilder.append(line);
        }
        // keep encoding same
        String jsonString = jsonBuilder.toString();
        cachedBody =
            jsonString.getBytes(
                request.getCharacterEncoding() != null
                    ? request.getCharacterEncoding()
                    : StandardCharsets.UTF_8.name());
        logPrettyJson(jsonString);
      } catch (IOException e) {
        // handle exception
        LOG.error("read request body failed", e);
        return request;
      }
      return new CachedBodyHttpServletRequest(request, cachedBody);
    }

    static ServletRequestContext from(HttpServletRequest request) throws IOException {
      HTTPMethod method = HTTPMethod.valueOf(request.getMethod());
      String path = request.getRequestURI().substring(1);
      Pair<Route, Map<String, String>> routeContext = Route.from(method, path);
      // Zuoru: add log for translate HttpServletRequest to ServletRequestContext
      LOG.error("Zuoru: method: {}, path: {}", method, path);
      HttpServletRequest cachedRequest = cacheRequestJson(request);

      if (routeContext == null) {
        return new ServletRequestContext(
            ErrorResponse.builder()
                .responseCode(400)
                .withType("BadRequestException")
                .withMessage(format("No route for request: %s %s", method, path))
                .build());
      }

      Route route = routeContext.first();
      Object requestBody = null;
      if (route.requestClass() != null) {
        requestBody =
            RESTObjectMapper.mapper().readValue(cachedRequest.getReader(), route.requestClass());
      } else if (route == Route.TOKENS) {
        try (Reader reader = new InputStreamReader(cachedRequest.getInputStream())) {
          requestBody = RESTUtil.decodeFormData(CharStreams.toString(reader));
        }
      }

      Map<String, String> queryParams =
          cachedRequest.getParameterMap().entrySet().stream()
              .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue()[0]));
      Map<String, String> headers =
          Collections.list(cachedRequest.getHeaderNames()).stream()
              .collect(Collectors.toMap(Function.identity(), cachedRequest::getHeader));

      return new ServletRequestContext(method, route, path, headers, queryParams, requestBody);
    }

    public HTTPMethod method() {
      return method;
    }

    public Route route() {
      return route;
    }

    public String path() {
      return path;
    }

    public Map<String, String> headers() {
      return headers;
    }

    public Map<String, String> queryParams() {
      return queryParams;
    }

    public Object body() {
      return body;
    }

    public Optional<ErrorResponse> error() {
      return Optional.ofNullable(errorResponse);
    }
  }
}
