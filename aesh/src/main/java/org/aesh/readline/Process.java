/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.aesh.readline;

import org.aesh.command.CommandResult;
import org.aesh.command.Execution;
import org.aesh.command.CommandException;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.parser.CommandLineParserException;
import org.aesh.command.validator.OptionValidatorException;
import org.aesh.terminal.tty.Signal;
import org.aesh.terminal.utils.Config;
import org.aesh.command.validator.CommandValidatorException;
import org.aesh.terminal.Connection;
import org.aesh.readline.util.LoggerUtil;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:stale.pedersen@jboss.org">Ståle W. Pedersen</a>
 */
public class Process extends Thread implements Consumer<Signal> {

    private final Connection conn;
    private final Execution<? extends CommandInvocation> execution;
    private final ProcessManager manager;
    private volatile boolean running;

    private static final Logger LOGGER = LoggerUtil.getLogger(Process.class.getName());
    private int pid;

    public Process(ProcessManager manager, Connection conn,
                   Execution<? extends CommandInvocation> execution) {
        this.manager = manager;
        this.conn = conn;
        this.execution = execution;
    }

    /**
     * Refactoring used: Replace Conditional with Polymorphism,
     * In this technique, I have created an interface 'SignalHandler' with method
     * 'handleSignal(). Then, we can create concrete classes for each signal, e.g. IntSignalHandler,  implementing the
     *  handleSignal() method for their respective signal.
     *  Finally, in the accept() method, we can create an instance of the appropriate signal handler based on the
     *  signal argument and call the handleSignal() method on it.
     */
    public interface SignalHandler {
        void handleSignal();
    }

    public class IntSignalHandler implements SignalHandler {
        private final boolean running;

        public IntSignalHandler(boolean running) {
            this.running = running;
        }

        @Override
        public void handleSignal() {
            if (running) {
                // Ctrl-C interrupt : we use Thread interrupts to signal the command to stop
                LOGGER.info("got interrupted in Task");
                Thread.currentThread().interrupt();
            }
        }
    }

// add other signal handlers for each signal


    /**
     * Refactoring method used: Replace Conditional with Polymorphism
     * reducing the code complexity by removing the long line of code in switch...
     * @param signal the input argument
     */
    public void accept(Signal signal) {
        SignalHandler handler;
        switch (signal) {
            case INT:
                handler = new IntSignalHandler(running);
                break;
            // add other cases for each signal
            default:
                // default handler
                handler = () -> {};
                break;
        }
        handler.handleSignal();
    }

    @Override
    public void run() {
        // Subscribe to events, in particular Ctrl-C
        Consumer<Signal> prev = conn.getSignalHandler();
        Consumer<int[]> prevIn = conn.getStdinHandler();
        conn.setSignalHandler(this);
        running = true;
        pid = (int) Thread.currentThread().getId();

        try {
            execution.execute();
        }
        catch (CommandValidatorException | CommandException  | OptionValidatorException | CommandLineParserException e ) {
            execution.setResut(CommandResult.FAILURE);
            conn.write(e.getMessage()+ Config.getLineSeparator());
        }
        catch (InterruptedException e) {
            // Ctlr-C interrupt
            execution.setResut(CommandResult.FAILURE);
        }
        catch (Exception e) {
            execution.setResut(CommandResult.FAILURE);
            conn.write(e.getMessage()+ Config.getLineSeparator());
            LOGGER.log(Level.WARNING, "Uncaught exception when executing the command: "+execution.getCommand().toString(), e);
        }
        finally {
            running = false;
            conn.setSignalHandler(prev);
            conn.setStdinHandler(prevIn);
            manager.processFinished(this);
        }
    }

    public Execution<? extends CommandInvocation> execution() {
        return execution;
    }

    public int pid() {
        return pid;
    }
}