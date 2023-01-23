/*
 * Copyright (c) 2021 Simon Johnson <simon622 AT gmail DOT com>
 *
 * Find me on GitHub:
 * https://github.com/simon622
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.slj.mqtt.sn.console.http.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slj.mqtt.sn.console.http.*;
import org.slj.mqtt.sn.console.http.impl.handlers.StaticFileHandler;
import org.slj.mqtt.sn.utils.Files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public abstract class AbstractHttpRequestResponseHandler implements IHttpRequestResponseHandler {
    protected final Logger logger =
            LoggerFactory.getLogger(getClass().getName());

    protected final ObjectMapper mapper;

    public AbstractHttpRequestResponseHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void handleRequest(IHttpRequestResponse httpRequestResponse) throws IOException {

        long start = System.currentTimeMillis();
        try {
            UsernamePassword credentials = getRequiredCredentials(httpRequestResponse);
            if(credentials != null){
                if(!handleBasicHttpAuthentication(credentials, httpRequestResponse)){
                    return;
                }
            }
            switch(httpRequestResponse.getMethod()){
                case GET:
                    handleHttpGet(httpRequestResponse);
                    break;
                case POST:
                    handleHttpPost(httpRequestResponse);
                    break;
                case HEAD:
                case PUT:
                case OPTIONS:
                case CONNECT:
                case TRACE:
                case PATCH:
                case DELETE:
                default:
                    sendUnsupportedOperationRequest(httpRequestResponse);
            }
        }
        catch(HttpException e){
            logger.warn("caught strong typed http exception, use code and message [{} -> {}]",
                    e.getResponseCode(), e.getResponseMessage());
            logger.error("handled error", e);
            try {
                writeASCIIResponse(httpRequestResponse, e.getResponseCode(), e.getResponseMessage());
            } catch (Exception ex){
                logger.error("error sending internal server error request!", ex);
            }
        }
        catch(Exception e){
            e.printStackTrace();
            logger.error("unhandled error", e);
            try {
                writeASCIIResponse(httpRequestResponse, HttpConstants.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            } catch (Exception ex){
                logger.error("error sending internal server error request!", ex);
            }
        } finally {
            logger.trace("request {} {} ({}) -> {} done in {}",
                    httpRequestResponse.getHttpRequestUri(), httpRequestResponse.getContextPath(),
                    httpRequestResponse.getContextRelativePath(), httpRequestResponse.getResponseCode(), System.currentTimeMillis() - start);
        }
    }

    protected void handleHttpGet(IHttpRequestResponse request) throws HttpException, IOException {
        sendNotFoundResponse(request);
    }

    protected void handleHttpPost(IHttpRequestResponse request) throws HttpException, IOException {
        sendNotFoundResponse(request);
    }

    protected void sendUnsupportedOperationRequest(IHttpRequestResponse request) throws IOException {
        writeASCIIResponse(request, HttpConstants.SC_METHOD_NOT_ALLOWED,
            Html.getErrorMessage(HttpConstants.SC_METHOD_NOT_ALLOWED, "Method not allowed"));
    }

    protected void sendNotFoundResponse(IHttpRequestResponse request) throws IOException {
        writeHTMLResponse(request, HttpConstants.SC_NOT_FOUND,
                Html.getErrorMessage(HttpConstants.SC_NOT_FOUND, "Resource Not found"));
    }

    protected void sendBadRequestResponse(IHttpRequestResponse request, String message) throws IOException {
        logger.info("resource not found {}", request);
        writeHTMLResponse(request, HttpConstants.SC_BAD_REQUEST,
                Html.getErrorMessage(HttpConstants.SC_BAD_REQUEST, message));
    }

    protected void sendRedirect(IHttpRequestResponse request, String resourceUri) throws IOException {
        try {
            logger.info("sending client side redirect to {}", resourceUri);
            request.addResponseHeader(HttpConstants.LOCATION_HEADER, resourceUri);
            request.sendResponseHeaders(HttpConstants.SC_MOVED_TEMPORARILY, 0);
        } finally {
            request.commit();
        }
    }

    protected void writeASCIIResponse(IHttpRequestResponse request, int responseCode, String message) throws IOException {
        writeResponseInternal(request, responseCode, HttpConstants.PLAIN_MIME_TYPE, message != null ? message.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    protected void writeHTMLResponse(IHttpRequestResponse request, int responseCode, String html) throws IOException {
        writeResponseInternal(request, responseCode, HttpConstants.HTML_MIME_TYPE, html != null ? html.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    protected void writeJSONResponse(IHttpRequestResponse request, int responseCode, byte[] bytes) throws IOException {
        writeResponseInternal(request, responseCode, HttpConstants.JSON_MIME_TYPE, bytes);
    }

    protected void writeJSONBeanResponse(IHttpRequestResponse request, int responseCode, Object bean) throws IOException {
        writeJSONResponse(request, responseCode, mapper.writeValueAsBytes(bean));
    }

    protected void writeMessageBeanResponse(IHttpRequestResponse request, int responseCode, Message message) throws IOException {
        writeJSONResponse(request, responseCode, mapper.writeValueAsBytes(message));
    }

    protected void writeStreamResponse(IHttpRequestResponse request, int responseCode, String mimeType, InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[1024];
        int length;
        while ((length = is.read(buf)) != -1) {
            baos.write(buf, 0, length);
        }
        byte[] bytes = baos.toByteArray();
        writeResponseInternal(request, responseCode, mimeType, bytes);
    }

    protected void writeResponseInternal(IHttpRequestResponse request, int responseCode, String mimeType, byte[] bytes) throws IOException {
        OutputStream os = null;
        try {
            request.setResponseContentType(mimeType, StandardCharsets.UTF_8);
            request.sendResponseHeaders(responseCode, bytes.length);
            os = request.getResponseBody();
            os.write(bytes);
        } finally {
            if(os != null) os.close();
            request.commit();
        }
    }

    protected void writeSimpleOKResponse(IHttpRequestResponse request) throws IOException {
        try {
            request.setResponseContentType(HttpConstants.PLAIN_MIME_TYPE, StandardCharsets.UTF_8);
            request.sendResponseHeaders(HttpConstants.SC_OK, 0);
        } finally {
            request.commit();
        }
    }

    protected void writeDataFromResource(IHttpRequestResponse requestResponse, String resourcePath) throws IOException {
        InputStream is = loadClasspathResource(resourcePath);
        if (is == null) {
            sendNotFoundResponse(requestResponse);
        } else {
            String fileName = Files.getFileName(resourcePath);
            String ext = Files.getFileExtension(resourcePath);
            String mimeType = HttpUtils.getMimeTypeFromFileExtension(ext);
            writeStreamResponse(requestResponse, HttpConstants.SC_OK, mimeType, is);
        }
    }

    protected <T> T readRequestBody(IHttpRequestResponse requestResponse, Class<T> cls) throws HttpInternalServerError{
        try {
            return mapper.readValue(requestResponse.getRequestBody(), cls);
        } catch(Exception e) {
            throw new HttpInternalServerError("error reading request body", e);
        }
    }

    protected InputStream loadClasspathResource(String resource) {
        logger.trace("loading resource from path " + resource);
        return StaticFileHandler.class.getClassLoader().getResourceAsStream(resource);
    }

    protected String getMandatoryParameter(IHttpRequestResponse requestResponse, String paramKey) throws HttpBadRequestException {
        String value = requestResponse.getParameter(paramKey);
        if(value == null){
            throw new HttpBadRequestException("mandatory request parameter not available " + paramKey);
        }
        return value;
    }

    protected String getParameter(IHttpRequestResponse requestResponse, String paramKey) {
        String value = requestResponse.getParameter(paramKey);
        return value;
    }

    protected boolean handleBasicHttpAuthentication(UsernamePassword usernamePassword, IHttpRequestResponse httpRequestResponse) throws IOException {

        String value = httpRequestResponse.getRequestHeader(HttpConstants.BASIC_AUTH_HEADER);
        if(value != null){
            value = value.substring(value.lastIndexOf(" ") + 1);
            value = new String(Base64.getDecoder().decode(value));
            String[] userNamePassword = value.split(":");
            if(usernamePassword.getUserName().equals(userNamePassword[0]) &&
                    usernamePassword.getPassword().equals(userNamePassword[1])){
                return true;
            }
        }

        httpRequestResponse.addResponseHeader(HttpConstants.BASIC_AUTH_CHALLENGE_HEADER,
                String.format(HttpConstants.BASIC_AUTH_REALM, usernamePassword.getRealm()));
        writeResponseInternal(httpRequestResponse, HttpConstants.SC_UNAUTHORIZED, HttpConstants.HTML_MIME_TYPE, new byte[0]);
        return false;
    }

    protected UsernamePassword getRequiredCredentials(IHttpRequestResponse request){
        return null;
    }

    public static class Message {

        public String title;
        public String message;
        public boolean success;

        public Message() {

        }

        public Message(String message) {
            this.message = message;
            this.success = true;
        }

        public Message(String title, String message) {
            this.message = message;
            this.title = title;
            this.success = true;
        }

        public Message(String message, boolean success) {
            this.message = message;
            this.success = success;
        }

        public Message(String title, String message, boolean success) {
            this.title = title;
            this.message = message;
            this.success = success;
        }
    }
}

