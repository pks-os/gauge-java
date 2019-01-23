// Copyright 2015 ThoughtWorks, Inc.

// This file is part of Gauge-Java.

// This program is free software.
//
// It is dual-licensed under:
// 1) the GNU General Public License as published by the Free Software Foundation,
// either version 3 of the License, or (at your option) any later version;
// or
// 2) the Eclipse Public License v1.0.
//
// You can redistribute it and/or modify it under the terms of either license.
// We would then provide copied of each license in a separate .txt file with the name of the license as the title of the file.
package com.thoughtworks.gauge.connection;


import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.thoughtworks.gauge.ClassInstanceManager;
import com.thoughtworks.gauge.Gauge;
import com.thoughtworks.gauge.datastore.DataStoreInitializer;
import com.thoughtworks.gauge.execution.parameters.parsers.base.ParameterParsingChain;
import com.thoughtworks.gauge.processor.IMessageProcessor;
import com.thoughtworks.gauge.processor.SpecExecutionStartingProcessor;
import com.thoughtworks.gauge.processor.SuiteExecutionEndingProcessor;
import com.thoughtworks.gauge.processor.SuiteExecutionStartingProcessor;
import com.thoughtworks.gauge.processor.ScenarioExecutionEndingProcessor;
import com.thoughtworks.gauge.processor.ScenarioExecutionStartingProcessor;
import com.thoughtworks.gauge.processor.SpecExecutionEndingProcessor;
import com.thoughtworks.gauge.processor.StepExecutionStartingProcessor;
import com.thoughtworks.gauge.processor.ExecuteStepProcessor;
import com.thoughtworks.gauge.processor.StepExecutionEndingProcessor;
import com.thoughtworks.gauge.processor.CacheFileRequestProcessor;
import com.thoughtworks.gauge.processor.StepNamesRequestProcessor;
import com.thoughtworks.gauge.processor.ValidateStepProcessor;
import com.thoughtworks.gauge.processor.KillProcessProcessor;
import com.thoughtworks.gauge.processor.RefactorRequestProcessor;
import com.thoughtworks.gauge.processor.StepNameRequestProcessor;
import com.thoughtworks.gauge.processor.StepPositionsRequestProcessor;
import com.thoughtworks.gauge.processor.DefaultMessageProcessor;
import com.thoughtworks.gauge.registry.ClassInitializerRegistry;
import com.thoughtworks.gauge.registry.StepRegistry;
import com.thoughtworks.gauge.scan.ClasspathScanner;
import com.thoughtworks.gauge.scan.CustomClassInitializerScanner;
import com.thoughtworks.gauge.scan.HooksScanner;
import com.thoughtworks.gauge.scan.StaticScanner;
import com.thoughtworks.gauge.scan.StepsScanner;
import com.thoughtworks.gauge.screenshot.CustomScreenshotScanner;
import gauge.messages.Messages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.HashMap;

/**
 * Receives messages from gauge core and processes them using the relevant MessageProcessor and returns a
 * valid response.
 */
public class MessageDispatcher {

    private HashMap<Messages.Message.MessageType, IMessageProcessor> messageProcessors;
    private StepRegistry stepRegistry;
    private final ClassInstanceManager instanceManager = new ClassInstanceManager(ClassInitializerRegistry.classInitializer());
    private StaticScanner staticScanner;

    public MessageDispatcher(StaticScanner staticScanner) {
        this.staticScanner = staticScanner;
        stepRegistry = staticScanner.getRegistry();
        messageProcessors = initializeMessageProcessor();
    }

    public void dispatchMessages(GaugeConnector connector) throws IOException {
        Socket gaugeSocket = connector.getGaugeSocket();
        InputStream inputStream = gaugeSocket.getInputStream();
        while (isConnected(gaugeSocket)) {
            try {
                MessageLength messageLength = getMessageLength(inputStream);
                byte[] bytes = toBytes(messageLength);
                Messages.Message message = Messages.Message.parseFrom(bytes);
                IMessageProcessor messageProcessor;
                if (message.getMessageType() == Messages.Message.MessageType.SuiteDataStoreInit) {
                    messageProcessor = getProcessor(message.getMessageType(), connector);
                } else {
                    messageProcessor = getProcessor(message.getMessageType());
                }
                if (!messageProcessors.containsKey(message.getMessageType())) {
                    System.err.println("Invalid message type received " + message.getMessageType());
                }
                Messages.Message response = messageProcessor.process(message);
                writeMessage(gaugeSocket, response);
                if (message.getMessageType() == Messages.Message.MessageType.KillProcessRequest) {
                    gaugeSocket.close();
                    return;
                }
            } catch (InvalidProtocolBufferException e) {
                return;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                System.err.println(throwable.toString());
                return;
            }
        }
    }

    private IMessageProcessor getProcessor(Messages.Message.MessageType request, GaugeConnector connector) {
        if (request == Messages.Message.MessageType.SuiteDataStoreInit) {
            ClasspathScanner classpathScanner = new ClasspathScanner();
            classpathScanner.scan(new StepsScanner(connector, stepRegistry), new HooksScanner(), new CustomScreenshotScanner(), new CustomClassInitializerScanner());
            this.stepRegistry = staticScanner.getStepRegistry(classpathScanner);
            Gauge.setInstanceManager(instanceManager);
            initializeExecutionMessageProcessor();
        }
        return getProcessor(request);
    }

    public IMessageProcessor getProcessor(Messages.Message.MessageType request) {
        if (messageProcessors.containsKey(request)) {
            return messageProcessors.get(request);
        }
        return new DefaultMessageProcessor();
    }

    private void initializeExecutionMessageProcessor() {
        ParameterParsingChain chain = new ParameterParsingChain();
        messageProcessors.put(Messages.Message.MessageType.ExecutionStarting, new SuiteExecutionStartingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.ExecutionEnding, new SuiteExecutionEndingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.SpecExecutionStarting, new SpecExecutionStartingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.SpecExecutionEnding, new SpecExecutionEndingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.ScenarioExecutionStarting, new ScenarioExecutionStartingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.ScenarioExecutionEnding, new ScenarioExecutionEndingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.StepExecutionStarting, new StepExecutionStartingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.StepExecutionEnding, new StepExecutionEndingProcessor(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.ExecuteStep, new ExecuteStepProcessor(instanceManager, chain, stepRegistry));
        messageProcessors.put(Messages.Message.MessageType.SuiteDataStoreInit, new DataStoreInitializer(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.SpecDataStoreInit, new DataStoreInitializer(instanceManager));
        messageProcessors.put(Messages.Message.MessageType.ScenarioDataStoreInit, new DataStoreInitializer(instanceManager));
    }

    private HashMap<Messages.Message.MessageType, IMessageProcessor> initializeMessageProcessor() {
        return new HashMap<Messages.Message.MessageType, IMessageProcessor>() {{
            put(Messages.Message.MessageType.StepNameRequest, new StepNameRequestProcessor(stepRegistry));
            put(Messages.Message.MessageType.StepNamesRequest, new StepNamesRequestProcessor(stepRegistry));
            put(Messages.Message.MessageType.RefactorRequest, new RefactorRequestProcessor(instanceManager, stepRegistry));
            put(Messages.Message.MessageType.CacheFileRequest, new CacheFileRequestProcessor(staticScanner));
            put(Messages.Message.MessageType.StepPositionsRequest, new StepPositionsRequestProcessor(stepRegistry));
            put(Messages.Message.MessageType.StepValidateRequest, new ValidateStepProcessor(stepRegistry));
            put(Messages.Message.MessageType.StubImplementationCodeRequest, new StubImplementationCodeProcessor());
            put(Messages.Message.MessageType.KillProcessRequest, new KillProcessProcessor(instanceManager));
        }};
    }

    private MessageLength getMessageLength(InputStream is) throws IOException {
        CodedInputStream codedInputStream = CodedInputStream.newInstance(is);
        long size = codedInputStream.readRawVarint64();
        return new MessageLength(size, codedInputStream);
    }

    private byte[] toBytes(MessageLength messageLength) throws IOException {
        long messageSize = messageLength.getLength();
        CodedInputStream stream = messageLength.getRemainingStream();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (int i = 0; i < messageSize; i++) {
            outputStream.write(stream.readRawByte());
        }

        return outputStream.toByteArray();
    }

    private void writeMessage(Socket socket, Messages.Message message) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(stream);
        byte[] bytes = message.toByteArray();
        cos.writeRawVarint64(bytes.length);
        cos.flush();
        stream.write(bytes);
        socket.getOutputStream().write(stream.toByteArray());
        socket.getOutputStream().flush();
    }

    private boolean isConnected(Socket socket) {
        return !socket.isClosed() && socket.isConnected();
    }
}
