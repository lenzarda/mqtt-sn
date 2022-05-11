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

package org.slj.mqtt.sn.gateway.impl.connector;

import org.slj.mqtt.sn.gateway.spi.broker.IMqttsnBackendConnectionFactory;
import org.slj.mqtt.sn.gateway.spi.broker.MqttsnBackendException;
import org.slj.mqtt.sn.gateway.spi.broker.MqttsnBackendOptions;

public class LoopbackMqttsnBrokerConnectionFactory implements IMqttsnBackendConnectionFactory<LoopbackMqttsnBrokerConnection> {

    @Override
    public LoopbackMqttsnBrokerConnection createConnection(MqttsnBackendOptions options, String clientId) throws MqttsnBackendException {
        try {
            LoopbackMqttsnBrokerConnection connection = new LoopbackMqttsnBrokerConnection(options, clientId);
            if(options.getConnectOnStartup()) connection.connect();
            return connection;
        } catch(Exception e){
            throw new MqttsnBackendException("error creating connection;", e);
        }
    }
}