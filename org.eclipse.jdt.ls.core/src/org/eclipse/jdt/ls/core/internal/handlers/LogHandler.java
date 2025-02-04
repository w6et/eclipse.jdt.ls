/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.function.Predicate;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.managers.TelemetryEvent;
import org.eclipse.lsp4j.MessageType;

import com.google.gson.JsonObject;

/**
 * The LogHandler hooks in the Eclipse log and forwards all Eclipse log messages to
 * the the client. In VSCode you can see all the messages in the Output view, in the
 * 'Java Language Support' channel.
 */
public class LogHandler {

	/**
	 * The filter that decide whether an Eclipse log message gets forwarded to the client
	 * via {@link org.eclipse.lsp4j.services.LanguageClient#logMessage(org.eclipse.lsp4j.MessageParams)}
	 * <p>Clients who load the LS in same process can override the default log handler.
	 * This usually needs to be done very early, before the language server starts.</p>
	 */
	public static Predicate<IStatus> defaultLogFilter = new DefaultLogFilter();
	private static final String JAVA_ERROR_LOG = "java.ls.error";

	private ILogListener logListener;
	private DateFormat dateFormat;
	private int logLevelMask;
	private JavaClientConnection connection;
	private Predicate<IStatus> filter;

	/**
	 * Equivalent to <code>LogHandler(defaultLogFilter)</code>.
	 */
	public LogHandler() {
		this(defaultLogFilter);
	}

	public LogHandler(Predicate<IStatus> filter) {
		this.filter = filter;
	}

	public void install(JavaClientConnection rcpConnection) {
		this.dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
		this.logLevelMask = getLogLevelMask(System.getProperty("log.level", ""));//Empty by default
		this.connection = rcpConnection;

		this.logListener = new ILogListener() {
			@Override
			public void logging(IStatus status, String bundleId) {
				processLogMessage(status);
			}
		};
		Platform.addLogListener(this.logListener);
	}

	public void uninstall() {
		Platform.removeLogListener(this.logListener);
	}

	private int getLogLevelMask(String logLevel) {
		switch (logLevel) {
		case "ALL":
			return -1;
		case "ERROR":
			return IStatus.ERROR;
		case "INFO":
			return IStatus.ERROR | IStatus.WARNING | IStatus.INFO;
		case "WARNING":
		default:
			return IStatus.ERROR | IStatus.WARNING;

		}
	}

	private void processLogMessage(IStatus status) {
		if ((filter != null && !filter.test(status)) || !status.matches(this.logLevelMask)) {
			//no op;
			return;
		}
		String dateString = this.dateFormat.format(new Date());
		String message = status.getMessage();
		if (status.getException() != null) {
			message = message + '\n' + status.getException().getMessage();
			StringWriter sw = new StringWriter();
			status.getException().printStackTrace(new PrintWriter(sw));
			String exceptionAsString = sw.toString();
			message = message + '\n' + exceptionAsString;
		}

		connection.logMessage(getMessageTypeFromSeverity(status.getSeverity()), dateString + ' ' + message);
		// Send a trace event to client
		if (status.getSeverity() == IStatus.ERROR) {
			JsonObject properties = new JsonObject();
			properties.addProperty("message", redact(status.getMessage()));
			if (status.getException() != null) {
				properties.addProperty("exception", message);
			}
			connection.telemetryEvent(new TelemetryEvent(JAVA_ERROR_LOG, properties));
		}
	}

	private String redact(String message) {
		if (message == null) {
			return null;
		}

		if (message.startsWith("Error occured while building workspace.")) {
			return "Error occured while building workspace.";
		}

		return message;
	}

	private MessageType getMessageTypeFromSeverity(int severity) {
		switch (severity) {
		case IStatus.ERROR:
			return MessageType.Error;
		case IStatus.WARNING:
			return MessageType.Warning;
		case IStatus.INFO:
			return MessageType.Info;
		default:
			return MessageType.Log;
		}
	}

}
